package org.schabi.newpipe.player.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.view.animation.AnticipateInterpolator
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.math.MathUtils
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.PlayerBinding
import org.schabi.newpipe.databinding.PlayerPopupCloseOverlayBinding
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.gesture.BasePlayerGestureListener
import org.schabi.newpipe.player.gesture.PopupPlayerGestureListener
import org.schabi.newpipe.player.helper.PlayerHelper
import kotlin.math.pow
import kotlin.math.sqrt

@UnstableApi class PopupPlayerUi(player: Player, playerBinding: PlayerBinding) : VideoPlayerUi(player, playerBinding) {
    /*//////////////////////////////////////////////////////////////////////////
    // Popup player
    ////////////////////////////////////////////////////////////////////////// */
    var closeOverlayBinding: PlayerPopupCloseOverlayBinding? = null
        private set

    var isPopupClosing: Boolean = false
        private set

    //endregion
    var screenWidth: Int = 0
        private set
    var screenHeight: Int = 0
        private set

    // null if player is not popup
    var popupLayoutParams: WindowManager.LayoutParams? = null
        private set
    val windowManager: WindowManager? = ContextCompat.getSystemService(context, WindowManager::class.java)

    override fun setupAfterIntent() {
        super.setupAfterIntent()
        initPopup()
        initPopupCloseOverlay()
    }

    public override fun buildGestureListener(): BasePlayerGestureListener {
        return PopupPlayerGestureListener(this)
    }

