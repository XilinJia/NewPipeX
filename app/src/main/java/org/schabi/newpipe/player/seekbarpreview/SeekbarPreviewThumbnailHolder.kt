package org.schabi.newpipe.player.seekbarpreview

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.collection.SparseArrayCompat
import com.google.common.base.Stopwatch
import org.schabi.newpipe.extractor.stream.Frameset
import org.schabi.newpipe.player.seekbarpreview.SeekbarPreviewThumbnailHelper.SeekbarPreviewThumbnailType
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.util.image.PicassoHelper.loadSeekbarThumbnailPreview
import java.util.*
import java.util.concurrent.Executors
import java.util.function.Supplier
import kotlin.math.abs

class SeekbarPreviewThumbnailHolder {
    // Key = Position of the picture in milliseconds
    // Supplier = Supplies the bitmap for that position
    private val seekbarPreviewData = SparseArrayCompat<Supplier<Bitmap?>>()

    // This ensures that if the reset is still undergoing
    // and another reset starts, only the last reset is processed
    private var currentUpdateRequestIdentifier: UUID = UUID.randomUUID()

    fun resetFrom(context: Context, framesets: List<Frameset?>) {
        val seekbarPreviewType = SeekbarPreviewThumbnailHelper.getSeekbarPreviewThumbnailType(context)

        val updateRequestIdentifier = UUID.randomUUID()
        this.currentUpdateRequestIdentifier = updateRequestIdentifier

        val executorService = Executors.newSingleThreadExecutor()
        executorService.submit {
            try {
                resetFromAsync(seekbarPreviewType, framesets, updateRequestIdentifier)
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to execute async", ex)
            }
        }
        // ensure that the executorService stops/destroys it's threads
        // after the task is finished
        executorService.shutdown()
    }

    private fun resetFromAsync(seekbarPreviewType: Int, framesets: List<Frameset?>, updateRequestIdentifier: UUID) {
        Logd(TAG, "Clearing seekbarPreviewData")
        synchronized(seekbarPreviewData) {
            seekbarPreviewData.clear()
        }

        if (seekbarPreviewType == SeekbarPreviewThumbnailType.NONE) {
            Logd(TAG, "Not processing seekbarPreviewData due to settings")
            return
        }

        val frameset = getFrameSetForType(framesets, seekbarPreviewType)
        if (frameset == null) {
            Logd(TAG, "No frameset was found to fill seekbarPreviewData")
            return
        }
        Logd(TAG, "Frameset quality info: [width=${frameset.frameWidth}, heigh=${frameset.frameHeight}]")

        // Abort method execution if we are not the latest request
        if (!isRequestIdentifierCurrent(updateRequestIdentifier)) return

        generateDataFrom(frameset, updateRequestIdentifier)
    }

    private fun getFrameSetForType(framesets: List<Frameset?>,
                                   seekbarPreviewType: Int
    ): Frameset? {
        if (seekbarPreviewType == SeekbarPreviewThumbnailType.HIGH_QUALITY) {
            Logd(TAG, "Strategy for seekbarPreviewData: high quality")
            return framesets.stream()
                .max(Comparator.comparingInt { fs: Frameset? -> fs!!.frameHeight * fs.frameWidth })
                .orElse(null)
        } else {
            Logd(TAG, "Strategy for seekbarPreviewData: low quality")
            return framesets.stream()
                .min(Comparator.comparingInt { fs: Frameset? -> fs!!.frameHeight * fs.frameWidth })
                .orElse(null)
        }
    }

    private fun generateDataFrom(frameset: Frameset, updateRequestIdentifier: UUID) {
        Logd(TAG, "Starting generation of seekbarPreviewData")
        val sw = if (Log.isLoggable(TAG, Log.DEBUG)) Stopwatch.createStarted() else null

        var currentPosMs = 0
        var pos = 1

        val urlFrameCount = frameset.framesPerPageX * frameset.framesPerPageY

        // Process each url in the frameset
        for (url in frameset.urls) {
            // get the bitmap
            val srcBitMap = getBitMapFrom(url)

            // The data is not added directly to "seekbarPreviewData" due to
            // concurrency and checks for "updateRequestIdentifier"
            val generatedDataForUrl = SparseArrayCompat<Supplier<Bitmap?>>(urlFrameCount)

            // The bitmap consists of several images, which we process here
            // foreach frame in the returned bitmap
            for (i in 0 until urlFrameCount) {
                // Frames outside the video length are skipped
                if (pos > frameset.totalCount) break

                // Get the bounds where the frame is found
                val bounds = frameset.getFrameBoundsAt(currentPosMs.toLong())
                generatedDataForUrl.put(currentPosMs, Supplier<Bitmap?> {
                    // It can happen, that the original bitmap could not be downloaded
                    // In such a case - we don't want a NullPointer - simply return null
                    if (srcBitMap == null) return@Supplier null

                    Bitmap.createBitmap(srcBitMap, bounds[1], bounds[2], frameset.frameWidth, frameset.frameHeight)
                })

                currentPosMs += frameset.durationPerFrame
                pos++
            }

            // Check if we are still the latest request
            // If not abort method execution
            if (isRequestIdentifierCurrent(updateRequestIdentifier)) {
                synchronized(seekbarPreviewData) {
                    seekbarPreviewData.putAll(generatedDataForUrl)
                }
            } else {
                Logd(TAG, "Aborted of generation of seekbarPreviewData")
                break
            }
        }

        if (sw != null) {
            Logd(TAG, "Generation of seekbarPreviewData took " + sw.stop())
        }
    }

    private fun getBitMapFrom(url: String?): Bitmap? {
        if (url == null) {
            Log.w(TAG, "url is null; This should never happen")
            return null
        }

        val sw = if (Log.isLoggable(TAG, Log.DEBUG)) Stopwatch.createStarted() else null
        try {
            Logd(TAG, "Downloading bitmap for seekbarPreview from '$url'")

            // Gets the bitmap within the timeout of 15 seconds imposed by default by OkHttpClient
            // Ensure that your are not running on the main-Thread this will otherwise hang
            val bitmap = loadSeekbarThumbnailPreview(url).get()

            if (sw != null) {
                Logd(TAG, "Download of bitmap for seekbarPreview from '$url' took ${sw.stop()}")
            }

            return bitmap
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to get bitmap for seekbarPreview from url='$url' in time", ex)
            return null
        }
    }

    private fun isRequestIdentifierCurrent(requestIdentifier: UUID): Boolean {
        return this.currentUpdateRequestIdentifier == requestIdentifier
    }

    fun getBitmapAt(positionInMs: Int): Optional<Bitmap> {
        // Get the frame supplier closest to the requested position
        var closestFrame = Supplier<Bitmap?> { null }
        synchronized(seekbarPreviewData) {
            var min = Int.MAX_VALUE
            for (i in 0 until seekbarPreviewData.size()) {
                val pos = abs((seekbarPreviewData.keyAt(i) - positionInMs).toDouble()).toInt()
                if (pos < min) {
                    closestFrame = seekbarPreviewData.valueAt(i)
                    min = pos
                }
            }
        }

        return Optional.ofNullable(closestFrame.get())
    }

    companion object {
        // This has to be <= 23 chars on devices running Android 7 or lower (API <= 25)
        // or it fails with an IllegalArgumentException
        // https://stackoverflow.com/a/54744028
        const val TAG: String = "SeekbarPrevThumbHolder"
    }
}
