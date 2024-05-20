package us.shandian.giga.get

import android.text.TextUtils
import android.util.Log
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.util.Logd
import us.shandian.giga.util.Utility
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.nio.channels.ClosedByInterruptException

class DownloadInitializer internal constructor(private val mMission: DownloadMission) : Thread() {
    private var mConn: HttpURLConnection? = null

    private fun dispose() {
        try {
            mConn!!.inputStream.close()
        } catch (e: Exception) {
            // nothing to do
        }
    }

    override fun run() {
        if (mMission.current > 0) mMission.resetState(false, true, DownloadMission.ERROR_NOTHING)

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
                        if (mConn != null) mMission.establishConnection(mId, mConn!!)
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
                        if (lowestSize < 1) {
                            // the length is unknown use the default size
                            mMission.offsets[0] = RESERVE_SPACE_DEFAULT.toLong()
                        } else {
                            // use the smallest resource size to download, otherwise, use the maximum
                            mMission.offsets[0] = if (lowestSize < RESERVE_SPACE_MAXIMUM) lowestSize else RESERVE_SPACE_MAXIMUM.toLong()
                        }
                    }
                } else {
                    // ask for the current resource length
                    mConn = mMission.openConnection(true, 0, 0)
                    if (mConn != null) mMission.establishConnection(mId, mConn!!)
                    dispose()

                    if (!mMission.running || interrupted()) return

                    httpCode = mConn!!.getResponseCode()
                    if (mConn != null) mMission.length = Utility.getTotalContentLength(mConn!!)
                }

                if (mMission.length == 0L || httpCode == 204) {
                    mMission.notifyError(DownloadMission.ERROR_HTTP_NO_CONTENT, null)
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
                    if (mConn != null) mMission.establishConnection(mId, mConn!!)
                    dispose()

                    if (!mMission.running || interrupted()) return

                    synchronized(mMission.LOCK) {
                        if (mConn?.getResponseCode() == 206) {
                            if (mMission.threadCount > 1) {
                                var count = (mMission.length / DownloadMission.BLOCK_SIZE).toInt()
                                if ((count * DownloadMission.BLOCK_SIZE) < mMission.length) count++

                                mMission.blocks = IntArray(count)
                            } else {
                                // if one thread is required don't calculate blocks, is useless
                                mMission.blocks = IntArray(0)
                                mMission.unknownLength = false
                            }
                            Logd(TAG, "http response code = " + mConn!!.getResponseCode())
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
                        !TextUtils.isEmpty(entityTag) -> {
                            recovery.validateCondition = entityTag
                        }
                        !TextUtils.isEmpty(lastModified) -> {
                            recovery.validateCondition = lastModified // Note: this is less precise
                        }
                        else -> {
                            recovery.validateCondition = null
                        }
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

                if (e is DownloadMission.HttpError && e.statusCode == DownloadMission.ERROR_HTTP_FORBIDDEN) {
                    // for youtube streams. The url has expired
                    interrupt()
                    mMission.doRecover(DownloadMission.ERROR_HTTP_FORBIDDEN)
                    return
                }

                if (e is IOException && e.message!!.contains("Permission denied")) {
                    mMission.notifyError(DownloadMission.ERROR_PERMISSION_DENIED, e)
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
        private const val TAG = "DownloadInitializer"
        const val mId: Int = 0
        private const val RESERVE_SPACE_DEFAULT = 5 * 1024 * 1024 // 5 MiB
        private const val RESERVE_SPACE_MAXIMUM = 150 * 1024 * 1024 // 150 MiB
    }
}
