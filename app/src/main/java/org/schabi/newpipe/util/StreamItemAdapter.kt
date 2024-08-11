package org.schabi.newpipe.util

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.collection.SparseArrayCompat
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.giga.util.Utility
import java.io.Serializable
import java.util.*
import java.util.concurrent.Callable
import java.util.stream.Collectors

/**
 * A list adapter for a list of [streams][Stream].
 * It currently supports [VideoStream], [AudioStream] and [SubtitlesStream].
 *
 * @param <T> the primary stream type's class extending [Stream]
 * @param <U> the secondary stream type's class extending [Stream]
</U></T> */
class StreamItemAdapter<T : Stream, U : Stream>(private val streamsWrapper: StreamInfoWrapper<T>,
        val allSecondary: SparseArrayCompat<SecondaryStreamHelper<U>>) : BaseAdapter() {
    /**
     * Indicates that at least one of the primary streams is an instance of [VideoStream],
     * has no audio ([VideoStream.isVideoOnly] returns true) and has no secondary stream
     * associated with it.
     */
    private val hasAnyVideoOnlyStreamWithNoSecondaryStream: Boolean

    init {
        this.hasAnyVideoOnlyStreamWithNoSecondaryStream = checkHasAnyVideoOnlyStreamWithNoSecondaryStream()
    }

    constructor(streamsWrapper: StreamInfoWrapper<T>) : this(streamsWrapper, SparseArrayCompat<SecondaryStreamHelper<U>>(0))

    val all: List<T>
        get() = streamsWrapper.streamsList

    override fun getCount(): Int {
        return streamsWrapper.streamsList.size
    }

    override fun getItem(position: Int): T {
        return streamsWrapper.streamsList[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getDropDownView(position: Int, convertView: View, parent: ViewGroup): View {
        return getCustomView(position, convertView, parent, true)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        return getCustomView((parent as Spinner).selectedItemPosition, convertView, parent, false)
    }

    private fun getCustomView(position: Int, view: View?, parent: ViewGroup, isDropdownItem: Boolean): View {
        val context = parent.context
        val convertView = view ?: LayoutInflater.from(context).inflate(R.layout.stream_quality_item, parent, false)

        val woSoundIconView = convertView.findViewById<ImageView>(R.id.wo_sound_icon)
        val formatNameView = convertView.findViewById<TextView>(R.id.stream_format_name)
        val qualityView = convertView.findViewById<TextView>(R.id.stream_quality)
        val sizeView = convertView.findViewById<TextView>(R.id.stream_size)

        val stream = getItem(position)
        val mediaFormat = streamsWrapper.getFormat(position)

        var woSoundIconVisibility = View.GONE
        var qualityString: String?

        when (stream) {
            is VideoStream -> {
                val videoStream = (stream as VideoStream)
                qualityString = videoStream.getResolution()

                if (hasAnyVideoOnlyStreamWithNoSecondaryStream) {
                    if (videoStream.isVideoOnly()) {
                        // It has a secondary stream associated with it, so check if it's a
                        // dropdown view so it doesn't look out of place (missing margin)
                        // compared to those that don't.
                        // It doesn't have a secondary stream, icon is visible no matter what.
                        woSoundIconVisibility = if (allSecondary[position] != null) {
                            if (isDropdownItem) View.INVISIBLE else View.GONE
                        } else View.VISIBLE
                    } else if (isDropdownItem) {
                        woSoundIconVisibility = View.INVISIBLE
                    }
                }
            }
            is AudioStream -> {
                val audioStream = (stream as AudioStream)
                qualityString = if (audioStream.averageBitrate > 0) {
                    audioStream.averageBitrate.toString() + "kbps"
                } else {
                    context.getString(R.string.unknown_quality)
                }
            }
            is SubtitlesStream -> {
                qualityString = (stream as SubtitlesStream).displayLanguageName
                if ((stream as SubtitlesStream).isAutoGenerated) {
                    qualityString += " (" + context.getString(R.string.caption_auto_generated) + ")"
                }
            }
            else -> {
                qualityString = mediaFormat?.getSuffix() ?: context.getString(R.string.unknown_quality)
            }
        }

        if (streamsWrapper.getSizeInBytes(position) > 0) {
            val secondary = allSecondary[position]
            if (secondary != null) {
                val size = (secondary.sizeInBytes
                        + streamsWrapper.getSizeInBytes(position))
                sizeView.text = Utility.formatBytes(size)
            } else {
                sizeView.text = streamsWrapper.getFormattedSize(position)
            }
            sizeView.visibility = View.VISIBLE
        } else {
            sizeView.visibility = View.GONE
        }

        if (stream is SubtitlesStream) {
            formatNameView.text = (stream as SubtitlesStream).languageTag
        } else {
            if (mediaFormat == null) {
                formatNameView.text = context.getString(R.string.unknown_format)
            } else if (mediaFormat == MediaFormat.WEBMA_OPUS) {
                // noinspection AndroidLintSetTextI18n
                formatNameView.text = "opus"
            } else {
                formatNameView.text = mediaFormat.getName()
            }
        }

        qualityView.text = qualityString
        woSoundIconView.visibility = woSoundIconVisibility

        return convertView
    }

    /**
     * @return if there are any video-only streams with no secondary stream associated with them.
     * @see .hasAnyVideoOnlyStreamWithNoSecondaryStream
     */
    private fun checkHasAnyVideoOnlyStreamWithNoSecondaryStream(): Boolean {
        for (i in streamsWrapper.streamsList.indices) {
            val stream: T = streamsWrapper.streamsList[i]
            if (stream is VideoStream) {
                val videoOnly = (stream as VideoStream).isVideoOnly()
                if (videoOnly && allSecondary[i] == null) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * A wrapper class that includes a way of storing the stream sizes.
     *
     * @param <T> the stream type's class extending [Stream]
    </T> */
    class StreamInfoWrapper<T : Stream>(streamList: List<T>,
                                         context: Context?
    ) : Serializable {
        @JvmField
        val streamsList: List<T> = streamList
        private val streamSizes = LongArray(streamsList.size)
        private val streamFormats = arrayOfNulls<MediaFormat>(streamsList.size)
        private val unknownSize = context?.getString(R.string.unknown_content) ?: "--.-"

        init {
            resetInfo()
        }

        fun resetInfo() {
            Arrays.fill(streamSizes, SIZE_UNSET.toLong())
            for (i in streamsList.indices) {
                streamFormats[i] = if (streamsList[i] == null // test for invalid streams
                ) null else streamsList[i]!!.format
            }
        }

        fun getSizeInBytes(streamIndex: Int): Long {
            return streamSizes[streamIndex]
        }

        fun getSizeInBytes(stream: T): Long {
            return streamSizes[streamsList.indexOf(stream)]
        }

        fun getFormattedSize(streamIndex: Int): String {
            return formatSize(getSizeInBytes(streamIndex))
        }

        private fun formatSize(size: Long): String {
            if (size > -1) {
                return Utility.formatBytes(size)
            }
            return unknownSize
        }

        fun setSize(stream: T, sizeInBytes: Long) {
            streamSizes[streamsList.indexOf(stream)] = sizeInBytes
        }

        fun getFormat(streamIndex: Int): MediaFormat? {
            return streamFormats[streamIndex]
        }

        fun setFormat(stream: T, format: MediaFormat?) {
            streamFormats[streamsList.indexOf(stream)] = format
        }

        companion object {
            private val EMPTY = StreamInfoWrapper(emptyList<Stream>(), null)
            private const val SIZE_UNSET = -2

            /**
             * Helper method to fetch the sizes and missing media formats
             * of all the streams in a wrapper.
             *
             * @param <X> the stream type's class extending [Stream]
             * @param streamsWrapper the wrapper
             * @return a [Single] that returns a boolean indicating if any elements were changed
            </X> */
            @JvmStatic
            fun <X : Stream> fetchMoreInfoForWrapper(streamsWrapper: StreamInfoWrapper<X>): Single<Boolean> {
                val fetchAndSet = Callable {
                    var hasChanged = false
                    for (stream in streamsWrapper.streamsList) {
                        val changeSize = streamsWrapper.getSizeInBytes(stream) <= SIZE_UNSET
                        val changeFormat = stream!!.format == null
                        if (!changeSize && !changeFormat) {
                            continue
                        }
                        val response = DownloaderImpl.instance!!.head(stream.content)
                        if (changeSize) {
                            val contentLength = response.getHeader("Content-Length")
                            if (!Utils.isNullOrEmpty(contentLength)) {
                                streamsWrapper.setSize(stream, contentLength!!.toLong())
                                hasChanged = true
                            }
                        }
                        if (changeFormat) {
                            hasChanged = (retrieveMediaFormat(stream, streamsWrapper, response) || hasChanged)
                        }
                    }
                    hasChanged
                }

                return Single.fromCallable(fetchAndSet)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .onErrorReturnItem(true)
            }

            /**
             * Try to retrieve the [MediaFormat] for a stream from the request headers.
             *
             * @param <X>            the stream type to get the [MediaFormat] for
             * @param stream         the stream to find the [MediaFormat] for
             * @param streamsWrapper the wrapper to store the found [MediaFormat] in
             * @param response       the response of the head request for the given stream
             * @return `true` if the media format could be retrieved; `false` otherwise
            </X> */
            @VisibleForTesting
            fun <X : Stream> retrieveMediaFormat(
                    stream: X,
                    streamsWrapper: StreamInfoWrapper<X>,
                    response: Response
            ): Boolean {
                return (retrieveMediaFormatFromFileTypeHeaders(stream, streamsWrapper, response)
                        || retrieveMediaFormatFromContentDispositionHeader(
                    stream, streamsWrapper, response)
                        || retrieveMediaFormatFromContentTypeHeader(stream, streamsWrapper, response))
            }

            @VisibleForTesting
            fun <X : Stream> retrieveMediaFormatFromFileTypeHeaders(
                    stream: X,
                    streamsWrapper: StreamInfoWrapper<X>,
                    response: Response
            ): Boolean {
                // try to use additional headers from CDNs or servers,
                // e.g. x-amz-meta-file-type (e.g. for SoundCloud)
                val keys = response.responseHeaders().keys.stream()
                    .filter { k: String -> k.endsWith("file-type") }.collect(Collectors.toList())
                if (!keys.isEmpty()) {
                    for (key in keys) {
                        val suffix = response.getHeader(key)
                        val format = MediaFormat.getFromSuffix(suffix)
                        if (format != null) {
                            streamsWrapper.setFormat(stream, format)
                            return true
                        }
                    }
                }
                return false
            }

            /**
             *
             * Retrieve a [MediaFormat] from a HTTP Content-Disposition header
             * for a stream and store the info in a wrapper.
             * @see  [
             * mdn Web Docs for the HTTP Content-Disposition Header](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Disposition)
             *
             * @param stream the stream to get the [MediaFormat] for
             * @param streamsWrapper the wrapper to store the [MediaFormat] in
             * @param response the response to get the Content-Disposition header from
             * @return `true` if the [MediaFormat] could be retrieved from the response;
             * otherwise `false`
             * @param <X>
            </X> */
            @VisibleForTesting
            fun <X : Stream> retrieveMediaFormatFromContentDispositionHeader(
                    stream: X,
                    streamsWrapper: StreamInfoWrapper<X>,
                    response: Response
            ): Boolean {
                // parse the Content-Disposition header,
                // see
                // there can be two filename directives
                var contentDisposition: String? = response.getHeader("Content-Disposition") ?: return false
                try {
                    contentDisposition = Utils.decodeUrlUtf8(contentDisposition)
                    val parts = contentDisposition.split(";".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                    for (part_ in parts) {
                        val part = part_.trim { it <= ' ' }

                        // extract the filename
                        val fileName = if (part.startsWith("filename=")) {
                            // remove directive and decode
                            Utils.decodeUrlUtf8(part.substring(9))
                        } else if (part.startsWith("filename*=")) {
                            Utils.decodeUrlUtf8(part.substring(10))
                        } else {
                            continue
                        }

                        // extract the file extension / suffix
                        val p = fileName.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                        var suffix = p[p.size - 1]
                        if (suffix.endsWith("\"") || suffix.endsWith("'")) {
                            // remove trailing quotes if present, end index is exclusive
                            suffix = suffix.substring(0, suffix.length - 1)
                        }

                        // get the corresponding media format
                        val format = MediaFormat.getFromSuffix(suffix)
                        if (format != null) {
                            streamsWrapper.setFormat(stream, format)
                            return true
                        }
                    }
                } catch (ignored: Exception) {
                    // fail silently
                }
                return false
            }

            @VisibleForTesting
            fun <X : Stream> retrieveMediaFormatFromContentTypeHeader(
                    stream: X,
                    streamsWrapper: StreamInfoWrapper<X>,
                    response: Response
            ): Boolean {
                // try to get the format by content type
                // some mime types are not unique for every format, those are omitted
                val contentTypeHeader = response.getHeader("Content-Type") ?: return false

                var foundFormat: MediaFormat? = null
                for (format in MediaFormat.getAllFromMimeType(contentTypeHeader)) {
                    if (foundFormat == null) {
                        foundFormat = format
                    } else if (foundFormat.id != format.id) {
                        return false
                    }
                }
                if (foundFormat != null) {
                    streamsWrapper.setFormat(stream, foundFormat)
                    return true
                }
                return false
            }

            @JvmStatic
            fun <X : Stream> empty(): StreamInfoWrapper<X> {
                return EMPTY as StreamInfoWrapper<X>
            }
        }
    }
}
