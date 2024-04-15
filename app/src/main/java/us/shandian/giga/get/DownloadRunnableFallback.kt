package us.shandian.giga.get

import android.util.Log
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.streams.io.SharpStream
import us.shandian.giga.util.Utility
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.nio.channels.ClosedByInterruptException

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

        if (BuildConfig.DEBUG && !mMission.unknownLength && start > 0) {
            Log.i(TAG, "Resuming a single-thread download at $start")
        }

        try {
            val rangeStart = if ((mMission.unknownLength || start < 1)) -1 else start

            val mId = 1
            mConn = mMission.openConnection(false, rangeStart, -1)

            if (mRetryCount == 0 && rangeStart == -1L) {
                // workaround: bypass android connection pool
                mConn?.setRequestProperty("Range", "bytes=0-")
            }

            if (mConn != null) mMission.establishConnection(mId, mConn!!)

            // check if the download can be resumed
            if (mConn?.getResponseCode() == 416 && start > 0) {
                mMission.notifyProgress(-start)
                start = 0
                mRetryCount--
                throw DownloadMission.HttpError(416)
            }

            // secondary check for the file length
            if (!mMission.unknownLength && mConn != null) mMission.unknownLength = Utility.getContentLength(mConn!!) == -1L

            if (mMission.unknownLength || mConn?.getResponseCode() == 200) {
                // restart amount of bytes downloaded
                mMission.done = mMission.offsets[mMission.current] - mMission.offsets[0]
            }

            mF = mMission.storage!!.stream
            mF!!.seek(mMission.offsets[mMission.current] + start)

            mIs = mConn?.getInputStream()

            val buf = ByteArray(DownloadMission.BUFFER_SIZE)
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

            if (e is DownloadMission.HttpError && e.statusCode == DownloadMission.ERROR_HTTP_FORBIDDEN) {
                // for youtube streams. The url has expired, recover
                dispose()
                mMission.doRecover(DownloadMission.ERROR_HTTP_FORBIDDEN)
                return
            }

            if (mRetryCount++ >= mMission.maxRetry) {
                mMission.notifyError(e)
                return
            }

            if (BuildConfig.DEBUG) {
                Log.e(TAG, "got exception, retrying...", e)
            }

            run() // try again
            return
        }

        if (done) {
            mMission.notifyFinished()
        } else {
            mMission.fallbackResumeOffset = start
        }
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

    companion object {
        private const val TAG = "DownloadRunnableFallback"
    }
}
