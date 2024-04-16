package org.schabi.newpipe.player.ui

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.view.View.OnLayoutChangeListener
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.VideoSize
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.QueueItemMenuUtil.openPopupMenu
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.PlayerBinding
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamSegment
import org.schabi.newpipe.fragments.OnScrollBelowItemsListener
import org.schabi.newpipe.fragments.detail.VideoDetailFragment
import org.schabi.newpipe.info_list.StreamSegmentAdapter
import org.schabi.newpipe.info_list.StreamSegmentAdapter.StreamSegmentListener
import org.schabi.newpipe.info_list.StreamSegmentItem
import org.schabi.newpipe.ktx.AnimationType
import org.schabi.newpipe.ktx.animate
import org.schabi.newpipe.local.dialog.PlaylistDialog.Companion.showForPlayQueue
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.event.PlayerServiceEventListener
import org.schabi.newpipe.player.gesture.BasePlayerGestureListener
import org.schabi.newpipe.player.gesture.MainPlayerGestureListener
import org.schabi.newpipe.player.helper.PlaybackParameterDialog
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.player.helper.PlayerHelper.MinimizeMode
import org.schabi.newpipe.player.notification.NotificationConstants
import org.schabi.newpipe.player.playqueue.*
import org.schabi.newpipe.util.DeviceUtils.dpToPx
import org.schabi.newpipe.util.DeviceUtils.isLandscape
import org.schabi.newpipe.util.DeviceUtils.isTablet
import org.schabi.newpipe.util.DeviceUtils.isTv
import org.schabi.newpipe.util.DeviceUtils.spToPx
import org.schabi.newpipe.util.NavigationHelper.playOnPopupPlayer
import org.schabi.newpipe.util.external_communication.KoreUtils.shouldShowPlayWithKodi
import org.schabi.newpipe.util.external_communication.ShareUtils.shareText
import java.util.*
import kotlin.math.max
import kotlin.math.min

/*//////////////////////////////////////////////////////////////////////////
    // Constructor, setup, destroy
    ////////////////////////////////////////////////////////////////////////// */
