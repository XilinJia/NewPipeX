package org.schabi.newpipe.player.mediasession

import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.Bitmap
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.player.PlayerManager
import org.schabi.newpipe.player.notification.NotificationActionData
import org.schabi.newpipe.player.notification.NotificationActionData.Companion.fromNotificationActionEnum
import org.schabi.newpipe.player.notification.NotificationConstants
import org.schabi.newpipe.player.notification.NotificationConstants.ACTION_RECREATE_NOTIFICATION
import org.schabi.newpipe.player.ui.PlayerUi
import org.schabi.newpipe.player.ui.VideoPlayerUi
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.util.StreamTypeUtil.isLiveStream
import java.util.*
import java.util.stream.Collectors
import java.util.stream.IntStream

@UnstableApi class MediaSessionPlayerUi(playerManager: PlayerManager) : PlayerUi(playerManager), OnSharedPreferenceChangeListener {
    private var mediaSession: MediaSession? = null

    private val ignoreHardwareMediaButtonsKey = context.getString(R.string.ignore_hardware_media_buttons_key)
    private var shouldIgnoreHardwareMediaButtons = false

    // used to check whether any notification action changed, before sending costly updates
    private var prevNotificationActions: List<NotificationActionData?> = listOf<NotificationActionData>()

    override fun initPlayer() {
        super.initPlayer()
        destroyPlayer() // release previously used resources

        mediaSession = MediaSession.Builder(context, playerManager.exoPlayer!!)
//            .setSessionCallback(MySessionCallback())
            .build()

        // listen to changes to ignore_hardware_media_buttons_key
//        updateShouldIgnoreHardwareMediaButtons(player.prefs)
//        player.prefs.registerOnSharedPreferenceChangeListener(this)

        // force updating media session actions by resetting the previous ones
        prevNotificationActions = listOf<NotificationActionData>()
        updateMediaSessionActions()
    }

    override fun destroyPlayer() {
        super.destroyPlayer()
        Logd(TAG, "session destroyPlayer")
        playerManager.prefs.unregisterOnSharedPreferenceChangeListener(this)
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        prevNotificationActions = listOf<NotificationActionData>()
    }

//    override fun onThumbnailLoaded(bitmap: Bitmap?) {
//        super.onThumbnailLoaded(bitmap)
//    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == null || key == ignoreHardwareMediaButtonsKey) updateShouldIgnoreHardwareMediaButtons(sharedPreferences)
    }

    fun updateShouldIgnoreHardwareMediaButtons(sharedPreferences: SharedPreferences) {
        shouldIgnoreHardwareMediaButtons = sharedPreferences.getBoolean(ignoreHardwareMediaButtonsKey, false)
    }

//    fun handleMediaButtonIntent(intent: Intent?) {
//        MediaButtonReceiver.handleIntent(mediaSession, intent)
//    }

    val sessionToken: Optional<MediaSessionCompat.Token>
//        get() = Optional.ofNullable(mediaSession).map { obj: MediaSessionCompat -> obj.sessionToken }
        get() = Optional.ofNullable(mediaSession).map { obj: MediaSession ->
            obj.sessionCompatToken
        }

    private val forwardingPlayer: ForwardingPlayer
        // ForwardingPlayer means that all media session actions called on this player are
        // forwarded directly to the connected exoplayer, except for the overridden methods. So
        // override play and pause since our player adds more functionality to them over exoplayer.
        get() = object : ForwardingPlayer(playerManager.exoPlayer!!) {
            override fun play() {
                playerManager.play()
                // hide the player controls even if the play command came from the media session
                playerManager.UIs.get(VideoPlayerUi::class.java).ifPresent { ui -> ui.hideControls(0, 0) }
            }
            override fun pause() {
                playerManager.pause()
            }
        }

    private fun buildMediaMetadata(): MediaMetadataCompat {
        Logd(TAG, "buildMediaMetadata called")

        // set title and artist
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, playerManager.videoTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, playerManager.uploaderName)

        // set duration (-1 for livestreams or if unknown, see the METADATA_KEY_DURATION docs)
        val duration = playerManager.currentStreamInfo
            .filter { info: StreamInfo -> !isLiveStream(info.streamType) }
            .map { info: StreamInfo -> info.duration * 1000L }
            .orElse(-1L)
        builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)

        // set album art, unless the user asked not to, or there is no thumbnail available
        val showThumbnail = playerManager.prefs.getBoolean(context.getString(R.string.show_thumbnail_key), true)
        Optional.ofNullable(playerManager.thumbnail)
            .filter { bitmap: Bitmap? -> showThumbnail }
            .ifPresent { bitmap: Bitmap? ->
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
            }

        return builder.build()
    }

    private fun updateMediaSessionActions() {
        // On Android 13+ (or Android T or API 33+) the actions in the player notification can't be
        // controlled directly anymore, but are instead derived from custom media session actions.
        // However the system allows customizing only two of these actions, since the other three
        // are fixed to play-pause-buffering, previous, next.

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Although setting media session actions on older android versions doesn't seem to
            // cause any trouble, it also doesn't seem to do anything, so we don't do anything to
            // save battery. Check out NotificationUtil.updateActions() to see what happens on
            // older android versions.
            return
        }

        // only use the fourth and fifth actions (the settings page also shows only the last 2 on
        // Android 13+)
        val newNotificationActions = IntStream.of(3, 4)
            .map { i: Int ->
                playerManager.prefs.getInt(playerManager.context.getString(NotificationConstants.SLOT_PREF_KEYS[i]), NotificationConstants.SLOT_DEFAULTS[i])
            }
            .mapToObj { action: Int -> fromNotificationActionEnum(playerManager, action) }
            .filter { obj: NotificationActionData? -> Objects.nonNull(obj) }
            .collect(Collectors.toList())

        // avoid costly notification actions update, if nothing changed from last time
        if (newNotificationActions != prevNotificationActions) prevNotificationActions = newNotificationActions
    }

    override fun onBlocked() {
        super.onBlocked()
        updateMediaSessionActions()
    }

    override fun onPlaying() {
        super.onPlaying()
        updateMediaSessionActions()
    }

    override fun onBuffering() {
        super.onBuffering()
        updateMediaSessionActions()
    }

    override fun onPaused() {
        super.onPaused()
        updateMediaSessionActions()
    }

    override fun onPausedSeek() {
        super.onPausedSeek()
        updateMediaSessionActions()
    }

    override fun onCompleted() {
        super.onCompleted()
        updateMediaSessionActions()
    }

    override fun onRepeatModeChanged(repeatMode: @androidx.media3.common.Player.RepeatMode Int) {
        super.onRepeatModeChanged(repeatMode)
        updateMediaSessionActions()
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        super.onShuffleModeEnabledChanged(shuffleModeEnabled)
        updateMediaSessionActions()
    }

    override fun onBroadcastReceived(intent: Intent?) {
        super.onBroadcastReceived(intent)
        if (ACTION_RECREATE_NOTIFICATION == intent!!.action) {
            // the notification actions changed
            updateMediaSessionActions()
        }
    }

    override fun onMetadataChanged(info: StreamInfo) {
        super.onMetadataChanged(info)
        updateMediaSessionActions()
    }

    override fun onPlayQueueEdited() {
        super.onPlayQueueEdited()
        updateMediaSessionActions()
    }

    companion object {
        private const val TAG = "MediaSessUi"
    }
}
