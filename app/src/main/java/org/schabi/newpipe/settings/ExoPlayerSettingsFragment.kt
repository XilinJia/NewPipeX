package org.schabi.newpipe.settings

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import org.schabi.newpipe.R
import java.lang.Boolean
import kotlin.Any
import kotlin.String

class ExoPlayerSettingsFragment : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?,
                                     rootKey: String?
    ) {
        addPreferencesFromResourceRegistry()

        val disabledMediaTunnelingAutomaticallyKey =
            getString(R.string.disabled_media_tunneling_automatically_key)
        val disableMediaTunnelingPref =
            requirePreference(R.string.disable_media_tunneling_key) as SwitchPreferenceCompat
        val prefs = PreferenceManager
            .getDefaultSharedPreferences(requireContext())
        val mediaTunnelingAutomaticallyDisabled =
            prefs.getInt(disabledMediaTunnelingAutomaticallyKey, -1) == 1
        val summaryText = getString(R.string.disable_media_tunneling_summary)
        disableMediaTunnelingPref.summary = if (mediaTunnelingAutomaticallyDisabled
        ) summaryText + " " + getString(R.string.disable_media_tunneling_automatic_info)
        else summaryText

        disableMediaTunnelingPref.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { p: Preference, enabled: Any ->
                if (Boolean.FALSE == enabled) {
                    PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .edit()
                        .putInt(disabledMediaTunnelingAutomaticallyKey, 0)
                        .apply()
                    // the info text might have been shown before
                    p.setSummary(R.string.disable_media_tunneling_summary)
                }
                true
            }
    }
}