    @SuppressLint("RtlHardcoded")
    private fun initPopup() {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "initPopup() called")
        }

        // Popup is already added to windowManager
        if (popupHasParent()) {
            return
        }

        updateScreenSize()

        popupLayoutParams = retrievePopupLayoutParamsFromPrefs()
        binding.surfaceView.setHeights(popupLayoutParams!!.height, popupLayoutParams!!.height)

        checkPopupPositionBounds()

        binding.loadingPanel.minimumWidth = popupLayoutParams!!.width
        binding.loadingPanel.minimumHeight = popupLayoutParams!!.height

        windowManager!!.addView(binding.root, popupLayoutParams)
        setupVideoSurfaceIfNeeded() // now there is a parent, we can setup video surface

        // Popup doesn't have aspectRatio selector, using FIT automatically
        setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    }

    @SuppressLint("RtlHardcoded")
    private fun initPopupCloseOverlay() {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "initPopupCloseOverlay() called")
        }

        // closeOverlayView is already added to windowManager
        if (closeOverlayBinding != null) {
            return
        }

        closeOverlayBinding = PlayerPopupCloseOverlayBinding.inflate(LayoutInflater.from(context))

        val closeOverlayLayoutParams = buildCloseOverlayLayoutParams()
        closeOverlayBinding!!.closeButton.visibility = View.GONE
        windowManager!!.addView(closeOverlayBinding!!.root, closeOverlayLayoutParams)
    }

    override fun setupElementsVisibility() {
        binding.fullScreenButton.visibility = View.VISIBLE
        binding.screenRotationButton.visibility = View.GONE
        binding.resizeTextView.visibility = View.GONE
        binding.root.findViewById<View>(R.id.metadataView).visibility = View.GONE
        binding.queueButton.visibility = View.GONE
        binding.segmentsButton.visibility = View.GONE
        binding.moreOptionsButton.visibility = View.GONE
        binding.topControls.orientation = LinearLayout.HORIZONTAL
        binding.primaryControls.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        binding.secondaryControls.alpha = 1.0f
        binding.secondaryControls.visibility = View.VISIBLE
        binding.secondaryControls.translationY = 0f
        binding.share.visibility = View.GONE
        binding.playWithKodi.visibility = View.GONE
        binding.openInBrowser.visibility = View.GONE
        binding.switchMute.visibility = View.GONE
        binding.playerCloseButton.visibility = View.GONE
        binding.topControls.bringToFront()
        binding.topControls.isClickable = false
        binding.topControls.isFocusable = false
        binding.bottomControls.bringToFront()
        super.setupElementsVisibility()
    }

    override fun setupElementsSize(resources: Resources) {
        setupElementsSize(
            0,
            0,
            resources.getDimensionPixelSize(R.dimen.player_popup_controls_padding),
            resources.getDimensionPixelSize(R.dimen.player_popup_buttons_padding)
        )
    }

    override fun removeViewFromParent() {
        // view was added by windowManager for popup player
        windowManager!!.removeViewImmediate(binding.root)
    }

    override fun destroy() {
        super.destroy()
        removePopupFromView()
    }


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Broadcast receiver
    ////////////////////////////////////////////////////////////////////////// */
    //region Broadcast receiver
    override fun onBroadcastReceived(intent: Intent?) {
        super.onBroadcastReceived(intent)
        if (Intent.ACTION_CONFIGURATION_CHANGED == intent!!.action) {
            updateScreenSize()
            changePopupSize(popupLayoutParams!!.width)
            checkPopupPositionBounds()
        } else if (player.isPlaying || player.isLoading) {
            if (Intent.ACTION_SCREEN_OFF == intent.action) {
                // Use only audio source when screen turns off while popup player is playing
                player.useVideoSource(false)
            } else if (Intent.ACTION_SCREEN_ON == intent.action) {
                // Restore video source when screen turns on and user was watching video in popup
                player.useVideoSource(true)
            }
        }
    }


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Popup position and size
    ////////////////////////////////////////////////////////////////////////// */
    //region Popup position and size
    /**
     * Check if [.popupLayoutParams]' position is within a arbitrary boundary
     * that goes from (0, 0) to (screenWidth, screenHeight).
     *
     *
     * If it's out of these boundaries, [.popupLayoutParams]' position is changed
     * and `true` is returned to represent this change.
     *
     */
    fun checkPopupPositionBounds() {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "checkPopupPositionBounds() called with: "
                    + "screenWidth = [" + screenWidth + "], "
                    + "screenHeight = [" + screenHeight + "]")
        }
        if (popupLayoutParams == null) {
            return
        }

        popupLayoutParams!!.x = MathUtils.clamp(popupLayoutParams!!.x, 0, screenWidth
                - popupLayoutParams!!.width)
        popupLayoutParams!!.y = MathUtils.clamp(popupLayoutParams!!.y, 0, screenHeight
                - popupLayoutParams!!.height)
    }

    fun updateScreenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager!!.currentWindowMetrics
            val bounds = windowMetrics.bounds
            val windowInsets = windowMetrics.windowInsets
            val insets = windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
            screenWidth = bounds.width() - (insets.left + insets.right)
            screenHeight = bounds.height() - (insets.top + insets.bottom)
        } else {
            val metrics = DisplayMetrics()
            windowManager!!.defaultDisplay.getMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        if (MainActivity.DEBUG) {
            Log.d(TAG, "updateScreenSize() called: screenWidth = ["
                    + screenWidth + "], screenHeight = [" + screenHeight + "]")
        }
    }

    /**
     * Changes the size of the popup based on the width.
     * @param width the new width, height is calculated with
     * [PlayerHelper.getMinimumVideoHeight]
     */
    fun changePopupSize(width: Int) {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "changePopupSize() called with: width = [$width]")
        }

        if (anyPopupViewIsNull()) {
            return
        }

        val minimumWidth = context.resources.getDimension(R.dimen.popup_minimum_width)
        val actualWidth = MathUtils.clamp(width, minimumWidth.toInt(), screenWidth)
        val actualHeight = PlayerHelper.getMinimumVideoHeight(width.toFloat()).toInt()
        if (MainActivity.DEBUG) {
            Log.d(TAG, "updatePopupSize() updated values:"
                    + "  width = [" + actualWidth + "], height = [" + actualHeight + "]")
        }

        popupLayoutParams!!.width = actualWidth
        popupLayoutParams!!.height = actualHeight
        binding.surfaceView.setHeights(popupLayoutParams!!.height, popupLayoutParams!!.height)
        windowManager!!.updateViewLayout(binding.root, popupLayoutParams)
    }

    override fun calculateMaxEndScreenThumbnailHeight(bitmap: Bitmap): Float {
        // no need for the end screen thumbnail to be resized on popup player: it's only needed
        // for the main player so that it is enlarged correctly inside the fragment
        return bitmap.height.toFloat()
    }


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Popup closing
    ////////////////////////////////////////////////////////////////////////// */
    //region Popup closing
    fun closePopup() {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "closePopup() called, isPopupClosing = $isPopupClosing")
        }
        if (isPopupClosing) {
            return
        }
        isPopupClosing = true

        player.saveStreamProgressState()
        windowManager!!.removeView(binding.root)

        animatePopupOverlayAndFinishService()
    }

    fun removePopupFromView() {
        // wrap in try-catch since it could sometimes generate errors randomly
        try {
            if (popupHasParent()) {
                windowManager!!.removeView(binding.root)
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Failed to remove popup from window manager", e)
        }

        try {
            val closeOverlayHasParent = (closeOverlayBinding != null
                    && closeOverlayBinding!!.root.parent != null)
            if (closeOverlayHasParent) {
                windowManager!!.removeView(closeOverlayBinding!!.root)
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Failed to remove popup overlay from window manager", e)
        }
    }

    private fun animatePopupOverlayAndFinishService() {
        val targetTranslationY = (closeOverlayBinding!!.closeButton.rootView.height
                - closeOverlayBinding!!.closeButton.y).toInt()

        closeOverlayBinding!!.closeButton.animate().setListener(null).cancel()
        closeOverlayBinding!!.closeButton.animate()
            .setInterpolator(AnticipateInterpolator())
            .translationY(targetTranslationY.toFloat())
            .setDuration(400)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    end()
                }

                override fun onAnimationEnd(animation: Animator) {
                    end()
                }

                private fun end() {
                    windowManager!!.removeView(closeOverlayBinding!!.root)
                    closeOverlayBinding = null
                    player.service.stopService()
                }
            }).start()
    }

    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Playback states
    ////////////////////////////////////////////////////////////////////////// */
    //region Playback states
    private fun changePopupWindowFlags(flags: Int) {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "changePopupWindowFlags() called with: flags = [$flags]")
        }

        if (!anyPopupViewIsNull()) {
            popupLayoutParams!!.flags = flags
            windowManager!!.updateViewLayout(binding.root, popupLayoutParams)
        }
    }

    override fun onPlaying() {
        super.onPlaying()
        changePopupWindowFlags(ONGOING_PLAYBACK_WINDOW_FLAGS)
    }

    override fun onPaused() {
        super.onPaused()
        changePopupWindowFlags(IDLE_WINDOW_FLAGS)
    }

    override fun onCompleted() {
        super.onCompleted()
        changePopupWindowFlags(IDLE_WINDOW_FLAGS)
    }

    override fun setupSubtitleView(captionScale: Float) {
        val captionRatio = (captionScale - 1.0f) / 5.0f + 1.0f
        binding.subtitleView.setFractionalTextSize(
            SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * captionRatio)
    }

    override fun onPlaybackSpeedClicked() {
        playbackSpeedPopupMenu?.show()
        isSomePopupMenuVisible = true
    }


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Gestures
    ////////////////////////////////////////////////////////////////////////// */
    //region Gestures
    private fun distanceFromCloseButton(popupMotionEvent: MotionEvent): Int {
        val closeOverlayButtonX = (closeOverlayBinding!!.closeButton.left
                + closeOverlayBinding!!.closeButton.width / 2)
        val closeOverlayButtonY = (closeOverlayBinding!!.closeButton.top
                + closeOverlayBinding!!.closeButton.height / 2)

        val fingerX = popupLayoutParams!!.x + popupMotionEvent.x
        val fingerY = popupLayoutParams!!.y + popupMotionEvent.y

        return sqrt((closeOverlayButtonX - fingerX).pow(2.0f) + (closeOverlayButtonY - fingerY).pow(2.0f)).toInt()
    }

    private val closingRadius: Float
        get() {
            val buttonRadius = closeOverlayBinding!!.closeButton.width / 2
            // 20% wider than the button itself
            return buttonRadius * 1.2f
        }

    fun isInsideClosingRadius(popupMotionEvent: MotionEvent): Boolean {
        return distanceFromCloseButton(popupMotionEvent) <= closingRadius
    }


    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Popup & closing overlay layout params + saving popup position and size
    ////////////////////////////////////////////////////////////////////////// */
    //region Popup & closing overlay layout params + saving popup position and size
    /**
     * `screenWidth` and `screenHeight` must have been initialized.
     * @return the popup starting layout params
     */
    @SuppressLint("RtlHardcoded")
    fun retrievePopupLayoutParamsFromPrefs(): WindowManager.LayoutParams {
        val prefs = player.prefs
        val context = player.context

        val popupRememberSizeAndPos = prefs.getBoolean(
            context.getString(R.string.popup_remember_size_pos_key), true)
        val defaultSize = context.resources.getDimension(R.dimen.popup_default_width)
        val popupWidth = if (popupRememberSizeAndPos
        ) prefs.getFloat(context.getString(R.string.popup_saved_width_key), defaultSize)
        else defaultSize
        val popupHeight = PlayerHelper.getMinimumVideoHeight(popupWidth)

        val params = WindowManager.LayoutParams(
            popupWidth.toInt(), popupHeight.toInt(),
            popupLayoutParamType(),
            IDLE_WINDOW_FLAGS,
            PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.LEFT or Gravity.TOP
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        val centerX = (screenWidth / 2f - popupWidth / 2f).toInt()
        val centerY = (screenHeight / 2f - popupHeight / 2f).toInt()
        params.x = if (popupRememberSizeAndPos
        ) prefs.getInt(context.getString(R.string.popup_saved_x_key), centerX) else centerX
        params.y = if (popupRememberSizeAndPos
        ) prefs.getInt(context.getString(R.string.popup_saved_y_key), centerY) else centerY

        return params
    }

    fun savePopupPositionAndSizeToPrefs() {
        if (popupLayoutParams != null) {
            val context = player.context
            player.prefs.edit()
                .putFloat(context.getString(R.string.popup_saved_width_key),
                    popupLayoutParams!!.width.toFloat())
                .putInt(context.getString(R.string.popup_saved_x_key),
                    popupLayoutParams!!.x)
                .putInt(context.getString(R.string.popup_saved_y_key),
                    popupLayoutParams!!.y)
                .apply()
        }
    }

    //endregion
    /*//////////////////////////////////////////////////////////////////////////
    // Getters
    ////////////////////////////////////////////////////////////////////////// */
    //region Getters
    private fun popupHasParent(): Boolean {
        return binding != null && binding.root.layoutParams is WindowManager.LayoutParams && binding.root.parent != null
    }

    private fun anyPopupViewIsNull(): Boolean {
        return popupLayoutParams == null || windowManager == null || binding.root.parent == null
    }

    companion object {
        private val TAG: String = PopupPlayerUi::class.java.simpleName

        /**
         * Maximum opacity allowed for Android 12 and higher to allow touches on other apps when using
         * NewPipe's popup player.
         *
         *
         *
         * This value is hardcoded instead of being get dynamically with the method linked of the
         * constant documentation below, because it is not static and popup player layout parameters
         * are generated with static methods.
         *
         *
         * @see WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
         */
        private const val MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER = 0.8f

        /*//////////////////////////////////////////////////////////////////////////
    // Popup player window manager
    ////////////////////////////////////////////////////////////////////////// */
        const val IDLE_WINDOW_FLAGS: Int = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        const val ONGOING_PLAYBACK_WINDOW_FLAGS: Int = (IDLE_WINDOW_FLAGS
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        @SuppressLint("RtlHardcoded")
        fun buildCloseOverlayLayoutParams(): WindowManager.LayoutParams {
            val flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)

            val closeOverlayLayoutParams = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                popupLayoutParamType(),
                flags,
                PixelFormat.TRANSLUCENT)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Setting maximum opacity allowed for touch events to other apps for Android 12 and
                // higher to prevent non interaction when using other apps with the popup player
                closeOverlayLayoutParams.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER
            }

            closeOverlayLayoutParams.gravity = Gravity.LEFT or Gravity.TOP
            closeOverlayLayoutParams.softInputMode =
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            return closeOverlayLayoutParams
        }

        fun popupLayoutParamType(): Int {
            return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
            ) WindowManager.LayoutParams.TYPE_PHONE
            else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
    }
}
