package org.schabi.newpipe.player.mediasource

import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import org.schabi.newpipe.player.mediaitem.PlaceholderTag
import org.schabi.newpipe.player.playqueue.PlayQueueItem

@UnstableApi internal class PlaceholderMediaSource private constructor() : CompositeMediaSource<Void?>(), ManagedMediaSource {
    override fun getMediaItem(): MediaItem {
        return MEDIA_ITEM
    }

    override fun onChildSourceInfoRefreshed(id: Void?,
                                            mediaSource: MediaSource,
                                            timeline: Timeline
    ) {
        /* Do nothing, no timeline updates or error will stall playback */
    }

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod {
        return PlaceholderMediaSource() as MediaPeriod
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {}

    override fun shouldBeReplacedWith(newIdentity: PlayQueueItem, isInterruptable: Boolean): Boolean {
        return true
    }

    override fun isStreamEqual(stream: PlayQueueItem): Boolean {
        return false
    }

    companion object {
        val COPY: PlaceholderMediaSource = PlaceholderMediaSource()
        private val MEDIA_ITEM = PlaceholderTag.EMPTY.withExtras(COPY)!!
            .asMediaItem()
    }
}
