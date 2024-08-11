@file:JvmName("TextViewUtils")

package org.schabi.newpipe.ui.ktx

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.animation.addListener
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import org.schabi.newpipe.util.Logd

private const val TAG = "TextViewUtils"

/**
 * Animate the text color of any view that extends [TextView] (Buttons, EditText...).
 *
 * @param duration   the duration of the animation
 * @param colorStart the text color to start with
 * @param colorEnd   the text color to end with
 */
fun TextView.animateTextColor(duration: Long, @ColorInt colorStart: Int, @ColorInt colorEnd: Int) {
    Logd(TAG, "animateTextColor() called with: view = [$this], duration = [$duration], colorStart = [$colorStart], colorEnd = [$colorEnd]")

    val viewPropertyAnimator = ValueAnimator.ofObject(ArgbEvaluator(), colorStart, colorEnd)
    viewPropertyAnimator.interpolator = FastOutSlowInInterpolator()
    viewPropertyAnimator.duration = duration
    viewPropertyAnimator.addUpdateListener { setTextColor(it.animatedValue as Int) }
    viewPropertyAnimator.addListener(onCancel = { setTextColor(colorEnd) }, onEnd = { setTextColor(colorEnd) })
    viewPropertyAnimator.start()
}
