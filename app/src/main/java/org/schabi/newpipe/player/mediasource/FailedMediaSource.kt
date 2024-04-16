package org.schabi.newpipe.player.mediasource

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.*
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import org.schabi.newpipe.player.mediaitem.ExceptionTag
import org.schabi.newpipe.player.playqueue.PlayQueueItem
import java.io.IOException
import java.util.List
import java.util.concurrent.TimeUnit

@UnstableApi class FailedMediaSource(val stream: PlayQueueItem, val error: Exception, private val retryTimestamp: Long)
    : BaseMediaSource(), ManagedMediaSource {

    private val TAG = "FailedMediaSource@" + Integer.toHexString(hashCode())
    private val mediaItem = ExceptionTag.of(stream, List.of(error)).withExtras(this).asMediaItem()

    private fun canRetry(): Boolean {
        return System.currentTimeMillis() >= retryTimestamp
    }

    override fun getMediaItem(): MediaItem {
        return mediaItem
    }

    /**
     * Prepares the source with [Timeline] info on the silence playback when the error
     * is classed as [FailedMediaSourceException], for example, when the error is
     * [ExtractionException][org.schabi.newpipe.extractor.exceptions.ExtractionException].
     * These types of error are swallowed by [FailedMediaSource], and the underlying
     * exception is carried to the [MediaItem] metadata during playback.
     * <br></br><br></br>
     * If the exception is not known, e.g. [java.net.UnknownHostException] or some
     * other network issue, then no source info is refreshed and
     * [.maybeThrowSourceInfoRefreshError] be will triggered.
     * <br></br><br></br>
     * Note that this method is called only once until [.releaseSourceInternal] is called,
     * so if no action is done in here, playback will stall unless
     * [.maybeThrowSourceInfoRefreshError] is called.
     *
     * @param mediaTransferListener No data transfer listener needed, ignored here.
     */
    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        Log.e(TAG, "Loading failed source: ", error)
        if (error is FailedMediaSourceException) {
            if (mediaItem != null) refreshSourceInfo(makeSilentMediaTimeline(SILENCE_DURATION_US, mediaItem))
        }
    }

    /**
     * If the error is not known, e.g. network issue, then the exception is not swallowed here in
     * [FailedMediaSource]. The exception is then propagated to the player, which
     * [Player][org.schabi.newpipe.player.Player] can react to inside
     * [androidx.media3.common.Player.Listener.onPlayerError].
     *
     * @throws IOException An error which will always result in
     * [androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED].
     */
    @Throws(IOException::class)
    override fun maybeThrowSourceInfoRefreshError() {
        if (error !is FailedMediaSourceException) {
            throw IOException(error)
        }
    }

    /**
     * This method is only called if [.prepareSourceInternal]
     * refreshes the source info with no exception. All parameters are ignored as this
     * returns a static and reused piece of silent audio.
     *
     * @param id                The identifier of the period.
     * @param allocator         An [Allocator] from which to obtain media buffer allocations.
     * @param startPositionUs   The expected start position, in microseconds.
     * @return The common [MediaPeriod] holding the silence.
     */
    override fun createPeriod(id: MediaSource.MediaPeriodId,
                              allocator: Allocator,
                              startPositionUs: Long
    ): MediaPeriod {
        return SILENT_MEDIA
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        /* Do Nothing (we want to keep re-using the Silent MediaPeriod) */
    }

    override fun releaseSourceInternal() {
        /* Do Nothing, no clean-up for processing/extra thread is needed by this MediaSource */
    }

    override fun shouldBeReplacedWith(newIdentity: PlayQueueItem,
                                      isInterruptable: Boolean
    ): Boolean {
        return newIdentity != stream || canRetry()
    }

    override fun isStreamEqual(stream: PlayQueueItem): Boolean {
        return this.stream == stream
    }

    open class FailedMediaSourceException : Exception {
        internal constructor(message: String?) : super(message)

        internal constructor(cause: Throwable?) : super(cause)
    }

    class MediaSourceResolutionException(message: String?) : FailedMediaSourceException(message)

    class StreamInfoLoadException(cause: Throwable?) : FailedMediaSourceException(cause)

    companion object {
        /**
         * Play 2 seconds of silenced audio when a stream fails to resolve due to a known issue,
         * such as [org.schabi.newpipe.extractor.exceptions.ExtractionException].
         *
         * This silence duration allows user to react and have time to jump to a previous stream,
         * while still provide a smooth playback experience. A duration lower than 1 second is
         * not recommended, it may cause ExoPlayer to buffer for a while.
         */
        val SILENCE_DURATION_US: Long = TimeUnit.SECONDS.toMicros(2)
        val SILENT_MEDIA: MediaPeriod = makeSilentMediaPeriod(SILENCE_DURATION_US)

        fun of(playQueueItem: PlayQueueItem,
               error: FailedMediaSourceException
        ): FailedMediaSource {
            return FailedMediaSource(playQueueItem, error, Long.MAX_VALUE)
        }

        fun of(playQueueItem: PlayQueueItem,
               error: Exception,
               retryWaitMillis: Long
        ): FailedMediaSource {
            return FailedMediaSource(playQueueItem, error,
                System.currentTimeMillis() + retryWaitMillis)
        }

        private fun makeSilentMediaTimeline(durationUs: Long, mediaItem: MediaItem): Timeline {
            return SinglePeriodTimeline(
                durationUs,  /* isSeekable= */
                true,  /* isDynamic= */
                false,  /* useLiveConfiguration= */
                false,  /* manifest= */
                null,
                mediaItem)
        }

        private fun makeSilentMediaPeriod(durationUs: Long): MediaPeriod {
            val mediaSource = SilenceMediaSource.Factory()
                .setDurationUs(durationUs)
                .createMediaSource()
            val mediaPeriodId = MediaSource.MediaPeriodId(0)
            val allocator = DefaultAllocator(false, 0)
            return mediaSource.createPeriod(mediaPeriodId, allocator, 0)
//            return SilenceMediaSource.Factory()
//                .setDurationUs(durationUs)
//                .createMediaSource()
//                .createPeriod(null, null, 0)
        }
    }
}
