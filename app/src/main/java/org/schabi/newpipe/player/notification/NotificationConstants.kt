package org.schabi.newpipe.player.notification

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import androidx.annotation.IntDef
import org.schabi.newpipe.App
import org.schabi.newpipe.R
import org.schabi.newpipe.util.Localization.concatenateStrings
import java.util.*

object NotificationConstants {
    /*//////////////////////////////////////////////////////////////////////////
    // Intent actions
    ////////////////////////////////////////////////////////////////////////// */
    private const val BASE_ACTION = App.PACKAGE_NAME + ".player.MainPlayer."
    const val ACTION_CLOSE: String = BASE_ACTION + "CLOSE"
    const val ACTION_PLAY_PAUSE: String = BASE_ACTION + ".player.MainPlayer.PLAY_PAUSE"
    const val ACTION_REPEAT: String = BASE_ACTION + ".player.MainPlayer.REPEAT"
    const val ACTION_PLAY_NEXT: String = BASE_ACTION + ".player.MainPlayer.ACTION_PLAY_NEXT"
    const val ACTION_PLAY_PREVIOUS: String = BASE_ACTION + ".player.MainPlayer.ACTION_PLAY_PREVIOUS"
    const val ACTION_FAST_REWIND: String = BASE_ACTION + ".player.MainPlayer.ACTION_FAST_REWIND"
    const val ACTION_FAST_FORWARD: String = BASE_ACTION + ".player.MainPlayer.ACTION_FAST_FORWARD"
    const val ACTION_SHUFFLE: String = BASE_ACTION + ".player.MainPlayer.ACTION_SHUFFLE"
    const val ACTION_RECREATE_NOTIFICATION: String = BASE_ACTION + ".player.MainPlayer.ACTION_RECREATE_NOTIFICATION"


    const val NOTHING: Int = 0
    const val PREVIOUS: Int = 1
    const val NEXT: Int = 2
    const val REWIND: Int = 3
    const val FORWARD: Int = 4
    const val SMART_REWIND_PREVIOUS: Int = 5
    const val SMART_FORWARD_NEXT: Int = 6
    const val PLAY_PAUSE: Int = 7
    const val PLAY_PAUSE_BUFFERING: Int = 8
    const val REPEAT: Int = 9
    const val SHUFFLE: Int = 10
    const val CLOSE: Int = 11

    @Action
    val ALL_ACTIONS: IntArray = intArrayOf(NOTHING, PREVIOUS, NEXT, REWIND, FORWARD,
        SMART_REWIND_PREVIOUS, SMART_FORWARD_NEXT, PLAY_PAUSE, PLAY_PAUSE_BUFFERING, REPEAT,
        SHUFFLE, CLOSE)

    @DrawableRes
    val ACTION_ICONS: IntArray = intArrayOf(
        0,
        R.drawable.exo_icon_previous,
        R.drawable.exo_icon_next,
        R.drawable.exo_icon_rewind,
        R.drawable.exo_icon_fastforward,
        R.drawable.exo_icon_previous,
        R.drawable.exo_icon_next,
        R.drawable.ic_pause,
        R.drawable.ic_hourglass_top,
        R.drawable.exo_icon_repeat_all,
        R.drawable.exo_icon_shuffle_on,
        R.drawable.ic_close,
    )


    @JvmField
    @Action
    val SLOT_DEFAULTS: IntArray = intArrayOf(
        SMART_REWIND_PREVIOUS,
        PLAY_PAUSE_BUFFERING,
        SMART_FORWARD_NEXT,
        REPEAT,
        CLOSE,
    )

    @JvmField
    val SLOT_PREF_KEYS: IntArray = intArrayOf(
        R.string.notification_slot_0_key,
        R.string.notification_slot_1_key,
        R.string.notification_slot_2_key,
        R.string.notification_slot_3_key,
        R.string.notification_slot_4_key,
    )


    val SLOT_COMPACT_DEFAULTS: List<Int> = listOf(0, 1, 2)

    val SLOT_COMPACT_PREF_KEYS: IntArray = intArrayOf(
        R.string.notification_slot_compact_0_key,
        R.string.notification_slot_compact_1_key,
        R.string.notification_slot_compact_2_key,
    )


    fun getActionName(context: Context, @Action action: Int): String {
        return when (action) {
            PREVIOUS -> context.getString(R.string.exo_controls_previous_description)
            NEXT -> context.getString(R.string.exo_controls_next_description)
            REWIND -> context.getString(R.string.exo_controls_rewind_description)
            FORWARD -> context.getString(R.string.exo_controls_fastforward_description)
            SMART_REWIND_PREVIOUS -> concatenateStrings(
                context.getString(R.string.exo_controls_rewind_description),
                context.getString(R.string.exo_controls_previous_description))
            SMART_FORWARD_NEXT -> concatenateStrings(
                context.getString(R.string.exo_controls_fastforward_description),
                context.getString(R.string.exo_controls_next_description))
            PLAY_PAUSE -> concatenateStrings(
                context.getString(R.string.exo_controls_play_description),
                context.getString(R.string.exo_controls_pause_description))
            PLAY_PAUSE_BUFFERING -> concatenateStrings(
                context.getString(R.string.exo_controls_play_description),
                context.getString(R.string.exo_controls_pause_description),
                context.getString(R.string.notification_action_buffering))
            REPEAT -> context.getString(R.string.notification_action_repeat)
            SHUFFLE -> context.getString(R.string.notification_action_shuffle)
            CLOSE -> context.getString(R.string.close)
            NOTHING -> context.getString(R.string.notification_action_nothing)
            else -> context.getString(R.string.notification_action_nothing)
        }
    }


    /**
     * @param context the context to use
     * @param sharedPreferences the shared preferences to query values from
     * @return a sorted list of the indices of the slots to use as compact slots
     */
    fun getCompactSlotsFromPreferences(
            context: Context,
            sharedPreferences: SharedPreferences
    ): Collection<Int> {
        val compactSlots: SortedSet<Int> = TreeSet()
        for (i in 0..2) {
            val compactSlot = sharedPreferences.getInt(
                context.getString(SLOT_COMPACT_PREF_KEYS[i]), Int.MAX_VALUE)

            if (compactSlot == Int.MAX_VALUE) {
                // settings not yet populated, return default values
                return SLOT_COMPACT_DEFAULTS
            }

            if (compactSlot >= 0) {
                // compact slot is < 0 if there are less than 3 checked checkboxes
                compactSlots.add(compactSlot)
            }
        }
        return compactSlots
    }

    @Retention(AnnotationRetention.SOURCE)
    @IntDef(Action.NOTHING, Action.PREVIOUS, Action.NEXT, Action.REWIND, Action.FORWARD, Action.SMART_REWIND_PREVIOUS, Action.SMART_FORWARD_NEXT, Action.PLAY_PAUSE, Action.PLAY_PAUSE_BUFFERING, Action.REPEAT, Action.SHUFFLE, Action.CLOSE)
    annotation class Action {
        companion object {
            const val NOTHING = 0
            const val PREVIOUS = 1
            const val NEXT = 2
            const val REWIND = 3
            const val FORWARD = 4
            const val SMART_REWIND_PREVIOUS = 5
            const val SMART_FORWARD_NEXT = 6
            const val PLAY_PAUSE = 7
            const val PLAY_PAUSE_BUFFERING = 8
            const val REPEAT = 9
            const val SHUFFLE = 10
            const val CLOSE = 11
        }
    }
}
