package org.schabi.newpipe.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.preference.Preference
import org.schabi.newpipe.R
import org.schabi.newpipe.util.KEY_THEME_CHANGE
import org.schabi.newpipe.util.ThemeHelper.setDayNightMode

class AppearanceSettingsFragment : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResourceRegistry()

        val themeKey = getString(R.string.theme_key)
        // the key of the active theme when settings were opened (or recreated after theme change)
        val startThemeKey = defaultPreferences.getString(themeKey, getString(R.string.default_theme_value))
        val autoDeviceThemeKey = getString(R.string.auto_device_theme_key)
        findPreference<Preference>(themeKey)!!.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { preference: Preference?, newValue: Any ->
                if (newValue.toString() == autoDeviceThemeKey) {
                    Toast.makeText(context, getString(R.string.select_night_theme_toast),
                        Toast.LENGTH_LONG).show()
                }
                applyThemeChange(startThemeKey, themeKey, newValue)
                false
            }

        val nightThemeKey = getString(R.string.night_theme_key)
        if (startThemeKey == autoDeviceThemeKey) {
            val startNightThemeKey = defaultPreferences.getString(nightThemeKey, getString(R.string.default_night_theme_value))

            findPreference<Preference>(nightThemeKey)!!.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { preference: Preference?, newValue: Any ->
                    applyThemeChange(startNightThemeKey, nightThemeKey, newValue)
                    false
                }
        } else {
            // disable the night theme selection
            val preference = findPreference<Preference>(nightThemeKey)
            if (preference != null) {
                preference.isEnabled = false
                preference.summary = getString(R.string.night_theme_available,
                    getString(R.string.auto_device_theme_title))
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (getString(R.string.caption_settings_key) == preference.key) {
            try {
                startActivity(Intent(Settings.ACTION_CAPTIONING_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.general_error, Toast.LENGTH_SHORT).show()
            }
        }

        return super.onPreferenceTreeClick(preference)
    }

    private fun applyThemeChange(beginningThemeKey: String?,
                                 themeKey: String,
                                 newValue: Any
    ) {
        defaultPreferences.edit().putBoolean(KEY_THEME_CHANGE, true).apply()
        defaultPreferences.edit().putString(themeKey, newValue.toString()).apply()

        setDayNightMode(requireContext(), newValue.toString())

        if (newValue != beginningThemeKey && activity != null) {
            // if it's not the current theme
            ActivityCompat.recreate(requireActivity())
        }
    }
}
