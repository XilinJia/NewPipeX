package org.schabi.newpipe.util.image

import android.content.Context
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.Image.ResolutionLevel

enum class PreferredImageQuality {
    NONE,
    LOW,
    MEDIUM,
    HIGH;

    fun toResolutionLevel(): ResolutionLevel {
        return when (this) {
            LOW -> ResolutionLevel.LOW
            MEDIUM -> ResolutionLevel.MEDIUM
            HIGH -> ResolutionLevel.HIGH
            NONE -> ResolutionLevel.UNKNOWN
            else -> ResolutionLevel.UNKNOWN
        }
    }

    companion object {
        @JvmStatic
        fun fromPreferenceKey(context: Context, key: String): PreferredImageQuality {
            return if (context.getString(R.string.image_quality_none_key) == key) {
                NONE
            } else if (context.getString(R.string.image_quality_low_key) == key) {
                LOW
            } else if (context.getString(R.string.image_quality_high_key) == key) {
                HIGH
            } else {
                MEDIUM // default to medium
            }
        }
    }
}
