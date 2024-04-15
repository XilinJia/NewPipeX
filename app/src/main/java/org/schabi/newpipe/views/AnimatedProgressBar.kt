package org.schabi.newpipe.views

import android.content.Context
import android.util.AttributeSet
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.Transformation
import android.widget.ProgressBar

class AnimatedProgressBar : ProgressBar {
    private var animation: ProgressBarAnimation? = null

    constructor(context: Context?) : super(context)

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context?, attrs: AttributeSet?,
                defStyleAttr: Int
    ) : super(context, attrs, defStyleAttr)

    @Synchronized
    fun setProgressAnimated(progress: Int) {
        cancelAnimation()
        animation = ProgressBarAnimation(this, getProgress().toFloat(), progress.toFloat())
        startAnimation(animation)
    }

    private fun cancelAnimation() {
        if (animation != null) {
            animation!!.cancel()
            animation = null
        }
        clearAnimation()
    }

    private class ProgressBarAnimation(private val progressBar: AnimatedProgressBar, private val from: Float,
                                       private val to: Float
    ) : Animation() {
        init {
            duration = 500
            interpolator = AccelerateDecelerateInterpolator()
        }

        override fun applyTransformation(interpolatedTime: Float, t: Transformation) {
            super.applyTransformation(interpolatedTime, t)
            val value = from + (to - from) * interpolatedTime
            progressBar.progress = value.toInt()
        }
    }
}
