package org.schabi.newpipe.ui.list

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import icepick.State
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.ListInfo
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.util.NO_SERVICE_ID
import org.schabi.newpipe.ui.views.NewPipeRecyclerView
import java.util.*

abstract class BaseListInfoFragment<I : InfoItem, L : ListInfo<I>> protected constructor(private val errorUserAction: UserAction)
    : BaseListFragment<L, InfoItemsPage<I>>() {

    @JvmField
    @State
    var serviceId: Int = NO_SERVICE_ID

    @JvmField
    @State
    var name: String? = null

    @JvmField
    @State
    var url: String? = null

    @JvmField
    protected var currentInfo: L? = null
    @JvmField
    protected var currentNextPage: Page? = null
    @JvmField
    protected var currentWorker: Disposable? = null

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)
        setTitle(name?:"")
        showListFooter(hasMoreItems())
    }

    override fun onPause() {
        super.onPause()
        currentWorker?.dispose()
    }

    override fun onResume() {
        super.onResume()
        // Check if it was loading when the fragment was stopped/paused,
        if (wasLoading.getAndSet(false)) {
            if (hasMoreItems() && infoListAdapter!!.itemsList.isNotEmpty()) loadMoreItems()
            else doInitialLoadLogic()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentWorker?.dispose()
        currentWorker = null
    }

    /*//////////////////////////////////////////////////////////////////////////
    // State Saving
    ////////////////////////////////////////////////////////////////////////// */
    override fun writeTo(objectsToSave: Queue<Any>?) {
        super.writeTo(objectsToSave)
        objectsToSave?.add(currentInfo)
        objectsToSave?.add(currentNextPage)
    }

    @Throws(Exception::class)
    override fun readFrom(savedObjects: Queue<Any>) {
        super.readFrom(savedObjects)
        currentInfo = savedObjects.poll() as? L
        currentNextPage = savedObjects.poll() as? Page
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Load and handle
    ////////////////////////////////////////////////////////////////////////// */
    override fun doInitialLoadLogic() {
        Logd(TAG, "doInitialLoadLogic() called")

        if (currentInfo == null) startLoading(false)
        else handleResult(currentInfo!!)
    }

    /**
     * Implement the logic to load the info from the network.<br></br>
     * You can use the default implementations from [org.schabi.newpipe.util.ExtractorHelper].
     *
     * @param forceLoad allow or disallow the result to come from the cache
     * @return Rx [Single] containing the [ListInfo]
     */
    protected abstract fun loadResult(forceLoad: Boolean): Single<L>

    public override fun startLoading(forceLoad: Boolean) {
        super.startLoading(forceLoad)

        showListFooter(false)
        infoListAdapter!!.clearStreamItemList()

        currentInfo = null
        currentWorker?.dispose()
        currentWorker = loadResult(forceLoad)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ result: L ->
                isLoading.set(false)
                currentInfo = result
                currentNextPage = result.nextPage
                handleResult(result)
            }, { throwable: Throwable? ->
                showError(ErrorInfo(throwable!!, errorUserAction, "Start loading: $url", serviceId))
            })
    }

    /**
     * Implement the logic to load more items.
     *
     * You can use the default implementations
     * from [org.schabi.newpipe.util.ExtractorHelper].
     *
     * @return Rx [Single] containing the [ListExtractor.InfoItemsPage]
     */
    protected abstract fun loadMoreItemsLogic(): Single<InfoItemsPage<I>>

    override fun loadMoreItems() {
        isLoading.set(true)
        currentWorker?.dispose()
        forbidDownwardFocusScroll()

        currentWorker = loadMoreItemsLogic()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doFinally { this.allowDownwardFocusScroll() }
            .subscribe({ infoItemsPage: InfoItemsPage<I> ->
                isLoading.set(false)
                handleNextItems(infoItemsPage)
            }, { throwable: Throwable ->
                dynamicallyShowErrorPanelOrSnackbar(ErrorInfo(throwable, errorUserAction, "Loading more items: $url", serviceId))
            })
    }

    private fun forbidDownwardFocusScroll() {
        if (itemsList is NewPipeRecyclerView) {
            (itemsList as NewPipeRecyclerView).setFocusScrollAllowed(false)
        }
    }

    private fun allowDownwardFocusScroll() {
        if (itemsList is NewPipeRecyclerView) {
            (itemsList as NewPipeRecyclerView).setFocusScrollAllowed(true)
        }
    }

    override fun handleNextItems(result: InfoItemsPage<I>) {
        super.handleNextItems(result)

        currentNextPage = result.nextPage
        infoListAdapter!!.addInfoItemList(result.items)

        showListFooter(hasMoreItems())

        if (result.errors.isNotEmpty()) {
            dynamicallyShowErrorPanelOrSnackbar(ErrorInfo(result.errors, errorUserAction, "Get next items of: $url", serviceId))
        }
    }

    override fun hasMoreItems(): Boolean {
        return Page.isValid(currentNextPage)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    ////////////////////////////////////////////////////////////////////////// */
    override fun handleResult(result: L) {
        super.handleResult(result)

        name = result.name
        setTitle(name?:"")

        if (infoListAdapter!!.itemsList.isEmpty()) {
            when {
                result.relatedItems.isNotEmpty() -> {
                    infoListAdapter!!.addInfoItemList(result.relatedItems)
                    showListFooter(hasMoreItems())
                }
                hasMoreItems() -> {
                    loadMoreItems()
                }
                else -> {
                    infoListAdapter!!.clearStreamItemList()
                    showEmptyState()
                }
            }
        }

        if (result.errors.isNotEmpty()) {
            val errors: MutableList<Throwable> = ArrayList(result.errors)
            // handling ContentNotSupportedException not to show the error but an appropriate string
            // so that crashes won't be sent uselessly and the user will understand what happened
            errors.removeIf { obj: Throwable? -> ContentNotSupportedException::class.java.isInstance(obj) }

            if (errors.isNotEmpty()) {
                dynamicallyShowErrorPanelOrSnackbar(ErrorInfo(result.errors, errorUserAction, "Start loading: $url", serviceId))
            }
        }
    }

    override fun showEmptyState() {
        // show "no streams" for SoundCloud; otherwise "no videos"
        // showing "no live streams" is handled in KioskFragment
        if (emptyStateView != null) {
            if (currentInfo!!.service === ServiceList.SoundCloud) setEmptyStateMessage(R.string.no_streams)
            else setEmptyStateMessage(R.string.no_videos)
        }
        super.showEmptyState()
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    ////////////////////////////////////////////////////////////////////////// */
    protected fun setInitialData(sid: Int, u: String?, title: String?) {
        this.serviceId = sid
        this.url = u
        this.name = if (!TextUtils.isEmpty(title)) title else ""
    }

    private fun dynamicallyShowErrorPanelOrSnackbar(errorInfo: ErrorInfo) {
        if (infoListAdapter!!.itemCount == 0) {
            // show error panel only if no items already visible
            showError(errorInfo)
        } else {
            isLoading.set(false)
            showSnackBarError(errorInfo)
        }
    }
}
