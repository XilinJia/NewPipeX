package org.schabi.newpipe.player.mediasource

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import org.schabi.newpipe.player.mediaitem.MediaItemTag
import org.schabi.newpipe.player.playqueue.PlayQueueItem

@OptIn(UnstableApi::class) class LoadedMediaSource(source: MediaSource,
                        tag: MediaItemTag,
                        val stream: PlayQueueItem,
                        private val expireTimestamp: Long
) : WrappingMediaSource(source), ManagedMediaSource {
    private val mediaItem = tag.withExtras(this)!!.asMediaItem()

    private val isExpired: Boolean
        get() = System.currentTimeMillis() >= expireTimestamp

    override fun getMediaItem(): MediaItem {
        return mediaItem
    }

    override fun shouldBeReplacedWith(newIdentity: PlayQueueItem,
                                      isInterruptable: Boolean
    ): Boolean {
        return newIdentity != stream || (isInterruptable && isExpired)
    }

    override fun isStreamEqual(otherStream: PlayQueueItem): Boolean {
        return this.stream == otherStream
    }
}
