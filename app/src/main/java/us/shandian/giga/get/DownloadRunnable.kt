package us.shandian.giga.get

import android.util.Log
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.streams.io.SharpStream
import org.schabi.newpipe.util.Logd
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.channels.ClosedByInterruptException
import java.util.*

/**
 * Runnable to download blocks of a file until the file is completely downloaded,
 * an error occurs or the process is stopped.
 */
class DownloadRunnable internal constructor(mission: DownloadMission, private val mId: Int) : Thread() {
    private val mMission: DownloadMission = Objects.requireNonNull(mission)

    private var mConn: HttpURLConnection? = null

    private fun releaseBlock(block: DownloadMission.Block, remain: Long) {
        // set the block offset to -1 if it is completed
        mMission.releaseBlock(block.position, if (remain < 0) -1 else block.done)
    }

    override fun run() {
        var retry = false
        var block: DownloadMission.Block? = null
        var retryCount = 0
        val f: SharpStream

        try {
            f = mMission.storage!!.stream
        } catch (e: IOException) {
            mMission.notifyError(e) // this never should happen
            return
        }

        while (mMission.running && mMission.errCode == DownloadMission.ERROR_NOTHING) {
            if (!retry) block = mMission.acquireBlock()

            if (block == null) {
                Logd(TAG, "$mId:no more blocks left, exiting")
                break
            }

            if (retry) Logd(TAG, "$mId:retry block at position=${block.position} from the start")
            else Logd(TAG, "$mId:acquired block at position=${block.position} done=${block.done}")

            var start = block.position.toLong() * DownloadMission.BLOCK_SIZE
            var end = start + DownloadMission.BLOCK_SIZE - 1

            start += block.done.toLong()

            if (end >= mMission.length) end = mMission.length - 1

            try {
                mConn = mMission.openConnection(false, start, end)
                if (mConn != null) mMission.establishConnection(mId, mConn!!)

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

                    throw DownloadMission.HttpError(416)
                }

                retry = false

                // The server may be ignoring the range request
                if (mConn?.getResponseCode() != 206) {
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG, "$mId:Unsupported ${mConn?.getResponseCode()}")
                    }
                    mMission.notifyError(DownloadMission.HttpError(mConn!!.getResponseCode()))
                    break
                }

                f.seek(mMission.offsets[mMission.current] + start)

                mConn!!.getInputStream().use { `is` ->
                    val buf = ByteArray(DownloadMission.BUFFER_SIZE)
                    var len: Int = 0

                    // use always start <= end
                    // fixes a deadlock because in some videos, youtube is sending one byte alone
                    while ((start <= end && mMission.running) && (`is`.read(buf, 0, buf.size).also { len = it }) != -1) {
                        f.write(buf, 0, len)
                        start += len.toLong()
                        block.done += len
                        mMission.notifyProgress(len.toLong())
                    }
                }
                if (mMission.running) {
                    Logd(TAG, mId.toString() + ":position " + block.position + " stopped " + start + "/" + end)
                }
            } catch (e: Exception) {
                if (!mMission.running || e is ClosedByInterruptException) break

                if (e is DownloadMission.HttpError && e.statusCode == DownloadMission.ERROR_HTTP_FORBIDDEN) {
                    // for youtube streams. The url has expired, recover
                    f.close()

                    if (mId == 1) {
                        // only the first thread will execute the recovery procedure
                        mMission.doRecover(DownloadMission.ERROR_HTTP_FORBIDDEN)
                    }
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

        if (mMission.errCode == DownloadMission.ERROR_NOTHING && mMission.running) {
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

    companion object {
        private const val TAG = "DownloadRunnable"
    }
}
