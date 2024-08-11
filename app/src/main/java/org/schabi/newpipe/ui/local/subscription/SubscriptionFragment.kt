package org.schabi.newpipe.ui.local.subscription

//import org.schabi.newpipe.local.subscription.services.SubscriptionsImportService.KEY_MODE
//import org.schabi.newpipe.local.subscription.services.SubscriptionsImportService.KEY_VALUE
//import org.schabi.newpipe.local.subscription.services.SubscriptionsImportService.PREVIOUS_EXPORT_MODE
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.*
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ItemTouchHelper.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xwray.groupie.*
import com.xwray.groupie.viewbinding.BindableItem
import com.xwray.groupie.viewbinding.GroupieViewHolder
import icepick.Icepick
import icepick.State
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.feed.model.FeedGroupEntity.Companion.GROUP_ALL_ID
import org.schabi.newpipe.databinding.*
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.ui.ktx.animate
import org.schabi.newpipe.giga.io.NoFileManagerSafeGuard
import org.schabi.newpipe.giga.io.StoredFileHelper
import org.schabi.newpipe.ui.fragments.BaseStateFragment
import org.schabi.newpipe.ui.local.subscription.SubscriptionViewModel.SubscriptionState
import org.schabi.newpipe.ui.local.subscription.dialog.FeedGroupDialog
import org.schabi.newpipe.ui.local.subscription.dialog.FeedGroupReorderDialogViewModel
import org.schabi.newpipe.ui.local.subscription.dialog.FeedGroupReorderDialogViewModel.DialogEvent.ProcessingEvent
import org.schabi.newpipe.ui.local.subscription.dialog.FeedGroupReorderDialogViewModel.DialogEvent.SuccessEvent
import org.schabi.newpipe.ui.local.subscription.dialog.ImportConfirmationDialog
import org.schabi.newpipe.ui.local.subscription.item.ChannelItem
import org.schabi.newpipe.ui.local.subscription.item.FeedGroupCardGridItem
import org.schabi.newpipe.ui.local.subscription.item.FeedGroupCardItem
import org.schabi.newpipe.ui.local.subscription.item.ImportSubscriptionsHintPlaceholderItem
import org.schabi.newpipe.ui.local.subscription.services.SubscriptionsExportService
import org.schabi.newpipe.ui.local.subscription.services.SubscriptionsImportService
import org.schabi.newpipe.ui.local.subscription.services.SubscriptionsImportService.Companion.KEY_MODE
import org.schabi.newpipe.ui.local.subscription.services.SubscriptionsImportService.Companion.KEY_VALUE
import org.schabi.newpipe.ui.local.subscription.services.SubscriptionsImportService.Companion.PREVIOUS_EXPORT_MODE
import org.schabi.newpipe.util.*
import org.schabi.newpipe.util.ThemeHelper.getGridSpanCount
import org.schabi.newpipe.util.ThemeHelper.getGridSpanCountChannels
import org.schabi.newpipe.util.external_communication.ShareUtils
import java.text.SimpleDateFormat
import java.util.*

@UnstableApi class SubscriptionFragment : BaseStateFragment<SubscriptionState>() {
    private var _binding: FragmentSubscriptionBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: SubscriptionViewModel
    private lateinit var subscriptionManager: SubscriptionManager
    private val disposables: CompositeDisposable = CompositeDisposable()

    private val groupAdapter = GroupAdapter<GroupieViewHolder<FeedItemCarouselBinding>>()
    private lateinit var carouselAdapter: GroupAdapter<GroupieViewHolder<FeedItemCarouselBinding>>
    private lateinit var feedGroupsCarousel: FeedGroupCarouselItem
    private lateinit var feedGroupsSortMenuItem: GroupsHeader
    private val subscriptionsSection = Section()

    private val requestExportLauncher = registerForActivityResult(StartActivityForResult(), this::requestExportResult)
    private val requestImportLauncher = registerForActivityResult(StartActivityForResult(), this::requestImportResult)

    @State
    @JvmField
    var itemsListState: Parcelable? = null

    @State
    @JvmField
    var feedGroupsCarouselState: Parcelable? = null

    lateinit var fm: FragmentManager

    init {
        setHasOptionsMenu(true)
    }

    // /////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle
    // /////////////////////////////////////////////////////////////////////////

