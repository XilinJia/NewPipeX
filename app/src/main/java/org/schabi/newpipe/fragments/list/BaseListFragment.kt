package org.schabi.newpipe.fragments.list

import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ErrorUtil.Companion.showUiErrorSnackbar
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.fragments.BaseStateFragment
import org.schabi.newpipe.fragments.OnScrollBelowItemsListener
import org.schabi.newpipe.info_list.InfoListAdapter
import org.schabi.newpipe.info_list.ItemViewMode
import org.schabi.newpipe.info_list.dialog.InfoItemDialog
import org.schabi.newpipe.info_list.dialog.InfoItemDialog.Builder.Companion.reportErrorDuringInitialization
import org.schabi.newpipe.ktx.animate
import org.schabi.newpipe.ktx.animateHideRecyclerViewAllowingScrolling
import org.schabi.newpipe.util.*
import org.schabi.newpipe.util.NavigationHelper.openChannelFragment
import org.schabi.newpipe.util.NavigationHelper.openPlaylistFragment
import org.schabi.newpipe.util.NavigationHelper.openVideoDetailFragment
import org.schabi.newpipe.util.StateSaver.WriteRead
import org.schabi.newpipe.util.StateSaver.onDestroy
import org.schabi.newpipe.util.StateSaver.tryToRestore
import org.schabi.newpipe.util.StateSaver.tryToSave
import org.schabi.newpipe.util.ThemeHelper.getItemViewMode
import org.schabi.newpipe.views.SuperScrollLayoutManager
import java.util.*
import java.util.function.Supplier

