package org.schabi.newpipe.player.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.*
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.player.PlayerManager
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.player.helper.PlayerHelper.MinimizeMode
import org.schabi.newpipe.player.mediasession.MediaSessionPlayerUi
import org.schabi.newpipe.player.notification.NotificationConstants.ACTION_RECREATE_NOTIFICATION
import org.schabi.newpipe.player.ui.PlayerUi
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.util.NavigationHelper.getPlayQueueActivityIntent
import org.schabi.newpipe.util.NavigationHelper.getPlayerIntent
import java.util.*
import kotlin.math.min

@UnstableApi class NotificationPlayerUi(playerManager: PlayerManager) : PlayerUi(playerManager) {
    private val notificationUtil = NotificationUtil(playerManager)

    override fun destroy() {
        super.destroy()
        notificationUtil.cancelNotificationAndStopForeground()
    }

    override fun onThumbnailLoaded(bitmap: Bitmap?) {
        super.onThumbnailLoaded(bitmap)
        notificationUtil.updateThumbnail()
    }

    override fun onBlocked() {
        super.onBlocked()
        notificationUtil.createNotificationIfNeededAndUpdate(false)
    }

    override fun onPlaying() {
        super.onPlaying()
        notificationUtil.createNotificationIfNeededAndUpdate(false)
    }

    override fun onBuffering() {
        super.onBuffering()
        if (notificationUtil.shouldUpdateBufferingSlot()) notificationUtil.createNotificationIfNeededAndUpdate(false)
    }

    override fun onPaused() {
        super.onPaused()
        // Remove running notification when user does not want minimization to background or popup
        if (PlayerHelper.getMinimizeOnExitAction(context) == MinimizeMode.MINIMIZE_ON_EXIT_MODE_NONE && playerManager.videoPlayerSelected())
            notificationUtil.cancelNotificationAndStopForeground()
        else notificationUtil.createNotificationIfNeededAndUpdate(false)
    }

    override fun onPausedSeek() {
        super.onPausedSeek()
        notificationUtil.createNotificationIfNeededAndUpdate(false)
    }

    override fun onCompleted() {
        super.onCompleted()
        notificationUtil.createNotificationIfNeededAndUpdate(false)
    }

    override fun onRepeatModeChanged(repeatMode: @androidx.media3.common.Player.RepeatMode Int) {
        super.onRepeatModeChanged(repeatMode)
        notificationUtil.createNotificationIfNeededAndUpdate(false)
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        super.onShuffleModeEnabledChanged(shuffleModeEnabled)
        notificationUtil.createNotificationIfNeededAndUpdate(false)
    }

    override fun onBroadcastReceived(intent: Intent?) {
        super.onBroadcastReceived(intent)
        if (ACTION_RECREATE_NOTIFICATION == intent!!.action) notificationUtil.createNotificationIfNeededAndUpdate(true)
    }

    override fun onMetadataChanged(info: StreamInfo) {
        super.onMetadataChanged(info)
        notificationUtil.createNotificationIfNeededAndUpdate(true)
    }

    override fun onPlayQueueEdited() {
        super.onPlayQueueEdited()
        notificationUtil.createNotificationIfNeededAndUpdate(false)
    }

    fun createNotificationAndStartForeground() {
        notificationUtil.createNotificationAndStartForeground()
    }

    @UnstableApi class NotificationUtil(private val playerManager: PlayerManager) {
        @NotificationConstants.Action
        private val notificationSlots = NotificationConstants.SLOT_DEFAULTS.clone()

        private var notificationManager: NotificationManagerCompat? = null
        private var notificationBuilder: NotificationCompat.Builder? = null


