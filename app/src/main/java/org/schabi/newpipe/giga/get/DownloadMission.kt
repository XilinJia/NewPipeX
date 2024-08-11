package org.schabi.newpipe.giga.get

import android.os.Handler
import android.system.ErrnoException
import android.system.OsConstants
import android.text.TextUtils
import android.util.Log
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.giga.io.SharpStream
import org.schabi.newpipe.giga.io.StoredFileHelper
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.giga.postprocessing.Postprocessing
import org.schabi.newpipe.giga.service.DownloadManagerService
import org.schabi.newpipe.giga.util.Utility
import java.io.*
import java.net.*
import java.nio.channels.ClosedByInterruptException
import java.util.*
import javax.net.ssl.SSLException
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min

class DownloadMission(urls: Array<String?>, storage: StoredFileHelper?, kind: Char, psInstance: Postprocessing?) : Mission() {
    /**
     * The urls of the file to download
     */
    @JvmField
    var urls: Array<String?>

    /**
     * Number of bytes downloaded and written
     */
    @JvmField
    @Volatile
    var done: Long = 0

    /**
     * Indicates a file generated dynamically on the web server
     */
    @JvmField
    var unknownLength: Boolean = false

    /**
     * offset in the file where the data should be written
     */
    @JvmField
    var offsets: LongArray

    /**
     * Indicates if the post-processing state:
     * 0: ready
     * 1: running
     * 2: completed
     * 3: hold
     */
    @JvmField
    @Volatile
    var psState: Int = 0

    /**
     * the post-processing algorithm instance
     */
    @JvmField
    var psAlgorithm: Postprocessing?

    /**
     * The current resource to download, `urls[current]` and `offsets[current]`
     */
    var current: Int = 0

    /**
     * Metadata where the mission state is saved
     */
    @JvmField
    @Transient
    var metadata: File? = null

    /**
     * maximum attempts
     */
    @JvmField
    @Transient
    var maxRetry: Int

    /**
     * Approximated final length, this represent the sum of all resources sizes
     */
    @JvmField
    var nearLength: Long = 0

    /**
     * Download blocks, the size is multiple of [DownloadMission.BLOCK_SIZE].
     * Every entry (block) in this array holds an offset, used to resume the download.
     * An block offset can be -1 if the block was downloaded successfully.
     */
    var blocks: IntArray? = null

    /**
     * Download/File resume offset in fallback mode (if applicable) [DownloadRunnableFallback]
     */
    @Volatile
    var fallbackResumeOffset: Long = 0

    /**
     * Maximum of download threads running, chosen by the user
     */
    @JvmField
    var threadCount: Int = 3

    /**
     * information required to recover a download
     */
    @JvmField
    var recoveryInfo: Array<MissionRecoveryInfo>? = null

    @Transient
    private var finishCount = 0

    @JvmField
    @Volatile
    @Transient
    var running: Boolean = false
    @JvmField
    var enqueued: Boolean

    @JvmField
    var errCode: Int = ERROR_NOTHING
    @JvmField
    var errObject: Exception? = null

    @JvmField
    @Transient
    var mHandler: Handler? = null

    @Transient
    private var blockAcquired: BooleanArray? = null

    @Transient
    private var writingToFileNext: Long = 0

    @Volatile
    @Transient
    private var writingToFile = false

    val LOCK: Any = Lock()

    @JvmField
    @Transient
    var threads: Array<Thread?> = arrayOfNulls(0)

    @JvmField
    @Transient
    var init: Thread? = null

    /**
     * Indicates if the download if fully finished
     * @return true, otherwise, false
     */
    val isFinished: Boolean
        get() = current >= urls.size && (psAlgorithm == null || psState == 2)

    /**
     * Indicates if the download file is corrupt due a failed post-processing
     * @return `true` if this mission is unrecoverable
     */
    val isPsFailed: Boolean
        get() {
            when (errCode) {
                ERROR_POSTPROCESSING, ERROR_POSTPROCESSING_STOPPED -> return psAlgorithm!!.worksOnSameFile
            }
            return false
        }

    /**
     * Indicates if a post-processing algorithm is running
     * @return true, otherwise, false
     */
    val isPsRunning: Boolean
        get() = psAlgorithm != null && (psState == 1 || psState == 3)

    /**
     * Indicated if the mission is ready
     * @return true, otherwise, false
     */
    val isInitialized: Boolean
        get() = blocks != null // DownloadMissionInitializer was executed

    /**
     * Indicates whatever is possible to start the mission
     * @return `true` is this mission its "healthy", otherwise, `false`
     */
    val isCorrupt: Boolean
        get() {
            if (urls.isEmpty()) return false
            return (isPsFailed || errCode == ERROR_POSTPROCESSING_HOLD) || this.isFinished
        }

    /**
     * Indicates if mission urls has expired and there an attempt to renovate them
     * @return `true` if the mission is running a recovery procedure, otherwise, `false`
     */
    val isRecovering: Boolean
        get() = threads.isNotEmpty() && threads[0] is DownloadMissionRecover && threads[0]!!.isAlive

    /**
     * Gets the approximated final length of the file
     * @return the length in bytes
     */
    override var length: Long = 0L
        get() {
            if (psState == 1 || psState == 3) return field
            var calculated = offsets[if (current < offsets.size) current else offsets.size - 1] + field
            calculated -= offsets[0] // don't count reserved space
            return max(calculated, nearLength)
        }

