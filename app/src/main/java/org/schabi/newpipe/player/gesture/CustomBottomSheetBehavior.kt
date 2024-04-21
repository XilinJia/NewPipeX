package org.schabi.newpipe.player.gesture

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.schabi.newpipe.R

class CustomBottomSheetBehavior(context: Context,
                                attrs: AttributeSet?
) : BottomSheetBehavior<FrameLayout?>(context, attrs) {
    var globalRect: Rect = Rect()
    private var skippingInterception = false
    private val skipInterceptionOfElements: List<Int> = listOf(
        R.id.detail_content_root_layout, R.id.relatedItemsLayout,
        R.id.itemsListPanel, R.id.view_pager, R.id.tab_layout, R.id.bottomControls,
        R.id.playPauseButton, R.id.playPreviousButton, R.id.playNextButton)

    override fun onInterceptTouchEvent(parent: CoordinatorLayout,
                                       child: FrameLayout,
                                       event: MotionEvent
    ): Boolean {
        // Drop following when action ends
        if (event.action == MotionEvent.ACTION_CANCEL
                || event.action == MotionEvent.ACTION_UP) {
            skippingInterception = false
        }

        // Found that user still swiping, continue following
        if (skippingInterception || state == STATE_SETTLING) {
            return false
        }

        // The interception listens for the child view with the id "fragment_player_holder",
        // so the following two-finger gesture will be triggered only for the player view on
        // portrait and for the top controls (visible) on landscape.
        skipCollapsed = event.pointerCount == 2
        if (event.pointerCount == 2) {
            return super.onInterceptTouchEvent(parent, child, event)
        }

        // Don't need to do anything if bottomSheet isn't expanded
        if (state == STATE_EXPANDED
                && event.action == MotionEvent.ACTION_DOWN) {
            // Without overriding scrolling will not work when user touches these elements
            for (element in skipInterceptionOfElements) {
                val view = child.findViewById<View>(element)
                if (view != null) {
                    val visible = view.getGlobalVisibleRect(globalRect)
                    if (visible
                            && globalRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                        // Makes bottom part of the player draggable in portrait when
                        // playbackControlRoot is hidden
                        if (element == R.id.bottomControls
                                && child.findViewById<View>(R.id.playbackControlRoot)
                                    .visibility != View.VISIBLE) {
                            return super.onInterceptTouchEvent(parent, child, event)
                        }
                        skippingInterception = true
                        return false
                    }
                }
            }
        }

        return super.onInterceptTouchEvent(parent, child, event)
    }
}
