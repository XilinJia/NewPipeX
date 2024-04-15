package org.schabi.newpipe.fragments

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import icepick.State
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorPanelHelper
import org.schabi.newpipe.error.ErrorUtil
import org.schabi.newpipe.error.ErrorUtil.Companion.showSnackbar
import org.schabi.newpipe.ktx.animate
import org.schabi.newpipe.util.InfoCache
import java.util.concurrent.atomic.AtomicBoolean

abstract class BaseStateFragment<I> : BaseFragment(), ViewContract<I> {
    @JvmField
    @State
    var wasLoading: AtomicBoolean = AtomicBoolean()
    @JvmField
    protected var isLoading: AtomicBoolean = AtomicBoolean()

    @JvmField
    protected var emptyStateView: View? = null
    protected var emptyStateMessageView: TextView? = null
    private var loadingProgressBar: ProgressBar? = null

    private var errorPanelHelper: ErrorPanelHelper? = null

    @JvmField
    @State
    var lastPanelError: ErrorInfo? = null

    override fun onViewCreated(rootView: View, savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreateView")
        super.onViewCreated(rootView, savedInstanceState)
        doInitialLoadLogic()
    }

    override fun onPause() {
        super.onPause()
        wasLoading.set(isLoading.get())
    }

    override fun onResume() {
        super.onResume()
        if (lastPanelError != null) {
            showError(lastPanelError!!)
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Init
    ////////////////////////////////////////////////////////////////////////// */
    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)
        emptyStateView = rootView.findViewById(R.id.empty_state_view)
        emptyStateMessageView = rootView.findViewById(R.id.empty_state_message)
        loadingProgressBar = rootView.findViewById(R.id.loading_progress_bar)
        errorPanelHelper = ErrorPanelHelper(this, rootView) { this.onRetryButtonClicked() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        errorPanelHelper?.dispose()
        emptyStateView = null
        emptyStateMessageView = null
    }

    protected fun onRetryButtonClicked() {
        reloadContent()
    }

    open fun reloadContent() {
        startLoading(true)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Load
    ////////////////////////////////////////////////////////////////////////// */
    protected open fun doInitialLoadLogic() {
        startLoading(true)
    }

    protected open fun startLoading(forceLoad: Boolean) {
        if (DEBUG) {
            Log.d(TAG, "startLoading() called with: forceLoad = [$forceLoad]")
        }
        showLoading()
        isLoading.set(true)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    ////////////////////////////////////////////////////////////////////////// */
    override fun showLoading() {
        emptyStateView?.animate(false, 150)
        loadingProgressBar?.animate(true, 400)
        hideErrorPanel()
    }

    override fun hideLoading() {
        emptyStateView?.animate(false, 150)
        loadingProgressBar?.animate(false, 0)
        hideErrorPanel()
    }

    override fun showEmptyState() {
        isLoading.set(false)
        emptyStateView?.animate(true, 200)
        loadingProgressBar?.animate(false, 0)
        hideErrorPanel()
    }

    override fun handleResult(result: I) {
        if (DEBUG) {
            Log.d(TAG, "handleResult() called with: result = [$result]")
        }
        hideLoading()
    }

    override fun handleError() {
        isLoading.set(false)
        InfoCache.instance.clearCache()
        emptyStateView?.animate(false, 150)
        loadingProgressBar?.animate(false, 0)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Error handling
    ////////////////////////////////////////////////////////////////////////// */
    fun showError(errorInfo: ErrorInfo) {
        handleError()

        if (isDetached || isRemoving) {
            if (DEBUG) {
                Log.w(TAG, "showError() is detached or removing = [$errorInfo]")
            }
            return
        }

        errorPanelHelper!!.showError(errorInfo)
        lastPanelError = errorInfo
    }

    fun showTextError(errorString: String) {
        handleError()

        if (isDetached || isRemoving) {
            if (DEBUG) {
                Log.w(TAG, "showTextError() is detached or removing = [$errorString]")
            }
            return
        }

        errorPanelHelper!!.showTextError(errorString)
    }

    protected fun setEmptyStateMessage(@StringRes text: Int) {
        emptyStateMessageView?.setText(text)
    }

    fun hideErrorPanel() {
        errorPanelHelper!!.hide()
        lastPanelError = null
    }

    val isErrorPanelVisible: Boolean
        get() = errorPanelHelper!!.isVisible()

    /**
     * Directly calls [ErrorUtil.showSnackbar], that shows a snackbar if
     * a valid view can be found, otherwise creates an error report notification.
     *
     * @param errorInfo The error information
     */
    fun showSnackBarError(errorInfo: ErrorInfo) {
        if (DEBUG) {
            Log.d(TAG, "showSnackBarError() called with: errorInfo = [$errorInfo]")
        }
        showSnackbar(this, errorInfo)
    }
}
