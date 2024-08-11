package org.schabi.newpipe.settings

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.giga.io.NoFileManagerSafeGuard.launchSafe
import org.schabi.newpipe.giga.io.StoredDirectoryHelper
import org.schabi.newpipe.giga.io.StoredDirectoryHelper.Companion.getPicker
import org.schabi.newpipe.util.FilePickerActivityHelper.Companion.isOwnFileUri
import org.schabi.newpipe.util.Localization.assureCorrectAppLanguage
import org.schabi.newpipe.util.Logd
import java.io.File
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.URI

class DownloadSettingsFragment : BasePreferenceFragment() {
    private var downloadPathVideoPreference: String? = null
    private var downloadPathAudioPreference: String? = null
    private var storageUseSafPreference: String? = null

    private var prefPathVideo: Preference? = null
    private var prefPathAudio: Preference? = null
    private var prefStorageAsk: Preference? = null

    private var ctx: Context? = null
    private val requestDownloadVideoPathLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result: ActivityResult -> this.requestDownloadVideoPathResult(result) }
    private val requestDownloadAudioPathLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result: ActivityResult -> this.requestDownloadAudioPathResult(result) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResourceRegistry()

        downloadPathVideoPreference = getString(R.string.download_path_video_key)
        downloadPathAudioPreference = getString(R.string.download_path_audio_key)
        storageUseSafPreference = getString(R.string.storage_use_saf)
        val downloadStorageAsk = getString(R.string.downloads_storage_ask)

        prefPathVideo = findPreference(downloadPathVideoPreference!!)
        prefPathAudio = findPreference(downloadPathAudioPreference!!)
        prefStorageAsk = findPreference(downloadStorageAsk)

        val prefUseSaf = findPreference<SwitchPreferenceCompat>(storageUseSafPreference!!)
        prefUseSaf!!.isChecked = NewPipeSettings.useStorageAccessFramework(requireContext())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            prefUseSaf.isEnabled = false
            prefUseSaf.setSummary(R.string.downloads_storage_use_saf_summary_api_29)
            prefStorageAsk?.setSummary(R.string.downloads_storage_ask_summary_no_saf_notice)
        }

        updatePreferencesSummary()
        updatePathPickers(!defaultPreferences.getBoolean(downloadStorageAsk, false))

        if (hasInvalidPath(downloadPathVideoPreference!!) || hasInvalidPath(downloadPathAudioPreference!!)) {
            updatePreferencesSummary()
        }

        prefStorageAsk?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { preference: Preference?, value: Any? ->
                updatePathPickers(!(value as Boolean))
                true
            }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        ctx = context
    }

    override fun onDetach() {
        super.onDetach()
        ctx = null
        prefStorageAsk?.onPreferenceChangeListener = null
    }

    private fun updatePreferencesSummary() {
        showPathInSummary(downloadPathVideoPreference, R.string.download_path_summary, prefPathVideo)
        showPathInSummary(downloadPathAudioPreference, R.string.download_path_audio_summary, prefPathAudio)
    }

    private fun showPathInSummary(prefKey: String?, @StringRes defaultString: Int,
                                  target: Preference?
    ) {
        var rawUri = defaultPreferences.getString(prefKey, null)
        if (rawUri.isNullOrEmpty()) {
            target!!.summary = getString(defaultString)
            return
        }

        if (rawUri[0] == File.separatorChar) {
            target!!.summary = rawUri
            return
        }
        if (rawUri.startsWith(ContentResolver.SCHEME_FILE)) {
            target!!.summary = File(URI.create(rawUri)).path
            return
        }

        try {
            rawUri = Utils.decodeUrlUtf8(rawUri)
        } catch (e: UnsupportedEncodingException) {
            // nothing to do
        }

        target!!.summary = rawUri
    }

    private fun isFileUri(path: String): Boolean {
        return path[0] == File.separatorChar || path.startsWith(ContentResolver.SCHEME_FILE)
    }

    private fun hasInvalidPath(prefKey: String): Boolean {
        val value = defaultPreferences.getString(prefKey, null)
        return value.isNullOrEmpty()
    }

    private fun updatePathPickers(enabled: Boolean) {
        prefPathVideo?.isEnabled = enabled
        prefPathAudio?.isEnabled = enabled
    }

    // FIXME: after releasing the old path, all downloads created on the folder becomes inaccessible
    private fun forgetSAFTree(context: Context, oldPath: String?) {
        if (IGNORE_RELEASE_ON_OLD_PATH) return

        if (oldPath.isNullOrEmpty() || isFileUri(oldPath)) return

        try {
            val uri = Uri.parse(oldPath)

            context.contentResolver.releasePersistableUriPermission(uri, StoredDirectoryHelper.PERMISSION_FLAGS)
            context.revokeUriPermission(uri, StoredDirectoryHelper.PERMISSION_FLAGS)

            Log.i(TAG, "Revoke old path permissions success on $oldPath")
        } catch (err: Exception) {
            Log.e(TAG, "Error revoking old path permissions on $oldPath", err)
        }
    }

    private fun showMessageDialog(@StringRes title: Int, @StringRes message: Int) {
        AlertDialog.Builder(ctx!!)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        Logd(TAG, "onPreferenceTreeClick() called with: preference = [$preference]")
        val key = preference.key

        when (key) {
            storageUseSafPreference -> {
                if (!NewPipeSettings.useStorageAccessFramework(requireContext())) {
                    NewPipeSettings.saveDefaultVideoDownloadDirectory(requireContext())
                    NewPipeSettings.saveDefaultAudioDownloadDirectory(requireContext())
                } else {
                    defaultPreferences.edit().putString(downloadPathVideoPreference, null)
                        .putString(downloadPathAudioPreference, null).apply()
                }
                updatePreferencesSummary()
                return true
            }
            downloadPathVideoPreference -> launchDirectoryPicker(requestDownloadVideoPathLauncher)
            downloadPathAudioPreference -> launchDirectoryPicker(requestDownloadAudioPathLauncher)
            else -> return super.onPreferenceTreeClick(preference)
        }

        return true
    }

    private fun launchDirectoryPicker(launcher: ActivityResultLauncher<Intent>) {
        launchSafe(launcher, getPicker(requireContext()), TAG, ctx)
    }

    private fun requestDownloadVideoPathResult(result: ActivityResult) {
        requestDownloadPathResult(result, downloadPathVideoPreference)
    }

    private fun requestDownloadAudioPathResult(result: ActivityResult) {
        requestDownloadPathResult(result, downloadPathAudioPreference)
    }

    private fun requestDownloadPathResult(result: ActivityResult, key: String?) {
        assureCorrectAppLanguage(requireContext())

        if (result.resultCode != Activity.RESULT_OK) return

        var uri: Uri? = null
        if (result.data != null) uri = result.data!!.data

        if (uri == null) {
            showMessageDialog(R.string.general_error, R.string.invalid_directory)
            return
        }

        // revoke permissions on the old save path (required for SAF only)
        val context = requireContext()

        forgetSAFTree(context, defaultPreferences.getString(key, ""))

        if (!isOwnFileUri(context, uri)) {
            // steps to acquire the selected path:
            //     1. acquire permissions on the new save path
            //     2. save the new path, if step(2) was successful
            try {
                context.grantUriPermission(context.packageName, uri, StoredDirectoryHelper.PERMISSION_FLAGS)

                val mainStorage = StoredDirectoryHelper(context, uri, "")
                Log.i(TAG, "Acquiring tree success from $uri")

                if (!mainStorage.canWrite()) throw IOException("No write permissions on $uri")
            } catch (err: IOException) {
                Log.e(TAG, "Error acquiring tree from $uri", err)
                showMessageDialog(R.string.general_error, R.string.no_available_dir)
                return
            }
        } else {
            val target = com.nononsenseapps.filepicker.Utils.getFileForUri(uri)
            if (!target.canWrite()) {
                showMessageDialog(R.string.download_to_sdcard_error_title, R.string.download_to_sdcard_error_message)
                return
            }
            uri = Uri.fromFile(target)
        }

        defaultPreferences.edit().putString(key, uri.toString()).apply()
        updatePreferencesSummary()
    }

    companion object {
        const val IGNORE_RELEASE_ON_OLD_PATH: Boolean = true
    }
}
