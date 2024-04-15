/*
 * Copyright 2019 Alexander Rvachev <rvacheva@nxt.ru>
 * FocusOverlayView.java is part of NewPipe
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

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.*
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.appcompat.view.WindowCallbackWrapper
import org.schabi.newpipe.R
import java.lang.ref.WeakReference

class FocusOverlayView(context: Context) : Drawable(), OnGlobalFocusChangeListener, OnDrawListener,
    OnGlobalLayoutListener, OnScrollChangedListener, OnTouchModeChangeListener {
    private var isInTouchMode = false

    private val focusRect = Rect()

    private val rectPaint = Paint()

    private val animator: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            updateRect()
        }
    }

    private var focused: WeakReference<View>? = null

    init {
        rectPaint.style = Paint.Style.STROKE
        rectPaint.strokeWidth = 2f
        rectPaint.color = context.resources.getColor(R.color.white)
    }

    override fun onGlobalFocusChanged(oldFocus: View?, newFocus: View?) {
        focused = if (newFocus != null) {
            WeakReference(newFocus)
        } else {
            null
        }

        updateRect()

        animator.sendEmptyMessageDelayed(0, 1000)
    }

    private fun updateRect() {
        val focusedView = if (focused == null) null else focused!!.get()

        val l = focusRect.left
        val r = focusRect.right
        val t = focusRect.top
        val b = focusRect.bottom

        if (focusedView != null && isShown(focusedView)) {
            focusedView.getGlobalVisibleRect(focusRect)
        }

        if (shouldClearFocusRect(focusedView, focusRect)) {
            focusRect.setEmpty()
        }

        if (l != focusRect.left || r != focusRect.right || t != focusRect.top || b != focusRect.bottom) {
            invalidateSelf()
        }
    }

    private fun isShown(view: View): Boolean {
        return view.width != 0 && view.height != 0 && view.isShown
    }

    override fun onDraw() {
        updateRect()
    }

    override fun onScrollChanged() {
        updateRect()

        animator.removeMessages(0)
        animator.sendEmptyMessageDelayed(0, 1000)
    }

    override fun onGlobalLayout() {
        updateRect()

        animator.sendEmptyMessageDelayed(0, 1000)
    }

    override fun onTouchModeChanged(inTouchMode: Boolean) {
        this.isInTouchMode = inTouchMode

        if (inTouchMode) {
            updateRect()
        } else {
            invalidateSelf()
        }
    }

    fun setCurrentFocus(newFocus: View?) {
        if (newFocus == null) return

        this.isInTouchMode = newFocus.isInTouchMode

        onGlobalFocusChanged(null, newFocus)
    }

    override fun draw(canvas: Canvas) {
        if (!isInTouchMode && focusRect.width() != 0) {
            canvas.drawRect(focusRect, rectPaint)
        }
    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSPARENT
    }

    override fun setAlpha(alpha: Int) {
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
    }

    /*
     * When any view in the player looses it's focus (after setVisibility(GONE)) the focus gets
     * added to the whole fragment which has a width and height equal to the window frame.
     * The easiest way to avoid the unneeded frame is to skip highlighting of rect that is
     * equal to the overlayView bounds
     * */
    private fun shouldClearFocusRect(focusedView: View?, focusedRect: Rect): Boolean {
        return focusedView == null || focusedRect == bounds
    }

    private fun onKey(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return
        }

        updateRect()

        animator.sendEmptyMessageDelayed(0, 100)
    }

    companion object {
        @JvmStatic
        fun setupFocusObserver(dialog: Dialog) {
            val displayRect = Rect()

            val window = dialog.window!!
            val decor = window.decorView
            decor.getWindowVisibleDisplayFrame(displayRect)

            val overlay = FocusOverlayView(dialog.context)
            overlay.setBounds(0, 0, displayRect.width(), displayRect.height())

            setupOverlay(window, overlay)
        }

        @JvmStatic
        fun setupFocusObserver(activity: Activity) {
            val displayRect = Rect()

            val window = activity.window
            val decor = window.decorView
            decor.getWindowVisibleDisplayFrame(displayRect)

            val overlay = FocusOverlayView(activity)
            overlay.setBounds(0, 0, displayRect.width(), displayRect.height())

            setupOverlay(window, overlay)
        }

        private fun setupOverlay(window: Window?, overlay: FocusOverlayView) {
            val decor = window!!.decorView as ViewGroup
            decor.overlay.add(overlay)

            fixFocusHierarchy(decor)

            val observer = decor.viewTreeObserver
            observer.addOnScrollChangedListener(overlay)
            observer.addOnGlobalFocusChangeListener(overlay)
            observer.addOnGlobalLayoutListener(overlay)
            observer.addOnTouchModeChangeListener(overlay)
            observer.addOnDrawListener(overlay)

            overlay.setCurrentFocus(decor.focusedChild)

            // Some key presses don't actually move focus, but still result in movement on screen.
            // For example, MovementMethod of TextView may cause requestRectangleOnScreen() due to
            // some "focusable" spans, which in turn causes CoordinatorLayout to "scroll" it's children.
            // Unfortunately many such forms of "scrolling" do not count as scrolling for purpose
            // of dispatching ViewTreeObserver callbacks, so we have to intercept them by directly
            // receiving keys from Window.
//            TODO: WindowCallbackWrapper can only be called from within the same library group prefix (referenced groupId=`androidx.appcompat` with prefix androidx from groupId=`NewPipe`)
//            window.callback = object : WindowCallbackWrapper(window.callback) {
//                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
//                    val res = super.dispatchKeyEvent(event)
//                    overlay.onKey(event)
//                    return res
//                }
//            }
        }

        private fun fixFocusHierarchy(decor: View) {
            // During Android 8 development some dumb ass decided, that action bar has to be
            // a keyboard focus cluster. Unfortunately, keyboard clusters do not work for primary
            // auditory of key navigation — Android TV users (Android TV remotes do not have
            // keyboard META key for moving between clusters). We have to fix this unfortunate accident
            // While we are at it, let's deal with touchscreenBlocksFocus too.

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return
            }

            if (decor !is ViewGroup) {
                return
            }

            clearFocusObstacles(decor)
        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        private fun clearFocusObstacles(viewGroup: ViewGroup) {
            viewGroup.touchscreenBlocksFocus = false

            if (viewGroup.isKeyboardNavigationCluster) {
                viewGroup.isKeyboardNavigationCluster = false

                return  // clusters aren't supposed to nest
            }

            val childCount = viewGroup.childCount

            for (i in 0 until childCount) {
                val view = viewGroup.getChildAt(i)

                if (view is ViewGroup) {
                    clearFocusObstacles(view)
                }
            }
        }
    }
}
