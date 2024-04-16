package org.schabi.newpipe.views

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout

@UnstableApi class ExpandableSurfaceView(context: Context?, attrs: AttributeSet?) : SurfaceView(context, attrs) {
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var baseHeight = 0
    private var maxHeight = 0
    private var videoAspectRatio = 0.0f
    private var scaleX = 1.0f
    private var scaleY = 1.0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (videoAspectRatio == 0.0f) {
            return
        }

        var width = MeasureSpec.getSize(widthMeasureSpec)
        val verticalVideo = videoAspectRatio < 1
        // Use maxHeight only on non-fit resize mode and in vertical videos
        var height =
            if (maxHeight != 0 && resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT && verticalVideo) maxHeight else baseHeight

        if (height == 0) {
            return
        }

        val viewAspectRatio = width / (height.toFloat())
        val aspectDeformation = videoAspectRatio / viewAspectRatio - 1
        scaleX = 1.0f
        scaleY = 1.0f

        if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
            if (aspectDeformation > 0) {
                height = (width / videoAspectRatio).toInt()
            } else {
                width = (height * videoAspectRatio).toInt()
            }
        } else if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
            if (aspectDeformation < 0) {
                scaleY = viewAspectRatio / videoAspectRatio
            } else {
                scaleX = videoAspectRatio / viewAspectRatio
            }
        }

        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    /**
     * Scale view only in [.onLayout] to make transition for ZOOM mode as smooth as possible.
     */
    override fun onLayout(changed: Boolean,
                          left: Int, top: Int, right: Int, bottom: Int
    ) {
        setScaleX(scaleX)
        setScaleY(scaleY)
    }

    /**
     * @param base The height that will be used in every resize mode as a minimum height
     * @param max  The max height for vertical videos in non-FIT resize modes
     */
    fun setHeights(base: Int, max: Int) {
        if (baseHeight == base && maxHeight == max) {
            return
        }
        baseHeight = base
        maxHeight = max
        requestLayout()
    }

    fun setResizeMode(newResizeMode: @AspectRatioFrameLayout.ResizeMode Int) {
        if (resizeMode == newResizeMode) {
            return
        }

        resizeMode = newResizeMode
        requestLayout()
    }

    fun getResizeMode(): @AspectRatioFrameLayout.ResizeMode Int {
        return resizeMode
    }

    fun setAspectRatio(aspectRatio: Float) {
        if (videoAspectRatio == aspectRatio) {
            return
        }

        videoAspectRatio = aspectRatio
        requestLayout()
    }
}