    override fun onAttach(context: Context) {
        super.onAttach(context)
        subscriptionManager = SubscriptionManager(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        fm = requireActivity().supportFragmentManager
        return inflater.inflate(R.layout.fragment_subscription, container, false)
    }

    override fun onPause() {
        super.onPause()
        itemsListState = binding.itemsList.layoutManager?.onSaveInstanceState()
        feedGroupsCarouselState = feedGroupsCarousel.onSaveInstanceState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        disposables.dispose()
    }

    // ////////////////////////////////////////////////////////////////////////
    // Menu
    // ////////////////////////////////////////////////////////////////////////

    @SuppressLint("UseRequireInsteadOfGet")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        activity!!.supportActionBar?.setDisplayShowTitleEnabled(true)
        activity!!.supportActionBar?.setTitle(R.string.tab_subscriptions)
        buildImportExportMenu(menu)
    }

    private fun buildImportExportMenu(menu: Menu) {
        // -- Import --
        val importSubMenu = menu.addSubMenu(R.string.import_from)

        addMenuItemToSubmenu(importSubMenu, R.string.previous_export) { onImportPreviousSelected() }.setIcon(R.drawable.ic_backup)

        for (service in ServiceList.all()) {
            val subscriptionExtractor = service.subscriptionExtractor ?: continue

            val supportedSources = subscriptionExtractor.supportedSources
            if (supportedSources.isEmpty()) continue

            addMenuItemToSubmenu(importSubMenu, service.serviceInfo.name) {
                onImportFromServiceSelected(service.serviceId)
            }.setIcon(ServiceHelper.getIcon(service.serviceId))
        }

        // -- Export --
        val exportSubMenu = menu.addSubMenu(R.string.export_to)
        addMenuItemToSubmenu(exportSubMenu, R.string.file) { onExportSelected() }.setIcon(R.drawable.ic_save)
    }

    private fun addMenuItemToSubmenu(subMenu: SubMenu, @StringRes title: Int, onClick: Runnable): MenuItem {
        return setClickListenerToMenuItem(subMenu.add(title), onClick)
    }

    private fun addMenuItemToSubmenu(subMenu: SubMenu, title: String, onClick: Runnable): MenuItem {
        return setClickListenerToMenuItem(subMenu.add(title), onClick)
    }

    private fun setClickListenerToMenuItem(menuItem: MenuItem, onClick: Runnable): MenuItem {
        menuItem.setOnMenuItemClickListener { onClick.run(); true }
        return menuItem
    }

    @OptIn(UnstableApi::class) private fun onImportFromServiceSelected(serviceId: Int) {
        val fragmentManager = fm
        NavigationHelper.openSubscriptionsImportFragment(fragmentManager, serviceId)
    }

    private fun onImportPreviousSelected() {
        NoFileManagerSafeGuard.launchSafe(requestImportLauncher, StoredFileHelper.getPicker(requireContext(), JSON_MIME_TYPE), TAG, requireContext())
    }

    private fun onExportSelected() {
        val date = SimpleDateFormat("yyyyMMddHHmm", Locale.ENGLISH).format(Date())
        val exportName = "newpipe_subscriptions_$date.json"
        NoFileManagerSafeGuard.launchSafe(requestExportLauncher,
            StoredFileHelper.getNewPicker(requireActivity(), exportName, JSON_MIME_TYPE, null), TAG, requireContext())
    }

    private fun openReorderDialog() {
        FeedGroupReorderDialog().show(parentFragmentManager, null)
    }

    @SuppressLint("UseRequireInsteadOfGet")
    private fun requestExportResult(result: ActivityResult) {
        if (result.data != null && result.resultCode == Activity.RESULT_OK) {
            activity!!.startService(Intent(activity, SubscriptionsExportService::class.java)
                .putExtra(SubscriptionsExportService.KEY_FILE_PATH, result.data?.data))
        }
    }

    private fun requestImportResult(result: ActivityResult) {
        if (result.data != null && result.resultCode == Activity.RESULT_OK) {
            ImportConfirmationDialog.show(this,
                Intent(activity, SubscriptionsImportService::class.java)
                    .putExtra(KEY_MODE, PREVIOUS_EXPORT_MODE)
                    .putExtra(KEY_VALUE, result.data?.data),
            )
        }
    }

