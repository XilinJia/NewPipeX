package org.schabi.newpipe.ui.list

import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import android.view.*
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.PignateFooterBinding
import org.schabi.newpipe.error.ErrorUtil.Companion.showUiErrorSnackbar
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.InfoItem.InfoType
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.ui.fragments.BaseStateFragment
import org.schabi.newpipe.ui.OnScrollBelowItemsListener
import org.schabi.newpipe.ui.info_list.InfoItemBuilder
import org.schabi.newpipe.ui.info_list.ItemViewMode
import org.schabi.newpipe.ui.info_list.dialog.InfoItemDialog
import org.schabi.newpipe.ui.info_list.dialog.InfoItemDialog.Builder.Companion.reportErrorDuringInitialization
import org.schabi.newpipe.ui.info_list.holder.*
import org.schabi.newpipe.ui.ktx.animate
import org.schabi.newpipe.ui.ktx.animateHideRecyclerViewAllowingScrolling
import org.schabi.newpipe.ui.local.history.HistoryRecordManager
import org.schabi.newpipe.util.*
import org.schabi.newpipe.util.NavigationHelper.openChannelFragment
import org.schabi.newpipe.util.NavigationHelper.openPlaylistFragment
import org.schabi.newpipe.util.NavigationHelper.openVideoDetailFragment
import org.schabi.newpipe.util.StateSaver.WriteRead
import org.schabi.newpipe.util.StateSaver.onDestroy
import org.schabi.newpipe.util.StateSaver.tryToRestore
import org.schabi.newpipe.util.StateSaver.tryToSave
import org.schabi.newpipe.util.ThemeHelper.getItemViewMode
import org.schabi.newpipe.ui.views.SuperScrollLayoutManager
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

    private val listLayoutManager: RecyclerView.LayoutManager
        get() = SuperScrollLayoutManager(activity)

    private val gridLayoutManager: RecyclerView.LayoutManager
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
        itemsList.adapter = infoListAdapter
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
    private fun useInitialItemListLoadScrollListener() {
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

    class InfoListAdapter(context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val layoutInflater: LayoutInflater = LayoutInflater.from(context)
        private val infoItemBuilder = InfoItemBuilder(context)
        private val infoItemList: MutableList<InfoItem> = ArrayList()
        private val recordManager = HistoryRecordManager(context)

        private var useMiniVariant = false
        private var showFooter = false

        private var itemMode = ItemViewMode.LIST

        private var headerSupplier: Supplier<View>? = null

        fun setOnStreamSelectedListener(listener: OnClickGesture<StreamInfoItem>?) {
            infoItemBuilder.onStreamSelectedListener = listener
        }

        fun setOnChannelSelectedListener(listener: OnClickGesture<ChannelInfoItem>?) {
            infoItemBuilder.onChannelSelectedListener = listener
        }

        fun setOnPlaylistSelectedListener(listener: OnClickGesture<PlaylistInfoItem>?) {
            infoItemBuilder.onPlaylistSelectedListener = listener
        }

        fun setOnCommentsSelectedListener(listener: OnClickGesture<CommentsInfoItem>?) {
            infoItemBuilder.onCommentsSelectedListener = listener
        }

        fun setUseMiniVariant(useMiniVariant: Boolean) {
            this.useMiniVariant = useMiniVariant
        }

        fun setItemViewMode(itemViewMode: ItemViewMode) {
            this.itemMode = itemViewMode
        }

        fun addInfoItemList(data: List<InfoItem>?) {
            if (data == null) return
            Logd(TAG, "addInfoItemList() before > infoItemList.size() = ${infoItemList.size}, data.size() = ${data.size}")

            val offsetStart = sizeConsideringHeaderOffset()
            infoItemList.addAll(data)
            Logd(TAG, "addInfoItemList() after > offsetStart = $offsetStart, infoItemList.size() = ${infoItemList.size}, hasHeader = ${hasHeader()}, showFooter = $showFooter")
            notifyItemRangeInserted(offsetStart, data.size)

            if (showFooter) {
                val footerNow = sizeConsideringHeaderOffset()
                notifyItemMoved(offsetStart, footerNow)
                Logd(TAG, "addInfoItemList() footer from $offsetStart to $footerNow")
            }
        }

        fun clearStreamItemList() {
            if (infoItemList.isEmpty()) return
            infoItemList.clear()
            notifyDataSetChanged()
        }

        fun setHeaderSupplier(headerSupplier: Supplier<View>?) {
            val changed = headerSupplier !== this.headerSupplier
            this.headerSupplier = headerSupplier
            if (changed) notifyDataSetChanged()
        }

        private fun hasHeader(): Boolean {
            return this.headerSupplier != null
        }

        fun showFooter(show: Boolean) {
            Logd(TAG, "showFooter() called with: show = [$show]")
            if (show == showFooter) return
            showFooter = show
            if (show) notifyItemInserted(sizeConsideringHeaderOffset())
            else notifyItemRemoved(sizeConsideringHeaderOffset())
        }

        private fun sizeConsideringHeaderOffset(): Int {
            val i = infoItemList.size + (if (hasHeader()) 1 else 0)
            Logd(TAG, "sizeConsideringHeaderOffset() called → $i")
            return i
        }

        val itemsList: MutableList<InfoItem>
            get() = infoItemList

        override fun getItemCount(): Int {
            var count = infoItemList.size
            if (hasHeader()) count++
            if (showFooter) count++

            Logd(TAG, "getItemCount() called with: count = $count, infoItemList.size() = ${infoItemList.size}, hasHeader = ${hasHeader()}, showFooter = $showFooter")
            return count
        }

        override fun getItemViewType(position: Int): Int {
            var position = position
            Logd(TAG, "getItemViewType() called with: position = [$position]")

            when {
                hasHeader() && position == 0 -> return HEADER_TYPE
                hasHeader() -> position--
            }
            if (position == infoItemList.size && showFooter) return FOOTER_TYPE
            val item = infoItemList[position]
            return when (item.infoType) {
                InfoType.STREAM -> {
                    when {
                        itemMode == ItemViewMode.CARD -> CARD_STREAM_HOLDER_TYPE
                        itemMode == ItemViewMode.GRID -> GRID_STREAM_HOLDER_TYPE
                        useMiniVariant -> MINI_STREAM_HOLDER_TYPE
                        else -> STREAM_HOLDER_TYPE
                    }
                }
                InfoType.CHANNEL -> {
                    when {
                        itemMode == ItemViewMode.CARD -> CARD_CHANNEL_HOLDER_TYPE
                        itemMode == ItemViewMode.GRID -> GRID_CHANNEL_HOLDER_TYPE
                        useMiniVariant -> MINI_CHANNEL_HOLDER_TYPE
                        else -> CHANNEL_HOLDER_TYPE
                    }
                }
                InfoType.PLAYLIST -> {
                    when {
                        itemMode == ItemViewMode.CARD -> CARD_PLAYLIST_HOLDER_TYPE
                        itemMode == ItemViewMode.GRID -> GRID_PLAYLIST_HOLDER_TYPE
                        useMiniVariant -> MINI_PLAYLIST_HOLDER_TYPE
                        else -> PLAYLIST_HOLDER_TYPE
                    }
                }
                InfoType.COMMENT -> COMMENT_HOLDER_TYPE
                else -> -1
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            Logd(TAG, "onCreateViewHolder() called with: parent = [$parent], type = [$type]")

            return when (type) {
                HEADER_TYPE -> HFHolder(headerSupplier!!.get())
                FOOTER_TYPE -> HFHolder(PignateFooterBinding.inflate(layoutInflater, parent, false).root)
                MINI_STREAM_HOLDER_TYPE -> StreamMiniInfoItemHolder(infoItemBuilder, parent)
                STREAM_HOLDER_TYPE -> StreamInfoItemHolder(infoItemBuilder, parent)
                GRID_STREAM_HOLDER_TYPE -> StreamGridInfoItemHolder(infoItemBuilder, parent)
                CARD_STREAM_HOLDER_TYPE -> StreamCardInfoItemHolder(infoItemBuilder, parent)
                MINI_CHANNEL_HOLDER_TYPE -> ChannelMiniInfoItemHolder(infoItemBuilder, parent)
                CHANNEL_HOLDER_TYPE -> ChannelInfoItemHolder(infoItemBuilder, parent)
                CARD_CHANNEL_HOLDER_TYPE -> ChannelCardInfoItemHolder(infoItemBuilder, parent)
                GRID_CHANNEL_HOLDER_TYPE -> ChannelGridInfoItemHolder(infoItemBuilder, parent)
                MINI_PLAYLIST_HOLDER_TYPE -> PlaylistMiniInfoItemHolder(infoItemBuilder, parent)
                PLAYLIST_HOLDER_TYPE -> PlaylistInfoItemHolder(infoItemBuilder, parent)
                GRID_PLAYLIST_HOLDER_TYPE -> PlaylistGridInfoItemHolder(infoItemBuilder, parent)
                CARD_PLAYLIST_HOLDER_TYPE -> PlaylistCardInfoItemHolder(infoItemBuilder, parent)
                COMMENT_HOLDER_TYPE -> CommentInfoItemHolder(infoItemBuilder, parent)
                else -> FallbackViewHolder(View(parent.context))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            Logd(TAG, "onBindViewHolder() called with: holder = [${holder.javaClass.simpleName}], position = [$position]")
            // If header is present, offset the items by -1
            if (holder is InfoItemHolder) holder.updateFromItem(infoItemList[if (hasHeader()) position - 1 else position], recordManager)
        }

        fun getSpanSizeLookup(spanCount: Int): SpanSizeLookup {
            return object : SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val type = getItemViewType(position)
                    return if (type == HEADER_TYPE || type == FOOTER_TYPE) spanCount else 1
                }
            }
        }

        internal class HFHolder(v: View) : RecyclerView.ViewHolder(v)

        class StreamGridInfoItemHolder(infoItemBuilder: InfoItemBuilder?, parent: ViewGroup?)
            : StreamInfoItemHolder(infoItemBuilder, R.layout.list_stream_grid_item, parent)

        class StreamCardInfoItemHolder(infoItemBuilder: InfoItemBuilder?, parent: ViewGroup?)
            : StreamInfoItemHolder(infoItemBuilder, R.layout.list_stream_card_item, parent)

        class PlaylistGridInfoItemHolder(infoItemBuilder: InfoItemBuilder?, parent: ViewGroup?)
            : PlaylistMiniInfoItemHolder(infoItemBuilder, R.layout.list_playlist_grid_item, parent)

        class PlaylistCardInfoItemHolder(infoItemBuilder: InfoItemBuilder?, parent: ViewGroup?)
            : PlaylistMiniInfoItemHolder(infoItemBuilder, R.layout.list_playlist_card_item, parent)

        class ChannelCardInfoItemHolder(infoItemBuilder: InfoItemBuilder, parent: ViewGroup?)
            : ChannelMiniInfoItemHolder(infoItemBuilder, R.layout.list_channel_card_item, parent) {
            override fun getDescriptionMaxLineCount(content: String?): Int {
                // Based on `list_channel_card_item` left side content (thumbnail 100dp
                // + additional details), Right side description can grow up to 8 lines.
                return 8
            }
        }

        class ChannelGridInfoItemHolder(infoItemBuilder: InfoItemBuilder, parent: ViewGroup?)
            : ChannelMiniInfoItemHolder(infoItemBuilder, R.layout.list_channel_grid_item, parent)

        companion object {
            private val TAG: String = InfoListAdapter::class.java.simpleName

            private const val HEADER_TYPE = 0
            private const val FOOTER_TYPE = 1

            private const val MINI_STREAM_HOLDER_TYPE = 0x100
            private const val STREAM_HOLDER_TYPE = 0x101
            private const val GRID_STREAM_HOLDER_TYPE = 0x102
            private const val CARD_STREAM_HOLDER_TYPE = 0x103
            private const val MINI_CHANNEL_HOLDER_TYPE = 0x200
            private const val CHANNEL_HOLDER_TYPE = 0x201
            private const val GRID_CHANNEL_HOLDER_TYPE = 0x202
            private const val CARD_CHANNEL_HOLDER_TYPE = 0x203
            private const val MINI_PLAYLIST_HOLDER_TYPE = 0x300
            private const val PLAYLIST_HOLDER_TYPE = 0x301
            private const val GRID_PLAYLIST_HOLDER_TYPE = 0x302
            private const val CARD_PLAYLIST_HOLDER_TYPE = 0x303
            private const val COMMENT_HOLDER_TYPE = 0x400
        }
    }

    companion object {
        private const val LIST_MODE_UPDATE_FLAG = 0x32
    }
}
