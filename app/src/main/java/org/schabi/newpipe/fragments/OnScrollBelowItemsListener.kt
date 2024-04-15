package org.schabi.newpipe.fragments

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager

/**
 * Recycler view scroll listener which calls the method [.onScrolledDown]
 * if the view is scrolled below the last item.
 */
abstract class OnScrollBelowItemsListener : RecyclerView.OnScrollListener() {
    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        super.onScrolled(recyclerView, dx, dy)
        if (dy > 0) {
            var pastVisibleItems = 0
            val layoutManager = recyclerView.layoutManager

            val visibleItemCount = layoutManager!!.childCount
            val totalItemCount = layoutManager.itemCount

            // Already covers the GridLayoutManager case
            when (layoutManager) {
                is LinearLayoutManager -> {
                    pastVisibleItems = layoutManager.findFirstVisibleItemPosition()
                }
                is StaggeredGridLayoutManager -> {
                    val positions = layoutManager.findFirstVisibleItemPositions(null)
                    if (positions != null && positions.size > 0) {
                        pastVisibleItems = positions[0]
                    }
                }
            }

            if ((visibleItemCount + pastVisibleItems) >= totalItemCount) {
                onScrolledDown(recyclerView)
            }
        }
    }

    /**
     * Called when the recycler view is scrolled below the last item.
     *
     * @param recyclerView the recycler view
     */
    abstract fun onScrolledDown(recyclerView: RecyclerView?)
}
