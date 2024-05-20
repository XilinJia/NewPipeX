package org.schabi.newpipe.player.helper

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.math.MathUtils
import androidx.fragment.app.DialogFragment
import androidx.preference.PreferenceManager
import icepick.Icepick
import icepick.State
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.DialogPlaybackParameterBinding
import org.schabi.newpipe.ktx.animateRotation
import org.schabi.newpipe.player.PlayerManager
import org.schabi.newpipe.player.helper.PlayerHelper.formatPitch
import org.schabi.newpipe.player.helper.PlayerHelper.formatSpeed
import org.schabi.newpipe.player.helper.PlayerSemitoneHelper.formatPitchSemitones
import org.schabi.newpipe.player.helper.PlayerSemitoneHelper.percentToSemitones
import org.schabi.newpipe.player.helper.PlayerSemitoneHelper.semitonesToPercent
import org.schabi.newpipe.player.ui.VideoPlayerUi
import org.schabi.newpipe.util.Localization.assureCorrectAppLanguage
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.util.SimpleOnSeekBarChangeListener
import org.schabi.newpipe.util.SliderStrategy
import org.schabi.newpipe.util.SliderStrategy.Quadratic
import org.schabi.newpipe.util.ThemeHelper.resolveDrawable
import java.util.*
import java.util.function.Consumer
import java.util.function.DoubleConsumer
import java.util.function.DoubleFunction
import java.util.function.DoubleSupplier
import kotlin.math.min

class PlaybackParameterDialog : DialogFragment() {
    private var callback: Callback? = null

    @JvmField
    @State
    var initialTempo: Double = DEFAULT_TEMPO

    @JvmField
    @State
    var initialPitchPercent: Double = DEFAULT_PITCH_PERCENT

    @JvmField
    @State
    var initialSkipSilence: Boolean = DEFAULT_SKIP_SILENCE

    @JvmField
    @State
    var tempo: Double = DEFAULT_TEMPO

    @JvmField
    @State
    var pitchPercent: Double = DEFAULT_PITCH_PERCENT

    @JvmField
    @State
    var skipSilence: Boolean = DEFAULT_SKIP_SILENCE

    private var binding: DialogPlaybackParameterBinding? = null