//region Constructor, setup, destroy
class MainPlayerUi(player: Player, playerBinding: PlayerBinding)
    : VideoPlayerUi(player, playerBinding), OnLayoutChangeListener {

    override var isFullscreen = false
    var isVerticalVideo: Boolean = false
        private set
    private var fragmentIsVisible = false

    private var settingsContentObserver: ContentObserver? = null

    private var playQueueAdapter: PlayQueueAdapter? = null
    private var segmentAdapter: StreamSegmentAdapter? = null
    private var isQueueVisible = false
    private var areSegmentsVisible = false

    // fullscreen player
    private var itemTouchHelper: ItemTouchHelper? = null


    /**
     * Open fullscreen on tablets where the option to have the main player start automatically in
     * fullscreen mode is on. Rotating the device to landscape is already done in [ ][VideoDetailFragment.openVideoPlayer] when the thumbnail is clicked, and that's
     * enough for phones, but not for tablets since the mini player can be also shown in landscape.
     */
    private fun directlyOpenFullscreenIfNeeded() {
        if (PlayerHelper.isStartMainPlayerFullscreenEnabled(player.service)
                && isTablet(player.service)
                && PlayerHelper.globalScreenOrientationLocked(player.service)) {
            player.getFragmentListener()
                .ifPresent { obj: PlayerServiceEventListener -> obj.onScreenRotationButtonClicked() }
        }
    }

    override fun setupAfterIntent() {
        // needed for tablets, check the function for a better explanation
        directlyOpenFullscreenIfNeeded()

        super.setupAfterIntent()

        initVideoPlayer()
        // Android TV: without it focus will frame the whole player
        binding.playPauseButton.requestFocus()

        // Note: This is for automatically playing (when "Resume playback" is off), see #6179
        if (player.playWhenReady) {
            player.play()
        } else {
            player.pause()
        }
    }

    override fun buildGestureListener(): BasePlayerGestureListener {
        return MainPlayerGestureListener(this)
    }

    override fun initListeners() {
        super.initListeners()

        binding.screenRotationButton.setOnClickListener(makeOnClickListener {
            // Only if it's not a vertical video or vertical video but in landscape with locked
            // orientation a screen orientation can be changed automatically
            if (!isVerticalVideo || (isLandscape && PlayerHelper.globalScreenOrientationLocked(context))) {
                player.getFragmentListener()
                    .ifPresent { obj: PlayerServiceEventListener -> obj.onScreenRotationButtonClicked() }
            } else {
                toggleFullscreen()
            }
        })
        binding.queueButton.setOnClickListener { v: View? -> onQueueClicked() }
        binding.segmentsButton.setOnClickListener { v: View? -> onSegmentsClicked() }

        binding.addToPlaylistButton.setOnClickListener { v: View? ->
            parentActivity.map { obj: AppCompatActivity? -> obj!!.supportFragmentManager }
                .ifPresent { fragmentManager: FragmentManager? -> showForPlayQueue(player, fragmentManager!!) }
        }

        settingsContentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                setupScreenRotationButton()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, settingsContentObserver!!)

        binding.root.addOnLayoutChangeListener(this)

        binding.moreOptionsButton.setOnLongClickListener { v: View? ->
            player.getFragmentListener()
                .ifPresent { obj: PlayerServiceEventListener -> obj.onMoreOptionsLongClicked() }
            hideControls(0, 0)
            hideSystemUIIfNeeded()
            true
        }
    }

    override fun deinitListeners() {
        super.deinitListeners()

        binding.queueButton.setOnClickListener(null)
        binding.segmentsButton.setOnClickListener(null)
        binding.addToPlaylistButton.setOnClickListener(null)

        context.contentResolver.unregisterContentObserver(settingsContentObserver!!)

        binding.root.removeOnLayoutChangeListener(this)
    }

    override fun initPlayback() {
        super.initPlayback()
        playQueueAdapter?.dispose()

        if (player.playQueue != null) playQueueAdapter = PlayQueueAdapter(context, player.playQueue!!)
        segmentAdapter = StreamSegmentAdapter(streamSegmentListener)
    }

    override fun removeViewFromParent() {
        // view was added to fragment
        val parent = binding.root.parent
        if (parent is ViewGroup) {
            parent.removeView(binding.root)
        }
    }

    override fun destroy() {
        super.destroy()

        // Exit from fullscreen when user closes the player via notification
        if (isFullscreen) {
            toggleFullscreen()
        }

        removeViewFromParent()
    }

    override fun destroyPlayer() {
        super.destroyPlayer()

        if (playQueueAdapter != null) {
            playQueueAdapter!!.unsetSelectedListener()
            playQueueAdapter!!.dispose()
        }
    }

    override fun smoothStopForImmediateReusing() {
        super.smoothStopForImmediateReusing()
        // Android TV will handle back button in case controls will be visible
        // (one more additional unneeded click while the player is hidden)
        hideControls(0, 0)
        closeItemsList()
    }

    private fun initVideoPlayer() {
        // restore last resize mode
        setResizeMode(PlayerHelper.retrieveResizeModeFromPrefs(player))
        binding.root.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun setupElementsVisibility() {
        super.setupElementsVisibility()

        closeItemsList()
        showHideKodiButton()
        binding.fullScreenButton.visibility = View.GONE
        setupScreenRotationButton()
        binding.resizeTextView.visibility = View.VISIBLE
        binding.root.findViewById<View>(R.id.metadataView).visibility = View.VISIBLE
        binding.moreOptionsButton.visibility = View.VISIBLE
        binding.topControls.orientation = LinearLayout.VERTICAL
        binding.primaryControls.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        binding.secondaryControls.visibility = View.INVISIBLE
        binding.moreOptionsButton.setImageDrawable(AppCompatResources.getDrawable(context,
            R.drawable.ic_expand_more))
        binding.share.visibility = View.VISIBLE
        binding.openInBrowser.visibility = View.VISIBLE
        binding.switchMute.visibility = View.VISIBLE
        binding.playerCloseButton.visibility = if (isFullscreen) View.GONE else View.VISIBLE
        // Top controls have a large minHeight which is allows to drag the player
        // down in fullscreen mode (just larger area to make easy to locate by finger)
        binding.topControls.isClickable = true
        binding.topControls.isFocusable = true

        binding.titleTextView.visibility = if (isFullscreen) View.VISIBLE else View.GONE
        binding.channelTextView.visibility = if (isFullscreen) View.VISIBLE else View.GONE
    }

    override fun setupElementsSize(resources: Resources) {
        setupElementsSize(
            resources.getDimensionPixelSize(R.dimen.player_main_buttons_min_width),
            resources.getDimensionPixelSize(R.dimen.player_main_top_padding),
            resources.getDimensionPixelSize(R.dimen.player_main_controls_padding),
            resources.getDimensionPixelSize(R.dimen.player_main_buttons_padding)
        )
    }


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Broadcast receiver
    ////////////////////////////////////////////////////////////////////////// */
    //region Broadcast receiver
    override fun onBroadcastReceived(intent: Intent?) {
        super.onBroadcastReceived(intent)
        if (Intent.ACTION_CONFIGURATION_CHANGED == intent!!.action) {
            // Close it because when changing orientation from portrait
            // (in fullscreen mode) the size of queue layout can be larger than the screen size
            closeItemsList()
        } else if (NotificationConstants.ACTION_PLAY_PAUSE == intent.action) {
            // Ensure that we have audio-only stream playing when a user
            // started to play from notification's play button from outside of the app
            if (!fragmentIsVisible) {
                onFragmentStopped()
            }
        } else if (VideoDetailFragment.ACTION_VIDEO_FRAGMENT_STOPPED == intent.action) {
            fragmentIsVisible = false
            onFragmentStopped()
        } else if (VideoDetailFragment.ACTION_VIDEO_FRAGMENT_RESUMED == intent.action) {
            // Restore video source when user returns to the fragment
            fragmentIsVisible = true
            player.useVideoSource(true)

            // When a user returns from background, the system UI will always be shown even if
            // controls are invisible: hide it in that case
            if (!isControlsVisible) {
                hideSystemUIIfNeeded()
            }
        }
    }


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Fragment binding
    ////////////////////////////////////////////////////////////////////////// */
    //region Fragment binding
    override fun onFragmentListenerSet() {
        super.onFragmentListenerSet()
        fragmentIsVisible = true
        // Apply window insets because Android will not do it when orientation changes
        // from landscape to portrait
        if (!isFullscreen) {
            binding.playbackControlRoot.setPadding(0, 0, 0, 0)
        }
        binding.itemsListPanel.setPadding(0, 0, 0, 0)
        player.getFragmentListener().ifPresent { obj: PlayerServiceEventListener -> obj.onViewCreated() }
    }

    /**
     * This will be called when a user goes to another app/activity, turns off a screen.
     * We don't want to interrupt playback and don't want to see notification so
     * next lines of code will enable audio-only playback only if needed
     */
    private fun onFragmentStopped() {
        if (player.isPlaying || player.isLoading) {
            when (PlayerHelper.getMinimizeOnExitAction(context)) {
                MinimizeMode.MINIMIZE_ON_EXIT_MODE_BACKGROUND -> player.useVideoSource(false)
                MinimizeMode.MINIMIZE_ON_EXIT_MODE_POPUP -> parentActivity.ifPresent { activity: AppCompatActivity? ->
                    player.setRecovery()
                    playOnPopupPlayer(activity!!, player.playQueue, true)
                }
                MinimizeMode.MINIMIZE_ON_EXIT_MODE_NONE -> player.pause()
                else -> player.pause()
            }
        }
    }


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Playback states
    ////////////////////////////////////////////////////////////////////////// */
    //region Playback states
    override fun onUpdateProgress(currentProgress: Int,
                                  duration: Int,
                                  bufferPercent: Int
    ) {
        super.onUpdateProgress(currentProgress, duration, bufferPercent)

        if (areSegmentsVisible) {
            segmentAdapter!!.selectSegmentAt(getNearestStreamSegmentPosition(currentProgress.toLong()))
        }
        if (isQueueVisible) {
            updateQueueTime(currentProgress)
        }
    }

    override fun onPlaying() {
        super.onPlaying()
        checkLandscape()
    }

    override fun onCompleted() {
        super.onCompleted()
        if (isFullscreen) {
            toggleFullscreen()
        }
    }


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Controls showing / hiding
    ////////////////////////////////////////////////////////////////////////// */
    //region Controls showing / hiding
    override fun showOrHideButtons() {
        super.showOrHideButtons()
        val playQueue = player.playQueue ?: return

        val showQueue = !playQueue.streams.isEmpty()
        val showSegment = !player.currentStreamInfo
            .map { obj: StreamInfo -> obj.streamSegments }
            .map { obj: List<StreamSegment> -> obj.isEmpty() }
            .orElse( /*no stream info=*/true)

        binding.queueButton.visibility = if (showQueue) View.VISIBLE else View.GONE
        binding.queueButton.alpha = if (showQueue) 1.0f else 0.0f
        binding.segmentsButton.visibility = if (showSegment) View.VISIBLE else View.GONE
        binding.segmentsButton.alpha = if (showSegment) 1.0f else 0.0f
    }

    public override fun showSystemUIPartially() {
        if (isFullscreen) {
            parentActivity.map { obj: AppCompatActivity? -> obj!!.window }.ifPresent { window: Window ->
                window.statusBarColor = Color.TRANSPARENT
                window.navigationBarColor = Color.TRANSPARENT
                val visibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
                window.decorView.systemUiVisibility = visibility
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        }
    }

    public override fun hideSystemUIIfNeeded() {
        player.getFragmentListener().ifPresent { obj: PlayerServiceEventListener -> obj.hideSystemUiIfNeeded() }
    }

    /**
     * Calculate the maximum allowed height for the [R.id.endScreen]
     * to prevent it from enlarging the player.
     *
     *
     * The calculating follows these rules:
     *
     *  *
     * Show at least stream title and content creator on TVs and tablets when in landscape
     * (always the case for TVs) and not in fullscreen mode. This requires to have at least
     * [.DETAIL_ROOT_MINIMUM_HEIGHT] free space for [R.id.detail_root] and
     * additional space for the stream title text size ([R.id.detail_title_root_layout]).
     * The text size is [.DETAIL_TITLE_TEXT_SIZE_TABLET] on tablets and
     * [.DETAIL_TITLE_TEXT_SIZE_TV] on TVs, see [R.id.titleTextView].
     *
     *  *
     * Otherwise, the max thumbnail height is the screen height.
     *
     *
     *
     * @param bitmap the bitmap that needs to be resized to fit the end screen
     * @return the maximum height for the end screen thumbnail
     */
    override fun calculateMaxEndScreenThumbnailHeight(bitmap: Bitmap): Float {
        val screenHeight = context.resources.displayMetrics.heightPixels

        if (isTv(context) && !isFullscreen) {
            val videoInfoHeight = (dpToPx(DETAIL_ROOT_MINIMUM_HEIGHT, context)
                    + spToPx(DETAIL_TITLE_TEXT_SIZE_TV, context))
            return min(bitmap.height.toDouble(), (screenHeight - videoInfoHeight).toDouble()).toFloat()
        } else if (isTablet(context) && isLandscape && !isFullscreen) {
            val videoInfoHeight = (dpToPx(DETAIL_ROOT_MINIMUM_HEIGHT, context)
                    + spToPx(DETAIL_TITLE_TEXT_SIZE_TABLET, context))
            return min(bitmap.height.toDouble(), (screenHeight - videoInfoHeight).toDouble()).toFloat()
        } else { // fullscreen player: max height is the device height
            return min(bitmap.height.toDouble(), screenHeight.toDouble()).toFloat()
        }
    }

    private fun showHideKodiButton() {
        // show kodi button if it supports the current service and it is enabled in settings
        val playQueue = player.playQueue
        binding.playWithKodi.visibility = if (playQueue != null && playQueue.item != null && shouldShowPlayWithKodi(
                    context,
                    playQueue.item!!.serviceId)
        ) View.VISIBLE else View.GONE
    }


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Captions (text tracks)
    ////////////////////////////////////////////////////////////////////////// */
    //region Captions (text tracks)
    override fun setupSubtitleView(captionScale: Float) {
        val metrics = context.resources.displayMetrics
        val minimumLength = min(metrics.heightPixels.toDouble(), metrics.widthPixels.toDouble()).toInt()
        val captionRatioInverse = 20f + 4f * (1.0f - captionScale)
        binding.subtitleView.setFixedTextSize(
            TypedValue.COMPLEX_UNIT_PX, minimumLength / captionRatioInverse)
    }


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Gestures
    ////////////////////////////////////////////////////////////////////////// */
    //region Gestures
    override fun onLayoutChange(view: View, l: Int, t: Int, r: Int, b: Int,
                                ol: Int, ot: Int, or: Int, ob: Int
    ) {
        if (l != ol || t != ot || r != or || b != ob) {
            // Use a smaller value to be consistent across screen orientations, and to make usage
            // easier. Multiply by 3/4 to ensure the user does not need to move the finger up to the
            // screen border, in order to reach the maximum volume/brightness.
            val width = r - l
            val height = b - t
            val min = min(width.toDouble(), height.toDouble()).toInt()
            val maxGestureLength = (min * 0.75).toInt()

            if (MainActivity.DEBUG) {
                Log.d(TAG, "maxGestureLength = $maxGestureLength")
            }

            binding.volumeProgressBar.max = maxGestureLength
            binding.brightnessProgressBar.max = maxGestureLength

            setInitialGestureValues()
            binding.itemsListPanel.layoutParams.height =
                height - binding.itemsListPanel.top
        }
    }

    private fun setInitialGestureValues() {
        if (player.audioReactor != null) {
            val currentVolumeNormalized = player.audioReactor!!.volume.toFloat() / player.audioReactor!!.maxVolume
            binding.volumeProgressBar.progress = (binding.volumeProgressBar.max * currentVolumeNormalized).toInt()
        }
    }


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Play queue, segments and streams
    ////////////////////////////////////////////////////////////////////////// */
    //region Play queue, segments and streams
    override fun onMetadataChanged(info: StreamInfo) {
        super.onMetadataChanged(info)
        showHideKodiButton()
        if (areSegmentsVisible) {
            if (segmentAdapter!!.setItems(info)) {
                val adapterPosition = getNearestStreamSegmentPosition(player.exoPlayer!!.currentPosition)
                segmentAdapter!!.selectSegmentAt(adapterPosition)
                binding.itemsList.scrollToPosition(adapterPosition)
            } else {
                closeItemsList()
            }
        }
    }

    override fun onPlayQueueEdited() {
        super.onPlayQueueEdited()
        showOrHideButtons()
    }

    private fun onQueueClicked() {
        isQueueVisible = true

        hideSystemUIIfNeeded()
        buildQueue()

        binding.itemsListHeaderTitle.visibility = View.GONE
        binding.itemsListHeaderDuration.visibility = View.VISIBLE
        binding.shuffleButton.visibility = View.VISIBLE
        binding.repeatButton.visibility = View.VISIBLE
        binding.addToPlaylistButton.visibility = View.VISIBLE

        hideControls(0, 0)
        binding.itemsListPanel.requestFocus()
        binding.itemsListPanel.animate(true, DEFAULT_CONTROLS_DURATION,
            AnimationType.SLIDE_AND_ALPHA)

        val playQueue = player.playQueue
        if (playQueue != null) {
            binding.itemsList.scrollToPosition(playQueue.index)
        }

        updateQueueTime(player.exoPlayer!!.currentPosition.toInt())
    }

    private fun buildQueue() {
        binding.itemsList.adapter = playQueueAdapter
        binding.itemsList.isClickable = true
        binding.itemsList.isLongClickable = true

        binding.itemsList.clearOnScrollListeners()
        binding.itemsList.addOnScrollListener(queueScrollListener)

        itemTouchHelper = ItemTouchHelper(itemTouchCallback)
        itemTouchHelper!!.attachToRecyclerView(binding.itemsList)

        playQueueAdapter!!.setSelectedListener(onSelectedListener)

        binding.itemsListClose.setOnClickListener { view: View? -> closeItemsList() }
    }

    private fun onSegmentsClicked() {
        areSegmentsVisible = true

        hideSystemUIIfNeeded()
        buildSegments()

        binding.itemsListHeaderTitle.visibility = View.VISIBLE
        binding.itemsListHeaderDuration.visibility = View.GONE
        binding.shuffleButton.visibility = View.GONE
        binding.repeatButton.visibility = View.GONE
        binding.addToPlaylistButton.visibility = View.GONE

        hideControls(0, 0)
        binding.itemsListPanel.requestFocus()
        binding.itemsListPanel.animate(true, DEFAULT_CONTROLS_DURATION, AnimationType.SLIDE_AND_ALPHA)

        val adapterPosition = getNearestStreamSegmentPosition(player.exoPlayer!!.currentPosition)
        segmentAdapter!!.selectSegmentAt(adapterPosition)
        binding.itemsList.scrollToPosition(adapterPosition)
    }

    private fun buildSegments() {
        binding.itemsList.adapter = segmentAdapter
        binding.itemsList.isClickable = true
        binding.itemsList.isLongClickable = true

        binding.itemsList.clearOnScrollListeners()
        if (itemTouchHelper != null) {
            itemTouchHelper!!.attachToRecyclerView(null)
        }

        player.currentStreamInfo.ifPresent { info: StreamInfo? ->
            segmentAdapter!!.setItems(info!!)
        }

        binding.shuffleButton.visibility = View.GONE
        binding.repeatButton.visibility = View.GONE
        binding.addToPlaylistButton.visibility = View.GONE
        binding.itemsListClose.setOnClickListener { view: View? -> closeItemsList() }
    }

    fun closeItemsList() {
        if (isQueueVisible || areSegmentsVisible) {
            isQueueVisible = false
            areSegmentsVisible = false

            if (itemTouchHelper != null) {
                itemTouchHelper!!.attachToRecyclerView(null)
            }

            binding.itemsListPanel.animate(false, DEFAULT_CONTROLS_DURATION,
                AnimationType.SLIDE_AND_ALPHA, 0) { // Even when queueLayout is GONE it receives touch events
                // and ruins normal behavior of the app. This line fixes it
                binding.itemsListPanel.translationY = -binding.itemsListPanel.height * 5.0f
            }

            // clear focus, otherwise a white rectangle remains on top of the player
            binding.itemsListClose.clearFocus()
            binding.playPauseButton.requestFocus()
        }
    }

    private val queueScrollListener: OnScrollBelowItemsListener
        get() = object : OnScrollBelowItemsListener() {
            override fun onScrolledDown(recyclerView: RecyclerView?) {
                val playQueue = player.playQueue
                if (playQueue != null && !playQueue.isComplete) {
                    playQueue.fetch()
                } else if (binding != null) {
                    binding.itemsList.clearOnScrollListeners()
                }
            }
        }

    private val streamSegmentListener: StreamSegmentListener
        get() = object : StreamSegmentListener {
            override fun onItemClick(item: StreamSegmentItem, seconds: Int) {
                segmentAdapter!!.selectSegment(item)
                player.seekTo(seconds * 1000L)
                player.triggerProgressUpdate()
            }

            override fun onItemLongClick(item: StreamSegmentItem, seconds: Int) {
                val currentMetadata = player.currentMetadata
                if (currentMetadata == null || currentMetadata.serviceId != ServiceList.YouTube.serviceId) {
                    return
                }

                val currentItem = player.currentItem
                if (currentItem != null) {
                    var videoUrl = player.videoUrl
                    videoUrl += ("&t=$seconds")
                    shareText(context, currentItem.title,
                        videoUrl, currentItem.thumbnails)
                }
            }
        }

    private fun getNearestStreamSegmentPosition(playbackPosition: Long): Int {
        var nearestPosition = 0
        val segments = player.currentStreamInfo
            .map { obj: StreamInfo -> obj.streamSegments }
            .orElse(emptyList())

        for (i in segments.indices) {
            if (segments[i].startTimeSeconds * 1000L > playbackPosition) {
                break
            }
            nearestPosition++
        }
        return max(0.0, (nearestPosition - 1).toDouble()).toInt()
    }

    private val itemTouchCallback: ItemTouchHelper.SimpleCallback
        get() = object : PlayQueueItemTouchCallback() {
            override fun onMove(sourceIndex: Int, targetIndex: Int) {
                val playQueue = player.playQueue
                playQueue?.move(sourceIndex, targetIndex)
            }

            override fun onSwiped(index: Int) {
                val playQueue = player.playQueue
                if (playQueue != null && index != -1) {
                    playQueue.remove(index)
                }
            }
        }

    private val onSelectedListener: PlayQueueItemBuilder.OnSelectedListener
        get() = object : PlayQueueItemBuilder.OnSelectedListener {
            override fun selected(item: PlayQueueItem, view: View) {
                player.selectQueueItem(item)
            }

            override fun held(item: PlayQueueItem, view: View) {
                val playQueue = player.playQueue
                val parentActivity: AppCompatActivity? = parentActivity?.orElse(null)
                if (playQueue != null && parentActivity != null && playQueue.indexOf(item) != -1) {
                    openPopupMenu(player.playQueue!!, item, view, true,
                        parentActivity.supportFragmentManager, context)
                }
            }

            override fun onStartDrag(viewHolder: PlayQueueItemHolder) {
                if (itemTouchHelper != null) {
                    itemTouchHelper!!.startDrag(viewHolder)
                }
            }
        }

    private fun updateQueueTime(currentTime: Int) {
        val playQueue = player.playQueue ?: return

        val currentStream = playQueue.index
        var before = 0
        var after = 0

        val streams = playQueue.streams
        val nStreams = streams.size

        for (i in 0 until nStreams) {
            if (i < currentStream) {
                before += streams[i].duration.toInt()
            } else {
                after += streams[i].duration.toInt()
            }
        }

        before *= 1000
        after *= 1000

        binding.itemsListHeaderDuration.text = String.format("%s/%s",
            PlayerHelper.getTimeString(currentTime + before),
            PlayerHelper.getTimeString(before + after)
        )
    }

    override val isAnyListViewOpen: Boolean
        get() = isQueueVisible || areSegmentsVisible

    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Click listeners
    ////////////////////////////////////////////////////////////////////////// */
    //region Click listeners
    override fun onPlaybackSpeedClicked() {
        parentActivity.ifPresent { activity: AppCompatActivity? ->
            PlaybackParameterDialog.newInstance(player.playbackSpeed.toDouble(),
                player.playbackPitch.toDouble(),
                player.playbackSkipSilence,
                object : PlaybackParameterDialog.Callback {
                    override fun onPlaybackParameterChanged(
                            speed: Float,
                            pitch: Float,
                            skipSilence: Boolean
                    ) {
                        player.setPlaybackParameters(speed, pitch, skipSilence)
                    }
                }
            )
                .show(activity!!.supportFragmentManager, null)
        }
    }

    override fun onKeyDown(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SPACE && isFullscreen) {
            player.playPause()
            if (player.isPlaying) {
                hideControls(0, 0)
            }
            return true
        }
        return super.onKeyDown(keyCode)
    }


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Video size, orientation, fullscreen
    ////////////////////////////////////////////////////////////////////////// */
    //region Video size, orientation, fullscreen
    private fun setupScreenRotationButton() {
        binding.screenRotationButton.visibility = if (PlayerHelper.globalScreenOrientationLocked(context)
                || isVerticalVideo || isTablet(context)
        ) View.VISIBLE else View.GONE
        binding.screenRotationButton.setImageDrawable(AppCompatResources.getDrawable(context,
            if (isFullscreen) R.drawable.ic_fullscreen_exit
            else R.drawable.ic_fullscreen))
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        super.onVideoSizeChanged(videoSize)
        isVerticalVideo = videoSize.width < videoSize.height

        if ((PlayerHelper.globalScreenOrientationLocked(context)
                        && isFullscreen) && isLandscape == isVerticalVideo && !isTv(context)
                && !isTablet(context)) {
            // set correct orientation
            player.getFragmentListener()
                .ifPresent { obj: PlayerServiceEventListener -> obj.onScreenRotationButtonClicked() }
        }

        setupScreenRotationButton()
    }

    fun toggleFullscreen() {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "toggleFullscreen() called")
        }
        val fragmentListener = player.getFragmentListener()
            .orElse(null)
        if (fragmentListener == null || player.exoPlayerIsNull()) {
            return
        }

        isFullscreen = !isFullscreen
        if (isFullscreen) {
            // Android needs tens milliseconds to send new insets but a user is able to see
            // how controls changes it's position from `0` to `nav bar height` padding.
            // So just hide the controls to hide this visual inconsistency
            hideControls(0, 0)
        } else {
            // Apply window insets because Android will not do it when orientation changes
            // from landscape to portrait (open vertical video to reproduce)
            binding.playbackControlRoot.setPadding(0, 0, 0, 0)
        }
        fragmentListener.onFullscreenStateChanged(isFullscreen)

        binding.titleTextView.visibility = if (isFullscreen) View.VISIBLE else View.GONE
        binding.channelTextView.visibility = if (isFullscreen) View.VISIBLE else View.GONE
        binding.playerCloseButton.visibility = if (isFullscreen) View.GONE else View.VISIBLE
        setupScreenRotationButton()
    }

    fun checkLandscape() {
        // check if landscape is correct
        val videoInLandscapeButNotInFullscreen = (isLandscape
                && !isFullscreen
                && !player.isAudioOnly)
        val notPaused = (player.currentState != Player.STATE_COMPLETED
                && player.currentState != Player.STATE_PAUSED)

        if (videoInLandscapeButNotInFullscreen
                && notPaused
                && !isTablet(context)) {
            toggleFullscreen()
        }
    }


    private val parentContext: Optional<Context>
        //endregion
        get() = Optional.ofNullable(binding.root.parent)
            .filter { obj: ViewParent? -> ViewGroup::class.java.isInstance(obj) }
            .map { parent: ViewParent -> (parent as ViewGroup).context }

    val parentActivity: Optional<AppCompatActivity?>
        get() = parentContext
            .filter { obj: Context? -> AppCompatActivity::class.java.isInstance(obj) }
            .map { obj: Context? -> AppCompatActivity::class.java.cast(obj) }

    val isLandscape: Boolean
        get() =// DisplayMetrics from activity context knows about MultiWindow feature
            // while DisplayMetrics from app context doesn't
            isLandscape(parentContext.orElse(player.service)) //endregion

    companion object {
        private val TAG: String = MainPlayerUi::class.java.simpleName

        // see the Javadoc of calculateMaxEndScreenThumbnailHeight for information
        private const val DETAIL_ROOT_MINIMUM_HEIGHT = 85 // dp
        private const val DETAIL_TITLE_TEXT_SIZE_TV = 16 // sp
        private const val DETAIL_TITLE_TEXT_SIZE_TABLET = 15 // sp
    }
}
