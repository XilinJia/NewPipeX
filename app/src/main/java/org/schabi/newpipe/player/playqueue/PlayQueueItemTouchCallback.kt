package org.schabi.newpipe.player.playqueue

import androidx.core.math.MathUtils
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.sign

abstract class PlayQueueItemTouchCallback :
    ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.RIGHT) {
    abstract fun onMove(sourceIndex: Int, targetIndex: Int)

    abstract fun onSwiped(index: Int)

    override fun interpolateOutOfBoundsScroll(recyclerView: RecyclerView,
                                              viewSize: Int,
                                              viewSizeOutOfBounds: Int,
                                              totalSize: Int,
                                              msSinceStartScroll: Long
    ): Int {
        val standardSpeed = super.interpolateOutOfBoundsScroll(recyclerView, viewSize, viewSizeOutOfBounds, totalSize, msSinceStartScroll)
        val clampedAbsVelocity: Int = MathUtils.clamp(abs(standardSpeed.toDouble()), MINIMUM_INITIAL_DRAG_VELOCITY, MAXIMUM_INITIAL_DRAG_VELOCITY).toInt()
        return clampedAbsVelocity * sign(viewSizeOutOfBounds.toDouble()).toInt()
    }

    override fun onMove(recyclerView: RecyclerView,
                        source: RecyclerView.ViewHolder,
                        target: RecyclerView.ViewHolder
    ): Boolean {
        if (source.itemViewType != target.itemViewType) {
            return false
        }

        val sourceIndex = source.layoutPosition
        val targetIndex = target.layoutPosition
        onMove(sourceIndex, targetIndex)
        return true
    }

    override fun isLongPressDragEnabled(): Boolean {
        return false
    }

    override fun isItemViewSwipeEnabled(): Boolean {
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, swipeDir: Int) {
        onSwiped(viewHolder.bindingAdapterPosition)
    }

    companion object {
        private const val MINIMUM_INITIAL_DRAG_VELOCITY = 10.0
        private const val MAXIMUM_INITIAL_DRAG_VELOCITY = 25.0
    }
}
