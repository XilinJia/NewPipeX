 /*
 * Copyright 2017 Mauricio Colli <mauriciocolli@outlook.com>
 * Part of NewPipe
 *
 * License: GPL-3.0+
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.schabi.newpipe.player

import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import org.schabi.newpipe.player.notification.NotificationPlayerUi
import org.schabi.newpipe.util.Localization.assureCorrectAppLanguage
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.util.ThemeHelper.setTheme
import java.lang.ref.WeakReference

/**
 * One service for all players.
 */
@OptIn(UnstableApi::class)
class PlayerService : Service() {
    private var playerManager: PlayerManager? = null

    private val mBinder: IBinder = LocalBinder(this)

    /*//////////////////////////////////////////////////////////////////////////
    // Service's LifeCycle
    ////////////////////////////////////////////////////////////////////////// */
    @OptIn(UnstableApi::class) override fun onCreate() {
        Logd(TAG, "onCreate() called")
        assureCorrectAppLanguage(this)
        setTheme(this)

        playerManager = PlayerManager(this)
        /*
        Create the player notification and start immediately the service in foreground,
        otherwise if nothing is played or initializing the player and its components (especially
        loading stream metadata) takes a lot of time, the app would crash on Android 8+ as the
        service would never be put in the foreground while we said to the system we would do so
         */
        playerManager!!.UIs.get(NotificationPlayerUi::class.java).ifPresent { obj: NotificationPlayerUi -> obj.createNotificationAndStartForeground() }
    }

    @OptIn(UnstableApi::class) override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logd(TAG, "onStartCommand() called with: intent = [$intent], flags = [$flags], startId = [$startId]")

        /*
        Be sure that the player notification is set and the service is started in foreground,
        otherwise, the app may crash on Android 8+ as the service would never be put in the
        foreground while we said to the system we would do so
        The service is always requested to be started in foreground, so always creating a
        notification if there is no one already and starting the service in foreground should
        not create any issues
        If the service is already started in foreground, requesting it to be started shouldn't
        do anything
         */
        playerManager?.UIs?.get(NotificationPlayerUi::class.java)?.ifPresent { obj: NotificationPlayerUi -> obj.createNotificationAndStartForeground() }

        if (Intent.ACTION_MEDIA_BUTTON == intent?.action && playerManager?.playQueue == null) {
            /*
            No need to process media button's actions if the player is not working, otherwise
            the player service would strangely start with nothing to play
            Stop the service in this case, which will be removed from the foreground and its
            notification cancelled in its destruction
             */
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent != null) playerManager?.handleIntent(intent)
//        player?.UIs?.get(MediaSessionPlayerUi::class.java)
//            ?.ifPresent { ui: MediaSessionPlayerUi -> ui.handleMediaButtonIntent(intent) }

        return START_NOT_STICKY
    }

    fun stopForImmediateReusing() {
        Logd(TAG, "stopForImmediateReusing() called")

        if (playerManager != null && !playerManager!!.exoPlayerIsNull()) {
            // Releases wifi & cpu, disables keepScreenOn, etc.
            // We can't just pause the player here because it will make transition
            // from one stream to a new stream not smooth
            playerManager!!.smoothStopForImmediateReusing()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        super.onTaskRemoved(rootIntent)
        if (playerManager != null && !playerManager!!.videoPlayerSelected()) return

        onDestroy()
        // Unload from memory completely
        Runtime.getRuntime().halt(0)
    }

    override fun onDestroy() {
        Logd(TAG, "destroy() called")
        cleanup()
    }

    private fun cleanup() {
        playerManager?.destroy()
        playerManager = null
    }

    fun stopService() {
        cleanup()
        stopSelf()
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AudioServiceLeakFix.preventLeakOf(base))
    }

    override fun onBind(intent: Intent): IBinder? {
        return mBinder
    }

    class LocalBinder internal constructor(playerService: PlayerService) : Binder() {
        private val playerService = WeakReference(playerService)
        val service: PlayerService?
            get() = playerService.get()

        fun getPlayer(): PlayerManager? {
            return playerService.get()!!.playerManager
        }
    }

    /**
     * Fixes a leak caused by AudioManager using an Activity context.
     * Tracked at https://android-review.googlesource.com/#/c/140481/1 and
     * https://github.com/square/leakcanary/issues/205
     * Source:
     * https://gist.github.com/jankovd/891d96f476f7a9ce24e2
     */
    class AudioServiceLeakFix internal constructor(base: Context?) : ContextWrapper(base) {
        override fun getSystemService(name: String): Any {
            if (AUDIO_SERVICE == name) {
                return applicationContext.getSystemService(name)
            }
            return super.getSystemService(name)
        }

        companion object {
            fun preventLeakOf(base: Context?): ContextWrapper {
                return AudioServiceLeakFix(base)
            }
        }
    }

    companion object {
        private val TAG: String = PlayerService::class.java.simpleName
        private const val DEBUG = PlayerManager.DEBUG
    }
}
