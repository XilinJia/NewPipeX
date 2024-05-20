package org.schabi.newpipe.player.notification

import android.annotation.SuppressLint
import androidx.annotation.DrawableRes
import androidx.media3.common.util.UnstableApi
import org.schabi.newpipe.R
import org.schabi.newpipe.player.PlayerManager
import java.util.*

@UnstableApi class NotificationActionData(private val action: String, private val name: String,
                                          @field:DrawableRes @param:DrawableRes private val icon: Int) {

    fun action(): String {
        return action
    }

    fun name(): String {
        return name
    }

    @DrawableRes
    fun icon(): Int {
        return icon
    }

    override fun equals(obj: Any?): Boolean {
        return ((obj is NotificationActionData) && (this.action == obj.action) && (this.name == obj.name) && (this.icon == obj.icon))
    }

    override fun hashCode(): Int {
        return Objects.hash(action, name, icon)
    }

    companion object {
        @JvmStatic
        @SuppressLint("PrivateResource") // we currently use Exoplayer's internal strings and icons
        fun fromNotificationActionEnum(playerManager: PlayerManager, @NotificationConstants.Action selectedAction: Int): NotificationActionData? {
            val baseActionIcon = NotificationConstants.ACTION_ICONS[selectedAction]
            val ctx = playerManager.context

            when (selectedAction) {
                NotificationConstants.Action.PREVIOUS ->
                    return NotificationActionData(NotificationConstants.ACTION_PLAY_PREVIOUS, ctx.getString(R.string.exo_controls_previous_description), baseActionIcon)
                NotificationConstants.Action.NEXT -> return NotificationActionData(NotificationConstants.ACTION_PLAY_NEXT, ctx.getString(R.string.exo_controls_next_description), baseActionIcon)
                NotificationConstants.Action.REWIND -> return NotificationActionData(NotificationConstants.ACTION_FAST_REWIND, ctx.getString(R.string.exo_controls_rewind_description), baseActionIcon)
                NotificationConstants.Action.FORWARD -> return NotificationActionData(NotificationConstants.ACTION_FAST_FORWARD, ctx.getString(R.string.exo_controls_fastforward_description), baseActionIcon)

                NotificationConstants.Action.SMART_REWIND_PREVIOUS -> return if (playerManager.playQueue != null && playerManager.playQueue!!.size() > 1)
                    NotificationActionData(NotificationConstants.ACTION_PLAY_PREVIOUS, ctx.getString(R.string.exo_controls_previous_description), R.drawable.exo_notification_previous)
                else NotificationActionData(NotificationConstants.ACTION_FAST_REWIND, ctx.getString(R.string.exo_controls_rewind_description), R.drawable.exo_styled_controls_rewind)

                NotificationConstants.Action.SMART_FORWARD_NEXT -> return if (playerManager.playQueue != null && playerManager.playQueue!!.size() > 1)
                    NotificationActionData(NotificationConstants.ACTION_PLAY_NEXT, ctx.getString(R.string.exo_controls_next_description), R.drawable.exo_notification_next)
                else NotificationActionData(NotificationConstants.ACTION_FAST_FORWARD, ctx.getString(R.string.exo_controls_fastforward_description), R.drawable.exo_styled_controls_fastforward)

                NotificationConstants.Action.PLAY_PAUSE_BUFFERING -> {
                    if (playerManager.currentState == PlayerManager.STATE_PREFLIGHT || playerManager.currentState == PlayerManager.STATE_BLOCKED || playerManager.currentState == PlayerManager.STATE_BUFFERING) {
                        return NotificationActionData(NotificationConstants.ACTION_PLAY_PAUSE, ctx.getString(R.string.notification_action_buffering), R.drawable.ic_hourglass_top)
                    }

                    return when {
                        playerManager.currentState == PlayerManager.STATE_COMPLETED -> NotificationActionData(NotificationConstants.ACTION_PLAY_PAUSE, ctx.getString(R.string.exo_controls_pause_description), R.drawable.ic_replay)
                        playerManager.isPlaying || playerManager.currentState == PlayerManager.STATE_PREFLIGHT || playerManager.currentState == PlayerManager.STATE_BLOCKED || playerManager.currentState == PlayerManager.STATE_BUFFERING ->
                            NotificationActionData(NotificationConstants.ACTION_PLAY_PAUSE, ctx.getString(R.string.exo_controls_pause_description), R.drawable.exo_notification_pause)
                        else -> NotificationActionData(NotificationConstants.ACTION_PLAY_PAUSE, ctx.getString(R.string.exo_controls_play_description), R.drawable.exo_notification_play)
                    }
                }
                NotificationConstants.Action.PLAY_PAUSE -> return when {
                    playerManager.currentState == PlayerManager.STATE_COMPLETED -> NotificationActionData(NotificationConstants.ACTION_PLAY_PAUSE, ctx.getString(R.string.exo_controls_pause_description), R.drawable.ic_replay)
                    playerManager.isPlaying || playerManager.currentState == PlayerManager.STATE_PREFLIGHT || playerManager.currentState == PlayerManager.STATE_BLOCKED || playerManager.currentState == PlayerManager.STATE_BUFFERING ->
                        NotificationActionData(NotificationConstants.ACTION_PLAY_PAUSE, ctx.getString(R.string.exo_controls_pause_description), R.drawable.exo_notification_pause)
                    else -> NotificationActionData(NotificationConstants.ACTION_PLAY_PAUSE, ctx.getString(R.string.exo_controls_play_description), R.drawable.exo_notification_play)
                }

                NotificationConstants.Action.REPEAT -> return when (playerManager.repeatMode) {
                    androidx.media3.common.Player.REPEAT_MODE_ALL -> NotificationActionData(NotificationConstants.ACTION_REPEAT, ctx.getString(R.string.exo_controls_repeat_all_description), R.drawable.exo_styled_controls_repeat_all)
                    androidx.media3.common.Player.REPEAT_MODE_ONE ->
                        NotificationActionData(NotificationConstants.ACTION_REPEAT, ctx.getString(R.string.exo_controls_repeat_one_description), R.drawable.exo_styled_controls_repeat_one)
                    /* player.getRepeatMode() == REPEAT_MODE_OFF */
                    else ->
                        NotificationActionData(NotificationConstants.ACTION_REPEAT, ctx.getString(R.string.exo_controls_repeat_off_description), R.drawable.exo_styled_controls_repeat_off)
                }

                NotificationConstants.Action.SHUFFLE -> return if (playerManager.playQueue != null && playerManager.playQueue!!.isShuffled)
                    NotificationActionData(NotificationConstants.ACTION_SHUFFLE, ctx.getString(R.string.exo_controls_shuffle_on_description), R.drawable.exo_styled_controls_shuffle_on)
                else NotificationActionData(NotificationConstants.ACTION_SHUFFLE, ctx.getString(R.string.exo_controls_shuffle_off_description), R.drawable.exo_styled_controls_shuffle_off)

                NotificationConstants.Action.CLOSE -> return NotificationActionData(NotificationConstants.ACTION_CLOSE, ctx.getString(R.string.close), R.drawable.ic_close)

                NotificationConstants.Action.NOTHING -> return null    // do nothing
                else -> return null
            }
        }
    }
}
