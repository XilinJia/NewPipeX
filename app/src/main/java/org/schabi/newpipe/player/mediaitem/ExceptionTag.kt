package org.schabi.newpipe.player.mediaitem

import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.playqueue.PlayQueueItem
import org.schabi.newpipe.util.image.ImageStrategy.choosePreferredImage
import java.util.*

/**
 * This [MediaItemTag] object is designed to contain metadata for a stream
 * that has failed to load. It supplies metadata from an underlying
 * [PlayQueueItem], which is used by the internal players to resolve actual
 * playback info.
 *
 * This [MediaItemTag] does not contain any [StreamInfo] that can be
 * used to start playback and can be detected by checking [ExceptionTag.getErrors]
 * when in generic form.
 */
class ExceptionTag private constructor(private val item: PlayQueueItem,
                                       override val errors: List<Exception>,
                                       private val extras: Any?
) : MediaItemTag {
    override val serviceId: Int
        get() = item.serviceId

    override val title: String
        get() = item.title

    override val uploaderName: String
        get() = item.uploader

    override val durationSeconds: Long
        get() = item.duration

    override val streamUrl: String
        get() = item.url

    override val thumbnailUrl: String?
        get() = choosePreferredImage(item.thumbnails)

    override val uploaderUrl: String
        get() = item.uploaderUrl

    override val streamType: StreamType
        get() = item.streamType

    override fun <T> getMaybeExtras(type: Class<T>): Optional<T>? {
        return Optional.ofNullable(extras).map { obj: Any? -> type.cast(obj) }
    }

    override fun <T> withExtras(extra: T): MediaItemTag {
        return ExceptionTag(item, errors, extra)
    }

    companion object {
        fun of(playQueueItem: PlayQueueItem,
               errors: List<Exception>
        ): ExceptionTag {
            return ExceptionTag(playQueueItem, errors, null)
        }
    }
}
