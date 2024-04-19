package org.schabi.newpipe.local.playlist

import android.content.DialogInterface
import android.os.Bundle
import android.os.Parcelable
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.util.Pair
import android.view.*
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import icepick.State
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.PublishSubject
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.schabi.newpipe.NewPipeDatabase.getInstance
import org.schabi.newpipe.R
import org.schabi.newpipe.database.LocalItem
import org.schabi.newpipe.database.history.model.StreamHistoryEntry
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.databinding.DialogEditTextBinding
import org.schabi.newpipe.databinding.LocalPlaylistHeaderBinding
import org.schabi.newpipe.databinding.PlaylistControlBinding
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil.Companion.showUiErrorSnackbar
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.fragments.MainFragment.SelectedTabsPagerAdapter
import org.schabi.newpipe.fragments.list.playlist.PlaylistControlViewHolder
import org.schabi.newpipe.info_list.dialog.InfoItemDialog
import org.schabi.newpipe.info_list.dialog.InfoItemDialog.Builder.Companion.reportErrorDuringInitialization
import org.schabi.newpipe.info_list.dialog.StreamDialogDefaultEntry
import org.schabi.newpipe.ktx.animate
import org.schabi.newpipe.local.BaseLocalListFragment
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.Localization.localizeStreamCount
import org.schabi.newpipe.util.NavigationHelper.openVideoDetailFragment
import org.schabi.newpipe.util.NavigationHelper.playOnBackgroundPlayer
import org.schabi.newpipe.util.OnClickGesture
import org.schabi.newpipe.util.PlayButtonHelper.initPlaylistControlClickListener
import org.schabi.newpipe.util.ThemeHelper.shouldUseGridLayout
import org.schabi.newpipe.util.external_communication.ShareUtils.shareText
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Collectors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

class LocalPlaylistFragment : BaseLocalListFragment<List<PlaylistStreamEntry>, Void?>(), PlaylistControlViewHolder {
    @JvmField
    @State
    var playlistId: Long? = null

    @JvmField
    @State
    var name: String? = null

    @JvmField
    @State
    var itemsListState: Parcelable? = null

    private var headerBinding: LocalPlaylistHeaderBinding? = null
    private var playlistControlBinding: PlaylistControlBinding? = null

    private var itemTouchHelper: ItemTouchHelper? = null

    private var playlistManager: LocalPlaylistManager? = null
    private var databaseSubscription: Subscription? = null

    private var debouncedSaveSignal: PublishSubject<Long>? = null
    private var disposables: CompositeDisposable? = null

    /** Whether the playlist has been fully loaded from db.  */
    private var isLoadingComplete: AtomicBoolean? = null

    /** Whether the playlist has been modified (e.g. items reordered or deleted)  */
    private var isModified: AtomicBoolean? = null

    /** Flag to prevent simultaneous rewrites of the playlist.  */
    private var isRewritingPlaylist = false

    /**
     * The pager adapter that the fragment is created from when it is used as frontpage, i.e.
     * [.useAsFrontPage] is [true].
     */
    private var tabsPagerAdapter: SelectedTabsPagerAdapter? = null

    ///////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle - Creation
    ///////////////////////////////////////////////////////////////////////////
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playlistManager = LocalPlaylistManager(getInstance(requireContext()))
        debouncedSaveSignal = PublishSubject.create()

        disposables = CompositeDisposable()

