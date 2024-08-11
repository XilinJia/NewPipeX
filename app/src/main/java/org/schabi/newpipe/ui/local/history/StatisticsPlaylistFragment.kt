package org.schabi.newpipe.ui.local.history

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Parcelable
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import com.google.android.material.snackbar.Snackbar
import icepick.State
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.schabi.newpipe.R
import org.schabi.newpipe.database.LocalItem
import org.schabi.newpipe.database.stream.StreamStatisticsEntry
import org.schabi.newpipe.databinding.PlaylistControlBinding
import org.schabi.newpipe.databinding.StatisticPlaylistControlBinding
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.ui.list.PlaylistControlViewHolder
import org.schabi.newpipe.ui.info_list.dialog.InfoItemDialog
import org.schabi.newpipe.ui.info_list.dialog.InfoItemDialog.Builder.Companion.reportErrorDuringInitialization
import org.schabi.newpipe.ui.info_list.dialog.StreamDialogDefaultEntry
import org.schabi.newpipe.ui.info_list.dialog.StreamDialogEntry.StreamDialogEntryAction
import org.schabi.newpipe.ui.local.BaseLocalListFragment
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.settings.HistorySettingsFragment.Companion.openDeleteWatchHistoryDialog
import org.schabi.newpipe.util.NavigationHelper.openVideoDetailFragment
import org.schabi.newpipe.util.NavigationHelper.playOnBackgroundPlayer
import org.schabi.newpipe.util.OnClickGesture
import org.schabi.newpipe.util.PlayButtonHelper.initPlaylistControlClickListener
import java.util.*
import kotlin.math.max

class StatisticsPlaylistFragment : BaseLocalListFragment<List<StreamStatisticsEntry?>?, Void?>(), PlaylistControlViewHolder {

    private val disposables = CompositeDisposable()

    @JvmField
    @State
    var itemsListState: Parcelable? = null
    private var sortMode = StatisticSortMode.LAST_PLAYED

    private var headerBinding: StatisticPlaylistControlBinding? = null
    private var playlistControlBinding: PlaylistControlBinding? = null

    /* Used for independent events */
    private var databaseSubscription: Subscription? = null
    private var recordManager: HistoryRecordManager? = null

    private fun processResult(results: List<StreamStatisticsEntry>): List<StreamStatisticsEntry>? {
        val comparator = when (sortMode) {
            StatisticSortMode.LAST_PLAYED -> Comparator.comparing(StreamStatisticsEntry::latestAccessDate)
            StatisticSortMode.MOST_PLAYED -> Comparator.comparingLong(StreamStatisticsEntry::watchCount)
            else -> return null
        }
        Collections.sort(results, comparator.reversed())
        return results
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle - Creation
    ///////////////////////////////////////////////////////////////////////////
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordManager = HistoryRecordManager(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_playlist, container, false)
    }

