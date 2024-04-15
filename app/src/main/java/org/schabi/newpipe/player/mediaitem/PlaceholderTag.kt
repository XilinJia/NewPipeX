package org.schabi.newpipe.player.mediaitem

import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.util.NO_SERVICE_ID
import java.util.*

/**
 * This is a Placeholding [MediaItemTag], designed as a dummy metadata object for
 * any stream that has not been resolved.
 *
 * This object cannot be instantiated and does not hold real metadata of any form.
 */
class PlaceholderTag private constructor(private val extras: Any?) : MediaItemTag {
    override val errors: List<Exception>
        get() = emptyList()

    override val serviceId: Int
        get() = NO_SERVICE_ID

    override val title: String
        get() = UNKNOWN_VALUE_INTERNAL

    override val uploaderName: String
        get() = UNKNOWN_VALUE_INTERNAL

    override val streamUrl: String
        get() = UNKNOWN_VALUE_INTERNAL

    override val thumbnailUrl: String
        get() = UNKNOWN_VALUE_INTERNAL

    override val durationSeconds: Long
        get() = 0

    override val streamType: StreamType
        get() = StreamType.NONE

    override val uploaderUrl: String
        get() = PlaceholderTag.UNKNOWN_VALUE_INTERNAL

    override fun <T> getMaybeExtras(type: Class<T>): Optional<T>? {
        return Optional.ofNullable(extras).map { obj: Any? -> type.cast(obj) }
    }

    override fun <T> withExtras(extra: T): MediaItemTag? {
        return PlaceholderTag(extra)
    }

    companion object {
        @JvmField
        val EMPTY: PlaceholderTag = PlaceholderTag(null)
        private val UNKNOWN_VALUE_INTERNAL: String = "Placeholder"
    }
}