    init {
        require(Objects.requireNonNull(urls).isNotEmpty()) { "urls array is empty" }
        this.urls = urls
        this.kind = kind
        this.offsets = LongArray(urls.size)
        this.enqueued = true
        this.maxRetry = 3
        this.storage = storage
        this.psAlgorithm = psInstance

        if (BuildConfig.DEBUG && psInstance == null && urls.size > 1) {
            Log.w(TAG, "mission created with multiple urls ¿missing post-processing algorithm?")
        }
    }

    /**
     * Acquire a block
     * @return the block or `null` if no more blocks left
     */
    fun acquireBlock(): Block? {
        synchronized(LOCK) {
            for (i in blockAcquired!!.indices) {
                if (!blockAcquired!![i] && blocks!![i] >= 0) {
                    val block = Block()
                    block.position = i
                    block.done = blocks!![i]
                    blockAcquired!![i] = true
                    return block
                }
            }
        }

        return null
    }

    /**
     * Release an block
     * @param position the index of the block
     * @param done     amount of bytes downloaded
     */
    fun releaseBlock(position: Int, done: Int) {
        synchronized(LOCK) {
            blockAcquired!![position] = false
            blocks!![position] = done
        }
    }

    /**
     * Opens a connection
     * @param headRequest `true` for use `HEAD` request method, otherwise, `GET` is used
     * @param rangeStart  range start
     * @param rangeEnd    range end
     * @return a [URLConnection][java.net.URLConnection] linking to the URL.
     * @throws IOException if an I/O exception occurs.
     */
    @Throws(IOException::class)
    fun openConnection(headRequest: Boolean, rangeStart: Long, rangeEnd: Long): HttpURLConnection {
        return openConnection(urls[current], headRequest, rangeStart, rangeEnd)
    }

