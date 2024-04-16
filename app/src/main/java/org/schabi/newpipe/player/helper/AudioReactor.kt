package org.schabi.newpipe.player.helper

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.media.audiofx.AudioEffect
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

@OptIn(UnstableApi::class) class AudioReactor(private val context: Context, private val player: ExoPlayer)
    : OnAudioFocusChangeListener, AnalyticsListener {
    private val audioManager = ContextCompat.getSystemService(context, AudioManager::class.java)

    private val request: AudioFocusRequestCompat

    init {
        player.addAnalyticsListener(this)

        request = AudioFocusRequestCompat.Builder(FOCUS_GAIN_TYPE) //.setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(this)
            .build()
    }

    fun dispose() {
        abandonAudioFocus()
        player.removeAnalyticsListener(this)
        notifyAudioSessionUpdate(false, player.audioSessionId)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Audio Manager
    ////////////////////////////////////////////////////////////////////////// */
    fun requestAudioFocus() {
        AudioManagerCompat.requestAudioFocus(audioManager!!, request)
    }

    fun abandonAudioFocus() {
        AudioManagerCompat.abandonAudioFocusRequest(audioManager!!, request)
    }

    var volume: Int
        get() = audioManager!!.getStreamVolume(STREAM_TYPE)
        set(volume) {
            audioManager!!.setStreamVolume(STREAM_TYPE, volume, 0)
        }

    val maxVolume: Int
        get() = AudioManagerCompat.getStreamMaxVolume(audioManager!!, STREAM_TYPE)

    /*//////////////////////////////////////////////////////////////////////////
    // AudioFocus
    ////////////////////////////////////////////////////////////////////////// */
    override fun onAudioFocusChange(focusChange: Int) {
        Log.d(TAG, "onAudioFocusChange() called with: focusChange = [$focusChange]")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> onAudioFocusGain()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onAudioFocusLossCanDuck()
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onAudioFocusLoss()
        }
    }

    private fun onAudioFocusGain() {
        Log.d(TAG, "onAudioFocusGain() called")
        player.volume = DUCK_AUDIO_TO
        animateAudio(DUCK_AUDIO_TO, 1.0f)

        if (PlayerHelper.isResumeAfterAudioFocusGain(context)) {
            player.play()
        }
    }

    private fun onAudioFocusLoss() {
        Log.d(TAG, "onAudioFocusLoss() called")
        player.pause()
    }

    private fun onAudioFocusLossCanDuck() {
        Log.d(TAG, "onAudioFocusLossCanDuck() called")
        // Set the volume to 1/10 on ducking
        player.volume = DUCK_AUDIO_TO
    }

    private fun animateAudio(from: Float, to: Float) {
        val valueAnimator = ValueAnimator()
        valueAnimator.setFloatValues(from, to)
        valueAnimator.setDuration(DUCK_DURATION.toLong())
        valueAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                player.volume = from
            }

            override fun onAnimationCancel(animation: Animator) {
                player.volume = to
            }

            override fun onAnimationEnd(animation: Animator) {
                player.volume = to
            }
        })
        valueAnimator.addUpdateListener { animation: ValueAnimator -> player.volume = animation.animatedValue as Float }
        valueAnimator.start()
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Audio Processing
    ////////////////////////////////////////////////////////////////////////// */
    override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime,
                                         audioSessionId: Int
    ) {
        notifyAudioSessionUpdate(true, audioSessionId)
    }

    private fun notifyAudioSessionUpdate(active: Boolean, audioSessionId: Int) {
        if (!PlayerHelper.isUsingDSP) {
            return
        }
        val intent = Intent(if (active
        ) AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
        else AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        context.sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "AudioFocusReactor"

        private const val DUCK_DURATION = 1500
        private const val DUCK_AUDIO_TO = .2f

        private const val FOCUS_GAIN_TYPE = AudioManagerCompat.AUDIOFOCUS_GAIN
        private const val STREAM_TYPE = AudioManager.STREAM_MUSIC
    }
}
