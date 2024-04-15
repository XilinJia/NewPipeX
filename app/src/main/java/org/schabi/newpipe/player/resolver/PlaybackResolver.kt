package org.schabi.newpipe.player.resolver

import android.net.Uri
import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MediaItem.LiveConfiguration
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.dash.manifest.DashManifest
import com.google.android.exoplayer2.source.dash.manifest.DashManifestParser
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource
import com.google.android.exoplayer2.source.smoothstreaming.manifest.SsManifest
import com.google.android.exoplayer2.source.smoothstreaming.manifest.SsManifestParser
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.CreationException
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.*
import org.schabi.newpipe.player.datasource.NonUriHlsDataSourceFactory
import org.schabi.newpipe.player.helper.PlayerDataSource
import org.schabi.newpipe.player.mediaitem.MediaItemTag
import org.schabi.newpipe.player.mediaitem.StreamInfoTag
import org.schabi.newpipe.player.resolver.PlaybackResolver.ResolverException
import org.schabi.newpipe.util.StreamTypeUtil.isLiveStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * This interface is just a shorthand for [Resolver] with [StreamInfo] as source and
 * [MediaSource] as product. It contains many static methods that can be used by classes
 * implementing this interface, and nothing else.
 */
interface PlaybackResolver : Resolver<StreamInfo, MediaSource> {
    //endregion
    //region Resolver exception
    class ResolverException : Exception {
        constructor(message: String?) : super(message)

        constructor(message: String?, cause: Throwable?) : super(message, cause)
    } //endregion

