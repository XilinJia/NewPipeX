package org.schabi.newpipe.player.mediaitem

import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.util.image.ImageStrategy.choosePreferredImage
import java.util.*

/**
 * This [MediaItemTag] object contains metadata for a resolved stream
 * that is ready for playback. This object guarantees the [StreamInfo]
 * is available and may provide the [Quality] of video stream used in
 * the [MediaItem].
 */
class StreamInfoTag private constructor(private val streamInfo: StreamInfo,
                                        private val quality: MediaItemTag.Quality?,
                                        private val audioTrack: MediaItemTag.AudioTrack?,
                                        private val extras: Any?
) : MediaItemTag {
    override val errors: List<Exception>
        get() = emptyList()

    override val serviceId: Int
        get() = streamInfo.serviceId

    override val title: String
        get() = streamInfo.name

    override val uploaderName: String
        get() = streamInfo.uploaderName

    override val durationSeconds: Long
        get() = streamInfo.duration

    override val streamUrl: String
        get() = streamInfo.url

    override val thumbnailUrl: String?
        get() = choosePreferredImage(streamInfo.thumbnails)

    override val uploaderUrl: String
        get() = streamInfo.uploaderUrl

    override val streamType: StreamType
        get() = streamInfo.streamType

    override val maybeStreamInfo: Optional<StreamInfo>
        get() = Optional.of(streamInfo)

    override val maybeQuality: Optional<MediaItemTag.Quality>
        get() = Optional.ofNullable(quality)

    override val maybeAudioTrack: Optional<MediaItemTag.AudioTrack>
        get() = Optional.ofNullable(audioTrack)

    override fun <T> getMaybeExtras(type: Class<T>): Optional<T>? {
        return Optional.ofNullable(extras).map { obj: Any? -> type.cast(obj) }
    }

    override fun <T> withExtras(extra: T): StreamInfoTag {
        return StreamInfoTag(streamInfo, quality, audioTrack, extra)
    }

    companion object {
        fun of(streamInfo: StreamInfo,
               sortedVideoStreams: List<VideoStream>,
               selectedVideoStreamIndex: Int,
               audioStreams: List<AudioStream>,
               selectedAudioStreamIndex: Int
        ): StreamInfoTag {
            val quality = MediaItemTag.Quality.of(sortedVideoStreams, selectedVideoStreamIndex)
            val audioTrack = MediaItemTag.AudioTrack.of(audioStreams, selectedAudioStreamIndex)
            return StreamInfoTag(streamInfo, quality, audioTrack, null)
        }

        fun of(streamInfo: StreamInfo, audioStreams: List<AudioStream>, selectedAudioStreamIndex: Int): StreamInfoTag {
            val audioTrack = MediaItemTag.AudioTrack.of(audioStreams, selectedAudioStreamIndex)
            return StreamInfoTag(streamInfo, null, audioTrack, null)
        }

        fun of(streamInfo: StreamInfo): StreamInfoTag {
            return StreamInfoTag(streamInfo, null, null, null)
        }
    }
}