    @Throws(IOException::class)
    fun openConnection(url: String?, headRequest: Boolean, rangeStart: Long, rangeEnd: Long): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", DownloaderImpl.USER_AGENT)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Encoding", "*")

        if (headRequest) conn.requestMethod = "HEAD"

        // BUG workaround: switching between networks can freeze the download forever
        conn.connectTimeout = 30000

        if (rangeStart >= 0) {
            var req = "bytes=$rangeStart-"
            if (rangeEnd > 0) req += rangeEnd
            conn.setRequestProperty("Range", req)
        }

        return conn
    }

    /**
     * @param threadId id of the calling thread
     * @param conn     Opens and establish the communication
     * @throws IOException if an error occurred connecting to the server.
     * @throws HttpError   if the HTTP Status-Code is not satisfiable
     */
    @Throws(IOException::class, HttpError::class)
    fun establishConnection(threadId: Int, conn: HttpURLConnection) {
        val statusCode = conn.responseCode
        Logd(TAG, "$threadId:[request]  Range=${conn.getRequestProperty("Range")}")
        Logd(TAG, "$threadId:[response] Code=$statusCode")
        Logd(TAG, "$threadId:[response] Content-Length=${conn.contentLength}")
        Logd(TAG, "$threadId:[response] Content-Range=${conn.getHeaderField("Content-Range")}")

        when (statusCode) {
            204, 205, 207 -> throw HttpError(statusCode)
            416 -> return  // let the download thread handle this error
            else -> if (statusCode < 200 || statusCode > 299) throw HttpError(statusCode)
        }
    }

    private fun notify(what: Int) {
        mHandler?.obtainMessage(what, this)?.sendToTarget()
    }

    @Synchronized
    fun notifyProgress(deltaLen: Long) {
        if (unknownLength) length += deltaLen // Update length before proceeding
        done += deltaLen
        if (metadata == null) return
        if (!writingToFile && (done > writingToFileNext || deltaLen < 0)) {
            writingToFile = true
            writingToFileNext = done + BLOCK_SIZE
            writeThisToFileAsync()
        }
    }

    @Synchronized
    fun notifyError(err: Exception?) {
        Log.e(TAG, "notifyError()", err)
        when (err) {
            is FileNotFoundException -> notifyError(ERROR_FILE_CREATION, null)
            is SSLException -> notifyError(ERROR_SSL_EXCEPTION, null)
            is HttpError -> notifyError(err.statusCode, null)
            is ConnectException -> notifyError(ERROR_CONNECT_HOST, null)
            is UnknownHostException -> notifyError(ERROR_UNKNOWN_HOST, null)
            is SocketTimeoutException -> notifyError(ERROR_TIMEOUT, null)
            else -> notifyError(ERROR_UNKNOWN_EXCEPTION, err)
        }
    }

    @Synchronized
    fun notifyError(code: Int, err: Exception?) {
        var code = code
        var err = err
        Log.e(TAG, "notifyError() code = $code", err)
        if (err != null && err.cause is ErrnoException) {
            val errno = (err.cause as? ErrnoException)?.errno
            when (errno) {
                OsConstants.ENOSPC -> {
                    code = ERROR_INSUFFICIENT_STORAGE
                    err = null
                }
                OsConstants.EACCES -> {
                    code = ERROR_PERMISSION_DENIED
                    err = null
                }
            }
        }

        if (err is IOException) {
            when {
                err.message!!.contains("Permission denied") -> {
                    code = ERROR_PERMISSION_DENIED
                    err = null
                }
                err.message!!.contains("ENOSPC") -> {
                    code = ERROR_INSUFFICIENT_STORAGE
                    err = null
                }
                storage?.canWrite() != true -> {
                    code = ERROR_FILE_CREATION
                    err = null
                }
            }
        }

        errCode = code
        errObject = err

        when (code) {
            ERROR_SSL_EXCEPTION, ERROR_UNKNOWN_HOST, ERROR_CONNECT_HOST, ERROR_TIMEOUT -> {}
            // also checks for server errors
            else -> if (code < 500 || code > 599) enqueued = false
        }
        notify(DownloadManagerService.MESSAGE_ERROR)

        if (running) pauseThreads()
    }

    @Synchronized
    fun notifyFinished() {
        if (current < urls.size) {
            if (++finishCount < threads.size) return
            Logd(TAG, "onFinish: downloaded " + (current + 1) + "/" + urls.size)

            current++
            if (current < urls.size) {
                // prepare next sub-mission
                offsets[current] = offsets[current - 1] + length
                initializer()
                return
            }
        }

        if (psAlgorithm != null && psState == 0) {
            threads = arrayOf(runAsync(1) { this.doPostprocessing() })
            return
        }

        // this mission is fully finished
        unknownLength = false
        enqueued = false
        running = false

        deleteThisFromFile()
        notify(DownloadManagerService.MESSAGE_FINISHED)
    }

    private fun notifyPostProcessing(state: Int) {
        val action = when (state) {
            1 -> "Running"
            2 -> "Completed"
            else -> "Failed"
        }
        Logd(TAG, "$action postprocessing on ${storage?.name}")
        if (state == 2) {
            psState = state
            return
        }
        synchronized(LOCK) {
            // don't return without fully write the current state
            psState = state
            writeThisToFile()
        }
    }

    /**
     * Start downloading with multiple threads.
     */
    fun start() {
        if (running || isFinished || urls.isEmpty()) return
        // ensure that the previous state is completely paused.
        joinForThreads(10000)
        running = true
        errCode = ERROR_NOTHING
        if (hasInvalidStorage()) {
            notifyError(ERROR_FILE_CREATION, null)
            return
        }
        if (current >= urls.size) {
            notifyFinished()
            return
        }
        notify(DownloadManagerService.MESSAGE_RUNNING)
        if (urls[current] == null) {
            doRecover(ERROR_RESOURCE_GONE)
            return
        }
        if (blocks == null) {
            initializer()
            return
        }
        init = null
        finishCount = 0
        blockAcquired = BooleanArray(blocks!!.size)

        if (blocks!!.isEmpty()) threads = arrayOf(runAsync(1, DownloadRunnableFallback(this)))
        else {
            var remainingBlocks = 0
            for (block in blocks!!) if (block >= 0) remainingBlocks++
            if (remainingBlocks < 1) {
                notifyFinished()
                return
            }
            threads = arrayOfNulls(min(threadCount, remainingBlocks))
            for (i in threads.indices) {
                threads[i] = runAsync(i + 1, DownloadRunnable(this, i))
            }
        }
    }

    /**
     * Pause the mission
     */
    fun pause() {
        if (!running) return

        if (isPsRunning) {
            if (BuildConfig.DEBUG) Log.w(TAG, "pause during post-processing is not applicable.")
            return
        }

        running = false
        notify(DownloadManagerService.MESSAGE_PAUSED)

        if (init?.isAlive == true) {
            // NOTE: if start() method is running ¡will no have effect!
            init!!.interrupt()
            synchronized(LOCK) {
                resetState(rollback = false, persistChanges = true, errorCode = ERROR_NOTHING)
            }
            return
        }

        if (BuildConfig.DEBUG && unknownLength) Log.w(TAG, "pausing a download that can not be resumed (range requests not allowed by the server).")

        init = null
        pauseThreads()
    }

    private fun pauseThreads() {
        running = false
        joinForThreads(-1)
        writeThisToFile()
    }

    /**
     * Removes the downloaded file and the meta file
     */
    override fun delete(): Boolean {
        psAlgorithm?.cleanupTemporalDir()
        notify(DownloadManagerService.MESSAGE_DELETED)
        val res = deleteThisFromFile()
        if (!super.delete()) return false
        return res
    }

    /**
     * Resets the mission state
     *
     * @param rollback       `true` true to forget all progress, otherwise, `false`
     * @param persistChanges `true` to commit changes to the metadata file, otherwise, `false`
     */
    fun resetState(rollback: Boolean, persistChanges: Boolean, errorCode: Int) {
        length = 0
        errCode = errorCode
        errObject = null
        unknownLength = false
        threads = arrayOfNulls(0)
        fallbackResumeOffset = 0
        blocks = null
        blockAcquired = null

        if (rollback) current = 0
        if (persistChanges) writeThisToFile()
    }

    private fun initializer() {
        init = runAsync(DownloadInitializer.mId, DownloadInitializer(this))
    }

    private fun writeThisToFileAsync() {
        runAsync(-2) { this.writeThisToFile() }
    }

    /**
     * Write this [DownloadMission] to the meta file asynchronously
     * if no thread is already running.
     */
    fun writeThisToFile() {
        synchronized(LOCK) {
            if (metadata == null) return
            Utility.writeToFile(metadata!!, this)
            writingToFile = false
        }
    }

    /**
     * set this mission state on the queue
     * @param queue true to add to the queue, otherwise, false
     */
    fun setEnqueued(queue: Boolean) {
        enqueued = queue
        writeThisToFileAsync()
    }

    /**
     * Attempts to continue a blocked post-processing
     *
     * @param recover `true` to retry, otherwise, `false` to cancel
     */
    fun psContinue(recover: Boolean) {
        psState = 1
        errCode = if (recover) ERROR_NOTHING else ERROR_POSTPROCESSING
        threads[0]!!.interrupt()
    }

    /**
     * Indicates whatever the backed storage is invalid
     * @return `true`, if storage is invalid and cannot be used
     */
    fun hasInvalidStorage(): Boolean {
        return errCode == ERROR_PROGRESS_LOST || storage == null || !storage!!.existsAsFile()
    }

    private fun doPostprocessing() {
        errCode = ERROR_NOTHING
        errObject = null
        val thread = Thread.currentThread()
        notifyPostProcessing(1)
        if (BuildConfig.DEBUG) thread.name = "[$TAG]  ps = $psAlgorithm  filename = ${storage?.name}"

        var exception: Exception? = null
        try {
            psAlgorithm?.run(this)
        } catch (err: Exception) {
            Log.e(TAG, "Post-processing failed. ${psAlgorithm.toString()}", err)
            if (err is InterruptedIOException || err is ClosedByInterruptException || thread.isInterrupted) {
                notifyError(ERROR_POSTPROCESSING_STOPPED, null)
                return
            }
            if (errCode == ERROR_NOTHING) errCode = ERROR_POSTPROCESSING
            exception = err
        } finally {
            notifyPostProcessing(if (errCode == ERROR_NOTHING) 2 else 0)
        }
        if (errCode != ERROR_NOTHING) {
            if (exception == null) exception = errObject
            notifyError(ERROR_POSTPROCESSING, exception)
            return
        }
        notifyFinished()
    }

    /**
     * Attempts to recover the download
     *
     * @param errorCode error code which trigger the recovery procedure
     */
    fun doRecover(errorCode: Int) {
        Log.i(TAG, "Attempting to recover the mission: ${storage?.name}")
        if (recoveryInfo == null) {
            notifyError(errorCode, null)
            urls = arrayOfNulls(0) // mark this mission as dead
            return
        }
        joinForThreads(0)
        threads = arrayOf(runAsync(DownloadMissionRecover.mID, DownloadMissionRecover(this, errorCode)))
    }

    private fun deleteThisFromFile(): Boolean {
        synchronized(LOCK) {
            val res = metadata?.delete() ?: false
            metadata = null
            return res
        }
    }

    /**
     * run a new thread
     *
     * @param id  id of new thread (used for debugging only)
     * @param who the Runnable whose `run` method is invoked.
     */
    private fun runAsync(id: Int, who: Runnable): Thread {
        return runAsync(id, Thread(who))
    }

    /**
     * run a new thread
     *
     * @param id  id of new thread (used for debugging only)
     * @param who the Thread whose `run` method is invoked when this thread is started
     * @return the passed thread
     */
    private fun runAsync(id: Int, who: Thread): Thread {
        // known thread ids:
        //   -2:     state saving by  notifyProgress()  method
        //   -1:     wait for saving the state by  pause()  method
        //    0:     initializer
        //  >=1:     any download thread

        if (BuildConfig.DEBUG) who.name = String.format("%s[%s] %s", TAG, id, storage?.name)
        who.start()
        return who
    }

    /**
     * Waits at most `millis` milliseconds for the thread to die
     *
     * @param millis the time to wait in milliseconds
     */
    private fun joinForThreads(millis: Int) {
        val currentThread = Thread.currentThread()

        if (init != null && init !== currentThread && init!!.isAlive) {
            init!!.interrupt()
            if (millis > 0) {
                try {
                    init!!.join(millis.toLong())
                } catch (e: InterruptedException) {
                    Log.w(TAG, "Initializer thread is still running", e)
                    return
                }
            }
        }

        // if a thread is still alive, possible reasons:
        //      slow device
        //      the user is spamming start/pause buttons
        //      start() method called quickly after pause()
        for (thread in threads) {
            if (thread?.isAlive != true || thread === Thread.currentThread()) continue
            thread.interrupt()
        }
        try {
            for (thread in threads) {
                if (thread?.isAlive != true) continue
                if (BuildConfig.DEBUG) Log.w(TAG, "thread alive: ${thread.name}")
                if (millis > 0) thread.join(millis.toLong())
            }
        } catch (e: InterruptedException) {
            throw RuntimeException("A download thread is still running", e)
        }
    }

    internal class HttpError(val statusCode: Int) : Exception() {
        override val message: String
            get() = "HTTP $statusCode"
    }

    class Block {
        var position: Int = 0
        var done: Int = 0
    }

    class DownloadInitializer internal constructor(private val mMission: DownloadMission) : Thread() {
        private var mConn: HttpURLConnection? = null

        private fun dispose() {
            try {
                mConn?.inputStream?.close()
            } catch (e: Exception) {
                // nothing to do
            }
        }

        override fun run() {
            if (mMission.current > 0) mMission.resetState(rollback = false,
                persistChanges = true,
                errorCode = ERROR_NOTHING)

            var retryCount = 0
            var httpCode = 204

            while (true) {
                try {
                    if (mMission.blocks == null && mMission.current == 0) {
                        // calculate the whole size of the mission
                        var finalLength: Long = 0
                        var lowestSize = Long.MAX_VALUE

                        var i = 0
                        while (i < mMission.urls.size && mMission.running) {
                            mConn = mMission.openConnection(mMission.urls[i], true, 0, 0)
                            mMission.establishConnection(mId, mConn!!)
                            dispose()

                            if (interrupted()) return
                            val length = if (mConn != null) Utility.getTotalContentLength(mConn!!) else 0

                            if (i == 0) {
                                httpCode = mConn!!.getResponseCode()
                                mMission.length = length
                            }

                            if (length > 0) finalLength += length
                            if (length < lowestSize) lowestSize = length
                            i++
                        }

                        mMission.nearLength = finalLength

                        // reserve space at the start of the file
                        if (mMission.psAlgorithm != null && mMission.psAlgorithm!!.reserveSpace) {
                            // the length is unknown use the default size
                            if (lowestSize < 1) mMission.offsets[0] = RESERVE_SPACE_DEFAULT.toLong()
                            // use the smallest resource size to download, otherwise, use the maximum
                            else mMission.offsets[0] = if (lowestSize < RESERVE_SPACE_MAXIMUM) lowestSize else RESERVE_SPACE_MAXIMUM.toLong()
                        }
                    } else {
                        // ask for the current resource length
                        mConn = mMission.openConnection(true, 0, 0)
                        mMission.establishConnection(mId, mConn!!)
                        dispose()
                        if (!mMission.running || interrupted()) return
                        httpCode = mConn!!.getResponseCode()
                        if (mConn != null) mMission.length = Utility.getTotalContentLength(mConn!!)
                    }

                    if (mMission.length == 0L || httpCode == 204) {
                        mMission.notifyError(ERROR_HTTP_NO_CONTENT, null)
                        return
                    }

                    // check for dynamic generated content
                    if (mMission.length == -1L && mConn!!.responseCode == 200) {
                        mMission.blocks = IntArray(0)
                        mMission.length = 0
                        mMission.unknownLength = true
                        Logd(TAG, "falling back (unknown length)")
                    } else {
                        // Open again
                        mConn = mMission.openConnection(true, mMission.length - 10, mMission.length)
                        mMission.establishConnection(mId, mConn!!)
                        dispose()
                        if (!mMission.running || interrupted()) return
                        synchronized(mMission.LOCK) {
                            if (mConn?.getResponseCode() == 206) {
                                if (mMission.threadCount > 1) {
                                    var count = (mMission.length / BLOCK_SIZE).toInt()
                                    if ((count * BLOCK_SIZE) < mMission.length) count++
                                    mMission.blocks = IntArray(count)
                                } else {
                                    // if one thread is required don't calculate blocks, is useless
                                    mMission.blocks = IntArray(0)
                                    mMission.unknownLength = false
                                }
                                Logd(TAG, "http response code = " + mConn?.getResponseCode())
                            } else {
                                // Fallback to single thread
                                mMission.blocks = IntArray(0)
                                mMission.unknownLength = false
                                Logd(TAG, "falling back due http response code = " + mConn?.getResponseCode())
                            }
                        }

                        if (!mMission.running || interrupted()) return
                    }

                    mMission.storage?.stream.use { fs ->
                        fs?.setLength(mMission.offsets[mMission.current] + mMission.length)
                        fs?.seek(mMission.offsets[mMission.current])
                    }
                    if (!mMission.running || interrupted()) return

                    if (!mMission.unknownLength && mMission.recoveryInfo != null) {
                        val entityTag = mConn!!.getHeaderField("ETAG")
                        val lastModified = mConn!!.getHeaderField("Last-Modified")
                        val recovery = mMission.recoveryInfo!![mMission.current]
                        when {
                            !TextUtils.isEmpty(entityTag) -> recovery.validateCondition = entityTag
                            !TextUtils.isEmpty(lastModified) -> recovery.validateCondition = lastModified // Note: this is less precise
                            else -> recovery.validateCondition = null
                        }
                    }
                    mMission.running = false
                    break
                } catch (e: InterruptedIOException) {
                    return
                } catch (e: ClosedByInterruptException) {
                    return
                } catch (e: Exception) {
                    if (!mMission.running || super.isInterrupted()) return
                    if (e is HttpError && e.statusCode == ERROR_HTTP_FORBIDDEN) {
                        // for youtube streams. The url has expired
                        interrupt()
                        mMission.doRecover(ERROR_HTTP_FORBIDDEN)
                        return
                    }
                    if (e is IOException && e.message!!.contains("Permission denied")) {
                        mMission.notifyError(ERROR_PERMISSION_DENIED, e)
                        return
                    }
                    if (retryCount++ > mMission.maxRetry) {
                        Log.e(TAG, "initializer failed", e)
                        mMission.notifyError(e)
                        return
                    }
                    Log.e(TAG, "initializer failed, retrying", e)
                }
            }
            mMission.start()
        }

        override fun interrupt() {
            super.interrupt()
            if (mConn != null) dispose()
        }

        companion object {
            const val mId: Int = 0
            private const val RESERVE_SPACE_DEFAULT = 5 * 1024 * 1024 // 5 MiB
            private const val RESERVE_SPACE_MAXIMUM = 150 * 1024 * 1024 // 150 MiB
        }
    }

    /**
     * Runnable to download blocks of a file until the file is completely downloaded,
     * an error occurs or the process is stopped.
     */
    class DownloadRunnable internal constructor(mission: DownloadMission, private val mId: Int) : Thread() {
        private val mMission: DownloadMission = Objects.requireNonNull(mission)
        private var mConn: HttpURLConnection? = null
        private fun releaseBlock(block: Block, remain: Long) {
            // set the block offset to -1 if it is completed
            mMission.releaseBlock(block.position, if (remain < 0) -1 else block.done)
        }

        override fun run() {
            var retry = false
            var block: Block? = null
            var retryCount = 0
            val f: SharpStream

            try {
                f = mMission.storage!!.stream
            } catch (e: IOException) {
                mMission.notifyError(e) // this never should happen
                return
            }

            while (mMission.running && mMission.errCode == ERROR_NOTHING) {
                if (!retry) block = mMission.acquireBlock()
                if (block == null) {
                    Logd(TAG, "$mId:no more blocks left, exiting")
                    break
                }

                if (retry) Logd(TAG, "$mId:retry block at position=${block.position} from the start")
                else Logd(TAG, "$mId:acquired block at position=${block.position} done=${block.done}")

                var start = block.position.toLong() * BLOCK_SIZE
                var end = start + BLOCK_SIZE - 1
                start += block.done.toLong()
                if (end >= mMission.length) end = mMission.length - 1

                try {
                    mConn = mMission.openConnection(false, start, end)
                    mMission.establishConnection(mId, mConn!!)

                    // check if the download can be resumed
                    if (mConn?.getResponseCode() == 416) {
                        if (block.done > 0) {
                            // try again from the start (of the block)
                            mMission.notifyProgress(-block.done.toLong())
                            block.done = 0
                            retry = true
                            mConn?.disconnect()
                            continue
                        }
                        throw HttpError(416)
                    }
                    retry = false

                    // The server may be ignoring the range request
                    if (mConn?.getResponseCode() != 206) {
                        if (BuildConfig.DEBUG) Log.e(TAG, "$mId:Unsupported ${mConn?.getResponseCode()}")
                        mMission.notifyError(HttpError(mConn!!.getResponseCode()))
                        break
                    }
                    f.seek(mMission.offsets[mMission.current] + start)
                    mConn!!.inputStream.use { `is` ->
                        val buf = ByteArray(BUFFER_SIZE)
                        var len = 0

                        // use always start <= end
                        // fixes a deadlock because in some videos, youtube is sending one byte alone
                        while ((start <= end && mMission.running) && (`is`.read(buf, 0, buf.size).also { len = it }) != -1) {
                            f.write(buf, 0, len)
                            start += len.toLong()
                            block.done += len
                            mMission.notifyProgress(len.toLong())
                        }
                    }
                    if (mMission.running) Logd(TAG, "$mId:position ${block.position} stopped $start/$end")
                } catch (e: Exception) {
                    if (!mMission.running || e is ClosedByInterruptException) break
                    if (e is HttpError && e.statusCode == ERROR_HTTP_FORBIDDEN) {
                        // for youtube streams. The url has expired, recover
                        f.close()

                        // only the first thread will execute the recovery procedure
                        if (mId == 1) mMission.doRecover(ERROR_HTTP_FORBIDDEN)
                        return
                    }

                    if (retryCount++ >= mMission.maxRetry) {
                        mMission.notifyError(e)
                        break
                    }

                    retry = true
                } finally {
                    if (!retry) releaseBlock(block, end - start)
                }
            }
            f.close()
            Logd(TAG, "thread $mId exited from main download loop")
            if (mMission.errCode == ERROR_NOTHING && mMission.running) {
                Logd(TAG, "no error has happened, notifying")
                mMission.notifyFinished()
            }
            if (!mMission.running) Logd(TAG, "The mission has been paused. Passing.")
        }

        override fun interrupt() {
            super.interrupt()
            try {
                if (mConn != null) mConn!!.disconnect()
            } catch (e: Exception) {
                // nothing to do
            }
        }
    }

    /**
     * Single-threaded fallback mode
     */
    class DownloadRunnableFallback internal constructor(private val mMission: DownloadMission) : Thread() {
        private var mRetryCount = 0
        private var mIs: InputStream? = null
        private var mF: SharpStream? = null
        private var mConn: HttpURLConnection? = null

        private fun dispose() {
            try {
                try {
                    if (mIs != null) mIs!!.close()
                } finally {
                    mConn!!.disconnect()
                }
            } catch (e: IOException) {
                // nothing to do
            }
            if (mF != null) mF!!.close()
        }

        override fun run() {
            val done: Boolean
            var start = mMission.fallbackResumeOffset

            if (!mMission.unknownLength && start > 0) Logd(TAG, "Resuming a single-thread download at $start")
            try {
                val rangeStart = if ((mMission.unknownLength || start < 1)) -1 else start
                val mId = 1
                mConn = mMission.openConnection(false, rangeStart, -1)
                // workaround: bypass android connection pool
                if (mRetryCount == 0 && rangeStart == -1L) mConn?.setRequestProperty("Range", "bytes=0-")

                if (mConn != null) mMission.establishConnection(mId, mConn!!)

                // check if the download can be resumed
                if (mConn?.getResponseCode() == 416 && start > 0) {
                    mMission.notifyProgress(-start)
                    start = 0
                    mRetryCount--
                    throw HttpError(416)
                }

                // secondary check for the file length
                if (!mMission.unknownLength && mConn != null) mMission.unknownLength = Utility.getContentLength(mConn!!) == -1L
                // restart amount of bytes downloaded
                if (mMission.unknownLength || mConn?.getResponseCode() == 200) mMission.done = mMission.offsets[mMission.current] - mMission.offsets[0]

                mF = mMission.storage!!.stream
                mF!!.seek(mMission.offsets[mMission.current] + start)
                mIs = mConn?.inputStream

                val buf = ByteArray(BUFFER_SIZE)
                var len = 0

                while (mMission.running && (mIs?.read(buf, 0, buf.size).also { len = it?:0 }) != -1) {
                    mF!!.write(buf, 0, len)
                    start += len.toLong()
                    mMission.notifyProgress(len.toLong())
                }
                dispose()
                // if thread goes interrupted check if the last part is written. This avoid re-download the whole file
                done = len == -1
            } catch (e: Exception) {
                dispose()
                mMission.fallbackResumeOffset = start
                if (!mMission.running || e is ClosedByInterruptException) return

                if (e is HttpError && e.statusCode == ERROR_HTTP_FORBIDDEN) {
                    // for youtube streams. The url has expired, recover
                    dispose()
                    mMission.doRecover(ERROR_HTTP_FORBIDDEN)
                    return
                }

                if (mRetryCount++ >= mMission.maxRetry) {
                    mMission.notifyError(e)
                    return
                }
                if (BuildConfig.DEBUG) Log.e(TAG, "got exception, retrying...", e)
                run() // try again
                return
            }
            if (done) mMission.notifyFinished()
            else mMission.fallbackResumeOffset = start
        }

        override fun interrupt() {
            super.interrupt()

            if (mConn != null) {
                try {
                    mConn!!.disconnect()
                } catch (e: Exception) {
                    // nothing to do
                }
            }
        }
    }

    class DownloadMissionRecover internal constructor(private val mMission: DownloadMission, private val mErrCode: Int) : Thread() {
        private val mNotInitialized = mMission.blocks == null && mMission.current == 0

        private var mConn: HttpURLConnection? = null
        private var mRecovery: MissionRecoveryInfo? = null
        private var mExtractor: StreamExtractor? = null

        override fun run() {
            if (mMission.source == null) {
                mMission.notifyError(mErrCode, null)
                return
            }

            var err: Exception? = null
            var attempt = 0

            while (attempt++ < mMission.maxRetry) {
                try {
                    tryRecover()
                    return
                } catch (e: InterruptedIOException) {
                    return
                } catch (e: ClosedByInterruptException) {
                    return
                } catch (e: Exception) {
                    if (!mMission.running || super.isInterrupted()) return
                    err = e
                }
            }

            // give up
            mMission.notifyError(mErrCode, err)
        }

        @Throws(ExtractionException::class, IOException::class, HttpError::class)
        private fun tryRecover() {
            if (mExtractor == null) {
                try {
                    val svr = NewPipe.getServiceByUrl(mMission.source)
                    mExtractor = svr.getStreamExtractor(mMission.source)
                    mExtractor?.fetchPage()
                } catch (e: ExtractionException) {
                    mExtractor = null
                    throw e
                }
            }

            // maybe the following check is redundant
            if (!mMission.running || super.isInterrupted()) return

            if (!mNotInitialized) {
                // set the current download url to null in case if the recovery
                // process is canceled. Next time start() method is called the
                // recovery will be executed, saving time
                mMission.urls[mMission.current] = null

                if (!mMission.recoveryInfo.isNullOrEmpty()) mRecovery = mMission.recoveryInfo!![mMission.current]
                resolveStream()
                return
            }
            Log.w(TAG, "mission is not fully initialized, this will take a while")

            try {
                while (mMission.current < mMission.urls.size) {
                    if (!mMission.recoveryInfo.isNullOrEmpty()) mRecovery = mMission.recoveryInfo!![mMission.current]

                    if (test()) {
                        mMission.current++
                        continue
                    }
                    if (!mMission.running) return

                    resolveStream()
                    if (!mMission.running) return
                    // before continue, check if the current stream was resolved
                    if (mMission.urls[mMission.current] == null) break
                    mMission.current++
                }
            } finally {
                mMission.current = 0
            }
            mMission.writeThisToFile()
            if (!mMission.running || super.isInterrupted()) return
            mMission.running = false
            mMission.start()
        }

        @Throws(IOException::class, ExtractionException::class, HttpError::class)
        private fun resolveStream() {
            // FIXME: this getErrorMessage() always returns "video is unavailable"
            /*if (mExtractor.getErrorMessage() != null) {
                mMission.notifyError(mErrCode, new ExtractionException(mExtractor.getErrorMessage()));
                return;
            }*/

            var url: String? = null

            when (mRecovery!!.kind) {
                'a' -> for (audio in mExtractor!!.audioStreams) {
                    if (audio.averageBitrate == mRecovery!!.desiredBitrate && audio.format == mRecovery!!.format && audio.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP) {
                        url = audio.content
                        break
                    }
                }
                'v' -> {
                    val videoStreams = if (mRecovery!!.isDesired2) mExtractor!!.videoOnlyStreams
                    else mExtractor!!.videoStreams
                    for (video in videoStreams) {
                        if (video.getResolution() == mRecovery!!.desired && video.format == mRecovery!!.format && video.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP) {
                            url = video.content
                            break
                        }
                    }
                }
                's' -> for (subtitles in mExtractor!!.getSubtitles(mRecovery!!.format)) {
                    val tag = subtitles.languageTag
                    if (tag == mRecovery!!.desired && subtitles.isAutoGenerated == mRecovery!!.isDesired2 && subtitles.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP) {
                        url = subtitles.content
                        break
                    }
                }
                else -> throw RuntimeException("Unknown stream type")
            }
            resolve(url)
        }

        @Throws(IOException::class, HttpError::class)
        private fun resolve(url: String?) {
            if (mRecovery!!.validateCondition == null) Log.w(TAG, "validation condition not defined, the resource can be stale")
            if (mMission.unknownLength || mRecovery!!.validateCondition == null) {
                recover(url, false)
                return
            }

            ///////////////////////////////////////////////////////////////////////
            ////// Validate the http resource doing a range request
            /////////////////////
            try {
                mConn = mMission.openConnection(url, true, mMission.length - 10, mMission.length)
                mConn?.setRequestProperty("If-Range", mRecovery!!.validateCondition)
                if (mConn != null) {
                    mMission.establishConnection(mID, mConn!!)
                    val code = mConn!!.getResponseCode()
                    when (code) {
                        200, 413 -> {
                            // stale
                            recover(url, true)
                            return
                        }
                        206 -> {
                            // in case of validation using the Last-Modified date, check the resource length
                            val contentRange = parseContentRange(mConn!!.getHeaderField("Content-Range"))
                            val lengthMismatch = contentRange[2] != -1L && contentRange[2] != mMission.length

                            recover(url, lengthMismatch)
                            return
                        }
                    }
                    throw HttpError(code)
                }
            } finally {
                disconnect()
            }
        }

        private fun recover(url: String?, stale: Boolean) {
            Log.i(TAG, String.format("recover()  name=%s  isStale=%s  url=%s", mMission.storage!!.name, stale, url))
            mMission.urls[mMission.current] = url

            if (url == null) {
                mMission.urls = arrayOfNulls(0)
                mMission.notifyError(ERROR_RESOURCE_GONE, null)
                return
            }
            if (mNotInitialized) return
            if (stale) mMission.resetState(rollback = false, persistChanges = false, errorCode = ERROR_NOTHING)
            mMission.writeThisToFile()
            if (!mMission.running || super.isInterrupted()) return

            mMission.running = false
            mMission.start()
        }

        private fun parseContentRange(value: String): LongArray {
            var value: String? = value
            val range = LongArray(3)

            // this never should happen
            if (value == null) return range

            try {
                value = value.trim { it <= ' ' }

                if (!value.startsWith("bytes")) return range // unknown range type

                val space = value.lastIndexOf(' ') + 1
                val dash = value.indexOf('-', space) + 1
                val bar = value.indexOf('/', dash)

                // start
                range[0] = value.substring(space, dash - 1).toLong()

                // end
                range[1] = value.substring(dash, bar).toLong()

                // resource length
                value = value.substring(bar + 1)
                if (value == "*") range[2] = -1 // unknown length received from the server but should be valid
                else range[2] = value.toLong()
            } catch (e: Exception) {
                // nothing to do
            }

            return range
        }

        private fun test(): Boolean {
            if (mMission.urls[mMission.current] == null) return false

            try {
                mConn = mMission.openConnection(mMission.urls[mMission.current], true, -1, -1)
                mMission.establishConnection(mID, mConn!!)
                if (mConn?.getResponseCode() == 200) return true
            } catch (e: Exception) {
                // nothing to do
            } finally {
                disconnect()
            }

            return false
        }

        private fun disconnect() {
            try {
                try {
                    mConn?.inputStream?.close()
                } finally {
                    mConn?.disconnect()
                }
            } catch (e: Exception) {
                // nothing to do
            } finally {
                mConn = null
            }
        }

        override fun interrupt() {
            super.interrupt()
            if (mConn != null) disconnect()
        }

        companion object {
            const val mID: Int = -3
        }
    }

    private class Lock : Serializable
    companion object {
        private const val TAG = "DownloadMission"

        private const val serialVersionUID = 6L // last bump: 07 october 2019

        const val BUFFER_SIZE: Int = 64 * 1024
        const val BLOCK_SIZE: Int = 512 * 1024

        const val ERROR_NOTHING: Int = -1
        const val ERROR_PATH_CREATION: Int = 1000
        const val ERROR_FILE_CREATION: Int = 1001
        const val ERROR_UNKNOWN_EXCEPTION: Int = 1002
        const val ERROR_PERMISSION_DENIED: Int = 1003
        const val ERROR_SSL_EXCEPTION: Int = 1004
        const val ERROR_UNKNOWN_HOST: Int = 1005
        const val ERROR_CONNECT_HOST: Int = 1006
        const val ERROR_POSTPROCESSING: Int = 1007
        const val ERROR_POSTPROCESSING_STOPPED: Int = 1008
        const val ERROR_POSTPROCESSING_HOLD: Int = 1009
        const val ERROR_INSUFFICIENT_STORAGE: Int = 1010
        const val ERROR_PROGRESS_LOST: Int = 1011
        const val ERROR_TIMEOUT: Int = 1012
        const val ERROR_RESOURCE_GONE: Int = 1013
        const val ERROR_HTTP_NO_CONTENT: Int = 204
        const val ERROR_HTTP_FORBIDDEN: Int = 403
    }
}
