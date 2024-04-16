package org.schabi.newpipe.player.mediaitem

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.LocalConfiguration
import androidx.media3.common.MediaItem.RequestMetadata
import androidx.media3.common.MediaMetadata
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.*

/**
 * Metadata container and accessor used by player internals.
 *
 * This interface ensures consistency of fetching metadata on each stream,
 * which is encapsulated in a [MediaItem] and delivered via ExoPlayer's
 * [Player.Listener] on event triggers to the downstream users.
 */
interface MediaItemTag {

    val errors: List<Exception?>?

    val serviceId: Int

    val title: String

    val uploaderName: String?

    val durationSeconds: Long

    val streamUrl: String?

    val thumbnailUrl: String?

    val uploaderUrl: String?

    val streamType: StreamType?

    val maybeStreamInfo: Optional<StreamInfo>
        get() = Optional.empty()

    val maybeQuality: Optional<Quality>
        get() = Optional.empty()

    val maybeAudioTrack: Optional<AudioTrack>
        get() = Optional.empty()

    fun <T> getMaybeExtras(type: Class<T>): Optional<T>?

    fun <T> withExtras(extra: T): MediaItemTag?

    fun makeMediaId(): String {
        return UUID.randomUUID().toString() + "[" + title + "]"
    }

    fun asMediaItem(): MediaItem {
        val thumbnailUrl = thumbnailUrl
        val mediaMetadata = MediaMetadata.Builder()
            .setArtworkUri(if (thumbnailUrl == null) null else Uri.parse(thumbnailUrl))
            .setArtist(uploaderName)
            .setDescription(title)
            .setDisplayTitle(title)
            .setTitle(title)
            .build()

        val requestMetaData = RequestMetadata.Builder()
            .setMediaUri(Uri.parse(streamUrl))
            .build()

        return MediaItem.fromUri(streamUrl!!)
            .buildUpon()
            .setMediaId(makeMediaId())
            .setMediaMetadata(mediaMetadata)
            .setRequestMetadata(requestMetaData)
            .setTag(this)
            .build()
    }

    class Quality private constructor(val sortedVideoStreams: List<VideoStream>, val selectedVideoStreamIndex: Int) {
        val selectedVideoStream: VideoStream?
            get() = if (selectedVideoStreamIndex < 0
                    || selectedVideoStreamIndex >= sortedVideoStreams.size) null else sortedVideoStreams[selectedVideoStreamIndex]

        companion object {
            fun of(sortedVideoStreams: List<VideoStream>,
                   selectedVideoStreamIndex: Int
            ): Quality {
                return Quality(sortedVideoStreams, selectedVideoStreamIndex)
            }
        }
    }

    class AudioTrack private constructor(val audioStreams: List<AudioStream>,
                                         val selectedAudioStreamIndex: Int
    ) {
        val selectedAudioStream: AudioStream?
            get() = if (selectedAudioStreamIndex < 0
                    || selectedAudioStreamIndex >= audioStreams.size) null else audioStreams[selectedAudioStreamIndex]

        companion object {
            fun of(audioStreams: List<AudioStream>,
                   selectedAudioStreamIndex: Int
            ): AudioTrack {
                return AudioTrack(audioStreams, selectedAudioStreamIndex)
            }
        }
    }

    companion object {
        fun from(mediaItem: MediaItem?): Optional<MediaItemTag> {
            return Optional.ofNullable(mediaItem)
                .map { item: MediaItem -> item.localConfiguration }
                .map { localConfiguration: LocalConfiguration? -> localConfiguration!!.tag }
                .filter { obj: Any? -> MediaItemTag::class.java.isInstance(obj) }
                .map { obj: Any? -> MediaItemTag::class.java.cast(obj) }
        }
    }
}