        /////////////////////////////////////////////////////
        // NOTIFICATION
        /////////////////////////////////////////////////////
        /**
         * Creates the notification if it does not exist already and recreates it if forceRecreate is
         * true. Updates the notification with the data in the player.
         * @param forceRecreate whether to force the recreation of the notification even if it already
         * exists
         */
        @Synchronized
        fun createNotificationIfNeededAndUpdate(forceRecreate: Boolean) {
            if (forceRecreate || notificationBuilder == null) notificationBuilder = createNotification()

            updateNotification()
            if (ActivityCompat.checkSelfPermission(playerManager.context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            notificationManager!!.notify(NOTIFICATION_ID, notificationBuilder!!.build())
        }

        @Synchronized
        fun updateThumbnail() {
            if (notificationBuilder != null) {
                Logd(TAG, "updateThumbnail() called with thumbnail = [" +
                        Integer.toHexString(Optional.ofNullable(playerManager.thumbnail).map { o: Bitmap? -> Objects.hashCode(o) }
                            .orElse(0)) + "], title = [" + playerManager.videoTitle + "]")

                setLargeIcon(notificationBuilder!!)
                if (ActivityCompat.checkSelfPermission(playerManager.context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return
                }
                notificationManager!!.notify(NOTIFICATION_ID, notificationBuilder!!.build())
            }
        }

        @Synchronized
        private fun createNotification(): NotificationCompat.Builder {
            Logd(TAG, "createNotification()")

            notificationManager = NotificationManagerCompat.from(playerManager.context)
            val builder = NotificationCompat.Builder(playerManager.context, playerManager.context.getString(R.string.notification_channel_id))
//        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()

            // setup media style (compact notification slots and media session)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                // notification actions are ignored on Android 13+, and are replaced by code in
                // MediaSessionPlayerUi
                val compactSlots = initializeNotificationSlots()
//            mediaStyle.setShowActionsInCompactView(*compactSlots)
            }
            playerManager.UIs
                .get(MediaSessionPlayerUi::class.java)
                .flatMap { obj: MediaSessionPlayerUi -> obj.sessionToken }
//            .ifPresent { token: MediaSessionCompat.Token? -> mediaStyle.setMediaSession(token) }

            // setup notification builder
            builder
//            .setStyle(mediaStyle)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setShowWhen(false)
                .setSmallIcon(R.drawable.ic_newpipe_triangle_white)
                .setColor(ContextCompat.getColor(playerManager.context, R.color.dark_background_color))
                .setColorized(playerManager.prefs.getBoolean(playerManager.context.getString(R.string.notification_colorize_key), true))
                .setDeleteIntent(PendingIntentCompat.getBroadcast(playerManager.context,
                    NOTIFICATION_ID, Intent(NotificationConstants.ACTION_CLOSE), PendingIntent.FLAG_UPDATE_CURRENT, false))

            // set the initial value for the video thumbnail, updatable with updateNotificationThumbnail
            setLargeIcon(builder)

            return builder
        }

        /**
         * Updates the notification builder and the button icons depending on the playback state.
         */
        @Synchronized
        private fun updateNotification() {
            Logd(TAG, "updateNotification()")

            // also update content intent, in case the user switched players
            notificationBuilder!!.setContentIntent(PendingIntentCompat.getActivity(playerManager.context,
                NOTIFICATION_ID, intentForNotification, PendingIntent.FLAG_UPDATE_CURRENT, false))
            notificationBuilder!!.setContentTitle(playerManager.videoTitle)
            notificationBuilder!!.setContentText(playerManager.uploaderName)
            notificationBuilder!!.setTicker(playerManager.videoTitle)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                // notification actions are ignored on Android 13+, and are replaced by code in
                // MediaSessionPlayerUi
                updateActions(notificationBuilder)
            }
        }


        @SuppressLint("RestrictedApi")
        fun shouldUpdateBufferingSlot(): Boolean {
            when {
                // if there is no notification active, there is no point in updating it
                notificationBuilder == null -> return false
                // this should never happen, but let's make sure notification actions are populated
                notificationBuilder!!.mActions.size < 3 -> return true
                // only second and third slot could contain PLAY_PAUSE_BUFFERING, update them only if they
                // are not already in the buffering state (the only one with a null action intent)
                else -> return ((notificationSlots[1] == NotificationConstants.PLAY_PAUSE_BUFFERING
                        && notificationBuilder!!.mActions[1].actionIntent != null)
                        || (notificationSlots[2] == NotificationConstants.PLAY_PAUSE_BUFFERING
                        && notificationBuilder!!.mActions[2].actionIntent != null))
            }
        }


        fun createNotificationAndStartForeground() {
            if (notificationBuilder == null) notificationBuilder = createNotification()
            updateNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                playerManager.service.startForeground(NOTIFICATION_ID, notificationBuilder!!.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                playerManager.service.startForeground(NOTIFICATION_ID, notificationBuilder!!.build())
            }
        }

        fun cancelNotificationAndStopForeground() {
            ServiceCompat.stopForeground(playerManager.service, ServiceCompat.STOP_FOREGROUND_REMOVE)
            notificationManager?.cancel(NOTIFICATION_ID)
            notificationManager = null
            notificationBuilder = null
        }


        /////////////////////////////////////////////////////
        // ACTIONS
        /////////////////////////////////////////////////////
        /**
         * The compact slots array from settings contains indices from 0 to 4, each referring to one of
         * the five actions configurable by the user. However, if the user sets an action to "Nothing",
         * then all of the actions coming after will have a "settings index" different than the index
         * of the corresponding action when sent to the system.
         *
         * @return the indices of compact slots referred to the list of non-nothing actions that will be
         * sent to the system
         */
        private fun initializeNotificationSlots(): IntArray {
            val settingsCompactSlots = NotificationConstants.getCompactSlotsFromPreferences(playerManager.context, playerManager.prefs)
            val adjustedCompactSlots: MutableList<Int> = ArrayList()

            var nonNothingIndex = 0
            for (i in 0..4) {
                notificationSlots[i] = playerManager.prefs.getInt(playerManager.context.getString(NotificationConstants.SLOT_PREF_KEYS[i]), NotificationConstants.SLOT_DEFAULTS[i])

                if (notificationSlots[i] != NotificationConstants.NOTHING) {
                    if (settingsCompactSlots.contains(i)) adjustedCompactSlots.add(nonNothingIndex)
                    nonNothingIndex += 1
                }
            }

            return adjustedCompactSlots.stream().mapToInt { obj: Int -> obj.toInt() }.toArray()
        }

        @SuppressLint("RestrictedApi")
        private fun updateActions(builder: NotificationCompat.Builder?) {
            builder!!.mActions.clear()
            for (i in 0..4) {
                addAction(builder, notificationSlots[i])
            }
        }

        private fun addAction(builder: NotificationCompat.Builder?, @NotificationConstants.Action slot: Int) {
            val data = NotificationActionData.fromNotificationActionEnum(playerManager, slot) ?: return

            val intent = PendingIntentCompat.getBroadcast(playerManager.context, NOTIFICATION_ID, Intent(data.action()), PendingIntent.FLAG_UPDATE_CURRENT, false)
            builder!!.addAction(NotificationCompat.Action(data.icon(), data.name(), intent))
        }

        private val intentForNotification: Intent
            get() {
                if (playerManager.audioPlayerSelected() || playerManager.popupPlayerSelected()) {
                    // Means we play in popup or audio only. Let's show the play queue
                    return getPlayQueueActivityIntent(playerManager.context)
                } else {
                    // We are playing in fragment. Don't open another activity just show fragment. That's it
                    val intent = getPlayerIntent(playerManager.context, MainActivity::class.java, null, true)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.setAction(Intent.ACTION_MAIN)
                    intent.addCategory(Intent.CATEGORY_LAUNCHER)
                    return intent
                }
            }


        /////////////////////////////////////////////////////
        // BITMAP
        /////////////////////////////////////////////////////
        private fun setLargeIcon(builder: NotificationCompat.Builder) {
            val showThumbnail = playerManager.prefs.getBoolean(playerManager.context.getString(R.string.show_thumbnail_key), true)
            val thumbnail = playerManager.thumbnail
            if (thumbnail == null || !showThumbnail) {
                // since the builder is reused, make sure the thumbnail is unset if there is not one
                builder.setLargeIcon(null as Bitmap?)
                return
            }

            val scaleImageToSquareAspectRatio = playerManager.prefs.getBoolean(playerManager.context.getString(R.string.scale_to_square_image_in_notifications_key), false)
            if (scaleImageToSquareAspectRatio) builder.setLargeIcon(getBitmapWithSquareAspectRatio(thumbnail))
            else builder.setLargeIcon(thumbnail)
        }

        private fun getBitmapWithSquareAspectRatio(bitmap: Bitmap): Bitmap {
            // Find the smaller dimension and then take a center portion of the image that
            // has that size.
            val w = bitmap.width
            val h = bitmap.height
            val dstSize = min(w.toDouble(), h.toDouble()).toInt()
            val x = (w - dstSize) / 2
            val y = (h - dstSize) / 2
            return Bitmap.createBitmap(bitmap, x, y, dstSize, dstSize)
        }

        companion object {
            private val TAG: String = NotificationUtil::class.java.simpleName
            private const val DEBUG = PlayerManager.DEBUG
            private const val NOTIFICATION_ID = 123789
        }
    }

}
