package org.schabi.newpipe.player.ui

import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.view.View.OnLayoutChangeListener
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.BitmapCompat
import androidx.core.graphics.Insets
import androidx.core.math.MathUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Tracks
import androidx.media3.common.TrackGroup
import androidx.media3.common.text.Cue
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import org.schabi.newpipe.App
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
//import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.PlayerBinding
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.fragments.detail.VideoDetailFragment
import org.schabi.newpipe.ktx.AnimationType
import org.schabi.newpipe.ktx.animate
import org.schabi.newpipe.ktx.animateRotation
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.gesture.BasePlayerGestureListener
import org.schabi.newpipe.player.gesture.DisplayPortion
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.player.mediaitem.MediaItemTag
import org.schabi.newpipe.player.mediasession.MediaSessionPlayerUi
import org.schabi.newpipe.player.mediasession.MediaSessionPlayerUi.Companion
import org.schabi.newpipe.player.playback.SurfaceHolderCallback
import org.schabi.newpipe.player.seekbarpreview.SeekbarPreviewThumbnailHelper
import org.schabi.newpipe.player.seekbarpreview.SeekbarPreviewThumbnailHolder
import org.schabi.newpipe.util.DeviceUtils.isTv
import org.schabi.newpipe.util.Localization.audioTrackName
import org.schabi.newpipe.util.NavigationHelper.playOnMainPlayer
import org.schabi.newpipe.util.external_communication.KoreUtils.playWithKore
import org.schabi.newpipe.util.external_communication.ShareUtils.copyToClipboard
import org.schabi.newpipe.util.external_communication.ShareUtils.openUrlInBrowser
import org.schabi.newpipe.util.external_communication.ShareUtils.shareText
import org.schabi.newpipe.views.player.PlayerFastSeekOverlay.PerformListener
import org.schabi.newpipe.views.player.PlayerFastSeekOverlay.PerformListener.FastSeekDirection
import java.util.*
import java.util.function.Function
import java.util.function.Predicate
import java.util.stream.Collectors

