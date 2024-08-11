package org.schabi.newpipe.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.google.android.material.tabs.TabLayout
import kotlin.math.max

/**
 * A TabLayout that is scrollable when tabs exceed its width.
 * Hides when there are less than 2 tabs.
 */
class ScrollableTabLayout : TabLayout {
    private var layoutWidth = 0
    private var prevVisibility = GONE

    constructor(context: Context?) : super(context!!)

    constructor(context: Context?, attrs: AttributeSet?) : super(context!!, attrs)

    constructor(context: Context?, attrs: AttributeSet?,
                defStyleAttr: Int
    ) : super(context!!, attrs, defStyleAttr)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int,
                          b: Int
    ) {
        super.onLayout(changed, l, t, r, b)

        remeasureTabs()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        layoutWidth = w
    }

    override fun addTab(tab: Tab, position: Int, setSelected: Boolean) {
        super.addTab(tab, position, setSelected)

        hasMultipleTabs()

        // Adding a tab won't decrease total tabs' width so tabMode won't have to change to FIXED
        if (tabMode != MODE_SCROLLABLE) {
            remeasureTabs()
        }
    }

    override fun removeTabAt(position: Int) {
        super.removeTabAt(position)

        hasMultipleTabs()

        // Removing a tab won't increase total tabs' width
        // so tabMode won't have to change to SCROLLABLE
        if (tabMode != MODE_FIXED) {
            remeasureTabs()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)

        // Check width if some tabs have been added/removed while ScrollableTabLayout was invisible
        // We don't have to check if it was GONE because then requestLayout() will be called
        if (changedView === this) {
            if (prevVisibility == INVISIBLE) {
                remeasureTabs()
            }
            prevVisibility = visibility
        }
    }

    var mode: Int
        get() = super.getTabMode()
        private set(mode) {
            if (mode == tabMode) {
                return
            }

            tabMode = mode
        }

    /**
     * Make ScrollableTabLayout not visible if there are less than two tabs.
     */
    private fun hasMultipleTabs() {
        visibility = if (tabCount > 1) {
            VISIBLE
        } else {
            GONE
        }
    }

    /**
     * Calculate minimal width required by tabs and set tabMode accordingly.
     */
    private fun remeasureTabs() {
        if (prevVisibility != VISIBLE) {
            return
        }
        if (layoutWidth == 0) {
            return
        }

        val count = tabCount
        var contentWidth = 0
        var cwDouble = 0.0
        for (i in 0 until count) {
            val child: View = getTabAt(i)!!.view
            if (child.visibility == VISIBLE) {
                // Use tab's minimum requested width should actual content be too small
                cwDouble += max(child.minimumWidth.toDouble(), child.measuredWidth.toDouble())
            }
        }
        contentWidth = cwDouble.toInt()
        if (contentWidth > layoutWidth) {
            this.mode = MODE_SCROLLABLE
        } else {
            this.mode = MODE_FIXED
        }
    }

    companion object {
        private val TAG: String = ScrollableTabLayout::class.java.simpleName
    }
}
