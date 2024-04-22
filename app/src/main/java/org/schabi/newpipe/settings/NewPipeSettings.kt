package org.schabi.newpipe.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.util.DeviceUtils
import org.schabi.newpipe.util.DeviceUtils.isFireTv
import org.schabi.newpipe.util.DeviceUtils.shouldSupportMediaTunneling
import java.io.File

/*
* Created by k3b on 07.01.2016.
*
* Copyright (C) Christian Schabesberger 2015 <chris.schabesberger@mailbox.org>
* NewPipeSettings.java is part of NewPipe.
*
* NewPipe is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* NewPipe is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
*/
/**
 * Helper class for global settings.
 */
object NewPipeSettings {
    @JvmStatic
    fun initSettings(context: Context) {
        // check if the last used preference version is set
        // to determine whether this is the first app run
        val lastUsedPrefVersion = PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(context.getString(R.string.last_used_preferences_version), -1)
        val isFirstRun = lastUsedPrefVersion == -1

        // first run migrations, then setDefaultValues, since the latter requires the correct types
        SettingMigrations.runMigrationsIfNeeded(context, isFirstRun)

        // readAgain is true so that if new settings are added their default value is set
        PreferenceManager.setDefaultValues(context, R.xml.main_settings, true)
        PreferenceManager.setDefaultValues(context, R.xml.video_audio_settings, true)
        PreferenceManager.setDefaultValues(context, R.xml.download_settings, true)
        PreferenceManager.setDefaultValues(context, R.xml.appearance_settings, true)
        PreferenceManager.setDefaultValues(context, R.xml.history_settings, true)
        PreferenceManager.setDefaultValues(context, R.xml.content_settings, true)
        PreferenceManager.setDefaultValues(context, R.xml.player_notification_settings, true)
        PreferenceManager.setDefaultValues(context, R.xml.update_settings, true)
        PreferenceManager.setDefaultValues(context, R.xml.debug_settings, true)

        saveDefaultVideoDownloadDirectory(context)
        saveDefaultAudioDownloadDirectory(context)

        disableMediaTunnelingIfNecessary(context, isFirstRun)
    }

    fun saveDefaultVideoDownloadDirectory(context: Context) {
        saveDefaultDirectory(context, R.string.download_path_video_key,
            Environment.DIRECTORY_MOVIES)
    }

    fun saveDefaultAudioDownloadDirectory(context: Context) {
        saveDefaultDirectory(context, R.string.download_path_audio_key,
            Environment.DIRECTORY_MUSIC)
    }

    private fun saveDefaultDirectory(context: Context, keyID: Int,
                                     defaultDirectoryName: String
    ) {
        if (!useStorageAccessFramework(context)) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val key = context.getString(keyID)
            val downloadPath = prefs.getString(key, null)
            if (!Utils.isNullOrEmpty(downloadPath)) {
                return
            }

            val spEditor = prefs.edit()
            spEditor.putString(key, getNewPipeChildFolderPathForDir(getDir(defaultDirectoryName)))
            spEditor.apply()
        }
    }

    @JvmStatic
    fun getDir(defaultDirectoryName: String?): File {
        return File(Environment.getExternalStorageDirectory(), defaultDirectoryName)
    }

    private fun getNewPipeChildFolderPathForDir(dir: File): String {
        return File(dir, "NewPipe").toURI().toString()
    }

    @JvmStatic
    fun useStorageAccessFramework(context: Context): Boolean {
        // There's a FireOS bug which prevents SAF open/close dialogs from being confirmed with a
        // remote (see #6455).
        if (isFireTv) {
            return false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true    // TODO: if true, always get no app on device can open this
        }

        val key = context.getString(R.string.storage_use_saf)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        return prefs.getBoolean(key, false)
    }

    private fun showSearchSuggestions(context: Context,
                                      sharedPreferences: SharedPreferences,
                                      @StringRes key: Int
    ): Boolean {
        val enabledSearchSuggestions = sharedPreferences.getStringSet(
            context.getString(R.string.show_search_suggestions_key), null)

        return enabledSearchSuggestions?.contains(context.getString(key)) ?: true // defaults to true
    }

    @JvmStatic
    fun showLocalSearchSuggestions(context: Context,
                                   sharedPreferences: SharedPreferences
    ): Boolean {
        return showSearchSuggestions(context, sharedPreferences,
            R.string.show_local_search_suggestions_key)
    }

    @JvmStatic
    fun showRemoteSearchSuggestions(context: Context,
                                    sharedPreferences: SharedPreferences
    ): Boolean {
        return showSearchSuggestions(context, sharedPreferences,
            R.string.show_remote_search_suggestions_key)
    }

    private fun disableMediaTunnelingIfNecessary(context: Context,
                                                 isFirstRun: Boolean
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val disabledTunnelingKey = context.getString(R.string.disable_media_tunneling_key)
        val disabledTunnelingAutomaticallyKey =
            context.getString(R.string.disabled_media_tunneling_automatically_key)
        val blacklistVersionKey =
            context.getString(R.string.media_tunneling_device_blacklist_version)

        val lastMediaTunnelingUpdate = prefs.getInt(blacklistVersionKey, 0)
        val wasDeviceBlacklistUpdated =
            DeviceUtils.MEDIA_TUNNELING_DEVICE_BLACKLIST_VERSION != lastMediaTunnelingUpdate
        val wasMediaTunnelingEnabledByUser = (
                prefs.getInt(disabledTunnelingAutomaticallyKey, -1) == 0
                        && !prefs.getBoolean(disabledTunnelingKey, false))

        if (java.lang.Boolean.TRUE == isFirstRun || (wasDeviceBlacklistUpdated && !wasMediaTunnelingEnabledByUser)) {
            setMediaTunneling(context)
        }
    }

    /**
     * Check if device does not support media tunneling
     * and disable that exoplayer feature if necessary.
     * @see DeviceUtils.shouldSupportMediaTunneling
     * @param context
     */
    fun setMediaTunneling(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!shouldSupportMediaTunneling()) {
            prefs.edit()
                .putBoolean(context.getString(R.string.disable_media_tunneling_key), true)
                .putInt(context.getString(
                    R.string.disabled_media_tunneling_automatically_key), 1)
                .putInt(context.getString(R.string.media_tunneling_device_blacklist_version),
                    DeviceUtils.MEDIA_TUNNELING_DEVICE_BLACKLIST_VERSION)
                .apply()
        } else {
            prefs.edit()
                .putInt(context.getString(R.string.media_tunneling_device_blacklist_version),
                    DeviceUtils.MEDIA_TUNNELING_DEVICE_BLACKLIST_VERSION).apply()
        }
    }
}
