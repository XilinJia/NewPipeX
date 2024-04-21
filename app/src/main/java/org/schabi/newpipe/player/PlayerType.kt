package org.schabi.newpipe.player

import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

enum class PlayerType {
    MAIN,
    AUDIO,
    POPUP;

    /**
     * @return an integer representing this [PlayerType], to be used to save it in intents
     * @see .retrieveFromIntent
     */
    fun valueForIntent(): Int {
        return ordinal
    }

    companion object {
        /**
         * @param intent the intent to retrieve a player type from
         * @return the player type integer retrieved from the intent, converted back into a [         ], or [PlayerType.MAIN] if there is no player type extra in the
         * intent
         * @throws ArrayIndexOutOfBoundsException if the intent contains an invalid player type integer
         * @see .valueForIntent
         */
        @OptIn(UnstableApi::class) @JvmStatic
        fun retrieveFromIntent(intent: Intent): PlayerType {
            val ext = intent.getIntExtra(Player.PLAYER_TYPE, MAIN.valueForIntent())
            Log.d("PlayerType", "retrieveFromIntent $ext")
            return entries[ext]
        }
    }
}
