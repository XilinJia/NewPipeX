package org.schabi.newpipe.settings

import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.res.Resources.NotFoundException
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.preference.ListPreference
import com.google.android.material.snackbar.Snackbar
import org.schabi.newpipe.R
import org.schabi.newpipe.util.ListHelper.getSortedResolutionList
import org.schabi.newpipe.util.ListHelper.isHighResolutionSelected
import org.schabi.newpipe.util.PermissionHelper.checkSystemAlertWindowPermission
import java.util.*

class VideoAudioSettingsFragment : BasePreferenceFragment() {
    private var listener: OnSharedPreferenceChangeListener? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResourceRegistry()

        updateSeekOptions()
        updateResolutionOptions()
        listener = OnSharedPreferenceChangeListener { sharedPreferences: SharedPreferences, key: String? ->

            // on M and above, if user chooses to minimise to popup player on exit
            // and the app doesn't have display over other apps permission,
            // show a snackbar to let the user give permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && getString(R.string.minimize_on_exit_key) == key) {
                val newSetting = sharedPreferences.getString(key, null)
                if (newSetting != null && newSetting == getString(R.string.minimize_on_exit_popup_key) && !Settings.canDrawOverlays(
                            context)) {
                    Snackbar.make(listView, R.string.permission_display_over_apps,
                        Snackbar.LENGTH_INDEFINITE)
                        .setAction(R.string.settings) { view: View? ->
                            checkSystemAlertWindowPermission(requireContext())
                        }
                        .show()
                }
            } else if (getString(R.string.use_inexact_seek_key) == key) {
                updateSeekOptions()
            } else if (getString(R.string.show_higher_resolutions_key) == key) {
                updateResolutionOptions()
            }
        }
    }

    /**
     * Update default resolution, default popup resolution & mobile data resolution options.
     * <br></br>
     * Show high resolutions when "Show higher resolution" option is enabled.
     * Set default resolution to "best resolution" when "Show higher resolution" option
     * is disabled.
     */
    private fun updateResolutionOptions() {
        val resources = resources
        val showHigherResolutions = preferenceManager.sharedPreferences!!.getBoolean(resources.getString(R.string.show_higher_resolutions_key), false)

        // get sorted resolution lists
        val resolutionListDescriptions = getSortedResolutionList(
            resources,
            R.array.resolution_list_description,
            R.array.high_resolution_list_descriptions,
            showHigherResolutions)
        val resolutionListValues = getSortedResolutionList(
            resources,
            R.array.resolution_list_values,
            R.array.high_resolution_list_values,
            showHigherResolutions)
        val limitDataUsageResolutionValues = getSortedResolutionList(
            resources,
            R.array.limit_data_usage_values_list,
            R.array.high_resolution_limit_data_usage_values_list,
            showHigherResolutions)
        val limitDataUsageResolutionDescriptions = getSortedResolutionList(resources,
            R.array.limit_data_usage_description_list,
            R.array.high_resolution_list_descriptions,
            showHigherResolutions)

        // get resolution preferences
        val defaultResolution = findPreference<ListPreference>(
            getString(R.string.default_resolution_key))
        val defaultPopupResolution = findPreference<ListPreference>(
            getString(R.string.default_popup_resolution_key))
        val mobileDataResolution = findPreference<ListPreference>(
            getString(R.string.limit_mobile_data_usage_key))

        // update resolution preferences with new resolutions, entries & values for each
        defaultResolution!!.entries = resolutionListDescriptions.toTypedArray<String>()
        defaultResolution.entryValues = resolutionListValues.toTypedArray<String>()
        defaultPopupResolution!!.entries = resolutionListDescriptions.toTypedArray<String>()
        defaultPopupResolution.entryValues = resolutionListValues.toTypedArray<String>()
        mobileDataResolution!!.entries = limitDataUsageResolutionDescriptions.toTypedArray<String>()
        mobileDataResolution.entryValues = limitDataUsageResolutionValues.toTypedArray<String>()

        // if "Show higher resolution" option is disabled,
        // set default resolution to "best resolution"
        if (!showHigherResolutions) {
            if (isHighResolutionSelected(defaultResolution.value,
                        R.array.high_resolution_list_values,
                        resources)) {
                defaultResolution.setValueIndex(0)
            }
            if (isHighResolutionSelected(defaultPopupResolution.value,
                        R.array.high_resolution_list_values,
                        resources)) {
                defaultPopupResolution.setValueIndex(0)
            }
            if (isHighResolutionSelected(mobileDataResolution.value,
                        R.array.high_resolution_limit_data_usage_values_list,
                        resources)) {
                mobileDataResolution.setValueIndex(0)
            }
        }
    }

    /**
     * Update fast-forward/-rewind seek duration options
     * according to language and inexact seek setting.
     * Exoplayer can't seek 5 seconds in audio when using inexact seek.
     */
    private fun updateSeekOptions() {
        // initializing R.array.seek_duration_description to display the translation of seconds
        val res = resources
        val durationsValues = res.getStringArray(R.array.seek_duration_value)
        val displayedDurationValues: MutableList<String> = LinkedList()
        val displayedDescriptionValues: MutableList<String> = LinkedList()
        var currentDurationValue: Int
        val inexactSeek = preferenceManager.sharedPreferences!!.getBoolean(res.getString(R.string.use_inexact_seek_key), false)

        for (durationsValue in durationsValues) {
            currentDurationValue =
                durationsValue.toInt() / DateUtils.SECOND_IN_MILLIS.toInt()
            if (inexactSeek && currentDurationValue % 10 == 5) {
                continue
            }

            displayedDurationValues.add(durationsValue)
            try {
                displayedDescriptionValues.add(String.format(
                    res.getQuantityString(R.plurals.seconds,
                        currentDurationValue),
                    currentDurationValue))
            } catch (ignored: NotFoundException) {
                // if this happens, the translation is missing,
                // and the english string will be displayed instead
            }
        }

        val durations = findPreference<ListPreference>(
            getString(R.string.seek_duration_key))
        durations!!.entryValues = displayedDurationValues.toTypedArray<CharSequence>()
        durations.entries = displayedDescriptionValues.toTypedArray<CharSequence>()
        val selectedDuration = durations.value.toInt()
        if (inexactSeek && selectedDuration / DateUtils.SECOND_IN_MILLIS.toInt() % 10 == 5) {
            val newDuration = selectedDuration / DateUtils.SECOND_IN_MILLIS.toInt() + 5
            durations.value = (newDuration * DateUtils.SECOND_IN_MILLIS.toInt()).toString()

            val toast = Toast
                .makeText(context,
                    getString(R.string.new_seek_duration_toast, newDuration),
                    Toast.LENGTH_LONG)
            toast.show()
        }
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences!!.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onPause() {
        super.onPause()
        preferenceManager.sharedPreferences!!.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
