package us.shandian.giga.get

import android.os.Handler
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.streams.io.StoredFileHelper
import us.shandian.giga.postprocessing.Postprocessing
import us.shandian.giga.service.DownloadManagerService
import us.shandian.giga.util.Utility
import java.io.*
import java.net.*
import java.nio.channels.ClosedByInterruptException
import java.util.*
import javax.net.ssl.SSLException
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min

class DownloadMission(urls: Array<String?>, storage: StoredFileHelper?, kind: Char, psInstance: Postprocessing?) :
    Mission() {
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

    init {
        require(Objects.requireNonNull(urls).size >= 1) { "urls array is empty" }
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
     *
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
     *
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
     *
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

        if (BuildConfig.DEBUG) {
            Log.d(TAG, threadId.toString() + ":[request]  Range=" + conn.getRequestProperty("Range"))
            Log.d(TAG, "$threadId:[response] Code=$statusCode")
            Log.d(TAG, threadId.toString() + ":[response] Content-Length=" + conn.contentLength)
            Log.d(TAG, threadId.toString() + ":[response] Content-Range=" + conn.getHeaderField("Content-Range"))
        }

        when (statusCode) {
            204, 205, 207 -> throw HttpError(statusCode)
            416 -> return  // let the download thread handle this error
            else -> if (statusCode < 200 || statusCode > 299) {
                throw HttpError(statusCode)
            }
        }
    }


    private fun notify(what: Int) {
        mHandler!!.obtainMessage(what, this).sendToTarget()
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
            is FileNotFoundException -> {
                notifyError(ERROR_FILE_CREATION, null)
            }
            is SSLException -> {
                notifyError(ERROR_SSL_EXCEPTION, null)
            }
            is HttpError -> {
                notifyError(err.statusCode, null)
            }
            is ConnectException -> {
                notifyError(ERROR_CONNECT_HOST, null)
            }
            is UnknownHostException -> {
                notifyError(ERROR_UNKNOWN_HOST, null)
            }
            is SocketTimeoutException -> {
                notifyError(ERROR_TIMEOUT, null)
            }
            else -> {
                notifyError(ERROR_UNKNOWN_EXCEPTION, err)
            }
        }
    }

    @Synchronized
    fun notifyError(code: Int, err: Exception?) {
        var code = code
        var err = err
        Log.e(TAG, "notifyError() code = $code", err)
        if (err != null && err.cause is ErrnoException) {
            val errno = (err.cause as ErrnoException?)!!.errno
            if (errno == OsConstants.ENOSPC) {
                code = ERROR_INSUFFICIENT_STORAGE
                err = null
            } else if (errno == OsConstants.EACCES) {
                code = ERROR_PERMISSION_DENIED
                err = null
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
                !storage!!.canWrite() -> {
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

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "onFinish: downloaded " + (current + 1) + "/" + urls.size)
            }

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
        Log.d(TAG, action + " postprocessing on " + storage!!.name)

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
        if (running || isFinished || urls.size < 1) return

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

        if (blocks!!.size < 1) {
            threads = arrayOf(runAsync(1, DownloadRunnableFallback(this)))
        } else {
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
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "pause during post-processing is not applicable.")
            }
            return
        }

        running = false
        notify(DownloadManagerService.MESSAGE_PAUSED)

        if (init != null && init!!.isAlive) {
            // NOTE: if start() method is running ¡will no have effect!
            init!!.interrupt()
            synchronized(LOCK) {
                resetState(false, true, ERROR_NOTHING)
            }
            return
        }

        if (BuildConfig.DEBUG && unknownLength) {
            Log.w(TAG, "pausing a download that can not be resumed (range requests not allowed by the server).")
        }

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
        if (psAlgorithm != null) psAlgorithm!!.cleanupTemporalDir()

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

    val isFinished: Boolean
        /**
         * Indicates if the download if fully finished
         *
         * @return true, otherwise, false
         */
        get() = current >= urls.size && (psAlgorithm == null || psState == 2)

    val isPsFailed: Boolean
        /**
         * Indicates if the download file is corrupt due a failed post-processing
         *
         * @return `true` if this mission is unrecoverable
         */
        get() {
            when (errCode) {
                ERROR_POSTPROCESSING, ERROR_POSTPROCESSING_STOPPED -> return psAlgorithm!!.worksOnSameFile
            }
            return false
        }

    val isPsRunning: Boolean
        /**
         * Indicates if a post-processing algorithm is running
         *
         * @return true, otherwise, false
         */
        get() = psAlgorithm != null && (psState == 1 || psState == 3)

    val isInitialized: Boolean
        /**
         * Indicated if the mission is ready
         *
         * @return true, otherwise, false
         */
        get() = blocks != null // DownloadMissionInitializer was executed

    override var length: Long = 0L
        /**
         * Gets the approximated final length of the file
         *
         * @return the length in bytes
         */
        get() {
            if (psState == 1 || psState == 3) {
                return field
            }

            var calculated = offsets[if (current < offsets.size) current else offsets.size - 1] + field
            calculated -= offsets[0] // don't count reserved space

            return max(calculated, nearLength)
        }
        set(length) {
            super.length = length
        }

    /**
     * set this mission state on the queue
     *
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
     *
     * @return `true`, if storage is invalid and cannot be used
     */
    fun hasInvalidStorage(): Boolean {
        return errCode == ERROR_PROGRESS_LOST || storage == null || !storage!!.existsAsFile()
    }

    val isCorrupt: Boolean
        /**
         * Indicates whatever is possible to start the mission
         *
         * @return `true` is this mission its "healthy", otherwise, `false`
         */
        get() {
            if (urls.size < 1) return false
            return (isPsFailed || errCode == ERROR_POSTPROCESSING_HOLD) || this.isFinished
        }

    val isRecovering: Boolean
        /**
         * Indicates if mission urls has expired and there an attempt to renovate them
         *
         * @return `true` if the mission is running a recovery procedure, otherwise, `false`
         */
        get() = threads.size > 0 && threads[0] is DownloadMissionRecover && threads[0]!!.isAlive()

    private fun doPostprocessing() {
        errCode = ERROR_NOTHING
        errObject = null
        val thread = Thread.currentThread()

        notifyPostProcessing(1)

        if (BuildConfig.DEBUG) {
            thread.name = "[" + TAG + "]  ps = " + psAlgorithm + "  filename = " + storage!!.name
        }

        var exception: Exception? = null

        try {
            psAlgorithm!!.run(this)
        } catch (err: Exception) {
            Log.e(TAG, "Post-processing failed. " + psAlgorithm.toString(), err)

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
        Log.i(TAG, "Attempting to recover the mission: " + storage!!.name)

        if (recoveryInfo == null) {
            notifyError(errorCode, null)
            urls = arrayOfNulls(0) // mark this mission as dead
            return
        }

        joinForThreads(0)

        threads = arrayOf(runAsync(DownloadMissionRecover.mID, DownloadMissionRecover(this, errorCode))
        )
    }

    private fun deleteThisFromFile(): Boolean {
        synchronized(LOCK) {
            val res = metadata!!.delete()
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

        if (BuildConfig.DEBUG) {
            who.name = String.format("%s[%s] %s", TAG, id, storage!!.name)
        }

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
            if (!thread!!.isAlive || thread === Thread.currentThread()) continue
            thread.interrupt()
        }

        try {
            for (thread in threads) {
                if (!thread!!.isAlive) continue
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "thread alive: " + thread.name)
                }
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

    private class Lock : Serializable
    companion object {
        private const val serialVersionUID = 6L // last bump: 07 october 2019

        const val BUFFER_SIZE: Int = 64 * 1024
        const val BLOCK_SIZE: Int = 512 * 1024

        private const val TAG = "DownloadMission"

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
