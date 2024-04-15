/*
 * Copyright 2018 Mauricio Colli <mauriciocolli@outlook.com>
 * CollapsibleView.java is part of NewPipe
 *
 * License: GPL-3.0+
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.schabi.newpipe.views

import android.animation.ValueAnimator
import android.content.Context
import android.os.Parcelable
import android.util.AttributeSet
import android.util.Log
import android.widget.LinearLayout
import androidx.annotation.IntDef
import icepick.Icepick
import icepick.State
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.ktx.animateHeight
import org.schabi.newpipe.views.CollapsibleView

/**
 * A view that can be fully collapsed and expanded.
 */
class CollapsibleView : LinearLayout {
    @JvmField
    @State
    @ViewMode
    var currentState: Int = COLLAPSED
    private var readyToChangeState = false

    private var targetHeight = -1
    private var currentAnimator: ValueAnimator? = null
    private val listeners: MutableList<StateListener> = ArrayList()

    constructor(context: Context?) : super(context)

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context?, attrs: AttributeSet?,
                defStyleAttr: Int
    ) : super(context, attrs, defStyleAttr)

    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int,
                defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    /*//////////////////////////////////////////////////////////////////////////
    // Collapse/expand logic
    ////////////////////////////////////////////////////////////////////////// */
    /**
     * This method recalculates the height of this view so it **must** be called when
     * some child changes (e.g. add new views, change text).
     */
    fun ready() {
        if (MainActivity.DEBUG) {
            Log.d(TAG, getDebugLogString("ready() called"))
        }

        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
            MeasureSpec.UNSPECIFIED)
        targetHeight = measuredHeight

        layoutParams.height = if (currentState == COLLAPSED) 0 else targetHeight
        requestLayout()
        broadcastState()

        readyToChangeState = true

        if (MainActivity.DEBUG) {
            Log.d(TAG, getDebugLogString("ready() *after* measuring"))
        }
    }

    fun collapse() {
        if (MainActivity.DEBUG) {
            Log.d(TAG, getDebugLogString("collapse() called"))
        }

        if (!readyToChangeState) {
            return
        }

        val height = height
        if (height == 0) {
            setCurrentState(COLLAPSED)
            return
        }

        if (currentAnimator != null && currentAnimator!!.isRunning) {
            currentAnimator!!.cancel()
        }
        currentAnimator = this.animateHeight(ANIMATION_DURATION.toLong(), 0)

        setCurrentState(COLLAPSED)
    }

    fun expand() {
        if (MainActivity.DEBUG) {
            Log.d(TAG, getDebugLogString("expand() called"))
        }

        if (!readyToChangeState) {
            return
        }

        val height = height
        if (height == this.targetHeight) {
            setCurrentState(EXPANDED)
            return
        }

        if (currentAnimator != null && currentAnimator!!.isRunning) {
            currentAnimator!!.cancel()
        }
        currentAnimator = this.animateHeight(ANIMATION_DURATION.toLong(), this.targetHeight)
        setCurrentState(EXPANDED)
    }

    fun switchState() {
        if (!readyToChangeState) {
            return
        }

        if (currentState == COLLAPSED) {
            expand()
        } else {
            collapse()
        }
    }

    @ViewMode
    fun getCurrentState(): Int {
        return currentState
    }

    fun setCurrentState(@ViewMode currentState: Int) {
        this.currentState = currentState
        broadcastState()
    }

    fun broadcastState() {
        for (listener in listeners) {
            listener.onStateChanged(currentState)
        }
    }

    /**
     * Add a listener which will be listening for changes in this view (i.e. collapsed or expanded).
     * @param listener [StateListener] to be added
     */
    fun addListener(listener: StateListener) {
        check(!listeners.contains(listener)) { "Trying to add the same listener multiple times" }

        listeners.add(listener)
    }

    /**
     * Remove a listener so it doesn't receive more state changes.
     * @param listener [StateListener] to be removed
     */
    fun removeListener(listener: StateListener) {
        listeners.remove(listener)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // State Saving
    ////////////////////////////////////////////////////////////////////////// */
    public override fun onSaveInstanceState(): Parcelable? {
        return Icepick.saveInstanceState(this, super.onSaveInstanceState())
    }

    public override fun onRestoreInstanceState(state: Parcelable) {
        super.onRestoreInstanceState(Icepick.restoreInstanceState(this, state))

        ready()
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Internal
    ////////////////////////////////////////////////////////////////////////// */
    fun getDebugLogString(description: String?): String {
        return String.format("%-100s → %s",
            description, "readyToChangeState = [" + readyToChangeState + "], "
                    + "currentState = [" + currentState + "], "
                    + "targetHeight = [" + targetHeight + "], "
                    + "mW x mH = [" + measuredWidth + "x" + measuredHeight + "], "
                    + "W x H = [" + width + "x" + height + "]")
    }

    @Retention(AnnotationRetention.SOURCE)
    @IntDef(ViewMode.COLLAPSED, ViewMode.EXPANDED)
    annotation class ViewMode {
        companion object {
            const val COLLAPSED = 0
            const val EXPANDED = 1
        }
    }

    /**
     * Simple interface used for listening state changes of the [CollapsibleView].
     */
    interface StateListener {
        /**
         * Called when the state changes.
         *
         * @param newState the state that the [CollapsibleView] transitioned to,<br></br>
         * it's an integer being either [.COLLAPSED] or [.EXPANDED]
         */
        fun onStateChanged(@ViewMode newState: Int)
    }

    companion object {
        private val TAG: String = CollapsibleView::class.java.simpleName

        private const val ANIMATION_DURATION = 420

        const val COLLAPSED: Int = 0
        const val EXPANDED: Int = 1
    }
}
