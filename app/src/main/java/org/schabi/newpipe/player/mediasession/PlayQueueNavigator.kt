package org.schabi.newpipe.player.mediasession

import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector.QueueNavigator
import com.google.android.exoplayer2.util.Util
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.util.image.ImageStrategy.choosePreferredImage
import java.util.*
import kotlin.math.min

class PlayQueueNavigator(private val mediaSession: MediaSessionCompat,
                         private val player: Player
) : QueueNavigator {
    private var activeQueueItemId: Long

    init {
        this.activeQueueItemId = MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong()
    }

    override fun getSupportedQueueNavigatorActions(exoPlayer: com.google.android.exoplayer2.Player): Long {
        return PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
    }

    override fun onTimelineChanged(exoPlayer: com.google.android.exoplayer2.Player) {
        publishFloatingQueueWindow()
    }

    override fun onCurrentMediaItemIndexChanged(
            exoPlayer: com.google.android.exoplayer2.Player
    ) {
        if (activeQueueItemId == MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong()
                || exoPlayer.currentTimeline.windowCount > MAX_QUEUE_SIZE) {
            publishFloatingQueueWindow()
        } else if (!exoPlayer.currentTimeline.isEmpty) {
            activeQueueItemId = exoPlayer.currentMediaItemIndex.toLong()
        }
    }

    override fun getActiveQueueItemId(exoPlayer: com.google.android.exoplayer2.Player?): Long {
        return Optional.ofNullable(player.playQueue).map(PlayQueue::index).orElse(-1)?.toLong()?:0L
    }

    override fun onSkipToPrevious(exoPlayer: com.google.android.exoplayer2.Player) {
        player.playPrevious()
    }

    override fun onSkipToQueueItem(exoPlayer: com.google.android.exoplayer2.Player,
                                   id: Long
    ) {
        if (player.playQueue != null) {
            player.selectQueueItem(player.playQueue!!.getItem(id.toInt()))
        }
    }

    override fun onSkipToNext(exoPlayer: com.google.android.exoplayer2.Player) {
        player.playNext()
    }

    private fun publishFloatingQueueWindow() {
        val windowCount = Optional.ofNullable(player.playQueue)
            .map { obj: PlayQueue -> obj.size() }
            .orElse(0)
        if (windowCount == 0) {
            mediaSession.setQueue(emptyList())
            activeQueueItemId = MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong()
            return
        }

        // Yes this is almost a copypasta, got a problem with that? =\
        val currentWindowIndex = player.playQueue!!.index
        val queueSize = min(MAX_QUEUE_SIZE.toDouble(), windowCount.toDouble())
            .toInt()
        val startIndex = Util.constrainValue(currentWindowIndex - ((queueSize - 1) / 2), 0,
            windowCount - queueSize)

        val queue: MutableList<MediaSessionCompat.QueueItem> = ArrayList()
        for (i in startIndex until startIndex + queueSize) {
            queue.add(MediaSessionCompat.QueueItem(getQueueMetadata(i), i.toLong()))
        }
        mediaSession.setQueue(queue)
        activeQueueItemId = currentWindowIndex.toLong()
    }

    fun getQueueMetadata(index: Int): MediaDescriptionCompat? {
        if (player.playQueue == null) {
            return null
        }
        val item = player.playQueue!!.getItem(index) ?: return null

        val descBuilder = MediaDescriptionCompat.Builder()
            .setMediaId(index.toString())
            .setTitle(item.title)
            .setSubtitle(item.uploader)

        // set additional metadata for A2DP/AVRCP (Audio/Video Bluetooth profiles)
        val additionalMetadata = Bundle()
        additionalMetadata.putString(MediaMetadataCompat.METADATA_KEY_TITLE, item.title)
        additionalMetadata.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, item.uploader)
        additionalMetadata
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, item.duration * 1000)
        additionalMetadata.putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, index + 1L)
        additionalMetadata
            .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, player.playQueue!!.size().toLong())
        descBuilder.setExtras(additionalMetadata)

        try {
            descBuilder.setIconUri(Uri.parse(
                choosePreferredImage(item.thumbnails)))
        } catch (e: Throwable) {
            // no thumbnail available at all, or the user disabled image loading,
            // or the obtained url is not a valid `Uri`
        }

        return descBuilder.build()
    }

    override fun onCommand(exoPlayer: com.google.android.exoplayer2.Player,
                           command: String,
                           extras: Bundle?,
                           cb: ResultReceiver?
    ): Boolean {
        return false
    }

    companion object {
        private const val MAX_QUEUE_SIZE = 10
    }
}
