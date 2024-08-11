package org.schabi.newpipe.ui

interface ViewContract<I> {
    fun showLoading()

    fun hideLoading()

    fun showEmptyState()

    fun handleResult(result: I)

    fun handleError()
}
