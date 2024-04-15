package org.schabi.newpipe.fragments.list.playlist

import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.Fragment
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.BiFunction
import io.reactivex.rxjava3.functions.Function
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.schabi.newpipe.NewPipeDatabase.getInstance
import org.schabi.newpipe.R
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.databinding.PlaylistControlBinding
import org.schabi.newpipe.databinding.PlaylistHeaderBinding
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil.Companion.showUiErrorSnackbar
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.fragments.list.BaseListInfoFragment
import org.schabi.newpipe.info_list.dialog.InfoItemDialog
import org.schabi.newpipe.info_list.dialog.InfoItemDialog.Builder.Companion.reportErrorDuringInitialization
import org.schabi.newpipe.info_list.dialog.StreamDialogDefaultEntry
import org.schabi.newpipe.ktx.animate
import org.schabi.newpipe.ktx.animateHideRecyclerViewAllowingScrolling
import org.schabi.newpipe.local.dialog.PlaylistDialog
import org.schabi.newpipe.local.playlist.RemotePlaylistManager
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.PlayQueueItem
import org.schabi.newpipe.player.playqueue.PlaylistPlayQueue
import org.schabi.newpipe.util.ExtractorHelper.getMorePlaylistItems
import org.schabi.newpipe.util.ExtractorHelper.getPlaylistInfo
import org.schabi.newpipe.util.Localization.localizeStreamCount
import org.schabi.newpipe.util.NavigationHelper.openChannelFragment
import org.schabi.newpipe.util.NavigationHelper.openSettings
import org.schabi.newpipe.util.NavigationHelper.playOnBackgroundPlayer
import org.schabi.newpipe.util.PlayButtonHelper.initPlaylistControlClickListener
import org.schabi.newpipe.util.ServiceHelper.getServiceById
import org.schabi.newpipe.util.external_communication.ShareUtils.openUrlInBrowser
import org.schabi.newpipe.util.external_communication.ShareUtils.shareText
import org.schabi.newpipe.util.image.PicassoHelper.cancelTag
import org.schabi.newpipe.util.image.PicassoHelper.loadAvatar
import org.schabi.newpipe.util.text.TextEllipsizer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier
import java.util.stream.Collectors
import kotlin.math.max

