package org.schabi.newpipe.player.helper

import androidx.core.math.MathUtils
import kotlin.math.ln
import kotlin.math.pow

/**
 * Converts between percent and 12-tone equal temperament semitones.
 * <br></br>
 * @see  [
 * Wikipedia: Equal temperament.Twelve-tone equal temperament
](https://en.wikipedia.org/wiki/Equal_temperament.Twelve-tone_equal_temperament) *
 */
object PlayerSemitoneHelper {
    const val SEMITONE_COUNT: Int = 12

    @JvmStatic
    fun formatPitchSemitones(percent: Double): String {
        return formatPitchSemitones(percentToSemitones(percent))
    }

    fun formatPitchSemitones(semitones: Int): String {
        return if (semitones > 0) "+$semitones" else "" + semitones
    }

    @JvmStatic
    fun semitonesToPercent(semitones: Int): Double {
        return (2.0).pow(ensureSemitonesInRange(semitones) / SEMITONE_COUNT.toDouble())
    }

    @JvmStatic
    fun percentToSemitones(percent: Double): Int {
        return ensureSemitonesInRange(
            Math.round(SEMITONE_COUNT * ln(percent) / ln(2.0)).toInt())
    }

    private fun ensureSemitonesInRange(semitones: Int): Int {
        return MathUtils.clamp(semitones, -SEMITONE_COUNT, SEMITONE_COUNT)
    }
}
