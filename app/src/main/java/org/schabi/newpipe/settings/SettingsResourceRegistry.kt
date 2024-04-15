package org.schabi.newpipe.settings

import androidx.annotation.XmlRes
import androidx.fragment.app.Fragment
import org.schabi.newpipe.R
import org.schabi.newpipe.settings.ContentSettingsFragment
import org.schabi.newpipe.settings.DebugSettingsFragment
import org.schabi.newpipe.settings.DownloadSettingsFragment
import org.schabi.newpipe.settings.HistorySettingsFragment
import org.schabi.newpipe.settings.MainSettingsFragment
import java.util.*

/**
 * A registry that contains information about SettingsFragments.
 * <br></br>
 * includes:
 *
 *  * Class of the SettingsFragment
 *  * XML-Resource
 *  * ...
 *
 *
 * E.g. used by the preference search.
 */
class SettingsResourceRegistry private constructor() {
    private val registeredEntries: MutableSet<SettingRegistryEntry?> = HashSet()

    init {
        add(MainSettingsFragment::class.java, R.xml.main_settings).setSearchable(false)

        add(AppearanceSettingsFragment::class.java, R.xml.appearance_settings)
        add(ContentSettingsFragment::class.java, R.xml.content_settings)
        add(DebugSettingsFragment::class.java, R.xml.debug_settings).setSearchable(false)
        add(DownloadSettingsFragment::class.java, R.xml.download_settings)
        add(HistorySettingsFragment::class.java, R.xml.history_settings)
        add(NotificationSettingsFragment::class.java, R.xml.notifications_settings)
        add(PlayerNotificationSettingsFragment::class.java, R.xml.player_notification_settings)
        add(UpdateSettingsFragment::class.java, R.xml.update_settings)
        add(VideoAudioSettingsFragment::class.java, R.xml.video_audio_settings)
        add(ExoPlayerSettingsFragment::class.java, R.xml.exoplayer_settings)
    }

    private fun add(
            fragmentClass: Class<out Fragment?>,
            @XmlRes preferencesResId: Int
    ): SettingRegistryEntry {
        val entry =
            SettingRegistryEntry(fragmentClass, preferencesResId)
        registeredEntries.add(entry)
        return entry
    }

    fun getEntryByFragmentClass(
            fragmentClass: Class<out Fragment?>
    ): SettingRegistryEntry? {
        Objects.requireNonNull(fragmentClass)
        return registeredEntries.stream()
            .filter { e: SettingRegistryEntry? -> e!!.fragmentClass == fragmentClass }
            .findFirst()
            .orElse(null)
    }

    fun getEntryByPreferencesResId(@XmlRes preferencesResId: Int): SettingRegistryEntry? {
        return registeredEntries.stream()
            .filter { e: SettingRegistryEntry? -> e!!.preferencesResId == preferencesResId }
            .findFirst()
            .orElse(null)
    }

    fun getPreferencesResId(fragmentClass: Class<out Fragment?>): Int {
        val entry = getEntryByFragmentClass(fragmentClass) ?: return -1
        return entry.preferencesResId
    }

    fun getFragmentClass(@XmlRes preferencesResId: Int): Class<out Fragment?>? {
        val entry = getEntryByPreferencesResId(preferencesResId) ?: return null
        return entry.fragmentClass
    }

    val allEntries: Set<SettingRegistryEntry?>
        get() = HashSet(registeredEntries)

    class SettingRegistryEntry(fragmentClass: Class<out Fragment?>,
                               @field:XmlRes @param:XmlRes val preferencesResId: Int
    ) {
        val fragmentClass: Class<out Fragment?> = Objects.requireNonNull(fragmentClass)

        var isSearchable: Boolean = true
            private set

        fun setSearchable(searchable: Boolean): SettingRegistryEntry {
            this.isSearchable = searchable
            return this
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o == null || javaClass != o.javaClass) {
                return false
            }
            val that = o as SettingRegistryEntry
            return preferencesResId == that.preferencesResId && fragmentClass == that.fragmentClass
        }

        override fun hashCode(): Int {
            return Objects.hash(fragmentClass, preferencesResId)
        }
    }

    companion object {
        val instance: SettingsResourceRegistry = SettingsResourceRegistry()
    }
}