abstract class BaseListFragment<I, N>
    : BaseStateFragment<I>(), ListViewContract<I, N>, WriteRead, OnSharedPreferenceChangeListener {
    @JvmField
    protected var savedState: org.schabi.newpipe.util.SavedState? = null

    private var useDefaultStateSaving = true
    private var updateFlags = 0

    /*//////////////////////////////////////////////////////////////////////////
    // Views
    ////////////////////////////////////////////////////////////////////////// */
    protected lateinit var itemsList: RecyclerView

    @JvmField
    protected var infoListAdapter: InfoListAdapter? = null
    private var focusedPosition = -1

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle
    ////////////////////////////////////////////////////////////////////////// */
    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (infoListAdapter == null) infoListAdapter = InfoListAdapter(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        PreferenceManager.getDefaultSharedPreferences(requireActivity())
            .registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (useDefaultStateSaving) onDestroy(savedState)

        PreferenceManager.getDefaultSharedPreferences(requireActivity())
            .unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onResume() {
        super.onResume()

        if (updateFlags != 0) {
            if ((updateFlags and LIST_MODE_UPDATE_FLAG) != 0) refreshItemViewMode()
            updateFlags = 0
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // State Saving
    ////////////////////////////////////////////////////////////////////////// */
    /**
     * If the default implementation of [StateSaver.WriteRead] should be used.
     *
     * @param useDefaultStateSaving Whether the default implementation should be used
     * @see StateSaver
     */
    fun setUseDefaultStateSaving(useDefaultStateSaving: Boolean) {
        this.useDefaultStateSaving = useDefaultStateSaving
    }

    override fun generateSuffix(): String {
        // Naive solution, but it's good for now (the items don't change)
        return ".${infoListAdapter!!.itemsList.size}.list"
    }

    private fun getFocusedPosition(): Int {
        try {
            val focusedItem = itemsList.focusedChild
            val itemHolder = itemsList.findContainingViewHolder(focusedItem)
            return itemHolder!!.bindingAdapterPosition
        } catch (e: NullPointerException) {
            return -1
        }
    }

    override fun writeTo(objectsToSave: Queue<Any>?) {
        if (!useDefaultStateSaving) return

        objectsToSave!!.add(infoListAdapter!!.itemsList)
        objectsToSave.add(getFocusedPosition())
    }

    @Throws(Exception::class)
    override fun readFrom(savedObjects: Queue<Any>) {
        if (!useDefaultStateSaving) return

        infoListAdapter!!.itemsList.clear()
        infoListAdapter!!.itemsList.addAll(savedObjects.poll() as List<InfoItem>)
        restoreFocus(savedObjects.poll() as Int)
    }

    private fun restoreFocus(position: Int?) {
        if (position == null || position < 0) return

        itemsList.post {
            val focusedHolder = itemsList.findViewHolderForAdapterPosition(position)
            focusedHolder?.itemView?.requestFocus()
        }
    }

    override fun onSaveInstanceState(bundle: Bundle) {
        super.onSaveInstanceState(bundle)
        if (useDefaultStateSaving) savedState = tryToSave(requireActivity().isChangingConfigurations, savedState, bundle, this)
    }

    override fun onRestoreInstanceState(bundle: Bundle) {
        super.onRestoreInstanceState(bundle)
        if (useDefaultStateSaving) savedState = tryToRestore(bundle, this)
    }

    override fun onStop() {
        focusedPosition = getFocusedPosition()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        restoreFocus(focusedPosition)
    }

    protected open val listHeaderSupplier: Supplier<View>?
        /*//////////////////////////////////////////////////////////////////////////
    // Init
    ////////////////////////////////////////////////////////////////////////// */
        get() = null

    protected val listLayoutManager: RecyclerView.LayoutManager
        get() = SuperScrollLayoutManager(activity)

    protected val gridLayoutManager: RecyclerView.LayoutManager
        get() {
            val resources = requireActivity().resources
            var width = resources.getDimensionPixelSize(R.dimen.video_item_grid_thumbnail_image_width)
            width = (width + (24 * resources.displayMetrics.density)).toInt()
            val spanCount = Math.floorDiv(resources.displayMetrics.widthPixels, width)
            val lm = GridLayoutManager(activity, spanCount)
            lm.spanSizeLookup = infoListAdapter!!.getSpanSizeLookup(spanCount)
            return lm
        }

    /**
     * Updates the item view mode based on user preference.
     */
    private fun refreshItemViewMode() {
        val itemViewMode = itemViewMode
        itemsList.layoutManager = if ((itemViewMode == ItemViewMode.GRID)) gridLayoutManager else listLayoutManager
        infoListAdapter!!.setItemViewMode(itemViewMode)
        infoListAdapter!!.notifyDataSetChanged()
    }

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)

        itemsList = rootView.findViewById(R.id.items_list)
        refreshItemViewMode()

        val listHeaderSupplier = listHeaderSupplier
        if (listHeaderSupplier != null) infoListAdapter!!.setHeaderSupplier(listHeaderSupplier)

        itemsList.setAdapter(infoListAdapter)
    }

    protected open fun onItemSelected(selectedItem: InfoItem) {

            Logd(TAG, "onItemSelected() called with: selectedItem = [$selectedItem]")

    }

    @OptIn(UnstableApi::class)
    override fun initListeners() {
        super.initListeners()
        infoListAdapter!!.setOnStreamSelectedListener(object : OnClickGesture<StreamInfoItem> {
            override fun selected(selectedItem: StreamInfoItem) {
                onStreamSelected(selectedItem)
            }
            override fun held(selectedItem: StreamInfoItem) {
                showInfoItemDialog(selectedItem)
            }
        })

        infoListAdapter!!.setOnChannelSelectedListener { selectedItem: ChannelInfoItem ->
            try {
                onItemSelected(selectedItem)
                openChannelFragment(fM!!, selectedItem.serviceId, selectedItem.url, selectedItem.name)
            } catch (e: Exception) {
                showUiErrorSnackbar(this, "Opening channel fragment", e)
            }
        }

        infoListAdapter!!.setOnPlaylistSelectedListener { selectedItem: PlaylistInfoItem ->
            try {
                onItemSelected(selectedItem)
                openPlaylistFragment(fM!!, selectedItem.serviceId, selectedItem.url, selectedItem.name)
            } catch (e: Exception) {
                showUiErrorSnackbar(this, "Opening playlist fragment", e)
            }
        }

        infoListAdapter!!.setOnCommentsSelectedListener { selectedItem: InfoItem -> this.onItemSelected(selectedItem) }

        // Ensure that there is always a scroll listener (e.g. when rotating the device)
        useNormalItemListScrollListener()
    }

    /**
     * Removes all listeners and adds the normal scroll listener to the [.itemsList].
     */
    protected fun useNormalItemListScrollListener() {
        Logd(TAG, "useNormalItemListScrollListener called")

        itemsList.clearOnScrollListeners()
        itemsList.addOnScrollListener(DefaultItemListOnScrolledDownListener())
    }

    /**
     * Removes all listeners and adds the initial scroll listener to the [.itemsList].
     * <br></br>
     * Which tries to load more items when not enough are in the view (not scrollable)
     * and more are available.
     * <br></br>
     * Note: This method only works because "This callback will also be called if visible
     * item range changes after a layout calculation. In that case, dx and dy will be 0."
     * - which might be unexpected because no actual scrolling occurs...
     * <br></br>
     * This listener will be replaced by DefaultItemListOnScrolledDownListener when
     *
     *  * the view was actually scrolled
     *  * the view is scrollable
     *  * no more items can be loaded
     *
     */
    protected fun useInitialItemListLoadScrollListener() {
        Logd(TAG, "useInitialItemListLoadScrollListener called")

        itemsList.clearOnScrollListeners()
        itemsList.addOnScrollListener(object : DefaultItemListOnScrolledDownListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy != 0) {
                    log("Vertical scroll occurred")
                    useNormalItemListScrollListener()
                    return
                }
                if (isLoading.get()) {
                    log("Still loading data -> Skipping")
                    return
                }
                if (!hasMoreItems()) {
                    log("No more items to load")
                    useNormalItemListScrollListener()
                    return
                }
                if (itemsList.canScrollVertically(1) || itemsList.canScrollVertically(-1)) {
                    log("View is scrollable")
                    useNormalItemListScrollListener()
                    return
                }

                log("Loading more data")
                loadMoreItems()
            }

            private fun log(msg: String) {
                Logd(TAG, "initItemListLoadScrollListener - $msg")
            }
        })
    }

    internal open inner class DefaultItemListOnScrolledDownListener : OnScrollBelowItemsListener() {
        override fun onScrolledDown(recyclerView: RecyclerView?) {
            onScrollToBottom()
        }
    }

    @OptIn(UnstableApi::class)
    private fun onStreamSelected(selectedItem: StreamInfoItem) {
        Logd(TAG, "onStreamSelected: ${selectedItem.name}")
        onItemSelected(selectedItem)
        Logd(TAG, "onItemClick: ${selectedItem.serviceId}, ${selectedItem.url}")
        openVideoDetailFragment(requireContext(), fM!!, selectedItem.serviceId, selectedItem.url, selectedItem.name, null, false)
    }

    protected fun onScrollToBottom() {
        if (hasMoreItems() && !isLoading.get()) loadMoreItems()
    }

    protected open fun showInfoItemDialog(item: StreamInfoItem?) {
        try {
            InfoItemDialog.Builder(requireActivity(), requireContext(), this, item!!).create().show()
        } catch (e: IllegalArgumentException) {
            reportErrorDuringInitialization(e, item!!)
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Menu
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        Logd(TAG, "onCreateOptionsMenu() called with: menu = [$menu], inflater = [$inflater]")
        super.onCreateOptionsMenu(menu, inflater)
        val supportActionBar = activity?.supportActionBar
        supportActionBar?.setDisplayShowTitleEnabled(true)
        supportActionBar?.setDisplayHomeAsUpEnabled(!useAsFrontPage)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Load and handle
    ////////////////////////////////////////////////////////////////////////// */
    override fun startLoading(forceLoad: Boolean) {
        useInitialItemListLoadScrollListener()
        super.startLoading(forceLoad)
    }

    protected abstract fun loadMoreItems()

    protected abstract fun hasMoreItems(): Boolean

    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    ////////////////////////////////////////////////////////////////////////// */
    override fun showLoading() {
        super.showLoading()
        itemsList.animateHideRecyclerViewAllowingScrolling()
    }

    override fun hideLoading() {
        super.hideLoading()
        itemsList.animate(true, 300)
    }

    override fun showEmptyState() {
        super.showEmptyState()
        showListFooter(false)
        itemsList.animateHideRecyclerViewAllowingScrolling()
    }

    override fun showListFooter(show: Boolean) {
        itemsList.post { infoListAdapter?.showFooter(show) }
    }

    override fun handleNextItems(result: N) {
        isLoading.set(false)
    }

    override fun handleError() {
        super.handleError()
        showListFooter(false)
        itemsList.animateHideRecyclerViewAllowingScrolling()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (getString(R.string.list_view_mode_key) == key) updateFlags = updateFlags or LIST_MODE_UPDATE_FLAG
    }

    protected open val itemViewMode: ItemViewMode
        /**
         * Returns preferred item view mode.
         * @return ItemViewMode
         */
        get() = getItemViewMode(requireContext())

    companion object {
        private const val LIST_MODE_UPDATE_FLAG = 0x32
    }
}