@UnstableApi abstract class VideoPlayerUi protected constructor(player: Player, playerBinding: PlayerBinding)
    : PlayerUi(player), OnSeekBarChangeListener, PopupMenu.OnMenuItemClickListener, PopupMenu.OnDismissListener {

    private enum class PlayButtonAction {
        PLAY, PAUSE, REPLAY
    }

    //endregion
    /*//////////////////////////////////////////////////////////////////////////
       // Getters
       ////////////////////////////////////////////////////////////////////////// */
    //region Getters
    /*//////////////////////////////////////////////////////////////////////////
        // Views
        ////////////////////////////////////////////////////////////////////////// */
    var binding: PlayerBinding
        protected set
    private val controlsVisibilityHandler = Handler(Looper.getMainLooper())
    private var surfaceHolderCallback: SurfaceHolderCallback? = null
    var surfaceIsSetup: Boolean = false

    var isSomePopupMenuVisible: Boolean = false
        protected set
    private var qualityPopupMenu: PopupMenu? = null
    private var audioTrackPopupMenu: PopupMenu? = null
    protected var playbackSpeedPopupMenu: PopupMenu? = null
    private var captionPopupMenu: PopupMenu? = null

    //endregion
    /*//////////////////////////////////////////////////////////////////////////
        // Gestures
        ////////////////////////////////////////////////////////////////////////// */
    var gestureDetector: GestureDetector? = null
        private set
    private var playerGestureListener: BasePlayerGestureListener? = null
    private var onLayoutChangeListener: OnLayoutChangeListener? = null

    private val seekbarPreviewThumbnailHolder = SeekbarPreviewThumbnailHolder()

    /*//////////////////////////////////////////////////////////////////////////
    // Constructor, setup, destroy
    ////////////////////////////////////////////////////////////////////////// */
    //region Constructor, setup, destroy
    init {
        binding = playerBinding
        setupFromView()
    }

    fun setupFromView() {
        initViews()
        initListeners()
        setupPlayerSeekOverlay()
    }

    private fun initViews() {
        setupSubtitleView()

        binding.resizeTextView.text = PlayerHelper.resizeTypeOf(context, binding.surfaceView.getResizeMode())

        binding.playbackSeekBar.thumb.colorFilter = PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_IN)
        binding.playbackSeekBar.progressDrawable.colorFilter = PorterDuffColorFilter(Color.RED, PorterDuff.Mode.MULTIPLY)

        val themeWrapper = ContextThemeWrapper(context, R.style.DarkPopupMenu)

        qualityPopupMenu = PopupMenu(themeWrapper, binding.qualityTextView)
        audioTrackPopupMenu = PopupMenu(themeWrapper, binding.audioTrackTextView)
        playbackSpeedPopupMenu = PopupMenu(context, binding.playbackSpeed)
        captionPopupMenu = PopupMenu(themeWrapper, binding.captionTextView)

        binding.progressBarLoadingPanel.indeterminateDrawable.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.MULTIPLY)

        binding.titleTextView.isSelected = true
        binding.channelTextView.isSelected = true

        // Prevent hiding of bottom sheet via swipe inside queue
        binding.itemsList.isNestedScrollingEnabled = false
    }

    abstract fun buildGestureListener(): BasePlayerGestureListener?

    protected open fun initListeners() {
        binding.qualityTextView.setOnClickListener(makeOnClickListener { this.onQualityClicked() })
        binding.audioTrackTextView.setOnClickListener(makeOnClickListener { this.onAudioTracksClicked() })
        binding.playbackSpeed.setOnClickListener(makeOnClickListener { this.onPlaybackSpeedClicked() })

        binding.playbackSeekBar.setOnSeekBarChangeListener(this)
        binding.captionTextView.setOnClickListener(makeOnClickListener { this.onCaptionClicked() })
        binding.resizeTextView.setOnClickListener(makeOnClickListener { this.onResizeClicked() })
        binding.playbackLiveSync.setOnClickListener(makeOnClickListener { player.seekToDefault() })

        playerGestureListener = buildGestureListener()
        gestureDetector = GestureDetector(context, playerGestureListener!!)
        binding.root.setOnTouchListener(playerGestureListener)

        binding.repeatButton.setOnClickListener { v: View? -> onRepeatClicked() }
        binding.shuffleButton.setOnClickListener { v: View? -> onShuffleClicked() }

        binding.playPauseButton.setOnClickListener(makeOnClickListener { player.playPause() })
        binding.playPreviousButton.setOnClickListener(makeOnClickListener { player.playPrevious() })
        binding.playNextButton.setOnClickListener(makeOnClickListener { player.playNext() })

        binding.moreOptionsButton.setOnClickListener(makeOnClickListener { this.onMoreOptionsClicked() })
        binding.share.setOnClickListener(makeOnClickListener {
            val currentItem = player.currentItem
            if (currentItem != null) shareText(context, currentItem.title, player.videoUrlAtCurrentTime, currentItem.thumbnails)
        })
        binding.share.setOnLongClickListener { v: View? ->
            copyToClipboard(context, player.videoUrlAtCurrentTime)
            true
        }
        binding.fullScreenButton.setOnClickListener(makeOnClickListener {
            player.setRecovery()
            if (player.playQueue != null) playOnMainPlayer(context, player.playQueue!!, true)
        })
        binding.playWithKodi.setOnClickListener(makeOnClickListener { this.onPlayWithKodiClicked() })
        binding.openInBrowser.setOnClickListener(makeOnClickListener { this.onOpenInBrowserClicked() })
        binding.playerCloseButton.setOnClickListener(makeOnClickListener { // set package to this app's package to prevent the intent from being seen outside
            context.sendBroadcast(Intent(VideoDetailFragment.ACTION_HIDE_MAIN_PLAYER).setPackage(App.PACKAGE_NAME))
        })
        binding.switchMute.setOnClickListener(makeOnClickListener { player.toggleMute() })

        ViewCompat.setOnApplyWindowInsetsListener(binding.itemsListPanel) { view: View, windowInsets: WindowInsetsCompat ->
            val cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            if (cutout != Insets.NONE) view.setPadding(cutout.left, cutout.top, cutout.right, cutout.bottom)
            windowInsets
        }

        // PlaybackControlRoot already consumed window insets but we should pass them to
        // player_overlays and fast_seek_overlay too. Without it they will be off-centered.
        onLayoutChangeListener =
            OnLayoutChangeListener { v: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int ->
                binding.playerOverlays.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom)
                // If we added padding to the fast seek overlay, too, it would not go under the
                // system ui. Instead we apply negative margins equal to the window insets of
                // the opposite side, so that the view covers all of the player (overflowing on
                // some sides) and its center coincides with the center of other controls.
                val fastSeekParams = binding.fastSeekOverlay.layoutParams as RelativeLayout.LayoutParams
                fastSeekParams.leftMargin = -v.paddingRight
                fastSeekParams.topMargin = -v.paddingBottom
                fastSeekParams.rightMargin = -v.paddingLeft
                fastSeekParams.bottomMargin = -v.paddingTop
            }
        binding.playbackControlRoot.addOnLayoutChangeListener(onLayoutChangeListener)
    }

    protected open fun deinitListeners() {
        binding.qualityTextView.setOnClickListener(null)
        binding.audioTrackTextView.setOnClickListener(null)
        binding.playbackSpeed.setOnClickListener(null)
        binding.playbackSeekBar.setOnSeekBarChangeListener(null)
        binding.captionTextView.setOnClickListener(null)
        binding.resizeTextView.setOnClickListener(null)
        binding.playbackLiveSync.setOnClickListener(null)

        binding.root.setOnTouchListener(null)
        playerGestureListener = null
        gestureDetector = null

        binding.repeatButton.setOnClickListener(null)
        binding.shuffleButton.setOnClickListener(null)

        binding.playPauseButton.setOnClickListener(null)
        binding.playPreviousButton.setOnClickListener(null)
        binding.playNextButton.setOnClickListener(null)

        binding.moreOptionsButton.setOnClickListener(null)
        binding.moreOptionsButton.setOnLongClickListener(null)
        binding.share.setOnClickListener(null)
        binding.share.setOnLongClickListener(null)
        binding.fullScreenButton.setOnClickListener(null)
        binding.screenRotationButton.setOnClickListener(null)
        binding.playWithKodi.setOnClickListener(null)
        binding.openInBrowser.setOnClickListener(null)
        binding.playerCloseButton.setOnClickListener(null)
        binding.switchMute.setOnClickListener(null)

        ViewCompat.setOnApplyWindowInsetsListener(binding.itemsListPanel, null)

        binding.playbackControlRoot.removeOnLayoutChangeListener(onLayoutChangeListener)
    }

    /**
     * Initializes the Fast-For/Backward overlay.
     */
    private fun setupPlayerSeekOverlay() {
        binding.fastSeekOverlay
            .seekSecondsSupplier { PlayerHelper.retrieveSeekDurationFromPreferences(player) / 1000 }
            .performListener(object : PerformListener {
                override fun onDoubleTap() {
                    binding.fastSeekOverlay.animate(true, SEEK_OVERLAY_DURATION.toLong())
                }

                override fun onDoubleTapEnd() {
                    binding.fastSeekOverlay.animate(false, SEEK_OVERLAY_DURATION.toLong())
                }

                override fun getFastSeekDirection(portion: DisplayPortion): FastSeekDirection {
                    if (player.exoPlayerIsNull()) {
                        // Abort seeking
                        playerGestureListener!!.endMultiDoubleTap()
                        return FastSeekDirection.NONE
                    }
                    when (portion) {
                        DisplayPortion.LEFT -> {
                            // Check if it's possible to rewind
                            // Small puffer to eliminate infinite rewind seeking
                            if (player.exoPlayer!!.currentPosition < 500L) return FastSeekDirection.NONE
                            return FastSeekDirection.BACKWARD
                        }
                        DisplayPortion.RIGHT -> {
                            // Check if it's possible to fast-forward
                            if (player.currentState == Player.STATE_COMPLETED || player.exoPlayer!!.currentPosition >= player.exoPlayer!!.duration)
                                return FastSeekDirection.NONE
                            return FastSeekDirection.FORWARD
                        }
                        /* portion == DisplayPortion.MIDDLE */
                        else -> return FastSeekDirection.NONE
                    }
                }

                override fun seek(forward: Boolean) {
                    playerGestureListener!!.keepInDoubleTapMode()
                    if (forward) player.fastForward()
                    else player.fastRewind()
                }
            })
        playerGestureListener!!.doubleTapControls(binding.fastSeekOverlay)
    }

    fun deinitPlayerSeekOverlay() {
        binding.fastSeekOverlay
            .seekSecondsSupplier(null)
            .performListener(null)
    }

    override fun setupAfterIntent() {
        super.setupAfterIntent()
        setupElementsVisibility()
        setupElementsSize(context.resources)
        binding.root.visibility = View.VISIBLE
        binding.playPauseButton.requestFocus()
    }

    override fun initPlayer() {
        super.initPlayer()
        setupVideoSurfaceIfNeeded()
    }

    override fun initPlayback() {
        super.initPlayback()

        // #6825 - Ensure that the shuffle-button is in the correct state on the UI
        setShuffleButton(player.exoPlayer!!.shuffleModeEnabled)
    }

    abstract fun removeViewFromParent()

    override fun destroyPlayer() {
        Log.d(TAG, "destroyPlayer")
        super.destroyPlayer()
        clearVideoSurface()
    }

    override fun destroy() {
        super.destroy()
        binding.endScreen.setImageDrawable(null)
        deinitPlayerSeekOverlay()
        deinitListeners()
    }

    protected open fun setupElementsVisibility() {
        setMuteButton(player.isMuted)
        binding.moreOptionsButton.animateRotation(DEFAULT_CONTROLS_DURATION, 0)
    }

    protected abstract fun setupElementsSize(resources: Resources)

    protected fun setupElementsSize(buttonsMinWidth: Int, playerTopPad: Int, controlsPad: Int, buttonsPad: Int) {
        binding.topControls.setPaddingRelative(controlsPad, playerTopPad, controlsPad, 0)
        binding.bottomControls.setPaddingRelative(controlsPad, 0, controlsPad, 0)
        binding.qualityTextView.setPadding(buttonsPad, buttonsPad, buttonsPad, buttonsPad)
        binding.audioTrackTextView.setPadding(buttonsPad, buttonsPad, buttonsPad, buttonsPad)
        binding.playbackSpeed.setPadding(buttonsPad, buttonsPad, buttonsPad, buttonsPad)
        binding.playbackSpeed.minimumWidth = buttonsMinWidth
        binding.captionTextView.setPadding(buttonsPad, buttonsPad, buttonsPad, buttonsPad)
    }

    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Broadcast receiver
    ////////////////////////////////////////////////////////////////////////// */
    //region Broadcast receiver
    override fun onBroadcastReceived(intent: Intent?) {
        super.onBroadcastReceived(intent)
        if (Intent.ACTION_CONFIGURATION_CHANGED == intent!!.action) {
            // When the orientation changes, the screen height might be smaller. If the end screen
            // thumbnail is not re-scaled, it can be larger than the current screen height and thus
            // enlarging the whole player. This causes the seekbar to be out of the visible area.
            updateEndScreenThumbnail(player.thumbnail)
        }
    }


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Thumbnail
    ////////////////////////////////////////////////////////////////////////// */
    //region Thumbnail
    /**
     * Scale the player audio / end screen thumbnail down if necessary.
     *
     *
     * This is necessary when the thumbnail's height is larger than the device's height
     * and thus is enlarging the player's height
     * causing the bottom playback controls to be out of the visible screen.
     *
     */
    override fun onThumbnailLoaded(bitmap: Bitmap?) {
        super.onThumbnailLoaded(bitmap)
        updateEndScreenThumbnail(bitmap)
    }

    private fun updateEndScreenThumbnail(thumbnail: Bitmap?) {
        if (thumbnail == null) {
            // remove end screen thumbnail
            binding.endScreen.setImageDrawable(null)
            return
        }

        val endScreenHeight = calculateMaxEndScreenThumbnailHeight(thumbnail)
        val endScreenBitmap = BitmapCompat.createScaledBitmap(thumbnail, (thumbnail.width / (thumbnail.height / endScreenHeight)).toInt(),
            endScreenHeight.toInt(), null, true)

        if (MainActivity.DEBUG) {
            Log.d(TAG, "Thumbnail - onThumbnailLoaded() called with: currentThumbnail = [$thumbnail], ${thumbnail.width}x${thumbnail.height}, scaled end screen height = $endScreenHeight, scaled end screen width = ${endScreenBitmap.width}")
        }

        binding.endScreen.setImageBitmap(endScreenBitmap)
    }

    protected abstract fun calculateMaxEndScreenThumbnailHeight(bitmap: Bitmap): Float


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Progress loop and updates
    ////////////////////////////////////////////////////////////////////////// */
    //region Progress loop and updates
    override fun onUpdateProgress(currentProgress: Int, duration: Int, bufferPercent: Int) {
        if (duration != binding.playbackSeekBar.max) setVideoDurationToControls(duration)

        if (player.currentState != Player.STATE_PAUSED) updatePlayBackElementsCurrentDuration(currentProgress)

        if (player.isLoading || bufferPercent > 90) {
            binding.playbackSeekBar.secondaryProgress = (binding.playbackSeekBar.max * (bufferPercent.toFloat() / 100)).toInt()
        }
        if (MainActivity.DEBUG && bufferPercent % 20 == 0) { //Limit log
            Log.d(TAG, "notifyProgressUpdateToListeners() called with: isVisible = $isControlsVisible, currentProgress = [$currentProgress], duration = [$duration], bufferPercent = [$bufferPercent]")
        }
        binding.playbackLiveSync.isClickable = !player.isLiveEdge
    }

    /**
     * Sets the current duration into the corresponding elements.
     *
     * @param currentProgress the current progress, in milliseconds
     */
    private fun updatePlayBackElementsCurrentDuration(currentProgress: Int) {
        // Don't set seekbar progress while user is seeking
        if (player.currentState != Player.STATE_PAUSED_SEEK) binding.playbackSeekBar.progress = currentProgress

        binding.playbackCurrentTime.text = PlayerHelper.getTimeString(currentProgress)
    }

    /**
     * Sets the video duration time into all control components (e.g. seekbar).
     *
     * @param duration the video duration, in milliseconds
     */
    private fun setVideoDurationToControls(duration: Int) {
        binding.playbackEndTime.text = PlayerHelper.getTimeString(duration)

        binding.playbackSeekBar.max = duration
        // This is important for Android TVs otherwise it would apply the default from
        // setMax/Min methods which is (max - min) / 20
        binding.playbackSeekBar.keyProgressIncrement = PlayerHelper.retrieveSeekDurationFromPreferences(player)
    }

    // seekbar listener
    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        // Currently we don't need method execution when fromUser is false
        if (!fromUser) return

        if (MainActivity.DEBUG) {
            Log.d(TAG, "onProgressChanged() called with: seekBar = [$seekBar], progress = [$progress]")
        }

        binding.currentDisplaySeek.text = PlayerHelper.getTimeString(progress)

        // Seekbar Preview Thumbnail
        SeekbarPreviewThumbnailHelper
            .tryResizeAndSetSeekbarPreviewThumbnail(player.context, seekbarPreviewThumbnailHolder.getBitmapAt(progress).orElse(null),
                binding.currentSeekbarPreviewThumbnail) { binding.subtitleView.width }

        adjustSeekbarPreviewContainer()
    }


    private fun adjustSeekbarPreviewContainer() {
        try {
            // Should only be required when an error occurred before
            // and the layout was positioned in the center
            binding.bottomSeekbarPreviewLayout.gravity = Gravity.NO_GRAVITY

            // Calculate the current left position of seekbar progress in px
            // More info: https://stackoverflow.com/q/20493577
            val currentSeekbarLeft = (binding.playbackSeekBar.left + binding.playbackSeekBar.paddingLeft + binding.playbackSeekBar.thumb.bounds.left)

            // Calculate the (unchecked) left position of the container
            val uncheckedContainerLeft = currentSeekbarLeft - (binding.seekbarPreviewContainer.width / 2)

            // Fix the position so it's within the boundaries
            val checkedContainerLeft = MathUtils.clamp(uncheckedContainerLeft, 0, binding.playbackWindowRoot.width - binding.seekbarPreviewContainer.width)

            // See also: https://stackoverflow.com/a/23249734
            val params = LinearLayout.LayoutParams(binding.seekbarPreviewContainer.layoutParams)
            params.marginStart = checkedContainerLeft
            binding.seekbarPreviewContainer.layoutParams = params
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to adjust seekbarPreviewContainer", ex)
            // Fallback - position in the middle
            binding.bottomSeekbarPreviewLayout.gravity = Gravity.CENTER
        }
    }

    // seekbar listener
    override fun onStartTrackingTouch(seekBar: SeekBar) {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "onStartTrackingTouch() called with: seekBar = [$seekBar]")
        }
        if (player.currentState != Player.STATE_PAUSED_SEEK) player.changeState(Player.STATE_PAUSED_SEEK)

        showControls(0)
        binding.currentDisplaySeek.animate(true, DEFAULT_CONTROLS_DURATION, AnimationType.SCALE_AND_ALPHA)
        binding.currentSeekbarPreviewThumbnail.animate(true, DEFAULT_CONTROLS_DURATION, AnimationType.SCALE_AND_ALPHA)
    }

    // seekbar listener
    override fun onStopTrackingTouch(seekBar: SeekBar) {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "onStopTrackingTouch() called with: seekBar = [$seekBar]")
        }

        player.seekTo(seekBar.progress.toLong())
        if (player.exoPlayer!!.duration == seekBar.progress.toLong()) player.exoPlayer!!.play()

        binding.playbackCurrentTime.text = PlayerHelper.getTimeString(seekBar.progress)
        binding.currentDisplaySeek.animate(false, 200, AnimationType.SCALE_AND_ALPHA)
        binding.currentSeekbarPreviewThumbnail.animate(false, 200, AnimationType.SCALE_AND_ALPHA)

        if (player.currentState == Player.STATE_PAUSED_SEEK) player.changeState(Player.STATE_BUFFERING)

        if (!player.isProgressLoopRunning) player.startProgressLoop()

        showControlsThenHide()
    }

    val isControlsVisible: Boolean
        //endregion
        get() = binding != null && binding.playbackControlRoot.visibility == View.VISIBLE

    fun showControlsThenHide() {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "showControlsThenHide() called")
        }

        showOrHideButtons()
        showSystemUIPartially()

        val hideTime = if (binding.playbackControlRoot.isInTouchMode) DEFAULT_CONTROLS_HIDE_TIME else DPAD_CONTROLS_HIDE_TIME

        showHideShadow(true, DEFAULT_CONTROLS_DURATION)
        binding.playbackControlRoot.animate(true, DEFAULT_CONTROLS_DURATION, AnimationType.ALPHA, 0) {
            hideControls(DEFAULT_CONTROLS_DURATION, hideTime)
        }
    }

    fun showControls(duration: Long) {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "showControls() called")
        }
        showOrHideButtons()
        showSystemUIPartially()
        controlsVisibilityHandler.removeCallbacksAndMessages(null)
        showHideShadow(true, duration)
        binding.playbackControlRoot.animate(true, duration)
    }

    fun hideControls(duration: Long, delay: Long) {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "hideControls() called with: duration = [$duration], delay = [$delay]")
        }

        showOrHideButtons()

        controlsVisibilityHandler.removeCallbacksAndMessages(null)
        controlsVisibilityHandler.postDelayed({
            showHideShadow(false, duration)
            binding.playbackControlRoot.animate(false, duration, AnimationType.ALPHA, 0) {
                this.hideSystemUIIfNeeded()
            }
        }, delay)
    }

    fun showHideShadow(show: Boolean, duration: Long) {
        binding.playbackControlsShadow.animate(show, duration, AnimationType.ALPHA, 0, null)
        binding.playerTopShadow.animate(show, duration, AnimationType.ALPHA, 0, null)
        binding.playerBottomShadow.animate(show, duration, AnimationType.ALPHA, 0, null)
    }

    protected open fun showOrHideButtons() {
        val playQueue = player.playQueue ?: return

        val showPrev = playQueue.index != 0
        val showNext = playQueue.index + 1 != playQueue.streams.size

        binding.playPreviousButton.visibility = if (showPrev) View.VISIBLE else View.INVISIBLE
        binding.playPreviousButton.alpha = if (showPrev) 1.0f else 0.0f
        binding.playNextButton.visibility = if (showNext) View.VISIBLE else View.INVISIBLE
        binding.playNextButton.alpha = if (showNext) 1.0f else 0.0f
    }

    protected open fun showSystemUIPartially() {
        // system UI is really changed only by MainPlayerUi, so overridden there
    }

    protected open fun hideSystemUIIfNeeded() {
        // system UI is really changed only by MainPlayerUi, so overridden there
    }

    // only MainPlayerUi has list views for the queue and for segments, so overridden there
    protected open val isAnyListViewOpen: Boolean
        get() = false

    // only MainPlayerUi can be in fullscreen, so overridden there
    open val isFullscreen: Boolean
        get() = false

    /**
     * Update the play/pause button ([R.id.playPauseButton]) to reflect the action
     * that will be performed when the button is clicked..
     * @param action the action that is performed when the play/pause button is clicked
     */
    private fun updatePlayPauseButton(action: PlayButtonAction) {
        val button = binding.playPauseButton
        when (action) {
            PlayButtonAction.PLAY -> {
                button.contentDescription = context.getString(R.string.play)
                button.setImageResource(R.drawable.ic_play_arrow)
            }
            PlayButtonAction.PAUSE -> {
                button.contentDescription = context.getString(R.string.pause)
                button.setImageResource(R.drawable.ic_pause)
            }
            PlayButtonAction.REPLAY -> {
                button.contentDescription = context.getString(R.string.replay)
                button.setImageResource(R.drawable.ic_replay)
            }
        }
    }


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Playback states
    ////////////////////////////////////////////////////////////////////////// */
    //region Playback states
    override fun onPrepared() {
        super.onPrepared()
        setVideoDurationToControls(player.exoPlayer!!.duration.toInt())
        binding.playbackSpeed.text = PlayerHelper.formatSpeed(player.playbackSpeed.toDouble())
    }

    override fun onBlocked() {
        super.onBlocked()

        // if we are e.g. switching players, hide controls
        hideControls(DEFAULT_CONTROLS_DURATION, 0)

        binding.playbackSeekBar.isEnabled = false
        binding.playbackSeekBar.thumb.colorFilter = PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_IN)

        binding.loadingPanel.setBackgroundColor(Color.BLACK)
        binding.loadingPanel.animate(true, 0)
        binding.surfaceForeground.animate(true, 100)

        updatePlayPauseButton(PlayButtonAction.PLAY)
        animatePlayButtons(false, 100)
        binding.root.keepScreenOn = false
    }

    override fun onPlaying() {
        super.onPlaying()

        updateStreamRelatedViews()

        binding.playbackSeekBar.isEnabled = true
        binding.playbackSeekBar.thumb.colorFilter = PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_IN)

        binding.loadingPanel.visibility = View.GONE

        binding.currentDisplaySeek.animate(false, 200, AnimationType.SCALE_AND_ALPHA)
        binding.playPauseButton.animate(false, 80, AnimationType.SCALE_AND_ALPHA, 0) {
            updatePlayPauseButton(PlayButtonAction.PAUSE)
            animatePlayButtons(true, 200)
            if (!isAnyListViewOpen) binding.playPauseButton.requestFocus()
        }

        binding.root.keepScreenOn = true
    }

    override fun onBuffering() {
        super.onBuffering()
        binding.loadingPanel.setBackgroundColor(Color.TRANSPARENT)
        binding.loadingPanel.visibility = View.VISIBLE
        binding.root.keepScreenOn = true
    }

    override fun onPaused() {
        super.onPaused()

        // Don't let UI elements popup during double tap seeking. This state is entered sometimes
        // during seeking/loading. This if-else check ensures that the controls aren't popping up.
        if (!playerGestureListener!!.isDoubleTapping) {
            showControls(400)
            binding.loadingPanel.visibility = View.GONE

            binding.playPauseButton.animate(false, 80, AnimationType.SCALE_AND_ALPHA, 0) {
                updatePlayPauseButton(PlayButtonAction.PLAY)
                animatePlayButtons(true, 200)
                if (!isAnyListViewOpen) binding.playPauseButton.requestFocus()
            }
        }

        binding.root.keepScreenOn = false
    }

    override fun onPausedSeek() {
        super.onPausedSeek()
        animatePlayButtons(false, 100)
        binding.root.keepScreenOn = true
    }

    override fun onCompleted() {
        super.onCompleted()

        binding.playPauseButton.animate(false, 0, AnimationType.SCALE_AND_ALPHA, 0) {
            updatePlayPauseButton(PlayButtonAction.REPLAY)
            animatePlayButtons(true, DEFAULT_CONTROLS_DURATION)
        }

        binding.root.keepScreenOn = false

        // When a (short) video ends the elements have to display the correct values - see #6180
        updatePlayBackElementsCurrentDuration(binding.playbackSeekBar.max)

        showControls(500)
        binding.currentDisplaySeek.animate(false, 200, AnimationType.SCALE_AND_ALPHA)
        binding.loadingPanel.visibility = View.GONE
        binding.surfaceForeground.animate(true, 100)
    }

    private fun animatePlayButtons(show: Boolean, duration: Long) {
        binding.playPauseButton.animate(show, duration, AnimationType.SCALE_AND_ALPHA)

        val playQueue = player.playQueue ?: return

        if (!show || playQueue.index > 0) binding.playPreviousButton.animate(show, duration, AnimationType.SCALE_AND_ALPHA)
        if (!show || playQueue.index + 1 < playQueue.streams.size) binding.playNextButton.animate(show, duration, AnimationType.SCALE_AND_ALPHA)
    }


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Repeat, shuffle, mute
    ////////////////////////////////////////////////////////////////////////// */
    //region Repeat, shuffle, mute
    fun onRepeatClicked() {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "onRepeatClicked() called")
        }
        player.cycleNextRepeatMode()
    }

    fun onShuffleClicked() {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "onShuffleClicked() called")
        }
        player.toggleShuffleModeEnabled()
    }

    override fun onRepeatModeChanged(repeatMode: @androidx.media3.common.Player.RepeatMode Int) {
        super.onRepeatModeChanged(repeatMode)

        when (repeatMode) {
            androidx.media3.common.Player.REPEAT_MODE_ALL -> {
                binding.repeatButton.setImageResource(R.drawable.exo_styled_controls_repeat_all)
            }
            androidx.media3.common.Player.REPEAT_MODE_ONE -> {
                binding.repeatButton.setImageResource(R.drawable.exo_styled_controls_repeat_one)
            }
            else -> /* repeatMode == REPEAT_MODE_OFF */ {
                binding.repeatButton.setImageResource(R.drawable.exo_styled_controls_repeat_off)
            }
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        super.onShuffleModeEnabledChanged(shuffleModeEnabled)
        setShuffleButton(shuffleModeEnabled)
    }

    override fun onMuteUnmuteChanged(isMuted: Boolean) {
        super.onMuteUnmuteChanged(isMuted)
        setMuteButton(isMuted)
    }

    private fun setMuteButton(isMuted: Boolean) {
        binding.switchMute.setImageDrawable(AppCompatResources.getDrawable(context,
            if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up))
    }

    private fun setShuffleButton(shuffled: Boolean) {
        binding.shuffleButton.imageAlpha = if (shuffled) 255 else 77
    }


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Other player listeners
    ////////////////////////////////////////////////////////////////////////// */
    //region Other player listeners
    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        super.onPlaybackParametersChanged(playbackParameters)
        binding.playbackSpeed.text = PlayerHelper.formatSpeed(playbackParameters.speed.toDouble())
    }

    override fun onRenderedFirstFrame() {
        super.onRenderedFirstFrame()
        //TODO check if this causes black screen when switching to fullscreen
        binding.surfaceForeground.animate(false, DEFAULT_CONTROLS_DURATION)
    }

    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Metadata & stream related views
    ////////////////////////////////////////////////////////////////////////// */
    //region Metadata & stream related views
    override fun onMetadataChanged(info: StreamInfo) {
        super.onMetadataChanged(info)

        updateStreamRelatedViews()

        binding.titleTextView.text = info.name
        binding.channelTextView.text = info.uploaderName

        seekbarPreviewThumbnailHolder.resetFrom(player.context, info.previewFrames)
    }

    private fun updateStreamRelatedViews() {
        player.currentStreamInfo.ifPresent { info: StreamInfo ->
            binding.qualityTextView.visibility = View.GONE
            binding.audioTrackTextView.visibility = View.GONE
            binding.playbackSpeed.visibility = View.GONE

            binding.playbackEndTime.visibility = View.GONE
            binding.playbackLiveSync.visibility = View.GONE

            when (info.streamType) {
                StreamType.AUDIO_STREAM, StreamType.POST_LIVE_AUDIO_STREAM -> {
                    binding.surfaceView.visibility = View.GONE
                    binding.endScreen.visibility = View.VISIBLE
                    binding.playbackEndTime.visibility = View.VISIBLE
                }
                StreamType.AUDIO_LIVE_STREAM -> {
                    binding.surfaceView.visibility = View.GONE
                    binding.endScreen.visibility = View.VISIBLE
                    binding.playbackLiveSync.visibility = View.VISIBLE
                }
                StreamType.LIVE_STREAM -> {
                    binding.surfaceView.visibility = View.VISIBLE
                    binding.endScreen.visibility = View.GONE
                    binding.playbackLiveSync.visibility = View.VISIBLE
                }
                StreamType.VIDEO_STREAM, StreamType.POST_LIVE_STREAM -> {
                    if (player.currentMetadata != null && player.currentMetadata!!.maybeQuality.isEmpty
                            || (info.videoStreams.isEmpty() && info.videoOnlyStreams.isEmpty())) {
                    } else {
                        buildQualityMenu()
                        buildAudioTrackMenu()

                        binding.qualityTextView.visibility = View.VISIBLE
                        binding.surfaceView.visibility = View.VISIBLE
                        binding.endScreen.visibility = View.GONE
                        binding.playbackEndTime.visibility = View.VISIBLE
                    }
                }
                else -> {
                    binding.endScreen.visibility = View.GONE
                    binding.playbackEndTime.visibility = View.VISIBLE
                }
            }
            buildPlaybackSpeedMenu()
            binding.playbackSpeed.visibility = View.VISIBLE
        }
    }


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Popup menus ("popup" means that they pop up, not that they belong to the popup player)
    ////////////////////////////////////////////////////////////////////////// */
    //region Popup menus ("popup" means that they pop up, not that they belong to the popup player)
    private fun buildQualityMenu() {
        if (qualityPopupMenu == null) return

        qualityPopupMenu!!.menu.removeGroup(POPUP_MENU_ID_QUALITY)

        val availableStreams = Optional.ofNullable<MediaItemTag>(player.currentMetadata)
            .flatMap<MediaItemTag.Quality> { obj: MediaItemTag -> obj.maybeQuality }
            .map<List<VideoStream>?>(Function<MediaItemTag.Quality, List<VideoStream>?> { it.sortedVideoStreams })
            .orElse(null)
        if (availableStreams == null) return

        for (i in availableStreams.indices) {
            val videoStream = availableStreams[i]
            qualityPopupMenu!!.menu.add(POPUP_MENU_ID_QUALITY, i, Menu.NONE,
                MediaFormat.getNameById(videoStream.formatId) + " " + videoStream.getResolution())
        }
        qualityPopupMenu!!.setOnMenuItemClickListener(this)
        qualityPopupMenu!!.setOnDismissListener(this)

        player.selectedVideoStream.ifPresent { s: VideoStream -> binding.qualityTextView.text = s.getResolution() }
    }

    private fun buildAudioTrackMenu() {
        if (audioTrackPopupMenu == null) return

        audioTrackPopupMenu!!.menu.removeGroup(POPUP_MENU_ID_AUDIO_TRACK)

        val availableStreams = Optional.ofNullable<MediaItemTag>(player.currentMetadata)
            .flatMap<MediaItemTag.AudioTrack> { obj: MediaItemTag -> obj.maybeAudioTrack }
            .map<List<AudioStream>?>(Function<MediaItemTag.AudioTrack, List<AudioStream>?> { it.audioStreams })
            .orElse(null)
        if (availableStreams == null || availableStreams.size < 2) return

        for (i in availableStreams.indices) {
            val audioStream = availableStreams[i]
            audioTrackPopupMenu!!.menu.add(POPUP_MENU_ID_AUDIO_TRACK, i, Menu.NONE, audioTrackName(context, audioStream))
        }

        player.selectedAudioStream?.ifPresent { s: AudioStream? -> binding.audioTrackTextView.text = audioTrackName(context, s!!) }
        binding.audioTrackTextView.visibility = View.VISIBLE
        audioTrackPopupMenu!!.setOnMenuItemClickListener(this)
        audioTrackPopupMenu!!.setOnDismissListener(this)
    }

    private fun buildPlaybackSpeedMenu() {
        if (playbackSpeedPopupMenu == null) return

        playbackSpeedPopupMenu!!.menu.removeGroup(POPUP_MENU_ID_PLAYBACK_SPEED)

        for (i in PLAYBACK_SPEEDS.indices) {
            playbackSpeedPopupMenu!!.menu.add(POPUP_MENU_ID_PLAYBACK_SPEED, i, Menu.NONE,
                PlayerHelper.formatSpeed(PLAYBACK_SPEEDS[i].toDouble()))
        }
        binding.playbackSpeed.text = PlayerHelper.formatSpeed(player.playbackSpeed.toDouble())
        playbackSpeedPopupMenu!!.setOnMenuItemClickListener(this)
        playbackSpeedPopupMenu!!.setOnDismissListener(this)
    }

    private fun buildCaptionMenu(availableLanguages: List<String?>) {
        if (captionPopupMenu == null) return

        captionPopupMenu!!.menu.removeGroup(POPUP_MENU_ID_CAPTION)

        captionPopupMenu!!.setOnDismissListener(this)

        // Add option for turning off caption
        val captionOffItem = captionPopupMenu!!.menu.add(POPUP_MENU_ID_CAPTION, 0, Menu.NONE, R.string.caption_none)
        captionOffItem.setOnMenuItemClickListener { menuItem: MenuItem? ->
            val textRendererIndex = player.captionRendererIndex
            if (textRendererIndex != Player.RENDERER_UNAVAILABLE) {
                player.trackSelector.setParameters(player.trackSelector
                    .buildUponParameters().setRendererDisabled(textRendererIndex, true))
            }
            player.prefs.edit().remove(context.getString(R.string.caption_user_set_key)).apply()
            true
        }

        // Add all available captions
        for (i in availableLanguages.indices) {
            val captionLanguage = availableLanguages[i]
            val captionItem = captionPopupMenu!!.menu.add(POPUP_MENU_ID_CAPTION, i + 1, Menu.NONE, captionLanguage)
            captionItem.setOnMenuItemClickListener { menuItem: MenuItem? ->
                val textRendererIndex = player.captionRendererIndex
                if (textRendererIndex != Player.RENDERER_UNAVAILABLE) {
                    // DefaultTrackSelector will select for text tracks in the following order.
                    // When multiple tracks share the same rank, a random track will be chosen.
                    // 1. ANY track exactly matching preferred language name
                    // 2. ANY track exactly matching preferred language stem
                    // 3. ROLE_FLAG_CAPTION track matching preferred language stem
                    // 4. ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND track matching preferred language stem
                    // This means if a caption track of preferred language is not available,
                    // then an auto-generated track of that language will be chosen automatically.
                    player.trackSelector.setParameters(player.trackSelector
                        .buildUponParameters()
                        .setPreferredTextLanguages(captionLanguage!!, PlayerHelper.captionLanguageStemOf(captionLanguage))
                        .setPreferredTextRoleFlags(C.ROLE_FLAG_CAPTION)
                        .setRendererDisabled(textRendererIndex, false))
                    player.prefs.edit().putString(context.getString(
                        R.string.caption_user_set_key), captionLanguage).apply()
                }
                true
            }
        }
        captionPopupMenu!!.setOnDismissListener(this)

        // apply caption language from previous user preference
        val textRendererIndex = player.captionRendererIndex
        if (textRendererIndex == Player.RENDERER_UNAVAILABLE) return

        // If user prefers to show no caption, then disable the renderer.
        // Otherwise, DefaultTrackSelector may automatically find an available caption
        // and display that.
        val userPreferredLanguage = player.prefs.getString(context.getString(R.string.caption_user_set_key), null)
        if (userPreferredLanguage == null) {
            player.trackSelector.setParameters(player.trackSelector.buildUponParameters()
                .setRendererDisabled(textRendererIndex, true))
            return
        }

        // Only set preferred language if it does not match the user preference,
        // otherwise there might be an infinite cycle at onTextTracksChanged.
        val selectedPreferredLanguages: List<String> = player.trackSelector.parameters.preferredTextLanguages
        if (!selectedPreferredLanguages.contains(userPreferredLanguage)) {
            player.trackSelector.setParameters(player.trackSelector.buildUponParameters()
                .setPreferredTextLanguages(userPreferredLanguage, PlayerHelper.captionLanguageStemOf(userPreferredLanguage))
                .setPreferredTextRoleFlags(C.ROLE_FLAG_CAPTION)
                .setRendererDisabled(textRendererIndex, false))
        }
    }

    protected abstract fun onPlaybackSpeedClicked()

    private fun onQualityClicked() {
        qualityPopupMenu!!.show()
        isSomePopupMenuVisible = true

        player.selectedVideoStream
            .map { s: VideoStream -> MediaFormat.getNameById(s.formatId) + " " + s.getResolution() }
            .ifPresent { text: String? -> binding.qualityTextView.text = text }
    }

    private fun onAudioTracksClicked() {
        audioTrackPopupMenu!!.show()
        isSomePopupMenuVisible = true
    }

    /**
     * Called when an item of the quality selector or the playback speed selector is selected.
     */
    override fun onMenuItemClick(menuItem: MenuItem): Boolean {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "onMenuItemClick() called with: "
                    + "menuItem = [" + menuItem + "], "
                    + "menuItem.getItemId = [" + menuItem.itemId + "]")
        }

        when (menuItem.groupId) {
            POPUP_MENU_ID_QUALITY -> {
                onQualityItemClick(menuItem)
                return true
            }
            POPUP_MENU_ID_AUDIO_TRACK -> {
                onAudioTrackItemClick(menuItem)
                return true
            }
            POPUP_MENU_ID_PLAYBACK_SPEED -> {
                val speedIndex = menuItem.itemId
                val speed = PLAYBACK_SPEEDS[speedIndex]

                player.playbackSpeed = speed
                binding.playbackSpeed.text = PlayerHelper.formatSpeed(speed.toDouble())
            }
        }

        return false
    }

    private fun onQualityItemClick(menuItem: MenuItem) {
        val menuItemIndex = menuItem.itemId
        val currentMetadata = player.currentMetadata
        if (currentMetadata == null || currentMetadata.maybeQuality.isEmpty) return

        val quality = currentMetadata.maybeQuality.get()
        val availableStreams = quality.sortedVideoStreams
        val selectedStreamIndex = quality.selectedVideoStreamIndex
        if (selectedStreamIndex == menuItemIndex || availableStreams.size <= menuItemIndex) return

        val newResolution = availableStreams[menuItemIndex].getResolution()
        player.setPlaybackQuality(newResolution)

        binding.qualityTextView.text = menuItem.title
    }

    private fun onAudioTrackItemClick(menuItem: MenuItem) {
        val menuItemIndex = menuItem.itemId
        val currentMetadata = player.currentMetadata
        if (currentMetadata == null || currentMetadata.maybeAudioTrack.isEmpty) return

        val audioTrack = currentMetadata.maybeAudioTrack.get()
        val availableStreams = audioTrack.audioStreams
        val selectedStreamIndex = audioTrack.selectedAudioStreamIndex
        if (selectedStreamIndex == menuItemIndex || availableStreams.size <= menuItemIndex) return

        val newAudioTrack = availableStreams[menuItemIndex].audioTrackId
        player.setAudioTrack(newAudioTrack)

        binding.audioTrackTextView.text = menuItem.title
    }

    /**
     * Called when some popup menu is dismissed.
     */
    override fun onDismiss(menu: PopupMenu?) {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "onDismiss() called with: menu = [$menu]")
        }
        isSomePopupMenuVisible = false //TODO check if this works
        player.selectedVideoStream.ifPresent { s: VideoStream -> binding.qualityTextView.text = s.getResolution() }

        if (player.isPlaying) {
            hideControls(DEFAULT_CONTROLS_DURATION, 0)
            hideSystemUIIfNeeded()
        }
    }

    private fun onCaptionClicked() {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "onCaptionClicked() called")
        }
        captionPopupMenu!!.show()
        isSomePopupMenuVisible = true
    }


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Captions (text tracks)
    ////////////////////////////////////////////////////////////////////////// */
    //region Captions (text tracks)
    override fun onTextTracksChanged(currentTracks: Tracks) {
        super.onTextTracksChanged(currentTracks)

        val trackTypeTextSupported = (!currentTracks.containsType(C.TRACK_TYPE_TEXT) || currentTracks.isTypeSupported(C.TRACK_TYPE_TEXT, false))
        if (player.trackSelector.currentMappedTrackInfo == null || !trackTypeTextSupported) {
            binding.captionTextView.visibility = View.GONE
            return
        }

        // Extract all loaded languages
        val textTracks = currentTracks
            .groups
            .stream()
            .filter { trackGroupInfo: Tracks.Group -> C.TRACK_TYPE_TEXT == trackGroupInfo.type }
            .collect(Collectors.toList())
        val availableLanguages = textTracks.stream()
            .map<TrackGroup>(Function<Tracks.Group, TrackGroup> { it.mediaTrackGroup })
            .filter { textTrack: TrackGroup -> textTrack.length > 0 }
            .map<String?> { textTrack: TrackGroup -> textTrack.getFormat(0).language }
            .collect(Collectors.toList<String?>())

        // Find selected text track
        val selectedTracks = textTracks.stream()
            .filter(Predicate<Tracks.Group> { it.isSelected() })
            .filter { info: Tracks.Group -> info.mediaTrackGroup.length >= 1 }
            .map<Format> { info: Tracks.Group -> info.mediaTrackGroup.getFormat(0) }
            .findFirst()

        // Build UI
        buildCaptionMenu(availableLanguages)
        if (player.trackSelector.parameters.getRendererDisabled(player.captionRendererIndex) || selectedTracks.isEmpty)
            binding.captionTextView.setText(R.string.caption_none)
        else binding.captionTextView.text = selectedTracks.get().language

        binding.captionTextView.visibility = if (availableLanguages.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onCues(cues: List<Cue>) {
        super.onCues(cues)
        binding.subtitleView.setCues(cues)
    }

    private fun setupSubtitleView() {
        setupSubtitleView(PlayerHelper.getCaptionScale(context))
        val captionStyle = PlayerHelper.getCaptionStyle(context)
        binding.subtitleView.setApplyEmbeddedStyles(captionStyle == CaptionStyleCompat.DEFAULT)
        binding.subtitleView.setStyle(captionStyle)
    }

    protected abstract fun setupSubtitleView(captionScale: Float)

    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Click listeners
    ////////////////////////////////////////////////////////////////////////// */
    //region Click listeners
    /**
     * Create on-click listener which manages the player controls after the view on-click action.
     *
     * @param runnable The action to be executed.
     * @return The view click listener.
     */
    protected fun makeOnClickListener(runnable: Runnable): View.OnClickListener {
        return View.OnClickListener { v: View ->
            if (MainActivity.DEBUG) {
                Log.d(TAG, "onClick() called with: v = [$v]")
            }
            runnable.run()

            // Manages the player controls after handling the view click.
            if (player.currentState == Player.STATE_COMPLETED) return@OnClickListener

            controlsVisibilityHandler.removeCallbacksAndMessages(null)
            showHideShadow(true, DEFAULT_CONTROLS_DURATION)
            binding.playbackControlRoot.animate(true, DEFAULT_CONTROLS_DURATION,
                AnimationType.ALPHA, 0) {
                if (player.currentState == Player.STATE_PLAYING && !isSomePopupMenuVisible) {
                    // Hide controls in fullscreen immediately
                    if (v === binding.playPauseButton || (v === binding.screenRotationButton && isFullscreen)) hideControls(0, 0)
                    else hideControls(DEFAULT_CONTROLS_DURATION, DEFAULT_CONTROLS_HIDE_TIME)
                }
            }
        }
    }

    open fun onKeyDown(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> if (isTv(context) && isControlsVisible) {
                hideControls(0, 0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if ((binding.root.hasFocus() && !binding.playbackControlRoot.hasFocus()) || this.isAnyListViewOpen) {
                    // do not interfere with focus in playlist and play queue etc.
                } else {
                    if (player.currentState == Player.STATE_BLOCKED) return true

                    if (isControlsVisible) {
                        hideControls(DEFAULT_CONTROLS_DURATION, DPAD_CONTROLS_HIDE_TIME)
                    } else {
                        binding.playPauseButton.requestFocus()
                        showControlsThenHide()
                        showSystemUIPartially()
                        return true
                    }
                }
            }
            else -> {}
        }
        return false
    }

    private fun onMoreOptionsClicked() {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "onMoreOptionsClicked() called")
        }

        val isMoreControlsVisible = binding.secondaryControls.visibility == View.VISIBLE

        binding.moreOptionsButton.animateRotation(DEFAULT_CONTROLS_DURATION, if (isMoreControlsVisible) 0 else 180)
        binding.secondaryControls.animate(!isMoreControlsVisible, DEFAULT_CONTROLS_DURATION,
            AnimationType.SLIDE_AND_ALPHA, 0) {
            // Fix for a ripple effect on background drawable.
            // When view returns from GONE state it takes more milliseconds than returning
            // from INVISIBLE state. And the delay makes ripple background end to fast
            if (isMoreControlsVisible) binding.secondaryControls.visibility = View.INVISIBLE
        }
        showControls(DEFAULT_CONTROLS_DURATION)
    }

    private fun onPlayWithKodiClicked() {
        if (player.currentMetadata != null) {
            player.pause()
            playWithKore(context, Uri.parse(player.videoUrl))
        }
    }

    private fun onOpenInBrowserClicked() {
        player.currentStreamInfo.ifPresent { streamInfo: StreamInfo ->
            openUrlInBrowser(player.context, streamInfo.originalUrl)
        }
    }

    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Video size
    ////////////////////////////////////////////////////////////////////////// */
    //region Video size
    protected fun setResizeMode(resizeMode: @AspectRatioFrameLayout.ResizeMode Int) {
        binding.surfaceView.setResizeMode(resizeMode)
        binding.resizeTextView.text = PlayerHelper.resizeTypeOf(context, resizeMode)
    }

    fun onResizeClicked() {
        setResizeMode(PlayerHelper.nextResizeModeAndSaveToPrefs(player, binding.surfaceView.getResizeMode()))
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        super.onVideoSizeChanged(videoSize)
        binding.surfaceView.setAspectRatio((videoSize.width.toFloat()) / videoSize.height)
    }


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // SurfaceHolderCallback helpers
    ////////////////////////////////////////////////////////////////////////// */
    //region SurfaceHolderCallback helpers
    /**
     * Connects the video surface to the exo player. This can be called anytime without the risk for
     * issues to occur, since the player will run just fine when no surface is connected. Therefore
     * the video surface will be setup only when all of these conditions are true: it is not already
     * setup (this just prevents wasting resources to setup the surface again), there is an exo
     * player, the root view is attached to a parent and the surface view is valid/unreleased (the
     * latter two conditions prevent "The surface has been released" errors). So this function can
     * be called many times and even while the UI is in unready states.
     */
    fun setupVideoSurfaceIfNeeded() {
        if (!surfaceIsSetup && player.exoPlayer != null && binding.root.parent != null) {
            Log.d(TAG, "setupVideoSurfaceIfNeeded")
            // make sure there is nothing left over from previous calls
            clearVideoSurface()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // >=API23
                Log.d(TAG, "adding surface callback")
                surfaceHolderCallback = SurfaceHolderCallback(context, player.exoPlayer!!)
                binding.surfaceView.holder.addCallback(surfaceHolderCallback)

                // ensure player is using an unreleased surface, which the surfaceView might not be
                // when starting playback on background or during player switching
                if (binding.surfaceView.holder.surface.isValid) {
                    // initially set the surface manually otherwise
                    // onRenderedFirstFrame() will not be called
                    player.exoPlayer!!.setVideoSurfaceHolder(binding.surfaceView.holder)
                }
            } else {
                player.exoPlayer!!.setVideoSurfaceView(binding.surfaceView)
            }

            surfaceIsSetup = true
        }
    }

    private fun clearVideoSurface() {
        // >=API23
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && surfaceHolderCallback != null) {
            binding.surfaceView.holder.removeCallback(surfaceHolderCallback)
            surfaceHolderCallback!!.release()
            surfaceHolderCallback = null
        }
        Optional.ofNullable(player.exoPlayer).ifPresent { obj: ExoPlayer -> obj.clearVideoSurface() }
        surfaceIsSetup = false
    }

    companion object {
        private val TAG: String = VideoPlayerUi::class.java.simpleName

        // time constants
        const val DEFAULT_CONTROLS_DURATION: Long = 300 // 300 millis
        const val DEFAULT_CONTROLS_HIDE_TIME: Long = 2000 // 2 Seconds
        const val DPAD_CONTROLS_HIDE_TIME: Long = 7000 // 7 Seconds
        const val SEEK_OVERLAY_DURATION: Int = 450 // 450 millis

        // other constants (TODO remove playback speeds and use normal menu for popup, too)
        private val PLAYBACK_SPEEDS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

        /*//////////////////////////////////////////////////////////////////////////
    // Popup menus ("popup" means that they pop up, not that they belong to the popup player)
    ////////////////////////////////////////////////////////////////////////// */
        private const val POPUP_MENU_ID_QUALITY = 69
        private const val POPUP_MENU_ID_AUDIO_TRACK = 70
        private const val POPUP_MENU_ID_PLAYBACK_SPEED = 79
        private const val POPUP_MENU_ID_CAPTION = 89
    }
}