    // ////////////////////////////////////////////////////////////////////////
    // Fragment Views
    // ////////////////////////////////////////////////////////////////////////

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)
        _binding = FragmentSubscriptionBinding.bind(rootView)

        groupAdapter.spanCount = if (SubscriptionViewModel.shouldUseGridForSubscription(requireContext())) getGridSpanCountChannels(requireContext()) else 1
        binding.itemsList.layoutManager = GridLayoutManager(requireContext(),
            groupAdapter.spanCount).apply { spanSizeLookup = groupAdapter.spanSizeLookup }
        binding.itemsList.adapter = groupAdapter
        binding.itemsList.itemAnimator = null

        viewModel = ViewModelProvider(this)[SubscriptionViewModel::class.java]
        viewModel.stateLiveData.observe(viewLifecycleOwner) { it?.let(this::handleResult) }
        viewModel.feedGroupsLiveData.observe(viewLifecycleOwner) {
            it?.let { (groups, listViewMode) ->
                handleFeedGroups(groups, listViewMode)
            }
        }
        setupInitialLayout()
    }

    private fun setupInitialLayout() {
        Section().apply {
            carouselAdapter = GroupAdapter<GroupieViewHolder<FeedItemCarouselBinding>>()
            carouselAdapter.setOnItemClickListener { item, _ ->
                when (item) {
                    is FeedGroupCardItem -> NavigationHelper.openFeedFragment(fm, item.groupId, item.name)
                    is FeedGroupCardGridItem -> NavigationHelper.openFeedFragment(fm, item.groupId, item.name)
                    is FeedGroupAddNewItem -> FeedGroupDialog.newInstance().show(fm, null)
                    is FeedGroupAddNewGridItem -> FeedGroupDialog.newInstance().show(fm, null)
                }
            }
            carouselAdapter.setOnItemLongClickListener { item, _ ->
                if ((item is FeedGroupCardItem && item.groupId == GROUP_ALL_ID) || (item is FeedGroupCardGridItem && item.groupId == GROUP_ALL_ID))
                    return@setOnItemLongClickListener false
                when (item) {
                    is FeedGroupCardItem -> FeedGroupDialog.newInstance(item.groupId).show(fm, null)
                    is FeedGroupCardGridItem -> FeedGroupDialog.newInstance(item.groupId).show(fm, null)
                }
                return@setOnItemLongClickListener true
            }
            feedGroupsCarousel = FeedGroupCarouselItem(carouselAdapter = carouselAdapter, listViewMode = viewModel.getListViewMode())
            feedGroupsSortMenuItem = GroupsHeader(title = getString(R.string.feed_groups_header_title), onSortClicked = ::openReorderDialog,
                onToggleListViewModeClicked = ::toggleListViewMode, listViewMode = viewModel.getListViewMode())

            add(Section(feedGroupsSortMenuItem, listOf(feedGroupsCarousel)))
            groupAdapter.clear()
            groupAdapter.add(this)
        }
        subscriptionsSection.setPlaceholder(ImportSubscriptionsHintPlaceholderItem())
        subscriptionsSection.setHideWhenEmpty(true)
        groupAdapter.add(Section(Header(getString(R.string.tab_subscriptions)), listOf(subscriptionsSection)))
    }

    private fun toggleListViewMode() {
        viewModel.setListViewMode(!viewModel.getListViewMode())
    }

    private fun showLongTapDialog(selectedItem: ChannelInfoItem) {
        val commands = arrayOf(getString(R.string.share), getString(R.string.open_in_browser), getString(R.string.unsubscribe))
        val actions = DialogInterface.OnClickListener { _, i ->
            when (i) {
                0 -> ShareUtils.shareText(requireContext(), selectedItem.name, selectedItem.url, selectedItem.thumbnails)
                1 -> ShareUtils.openUrlInBrowser(requireContext(), selectedItem.url)
                2 -> deleteChannel(selectedItem)
            }
        }

        val dialogTitleBinding = DialogTitleBinding.inflate(LayoutInflater.from(requireContext()))
        dialogTitleBinding.root.isSelected = true
        dialogTitleBinding.itemTitleView.text = selectedItem.name
        dialogTitleBinding.itemAdditionalDetails.visibility = View.GONE

        AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitleBinding.root)
            .setItems(commands, actions)
            .show()
    }

    private fun deleteChannel(selectedItem: ChannelInfoItem) {
        disposables.add(subscriptionManager.deleteSubscription(selectedItem.serviceId, selectedItem.url).subscribe {
            Toast.makeText(requireContext(), getString(R.string.channel_unsubscribed), Toast.LENGTH_SHORT).show() })
    }

    override fun doInitialLoadLogic() = Unit

    override fun startLoading(forceLoad: Boolean) = Unit

    private val listenerChannelItem =
        object : OnClickGesture<ChannelInfoItem> {
            override fun selected(selectedItem: ChannelInfoItem) =
                NavigationHelper.openChannelFragment(fm, selectedItem.serviceId, selectedItem.url, selectedItem.name)
            override fun held(selectedItem: ChannelInfoItem) = showLongTapDialog(selectedItem)
        }

    override fun handleResult(result: SubscriptionState) {
        super.handleResult(result)

        when (result) {
            is SubscriptionState.LoadedState -> {
                result.subscriptions.forEach {
                    if (it is ChannelItem) {
                        it.gesturesListener = listenerChannelItem
                        it.itemVersion =
                            if (SubscriptionViewModel.shouldUseGridForSubscription(requireContext())) ChannelItem.ItemVersion.GRID
                            else ChannelItem.ItemVersion.MINI
                    }
                }
                subscriptionsSection.update(result.subscriptions)
                subscriptionsSection.setHideWhenEmpty(false)
                if (itemsListState != null) {
                    binding.itemsList.layoutManager?.onRestoreInstanceState(itemsListState)
                    itemsListState = null
                }
            }
            is SubscriptionState.ErrorState -> {
                result.error?.let {
                    showError(ErrorInfo(result.error, UserAction.SOMETHING_ELSE, "Subscriptions"))
                }
            }
        }
    }

    private fun handleFeedGroups(groups: List<Group>, listViewMode: Boolean) {
        if (feedGroupsCarouselState != null) {
            feedGroupsCarousel.onRestoreInstanceState(feedGroupsCarouselState)
            feedGroupsCarouselState = null
        }

        binding.itemsList.post {
            // since this part was posted to the next UI cycle, the fragment might have been
            // removed in the meantime
            if (context == null) return@post

            feedGroupsCarousel.listViewMode = listViewMode
            feedGroupsSortMenuItem.showSortButton = groups.size > 1
            feedGroupsSortMenuItem.listViewMode = listViewMode
            feedGroupsCarousel.notifyChanged(FeedGroupCarouselItem.PAYLOAD_UPDATE_LIST_VIEW_MODE)
            feedGroupsSortMenuItem.notifyChanged(GroupsHeader.PAYLOAD_UPDATE_ICONS)

            // update items here to prevent flickering
            carouselAdapter.apply {
                clear()
                if (listViewMode) {
                    add(FeedGroupAddNewItem())
                    add(FeedGroupCardItem(GROUP_ALL_ID, getString(R.string.all), FeedGroupIcon.WHATS_NEW))
                } else {
                    add(FeedGroupAddNewGridItem())
                    add(FeedGroupCardGridItem(GROUP_ALL_ID, getString(R.string.all), FeedGroupIcon.WHATS_NEW))
                }
                addAll(groups)
            }
        }
    }

    // /////////////////////////////////////////////////////////////////////////
    // Contract
    // /////////////////////////////////////////////////////////////////////////

    override fun showLoading() {
        super.showLoading()
        binding.itemsList.animate(false, 100)
    }

    override fun hideLoading() {
        super.hideLoading()
        binding.itemsList.animate(true, 200)
    }

    class Header(private val title: String) : BindableItem<SubscriptionHeaderBinding>() {
        override fun getLayout(): Int = R.layout.subscription_header
        override fun bind(viewBinding: SubscriptionHeaderBinding, position: Int) {
            viewBinding.root.text = title
        }
        override fun initializeViewBinding(view: View) = SubscriptionHeaderBinding.bind(view)
    }

    class GroupsHeader(private val title: String, private val onSortClicked: () -> Unit, private val onToggleListViewModeClicked: () -> Unit,
                       var showSortButton: Boolean = true, var listViewMode: Boolean = true) : BindableItem<SubscriptionGroupsHeaderBinding>() {

        companion object {
            const val PAYLOAD_UPDATE_ICONS = 1
        }

        override fun getLayout(): Int = R.layout.subscription_groups_header
        override fun bind(viewBinding: SubscriptionGroupsHeaderBinding, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_UPDATE_ICONS)) {
                updateIcons(viewBinding)
                return
            }
            super.bind(viewBinding, position, payloads)
        }
        override fun bind(viewBinding: SubscriptionGroupsHeaderBinding, position: Int) {
            viewBinding.headerTitle.text = title
            viewBinding.headerSort.setOnClickListener { onSortClicked() }
            viewBinding.headerToggleViewMode.setOnClickListener { onToggleListViewModeClicked() }
            updateIcons(viewBinding)
        }
        override fun initializeViewBinding(view: View) = SubscriptionGroupsHeaderBinding.bind(view)
        private fun updateIcons(viewBinding: SubscriptionGroupsHeaderBinding) {
            viewBinding.headerToggleViewMode.setImageResource(if (listViewMode) R.drawable.ic_apps else R.drawable.ic_list)
            viewBinding.headerSort.isVisible = showSortButton
        }
    }

    class FeedGroupCarouselItem(private val carouselAdapter: GroupAdapter<GroupieViewHolder<FeedItemCarouselBinding>>, var listViewMode: Boolean)
        : BindableItem<FeedItemCarouselBinding>() {

        companion object {
            const val PAYLOAD_UPDATE_LIST_VIEW_MODE = 2
        }

        private var carouselLayoutManager: LinearLayoutManager? = null
        private var listState: Parcelable? = null

        override fun getLayout() = R.layout.feed_item_carousel
        fun onSaveInstanceState(): Parcelable? {
            listState = carouselLayoutManager?.onSaveInstanceState()
            return listState
        }
        fun onRestoreInstanceState(state: Parcelable?) {
            carouselLayoutManager?.onRestoreInstanceState(state)
            listState = state
        }
        override fun initializeViewBinding(view: View): FeedItemCarouselBinding {
            val viewBinding = FeedItemCarouselBinding.bind(view)
            updateViewMode(viewBinding)
            return viewBinding
        }

        override fun bind(viewBinding: FeedItemCarouselBinding, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_UPDATE_LIST_VIEW_MODE)) {
                updateViewMode(viewBinding)
                return
            }
            super.bind(viewBinding, position, payloads)
        }

        override fun bind(viewBinding: FeedItemCarouselBinding, position: Int) {
            viewBinding.recyclerView.apply { adapter = carouselAdapter }
            carouselLayoutManager?.onRestoreInstanceState(listState)
        }

        override fun unbind(viewHolder: GroupieViewHolder<FeedItemCarouselBinding>) {
            super.unbind(viewHolder)
            listState = carouselLayoutManager?.onSaveInstanceState()
        }

        private fun updateViewMode(viewBinding: FeedItemCarouselBinding) {
            viewBinding.recyclerView.apply { adapter = carouselAdapter }
            val context = viewBinding.root.context
            carouselLayoutManager = if (listViewMode) LinearLayoutManager(context)
            else GridLayoutManager(context, getGridSpanCount(context, DeviceUtils.dpToPx(112, context)))
            viewBinding.recyclerView.apply {
                layoutManager = carouselLayoutManager
                adapter = carouselAdapter
            }
        }
    }

    class FeedGroupAddNewGridItem : BindableItem<FeedGroupAddNewGridItemBinding>() {
        override fun getLayout(): Int = R.layout.feed_group_add_new_grid_item
        override fun initializeViewBinding(view: View) = FeedGroupAddNewGridItemBinding.bind(view)
        override fun bind(viewBinding: FeedGroupAddNewGridItemBinding, position: Int) {
            // this is a static item, nothing to do here
        }
    }

    class FeedGroupAddNewItem : BindableItem<FeedGroupAddNewItemBinding>() {
        override fun getLayout(): Int = R.layout.feed_group_add_new_item
        override fun initializeViewBinding(view: View) = FeedGroupAddNewItemBinding.bind(view)
        override fun bind(viewBinding: FeedGroupAddNewItemBinding, position: Int) {
            // this is a static item, nothing to do here
        }
    }

    class FeedGroupReorderDialog : DialogFragment() {
        private var _binding: DialogFeedGroupReorderBinding? = null
        private val binding get() = _binding!!
        private lateinit var viewModel: FeedGroupReorderDialogViewModel

        @State
        @JvmField
        var groupOrderedIdList = ArrayList<Long>()
        private val groupAdapter = GroupieAdapter()
        private val itemTouchHelper = ItemTouchHelper(getItemTouchCallback())

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            Icepick.restoreInstanceState(this, savedInstanceState)
            setStyle(STYLE_NO_TITLE, ThemeHelper.getMinWidthDialogTheme(requireContext()))
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            return inflater.inflate(R.layout.dialog_feed_group_reorder, container)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            _binding = DialogFeedGroupReorderBinding.bind(view)
            viewModel = ViewModelProvider(this).get(FeedGroupReorderDialogViewModel::class.java)
            viewModel.groupsLiveData.observe(viewLifecycleOwner, androidx.lifecycle.Observer(::handleGroups))
            viewModel.dialogEventLiveData.observe(viewLifecycleOwner) {
                when (it) {
                    ProcessingEvent -> disableInput()
                    SuccessEvent -> dismiss()
                }
            }

            binding.feedGroupsList.layoutManager = LinearLayoutManager(requireContext())
            binding.feedGroupsList.adapter = groupAdapter
            itemTouchHelper.attachToRecyclerView(binding.feedGroupsList)

            binding.confirmButton.setOnClickListener {
                viewModel.updateOrder(groupOrderedIdList)
            }
        }

        override fun onDestroyView() {
            _binding = null
            super.onDestroyView()
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            Icepick.saveInstanceState(this, outState)
        }

        private fun handleGroups(list: List<FeedGroupEntity>) {
            val groupList: List<FeedGroupEntity>

            if (groupOrderedIdList.isEmpty()) {
                groupList = list
                groupOrderedIdList.addAll(groupList.map { it.uid })
            } else groupList = list.sortedBy { groupOrderedIdList.indexOf(it.uid) }

            groupAdapter.update(groupList.map { FeedGroupReorderItem(it, itemTouchHelper) })
        }

        private fun disableInput() {
            _binding?.confirmButton?.isEnabled = false
            isCancelable = false
        }

        private fun getItemTouchCallback(): SimpleCallback {
            return object : TouchCallback() {
                override fun onMove(recyclerView: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                    val sourceIndex = source.bindingAdapterPosition
                    val targetIndex = target.bindingAdapterPosition
                    groupAdapter.notifyItemMoved(sourceIndex, targetIndex)
                    Collections.swap(groupOrderedIdList, sourceIndex, targetIndex)
                    return true
                }
                override fun isLongPressDragEnabled(): Boolean = false
                override fun isItemViewSwipeEnabled(): Boolean = false
                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, swipeDir: Int) {}
            }
        }

        data class FeedGroupReorderItem(val groupId: Long = GROUP_ALL_ID, val name: String, val icon: FeedGroupIcon?,
                                        val dragCallback: ItemTouchHelper) : BindableItem<FeedGroupReorderItemBinding>() {
            constructor (feedGroupEntity: FeedGroupEntity, dragCallback: ItemTouchHelper)
                    : this(feedGroupEntity.uid, feedGroupEntity.name, feedGroupEntity.icon, dragCallback)

            override fun getId(): Long {
                return when (groupId) {
                    GROUP_ALL_ID -> super.getId()
                    else -> groupId
                }
            }

            override fun getLayout(): Int = R.layout.feed_group_reorder_item
            override fun bind(viewBinding: FeedGroupReorderItemBinding, position: Int) {
                viewBinding.groupName.text = name
                if (icon != null) viewBinding.groupIcon.setImageResource(icon.getDrawableRes())
            }
            override fun bind(viewHolder: GroupieViewHolder<FeedGroupReorderItemBinding>, position: Int, payloads: MutableList<Any>) {
                super.bind(viewHolder, position, payloads)
                viewHolder.binding.handle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        dragCallback.startDrag(viewHolder)
                        return@setOnTouchListener true
                    }
                    false
                }
            }
            override fun getDragDirs(): Int {
                return UP or DOWN
            }
            override fun initializeViewBinding(view: View) = FeedGroupReorderItemBinding.bind(view)
        }
    }

    companion object {
//        const val JSON_MIME_TYPE = "application/json"
        const val JSON_MIME_TYPE = "*/*"    // TODO: some file pickers don't recognize this
    }
}