    override fun onResume() {
        super.onResume()
        if (activity != null) setTitle(requireActivity().getString(R.string.title_activity_history))
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_history, menu)
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle - Views
    ///////////////////////////////////////////////////////////////////////////
    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)
        if (!useAsFrontPage) setTitle(getString(R.string.title_last_played))
    }

    override val listHeader: ViewBinding?
        get() {
            headerBinding = StatisticPlaylistControlBinding.inflate(requireActivity().layoutInflater, itemsList, false)
            playlistControlBinding = headerBinding!!.playlistControl
            return headerBinding
        }

    override fun initListeners() {
        super.initListeners()

        itemListAdapter?.setSelectedListener(object : OnClickGesture<LocalItem> {
            override fun selected(selectedItem: LocalItem) {
                if (selectedItem is StreamStatisticsEntry) {
                    val item = selectedItem.streamEntity
                    openVideoDetailFragment(requireContext(), fM!!, item.serviceId, item.url, item.title, null, false)
                }
            }
            override fun held(selectedItem: LocalItem) {
                if (selectedItem is StreamStatisticsEntry) showInfoItemDialog(selectedItem)
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_history_clear) openDeleteWatchHistoryDialog(requireContext(), recordManager, disposables)
        else return super.onOptionsItemSelected(item)

        return true
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle - Loading
    ///////////////////////////////////////////////////////////////////////////
    override fun startLoading(forceLoad: Boolean) {
        super.startLoading(forceLoad)
        recordManager!!.streamStatistics
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(historyObserver)
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle - Destruction
    ///////////////////////////////////////////////////////////////////////////
    override fun onPause() {
        super.onPause()
        itemsListState = itemsList?.layoutManager?.onSaveInstanceState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        itemListAdapter?.unsetSelectedListener()
        headerBinding = null
        playlistControlBinding = null
        if (databaseSubscription != null) databaseSubscription!!.cancel()
        databaseSubscription = null
    }

    override fun onDestroy() {
        super.onDestroy()
        recordManager = null
        itemsListState = null
    }

    private val historyObserver: Subscriber<List<StreamStatisticsEntry?>?>
        ///////////////////////////////////////////////////////////////////////////
        get() = object : Subscriber<List<StreamStatisticsEntry?>?> {
            override fun onSubscribe(s: Subscription) {
                showLoading()
                if (databaseSubscription != null) databaseSubscription!!.cancel()
                databaseSubscription = s
                databaseSubscription!!.request(1)
            }
            override fun onNext(streams: List<StreamStatisticsEntry?>?) {
                handleResult(streams)
                if (databaseSubscription != null) databaseSubscription!!.request(1)
            }
            override fun onError(exception: Throwable) {
                showError(ErrorInfo(exception, UserAction.SOMETHING_ELSE, "History Statistics"))
            }
            override fun onComplete() {}
        }

    @SuppressLint("UseRequireInsteadOfGet")
    override fun handleResult(result: List<StreamStatisticsEntry?>?) {
        super.handleResult(result)
        if (itemListAdapter == null) return

        playlistControlBinding!!.root.visibility = View.VISIBLE
        itemListAdapter?.clearStreamItemList()
        if (result.isNullOrEmpty()) {
            showEmptyState()
            return
        }

        itemListAdapter?.addItems(processResult(result.filterNotNull()))
        if (itemsListState != null && itemsList?.layoutManager != null) {
            itemsList!!.layoutManager!!.onRestoreInstanceState(itemsListState)
            itemsListState = null
        }

        initPlaylistControlClickListener(activity!!, playlistControlBinding!!, this)
        headerBinding!!.sortButton.setOnClickListener { view: View? -> toggleSortMode() }
        hideLoading()
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment Error Handling
    ///////////////////////////////////////////////////////////////////////////
    override fun resetFragment() {
        super.resetFragment()
        if (databaseSubscription != null) databaseSubscription!!.cancel()
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    ////////////////////////////////////////////////////////////////////////// */
    private fun toggleSortMode() {
        if (sortMode == StatisticSortMode.LAST_PLAYED) {
            sortMode = StatisticSortMode.MOST_PLAYED
            setTitle(getString(R.string.title_most_played))
            headerBinding!!.sortButtonIcon.setImageResource(R.drawable.ic_history)
            headerBinding!!.sortButtonText.setText(R.string.title_last_played)
        } else {
            sortMode = StatisticSortMode.LAST_PLAYED
            setTitle(getString(R.string.title_last_played))
            headerBinding!!.sortButtonIcon.setImageResource(R.drawable.ic_filter_list)
            headerBinding!!.sortButtonText.setText(R.string.title_most_played)
        }
        startLoading(true)
    }

    private fun getPlayQueueStartingAt(infoItem: StreamStatisticsEntry): PlayQueue {
        if (itemListAdapter == null) return getPlayQueue(0)
        return getPlayQueue(max(itemListAdapter!!.itemsList.indexOf(infoItem).toDouble(), 0.0).toInt())
    }

    private fun showInfoItemDialog(item: StreamStatisticsEntry) {
        val context = context
        val infoItem = item.toStreamInfoItem()

        try {
            val dialogBuilder = InfoItemDialog.Builder(requireActivity(), context!!, this, infoItem)

            // set entries in the middle; the others are added automatically
            dialogBuilder
                .addEntry(StreamDialogDefaultEntry.DELETE)
                .setAction(StreamDialogDefaultEntry.DELETE) { f: Fragment?, i: StreamInfoItem? ->
                    if (itemListAdapter != null) deleteEntry(max(itemListAdapter!!.itemsList.indexOf(item).toDouble(), 0.0).toInt()) }
                .setAction(StreamDialogDefaultEntry.START_HERE_ON_BACKGROUND, StreamDialogEntryAction { f: Fragment?, i: StreamInfoItem? ->
                    playOnBackgroundPlayer(context, getPlayQueueStartingAt(item), true) })
                .create()
                .show()
        } catch (e: IllegalArgumentException) {
            reportErrorDuringInitialization(e, infoItem)
        }
    }

    private fun deleteEntry(index: Int) {
        if (itemListAdapter == null) return
        val infoItem = itemListAdapter!!.itemsList[index]
        if (infoItem is StreamStatisticsEntry) {
            val onDelete = recordManager!!
                .deleteStreamHistoryAndState(infoItem.streamId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    {
                        if (view != null) Snackbar.make(requireView(), R.string.one_item_deleted, Snackbar.LENGTH_SHORT).show()
                        else Toast.makeText(context, R.string.one_item_deleted, Toast.LENGTH_SHORT).show()
                    },
                    { throwable: Throwable? -> showSnackBarError(ErrorInfo(throwable!!, UserAction.DELETE_FROM_HISTORY, "Deleting item")) })

            disposables.add(onDelete)
        }
    }

    override val playQueue: PlayQueue
        get() = getPlayQueue(0)

    private fun getPlayQueue(index: Int): PlayQueue {
        if (itemListAdapter == null) return SinglePlayQueue(emptyList(), 0)

        val infoItems: List<LocalItem> = itemListAdapter!!.itemsList
        val streamInfoItems: MutableList<StreamInfoItem> = ArrayList(infoItems.size)
        for (item in infoItems) {
            if (item is StreamStatisticsEntry) streamInfoItems.add(item.toStreamInfoItem())
        }
        return SinglePlayQueue(streamInfoItems, index)
    }

    private enum class StatisticSortMode {
        LAST_PLAYED,
        MOST_PLAYED,
    }
}

