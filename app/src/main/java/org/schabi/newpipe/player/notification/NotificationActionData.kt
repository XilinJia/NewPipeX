package org.schabi.newpipe.player.notification

import android.annotation.SuppressLint
import androidx.annotation.DrawableRes
import org.schabi.newpipe.R
import org.schabi.newpipe.player.Player
import java.util.*

class NotificationActionData(private val action: String, private val name: String,
                             @field:DrawableRes @param:DrawableRes private val icon: Int
) {
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
        return ((obj is NotificationActionData)
                && (this.action == obj.action) && (this.name == obj.name) && (this.icon == obj.icon))
    }

    override fun hashCode(): Int {
        return Objects.hash(action, name, icon)
    }

    companion object {
        @JvmStatic
        @SuppressLint("PrivateResource") // we currently use Exoplayer's internal strings and icons
        fun fromNotificationActionEnum(
                player: Player,
                @NotificationConstants.Action selectedAction: Int
        ): NotificationActionData? {
            val baseActionIcon = NotificationConstants.ACTION_ICONS[selectedAction]
            val ctx = player.context

            when (selectedAction) {
                NotificationConstants.PREVIOUS -> return NotificationActionData(NotificationConstants.ACTION_PLAY_PREVIOUS,
                    ctx.getString(R.string.exo_controls_previous_description), baseActionIcon)

                NotificationConstants.NEXT -> return NotificationActionData(NotificationConstants.ACTION_PLAY_NEXT,
                    ctx.getString(R.string.exo_controls_next_description), baseActionIcon)

                NotificationConstants.REWIND -> return NotificationActionData(NotificationConstants.ACTION_FAST_REWIND,
                    ctx.getString(R.string.exo_controls_rewind_description), baseActionIcon)

                NotificationConstants.FORWARD -> return NotificationActionData(NotificationConstants.ACTION_FAST_FORWARD,
                    ctx.getString(R.string.exo_controls_fastforward_description),
                    baseActionIcon)

                NotificationConstants.SMART_REWIND_PREVIOUS -> return if (player.playQueue != null && player.playQueue!!.size() > 1) {
                    NotificationActionData(NotificationConstants.ACTION_PLAY_PREVIOUS,
                        ctx.getString(R.string.exo_controls_previous_description),
                        R.drawable.exo_notification_previous)
                } else {
                    NotificationActionData(NotificationConstants.ACTION_FAST_REWIND,
                        ctx.getString(R.string.exo_controls_rewind_description),
                        R.drawable.exo_controls_rewind)
                }

                NotificationConstants.SMART_FORWARD_NEXT -> return if (player.playQueue != null && player.playQueue!!.size() > 1) {
                    NotificationActionData(NotificationConstants.ACTION_PLAY_NEXT,
                        ctx.getString(R.string.exo_controls_next_description),
                        R.drawable.exo_notification_next)
                } else {
                    NotificationActionData(NotificationConstants.ACTION_FAST_FORWARD,
                        ctx.getString(R.string.exo_controls_fastforward_description),
                        R.drawable.exo_controls_fastforward)
                }

                NotificationConstants.PLAY_PAUSE_BUFFERING -> {
                    if (player.currentState == Player.STATE_PREFLIGHT || player.currentState == Player.STATE_BLOCKED || player.currentState == Player.STATE_BUFFERING) {
                        return NotificationActionData(NotificationConstants.ACTION_PLAY_PAUSE,
                            ctx.getString(R.string.notification_action_buffering),
                            R.drawable.ic_hourglass_top)
                    }

                    return if (player.currentState == Player.STATE_COMPLETED) {
                        NotificationActionData(NotificationConstants.ACTION_PLAY_PAUSE,
                            ctx.getString(R.string.exo_controls_pause_description),
                            R.drawable.ic_replay)
                    } else if (player.isPlaying || player.currentState == Player.STATE_PREFLIGHT || player.currentState == Player.STATE_BLOCKED || player.currentState == Player.STATE_BUFFERING) {
                        NotificationActionData(NotificationConstants.ACTION_PLAY_PAUSE,
                            ctx.getString(R.string.exo_controls_pause_description),
                            R.drawable.exo_notification_pause)
                    } else {
                        NotificationActionData(NotificationConstants.ACTION_PLAY_PAUSE,
                            ctx.getString(R.string.exo_controls_play_description),
                            R.drawable.exo_notification_play)
                    }
                }
                NotificationConstants.PLAY_PAUSE -> return if (player.currentState == Player.STATE_COMPLETED) {
                    NotificationActionData(NotificationConstants.ACTION_PLAY_PAUSE,
                        ctx.getString(R.string.exo_controls_pause_description),
                        R.drawable.ic_replay)
                } else if (player.isPlaying || player.currentState == Player.STATE_PREFLIGHT || player.currentState == Player.STATE_BLOCKED || player.currentState == Player.STATE_BUFFERING) {
                    NotificationActionData(NotificationConstants.ACTION_PLAY_PAUSE,
                        ctx.getString(R.string.exo_controls_pause_description),
                        R.drawable.exo_notification_pause)
                } else {
                    NotificationActionData(NotificationConstants.ACTION_PLAY_PAUSE,
                        ctx.getString(R.string.exo_controls_play_description),
                        R.drawable.exo_notification_play)
                }

                NotificationConstants.REPEAT -> return if (player.repeatMode == com.google.android.exoplayer2.Player.REPEAT_MODE_ALL) {
                    NotificationActionData(NotificationConstants.ACTION_REPEAT,
                        ctx.getString(R.string.exo_controls_repeat_all_description),
                        R.drawable.exo_media_action_repeat_all)
                } else if (player.repeatMode == com.google.android.exoplayer2.Player.REPEAT_MODE_ONE) {
                    NotificationActionData(NotificationConstants.ACTION_REPEAT,
                        ctx.getString(R.string.exo_controls_repeat_one_description),
                        R.drawable.exo_media_action_repeat_one)
                } else  /* player.getRepeatMode() == REPEAT_MODE_OFF */ {
                    NotificationActionData(NotificationConstants.ACTION_REPEAT,
                        ctx.getString(R.string.exo_controls_repeat_off_description),
                        R.drawable.exo_media_action_repeat_off)
                }

                NotificationConstants.SHUFFLE -> return if (player.playQueue != null && player.playQueue!!.isShuffled) {
                    NotificationActionData(NotificationConstants.ACTION_SHUFFLE,
                        ctx.getString(R.string.exo_controls_shuffle_on_description),
                        R.drawable.exo_controls_shuffle_on)
                } else {
                    NotificationActionData(NotificationConstants.ACTION_SHUFFLE,
                        ctx.getString(R.string.exo_controls_shuffle_off_description),
                        R.drawable.exo_controls_shuffle_off)
                }

                NotificationConstants.CLOSE -> return NotificationActionData(NotificationConstants.ACTION_CLOSE,
                    ctx.getString(R.string.close),
                    R.drawable.ic_close)

                NotificationConstants.NOTHING ->                 // do nothing
                    return null
                else ->
                    return null
            }
        }
    }
}
