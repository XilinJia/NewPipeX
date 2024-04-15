package org.schabi.newpipe.util.image

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.core.graphics.BitmapCompat
import com.squareup.picasso.*
import okhttp3.Cache
import okhttp3.OkHttpClient
//import okhttp3.OkHttpClient.Builder.build
//import okhttp3.OkHttpClient.Builder.cache
//import okhttp3.OkHttpClient.Builder.callTimeout
//import okhttp3.OkHttpClient.cache
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.util.image.ImageStrategy.choosePreferredImage
import org.schabi.newpipe.util.image.ImageStrategy.shouldLoadImages
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

object PicassoHelper {
    private val TAG: String = PicassoHelper::class.java.simpleName
    private const val PLAYER_THUMBNAIL_TRANSFORMATION_KEY = "PICASSO_PLAYER_THUMBNAIL_TRANSFORMATION_KEY"

    private var picassoCache: com.squareup.picasso.Cache? = null
    private var picassoDownloaderClient: OkHttpClient? = null

    // suppress because terminate() is called in App.onTerminate(), preventing leaks
    @SuppressLint("StaticFieldLeak")
    private var picassoInstance: Picasso? = null


    @JvmStatic
    fun init(context: Context) {
        picassoCache = LruCache(10 * 1024 * 1024)
        picassoDownloaderClient = OkHttpClient.Builder()
            .cache(Cache(File(context.externalCacheDir, "picasso"),
                50L * 1024L * 1024L)) // this should already be the default timeout in OkHttp3, but just to be sure...
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

        picassoInstance = Picasso.Builder(context)
            .memoryCache(picassoCache!!) // memory cache
            .downloader(OkHttp3Downloader(picassoDownloaderClient)) // disk cache
            .defaultBitmapConfig(Bitmap.Config.RGB_565)
            .build()
    }

    @JvmStatic
    fun terminate() {
        picassoCache = null
        picassoDownloaderClient = null

        if (picassoInstance != null) {
            picassoInstance!!.shutdown()
            picassoInstance = null
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun clearCache(context: Context) {
        picassoInstance!!.shutdown()
        picassoCache!!.clear() // clear memory cache
        val diskCache = picassoDownloaderClient!!.cache
        diskCache?.delete()
        init(context)
    }

    @JvmStatic
    fun cancelTag(tag: Any?) {
        picassoInstance!!.cancelTag(tag!!)
    }

    @JvmStatic
    fun setIndicatorsEnabled(enabled: Boolean) {
        picassoInstance!!.setIndicatorsEnabled(enabled) // useful for debugging
    }


    fun loadAvatar(images: List<Image>): RequestCreator {
        return loadImageDefault(images, R.drawable.placeholder_person)
    }

    @JvmStatic
    fun loadAvatar(url: String?): RequestCreator {
        return loadImageDefault(url, R.drawable.placeholder_person)
    }

    fun loadThumbnail(images: List<Image>): RequestCreator {
        return loadImageDefault(images, R.drawable.placeholder_thumbnail_video)
    }

    @JvmStatic
    fun loadThumbnail(url: String?): RequestCreator {
        return loadImageDefault(url, R.drawable.placeholder_thumbnail_video)
    }

    fun loadDetailsThumbnail(images: List<Image>): RequestCreator {
        return loadImageDefault(choosePreferredImage(images),
            R.drawable.placeholder_thumbnail_video, false)
    }

    fun loadBanner(images: List<Image>): RequestCreator {
        return loadImageDefault(images, R.drawable.placeholder_channel_banner)
    }

    fun loadPlaylistThumbnail(images: List<Image>): RequestCreator {
        return loadImageDefault(images, R.drawable.placeholder_thumbnail_playlist)
    }

    @JvmStatic
    fun loadPlaylistThumbnail(url: String?): RequestCreator {
        return loadImageDefault(url, R.drawable.placeholder_thumbnail_playlist)
    }

    @JvmStatic
    fun loadSeekbarThumbnailPreview(url: String?): RequestCreator {
        return picassoInstance!!.load(url)
    }

    fun loadNotificationIcon(url: String?): RequestCreator {
        return loadImageDefault(url, R.drawable.ic_newpipe_triangle_white)
    }


    fun loadScaledDownThumbnail(context: Context,
                                images: List<Image>
    ): RequestCreator {
        // scale down the notification thumbnail for performance
        return loadThumbnail(images)
            .transform(object : Transformation {
                override fun transform(source: Bitmap): Bitmap {
                    if (MainActivity.DEBUG) {
                        Log.d(TAG, "Thumbnail - transform() called")
                    }

                    val notificationThumbnailWidth = min(
                        context.resources
                            .getDimension(R.dimen.player_notification_thumbnail_width).toDouble(),
                        source.width.toDouble()).toFloat()

                    val result = BitmapCompat.createScaledBitmap(
                        source,
                        notificationThumbnailWidth.toInt(),
                        (source.height
                                / (source.width / notificationThumbnailWidth)).toInt(),
                        null,
                        true)

                    if (result == source || !result.isMutable) {
                        // create a new mutable bitmap to prevent strange crashes on some
                        // devices (see #4638)
                        val copied = BitmapCompat.createScaledBitmap(
                            source,
                            notificationThumbnailWidth.toInt() - 1,
                            (source.height / (source.width
                                    / (notificationThumbnailWidth - 1))).toInt(),
                            null,
                            true)
                        source.recycle()
                        return copied
                    } else {
                        source.recycle()
                        return result
                    }
                }

                override fun key(): String {
                    return PLAYER_THUMBNAIL_TRANSFORMATION_KEY
                }
            })
    }

    fun getImageFromCacheIfPresent(imageUrl: String): Bitmap? {
        // URLs in the internal cache finish with \n so we need to add \n to image URLs
        return picassoCache!![imageUrl + "\n"]
    }


    private fun loadImageDefault(images: List<Image>,
                                 @DrawableRes placeholderResId: Int
    ): RequestCreator {
        return loadImageDefault(choosePreferredImage(images), placeholderResId)
    }

    private fun loadImageDefault(url: String?,
                                 @DrawableRes placeholderResId: Int,
                                 showPlaceholderWhileLoading: Boolean = true
    ): RequestCreator {
        // if the URL was chosen with `choosePreferredImage` it will be null, but check again
        // `shouldLoadImages` in case the URL was chosen with `imageListToDbUrl` (which is the case
        // for URLs stored in the database)
        if (Utils.isNullOrEmpty(url) || !shouldLoadImages()) {
            return picassoInstance!!
                .load(null as String?)
                .placeholder(placeholderResId) // show placeholder when no image should load
                .error(placeholderResId)
        } else {
            val requestCreator = picassoInstance!!
                .load(url)
                .error(placeholderResId)
            if (showPlaceholderWhileLoading) {
                requestCreator.placeholder(placeholderResId)
            }
            return requestCreator
        }
    }
}
