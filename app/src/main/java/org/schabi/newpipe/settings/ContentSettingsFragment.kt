package org.schabi.newpipe.settings

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import org.schabi.newpipe.DownloaderImpl.Companion.instance
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ErrorUtil.Companion.showUiErrorSnackbar
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.streams.io.NoFileManagerSafeGuard.launchSafe
import org.schabi.newpipe.streams.io.StoredFileHelper
import org.schabi.newpipe.streams.io.StoredFileHelper.Companion.getNewPicker
import org.schabi.newpipe.streams.io.StoredFileHelper.Companion.getPicker
import org.schabi.newpipe.util.Localization.assureCorrectAppLanguage
import org.schabi.newpipe.util.Localization.getPreferredContentCountry
import org.schabi.newpipe.util.Localization.getPreferredLocalization
import org.schabi.newpipe.util.NavigationHelper.restartApp
import org.schabi.newpipe.util.ZipHelper.isValidZipFile
import org.schabi.newpipe.util.image.ImageStrategy.setPreferredImageQuality
import org.schabi.newpipe.util.image.PicassoHelper.clearCache
import org.schabi.newpipe.util.image.PreferredImageQuality.Companion.fromPreferenceKey
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class ContentSettingsFragment : BasePreferenceFragment() {
    private val exportDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private var manager: ContentSettingsManager? = null

    private var importExportDataPathKey: String? = null
    private var youtubeRestrictedModeEnabledKey: String? = null

    private var initialSelectedLocalization: Localization? = null
    private var initialSelectedContentCountry: ContentCountry? = null
    private var initialLanguage: String? = null
    private val requestImportPathLauncher =
        registerForActivityResult<Intent, ActivityResult>(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            this.requestImportPathResult(result)
        }
    private val requestExportPathLauncher =
        registerForActivityResult<Intent, ActivityResult>(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            this.requestExportPathResult(result)
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val homeDir = ContextCompat.getDataDir(requireContext())
        Objects.requireNonNull(homeDir)
        manager = ContentSettingsManager(NewPipeFileLocator(homeDir!!))
        manager!!.deleteSettingsFile()

        importExportDataPathKey = getString(R.string.import_export_data_path)
        youtubeRestrictedModeEnabledKey = getString(R.string.youtube_restricted_mode_enabled)

        addPreferencesFromResourceRegistry()

        val importDataPreference = requirePreference(R.string.import_data)
        importDataPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener { p: Preference? ->
            launchSafe(
                requestImportPathLauncher,
                getPicker(requireContext(),
                    ZIP_MIME_TYPE, importExportDataUri),
                TAG,
                context
            )
            true
        }

        val exportDataPreference = requirePreference(R.string.export_data)
        exportDataPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener { p: Preference? ->
            launchSafe(
                requestExportPathLauncher,
                getNewPicker(requireContext(),
                    "NewPipeData-" + exportDateFormat.format(Date()) + ".zip",
                    ZIP_MIME_TYPE, importExportDataUri),
                TAG,
                context
            )
            true
        }

        initialSelectedLocalization = getPreferredLocalization(requireContext())
        initialSelectedContentCountry = getPreferredContentCountry(requireContext())
        initialLanguage = defaultPreferences.getString(getString(R.string.app_language_key), "en")

        val imageQualityPreference = requirePreference(R.string.image_quality_key)
        imageQualityPreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { preference: Preference, newValue: Any? ->
                setPreferredImageQuality(fromPreferenceKey(requireContext(), (newValue as String?)!!))
                try {
                    clearCache(preference.context)
                    Toast.makeText(preference.context,
                        R.string.thumbnail_cache_wipe_complete_notice, Toast.LENGTH_SHORT)
                        .show()
                } catch (e: IOException) {
                    Log.e(TAG, "Unable to clear Picasso cache", e)
                }
                true
            }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == youtubeRestrictedModeEnabledKey) {
            val context = context
            if (context != null) {
                instance!!.updateYoutubeRestrictedModeCookies(context)
            } else {
                Log.w(TAG, "onPreferenceTreeClick: null context")
            }
        }

        return super.onPreferenceTreeClick(preference)
    }

    override fun onDestroy() {
        super.onDestroy()

        val selectedLocalization = getPreferredLocalization(requireContext())
        val selectedContentCountry = getPreferredContentCountry(requireContext())
        val selectedLanguage = defaultPreferences.getString(getString(R.string.app_language_key), "en")

        if (selectedLocalization != initialSelectedLocalization
                || selectedContentCountry != initialSelectedContentCountry
                || selectedLanguage != initialLanguage) {
            Toast.makeText(requireContext(), R.string.localization_changes_requires_app_restart,
                Toast.LENGTH_LONG).show()

            NewPipe.setupLocalization(selectedLocalization, selectedContentCountry)
        }
    }

    private fun requestExportPathResult(result: ActivityResult) {
        assureCorrectAppLanguage(context!!)
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // will be saved only on success
            val lastExportDataUri = result.data!!.data

            val file =
                StoredFileHelper(context, result.data!!.data!!, ZIP_MIME_TYPE)

            exportDatabase(file, lastExportDataUri)
        }
    }

    private fun requestImportPathResult(result: ActivityResult) {
        assureCorrectAppLanguage(context!!)
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // will be saved only on success
            val lastImportDataUri = result.data!!.data

            val file =
                StoredFileHelper(context, result.data!!.data!!, ZIP_MIME_TYPE)

            AlertDialog.Builder(requireActivity())
                .setMessage(R.string.override_current_data)
                .setPositiveButton(R.string.ok) { d: DialogInterface?, id: Int ->
                    importDatabase(file,
                        lastImportDataUri)
                }
                .setNegativeButton(R.string.cancel) { d: DialogInterface, id: Int -> d.cancel() }
                .show()
        }
    }

    private fun exportDatabase(file: StoredFileHelper, exportDataUri: Uri?) {
        try {
            //checkpoint before export
            NewPipeDatabase.checkpoint()

            val preferences = PreferenceManager
                .getDefaultSharedPreferences(requireContext())
            manager!!.exportDatabase(preferences, file)

            saveLastImportExportDataUri(exportDataUri) // save export path only on success
            Toast.makeText(context, R.string.export_complete_toast, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            showUiErrorSnackbar(this, "Exporting database", e)
        }
    }

    private fun importDatabase(file: StoredFileHelper, importDataUri: Uri?) {
        // check if file is supported
        if (!isValidZipFile(file)) {
            Toast.makeText(context, R.string.no_valid_zip_file, Toast.LENGTH_SHORT)
                .show()
            return
        }

        try {
            if (!manager!!.ensureDbDirectoryExists()) {
                throw IOException("Could not create databases dir")
            }

            if (!manager!!.extractDb(file)) {
                Toast.makeText(context, R.string.could_not_import_all_files, Toast.LENGTH_LONG)
                    .show()
            }

            // if settings file exist, ask if it should be imported.
            if (manager!!.extractSettings(file)) {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.import_settings)
                    .setNegativeButton(R.string.cancel) { dialog: DialogInterface, which: Int ->
                        dialog.dismiss()
                        finishImport(importDataUri)
                    }
                    .setPositiveButton(R.string.ok) { dialog: DialogInterface, which: Int ->
                        dialog.dismiss()
                        val context = requireContext()
                        val prefs = PreferenceManager
                            .getDefaultSharedPreferences(context)
                        manager!!.loadSharedPreferences(prefs)
                        cleanImport(context, prefs)
                        finishImport(importDataUri)
                    }
                    .show()
            } else {
                finishImport(importDataUri)
            }
        } catch (e: Exception) {
            showUiErrorSnackbar(this, "Importing database", e)
        }
    }

    /**
     * Remove settings that are not supposed to be imported on different devices
     * and reset them to default values.
     * @param context the context used for the import
     * @param prefs the preferences used while running the import
     */
    private fun cleanImport(context: Context,
                            prefs: SharedPreferences
    ) {
        // Check if media tunnelling needs to be disabled automatically,
        // if it was disabled automatically in the imported preferences.
        val tunnelingKey = context.getString(R.string.disable_media_tunneling_key)
        val automaticTunnelingKey =
            context.getString(R.string.disabled_media_tunneling_automatically_key)
        // R.string.disable_media_tunneling_key should always be true
        // if R.string.disabled_media_tunneling_automatically_key equals 1,
        // but we double check here just to be sure and to avoid regressions
        // caused by possible later modification of the media tunneling functionality.
        // R.string.disabled_media_tunneling_automatically_key == 0:
        //     automatic value overridden by user in settings
        // R.string.disabled_media_tunneling_automatically_key == -1: not set
        val wasMediaTunnelingDisabledAutomatically = (
                prefs.getInt(automaticTunnelingKey, -1) == 1
                        && prefs.getBoolean(tunnelingKey, false))
        if (wasMediaTunnelingDisabledAutomatically) {
            prefs.edit()
                .putInt(automaticTunnelingKey, -1)
                .putBoolean(tunnelingKey, false)
                .apply()
            NewPipeSettings.setMediaTunneling(context)
        }
    }

    /**
     * Save import path and restart system.
     *
     * @param importDataUri The import path to save
     */
    private fun finishImport(importDataUri: Uri?) {
        // save import path only on success
        saveLastImportExportDataUri(importDataUri)
        // restart app to properly load db
        restartApp(requireActivity())
    }

    private val importExportDataUri: Uri?
        get() {
            val path = defaultPreferences.getString(importExportDataPathKey, null)
            return if (Utils.isBlank(path)) null else Uri.parse(path)
        }

    private fun saveLastImportExportDataUri(importExportDataUri: Uri?) {
        val editor = defaultPreferences.edit()
            .putString(importExportDataPathKey, importExportDataUri.toString())
        editor.apply()
    }

    companion object {
        private const val ZIP_MIME_TYPE = "application/zip"
    }
}
