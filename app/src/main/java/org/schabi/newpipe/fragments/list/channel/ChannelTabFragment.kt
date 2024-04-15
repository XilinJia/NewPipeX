package org.schabi.newpipe.fragments.list.channel

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import icepick.State
import io.reactivex.rxjava3.core.Single
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.PlaylistControlBinding
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.ReadyChannelTabListLinkHandler
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.fragments.list.BaseListInfoFragment
import org.schabi.newpipe.fragments.list.playlist.PlaylistControlViewHolder
import org.schabi.newpipe.player.playqueue.ChannelTabPlayQueue
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.util.ChannelTabHelper.isStreamsTab
import org.schabi.newpipe.util.ExtractorHelper.getChannelTab
import org.schabi.newpipe.util.ExtractorHelper.getMoreChannelTabItems
import org.schabi.newpipe.util.PlayButtonHelper.initPlaylistControlClickListener
import java.util.function.Supplier
import java.util.stream.Collectors

class ChannelTabFragment : BaseListInfoFragment<InfoItem, ChannelTabInfo>(UserAction.REQUESTED_CHANNEL),
    PlaylistControlViewHolder {

    // states must be protected and not private for IcePick being able to access them
    @JvmField
    @State
    var tabHandler: ListLinkHandler? = null

    @JvmField
    @State
    var channelName: String? = null

    private var playlistControlBinding: PlaylistControlBinding? = null

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(false)
    }

    override fun onCreateView(inflater: LayoutInflater,
                              container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        Log.d(TAG, "onCreateView")
        return inflater.inflate(R.layout.fragment_channel_tab, container, false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        playlistControlBinding = null
    }

    override val listHeaderSupplier: Supplier<View>?
        get() {
            if (isStreamsTab(tabHandler!!)) {
                playlistControlBinding = PlaylistControlBinding
                    .inflate(requireActivity().layoutInflater, itemsList, false)
                return Supplier { playlistControlBinding!!.root }
            }
            return null
        }

    override fun loadResult(forceLoad: Boolean): Single<ChannelTabInfo> {
        return getChannelTab(serviceId, tabHandler!!, forceLoad)
    }

    override fun loadMoreItemsLogic(): Single<InfoItemsPage<InfoItem>> {
        return getMoreChannelTabItems(serviceId, tabHandler, currentNextPage)
    }

    override fun setTitle(title: String) {
        // The channel name is displayed as title in the toolbar.
        // The title is always a description of the content of the tab fragment.
        // It should be unique for each channel because multiple channel tabs
        // can be added to the main page. Therefore, the channel name is used.
        // Using the title variable would cause the title to be the same for all channel tabs.
        super.setTitle(channelName!!)
    }

    override fun handleResult(result: ChannelTabInfo) {
        super.handleResult(result)

        // FIXME this is a really hacky workaround, to avoid storing useless data in the fragment
        //  state. The problem is, `ReadyChannelTabListLinkHandler` might contain raw JSON data that
        //  uses a lot of memory (e.g. ~800KB for YouTube). While 800KB doesn't seem much, if
        //  you combine just a couple of channel tab fragments you easily go over the 1MB
        //  save&restore transaction limit, and get `TransactionTooLargeException`s. A proper
        //  solution would require rethinking about `ReadyChannelTabListLinkHandler`s.
        if (tabHandler is ReadyChannelTabListLinkHandler) {
            try {
                // once `handleResult` is called, the parsed data was already saved to cache, so
                // we can discard any raw data in ReadyChannelTabListLinkHandler and create a
                // link handler with identical properties, but without any raw data
                val channelTabLHFactory = result.service
                    .channelTabLHFactory
                if (channelTabLHFactory != null) {
                    // some services do not not have a ChannelTabLHFactory
                    tabHandler = channelTabLHFactory.fromQuery(tabHandler!!.getId(),
                        tabHandler!!.getContentFilters(), tabHandler!!.getSortFilter())
                }
            } catch (e: ParsingException) {
                // silently ignore the error, as the app can continue to function normally
                Log.w(TAG, "Could not recreate channel tab handler", e)
            }
        }

        if (playlistControlBinding != null) {
            // PlaylistControls should be visible only if there is some item in
            // infoListAdapter other than header
            if (infoListAdapter!!.itemCount > 1) {
                playlistControlBinding!!.root.visibility = View.VISIBLE
            } else {
                playlistControlBinding!!.root.visibility = View.GONE
            }

            initPlaylistControlClickListener(requireActivity() as AppCompatActivity, playlistControlBinding!!, this)
        }
    }

    override val playQueue: ChannelTabPlayQueue
        get() {
            val streamItems = infoListAdapter!!.itemsList.stream()
                .filter { obj: InfoItem? -> StreamInfoItem::class.java.isInstance(obj) }
                .map { obj: InfoItem? -> StreamInfoItem::class.java.cast(obj) }
                .collect(Collectors.toList())

            return ChannelTabPlayQueue(currentInfo!!.serviceId, tabHandler!!, currentInfo!!.nextPage, streamItems, 0)
        }

    companion object {
        fun getInstance(serviceId: Int, tabHandler: ListLinkHandler?, channelName: String?): ChannelTabFragment {
            val instance = ChannelTabFragment()
            instance.serviceId = serviceId
            instance.tabHandler = tabHandler
            instance.channelName = channelName
            return instance
        }
    }
}
