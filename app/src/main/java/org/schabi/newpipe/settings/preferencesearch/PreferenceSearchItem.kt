package org.schabi.newpipe.settings.preferencesearch

import androidx.annotation.XmlRes
import java.util.*

/**
 * Represents a preference-item inside the search.
 */
class PreferenceSearchItem(key: String,
                           title: String,
                           summary: String,
                           entries: String,
                           breadcrumbs: String,
                           /**
                            * The xml-resource where this item was found/built from.
                            */
                           @field:XmlRes @param:XmlRes val searchIndexItemResId: Int
) {
    /**
     * Key of the setting/preference. E.g. used inside [android.content.SharedPreferences].
     */
    @JvmField
    val key: String = Objects.requireNonNull(key)

    /**
     * Title of the setting, e.g. 'Default resolution' or 'Show higher resolutions'.
     */
    val title: String = Objects.requireNonNull(title)

    /**
     * Summary of the setting, e.g. '480p' or 'Only some devices can play 2k/4k'.
     */
    val summary: String = Objects.requireNonNull(summary)

    /**
     * Possible entries of the setting, e.g. 480p,720p,...
     */
    val entries: String = Objects.requireNonNull(entries)

    /**
     * Breadcrumbs - a hint where the setting is located e.g. 'Video and Audio > Player'
     */
    val breadcrumbs: String = Objects.requireNonNull(breadcrumbs)

    fun hasData(): Boolean {
        return !key.isEmpty() && !title.isEmpty()
    }

    val allRelevantSearchFields: List<String>
        get() = listOf(title, summary, entries, breadcrumbs)

    override fun toString(): String {
        return "PreferenceItem: $title $summary $key"
    }
}