    /*//////////////////////////////////////////////////////////////////////////
    // Lifecycle
    ////////////////////////////////////////////////////////////////////////// */
    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is Callback) {
            callback = context
        } else if (callback == null) {
            dismiss()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Icepick.saveInstanceState(this, outState)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Dialog
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        assureCorrectAppLanguage(requireContext())
        Icepick.restoreInstanceState(this, savedInstanceState)

        binding = DialogPlaybackParameterBinding.inflate(layoutInflater)
        initUI()

        val dialogBuilder = AlertDialog.Builder(requireActivity())
            .setView(binding!!.root)
            .setCancelable(true)
            .setNegativeButton(R.string.cancel) { dialogInterface: DialogInterface?, i: Int ->
                setAndUpdateTempo(initialTempo)
                setAndUpdatePitch(initialPitchPercent)
                setAndUpdateSkipSilence(initialSkipSilence)
                updateCallback()
            }
            .setNeutralButton(R.string.playback_reset) { dialogInterface: DialogInterface?, i: Int ->
                setAndUpdateTempo(DEFAULT_TEMPO)
                setAndUpdatePitch(DEFAULT_PITCH_PERCENT)
                setAndUpdateSkipSilence(DEFAULT_SKIP_SILENCE)
                updateCallback()
            }
            .setPositiveButton(R.string.ok) { dialogInterface: DialogInterface?, i: Int -> updateCallback() }

        return dialogBuilder.create()
    }

    /*//////////////////////////////////////////////////////////////////////////
    // UI Initialization and Control
    ////////////////////////////////////////////////////////////////////////// */
    private fun initUI() {
        // Tempo
        setText(binding!!.tempoMinimumText,
            DoubleFunction<String> { obj: Double -> formatSpeed(obj) },
            MIN_PITCH_OR_SPEED)
        setText(binding!!.tempoMaximumText,
            DoubleFunction<String> { obj: Double -> formatSpeed(obj) },
            MAX_PITCH_OR_SPEED)

        binding!!.tempoSeekbar.max = QUADRATIC_STRATEGY.progressOf(MAX_PITCH_OR_SPEED)
        setAndUpdateTempo(tempo)
        binding!!.tempoSeekbar.setOnSeekBarChangeListener(
            getTempoOrPitchSeekbarChangeListener(
                QUADRATIC_STRATEGY
            ) { newTempo: Double -> this.onTempoSliderUpdated(newTempo) })

        registerOnStepClickListener(
            binding!!.tempoStepDown,
            { tempo },
            -1.0,
            { newTempo: Double -> this.onTempoSliderUpdated(newTempo) })
        registerOnStepClickListener(
            binding!!.tempoStepUp,
            { tempo },
            1.0,
            { newTempo: Double -> this.onTempoSliderUpdated(newTempo) })

        // Pitch
        binding!!.pitchToogleControlModes.setOnClickListener { v: View? ->
            val isCurrentlyVisible =
                binding!!.pitchControlModeTabs.visibility == View.GONE
            binding!!.pitchControlModeTabs.visibility = if (isCurrentlyVisible
            ) View.VISIBLE
            else View.GONE
            binding!!.pitchToogleControlModes
                .animateRotation(VideoPlayerUi.DEFAULT_CONTROLS_DURATION,
                    if (isCurrentlyVisible) 180 else 0)
        }

        pitchControlModeComponentMappings
            .forEach { (semitones: Boolean, textView: TextView) ->
                this.setupPitchControlModeTextView(semitones,
                    textView)
            }

        // Initialization is done at the end

        // Pitch - Percent
        setText(binding!!.pitchPercentMinimumText,
            DoubleFunction<String> { obj: Double -> formatSpeed(obj) },
            MIN_PITCH_OR_SPEED)
        setText(binding!!.pitchPercentMaximumText,
            DoubleFunction<String> { obj: Double -> formatSpeed(obj) },
            MAX_PITCH_OR_SPEED)

        binding!!.pitchPercentSeekbar.max = QUADRATIC_STRATEGY.progressOf(MAX_PITCH_OR_SPEED)
        setAndUpdatePitch(pitchPercent)
        binding!!.pitchPercentSeekbar.setOnSeekBarChangeListener(
            getTempoOrPitchSeekbarChangeListener(
                QUADRATIC_STRATEGY
            ) { newPitch: Double -> this.onPitchPercentSliderUpdated(newPitch) })

        registerOnStepClickListener(
            binding!!.pitchPercentStepDown,
            { pitchPercent },
            -1.0,
            { newPitch: Double -> this.onPitchPercentSliderUpdated(newPitch) })
        registerOnStepClickListener(
            binding!!.pitchPercentStepUp,
            { pitchPercent },
            1.0,
            { newPitch: Double -> this.onPitchPercentSliderUpdated(newPitch) })

        // Pitch - Semitone
        binding!!.pitchSemitoneSeekbar.setOnSeekBarChangeListener(
            getTempoOrPitchSeekbarChangeListener(
                SEMITONE_STRATEGY
            ) { newPitch: Double -> this.onPitchPercentSliderUpdated(newPitch) })

        registerOnSemitoneStepClickListener(
            binding!!.pitchSemitoneStepDown,
            -1
        ) { newPitch: Double -> this.onPitchPercentSliderUpdated(newPitch) }
        registerOnSemitoneStepClickListener(
            binding!!.pitchSemitoneStepUp,
            1
        ) { newPitch: Double -> this.onPitchPercentSliderUpdated(newPitch) }

        // Steps
        stepSizeComponentMappings
            .forEach { (stepSizeValue: Double, textView: TextView) -> this.setupStepTextView(stepSizeValue, textView) }
        // Initialize UI
        setStepSizeToUI(currentStepSize)

        // Bottom controls
        bindCheckboxWithBoolPref(
            binding!!.unhookCheckbox,
            R.string.playback_unhook_key,
            true
        ) { isChecked: Boolean? ->
            if (!isChecked!!) {
                // when unchecked, slide back to the minimum of current tempo or pitch
                ensureHookIsValidAndUpdateCallBack()
            }
        }

        setAndUpdateSkipSilence(skipSilence)
        binding!!.skipSilenceCheckbox.setOnCheckedChangeListener { compoundButton: CompoundButton?, isChecked: Boolean ->
            skipSilence = isChecked
            updateCallback()
        }

        // PitchControlMode has to be initialized at the end because it requires the unhookCheckbox
        changePitchControlMode(isCurrentPitchControlModeSemitone)
    }

    // -- General formatting --
    private fun setText(
            textView: TextView,
            formatter: DoubleFunction<String>,
            value: Double
    ) {
        Objects.requireNonNull(textView).text = formatter.apply(value)
    }

    // -- Steps --
    private fun registerOnStepClickListener(
            stepTextView: TextView,
            currentValueSupplier: DoubleSupplier,
            direction: Double,  // -1 for step down, +1 for step up
            newValueConsumer: DoubleConsumer
    ) {
        stepTextView.setOnClickListener { view: View? ->
            newValueConsumer.accept(
                currentValueSupplier.asDouble + 1 * currentStepSize * direction)
            updateCallback()
        }
    }

    private fun registerOnSemitoneStepClickListener(
            stepTextView: TextView,
            direction: Int,  // -1 for step down, +1 for step up
            newValueConsumer: DoubleConsumer
    ) {
        stepTextView.setOnClickListener { view: View? ->
            newValueConsumer.accept(semitonesToPercent(
                percentToSemitones(this.pitchPercent) + direction))
            updateCallback()
        }
    }

    // -- Pitch --
    private fun setupPitchControlModeTextView(
            semitones: Boolean,
            textView: TextView
    ) {
        textView.setOnClickListener { view: View? ->
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .putBoolean(getString(R.string.playback_adjust_by_semitones_key), semitones)
                .apply()
            changePitchControlMode(semitones)
        }
    }

    private val pitchControlModeComponentMappings: Map<Boolean, TextView>
        get() = java.util.Map.of<Boolean, TextView>(PITCH_CTRL_MODE_PERCENT, binding!!.pitchControlModePercent,
            PITCH_CTRL_MODE_SEMITONE, binding!!.pitchControlModeSemitone)

    private fun changePitchControlMode(semitones: Boolean) {
        // Bring all textviews into a normal state
        val pitchCtrlModeComponentMapping =
            pitchControlModeComponentMappings
        pitchCtrlModeComponentMapping.forEach { (v: Boolean?, textView: TextView) ->
            textView.background = resolveDrawable(requireContext(), R.attr.selectableItemBackground)
        }

        // Mark the selected textview
        val textView = pitchCtrlModeComponentMapping[semitones]
        if (textView != null) {
            textView.background = LayerDrawable(arrayOf(resolveDrawable(requireContext(), R.attr.dashed_border),
                resolveDrawable(requireContext(), R.attr.selectableItemBackground)
            ))
        }

        // Show or hide component
        binding!!.pitchPercentControl.visibility = if (semitones) View.GONE else View.VISIBLE
        binding!!.pitchSemitoneControl.visibility = if (semitones) View.VISIBLE else View.GONE

        if (semitones) {
            // Recalculate pitch percent when changing to semitone
            // (as it could be an invalid semitone value)
            val newPitchPercent = calcValidPitch(pitchPercent)

            // If the values differ set the new pitch
            if (this.pitchPercent != newPitchPercent) {
                Logd(TAG, "Bringing pitchPercent to correct corresponding semitone: currentPitchPercent = $pitchPercent, newPitchPercent = $newPitchPercent")

                this.onPitchPercentSliderUpdated(newPitchPercent)
                updateCallback()
            }
        } else if (!binding!!.unhookCheckbox.isChecked) {
            // When changing to percent it's possible that tempo is != pitch
            ensureHookIsValidAndUpdateCallBack()
        }
    }

    private val isCurrentPitchControlModeSemitone: Boolean
        get() = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getBoolean(
                getString(R.string.playback_adjust_by_semitones_key),
                PITCH_CTRL_MODE_PERCENT)

    // -- Steps (Set) --
    private fun setupStepTextView(
            stepSizeValue: Double,
            textView: TextView
    ) {
        setText(textView, { percent: Double -> getPercentString(percent) }, stepSizeValue)
        textView.setOnClickListener { view: View? ->
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .putFloat(getString(R.string.adjustment_step_key), stepSizeValue.toFloat())
                .apply()
            setStepSizeToUI(stepSizeValue)
        }
    }

    private val stepSizeComponentMappings: Map<Double, TextView>
        get() = java.util.Map.of<Double, TextView>(STEP_1_PERCENT_VALUE, binding!!.stepSizeOnePercent,
            STEP_5_PERCENT_VALUE, binding!!.stepSizeFivePercent,
            STEP_10_PERCENT_VALUE, binding!!.stepSizeTenPercent,
            STEP_25_PERCENT_VALUE, binding!!.stepSizeTwentyFivePercent,
            STEP_100_PERCENT_VALUE, binding!!.stepSizeOneHundredPercent)

    private fun setStepSizeToUI(newStepSize: Double) {
        // Bring all textviews into a normal state
        val stepSiteComponentMapping = stepSizeComponentMappings
        stepSiteComponentMapping.forEach { (v: Double?, textView: TextView) ->
            textView.background = resolveDrawable(requireContext(), R.attr.selectableItemBackground)
        }

        // Mark the selected textview
        val textView = stepSiteComponentMapping[newStepSize]
        if (textView != null) {
            textView.background = LayerDrawable(arrayOf(resolveDrawable(requireContext(), R.attr.dashed_border),
                resolveDrawable(requireContext(), R.attr.selectableItemBackground)
            ))
        }

        // Bind to the corresponding control components
        binding!!.tempoStepUp.text = getStepUpPercentString(newStepSize)
        binding!!.tempoStepDown.text = getStepDownPercentString(newStepSize)

        binding!!.pitchPercentStepUp.text = getStepUpPercentString(newStepSize)
        binding!!.pitchPercentStepDown.text = getStepDownPercentString(newStepSize)
    }

    private val currentStepSize: Double
        get() = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getFloat(getString(R.string.adjustment_step_key), DEFAULT_STEP.toFloat()).toDouble()

    // -- Additional options --
    private fun setAndUpdateSkipSilence(newSkipSilence: Boolean) {
        this.skipSilence = newSkipSilence
        binding!!.skipSilenceCheckbox.isChecked = newSkipSilence
    }

    // this method was written to be reusable
    private fun bindCheckboxWithBoolPref(
            checkBox: CheckBox,
            @StringRes resId: Int,
            defaultValue: Boolean,
            onInitialValueOrValueChange: Consumer<Boolean>
    ) {
        val prefValue = PreferenceManager
            .getDefaultSharedPreferences(requireContext())
            .getBoolean(getString(resId), defaultValue)

        checkBox.isChecked = prefValue

        onInitialValueOrValueChange.accept(prefValue)

        checkBox.setOnCheckedChangeListener { compoundButton: CompoundButton?, isChecked: Boolean ->
            // save whether pitch and tempo are unhooked or not
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .putBoolean(getString(resId), isChecked)
                .apply()
            onInitialValueOrValueChange.accept(isChecked)
        }
    }

    /**
     * Ensures that the slider hook is valid and if not sets and updates the sliders accordingly.
     * <br></br>
     * You have to ensure by yourself that the hooking is active.
     */
    private fun ensureHookIsValidAndUpdateCallBack() {
        if (tempo != pitchPercent) {
            setSliders(min(tempo, pitchPercent))
            updateCallback()
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Sliders
    ////////////////////////////////////////////////////////////////////////// */
    private fun getTempoOrPitchSeekbarChangeListener(
            sliderStrategy: SliderStrategy,
            newValueConsumer: DoubleConsumer
    ): OnSeekBarChangeListener {
        return object : SimpleOnSeekBarChangeListener() {
            override fun onProgressChanged(seekBar: SeekBar,
                                           progress: Int,
                                           fromUser: Boolean
            ) {
                if (fromUser) { // ensure that the user triggered the change
                    newValueConsumer.accept(sliderStrategy.valueOf(progress))
                    updateCallback()
                }
            }
        }
    }

    private fun onTempoSliderUpdated(newTempo: Double) {
        if (!binding!!.unhookCheckbox.isChecked) {
            setSliders(newTempo)
        } else {
            setAndUpdateTempo(newTempo)
        }
    }

    private fun onPitchPercentSliderUpdated(newPitch: Double) {
        if (!binding!!.unhookCheckbox.isChecked) {
            setSliders(newPitch)
        } else {
            setAndUpdatePitch(newPitch)
        }
    }

    private fun setSliders(newValue: Double) {
        setAndUpdateTempo(newValue)
        setAndUpdatePitch(newValue)
    }

    private fun setAndUpdateTempo(newTempo: Double) {
        this.tempo = MathUtils.clamp(newTempo, MIN_PITCH_OR_SPEED, MAX_PITCH_OR_SPEED)

        binding!!.tempoSeekbar.progress = QUADRATIC_STRATEGY.progressOf(tempo)
        setText(binding!!.tempoCurrentText, DoubleFunction<String> { obj: Double -> formatSpeed(obj) }, tempo)
    }

    private fun setAndUpdatePitch(newPitch: Double) {
        this.pitchPercent = calcValidPitch(newPitch)

        binding!!.pitchPercentSeekbar.progress = QUADRATIC_STRATEGY.progressOf(pitchPercent)
        binding!!.pitchSemitoneSeekbar.progress = SEMITONE_STRATEGY.progressOf(pitchPercent)
        setText(binding!!.pitchPercentCurrentText,
            DoubleFunction<String> { obj: Double -> formatSpeed(obj) },
            pitchPercent)
        setText(binding!!.pitchSemitoneCurrentText,
            DoubleFunction<String> { obj: Double -> formatPitchSemitones(obj) },
            pitchPercent)
    }

    private fun calcValidPitch(newPitch: Double): Double {
        val calcPitch = MathUtils.clamp(newPitch, MIN_PITCH_OR_SPEED, MAX_PITCH_OR_SPEED)

        if (!isCurrentPitchControlModeSemitone) {
            return calcPitch
        }

        return semitonesToPercent(
            percentToSemitones(calcPitch))
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Helper
    ////////////////////////////////////////////////////////////////////////// */
    private fun updateCallback() {
        if (callback == null) return
        Logd(TAG, "Updating callback: tempo = $tempo, pitchPercent = $pitchPercent, skipSilence = $skipSilence")
        callback!!.onPlaybackParameterChanged(tempo.toFloat(), pitchPercent.toFloat(), skipSilence)
    }

    interface Callback {
        fun onPlaybackParameterChanged(playbackTempo: Float, playbackPitch: Float, playbackSkipSilence: Boolean)
    }

    companion object {
        private const val TAG = "PlaybackParameterDialog"

        // Minimum allowable range in ExoPlayer
        private const val MIN_PITCH_OR_SPEED = 0.10
        private const val MAX_PITCH_OR_SPEED = 3.00

        private const val PITCH_CTRL_MODE_PERCENT = false
        private const val PITCH_CTRL_MODE_SEMITONE = true

        private const val STEP_1_PERCENT_VALUE = 0.01
        private const val STEP_5_PERCENT_VALUE = 0.05
        private const val STEP_10_PERCENT_VALUE = 0.10
        private const val STEP_25_PERCENT_VALUE = 0.25
        private const val STEP_100_PERCENT_VALUE = 1.00

        private const val DEFAULT_TEMPO = 1.00
        private const val DEFAULT_PITCH_PERCENT = 1.00
        private const val DEFAULT_STEP = STEP_25_PERCENT_VALUE
        private const val DEFAULT_SKIP_SILENCE = false

        private val QUADRATIC_STRATEGY: SliderStrategy = Quadratic(
            MIN_PITCH_OR_SPEED,
            MAX_PITCH_OR_SPEED,
            1.00,
            10000)

        private val SEMITONE_STRATEGY: SliderStrategy = object : SliderStrategy {
            override fun progressOf(value: Double): Int {
                return percentToSemitones(value) + 12
            }

            override fun valueOf(progress: Int): Double {
                return semitonesToPercent(progress - 12)
            }
        }

        fun newInstance(
                playbackTempo: Double,
                playbackPitch: Double,
                playbackSkipSilence: Boolean,
                callback: Callback?
        ): PlaybackParameterDialog {
            val dialog = PlaybackParameterDialog()
            dialog.callback = callback

            dialog.initialTempo = playbackTempo
            dialog.initialPitchPercent = playbackPitch
            dialog.initialSkipSilence = playbackSkipSilence

            dialog.tempo = dialog.initialTempo
            dialog.pitchPercent = dialog.initialPitchPercent
            dialog.skipSilence = dialog.initialSkipSilence

            return dialog
        }

        private fun getStepUpPercentString(percent: Double): String {
            return '+'.toString() + getPercentString(percent)
        }

        private fun getStepDownPercentString(percent: Double): String {
            return '-'.toString() + getPercentString(percent)
        }

        private fun getPercentString(percent: Double): String {
            return formatPitch(percent)
        }
    }
}
