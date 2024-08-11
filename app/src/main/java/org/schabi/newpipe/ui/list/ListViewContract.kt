package org.schabi.newpipe.ui.list

import org.schabi.newpipe.ui.ViewContract

interface ListViewContract<I, N> : ViewContract<I> {
    fun showListFooter(show: Boolean)

    fun handleNextItems(result: N)
}