        isLoadingComplete = AtomicBoolean()
        isModified = AtomicBoolean()
    }

    override fun onCreateView(inflater: LayoutInflater,
                              container: ViewGroup?,
                              savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_playlist, container, false)
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment Lifecycle - Views
    ///////////////////////////////////////////////////////////////////////////
    override fun setTitle(title: String) {
        super.setTitle(title)

        if (headerBinding != null) {
            headerBinding!!.playlistTitleView.text = title
        }
    }

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)
        setTitle(name!!)
    }

    override val listHeader: ViewBinding
        get() {
            headerBinding = LocalPlaylistHeaderBinding.inflate(requireActivity().layoutInflater, itemsList, false)
            playlistControlBinding = headerBinding!!.playlistControl

            headerBinding!!.playlistTitleView.isSelected = true

            return headerBinding!!
        }

    /**
     *
     * Commit changes immediately if the playlist has been modified.
     * Delete operations and other modifications will be committed to ensure that the database
     * is up to date, e.g. when the user adds the just deleted stream from another fragment.
     */
    fun commitChanges() {
        if (isModified != null && isModified!!.get()) {
            saveImmediate()
        }
    }

    override fun initListeners() {
        super.initListeners()

        headerBinding!!.playlistTitleView.setOnClickListener { view: View? -> createRenameDialog() }

        itemTouchHelper = ItemTouchHelper(itemTouchCallback)
        itemTouchHelper!!.attachToRecyclerView(itemsList)

        itemListAdapter!!.setSelectedListener(object : OnClickGesture<LocalItem> {
            @OptIn(UnstableApi::class) override fun selected(selectedItem: LocalItem) {
                if (selectedItem is PlaylistStreamEntry) {
                    val item =
                        selectedItem.streamEntity
                    openVideoDetailFragment(requireContext(), fM!!,
                        item.serviceId, item.url, item.title, null, false)
                }
            }

            override fun held(selectedItem: LocalItem) {
                if (selectedItem is PlaylistStreamEntry) {
                    showInfoItemDialog(selectedItem)
                }
            }

            override fun drag(selectedItem: LocalItem, viewHolder: RecyclerView.ViewHolder?) {
                if (itemTouchHelper != null) {
                    itemTouchHelper!!.startDrag(viewHolder!!)
                }
            }
        })
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment Lifecycle - Loading
    ///////////////////////////////////////////////////////////////////////////
    override fun showLoading() {
        super.showLoading()
        if (headerBinding != null) {
            headerBinding!!.root.animate(false, 200)
            playlistControlBinding!!.root.animate(false, 200)
        }
    }

    override fun hideLoading() {
        super.hideLoading()
        if (headerBinding != null) {
            headerBinding!!.root.animate(true, 200)
            playlistControlBinding!!.root.animate(true, 200)
        }
    }

    override fun startLoading(forceLoad: Boolean) {
        super.startLoading(forceLoad)

        disposables?.clear()
        disposables?.add(debouncedSaver)

        isLoadingComplete!!.set(false)
        isModified!!.set(false)

        playlistManager!!.getPlaylistStreams(playlistId!!)
            .onBackpressureLatest()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(playlistObserver)
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment Lifecycle - Destruction
    ///////////////////////////////////////////////////////////////////////////
    override fun onPause() {
        super.onPause()
        itemsListState = itemsList!!.layoutManager!!.onSaveInstanceState()

        // Save on exit
        saveImmediate()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        if (DEBUG) {
            Log.d(TAG, "onCreateOptionsMenu() called with: "
                    + "menu = [" + menu + "], inflater = [" + inflater + "]")
        }
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_local_playlist, menu)
    }

    override fun onDestroyView() {
        super.onDestroyView()

        itemListAdapter?.unsetSelectedListener()

        headerBinding = null
        playlistControlBinding = null

        databaseSubscription?.cancel()
        disposables?.clear()

        databaseSubscription = null
        itemTouchHelper = null
    }

    override fun onDestroy() {
        super.onDestroy()
        debouncedSaveSignal?.onComplete()
        disposables?.dispose()
        
        tabsPagerAdapter?.localPlaylistFragments?.remove(this)

        debouncedSaveSignal = null
        playlistManager = null
        disposables = null

        isLoadingComplete = null
        isModified = null
    }

    private val playlistObserver: Subscriber<List<PlaylistStreamEntry>>
        ///////////////////////////////////////////////////////////////////////////
        get() = object : Subscriber<List<PlaylistStreamEntry>> {
            override fun onSubscribe(s: Subscription) {
                showLoading()
                isLoadingComplete!!.set(false)

                databaseSubscription?.cancel()

                databaseSubscription = s
                databaseSubscription!!.request(1)
            }

            override fun onNext(streams: List<PlaylistStreamEntry>) {
                // Skip handling the result after it has been modified
                if (isModified == null || !isModified!!.get()) {
                    handleResult(streams)
                    isLoadingComplete!!.set(true)
                }

                databaseSubscription?.request(1)
            }

            override fun onError(exception: Throwable) {
                showError(ErrorInfo(exception, UserAction.REQUESTED_BOOKMARK, "Loading local playlist"))
            }

            override fun onComplete() {
            }
        }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_item_share_playlist -> {
                createShareConfirmationDialog()
            }
            R.id.menu_item_rename_playlist -> {
                createRenameDialog()
            }
            R.id.menu_item_remove_watched -> {
                if (!isRewritingPlaylist) {
                    AlertDialog.Builder(requireContext())
                        .setMessage(R.string.remove_watched_popup_warning)
                        .setTitle(R.string.remove_watched_popup_title)
                        .setPositiveButton(R.string.ok) { d: DialogInterface?, id: Int -> removeWatchedStreams(false) }
                        .setNeutralButton(R.string.remove_watched_popup_yes_and_partially_watched_videos) {
                            d: DialogInterface?, id: Int -> removeWatchedStreams(true) }
                        .setNegativeButton(R.string.cancel) { d: DialogInterface, id: Int -> d.cancel() }
                        .show()
                }
            }
            R.id.menu_item_remove_duplicates -> {
                if (!isRewritingPlaylist) {
                    openRemoveDuplicatesDialog()
                }
            }
            else -> {
                return super.onOptionsItemSelected(item)
            }
        }
        return true
    }

    /**
     * Shares the playlist as a list of stream URLs if `shouldSharePlaylistDetails` is
     * set to `false`. Shares the playlist name along with a list of video titles and URLs
     * if `shouldSharePlaylistDetails` is set to `true`.
     *
     * @param shouldSharePlaylistDetails Whether the playlist details should be included in the
     * shared content.
     */
    private fun sharePlaylist(shouldSharePlaylistDetails: Boolean) {
        val context = requireContext()

        disposables!!.add(playlistManager!!.getPlaylistStreams(playlistId!!)
            .flatMapSingle { playlist: List<PlaylistStreamEntry> ->
                Single.just(playlist.stream()
                    .map(PlaylistStreamEntry::streamEntity)
                    .map { streamEntity: StreamEntity ->
                        if (shouldSharePlaylistDetails) {
                            return@map context.getString(R.string.video_details_list_item, streamEntity.title, streamEntity.url)
                        } else {
                            return@map streamEntity.url
                        }
                    }
                    .collect(Collectors.joining("\n")))
            }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ urlsText: String? ->
                shareText(context, name!!, if (shouldSharePlaylistDetails) context.getString(R.string.share_playlist_content_details,
                        name, urlsText) else urlsText)
            },
                { throwable: Throwable? -> showUiErrorSnackbar(this, "Sharing playlist", throwable!!) }))
    }

    fun removeWatchedStreams(removePartiallyWatched: Boolean) {
        if (isRewritingPlaylist) return

        isRewritingPlaylist = true
        showLoading()

        val recordManager = HistoryRecordManager(requireContext())
        val historyIdsMaybe = recordManager.streamHistorySortedById
            .firstElement() // already sorted by ^ getStreamHistorySortedById(), binary search can be used
            .map { historyList: List<StreamHistoryEntry> ->
                historyList.stream().map<Long>(StreamHistoryEntry::streamId)
                    .collect(Collectors.toList())
            }
        val streamsMaybe = playlistManager!!.getPlaylistStreams(playlistId!!)
            .firstElement()
            .zipWith(historyIdsMaybe) { playlist: List<PlaylistStreamEntry>, historyStreamIds: List<Long>? ->
                // Remove Watched, Functionality data
                val itemsToKeep: MutableList<PlaylistStreamEntry?> = ArrayList()
                val isThumbnailPermanent = playlistManager?.getIsPlaylistThumbnailPermanent(playlistId!!)?: false
                var thumbnailVideoRemoved = false

                if (removePartiallyWatched) {
                    for (playlistItem in playlist) {
                        val indexInHistory = Collections.binarySearch(historyStreamIds, playlistItem!!.streamId)

                        if (indexInHistory < 0) {
                            itemsToKeep.add(playlistItem)
                        } else if (!isThumbnailPermanent && !thumbnailVideoRemoved
                                && (playlistManager!!.getPlaylistThumbnailStreamId(playlistId!!) == playlistItem.streamEntity.uid)) {
                            thumbnailVideoRemoved = true
                        }
                    }
                } else {
                    val streamStates = recordManager.loadLocalStreamStateBatch(playlist).blockingGet()

                    for (i in playlist.indices) {
                        val playlistItem = playlist[i]
                        val streamStateEntity = streamStates[i]

                        val indexInHistory = Collections.binarySearch(historyStreamIds, playlistItem!!.streamId)
                        val duration = playlistItem.toStreamInfoItem().duration

                        if (indexInHistory < 0 || (streamStateEntity != null && !streamStateEntity.isFinished(duration))) {
                            itemsToKeep.add(playlistItem)
                        } else if (!isThumbnailPermanent && !thumbnailVideoRemoved
                                && (playlistManager!!.getPlaylistThumbnailStreamId(playlistId!!) == playlistItem.streamEntity.uid)) {
                            thumbnailVideoRemoved = true
                        }
                    }
                }
                Pair<List<PlaylistStreamEntry?>, Boolean>(itemsToKeep, thumbnailVideoRemoved)
            }

        disposables!!.add(streamsMaybe.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ flow: Pair<List<PlaylistStreamEntry?>, Boolean> ->
                val itemsToKeep = flow.first
                val thumbnailVideoRemoved = flow.second

                itemListAdapter!!.clearStreamItemList()
                itemListAdapter!!.addItems(itemsToKeep.filterNotNull())
                saveChanges()

                if (thumbnailVideoRemoved) {
                    updateThumbnailUrl()
                }

                val videoCount = itemListAdapter!!.itemsList.size.toLong()
                setVideoCount(videoCount)
                if (videoCount == 0L) {
                    showEmptyState()
                }

                hideLoading()
                isRewritingPlaylist = false
            }, { throwable: Throwable? ->
                showError(ErrorInfo(throwable!!, UserAction.REQUESTED_BOOKMARK,
                    "Removing watched videos, partially watched=$removePartiallyWatched"))
            }))
    }

    override fun handleResult(result: List<PlaylistStreamEntry>) {
        super.handleResult(result)
        if (itemListAdapter == null) return

        itemListAdapter!!.clearStreamItemList()

        if (result.isEmpty()) {
            showEmptyState()
            return
        }

        itemListAdapter!!.addItems(result)
        if (itemsListState != null) {
            itemsList!!.layoutManager!!.onRestoreInstanceState(itemsListState)
            itemsListState = null
        }
        setVideoCount(itemListAdapter!!.itemsList.size.toLong())

        initPlaylistControlClickListener(requireActivity() as AppCompatActivity, playlistControlBinding!!, this)

        hideLoading()
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment Error Handling
    ///////////////////////////////////////////////////////////////////////////
    override fun resetFragment() {
        super.resetFragment()
        databaseSubscription?.cancel()
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Playlist Metadata/Streams Manipulation
    ////////////////////////////////////////////////////////////////////////// */
    private fun createRenameDialog() {
        if (playlistId == null || name == null || context == null) return

        val dialogBinding = DialogEditTextBinding.inflate(layoutInflater)
        dialogBinding.dialogEditText.setHint(R.string.name)
        dialogBinding.dialogEditText.inputType = InputType.TYPE_CLASS_TEXT
        dialogBinding.dialogEditText.setSelection(dialogBinding.dialogEditText.text!!.length)
        dialogBinding.dialogEditText.setText(name)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rename_playlist)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.rename) { dialogInterface: DialogInterface?, i: Int ->
                changePlaylistName(dialogBinding.dialogEditText.text.toString())
            }
            .show()
    }

    private fun changePlaylistName(title: String) {
        if (playlistManager == null) return

        this.name = title
        setTitle(title)

        if (DEBUG) {
            Log.d(TAG, "Updating playlist id=[$playlistId] with new title=[$title] items")
        }

        val disposable = playlistManager!!.renamePlaylist(playlistId!!, title)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ longs: Int? -> }, { throwable: Throwable? ->
                showError(ErrorInfo(throwable!!, UserAction.REQUESTED_BOOKMARK, "Renaming playlist"))
            })
        disposables!!.add(disposable)
    }

    private fun changeThumbnailStreamId(thumbnailStreamId: Long, isPermanent: Boolean) {
        if (playlistManager == null || (!isPermanent && playlistManager!!.getIsPlaylistThumbnailPermanent(playlistId!!))) return

        val successToast = Toast.makeText(getActivity(),
            R.string.playlist_thumbnail_change_success,
            Toast.LENGTH_SHORT)

        if (DEBUG) {
            Log.d(TAG, "Updating playlist id=[$playlistId] with new thumbnail stream id=[$thumbnailStreamId]")
        }

        val disposable = playlistManager!!
            .changePlaylistThumbnail(playlistId!!, thumbnailStreamId, isPermanent)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ ignore: Int? -> successToast.show() }, { throwable: Throwable? ->
                showError(ErrorInfo(throwable!!, UserAction.REQUESTED_BOOKMARK, "Changing playlist thumbnail"))
            })
        disposables!!.add(disposable)
    }

    private fun updateThumbnailUrl() {
        if (playlistManager!!.getIsPlaylistThumbnailPermanent(playlistId!!)) return

        val thumbnailStreamId = if (!itemListAdapter!!.itemsList.isEmpty()) {
            (itemListAdapter!!.itemsList[0] as PlaylistStreamEntry).streamEntity.uid
        } else {
            PlaylistEntity.DEFAULT_THUMBNAIL_ID
        }

        changeThumbnailStreamId(thumbnailStreamId, false)
    }

    private fun openRemoveDuplicatesDialog() {
        AlertDialog.Builder(requireActivity())
            .setTitle(R.string.remove_duplicates_title)
            .setMessage(R.string.remove_duplicates_message)
            .setPositiveButton(R.string.ok) { dialog: DialogInterface?, i: Int -> removeDuplicatesInPlaylist() }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun removeDuplicatesInPlaylist() {
        if (isRewritingPlaylist) return

        isRewritingPlaylist = true
        showLoading()

        val streamsMaybe = playlistManager!!.getDistinctPlaylistStreams(playlistId!!).firstElement()

        disposables!!.add(streamsMaybe.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ itemsToKeep: List<PlaylistStreamEntry>? ->
                itemListAdapter!!.clearStreamItemList()
                itemListAdapter!!.addItems(itemsToKeep)
                setVideoCount(itemListAdapter!!.itemsList.size.toLong())
                saveChanges()

                hideLoading()
                isRewritingPlaylist = false
            }, { throwable: Throwable? ->
                showError(ErrorInfo(throwable!!, UserAction.REQUESTED_BOOKMARK, "Removing duplicated streams"))
            }))
    }

    private fun deleteItem(item: PlaylistStreamEntry) {
        if (itemListAdapter == null) return

        itemListAdapter!!.removeItem(item)
        if (playlistManager!!.getPlaylistThumbnailStreamId(playlistId!!) == item.streamId) {
            updateThumbnailUrl()
        }

        setVideoCount(itemListAdapter!!.itemsList.size.toLong())
        saveChanges()
    }

    private fun saveChanges() {
        if (isModified == null || debouncedSaveSignal == null) return

        isModified!!.set(true)
        debouncedSaveSignal!!.onNext(System.currentTimeMillis())
    }

    private val debouncedSaver: Disposable
        get() {
            if (debouncedSaveSignal == null) return Disposable.empty()

            return debouncedSaveSignal!!
                .debounce(SAVE_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ ignored: Long? -> saveImmediate() }, { throwable: Throwable? ->
                    showError(ErrorInfo(throwable!!, UserAction.SOMETHING_ELSE, "Debounced saver"))
                })
        }

    private fun saveImmediate() {
        if (playlistManager == null || itemListAdapter == null) return

        // List must be loaded and modified in order to save
        if (isLoadingComplete == null || isModified == null || !isLoadingComplete!!.get() || !isModified!!.get()) {
            Log.w(TAG, "Attempting to save playlist when local playlist is not loaded or not modified: playlist id=[$playlistId]")
            return
        }

        val items: List<LocalItem> = itemListAdapter!!.itemsList
        val streamIds: MutableList<Long?> = ArrayList(items.size)
        for (item in items) {
            if (item is PlaylistStreamEntry) {
                streamIds.add(item.streamId)
            }
        }

        if (DEBUG) {
            Log.d(TAG, "Updating playlist id=[$playlistId] with [${streamIds.size}] items")
        }

        val disposable = playlistManager!!.updateJoin(playlistId!!, streamIds)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                {
                    if (isModified != null) isModified!!.set(false)
                },
                { throwable: Throwable? ->
                    showError(ErrorInfo(throwable!!, UserAction.REQUESTED_BOOKMARK, "Saving playlist"))
                }
            )
        disposables!!.add(disposable)
    }


    private val itemTouchCallback: ItemTouchHelper.SimpleCallback
        get() {
            var directions = ItemTouchHelper.UP or ItemTouchHelper.DOWN
            if (shouldUseGridLayout(requireContext())) {
                directions = directions or (ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
            }
            return object : ItemTouchHelper.SimpleCallback(directions,
                ItemTouchHelper.ACTION_STATE_IDLE) {
                override fun interpolateOutOfBoundsScroll(recyclerView: RecyclerView,
                                                          viewSize: Int,
                                                          viewSizeOutOfBounds: Int,
                                                          totalSize: Int,
                                                          msSinceStartScroll: Long): Int {
                    val standardSpeed = super.interpolateOutOfBoundsScroll(recyclerView, viewSize, viewSizeOutOfBounds, totalSize, msSinceStartScroll)
                    val minimumAbsVelocity = max(MINIMUM_INITIAL_DRAG_VELOCITY.toDouble(), abs(standardSpeed.toDouble())).toInt()
                    return minimumAbsVelocity * sign(viewSizeOutOfBounds.toDouble()).toInt()
                }

                override fun onMove(recyclerView: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                    if (source.itemViewType != target.itemViewType || itemListAdapter == null) return false

                    val sourceIndex = source.bindingAdapterPosition
                    val targetIndex = target.bindingAdapterPosition
                    val isSwapped = itemListAdapter!!.swapItems(sourceIndex, targetIndex)
                    if (isSwapped) {
                        saveChanges()
                    }
                    return isSwapped
                }

                override fun isLongPressDragEnabled(): Boolean {
                    return false
                }

                override fun isItemViewSwipeEnabled(): Boolean {
                    return false
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, swipeDir: Int
                ) {
                }
            }
        }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    ////////////////////////////////////////////////////////////////////////// */
    private fun getPlayQueueStartingAt(infoItem: PlaylistStreamEntry): PlayQueue {
        return getPlayQueue(max(itemListAdapter!!.itemsList.indexOf(infoItem).toDouble(), 0.0).toInt())
    }

    @OptIn(UnstableApi::class) protected fun showInfoItemDialog(item: PlaylistStreamEntry) {
        val infoItem = item.toStreamInfoItem()

        try {
            val context = context
            val dialogBuilder =
                InfoItemDialog.Builder(requireActivity(), context!!, this, infoItem)

            // add entries in the middle
            dialogBuilder.addAllEntries(
                StreamDialogDefaultEntry.SET_AS_PLAYLIST_THUMBNAIL,
                StreamDialogDefaultEntry.DELETE
            )

            // set custom actions
            // all entries modified below have already been added within the builder
            dialogBuilder
                .setAction(StreamDialogDefaultEntry.START_HERE_ON_BACKGROUND) { f: Fragment?, i: StreamInfoItem? ->
                    playOnBackgroundPlayer(context, getPlayQueueStartingAt(item), true)
                }
                .setAction(StreamDialogDefaultEntry.SET_AS_PLAYLIST_THUMBNAIL
                ) { f: Fragment?, i: StreamInfoItem? ->
                    changeThumbnailStreamId(item.streamEntity.uid, true)
                }
                .setAction(StreamDialogDefaultEntry.DELETE) { f: Fragment?, i: StreamInfoItem? -> deleteItem(item) }
                .create()
                .show()
        } catch (e: IllegalArgumentException) {
            reportErrorDuringInitialization(e, infoItem)
        }
    }

    private fun setInitialData(pid: Long, title: String) {
        this.playlistId = pid
        this.name = if (!TextUtils.isEmpty(title)) title else ""
    }

    private fun setVideoCount(count: Long) {
        if (activity != null && headerBinding != null) {
            headerBinding!!.playlistStreamCount.text = localizeStreamCount(requireActivity(), count)
        }
    }

    override val playQueue: PlayQueue 
        get() = getPlayQueue(0)
    
    private fun getPlayQueue(index: Int): PlayQueue {
        if (itemListAdapter == null) return SinglePlayQueue(emptyList(), 0)

        val infoItems: List<LocalItem> = itemListAdapter!!.itemsList
        val streamInfoItems: MutableList<StreamInfoItem> = ArrayList(infoItems.size)
        for (item in infoItems) {
            if (item is PlaylistStreamEntry) {
                streamInfoItems.add(item.toStreamInfoItem())
            }
        }
        return SinglePlayQueue(streamInfoItems, index)
    }

    /**
     * Creates a dialog to confirm whether the user wants to share the playlist
     * with the playlist details or just the list of stream URLs.
     * After the user has made a choice, the playlist is shared.
     */
    private fun createShareConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.share_playlist)
            .setMessage(R.string.share_playlist_with_titles_message)
            .setCancelable(true)
            .setPositiveButton(R.string.share_playlist_with_titles) {
                dialog: DialogInterface?, which: Int -> sharePlaylist( /* shouldSharePlaylistDetails= */true) }
            .setNegativeButton(R.string.share_playlist_with_list) {
                dialog: DialogInterface?, which: Int -> sharePlaylist( /* shouldSharePlaylistDetails= */false) }
            .show()
    }

    fun setTabsPagerAdapter(tabsPagerAdapter: SelectedTabsPagerAdapter?) {
        this.tabsPagerAdapter = tabsPagerAdapter
    }

    companion object {
        /** Save the list 10 seconds after the last change occurred.  */
        private const val SAVE_DEBOUNCE_MILLIS: Long = 10000
        private const val MINIMUM_INITIAL_DRAG_VELOCITY = 12
        fun getInstance(playlistId: Long, name: String): LocalPlaylistFragment {
            val instance = LocalPlaylistFragment()
            instance.setInitialData(playlistId, name)
            return instance
        }
    }
}