class PlaylistFragment
    : BaseListInfoFragment<StreamInfoItem, PlaylistInfo>(UserAction.REQUESTED_PLAYLIST), PlaylistControlViewHolder {

    private var disposables: CompositeDisposable? = null
    private var bookmarkReactor: Subscription? = null
    private var isBookmarkButtonReady: AtomicBoolean? = null

    private var remotePlaylistManager: RemotePlaylistManager? = null
    private var playlistEntity: PlaylistRemoteEntity? = null

    /*//////////////////////////////////////////////////////////////////////////
    // Views
    ////////////////////////////////////////////////////////////////////////// */
    private var headerBinding: PlaylistHeaderBinding? = null
    private var playlistControlBinding: PlaylistControlBinding? = null

    private var playlistBookmarkButton: MenuItem? = null

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disposables = CompositeDisposable()
        isBookmarkButtonReady = AtomicBoolean(false)
        remotePlaylistManager = RemotePlaylistManager(getInstance(requireContext()))
    }

    override fun onCreateView(inflater: LayoutInflater,
                              container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        Log.d(TAG, "onCreateView")
        return inflater.inflate(R.layout.fragment_playlist, container, false)
    }

    override val listHeaderSupplier: Supplier<View>
        /*//////////////////////////////////////////////////////////////////////////
    // Init
    ////////////////////////////////////////////////////////////////////////// */
        get() {
            headerBinding = PlaylistHeaderBinding.inflate(requireActivity().layoutInflater, itemsList, false)
            playlistControlBinding = headerBinding!!.playlistControl

            return Supplier { headerBinding!!.root }
        }

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)

        // Is mini variant still relevant?
        // Only the remote playlist screen uses it now
        infoListAdapter!!.setUseMiniVariant(true)
    }

    private fun getPlayQueueStartingAt(infoItem: StreamInfoItem): PlayQueue {
        return getPlayQueue(max(infoListAdapter!!.itemsList.indexOf(infoItem).toDouble(), 0.0).toInt())
    }

    override fun showInfoItemDialog(item: StreamInfoItem?) {
        val context = context
        try {
            val dialogBuilder = InfoItemDialog.Builder(requireActivity(), context!!, this, item!!)
            dialogBuilder
                .setAction(StreamDialogDefaultEntry.START_HERE_ON_BACKGROUND) { f: Fragment?, infoItem: StreamInfoItem? ->
                    if (infoItem != null) playOnBackgroundPlayer(context, getPlayQueueStartingAt(infoItem), true)
                }
                .create()
                .show()
        } catch (e: IllegalArgumentException) {
            reportErrorDuringInitialization(e, item!!)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        if (DEBUG) {
            Log.d(TAG, "onCreateOptionsMenu() called with: "
                    + "menu = [" + menu + "], inflater = [" + inflater + "]")
        }
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_playlist, menu)

        playlistBookmarkButton = menu.findItem(R.id.menu_item_bookmark)
        updateBookmarkButtons()
    }

    override fun onDestroyView() {
        headerBinding = null
        playlistControlBinding = null
        super.onDestroyView()
        isBookmarkButtonReady?.set(false)
        disposables?.clear()
        bookmarkReactor?.cancel()
        bookmarkReactor = null
    }

    override fun onDestroy() {
        super.onDestroy()
        disposables?.dispose()
        disposables = null
        remotePlaylistManager = null
        playlistEntity = null
        isBookmarkButtonReady = null
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Load and handle
    ////////////////////////////////////////////////////////////////////////// */
    override fun loadMoreItemsLogic(): Single<InfoItemsPage<StreamInfoItem>> {
        return getMorePlaylistItems(serviceId, url, currentNextPage)
    }

    override fun loadResult(forceLoad: Boolean): Single<PlaylistInfo> {
        return getPlaylistInfo(serviceId, url!!, forceLoad)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> openSettings(requireContext())
            R.id.menu_item_openInBrowser -> openUrlInBrowser(requireContext(), url)
            R.id.menu_item_share -> shareText(requireContext(), name!!, url,
                if (currentInfo == null) listOf<Image>() else currentInfo!!.thumbnails)
            R.id.menu_item_bookmark -> onBookmarkClicked()
            R.id.menu_item_append_playlist -> if (currentInfo != null) {
                disposables!!.add(PlaylistDialog.createCorrespondingDialog(context,
                    playQueue.streams.stream()
                        .map { item: PlayQueueItem -> StreamEntity(item) }
                        .toList()
//                    playQueue!!
//                        .streams
//                        .stream()
//                        .map { item: PlayQueueItem ->
//                            StreamEntity(item)
//                        }
//                        .collect(Collectors.toList<PlayQueueItem>())
                ) { dialog -> dialog.show(fM!!, TAG) })
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    ////////////////////////////////////////////////////////////////////////// */
    override fun showLoading() {
        super.showLoading()
        headerBinding!!.root.animate(false, 200)
        itemsList!!.animateHideRecyclerViewAllowingScrolling()

        cancelTag(PICASSO_PLAYLIST_TAG)
        headerBinding!!.uploaderLayout.animate(false, 200)
    }

    override fun handleResult(result: PlaylistInfo) {
        super.handleResult(result)

        headerBinding!!.root.animate(true, 100)
        headerBinding!!.uploaderLayout.animate(true, 300)
        headerBinding!!.uploaderLayout.setOnClickListener(null)
        // If we have an uploader put them into the UI
        if (!TextUtils.isEmpty(result.uploaderName)) {
            headerBinding!!.uploaderName.text = result.uploaderName
            if (!TextUtils.isEmpty(result.uploaderUrl)) {
                headerBinding!!.uploaderLayout.setOnClickListener { v: View? ->
                    try {
                        openChannelFragment(fM!!, result.serviceId, result.uploaderUrl, result.uploaderName)
                    } catch (e: Exception) {
                        showUiErrorSnackbar(this, "Opening channel fragment", e)
                    }
                }
            }
        } else { // Otherwise say we have no uploader
            headerBinding!!.uploaderName.setText(R.string.playlist_no_uploader)
        }

        playlistControlBinding!!.root.visibility = View.VISIBLE

        if (result.serviceId == ServiceList.YouTube.serviceId
                && (YoutubeParsingHelper.isYoutubeMixId(result.id) || YoutubeParsingHelper.isYoutubeMusicMixId(result.id))) {
            // this is an auto-generated playlist (e.g. Youtube mix), so a radio is shown
            val model = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, 0f)
                .build() // this turns the image back into a square
            headerBinding!!.uploaderAvatarView.shapeAppearanceModel = model
            headerBinding!!.uploaderAvatarView.strokeColor = AppCompatResources
                .getColorStateList(requireContext(), R.color.transparent_background_color)
            headerBinding!!.uploaderAvatarView.setImageDrawable(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_radio))
        } else {
            loadAvatar(result.uploaderAvatars).tag(PICASSO_PLAYLIST_TAG)
                .into(headerBinding!!.uploaderAvatarView)
        }

        headerBinding!!.playlistStreamCount.text = localizeStreamCount(requireContext(), result.streamCount)

        val description = result.description
        if (description != null && description !== Description.EMPTY_DESCRIPTION && !Utils.isBlank(description.content)) {
            val ellipsizer = TextEllipsizer(headerBinding!!.playlistDescription, 5, getServiceById(result.serviceId))
            ellipsizer.setStateChangeListener { isEllipsized: Boolean ->
                headerBinding!!.playlistDescriptionReadMore.setText(if (java.lang.Boolean.TRUE == isEllipsized) R.string.show_more else R.string.show_less
                )
            }
            ellipsizer.setOnContentChanged { canBeEllipsized: Boolean ->
                headerBinding!!.playlistDescriptionReadMore.visibility =
                    if (java.lang.Boolean.TRUE == canBeEllipsized) View.VISIBLE else View.GONE
                if (java.lang.Boolean.TRUE == canBeEllipsized) {
                    ellipsizer.ellipsize()
                }
            }
            ellipsizer.setContent(description)
            headerBinding!!.playlistDescriptionReadMore.setOnClickListener { v: View? -> ellipsizer.toggle() }
        } else {
            headerBinding!!.playlistDescription.visibility = View.GONE
            headerBinding!!.playlistDescriptionReadMore.visibility = View.GONE
        }

        if (!result.errors.isEmpty()) {
            showSnackBarError(ErrorInfo(result.errors, UserAction.REQUESTED_PLAYLIST, result.url, result))
        }

        remotePlaylistManager!!.getPlaylist(result)
            .flatMap(
                { lists: List<PlaylistRemoteEntity> ->
                    getUpdateProcessor(lists, result)
                },
                { lists: List<PlaylistRemoteEntity>, id: Int -> lists })
            .onBackpressureLatest()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(playlistBookmarkSubscriber)

        initPlaylistControlClickListener(requireActivity() as AppCompatActivity, playlistControlBinding!!, this)
    }

    override val playQueue: PlayQueue
        get() = getPlayQueue(0)

    private fun getPlayQueue(index: Int): PlayQueue {
        val infoItems: MutableList<StreamInfoItem> = ArrayList()
        for (i in infoListAdapter!!.itemsList) {
            if (i is StreamInfoItem) infoItems.add(i)
        }
        return PlaylistPlayQueue(currentInfo!!.serviceId, currentInfo!!.url, currentInfo!!.nextPage, infoItems, index)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    ////////////////////////////////////////////////////////////////////////// */
    private fun getUpdateProcessor(playlists: List<PlaylistRemoteEntity>, result: PlaylistInfo): Flowable<Int> {
        val noItemToUpdate = Flowable.just<Int>( /*noItemToUpdate=*/-1)
        if (playlists.isEmpty()) return noItemToUpdate

        val playlistRemoteEntity = playlists[0]
        if (playlistRemoteEntity.isIdenticalTo(result)) return noItemToUpdate

        return remotePlaylistManager!!.onUpdate(playlists[0].uid, result).toFlowable()
    }

    private val playlistBookmarkSubscriber: Subscriber<List<PlaylistRemoteEntity>>
        get() = object : Subscriber<List<PlaylistRemoteEntity>> {
            override fun onSubscribe(s: Subscription) {
                bookmarkReactor?.cancel()
                bookmarkReactor = s
                bookmarkReactor!!.request(1)
            }

            override fun onNext(playlist: List<PlaylistRemoteEntity>) {
                playlistEntity = if (playlist.isEmpty()) null else playlist[0]

                updateBookmarkButtons()
                isBookmarkButtonReady!!.set(true)
                bookmarkReactor?.request(1)
            }

            override fun onError(throwable: Throwable) {
                showError(ErrorInfo(throwable, UserAction.REQUESTED_BOOKMARK, "Get playlist bookmarks"))
            }

            override fun onComplete() {}
        }

    override fun setTitle(title: String) {
        super.setTitle(title)
        if (headerBinding != null) {
            headerBinding!!.playlistTitleView.text = title
        }
    }

    private fun onBookmarkClicked() {
        if (isBookmarkButtonReady == null || !isBookmarkButtonReady!!.get() || remotePlaylistManager == null) return

        val action: Disposable
        when {
            currentInfo != null && playlistEntity == null -> {
                action = remotePlaylistManager!!.onBookmark(currentInfo!!)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ ignored: Long? -> }, { throwable: Throwable? ->
                        showError(ErrorInfo(
                            throwable!!, UserAction.REQUESTED_BOOKMARK,
                            "Adding playlist bookmark"))
                    })
            }
            playlistEntity != null -> {
                action = remotePlaylistManager!!.deletePlaylist(playlistEntity!!.uid)
                    .observeOn(AndroidSchedulers.mainThread())
                    .doFinally { playlistEntity = null }
                    .subscribe({ ignored: Int? -> }, { throwable: Throwable? ->
                        showError(ErrorInfo(
                            throwable!!, UserAction.REQUESTED_BOOKMARK,
                            "Deleting playlist bookmark"))
                    })
            }
            else -> {
                action = Disposable.empty()
            }
        }

        disposables!!.add(action)
    }

    private fun updateBookmarkButtons() {
        if (playlistBookmarkButton == null || activity == null) return

        val drawable = if (playlistEntity == null) R.drawable.ic_playlist_add else R.drawable.ic_playlist_add_check

        val titleRes = if (playlistEntity == null) R.string.bookmark_playlist else R.string.unbookmark_playlist

        playlistBookmarkButton!!.setIcon(drawable)
        playlistBookmarkButton!!.setTitle(titleRes)
    }

    companion object {
        private const val PICASSO_PLAYLIST_TAG = "PICASSO_PLAYLIST_TAG"

        fun getInstance(serviceId: Int, url: String?, name: String?): PlaylistFragment {
            val instance = PlaylistFragment()
            instance.setInitialData(serviceId, url, name)
            return instance
        }
    }
}
