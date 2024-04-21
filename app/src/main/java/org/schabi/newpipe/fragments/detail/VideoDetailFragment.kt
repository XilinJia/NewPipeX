package org.schabi.newpipe.fragments.detail

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.view.View.OnLongClickListener
import android.view.View.OnTouchListener
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback
import icepick.State
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.App
import org.schabi.newpipe.R
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.databinding.FragmentVideoDetailBinding
import org.schabi.newpipe.download.DownloadDialog
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil.Companion.showSnackbar
import org.schabi.newpipe.error.ErrorUtil.Companion.showUiErrorSnackbar
import org.schabi.newpipe.error.ReCaptchaActivity
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.*
import org.schabi.newpipe.fragments.BackPressable
import org.schabi.newpipe.fragments.BaseStateFragment
import org.schabi.newpipe.fragments.EmptyFragment.Companion.newInstance
import org.schabi.newpipe.fragments.MainFragment
import org.schabi.newpipe.fragments.list.comments.CommentsFragment
import org.schabi.newpipe.fragments.list.videos.RelatedItemsFragment
import org.schabi.newpipe.ktx.AnimationType
import org.schabi.newpipe.ktx.animate
import org.schabi.newpipe.ktx.animateRotation
import org.schabi.newpipe.local.dialog.PlaylistDialog
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.local.playlist.LocalPlaylistFragment
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.Player.Companion.PLAYER_TYPE
import org.schabi.newpipe.player.PlayerService
import org.schabi.newpipe.player.PlayerType
import org.schabi.newpipe.player.event.OnKeyDownListener
import org.schabi.newpipe.player.event.PlayerServiceExtendedEventListener
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.player.helper.PlayerHolder
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.player.playqueue.events.PlayQueueEvent
import org.schabi.newpipe.player.ui.MainPlayerUi
import org.schabi.newpipe.player.ui.VideoPlayerUi
import org.schabi.newpipe.util.*
import org.schabi.newpipe.util.DependentPreferenceHelper.getResumePlaybackEnabled
import org.schabi.newpipe.util.DeviceUtils.getWindowHeight
import org.schabi.newpipe.util.DeviceUtils.isDesktopMode
import org.schabi.newpipe.util.DeviceUtils.isInMultiWindow
import org.schabi.newpipe.util.DeviceUtils.isLandscape
import org.schabi.newpipe.util.DeviceUtils.isTablet
import org.schabi.newpipe.util.DeviceUtils.isTv
import org.schabi.newpipe.util.ExtractorHelper.getStreamInfo
import org.schabi.newpipe.util.ExtractorHelper.isCached
import org.schabi.newpipe.util.ExtractorHelper.showMetaInfoInTextView
import org.schabi.newpipe.util.ListHelper.getDefaultAudioFormat
import org.schabi.newpipe.util.ListHelper.getDefaultResolutionIndex
import org.schabi.newpipe.util.ListHelper.getFilteredAudioStreams
import org.schabi.newpipe.util.ListHelper.getSortedStreamVideosList
import org.schabi.newpipe.util.ListHelper.getUrlAndNonTorrentStreams
import org.schabi.newpipe.util.Localization.audioTrackName
import org.schabi.newpipe.util.Localization.getDurationString
import org.schabi.newpipe.util.Localization.listeningCount
import org.schabi.newpipe.util.Localization.localizeViewCount
import org.schabi.newpipe.util.Localization.localizeWatchingCount
import org.schabi.newpipe.util.Localization.shortCount
import org.schabi.newpipe.util.Localization.shortSubscriberCount
import org.schabi.newpipe.util.NavigationHelper.enqueueOnPlayer
import org.schabi.newpipe.util.NavigationHelper.getPlayerIntent
import org.schabi.newpipe.util.NavigationHelper.openChannelFragment
import org.schabi.newpipe.util.NavigationHelper.openDownloads
import org.schabi.newpipe.util.NavigationHelper.openPlayQueue
import org.schabi.newpipe.util.NavigationHelper.openVideoDetailFragment
import org.schabi.newpipe.util.NavigationHelper.playOnBackgroundPlayer
import org.schabi.newpipe.util.NavigationHelper.playOnExternalPlayer
import org.schabi.newpipe.util.NavigationHelper.playOnPopupPlayer
import org.schabi.newpipe.util.PermissionHelper.checkStoragePermissions
import org.schabi.newpipe.util.PermissionHelper.isPopupEnabledElseAsk
import org.schabi.newpipe.util.PlayButtonHelper.shouldShowHoldToAppendTip
import org.schabi.newpipe.util.StreamTypeUtil.isLiveStream
import org.schabi.newpipe.util.ThemeHelper.resolveColorFromAttr
import org.schabi.newpipe.util.external_communication.KoreUtils.playWithKore
import org.schabi.newpipe.util.external_communication.KoreUtils.shouldShowPlayWithKodi
import org.schabi.newpipe.util.external_communication.ShareUtils.copyToClipboard
import org.schabi.newpipe.util.external_communication.ShareUtils.openUrlInBrowser
import org.schabi.newpipe.util.external_communication.ShareUtils.shareText
import org.schabi.newpipe.util.image.PicassoHelper.cancelTag
import org.schabi.newpipe.util.image.PicassoHelper.loadAvatar
import org.schabi.newpipe.util.image.PicassoHelper.loadDetailsThumbnail
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@UnstableApi class VideoDetailFragment
    : BaseStateFragment<StreamInfo>(), BackPressable, PlayerServiceExtendedEventListener, OnKeyDownListener {
    // tabs
    private var showComments = false
    private var showRelatedItems = false
    private var showDescription = false
    private var selectedTabTag: String? = null

    @AttrRes
    val tabIcons: MutableList<Int> = ArrayList()

    @StringRes
    val tabContentDescriptions: MutableList<Int> = ArrayList()
    private var tabSettingsChanged = false
    private var lastAppBarVerticalOffset = Int.MAX_VALUE // prevents useless updates

    private val preferenceChangeListener =
        OnSharedPreferenceChangeListener { sharedPreferences: SharedPreferences, key: String? ->
            when (key) {
                getString(R.string.show_comments_key) -> {
                    showComments = sharedPreferences.getBoolean(key, true)
                    tabSettingsChanged = true
                }
                getString(R.string.show_next_video_key) -> {
                    showRelatedItems = sharedPreferences.getBoolean(key, true)
                    tabSettingsChanged = true
                }
                getString(R.string.show_description_key) -> {
                    showDescription = sharedPreferences.getBoolean(key, true)
                    tabSettingsChanged = true
                }
            }
        }

    @JvmField
    @State
    var serviceId: Int = NO_SERVICE_ID

    @JvmField
    @State
    var title: String = ""

    @JvmField
    @State
    var url: String? = null
    protected var playQueue: PlayQueue? = null

    @JvmField
    @State
    var bottomSheetState: Int = BottomSheetBehavior.STATE_EXPANDED

    @JvmField
    @State
    var lastStableBottomSheetState: Int = BottomSheetBehavior.STATE_EXPANDED

    @JvmField
    @State
    var autoPlayEnabled: Boolean = true

    private var currentInfo: StreamInfo? = null
    private var currentWorker: Disposable? = null
    private val disposables = CompositeDisposable()
    private var positionSubscriber: Disposable? = null

    private var bottomSheetBehavior: BottomSheetBehavior<FrameLayout>? = null
    private var bottomSheetCallback: BottomSheetCallback? = null
    private var broadcastReceiver: BroadcastReceiver? = null

    /*//////////////////////////////////////////////////////////////////////////
    // Views
    ////////////////////////////////////////////////////////////////////////// */
    private var binding_: FragmentVideoDetailBinding? = null
    private val binding get() = binding_!!
    private lateinit var pageAdapter: TabAdapter

    private var settingsContentObserver: ContentObserver? = null
    private var playerService: PlayerService? = null
    private var player: Player? = null
    private val playerHolder: PlayerHolder = PlayerHolder.instance!!

    /*//////////////////////////////////////////////////////////////////////////
    // Service management
    ////////////////////////////////////////////////////////////////////////// */
    override fun onServiceConnected(connectedPlayer: Player?,
                                    connectedPlayerService: PlayerService?,
                                    playAfterConnect: Boolean) {
        player = connectedPlayer
        playerService = connectedPlayerService

        // It will do nothing if the player is not in fullscreen mode
        hideSystemUiIfNeeded()

        val playerUi = player!!.UIs().get(MainPlayerUi::class.java)
        if (!player!!.videoPlayerSelected() && !playAfterConnect) return

        when {
            isLandscape(requireContext()) -> {
                // If the video is playing but orientation changed
                // let's make the video in fullscreen again
                checkLandscape()
            }
            playerUi.map { ui: MainPlayerUi -> ui.isFullscreen && !ui.isVerticalVideo }
                .orElse(false) && !isTablet(requireActivity()) -> {
                // Tablet UI has orientation-independent fullscreen
                // Device is in portrait orientation after rotation but UI is in fullscreen.
                // Return back to non-fullscreen state
                playerUi.ifPresent { obj: MainPlayerUi -> obj.toggleFullscreen() }
            }
        }

        if (playAfterConnect || (currentInfo != null && isAutoplayEnabled && playerUi.isEmpty)) {
            autoPlayEnabled = true // forcefully start playing
            openVideoPlayerAutoFullscreen()
        }
        updateOverlayPlayQueueButtonVisibility()
    }

    override fun onServiceDisconnected() {
        playerService = null
        player = null
        restoreDefaultBrightness()
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Fragment's Lifecycle
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireActivity())
        showComments = prefs.getBoolean(getString(R.string.show_comments_key), true)
        showRelatedItems = prefs.getBoolean(getString(R.string.show_next_video_key), true)
        showDescription = prefs.getBoolean(getString(R.string.show_description_key), true)
        selectedTabTag = prefs.getString(getString(R.string.stream_info_selected_tab_key), COMMENTS_TAB_TAG)
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        setupBroadcastReceiver()

        settingsContentObserver = object : ContentObserver(Handler()) {
            override fun onChange(selfChange: Boolean) {
                if (!PlayerHelper.globalScreenOrientationLocked(requireContext())) {
                    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
        requireActivity().contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, settingsContentObserver!!)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding_ = FragmentVideoDetailBinding.inflate(inflater, container, false)
        Log.d(TAG, "onCreateView")
        return binding.root
    }

    override fun onPause() {
        super.onPause()
        currentWorker?.dispose()

        restoreDefaultBrightness()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .edit()
            .putString(getString(R.string.stream_info_selected_tab_key), pageAdapter.getItemTitle(binding.viewPager.currentItem))
            .apply()
    }

    override fun onResume() {
        super.onResume()
        if (DEBUG) {
            Log.d(TAG, "onResume() called")
        }

        requireActivity().sendBroadcast(Intent(ACTION_VIDEO_FRAGMENT_RESUMED))

        updateOverlayPlayQueueButtonVisibility()

        setupBrightness()

        if (tabSettingsChanged) {
            tabSettingsChanged = false
            initTabs()
            if (currentInfo != null) updateTabs(currentInfo!!)
        }

        // Check if it was loading when the fragment was stopped/paused
        if (wasLoading.getAndSet(false) && !wasCleared()) {
            startLoading(false)
        }
    }

    override fun onStop() {
        super.onStop()

        if (!requireActivity().isChangingConfigurations) {
            requireActivity().sendBroadcast(Intent(ACTION_VIDEO_FRAGMENT_STOPPED))
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Stop the service when user leaves the app with double back press
        // if video player is selected. Otherwise unbind
        if (requireActivity().isFinishing && isPlayerAvailable && player!!.videoPlayerSelected()) {
            playerHolder.stopService()
        } else {
            playerHolder.setListener(null)
        }

        PreferenceManager.getDefaultSharedPreferences(requireActivity())
            .unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        requireActivity().unregisterReceiver(broadcastReceiver)
        requireActivity().contentResolver.unregisterContentObserver(settingsContentObserver!!)

        positionSubscriber?.dispose()
        currentWorker?.dispose()

        disposables.clear()
        positionSubscriber = null
        currentWorker = null
        bottomSheetBehavior!!.removeBottomSheetCallback(bottomSheetCallback!!)

        if (requireActivity().isFinishing) {
            playQueue = null
            currentInfo = null
            stack = LinkedList()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding_ = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            ReCaptchaActivity.RECAPTCHA_REQUEST -> if (resultCode == Activity.RESULT_OK) {
                openVideoDetailFragment(requireContext(), fM!!, serviceId, url, title, null, false)
            } else {
                Log.e(TAG, "ReCaptcha failed")
            }
            else -> Log.e(TAG, "Request code from activity not supported [$requestCode]")
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // OnClick
    ////////////////////////////////////////////////////////////////////////// */
    private fun setOnClickListeners() {
        binding.detailTitleRootLayout.setOnClickListener { v: View? -> toggleTitleAndSecondaryControls() }
        binding.detailUploaderRootLayout.setOnClickListener(makeOnClickListener { info: StreamInfo ->
            if (info.subChannelUrl.isEmpty()) {
                if (info.uploaderUrl.isNotEmpty()) {
                    openChannel(info.uploaderUrl, info.uploaderName)
                }

                if (DEBUG) {
                    Log.i(TAG, "Can't open sub-channel because we got no channel URL")
                }
            } else {
                openChannel(info.subChannelUrl, info.subChannelName)
            }
        })
        binding.detailThumbnailRootLayout.setOnClickListener { v: View? ->
            autoPlayEnabled = true // forcefully start playing
            // FIXME Workaround #7427
            if (isPlayerAvailable) {
                player!!.setRecovery()
            }
            openVideoPlayerAutoFullscreen()
        }

        binding.detailControlsBackground.setOnClickListener { v: View? -> openBackgroundPlayer(false) }
        binding.detailControlsPopup.setOnClickListener { v: View? -> openPopupPlayer(false) }
        binding.detailControlsPlaylistAppend.setOnClickListener(makeOnClickListener { info: StreamInfo? ->
            if (fM != null && currentInfo != null) {
                val fragment = parentFragmentManager.findFragmentById(R.id.fragment_holder)

                // commit previous pending changes to database
                when (fragment) {
                    is LocalPlaylistFragment -> {
                        fragment.commitChanges()
                    }
                    is MainFragment -> {
                        fragment.commitPlaylistTabs()
                    }
                }

                disposables.add(PlaylistDialog.createCorrespondingDialog(requireContext(), listOf(StreamEntity(info!!))) { dialog ->
                    dialog.show(parentFragmentManager, TAG) })
            }
        })
        binding.detailControlsDownload.setOnClickListener { v: View? ->
            if (checkStoragePermissions(requireActivity(), PermissionHelper.DOWNLOAD_DIALOG_REQUEST_CODE)) {
                openDownloadDialog()
            }
        }
        binding.detailControlsShare.setOnClickListener(makeOnClickListener { info: StreamInfo ->
            shareText(requireContext(), info.name, info.url, info.thumbnails)
        })
        binding.detailControlsOpenInBrowser.setOnClickListener(makeOnClickListener { info: StreamInfo ->
            openUrlInBrowser(requireContext(), info.url)
        })
        binding.detailControlsPlayWithKodi.setOnClickListener(makeOnClickListener { info: StreamInfo ->
            playWithKore(requireContext(), Uri.parse(info.url))
        })
        if (DEBUG) {
            binding.detailControlsCrashThePlayer.setOnClickListener { v: View? ->
                VideoDetailPlayerCrasher.onCrashThePlayer(requireContext(), player)
            }
        }

        val overlayListener = View.OnClickListener { v: View? ->
            bottomSheetBehavior?.setState(BottomSheetBehavior.STATE_EXPANDED)
        }
        binding.overlayThumbnail.setOnClickListener(overlayListener)
        binding.overlayMetadataLayout.setOnClickListener(overlayListener)
        binding.overlayButtonsLayout.setOnClickListener(overlayListener)
        binding.overlayCloseButton.setOnClickListener { v: View? ->
//            bottomSheetBehavior?.setState(BottomSheetBehavior.STATE_HIDDEN)
            bottomSheetBehavior?.setState(BottomSheetBehavior.STATE_COLLAPSED)
        }
        binding.overlayPlayQueueButton.setOnClickListener { v: View? -> openPlayQueue(requireContext()) }
        binding.overlayPlayPauseButton.setOnClickListener { v: View? ->
            if (playerIsNotStopped()) {
                player!!.playPause()
                player!!.UIs().get(VideoPlayerUi::class.java).ifPresent { ui: VideoPlayerUi -> ui.hideControls(0, 0) }
                showSystemUi()
            } else {
                autoPlayEnabled = true // forcefully start playing
                openVideoPlayer(false)
            }
            setOverlayPlayPauseImage(isPlayerAvailable && player!!.isPlaying)
        }
    }

    private fun makeOnClickListener(consumer: Consumer<StreamInfo>): View.OnClickListener {
        return View.OnClickListener { v: View? ->
            if (!isLoading.get() && currentInfo != null) {
                consumer.accept(currentInfo!!)
            }
        }
    }

    private fun setOnLongClickListeners() {
        binding.detailTitleRootLayout.setOnLongClickListener(makeOnLongClickListener { info: StreamInfo? ->
            copyToClipboard(requireContext(), binding.detailVideoTitleView.text.toString())
        })
        binding.detailUploaderRootLayout.setOnLongClickListener(makeOnLongClickListener { info: StreamInfo ->
            if (info.subChannelUrl.isEmpty()) {
                Log.w(TAG, "Can't open parent channel because we got no parent channel URL")
            } else {
                openChannel(info.uploaderUrl, info.uploaderName)
            }
        })

        binding.detailControlsBackground.setOnLongClickListener(makeOnLongClickListener { info: StreamInfo? ->
            openBackgroundPlayer(true)
        })
        binding.detailControlsPopup.setOnLongClickListener(makeOnLongClickListener { info: StreamInfo? ->
            openPopupPlayer(true)
        })
        binding.detailControlsDownload.setOnLongClickListener(makeOnLongClickListener { info: StreamInfo? ->
            openDownloads(requireActivity())
        })

        val overlayListener = makeOnLongClickListener { info: StreamInfo -> openChannel(info.uploaderUrl, info.uploaderName) }
        binding.overlayThumbnail.setOnLongClickListener(overlayListener)
        binding.overlayMetadataLayout.setOnLongClickListener(overlayListener)
    }

    private fun makeOnLongClickListener(consumer: Consumer<StreamInfo>): OnLongClickListener {
        return OnLongClickListener { v: View? ->
            if (isLoading.get() || currentInfo == null) return@OnLongClickListener false
            consumer.accept(currentInfo!!)
            true
        }
    }

    private fun openChannel(subChannelUrl: String, subChannelName: String) {
        try {
            openChannelFragment(fM!!, currentInfo!!.serviceId, subChannelUrl, subChannelName)
        } catch (e: Exception) {
            showUiErrorSnackbar(this, "Opening channel fragment", e)
        }
    }

    private fun toggleTitleAndSecondaryControls() {
        if (binding.detailSecondaryControlPanel.visibility == View.GONE) {
            binding.detailVideoTitleView.maxLines = 10
            binding.detailToggleSecondaryControlsView.animateRotation(VideoPlayerUi.DEFAULT_CONTROLS_DURATION, 180)
            binding.detailSecondaryControlPanel.visibility = View.VISIBLE
        } else {
            binding.detailVideoTitleView.maxLines = 1
            binding.detailToggleSecondaryControlsView.animateRotation(VideoPlayerUi.DEFAULT_CONTROLS_DURATION, 0)
            binding.detailSecondaryControlPanel.visibility = View.GONE
        }
        // view pager height has changed, update the tab layout
        updateTabLayoutVisibility()
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Init
    ////////////////////////////////////////////////////////////////////////// */
    // called from onViewCreated in {@link BaseFragment#onViewCreated}
    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)

        pageAdapter = TabAdapter(childFragmentManager)
        binding.viewPager.adapter = pageAdapter
        binding.tabLayout.setupWithViewPager(binding.viewPager)

        binding.detailThumbnailRootLayout.requestFocus()

        binding.detailControlsPlayWithKodi.visibility =
            if (shouldShowPlayWithKodi(requireContext(), serviceId)) View.VISIBLE else View.GONE
        binding.detailControlsCrashThePlayer.visibility =
            if (DEBUG && PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .getBoolean(getString(R.string.show_crash_the_player_key), false)) View.VISIBLE else View.GONE
        accommodateForTvAndDesktopMode()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun initListeners() {
        super.initListeners()

        setOnClickListeners()
        setOnLongClickListeners()

        val controlsTouchListener = OnTouchListener { view: View?, motionEvent: MotionEvent ->
            if (motionEvent.action == MotionEvent.ACTION_DOWN && shouldShowHoldToAppendTip(requireActivity())) {
                binding.touchAppendDetail.animate(true, 250, AnimationType.ALPHA, 0) {
                    binding.touchAppendDetail.animate(false, 1500, AnimationType.ALPHA, 1000)
                }
            }
            false
        }
        binding.detailControlsBackground.setOnTouchListener(controlsTouchListener)
        binding.detailControlsPopup.setOnTouchListener(controlsTouchListener)

        binding.appBarLayout.addOnOffsetChangedListener { layout: AppBarLayout?, verticalOffset: Int ->
            // prevent useless updates to tab layout visibility if nothing changed
            if (verticalOffset != lastAppBarVerticalOffset) {
                lastAppBarVerticalOffset = verticalOffset
                // the view was scrolled
                updateTabLayoutVisibility()
            }
        }

        setupBottomPlayer()
        if (!playerHolder.isBound) {
            setHeightThumbnail()
        } else {
            playerHolder.startService(false, this)
        }
    }

    override fun onKeyDown(keyCode: Int): Boolean {
        return (isPlayerAvailable && player!!.UIs().get(VideoPlayerUi::class.java)
            .map { playerUi: VideoPlayerUi -> playerUi.onKeyDown(keyCode) }.orElse(false))
    }

    override fun onBackPressed(): Boolean {
        if (DEBUG) {
            Log.d(TAG, "onBackPressed() called")
        }

        // If we are in fullscreen mode just exit from it via first back press
        if (isFullscreen) {
            if (!isTablet(requireActivity())) {
                player!!.pause()
            }
            restoreDefaultOrientation()
            setAutoPlay(false)
            return true
        }

        // If we have something in history of played items we replay it here
        if (isPlayerAvailable && player!!.playQueue != null && player!!.videoPlayerSelected() && player!!.playQueue!!.previous()) {
            return true // no code here, as previous() was used in the if
        }

        // That means that we are on the start of the stack,
        if (stack.size <= 1) {
            restoreDefaultOrientation()
            return false // let MainActivity handle the onBack (e.g. to minimize the mini player)
        }

        // Remove top
        stack.pop()
        // Get stack item from the new top
        setupFromHistoryItem(Objects.requireNonNull(stack.peek()))

        return true
    }

    private fun setupFromHistoryItem(item: StackItem) {
        setAutoPlay(false)
        hideMainPlayerOnLoadingNewStream()

        setInitialData(item.serviceId, item.url, item.title, item.playQueue)
        startLoading(false)

        // Maybe an item was deleted in background activity
        if (item.playQueue.item == null) return

        val playQueueItem = item.playQueue.item
        // Update title, url, uploader from the last item in the stack (it's current now)
        val isPlayerStopped = !isPlayerAvailable || player!!.isStopped
        if (playQueueItem != null && isPlayerStopped) {
            updateOverlayData(playQueueItem.title, playQueueItem.uploader, playQueueItem.thumbnails)
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Info loading and handling
    ////////////////////////////////////////////////////////////////////////// */
    override fun doInitialLoadLogic() {
        if (wasCleared()) return

        if (currentInfo == null) {
            prepareAndLoadInfo()
        } else {
            prepareAndHandleInfoIfNeededAfterDelay(currentInfo, false, 50)
        }
    }

    fun selectAndLoadVideo(newServiceId: Int, newUrl: String?, newTitle: String, newQueue: PlayQueue?) {
        if (isPlayerAvailable && newQueue != null && playQueue?.item?.url != newUrl) {
            // Preloading can be disabled since playback is surely being replaced.
            player!!.disablePreloadingOfCurrentTrack()
        }
        setInitialData(newServiceId, newUrl, newTitle, newQueue)
        startLoading(false, true)
    }

    private fun prepareAndHandleInfoIfNeededAfterDelay(info: StreamInfo?, scrollToTop: Boolean, delay: Long) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (activity == null) return@postDelayed
            // Data can already be drawn, don't spend time twice
            if (info!!.name == binding.detailVideoTitleView.text.toString()) return@postDelayed
            prepareAndHandleInfo(info, scrollToTop)
        }, delay)
    }

    private fun prepareAndHandleInfo(info: StreamInfo, scrollToTop: Boolean) {
        if (DEBUG) {
            Log.d(TAG, "prepareAndHandleInfo() called with: info = [$info], scrollToTop = [$scrollToTop]")
        }
        showLoading()
        initTabs()

        if (scrollToTop) scrollToTop()

        handleResult(info)
        showContent()
    }

    protected fun prepareAndLoadInfo() {
        scrollToTop()
        startLoading(false)
    }

    public override fun startLoading(forceLoad: Boolean) {
        super.startLoading(forceLoad)

        initTabs()
        currentInfo = null
        currentWorker?.dispose()

        runWorker(forceLoad, stack.isEmpty())
    }

    private fun startLoading(forceLoad: Boolean, addToBackStack: Boolean) {
        super.startLoading(forceLoad)

        initTabs()
        currentInfo = null
        currentWorker?.dispose()

        runWorker(forceLoad, addToBackStack)
    }

    private fun runWorker(forceLoad: Boolean, addToBackStack: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireActivity())
        currentWorker = getStreamInfo(serviceId, url!!, forceLoad)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ result: StreamInfo ->
                isLoading.set(false)
                hideMainPlayerOnLoadingNewStream()
                if (result.ageLimit != StreamExtractor.NO_AGE_LIMIT && !prefs.getBoolean(getString(R.string.show_age_restricted_content), false)) {
                    hideAgeRestrictedContent()
                } else {
                    handleResult(result)
                    showContent()
                    if (addToBackStack) {
                        if (playQueue == null) playQueue = SinglePlayQueue(result)

                        if (stack.isEmpty() || stack.peek()?.playQueue?.equalStreams(playQueue) != true) {
                            stack.push(StackItem(serviceId, url!!, title, playQueue!!))
                        }
                    }

                    if (isAutoplayEnabled) {
                        openVideoPlayerAutoFullscreen()
                    }
                }
            }, { throwable: Throwable? ->
                showError(ErrorInfo(throwable!!, UserAction.REQUESTED_STREAM, url ?: "no url", serviceId))
            })
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Tabs
    ////////////////////////////////////////////////////////////////////////// */
    private fun initTabs() {
        if (pageAdapter.count != 0) {
            selectedTabTag = pageAdapter.getItemTitle(binding.viewPager.currentItem)
        }
        pageAdapter.clearAllItems()
        tabIcons.clear()
        tabContentDescriptions.clear()

        if (shouldShowComments()) {
            pageAdapter.addFragment(CommentsFragment.getInstance(serviceId, url, title), COMMENTS_TAB_TAG)
            tabIcons.add(R.drawable.ic_comment)
            tabContentDescriptions.add(R.string.comments_tab_description)
        }

        if (showRelatedItems && binding.relatedItemsLayout == null) {
            // temp empty fragment. will be updated in handleResult
            pageAdapter.addFragment(newInstance(false), RELATED_TAB_TAG)
            tabIcons.add(R.drawable.ic_art_track)
            tabContentDescriptions.add(R.string.related_items_tab_description)
        }

        if (showDescription) {
            // temp empty fragment. will be updated in handleResult
            pageAdapter.addFragment(newInstance(false), DESCRIPTION_TAB_TAG)
            tabIcons.add(R.drawable.ic_description)
            tabContentDescriptions.add(R.string.description_tab_description)
        }

        if (pageAdapter.count == 0) {
            pageAdapter.addFragment(newInstance(true), EMPTY_TAB_TAG)
        }
        pageAdapter.notifyDataSetUpdate()

        if (pageAdapter.count >= 2) {
            val position = pageAdapter.getItemPositionByTitle(selectedTabTag!!)
            if (position != -1) {
                binding.viewPager.currentItem = position
            }
            updateTabIconsAndContentDescriptions()
        }
        // the page adapter now contains tabs: show the tab layout
        updateTabLayoutVisibility()
    }

    /**
     * To be called whenever [.pageAdapter] is modified, since that triggers a refresh in
     * [FragmentVideoDetailBinding.tabLayout] resetting all tab's icons and content
     * descriptions. This reads icons from [.tabIcons] and content descriptions from
     * [.tabContentDescriptions], which are all set in [.initTabs].
     */
    private fun updateTabIconsAndContentDescriptions() {
        for (i in tabIcons.indices) {
            val tab = binding.tabLayout.getTabAt(i)
            tab?.setIcon(tabIcons[i])
            tab?.setContentDescription(tabContentDescriptions[i])
        }
    }

    private fun updateTabs(info: StreamInfo) {
        if (showRelatedItems) {
            if (binding.relatedItemsLayout == null) { // phone
                pageAdapter.updateItem(RELATED_TAB_TAG, RelatedItemsFragment.getInstance(info))
            } else { // tablet + TV
                childFragmentManager.beginTransaction()
                    .replace(R.id.relatedItemsLayout, RelatedItemsFragment.getInstance(info))
                    .commitAllowingStateLoss()
                binding.relatedItemsLayout!!.visibility = if (isFullscreen) View.GONE else View.VISIBLE
            }
        }

        if (showDescription) {
            pageAdapter.updateItem(DESCRIPTION_TAB_TAG, DescriptionFragment(info))
        }

        binding.viewPager.visibility = View.VISIBLE
        // make sure the tab layout is visible
        updateTabLayoutVisibility()
        pageAdapter.notifyDataSetUpdate()
        updateTabIconsAndContentDescriptions()
    }

    private fun shouldShowComments(): Boolean {
        return try {
            showComments && NewPipe.getService(serviceId)
                .serviceInfo
                .mediaCapabilities
                .contains(MediaCapability.COMMENTS)
        } catch (e: ExtractionException) {
            false
        }
    }

    fun updateTabLayoutVisibility() {
        //If binding is null we do not need to and should not do anything with its object(s)
        if (binding_ == null) return

        if (pageAdapter.count < 2 || binding.viewPager.visibility != View.VISIBLE) {
            // hide tab layout if there is only one tab or if the view pager is also hidden
            binding.tabLayout.visibility = View.GONE
        } else {
            // call `post()` to be sure `viewPager.getHitRect()`
            // is up to date and not being currently recomputed
            binding.tabLayout.post {
                val activity = getActivity()
                if (activity != null) {
                    val pagerHitRect = Rect()
                    binding.viewPager.getHitRect(pagerHitRect)

                    val height = getWindowHeight(activity.windowManager)
                    val viewPagerVisibleHeight = height - pagerHitRect.top
                    // see TabLayout.DEFAULT_HEIGHT, which is equal to 48dp
                    val tabLayoutHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics)

                    if (viewPagerVisibleHeight > tabLayoutHeight * 2) {
                        // no translation at all when viewPagerVisibleHeight > tabLayout.height * 3
                        binding.tabLayout.translationY = max(0.0, (tabLayoutHeight * 3 - viewPagerVisibleHeight).toDouble()).toFloat()
                        binding.tabLayout.visibility = View.VISIBLE
                    } else {
                        // view pager is not visible enough
                        binding.tabLayout.visibility = View.GONE
                    }
                }
            }
        }
    }

    fun scrollToTop() {
        binding.appBarLayout.setExpanded(true, true)
        // notify tab layout of scrolling
        updateTabLayoutVisibility()
    }

    fun scrollToComment(comment: CommentsInfoItem) {
        val commentsTabPos = pageAdapter.getItemPositionByTitle(COMMENTS_TAB_TAG)
        val fragment = pageAdapter.getItem(commentsTabPos) as? CommentsFragment ?: return

        // unexpand the app bar only if scrolling to the comment succeeded
        if (fragment.scrollToComment(comment)) {
            binding.appBarLayout.setExpanded(false, false)
            binding.viewPager.setCurrentItem(commentsTabPos, false)
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Play Utils
    ////////////////////////////////////////////////////////////////////////// */
    private fun toggleFullscreenIfInFullscreenMode() {
        // If a user watched video inside fullscreen mode and than chose another player
        // return to non-fullscreen mode
        if (isPlayerAvailable) {
            player!!.UIs().get(MainPlayerUi::class.java).ifPresent { playerUi: MainPlayerUi ->
                if (playerUi.isFullscreen) {
                    playerUi.toggleFullscreen()
                }
            }
        }
    }

    private fun openBackgroundPlayer(append: Boolean) {
        val useExternalAudioPlayer = PreferenceManager
            .getDefaultSharedPreferences(requireActivity())
            .getBoolean(requireActivity().getString(R.string.use_external_audio_player_key), false)

        toggleFullscreenIfInFullscreenMode()

        if (isPlayerAvailable) {
            // FIXME Workaround #7427
            player!!.setRecovery()
        }

        if (useExternalAudioPlayer) {
            showExternalAudioPlaybackDialog()
        } else {
            openNormalBackgroundPlayer(append)
        }
    }

    private fun openPopupPlayer(append: Boolean) {
        if (!isPopupEnabledElseAsk(requireActivity())) return

        Log.d(TAG, "openPopupPlayer called")
        // See UI changes while remote playQueue changes
        if (!isPlayerAvailable) {
            playerHolder.startService(false, this)
        } else {
            // FIXME Workaround #7427
            player!!.setRecovery()
        }

        toggleFullscreenIfInFullscreenMode()

        val queue = setupPlayQueueForIntent(append)
        if (append) { //resumePlayback: false
            enqueueOnPlayer(requireActivity(), queue, PlayerType.POPUP)
        } else {
            replaceQueueIfUserConfirms { playOnPopupPlayer(requireActivity(), queue, true) }
        }
    }

    /**
     * Opens the video player, in fullscreen if needed. In order to open fullscreen, the activity
     * is toggled to landscape orientation (which will then cause fullscreen mode).
     *
     * @param directlyFullscreenIfApplicable whether to open fullscreen if we are not already
     * in landscape and screen orientation is locked
     */
    fun openVideoPlayer(directlyFullscreenIfApplicable: Boolean) {
        if (directlyFullscreenIfApplicable && !isLandscape(requireContext()) && PlayerHelper.globalScreenOrientationLocked(requireContext())) {
            // Make sure the bottom sheet turns out expanded. When this code kicks in the bottom
            // sheet could not have fully expanded yet, and thus be in the STATE_SETTLING state.
            // When the activity is rotated, and its state is saved and then restored, the bottom
            // sheet would forget what it was doing, since even if STATE_SETTLING is restored, it
            // doesn't tell which state it was settling to, and thus the bottom sheet settles to
            // STATE_COLLAPSED. This can be solved by manually setting the state that will be
            // restored (i.e. bottomSheetState) to STATE_EXPANDED.
            updateBottomSheetState(BottomSheetBehavior.STATE_EXPANDED)
            // toggle landscape in order to open directly in fullscreen
            onScreenRotationButtonClicked()
        }

        if (PreferenceManager.getDefaultSharedPreferences(requireActivity()).getBoolean(this.getString(R.string.use_external_video_player_key), false)) {
            showExternalVideoPlaybackDialog()
        } else {
            replaceQueueIfUserConfirms { this.openMainPlayer() }
        }
    }

    /**
     * If the option to start directly fullscreen is enabled, calls
     * [.openVideoPlayer] with `directlyFullscreenIfApplicable = true`, so that
     * if the user is not already in landscape and he has screen orientation locked the activity
     * rotates and fullscreen starts. Otherwise, if the option to start directly fullscreen is
     * disabled, calls [.openVideoPlayer] with `directlyFullscreenIfApplicable
     * = false`, hence preventing it from going directly fullscreen.
     */
    fun openVideoPlayerAutoFullscreen() {
        openVideoPlayer(PlayerHelper.isStartMainPlayerFullscreenEnabled(requireContext()))
    }

    private fun openNormalBackgroundPlayer(append: Boolean) {
        Log.d(TAG, "openNormalBackgroundPlayer called")

        // See UI changes while remote playQueue changes
        if (!isPlayerAvailable) {
            playerHolder.startService(false, this)
        }

        val queue = setupPlayQueueForIntent(append)
        if (append) {
            enqueueOnPlayer(requireActivity(), queue, PlayerType.AUDIO)
        } else {
            replaceQueueIfUserConfirms { playOnBackgroundPlayer(requireActivity(), queue, true) }
        }
    }

    private fun openMainPlayer() {
        Log.d(TAG, "openMainPlayer called")

        if (!isPlayerServiceAvailable) {
            playerHolder.startService(autoPlayEnabled, this)
            return
        }
        if (currentInfo == null) return

        val queue = setupPlayQueueForIntent(false)
        tryAddVideoPlayerView()

        val playerIntent = getPlayerIntent(requireContext(), PlayerService::class.java, queue, true, autoPlayEnabled)
//        playerIntent.putExtra(FOREGROUND_SERVICE_TYPE, FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
//        playerIntent.putExtra(PLAYER_TYPE, "mediaPlayback")
        ContextCompat.startForegroundService(requireActivity(), playerIntent)
    }

    /**
     * When the video detail fragment is already showing details for a video and the user opens a
     * new one, the video detail fragment changes all of its old data to the new stream, so if there
     * is a video player currently open it should be hidden. This method does exactly that. If
     * autoplay is enabled, the underlying player is not stopped completely, since it is going to
     * be reused in a few milliseconds and the flickering would be annoying.
     */
    private fun hideMainPlayerOnLoadingNewStream() {
        val root = this.root
        if (!isPlayerServiceAvailable || root.isEmpty || !player!!.videoPlayerSelected()) return

        removeVideoPlayerView()
        if (isAutoplayEnabled) {
            playerService!!.stopForImmediateReusing()
            root.ifPresent { view: View -> view.visibility = View.GONE }
        } else {
            playerHolder.stopService()
        }
    }

    private fun setupPlayQueueForIntent(append: Boolean): PlayQueue {
        if (append) return SinglePlayQueue(currentInfo)

        var queue = playQueue
        // Size can be 0 because queue removes bad stream automatically when error occurs
        if (queue == null || queue.isEmpty) {
            queue = SinglePlayQueue(currentInfo)
        }

        return queue
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    ////////////////////////////////////////////////////////////////////////// */
    fun setAutoPlay(autoPlay: Boolean) {
        this.autoPlayEnabled = autoPlay
    }

    private fun startOnExternalPlayer(context: Context, info: StreamInfo, selectedStream: Stream) {
        playOnExternalPlayer(context, currentInfo!!.name, currentInfo!!.subChannelName, selectedStream)

        val recordManager = HistoryRecordManager(requireContext())
        disposables.add(recordManager.onViewed(info).onErrorComplete()
            .subscribe({ ignored: Long? -> },
                { error: Throwable? -> Log.e(TAG, "Register view failure: ", error) }
            ))
    }

    private val isExternalPlayerEnabled: Boolean
        get() = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getBoolean(getString(R.string.use_external_video_player_key), false)

    private val isAutoplayEnabled: Boolean
        // This method overrides default behaviour when setAutoPlay() is called.
        get() = (autoPlayEnabled && !isExternalPlayerEnabled && (!isPlayerAvailable || player!!.videoPlayerSelected()))
                && bottomSheetState != BottomSheetBehavior.STATE_HIDDEN && PlayerHelper.isAutoplayAllowedByUser(requireContext())

    private fun tryAddVideoPlayerView() {
        if (isPlayerAvailable && view != null) {
            // Setup the surface view height, so that it fits the video correctly; this is done also
            // here, and not only in the Handler, to avoid a choppy fullscreen rotation animation.
            setHeightThumbnail()
        }

        // do all the null checks in the posted lambda, too, since the player, the binding and the
        // view could be set or unset before the lambda gets executed on the next main thread cycle
        Handler(Looper.getMainLooper()).post {
            if (!isPlayerAvailable || view == null) return@post

            // setup the surface view height, so that it fits the video correctly
            setHeightThumbnail()
            player!!.UIs().get(MainPlayerUi::class.java).ifPresent { playerUi: MainPlayerUi ->
                // sometimes binding would be null here, even though getView() != null above u.u
                if (binding != null) {
                    // prevent from re-adding a view multiple times
                    playerUi.removeViewFromParent()
                    binding.playerPlaceholder.addView(playerUi.binding.root)
                    playerUi.setupVideoSurfaceIfNeeded()
                }
            }
        }
    }

    private fun removeVideoPlayerView() {
        makeDefaultHeightForVideoPlaceholder()
        player?.UIs()?.get(VideoPlayerUi::class.java)?.ifPresent { obj: VideoPlayerUi -> obj.removeViewFromParent() }
    }

    private fun makeDefaultHeightForVideoPlaceholder() {
        if (view == null) return

        binding.playerPlaceholder.layoutParams.height = FrameLayout.LayoutParams.MATCH_PARENT
        binding.playerPlaceholder.requestLayout()
    }

    private val preDrawListener: ViewTreeObserver.OnPreDrawListener = object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            val metrics = resources.displayMetrics

            if (view != null) {
                val act = requireActivity()
                val height = (if (isInMultiWindow(act as AppCompatActivity)) requireView() else act.window.decorView).height
                setHeightThumbnail(height, metrics)
                view!!.viewTreeObserver.removeOnPreDrawListener(this)
            }
            return false
        }
    }

    /**
     * Method which controls the size of thumbnail and the size of main player inside
     * a layout with thumbnail. It decides what height the player should have in both
     * screen orientations. It knows about multiWindow feature
     * and about videos with aspectRatio ZOOM (the height for them will be a bit higher,
     * [.MAX_PLAYER_HEIGHT])
     */
    private fun setHeightThumbnail() {
        val metrics = resources.displayMetrics
        val isPortrait = metrics.heightPixels > metrics.widthPixels
        requireView().viewTreeObserver.removeOnPreDrawListener(preDrawListener)

        val act = requireActivity()
        if (isFullscreen) {
            val height = (if (isInMultiWindow(act as AppCompatActivity)) requireView() else act.window.decorView).height
            // Height is zero when the view is not yet displayed like after orientation change
            if (height != 0) {
                setHeightThumbnail(height, metrics)
            } else {
                requireView().viewTreeObserver.addOnPreDrawListener(preDrawListener)
            }
        } else {
            val height = (if (isPortrait) metrics.widthPixels / (16.0f / 9.0f) else metrics.heightPixels / 2.0f).toInt()
            setHeightThumbnail(height, metrics)
        }
    }

    private fun setHeightThumbnail(newHeight: Int, metrics: DisplayMetrics) {
        binding.detailThumbnailImageView.layoutParams = FrameLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, newHeight)
        binding.detailThumbnailImageView.minimumHeight = newHeight
        if (isPlayerAvailable) {
            val maxHeight = (metrics.heightPixels * MAX_PLAYER_HEIGHT).toInt()
            player!!.UIs().get(VideoPlayerUi::class.java).ifPresent { ui: VideoPlayerUi ->
                ui.binding.surfaceView.setHeights(newHeight, if (ui.isFullscreen) newHeight else maxHeight)
            }
        }
    }

    private fun showContent() {
        binding.detailContentRootHiding.visibility = View.VISIBLE
    }

    protected fun setInitialData(newServiceId: Int, newUrl: String?, newTitle: String, newPlayQueue: PlayQueue?) {
        this.serviceId = newServiceId
        this.url = newUrl
        this.title = newTitle
        this.playQueue = newPlayQueue
    }

    private fun setErrorImage(imageResource: Int) {
        if (binding_ == null || activity == null) return

        binding.detailThumbnailImageView.setImageDrawable(AppCompatResources.getDrawable(requireContext(), imageResource))
        binding.detailThumbnailImageView.animate(false, 0, AnimationType.ALPHA, 0) {
            binding.detailThumbnailImageView.animate(true, 500)
        }
    }

    override fun handleError() {
        super.handleError()
        setErrorImage(R.drawable.not_available_monkey)

        // hide related streams for tablets
        binding.relatedItemsLayout?.visibility = View.INVISIBLE

        // hide comments / related streams / description tabs
        binding.viewPager.visibility = View.GONE
        binding.tabLayout.visibility = View.GONE
    }

    private fun hideAgeRestrictedContent() {
        showTextError(getString(R.string.restricted_video,
            getString(R.string.show_age_restricted_content_title)))
    }

    private fun setupBroadcastReceiver() {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Log.d(TAG, "onReceive ${intent.action}")
                when (intent.action) {
                    ACTION_SHOW_MAIN_PLAYER -> {
                        bottomSheetBehavior!!.setState(BottomSheetBehavior.STATE_EXPANDED)
                    }
//                    ACTION_HIDE_MAIN_PLAYER -> bottomSheetBehavior!!.setState(BottomSheetBehavior.STATE_HIDDEN)
                    ACTION_HIDE_MAIN_PLAYER -> bottomSheetBehavior!!.setState(BottomSheetBehavior.STATE_COLLAPSED)
                    ACTION_PLAYER_STARTED -> {
                        // If the state is not hidden we don't need to show the mini player
                        if (bottomSheetBehavior!!.state == BottomSheetBehavior.STATE_HIDDEN) {
                            bottomSheetBehavior!!.state = BottomSheetBehavior.STATE_COLLAPSED
                        }
                        // Rebound to the service if it was closed via notification or mini player
                        if (!playerHolder.isBound) {
                            playerHolder.startService(false, this@VideoDetailFragment)
                        }
                    }
                }
            }
        }
        val intentFilter = IntentFilter()
        intentFilter.addAction(ACTION_SHOW_MAIN_PLAYER)
        intentFilter.addAction(ACTION_HIDE_MAIN_PLAYER)
        intentFilter.addAction(ACTION_PLAYER_STARTED)
        //        activity.registerReceiver(broadcastReceiver, intentFilter);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireActivity().registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireActivity().registerReceiver(broadcastReceiver, intentFilter)
        }
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Orientation listener
    ////////////////////////////////////////////////////////////////////////// */
    private fun restoreDefaultOrientation() {
        if (isPlayerAvailable && player!!.videoPlayerSelected()) {
            toggleFullscreenIfInFullscreenMode()
        }

        // This will show systemUI and pause the player.
        // User can tap on Play button and video will be in fullscreen mode again
        // Note for tablet: trying to avoid orientation changes since it's not easy
        // to physically rotate the tablet every time
        if (!isTablet(requireActivity())) {
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    ////////////////////////////////////////////////////////////////////////// */
    override fun showLoading() {
        super.showLoading()

        //if data is already cached, transition from VISIBLE -> INVISIBLE -> VISIBLE is not required
        if (!isCached(serviceId, url!!, InfoItem.InfoType.STREAM)) {
            binding.detailContentRootHiding.visibility = View.INVISIBLE
        }

        binding.detailThumbnailPlayButton.animate(false, 50)
        binding.detailDurationView.animate(false, 100)
        binding.detailPositionView.visibility = View.GONE
        binding.positionView.visibility = View.GONE

        binding.detailVideoTitleView.text = title
        binding.detailVideoTitleView.maxLines = 1
        binding.detailVideoTitleView.animate(true, 0)

        binding.detailToggleSecondaryControlsView.visibility = View.GONE
        binding.detailTitleRootLayout.isClickable = false
        binding.detailSecondaryControlPanel.visibility = View.GONE

        if (binding.relatedItemsLayout != null) {
            if (showRelatedItems) {
                binding.relatedItemsLayout!!.visibility = if (isFullscreen) View.GONE else View.INVISIBLE
            } else {
                binding.relatedItemsLayout!!.visibility = View.GONE
            }
        }

        cancelTag(PICASSO_VIDEO_DETAILS_TAG)
        binding.detailThumbnailImageView.setImageBitmap(null)
        binding.detailSubChannelThumbnailView.setImageBitmap(null)
    }

    override fun handleResult(info: StreamInfo) {
        super.handleResult(info)

        currentInfo = info
        setInitialData(info.serviceId, info.originalUrl, info.name, playQueue)

        updateTabs(info)

        binding.detailThumbnailPlayButton.animate(true, 200)
        binding.detailVideoTitleView.text = title

        binding.detailSubChannelThumbnailView.visibility = View.GONE

        if (!TextUtils.isEmpty(info.subChannelName)) {
            displayBothUploaderAndSubChannel(info)
        } else {
            displayUploaderAsSubChannel(info)
        }

        if (info.viewCount >= 0) {
            when (info.streamType) {
                StreamType.AUDIO_LIVE_STREAM -> {
                    binding.detailViewCountView.text = listeningCount(requireActivity(), info.viewCount)
                }
                StreamType.LIVE_STREAM -> {
                    binding.detailViewCountView.text = localizeWatchingCount(requireActivity(), info.viewCount)
                }
                else -> {
                    binding.detailViewCountView.text = localizeViewCount(requireActivity(), info.viewCount)
                }
            }
            binding.detailViewCountView.visibility = View.VISIBLE
        } else {
            binding.detailViewCountView.visibility = View.GONE
        }

        if (info.dislikeCount == -1L && info.likeCount == -1L) {
            binding.detailThumbsDownImgView.visibility = View.VISIBLE
            binding.detailThumbsUpImgView.visibility = View.VISIBLE
            binding.detailThumbsUpCountView.visibility = View.GONE
            binding.detailThumbsDownCountView.visibility = View.GONE

            binding.detailThumbsDisabledView.visibility = View.VISIBLE
        } else {
            if (info.dislikeCount >= 0) {
                binding.detailThumbsDownCountView.text = shortCount(requireActivity(), info.dislikeCount)
                binding.detailThumbsDownCountView.visibility = View.VISIBLE
                binding.detailThumbsDownImgView.visibility = View.VISIBLE
            } else {
                binding.detailThumbsDownCountView.visibility = View.GONE
                binding.detailThumbsDownImgView.visibility = View.GONE
            }

            if (info.likeCount >= 0) {
                binding.detailThumbsUpCountView.text = shortCount(requireActivity(), info.likeCount)
                binding.detailThumbsUpCountView.visibility = View.VISIBLE
                binding.detailThumbsUpImgView.visibility = View.VISIBLE
            } else {
                binding.detailThumbsUpCountView.visibility = View.GONE
                binding.detailThumbsUpImgView.visibility = View.GONE
            }
            binding.detailThumbsDisabledView.visibility = View.GONE
        }

        when {
            info.duration > 0 -> {
                binding.detailDurationView.text = getDurationString(info.duration)
                binding.detailDurationView.setBackgroundColor(ContextCompat.getColor(requireActivity(), R.color.duration_background_color))
                binding.detailDurationView.animate(true, 100)
            }
            info.streamType == StreamType.LIVE_STREAM -> {
                binding.detailDurationView.setText(R.string.duration_live)
                binding.detailDurationView.setBackgroundColor(ContextCompat.getColor(requireActivity(), R.color.live_duration_background_color))
                binding.detailDurationView.animate(true, 100)
            }
            else -> {
                binding.detailDurationView.visibility = View.GONE
            }
        }

        binding.detailTitleRootLayout.isClickable = true
        binding.detailToggleSecondaryControlsView.rotation = 0f
        binding.detailToggleSecondaryControlsView.visibility = View.VISIBLE
        binding.detailSecondaryControlPanel.visibility = View.GONE

        checkUpdateProgressInfo(info)
        loadDetailsThumbnail(info.thumbnails).tag(PICASSO_VIDEO_DETAILS_TAG).into(binding.detailThumbnailImageView)
        showMetaInfoInTextView(info.metaInfo, binding.detailMetaInfoTextView, binding.detailMetaInfoSeparator, disposables)

        if (!isPlayerAvailable || player!!.isStopped) {
            updateOverlayData(info.name, info.uploaderName, info.thumbnails)
        }

        if (info.errors.isNotEmpty()) {
            // Bandcamp fan pages are not yet supported and thus a ContentNotAvailableException is
            // thrown. This is not an error and thus should not be shown to the user.
            for (throwable in info.errors) {
                if (throwable is ContentNotSupportedException && "Fan pages are not supported" == throwable.message) {
                    info.errors.remove(throwable)
                }
            }

            if (info.errors.isNotEmpty()) {
                showSnackBarError(ErrorInfo(info.errors, UserAction.REQUESTED_STREAM, info.url, info))
            }
        }

        binding.detailControlsDownload.visibility = if (isLiveStream(info.streamType)) View.GONE else View.VISIBLE
        binding.detailControlsBackground.visibility =
            if (info.audioStreams.isEmpty() && info.videoStreams.isEmpty()) View.GONE else View.VISIBLE

        val noVideoStreams = info.videoStreams.isEmpty() && info.videoOnlyStreams.isEmpty()
        binding.detailControlsPopup.visibility = if (noVideoStreams) View.GONE else View.VISIBLE
        binding.detailThumbnailPlayButton.setImageResource(
            if (noVideoStreams) R.drawable.ic_headset_shadow else R.drawable.ic_play_arrow_shadow)
    }

    private fun displayUploaderAsSubChannel(info: StreamInfo) {
        binding.detailSubChannelTextView.text = info.uploaderName
        binding.detailSubChannelTextView.visibility = View.VISIBLE
        binding.detailSubChannelTextView.isSelected = true

        if (info.uploaderSubscriberCount > -1) {
            binding.detailUploaderTextView.text = shortSubscriberCount(requireActivity(), info.uploaderSubscriberCount)
            binding.detailUploaderTextView.visibility = View.VISIBLE
        } else {
            binding.detailUploaderTextView.visibility = View.GONE
        }

        loadAvatar(info.uploaderAvatars).tag(PICASSO_VIDEO_DETAILS_TAG).into(binding.detailSubChannelThumbnailView)
        binding.detailSubChannelThumbnailView.visibility = View.VISIBLE
        binding.detailUploaderThumbnailView.visibility = View.GONE
    }

    private fun displayBothUploaderAndSubChannel(info: StreamInfo) {
        binding.detailSubChannelTextView.text = info.subChannelName
        binding.detailSubChannelTextView.visibility = View.VISIBLE
        binding.detailSubChannelTextView.isSelected = true

        val subText = StringBuilder()
        if (!TextUtils.isEmpty(info.uploaderName)) {
            subText.append(String.format(getString(R.string.video_detail_by), info.uploaderName))
        }
        if (info.uploaderSubscriberCount > -1) {
            if (subText.isNotEmpty()) {
                subText.append(Localization.DOT_SEPARATOR)
            }
            subText.append(shortSubscriberCount(requireActivity(), info.uploaderSubscriberCount))
        }

        if (subText.isNotEmpty()) {
            binding.detailUploaderTextView.text = subText
            binding.detailUploaderTextView.visibility = View.VISIBLE
            binding.detailUploaderTextView.isSelected = true
        } else {
            binding.detailUploaderTextView.visibility = View.GONE
        }

        loadAvatar(info.subChannelAvatars).tag(PICASSO_VIDEO_DETAILS_TAG)
            .into(binding.detailSubChannelThumbnailView)
        binding.detailSubChannelThumbnailView.visibility = View.VISIBLE
        loadAvatar(info.uploaderAvatars).tag(PICASSO_VIDEO_DETAILS_TAG)
            .into(binding.detailUploaderThumbnailView)
        binding.detailUploaderThumbnailView.visibility = View.VISIBLE
    }

    fun openDownloadDialog() {
        if (currentInfo == null) return

        try {
            val downloadDialog = DownloadDialog(requireActivity(), currentInfo!!)
            downloadDialog.show(requireActivity().supportFragmentManager, "downloadDialog")
        } catch (e: Exception) {
            showSnackbar(requireActivity(), ErrorInfo(e, UserAction.DOWNLOAD_OPEN_DIALOG,
                "Showing download dialog", currentInfo))
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Stream Results
    ////////////////////////////////////////////////////////////////////////// */
    private fun checkUpdateProgressInfo(info: StreamInfo) {
        positionSubscriber?.dispose()
        if (!getResumePlaybackEnabled(requireActivity())) {
            binding.positionView.visibility = View.GONE
            binding.detailPositionView.visibility = View.GONE
            return
        }
        val recordManager = HistoryRecordManager(requireContext())
        positionSubscriber = recordManager.loadStreamState(info)
            .subscribeOn(Schedulers.io())
            .onErrorComplete()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ state: StreamStateEntity ->
                updatePlaybackProgress(state.progressMillis, info.duration * 1000)
            }, { e: Throwable? -> }, {
                binding.positionView.visibility = View.GONE
                binding.detailPositionView.visibility = View.GONE
            })
    }

    private fun updatePlaybackProgress(progress: Long, duration: Long) {
        if (!getResumePlaybackEnabled(requireActivity())) return

        val progressSeconds = TimeUnit.MILLISECONDS.toSeconds(progress).toInt()
        val durationSeconds = TimeUnit.MILLISECONDS.toSeconds(duration).toInt()
        // If the old and the new progress values have a big difference then use animation.
        // Otherwise don't because it affects CPU
        val progressDifference = abs((binding.positionView.progress - progressSeconds).toDouble()).toInt()
        binding.positionView.max = durationSeconds
        if (progressDifference > 2) {
            binding.positionView.setProgressAnimated(progressSeconds)
        } else {
            binding.positionView.progress = progressSeconds
        }
        val position = getDurationString(progressSeconds.toLong())
        if (position !== binding.detailPositionView.text) {
            binding.detailPositionView.text = position
        }
        if (binding.positionView.visibility != View.VISIBLE) {
            binding.positionView.animate(true, 100)
            binding.detailPositionView.animate(true, 100)
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Player event listener
    ////////////////////////////////////////////////////////////////////////// */
    override fun onViewCreated() {
        tryAddVideoPlayerView()
    }

    override fun onQueueUpdate(queue: PlayQueue?) {
        playQueue = queue
        if (DEBUG) {
            Log.d(TAG, "onQueueUpdate() called with: serviceId = [$serviceId], videoUrl = [$url], name = [$title], playQueue = [$playQueue]")
        }

        // Register broadcast receiver to listen to playQueue changes
        // and hide the overlayPlayQueueButton when the playQueue is empty / destroyed.
        if (playQueue?.broadcastReceiver != null) {
            playQueue!!.broadcastReceiver!!.subscribe { event: PlayQueueEvent? -> updateOverlayPlayQueueButtonVisibility() }
        }

        // This should be the only place where we push data to stack.
        // It will allow to have live instance of PlayQueue with actual information about
        // deleted/added items inside Channel/Playlist queue and makes possible to have
        // a history of played items
        val stackPeek = stack.peek()
        if (stackPeek != null && !stackPeek.playQueue.equalStreams(queue)) {
            val playQueueItem = queue!!.item
            if (playQueueItem != null) {
                stack.push(StackItem(playQueueItem.serviceId, playQueueItem.url, playQueueItem.title, queue))
                return
            } // else continue below
        }

        val stackWithQueue = findQueueInStack(queue)
        // On every MainPlayer service's destroy() playQueue gets disposed and
        // no longer able to track progress. That's why we update our cached disposed
        // queue with the new one that is active and have the same history.
        // Without that the cached playQueue will have an old recovery position
        stackWithQueue?.playQueue = queue!!
    }

    override fun onPlaybackUpdate(state: Int, repeatMode: Int, shuffled: Boolean, parameters: PlaybackParameters?) {
        setOverlayPlayPauseImage(player != null && player!!.isPlaying)

        when (state) {
            Player.STATE_PLAYING -> if (binding.positionView.alpha != 1.0f && player!!.playQueue?.item?.url == url) {
                binding.positionView.animate(true, 100)
                binding.detailPositionView.animate(true, 100)
            }
        }
    }

    override fun onProgressUpdate(currentProgress: Int, duration: Int, bufferPercent: Int) {
        // Progress updates every second even if media is paused. It's useless until playing
        if (!player!!.isPlaying || playQueue == null) return

        if (player!!.playQueue?.item?.url == url) {
            updatePlaybackProgress(currentProgress.toLong(), duration.toLong())
        }
    }

    override fun onMetadataUpdate(info: StreamInfo?, queue: PlayQueue?) {
        val item = findQueueInStack(queue)
        if (item != null) {
            // When PlayQueue can have multiple streams (PlaylistPlayQueue or ChannelPlayQueue)
            // every new played stream gives new title and url.
            // StackItem contains information about first played stream. Let's update it here
            item.title = info!!.name
            item.url = info.url
        }
        // They are not equal when user watches something in popup while browsing in fragment and
        // then changes screen orientation. In that case the fragment will set itself as
        // a service listener and will receive initial call to onMetadataUpdate()
        if (!queue!!.equalStreams(playQueue)) return

        updateOverlayData(info!!.name, info.uploaderName, info.thumbnails)
        if (info.url == currentInfo?.url) return

        currentInfo = info
        setInitialData(info.serviceId, info.url, info.name, queue)
        setAutoPlay(false)
        // Delay execution just because it freezes the main thread, and while playing
        // next/previous video you see visual glitches
        // (when non-vertical video goes after vertical video)
        prepareAndHandleInfoIfNeededAfterDelay(info, true, 200)
    }

    override fun onPlayerError(error: PlaybackException?, isCatchableException: Boolean) {
        if (!isCatchableException) {
            // Properly exit from fullscreen
            toggleFullscreenIfInFullscreenMode()
            hideMainPlayerOnLoadingNewStream()
        }
    }

    override fun onServiceStopped() {
        setOverlayPlayPauseImage(false)
        if (currentInfo != null) {
            updateOverlayData(currentInfo!!.name, currentInfo!!.uploaderName, currentInfo!!.thumbnails)
        }
        updateOverlayPlayQueueButtonVisibility()
    }

    override fun onFullscreenStateChanged(fullscreen: Boolean) {
        setupBrightness()
        if (!isPlayerAndPlayerServiceAvailable || player!!.UIs().get(MainPlayerUi::class.java).isEmpty
                || this.root.map { obj: View -> obj.parent }.isEmpty) {
            return
        }

        if (fullscreen) {
            hideSystemUiIfNeeded()
            binding.overlayPlayPauseButton.requestFocus()
        } else {
            showSystemUi()
        }

        binding.relatedItemsLayout?.visibility = if (fullscreen) View.GONE else View.VISIBLE

        scrollToTop()

        tryAddVideoPlayerView()
    }

    override fun onScreenRotationButtonClicked() {
        // In tablet user experience will be better if screen will not be rotated
        // from landscape to portrait every time.
        // Just turn on fullscreen mode in landscape orientation
        // or portrait & unlocked global orientation
        val isLandscape = isLandscape(requireContext())
        if (isTablet(requireActivity()) && (!PlayerHelper.globalScreenOrientationLocked(requireContext()) || isLandscape)) {
            player!!.UIs().get(MainPlayerUi::class.java).ifPresent { obj: MainPlayerUi -> obj.toggleFullscreen() }
            return
        }

        val newOrientation = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        requireActivity().requestedOrientation = newOrientation
    }

    /*
     * Will scroll down to description view after long click on moreOptionsButton
     * */
    override fun onMoreOptionsLongClicked() {
        val params = binding.appBarLayout.layoutParams as CoordinatorLayout.LayoutParams
        val behavior = params.behavior as AppBarLayout.Behavior?
        val valueAnimator = ValueAnimator.ofInt(0, -binding.playerPlaceholder.height)
        valueAnimator.interpolator = DecelerateInterpolator()
        valueAnimator.addUpdateListener { animation: ValueAnimator ->
            behavior!!.setTopAndBottomOffset(animation.animatedValue as Int)
            binding.appBarLayout.requestLayout()
        }
        valueAnimator.interpolator = DecelerateInterpolator()
        valueAnimator.setDuration(500)
        valueAnimator.start()
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Player related utils
    ////////////////////////////////////////////////////////////////////////// */
    private fun showSystemUi() {
        if (DEBUG) {
            Log.d(TAG, "showSystemUi() called")
        }

        if (activity == null) return

        // Prevent jumping of the player on devices with cutout
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requireActivity().window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        requireActivity().window.decorView.systemUiVisibility = 0
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        requireActivity().window.statusBarColor = resolveColorFromAttr(requireContext(), android.R.attr.colorPrimary)
    }

    private fun hideSystemUi() {
        if (DEBUG) {
            Log.d(TAG, "hideSystemUi() called")
        }

        if (activity == null) return

        // Prevent jumping of the player on devices with cutout
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requireActivity().window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        var visibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        // In multiWindow mode status bar is not transparent for devices with cutout
        // if I include this flag. So without it is better in this case
        val isInMultiWindow = isInMultiWindow(requireActivity() as AppCompatActivity)
        if (!isInMultiWindow) {
            visibility = visibility or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
        requireActivity().window.decorView.systemUiVisibility = visibility

        if (isInMultiWindow || isFullscreen) {
            requireActivity().window.statusBarColor = Color.TRANSPARENT
            requireActivity().window.navigationBarColor = Color.TRANSPARENT
        }
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }

    // Listener implementation
    override fun hideSystemUiIfNeeded() {
        if (isFullscreen && bottomSheetBehavior!!.state == BottomSheetBehavior.STATE_EXPANDED) {
            hideSystemUi()
        }
    }

    private val isFullscreen: Boolean
        get() = isPlayerAvailable && player!!.UIs().get(VideoPlayerUi::class.java)
            .map { obj: VideoPlayerUi -> obj.isFullscreen }.orElse(false)

    private fun playerIsNotStopped(): Boolean {
        return isPlayerAvailable && !player!!.isStopped
    }

    private fun restoreDefaultBrightness() {
        val lp = requireActivity().window.attributes
        if (lp.screenBrightness == -1f) return

        // Restore the old  brightness when fragment.onPause() called or
        // when a player is in portrait
        lp.screenBrightness = -1f
        requireActivity().window.attributes = lp
    }

    private fun setupBrightness() {
        if (activity == null) return

        val lp = requireActivity().window.attributes
        if (!isFullscreen || bottomSheetState != BottomSheetBehavior.STATE_EXPANDED) {
            // Apply system brightness when the player is not in fullscreen
            restoreDefaultBrightness()
        } else {
            // Do not restore if user has disabled brightness gesture
            if (PlayerHelper.getActionForRightGestureSide(requireActivity()) != getString(R.string.brightness_control_key)
                    && PlayerHelper.getActionForLeftGestureSide(requireActivity()) != getString(R.string.brightness_control_key)) {
                return
            }
            // Restore already saved brightness level
            val brightnessLevel = PlayerHelper.getScreenBrightness(requireActivity())
            if (brightnessLevel == lp.screenBrightness) return

            lp.screenBrightness = brightnessLevel
            requireActivity().window.attributes = lp
        }
    }

    /**
     * Make changes to the UI to accommodate for better usability on bigger screens such as TVs
     * or in Android's desktop mode (DeX etc).
     */
    private fun accommodateForTvAndDesktopMode() {
        if (isTv(requireContext())) {
            // remove ripple effects from detail controls
            val transparent = ContextCompat.getColor(requireContext(), R.color.transparent_background_color)
            binding.detailControlsPlaylistAppend.setBackgroundColor(transparent)
            binding.detailControlsBackground.setBackgroundColor(transparent)
            binding.detailControlsPopup.setBackgroundColor(transparent)
            binding.detailControlsDownload.setBackgroundColor(transparent)
            binding.detailControlsShare.setBackgroundColor(transparent)
            binding.detailControlsOpenInBrowser.setBackgroundColor(transparent)
            binding.detailControlsPlayWithKodi.setBackgroundColor(transparent)
        }
        if (isDesktopMode(requireContext())) {
            // Remove the "hover" overlay (since it is visible on all mouse events and interferes
            // with the video content being played)
            binding.detailThumbnailRootLayout.foreground = null
        }
    }

    private fun checkLandscape() {
        if ((!player!!.isPlaying && player!!.playQueue !== playQueue) || player!!.playQueue == null) {
            setAutoPlay(true)
        }

        player!!.UIs().get(MainPlayerUi::class.java).ifPresent { obj: MainPlayerUi -> obj.checkLandscape() }
        // Let's give a user time to look at video information page if video is not playing
        if (PlayerHelper.globalScreenOrientationLocked(requireContext()) && !player!!.isPlaying) {
            player!!.play()
        }
    }

    /*
     * Means that the player fragment was swiped away via BottomSheetLayout
     * and is empty but ready for any new actions. See cleanUp()
     * */
    private fun wasCleared(): Boolean {
        return url == null
    }

    private fun findQueueInStack(queue: PlayQueue?): StackItem? {
        var item: StackItem? = null
        val iterator = stack.descendingIterator()
        while (iterator.hasNext()) {
            val next = iterator.next()
            if (next.playQueue.equalStreams(queue)) {
                item = next
                break
            }
        }
        return item
    }

    private fun replaceQueueIfUserConfirms(onAllow: Runnable) {
        val activeQueue = if (isPlayerAvailable) player!!.playQueue else null

        // Player will have STATE_IDLE when a user pressed back button
        if ((PlayerHelper.isClearingQueueConfirmationRequired(requireActivity())
                        && playerIsNotStopped()) && activeQueue != null && !activeQueue.equalStreams(playQueue)) {
            showClearingQueueConfirmation(onAllow)
        } else {
            onAllow.run()
        }
    }

    private fun showClearingQueueConfirmation(onAllow: Runnable) {
        AlertDialog.Builder(requireActivity())
            .setTitle(R.string.clear_queue_confirmation_description)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { dialog: DialogInterface, which: Int ->
                onAllow.run()
                dialog.dismiss()
            }
            .show()
    }

    private fun showExternalVideoPlaybackDialog() {
        if (currentInfo == null) return

        val builder = AlertDialog.Builder(requireActivity())
        builder.setTitle(R.string.select_quality_external_players)
        builder.setNeutralButton(R.string.open_in_browser) { dialog: DialogInterface?, i: Int ->
            openUrlInBrowser(requireActivity(), url)
        }

        val videoStreamsForExternalPlayers =
            getSortedStreamVideosList(
                requireActivity(),
                getUrlAndNonTorrentStreams(currentInfo!!.videoStreams),
                getUrlAndNonTorrentStreams(currentInfo!!.videoOnlyStreams),
                false,
                false
            )

        if (videoStreamsForExternalPlayers.isEmpty()) {
            builder.setMessage(R.string.no_video_streams_available_for_external_players)
            builder.setPositiveButton(R.string.ok, null)
        } else {
            val selectedVideoStreamIndexForExternalPlayers = getDefaultResolutionIndex(requireActivity(), videoStreamsForExternalPlayers)
            val resolutions = videoStreamsForExternalPlayers.stream()
                .map { obj: VideoStream -> obj.getResolution() }.toArray<CharSequence> { size -> arrayOfNulls<String>(size) }

            builder.setSingleChoiceItems(resolutions, selectedVideoStreamIndexForExternalPlayers, null)
            builder.setNegativeButton(R.string.cancel, null)
            builder.setPositiveButton(R.string.ok) { dialog: DialogInterface, i: Int ->
                val index = (dialog as AlertDialog).listView.checkedItemPosition
                // We don't have to manage the index validity because if there is no stream
                // available for external players, this code will be not executed and if there is
                // no stream which matches the default resolution, 0 is returned by
                // ListHelper.getDefaultResolutionIndex.
                // The index cannot be outside the bounds of the list as its always between 0 and
                // the list size - 1, .
                startOnExternalPlayer(requireActivity(), currentInfo!!, videoStreamsForExternalPlayers[index])
            }
        }
        builder.show()
    }

    private fun showExternalAudioPlaybackDialog() {
        if (currentInfo == null) return

        val audioStreams: List<AudioStream> = getUrlAndNonTorrentStreams(currentInfo!!.audioStreams)
        val audioTracks = getFilteredAudioStreams(requireActivity(), audioStreams)

        when {
            audioTracks.isEmpty() -> {
                Toast.makeText(activity, R.string.no_audio_streams_available_for_external_players, Toast.LENGTH_SHORT).show()
            }
            audioTracks.size == 1 -> {
                startOnExternalPlayer(requireActivity(), currentInfo!!, audioTracks[0]!!)
            }
            else -> {
                val selectedAudioStream = getDefaultAudioFormat(requireActivity(), audioTracks)
                val trackNames = audioTracks.stream()
                    .map<String?> { audioStream: AudioStream? ->
                        audioTrackName(requireActivity(), audioStream!!)
                    }
                    .toArray<CharSequence> { size -> arrayOfNulls<String>(size) }

                AlertDialog.Builder(requireActivity())
                    .setTitle(R.string.select_audio_track_external_players)
                    .setNeutralButton(R.string.open_in_browser) { dialog: DialogInterface?, i: Int ->
                        openUrlInBrowser(requireActivity(), url)
                    }
                    .setSingleChoiceItems(trackNames, selectedAudioStream, null)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok) { dialog: DialogInterface, i: Int ->
                        val index = (dialog as AlertDialog).listView.checkedItemPosition
                        startOnExternalPlayer(requireActivity(), currentInfo!!, audioTracks[index]!!)
                    }
                    .show()
            }
        }
    }

    /*
     * Remove unneeded information while waiting for a next task
     * */
    private fun cleanUp() {
        // New beginning
        stack.clear()
        currentWorker?.dispose()
        playerHolder.stopService()
        setInitialData(0, null, "", null)
        currentInfo = null
        updateOverlayData(null, null, listOf())
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Bottom mini player
    ////////////////////////////////////////////////////////////////////////// */
    /**
     * That's for Android TV support. Move focus from main fragment to the player or back
     * based on what is currently selected
     *
     * @param toMain if true than the main fragment will be focused or the player otherwise
     */
    private fun moveFocusToMainFragment(toMain: Boolean) {
        setupBrightness()
        val mainFragment = requireActivity().findViewById<ViewGroup>(R.id.fragment_holder)
        // Hamburger button steels a focus even under bottomSheet
        val toolbar = requireActivity().findViewById<Toolbar>(R.id.toolbar)
        val afterDescendants = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val blockDescendants = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (toMain) {
            mainFragment.descendantFocusability = afterDescendants
            toolbar.descendantFocusability = afterDescendants
            (requireView() as ViewGroup).descendantFocusability = blockDescendants
            // Only focus the mainFragment if the mainFragment (e.g. search-results)
            // or the toolbar (e.g. Textfield for search) don't have focus.
            // This was done to fix problems with the keyboard input, see also #7490
            if (!mainFragment.hasFocus() && !toolbar.hasFocus()) {
                mainFragment.requestFocus()
            }
        } else {
            mainFragment.descendantFocusability = blockDescendants
            toolbar.descendantFocusability = blockDescendants
            (requireView() as ViewGroup).descendantFocusability = afterDescendants
            // Only focus the player if it not already has focus
            if (!binding.root.hasFocus()) {
                binding.detailThumbnailRootLayout.requestFocus()
            }
        }
    }

    /**
     * When the mini player exists the view underneath it is not touchable.
     * Bottom padding should be equal to the mini player's height in this case
     *
     * @param showMore whether main fragment should be expanded or not
     */
    private fun manageSpaceAtTheBottom(showMore: Boolean) {
        val peekHeight = resources.getDimensionPixelSize(R.dimen.mini_player_height)
        val holder = requireActivity().findViewById<ViewGroup>(R.id.fragment_holder)
        val newBottomPadding = if (showMore) 0 else peekHeight
        if (holder.paddingBottom == newBottomPadding) return

        holder.setPadding(holder.paddingLeft, holder.paddingTop, holder.paddingRight, newBottomPadding)
    }

    private fun setupBottomPlayer() {
        val params = binding.appBarLayout.layoutParams as CoordinatorLayout.LayoutParams
        val behavior = params.behavior as AppBarLayout.Behavior?

        val bottomSheetLayout = requireActivity().findViewById<FrameLayout>(R.id.fragment_player_holder)
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheetLayout)
        bottomSheetBehavior!!.state = lastStableBottomSheetState
        updateBottomSheetState(lastStableBottomSheetState)

        val peekHeight = resources.getDimensionPixelSize(R.dimen.mini_player_height)
        if (bottomSheetState != BottomSheetBehavior.STATE_HIDDEN) {
            manageSpaceAtTheBottom(false)
            bottomSheetBehavior!!.peekHeight = peekHeight
            when (bottomSheetState) {
                BottomSheetBehavior.STATE_COLLAPSED -> {
                    binding.overlayLayout.alpha = MAX_OVERLAY_ALPHA
                }
                BottomSheetBehavior.STATE_EXPANDED -> {
                    binding.overlayLayout.alpha = 0f
                    setOverlayElementsClickable(false)
                }
            }
        }

        bottomSheetCallback = object : BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                updateBottomSheetState(newState)
                Log.d(TAG, "onStateChanged: $newState")

                when (newState) {
                    BottomSheetBehavior.STATE_HIDDEN -> {
                        moveFocusToMainFragment(true)
                        manageSpaceAtTheBottom(true)

                        bottomSheetBehavior!!.peekHeight = 0
                        cleanUp()
                    }
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        moveFocusToMainFragment(false)
                        manageSpaceAtTheBottom(false)

                        bottomSheetBehavior!!.peekHeight = peekHeight
                        // Disable click because overlay buttons located on top of buttons
                        // from the player
                        setOverlayElementsClickable(false)
                        hideSystemUiIfNeeded()
                        // Conditions when the player should be expanded to fullscreen
                        if (isLandscape(requireContext()) && isPlayerAvailable && player!!.isPlaying && !isFullscreen && !isTablet(requireActivity())) {
                            player!!.UIs().get(MainPlayerUi::class.java).ifPresent { obj: MainPlayerUi -> obj.toggleFullscreen() }
                        }
                        setOverlayLook(binding.appBarLayout, behavior, 1f)
                    }
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        moveFocusToMainFragment(true)
                        manageSpaceAtTheBottom(false)

                        bottomSheetBehavior!!.peekHeight = peekHeight

                        // Re-enable clicks
                        setOverlayElementsClickable(true)
                        if (isPlayerAvailable) {
                            player!!.UIs().get(MainPlayerUi::class.java).ifPresent { obj: MainPlayerUi -> obj.closeItemsList() }
                        }
                        setOverlayLook(binding.appBarLayout, behavior, 0f)
                    }
                    BottomSheetBehavior.STATE_DRAGGING, BottomSheetBehavior.STATE_SETTLING -> {
                        if (isFullscreen) {
                            showSystemUi()
                        }
                        if (isPlayerAvailable) {
                            player!!.UIs().get(MainPlayerUi::class.java).ifPresent { ui: MainPlayerUi ->
                                if (ui.isControlsVisible) {
                                    ui.hideControls(0, 0)
                                }
                            }
                        }
                    }
                    BottomSheetBehavior.STATE_HALF_EXPANDED -> {}
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                setOverlayLook(binding.appBarLayout, behavior, slideOffset)
            }
        }

        bottomSheetBehavior!!.addBottomSheetCallback(bottomSheetCallback!!)

        // User opened a new page and the player will hide itself
        requireActivity().supportFragmentManager.addOnBackStackChangedListener {
            if (bottomSheetBehavior!!.state == BottomSheetBehavior.STATE_EXPANDED) {
                bottomSheetBehavior!!.state = BottomSheetBehavior.STATE_COLLAPSED
            }
        }
    }

    private fun updateOverlayPlayQueueButtonVisibility() {
        val isPlayQueueEmpty = player == null || player!!.playQueue == null || player!!.playQueue!!.isEmpty
        if (binding_ != null) {
            // binding is null when rotating the device...
            binding.overlayPlayQueueButton.visibility = if (isPlayQueueEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun updateOverlayData(overlayTitle: String?, uploader: String?, thumbnails: List<Image>) {
        binding.overlayTitleTextView.text = if (TextUtils.isEmpty(overlayTitle)) "" else overlayTitle
        binding.overlayChannelTextView.text = if (TextUtils.isEmpty(uploader)) "" else uploader
        binding.overlayThumbnail.setImageDrawable(null)
        loadDetailsThumbnail(thumbnails).tag(PICASSO_VIDEO_DETAILS_TAG).into(binding.overlayThumbnail)
    }

    private fun setOverlayPlayPauseImage(playerIsPlaying: Boolean) {
        val drawable = if (playerIsPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        binding.overlayPlayPauseButton.setImageResource(drawable)
    }

    private fun setOverlayLook(appBar: AppBarLayout, behavior: AppBarLayout.Behavior?, slideOffset: Float) {
        // SlideOffset < 0 when mini player is about to close via swipe.
        // Stop animation in this case
        if (behavior == null || slideOffset < 0) return

        binding.overlayLayout.alpha = min(MAX_OVERLAY_ALPHA.toDouble(), (1 - slideOffset).toDouble()).toFloat()
        // These numbers are not special. They just do a cool transition
        behavior.setTopAndBottomOffset((-binding.detailThumbnailImageView.height * 2 * (1 - slideOffset) / 3).toInt())
        appBar.requestLayout()
    }

    private fun setOverlayElementsClickable(enable: Boolean) {
        binding.overlayThumbnail.isClickable = enable
        binding.overlayThumbnail.isLongClickable = enable
        binding.overlayMetadataLayout.isClickable = enable
        binding.overlayMetadataLayout.isLongClickable = enable
        binding.overlayButtonsLayout.isClickable = enable
        binding.overlayPlayQueueButton.isClickable = enable
        binding.overlayPlayPauseButton.isClickable = enable
        binding.overlayCloseButton.isClickable = enable
    }

    val isPlayerAvailable: Boolean
        // helpers to check the state of player and playerService
        get() = player != null

    val isPlayerServiceAvailable: Boolean
        get() = playerService != null

    val isPlayerAndPlayerServiceAvailable: Boolean
        get() = player != null && playerService != null

    val root: Optional<View>
        get() = Optional.ofNullable(player)
            .flatMap { player1: Player ->
                player1.UIs().get(VideoPlayerUi::class.java)
            }
            .map { playerUi: VideoPlayerUi -> playerUi.binding.root }

    private fun updateBottomSheetState(newState: Int) {
        bottomSheetState = newState
        if (newState != BottomSheetBehavior.STATE_DRAGGING && newState != BottomSheetBehavior.STATE_SETTLING) {
            lastStableBottomSheetState = newState
        }
    }

    companion object {
        const val KEY_SWITCHING_PLAYERS: String = "switching_players"

        private const val MAX_OVERLAY_ALPHA = 0.9f
        private const val MAX_PLAYER_HEIGHT = 0.7f

        const val ACTION_SHOW_MAIN_PLAYER: String = App.PACKAGE_NAME + ".VideoDetailFragment.ACTION_SHOW_MAIN_PLAYER"
        const val ACTION_HIDE_MAIN_PLAYER: String = App.PACKAGE_NAME + ".VideoDetailFragment.ACTION_HIDE_MAIN_PLAYER"
        const val ACTION_PLAYER_STARTED: String = App.PACKAGE_NAME + ".VideoDetailFragment.ACTION_PLAYER_STARTED"
        const val ACTION_VIDEO_FRAGMENT_RESUMED: String = App.PACKAGE_NAME + ".VideoDetailFragment.ACTION_VIDEO_FRAGMENT_RESUMED"
        const val ACTION_VIDEO_FRAGMENT_STOPPED: String = App.PACKAGE_NAME + ".VideoDetailFragment.ACTION_VIDEO_FRAGMENT_STOPPED"

        private const val COMMENTS_TAB_TAG = "COMMENTS"
        private const val RELATED_TAB_TAG = "NEXT VIDEO"
        private const val DESCRIPTION_TAB_TAG = "DESCRIPTION TAB"
        private const val EMPTY_TAB_TAG = "EMPTY TAB"

        private const val PICASSO_VIDEO_DETAILS_TAG = "PICASSO_VIDEO_DETAILS_TAG"

        /*//////////////////////////////////////////////////////////////////////// */
        fun getInstance(serviceId: Int, videoUrl: String?, name: String, queue: PlayQueue?): VideoDetailFragment {
            val instance = VideoDetailFragment()
            instance.setInitialData(serviceId, videoUrl, name, queue)
            return instance
        }

        val instanceInCollapsedState: VideoDetailFragment
            get() {
                val instance = VideoDetailFragment()
                instance.updateBottomSheetState(BottomSheetBehavior.STATE_COLLAPSED)
                return instance
            }

        /*//////////////////////////////////////////////////////////////////////////
    // OwnStack
    ////////////////////////////////////////////////////////////////////////// */
        /**
         * Stack that contains the "navigation history".<br></br>
         * The peek is the current video.
         */
        private var stack = LinkedList<StackItem>()
    }
}