    companion object {
        //region Cache key generation
        private fun commonCacheKeyOf(info: StreamInfo,
                                     stream: Stream,
                                     resolutionOrBitrateUnknown: Boolean
        ): StringBuilder {
            // stream info service id
            val cacheKey = StringBuilder(info.serviceId)

            // stream info id
            cacheKey.append(" ")
            cacheKey.append(info.id)

            // stream id (even if unknown)
            cacheKey.append(" ")
            cacheKey.append(stream.id)

            // mediaFormat (if not null)
            val mediaFormat = stream.format
            if (mediaFormat != null) {
                cacheKey.append(" ")
                cacheKey.append(mediaFormat.getName())
            }

            // content (only if other information is missing)
            // If the media format and the resolution/bitrate are both missing, then we don't have
            // enough information to distinguish this stream from other streams.
            // So, only in that case, we use the content (i.e. url or manifest) to differentiate
            // between streams.
            // Note that if the content were used even when other information is present, then two
            // streams with the same stats but with different contents (e.g. because the url was
            // refreshed) will be considered different (i.e. with a different cacheKey), making the
            // cache useless.
            if (resolutionOrBitrateUnknown && mediaFormat == null) {
                cacheKey.append(" ")
                cacheKey.append(Objects.hash(stream.content, stream.manifestUrl))
            }

            return cacheKey
        }

        /**
         * Builds the cache key of a [video stream][VideoStream].
         *
         *
         *
         * A cache key is unique to the features of the provided video stream, and when possible
         * independent of *transient* parameters (such as the URL of the stream).
         * This ensures that there are no conflicts, but also that the cache is used as much as
         * possible: the same cache should be used for two streams which have the same features but
         * e.g. a different URL, since the URL might have been reloaded in the meantime, but the stream
         * actually referenced by the URL is still the same.
         *
         *
         * @param info        the [stream info][StreamInfo], to distinguish between streams with
         * the same features but coming from different stream infos
         * @param videoStream the [video stream][VideoStream] for which the cache key should be
         * created
         * @return a key to be used to store the cache of the provided [video stream][VideoStream]
         */
        @JvmStatic
        fun cacheKeyOf(info: StreamInfo, videoStream: VideoStream): String? {
            val resolutionUnknown = videoStream.getResolution() == VideoStream.RESOLUTION_UNKNOWN
            val cacheKey = commonCacheKeyOf(info, videoStream, resolutionUnknown)

            // resolution (if known)
            if (!resolutionUnknown) {
                cacheKey.append(" ")
                cacheKey.append(videoStream.getResolution())
            }

            // isVideoOnly
            cacheKey.append(" ")
            cacheKey.append(videoStream.isVideoOnly())

            return cacheKey.toString()
        }

        /**
         * Builds the cache key of an audio stream.
         *
         *
         *
         * A cache key is unique to the features of the provided [audio stream][AudioStream], and
         * when possible independent of *transient* parameters (such as the URL of the stream).
         * This ensures that there are no conflicts, but also that the cache is used as much as
         * possible: the same cache should be used for two streams which have the same features but
         * e.g. a different URL, since the URL might have been reloaded in the meantime, but the stream
         * actually referenced by the URL is still the same.
         *
         *
         * @param info        the [stream info][StreamInfo], to distinguish between streams with
         * the same features but coming from different stream infos
         * @param audioStream the [audio stream][AudioStream] for which the cache key should be
         * created
         * @return a key to be used to store the cache of the provided [audio stream][AudioStream]
         */
        @JvmStatic
        fun cacheKeyOf(info: StreamInfo, audioStream: AudioStream): String? {
            val averageBitrateUnknown = audioStream.averageBitrate == AudioStream.UNKNOWN_BITRATE
            val cacheKey = commonCacheKeyOf(info, audioStream, averageBitrateUnknown)

            // averageBitrate (if known)
            if (!averageBitrateUnknown) {
                cacheKey.append(" ")
                cacheKey.append(audioStream.averageBitrate)
            }

            if (audioStream.audioTrackId != null) {
                cacheKey.append(" ")
                cacheKey.append(audioStream.audioTrackId)
            }

            if (audioStream.audioLocale != null) {
                cacheKey.append(" ")
                cacheKey.append(audioStream.audioLocale!!.isO3Language)
            }

            return cacheKey.toString()
        }

        /**
         * Use common base type [Stream] to handle [AudioStream] or [VideoStream]
         * transparently. For more info see [.cacheKeyOf] or
         * [.cacheKeyOf].
         *
         * @param info   the [stream info][StreamInfo], to distinguish between streams with
         * the same features but coming from different stream infos
         * @param stream the [Stream] ([AudioStream] or [VideoStream])
         * for which the cache key should be created
         * @return a key to be used to store the cache of the provided [Stream]
         */
        @JvmStatic
        fun cacheKeyOf(info: StreamInfo, stream: Stream?): String? {
            if (stream is AudioStream) {
                return cacheKeyOf(info, stream)
            } else if (stream is VideoStream) {
                return cacheKeyOf(info, stream)
            }
            throw RuntimeException("no audio or video stream. That should never happen")
        }


        //endregion
        //region Live media sources
        @JvmStatic
        fun maybeBuildLiveMediaSource(dataSource: PlayerDataSource,
                                      info: StreamInfo
        ): MediaSource? {
            if (!isLiveStream(info.streamType)) {
                return null
            }

            try {
                val tag = StreamInfoTag.of(info)
                if (!info.hlsUrl.isEmpty()) {
                    return buildLiveMediaSource(dataSource, info.hlsUrl, C.CONTENT_TYPE_HLS, tag)
                } else if (!info.dashMpdUrl.isEmpty()) {
                    return buildLiveMediaSource(
                        dataSource, info.dashMpdUrl, C.CONTENT_TYPE_DASH, tag)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error when generating live media source, falling back to standard sources",
                    e)
            }

            return null
        }

        @Throws(ResolverException::class)
        fun buildLiveMediaSource(dataSource: PlayerDataSource,
                                 sourceUrl: String?,
                                 type: @C.ContentType Int,
                                 metadata: MediaItemTag?
        ): MediaSource? {
            val factory: MediaSource.Factory = when (type) {
                C.CONTENT_TYPE_SS -> dataSource.liveSsMediaSourceFactory
                C.CONTENT_TYPE_DASH -> dataSource.liveDashMediaSourceFactory
                C.CONTENT_TYPE_HLS -> dataSource.liveHlsMediaSourceFactory
                C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_RTSP -> throw ResolverException("Unsupported type: $type")
                else -> throw ResolverException("Unsupported type: $type")
            }
            return factory.createMediaSource(
                MediaItem.Builder()
                    .setTag(metadata)
                    .setUri(Uri.parse(sourceUrl))
                    .setLiveConfiguration(
                        LiveConfiguration.Builder()
                            .setTargetOffsetMs(PlayerDataSource.LIVE_STREAM_EDGE_GAP_MILLIS.toLong())
                            .build())
                    .build())
        }


        //endregion
        //region Generic media sources
        @JvmStatic
        @Throws(ResolverException::class)
        fun buildMediaSource(dataSource: PlayerDataSource,
                             stream: Stream,
                             streamInfo: StreamInfo,
                             cacheKey: String,
                             metadata: MediaItemTag
        ): MediaSource? {
            if (streamInfo.service === ServiceList.YouTube) {
                return createYoutubeMediaSource(stream, streamInfo, dataSource, cacheKey, metadata)
            }

            val deliveryMethod = stream.deliveryMethod
            return when (deliveryMethod) {
                DeliveryMethod.PROGRESSIVE_HTTP -> buildProgressiveMediaSource(dataSource,
                    stream,
                    cacheKey,
                    metadata)
                DeliveryMethod.DASH -> buildDashMediaSource(dataSource, stream, cacheKey, metadata)
                DeliveryMethod.HLS -> buildHlsMediaSource(dataSource, stream, cacheKey, metadata)
                DeliveryMethod.SS -> buildSSMediaSource(dataSource, stream, cacheKey, metadata)
                else -> throw ResolverException("Unsupported delivery type: $deliveryMethod")
            }
        }

        @Throws(ResolverException::class)
        private fun buildProgressiveMediaSource(
                dataSource: PlayerDataSource,
                stream: Stream,
                cacheKey: String,
                metadata: MediaItemTag
        ): ProgressiveMediaSource {
            if (!stream.isUrl) {
                throw ResolverException("Non URI progressive contents are not supported")
            }
            throwResolverExceptionIfUrlNullOrEmpty(stream.content)
            return dataSource.progressiveMediaSourceFactory.createMediaSource(
                MediaItem.Builder()
                    .setTag(metadata)
                    .setUri(Uri.parse(stream.content))
                    .setCustomCacheKey(cacheKey)
                    .build())
        }

        @Throws(ResolverException::class)
        private fun buildDashMediaSource(dataSource: PlayerDataSource,
                                         stream: Stream,
                                         cacheKey: String,
                                         metadata: MediaItemTag
        ): DashMediaSource {
            if (stream.isUrl) {
                throwResolverExceptionIfUrlNullOrEmpty(stream.content)
                return dataSource.dashMediaSourceFactory.createMediaSource(
                    MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(Uri.parse(stream.content))
                        .setCustomCacheKey(cacheKey)
                        .build())
            }

            try {
                return dataSource.dashMediaSourceFactory.createMediaSource(
                    createDashManifest(stream.content, stream),
                    MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(manifestUrlToUri(stream.manifestUrl))
                        .setCustomCacheKey(cacheKey)
                        .build())
            } catch (e: IOException) {
                throw ResolverException(
                    "Could not create a DASH media source/manifest from the manifest text", e)
            }
        }

        @Throws(IOException::class)
        private fun createDashManifest(manifestContent: String,
                                       stream: Stream
        ): DashManifest {
            return DashManifestParser().parse(manifestUrlToUri(stream.manifestUrl),
                ByteArrayInputStream(manifestContent.toByteArray(StandardCharsets.UTF_8)))
        }

        @Throws(ResolverException::class)
        private fun buildHlsMediaSource(dataSource: PlayerDataSource,
                                        stream: Stream,
                                        cacheKey: String,
                                        metadata: MediaItemTag
        ): HlsMediaSource {
            if (stream.isUrl) {
                throwResolverExceptionIfUrlNullOrEmpty(stream.content)
                return dataSource.getHlsMediaSourceFactory(null).createMediaSource(
                    MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(Uri.parse(stream.content))
                        .setCustomCacheKey(cacheKey)
                        .build())
            }

            val hlsDataSourceFactoryBuilder =
                NonUriHlsDataSourceFactory.Builder()
            hlsDataSourceFactoryBuilder.setPlaylistString(stream.content)

            return dataSource.getHlsMediaSourceFactory(hlsDataSourceFactoryBuilder)
                .createMediaSource(MediaItem.Builder()
                    .setTag(metadata)
                    .setUri(manifestUrlToUri(stream.manifestUrl))
                    .setCustomCacheKey(cacheKey)
                    .build())
        }

        @Throws(ResolverException::class)
        private fun buildSSMediaSource(dataSource: PlayerDataSource,
                                       stream: Stream,
                                       cacheKey: String,
                                       metadata: MediaItemTag
        ): SsMediaSource {
            if (stream.isUrl) {
                throwResolverExceptionIfUrlNullOrEmpty(stream.content)
                return dataSource.sSMediaSourceFactory.createMediaSource(
                    MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(Uri.parse(stream.content))
                        .setCustomCacheKey(cacheKey)
                        .build())
            }

            val manifestUri = manifestUrlToUri(stream.manifestUrl)

            val smoothStreamingManifest: SsManifest
            try {
                val smoothStreamingManifestInput = ByteArrayInputStream(
                    stream.content.toByteArray(StandardCharsets.UTF_8))
                smoothStreamingManifest = SsManifestParser().parse(manifestUri,
                    smoothStreamingManifestInput)
            } catch (e: IOException) {
                throw ResolverException("Error when parsing manual SS manifest", e)
            }

            return dataSource.sSMediaSourceFactory.createMediaSource(
                smoothStreamingManifest,
                MediaItem.Builder()
                    .setTag(metadata)
                    .setUri(manifestUri)
                    .setCustomCacheKey(cacheKey)
                    .build())
        }


        //endregion
        //region YouTube media sources
        @Throws(ResolverException::class)
        private fun createYoutubeMediaSource(stream: Stream,
                                             streamInfo: StreamInfo,
                                             dataSource: PlayerDataSource,
                                             cacheKey: String,
                                             metadata: MediaItemTag
        ): MediaSource {
            if (!(stream is AudioStream || stream is VideoStream)) {
                throw ResolverException("Generation of YouTube DASH manifest for " + stream.javaClass.simpleName + " is not supported")
            }

            val streamType = streamInfo.streamType
            when (streamType) {
                StreamType.VIDEO_STREAM -> {
                    return createYoutubeMediaSourceOfVideoStreamType(dataSource, stream, streamInfo,
                        cacheKey, metadata)
                }
                StreamType.POST_LIVE_STREAM -> {
                    // If the content is not an URL, uses the DASH delivery method and if the stream type
                    // of the stream is a post live stream, it means that the content is an ended
                    // livestream so we need to generate the manifest corresponding to the content
                    // (which is the last segment of the stream)

                    try {
                        val itagItem = stream.itagItem ?: throw ResolverException("itagItem is null")
                        val manifestString = YoutubePostLiveStreamDvrDashManifestCreator
                            .fromPostLiveStreamDvrStreamingUrl(stream.content,
                                itagItem,
                                itagItem.targetDurationSec,
                                streamInfo.duration)
                        return buildYoutubeManualDashMediaSource(dataSource,
                            createDashManifest(manifestString, stream), stream, cacheKey,
                            metadata)
                    } catch (e: CreationException) {
                        throw ResolverException(
                            "Error when generating the DASH manifest of YouTube ended live stream", e)
                    } catch (e: IOException) {
                        throw ResolverException(
                            "Error when generating the DASH manifest of YouTube ended live stream", e)
                    } catch (e: NullPointerException) {
                        throw ResolverException(
                            "Error when generating the DASH manifest of YouTube ended live stream", e)
                    }
                }
                else -> {
                    throw ResolverException(
                        "DASH manifest generation of YouTube livestreams is not supported")
                }
            }
        }

        @Throws(ResolverException::class)
        private fun createYoutubeMediaSourceOfVideoStreamType(
                dataSource: PlayerDataSource,
                stream: Stream,
                streamInfo: StreamInfo,
                cacheKey: String,
                metadata: MediaItemTag
        ): MediaSource {
            val deliveryMethod = stream.deliveryMethod
            when (deliveryMethod) {
                DeliveryMethod.PROGRESSIVE_HTTP -> if ((stream is VideoStream && stream.isVideoOnly()) || stream is AudioStream) {
                    try {
                        if (stream.itagItem == null) throw ResolverException("stream.itagItem is null")
                        val manifestString = YoutubeProgressiveDashManifestCreator
                            .fromProgressiveStreamingUrl(stream.content,
                                stream.itagItem!!,
                                streamInfo.duration)
                        return buildYoutubeManualDashMediaSource(dataSource,
                            createDashManifest(manifestString, stream), stream, cacheKey,
                            metadata)
                    } catch (e: CreationException) {
                        Log.w(TAG, "Error when generating or parsing DASH manifest of "
                                + "YouTube progressive stream, falling back to a "
                                + "ProgressiveMediaSource.", e)
                        return buildYoutubeProgressiveMediaSource(dataSource, stream, cacheKey,
                            metadata)
                    } catch (e: IOException) {
                        Log.w(TAG, "Error when generating or parsing DASH manifest of "
                                + "YouTube progressive stream, falling back to a "
                                + "ProgressiveMediaSource.", e)
                        return buildYoutubeProgressiveMediaSource(dataSource, stream, cacheKey,
                            metadata)
                    } catch (e: NullPointerException) {
                        Log.w(TAG, "Error when generating or parsing DASH manifest of "
                                + "YouTube progressive stream, falling back to a "
                                + "ProgressiveMediaSource.", e)
                        return buildYoutubeProgressiveMediaSource(dataSource, stream, cacheKey,
                            metadata)
                    }
                } else {
                    // Legacy progressive streams, subtitles are handled by
                    // VideoPlaybackResolver
                    return buildYoutubeProgressiveMediaSource(dataSource, stream, cacheKey,
                        metadata)
                }
                DeliveryMethod.DASH -> {
                    // If the content is not a URL, uses the DASH delivery method and if the stream
                    // type of the stream is a video stream, it means the content is an OTF stream
                    // so we need to generate the manifest corresponding to the content (which is
                    // the base URL of the OTF stream).
                    try {
                        if (stream.itagItem == null) throw ResolverException("stream.itagItem is null")
                        val manifestString = YoutubeOtfDashManifestCreator
                            .fromOtfStreamingUrl(stream.content,
                                stream.itagItem!!,
                                streamInfo.duration)
                        return buildYoutubeManualDashMediaSource(dataSource,
                            createDashManifest(manifestString, stream), stream, cacheKey,
                            metadata)
                    } catch (e: CreationException) {
                        Log.e(TAG,
                            "Error when generating the DASH manifest of YouTube OTF stream", e)
                        throw ResolverException(
                            "Error when generating the DASH manifest of YouTube OTF stream", e)
                    } catch (e: IOException) {
                        Log.e(TAG,
                            "Error when generating the DASH manifest of YouTube OTF stream", e)
                        throw ResolverException(
                            "Error when generating the DASH manifest of YouTube OTF stream", e)
                    } catch (e: NullPointerException) {
                        Log.e(TAG,
                            "Error when generating the DASH manifest of YouTube OTF stream", e)
                        throw ResolverException(
                            "Error when generating the DASH manifest of YouTube OTF stream", e)
                    }
                    return dataSource.youtubeHlsMediaSourceFactory.createMediaSource(
                        MediaItem.Builder()
                            .setTag(metadata)
                            .setUri(Uri.parse(stream.content))
                            .setCustomCacheKey(cacheKey)
                            .build())
                }
                DeliveryMethod.HLS -> return dataSource.youtubeHlsMediaSourceFactory.createMediaSource(
                    MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(Uri.parse(stream.content))
                        .setCustomCacheKey(cacheKey)
                        .build())
                else -> throw ResolverException("Unsupported delivery method for YouTube contents: "
                        + deliveryMethod)
            }
        }

        private fun buildYoutubeManualDashMediaSource(
                dataSource: PlayerDataSource,
                dashManifest: DashManifest,
                stream: Stream,
                cacheKey: String,
                metadata: MediaItemTag
        ): DashMediaSource {
            return dataSource.youtubeDashMediaSourceFactory.createMediaSource(dashManifest,
                MediaItem.Builder()
                    .setTag(metadata)
                    .setUri(Uri.parse(stream.content))
                    .setCustomCacheKey(cacheKey)
                    .build())
        }

        private fun buildYoutubeProgressiveMediaSource(
                dataSource: PlayerDataSource,
                stream: Stream,
                cacheKey: String,
                metadata: MediaItemTag
        ): ProgressiveMediaSource {
            return dataSource.youtubeProgressiveMediaSourceFactory
                .createMediaSource(MediaItem.Builder()
                    .setTag(metadata)
                    .setUri(Uri.parse(stream.content))
                    .setCustomCacheKey(cacheKey)
                    .build())
        }


        //endregion
        //region Utils
        private fun manifestUrlToUri(manifestUrl: String?): Uri {
            return Uri.parse(Objects.requireNonNullElse(manifestUrl, ""))
        }

        @Throws(ResolverException::class)
        private fun throwResolverExceptionIfUrlNullOrEmpty(url: String?) {
            if (url == null) {
                throw ResolverException("Null stream URL")
            } else if (url.isEmpty()) {
                throw ResolverException("Empty stream URL")
            }
        }


        val TAG: String = PlaybackResolver::class.java.simpleName
    }
}
