package us.shandian.giga.get

import android.util.Log
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamExtractor
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.nio.channels.ClosedByInterruptException

class DownloadMissionRecover internal constructor(private val mMission: DownloadMission, private val mErrCode: Int) :
    Thread() {
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

    @Throws(ExtractionException::class, IOException::class, DownloadMission.HttpError::class)
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
                if (mMission.urls[mMission.current] == null) {
                    break
                }
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

    @Throws(IOException::class, ExtractionException::class, DownloadMission.HttpError::class)
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

    @Throws(IOException::class, DownloadMission.HttpError::class)
    private fun resolve(url: String?) {
        if (mRecovery!!.validateCondition == null) {
            Log.w(TAG, "validation condition not defined, the resource can be stale")
        }

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
                throw DownloadMission.HttpError(code)
            }
        } finally {
            disconnect()
        }
    }

    private fun recover(url: String?, stale: Boolean) {
        Log.i(TAG,
            String.format("recover()  name=%s  isStale=%s  url=%s", mMission.storage!!.name, stale, url)
        )

        mMission.urls[mMission.current] = url

        if (url == null) {
            mMission.urls = arrayOfNulls(0)
            mMission.notifyError(DownloadMission.ERROR_RESOURCE_GONE, null)
            return
        }

        if (mNotInitialized) return

        if (stale) {
            mMission.resetState(false, false, DownloadMission.ERROR_NOTHING)
        }

        mMission.writeThisToFile()

        if (!mMission.running || super.isInterrupted()) return

        mMission.running = false
        mMission.start()
    }

    private fun parseContentRange(value: String): LongArray {
        var value: String? = value
        val range = LongArray(3)

        if (value == null) {
            // this never should happen
            return range
        }

        try {
            value = value.trim { it <= ' ' }

            if (!value.startsWith("bytes")) {
                return range // unknown range type
            }

            val space = value.lastIndexOf(' ') + 1
            val dash = value.indexOf('-', space) + 1
            val bar = value.indexOf('/', dash)

            // start
            range[0] = value.substring(space, dash - 1).toLong()

            // end
            range[1] = value.substring(dash, bar).toLong()

            // resource length
            value = value.substring(bar + 1)
            if (value == "*") {
                range[2] = -1 // unknown length received from the server but should be valid
            } else {
                range[2] = value.toLong()
            }
        } catch (e: Exception) {
            // nothing to do
        }

        return range
    }

    private fun test(): Boolean {
        if (mMission.urls[mMission.current] == null) return false

        try {
            mConn = mMission.openConnection(mMission.urls[mMission.current], true, -1, -1)
            if (mConn != null) mMission.establishConnection(mID, mConn!!)

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
                mConn!!.inputStream.close()
            } finally {
                mConn!!.disconnect()
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
        private const val TAG = "DownloadMissionRecover"
        const val mID: Int = -3
    }
}
