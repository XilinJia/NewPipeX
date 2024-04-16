package org.schabi.newpipe.fragments.detail

import android.content.Context
import android.util.Log
import android.util.Pair
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ListRadioIconItemBinding
import org.schabi.newpipe.databinding.SingleChoiceDialogViewBinding
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.util.ThemeHelper.isLightThemeSelected
import java.io.IOException
import java.util.function.Supplier

/**
 * Outsourced logic for crashing the player in the [VideoDetailFragment].
 */
object VideoDetailPlayerCrasher {
    // This has to be <= 23 chars on devices running Android 7 or lower (API <= 25)
    // or it fails with an IllegalArgumentException
    // https://stackoverflow.com/a/54744028

    private const val TAG = "VideoDetPlayerCrasher"

    private const val DEFAULT_MSG = "Dummy"

    private val AVAILABLE_EXCEPTION_TYPES: List<Pair<String, Supplier<ExoPlaybackException>>> = java.util.List.of(
        Pair("Source", Supplier {
            ExoPlaybackException.createForSource(IOException(DEFAULT_MSG), PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)
        }),
        Pair("Renderer", Supplier {
            ExoPlaybackException.createForRenderer(
                Exception(DEFAULT_MSG),
                "Dummy renderer",
                0,
                null,
                C.FORMAT_HANDLED,  /*isRecoverable=*/
                false,
                PlaybackException.ERROR_CODE_DECODING_FAILED
            )
        }),
        Pair("Unexpected", Supplier {
            ExoPlaybackException.createForUnexpected(RuntimeException(DEFAULT_MSG), PlaybackException.ERROR_CODE_UNSPECIFIED)
        }),
        Pair("Remote", Supplier { ExoPlaybackException.createForRemote(DEFAULT_MSG) })
    )

    private fun getThemeWrapperContext(context: Context): Context {
        return ContextThemeWrapper(context, if (isLightThemeSelected(context)) R.style.LightTheme else R.style.DarkTheme)
    }

    fun onCrashThePlayer(context: Context, player: Player?) {
        if (player == null) {
            Log.d(TAG, "Player is not available")
            Toast.makeText(context, "Player is not available", Toast.LENGTH_SHORT)
                .show()
            return
        }

        // -- Build the dialog/UI --
        val themeWrapperContext = getThemeWrapperContext(context)
        val inflater = LayoutInflater.from(themeWrapperContext)

        val binding = SingleChoiceDialogViewBinding.inflate(inflater)

        val alertDialog = AlertDialog.Builder(themeWrapperContext)
            .setTitle("Choose an exception")
            .setView(binding.root)
            .setCancelable(true)
            .setNegativeButton(R.string.cancel, null)
            .create()

        for (entry in AVAILABLE_EXCEPTION_TYPES) {
            val radioButton = ListRadioIconItemBinding.inflate(inflater).root
            radioButton.text = entry.first
            radioButton.isChecked = false
            radioButton.layoutParams = RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            radioButton.setOnClickListener { v: View? ->
                tryCrashPlayerWith(player, entry.second.get())
                alertDialog.cancel()
            }
            binding.list.addView(radioButton)
        }

        alertDialog.show()
    }

    /**
     * Note that this method does not crash the underlying exoplayer directly (it's not possible).
     * It simply supplies a Exception to [Player.onPlayerError].
     * @param player
     * @param exception
     */
    private fun tryCrashPlayerWith(player: Player, exception: ExoPlaybackException) {
        Log.d(TAG, "Crashing the player using player.onPlayerError(ex)")
        try {
            player.onPlayerError(exception)
        } catch (exPlayer: Exception) {
            Log.e(TAG,
                "Run into an exception while crashing the player:",
                exPlayer)
        }
    }
}
