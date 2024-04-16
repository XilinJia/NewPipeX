package org.schabi.newpipe.player.resolver

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.C.RoleFlags
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.player.helper.PlayerDataSource
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.player.mediaitem.MediaItemTag
import org.schabi.newpipe.player.mediaitem.StreamInfoTag
import org.schabi.newpipe.player.resolver.PlaybackResolver.Companion.buildMediaSource
import org.schabi.newpipe.player.resolver.PlaybackResolver.Companion.cacheKeyOf
import org.schabi.newpipe.player.resolver.PlaybackResolver.Companion.maybeBuildLiveMediaSource
import org.schabi.newpipe.player.resolver.PlaybackResolver.ResolverException
import org.schabi.newpipe.util.ListHelper.getAudioFormatIndex
import org.schabi.newpipe.util.ListHelper.getFilteredAudioStreams
import org.schabi.newpipe.util.ListHelper.getPlayableStreams
import org.schabi.newpipe.util.ListHelper.getSortedStreamVideosList
import org.schabi.newpipe.util.ListHelper.getUrlAndNonTorrentStreams
import java.util.*
import java.util.function.Function

@UnstableApi class VideoPlaybackResolver(private val context: Context,
                            private val dataSource: PlayerDataSource,
                            private val qualityResolver: QualityResolver)
    : PlaybackResolver {

    internal var streamSourceType: SourceType? = null

    var playbackQuality: String? = null
    var audioTrack: String? = null

    enum class SourceType {
        LIVE_STREAM,
        VIDEO_WITH_SEPARATED_AUDIO,
        VIDEO_WITH_AUDIO_OR_AUDIO_ONLY
    }

    override fun resolve(info: StreamInfo): MediaSource? {
        val liveSource = maybeBuildLiveMediaSource(dataSource, info)
        if (liveSource != null) {
            streamSourceType = SourceType.LIVE_STREAM
            return liveSource
        }

        val mediaSources: MutableList<MediaSource> = ArrayList()

        // Create video stream source
        val videoStreamsList = getSortedStreamVideosList(context, getPlayableStreams(info.videoStreams, info.serviceId),
            getPlayableStreams(info.videoOnlyStreams, info.serviceId), false, true)
        val audioStreamsList: List<AudioStream> = getFilteredAudioStreams(context, info.audioStreams).filterNotNull()
        val videoIndex = when {
            videoStreamsList.isEmpty() -> {
                -1
            }
            playbackQuality == null -> {
                qualityResolver.getDefaultResolutionIndex(videoStreamsList)
            }
            else -> {
                qualityResolver.getOverrideResolutionIndex(videoStreamsList, playbackQuality)
            }
        }

        val audioIndex = getAudioFormatIndex(context, audioStreamsList, audioTrack)
        val tag: MediaItemTag = StreamInfoTag.of(info, videoStreamsList, videoIndex, audioStreamsList, audioIndex)
        val video = tag.maybeQuality
            .map<VideoStream?>(Function<MediaItemTag.Quality, VideoStream?> { it.selectedVideoStream })
            .orElse(null)
        val audio = tag.maybeAudioTrack
            .map<AudioStream?>(Function<MediaItemTag.AudioTrack, AudioStream?> { it.selectedAudioStream })
            .orElse(null)

        if (video != null) {
            try {
                val streamSource = buildMediaSource(dataSource, video, info, cacheKeyOf(info, video)!!, tag)
                if (streamSource != null) mediaSources.add(streamSource)
            } catch (e: ResolverException) {
                Log.e(TAG, "Unable to create video source", e)
                return null
            }
        }

        // Use the audio stream if there is no video stream, or
        // merge with audio stream in case if video does not contain audio
        if (audio != null && (video == null || video.isVideoOnly() || audioTrack != null)) {
            try {
                val audioSource = buildMediaSource(dataSource, audio, info, cacheKeyOf(info, audio)!!, tag)
                if (audioSource != null) mediaSources.add(audioSource)
                streamSourceType = SourceType.VIDEO_WITH_SEPARATED_AUDIO
            } catch (e: ResolverException) {
                Log.e(TAG, "Unable to create audio source", e)
                return null
            }
        } else {
            streamSourceType = SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY
        }

        // If there is no audio or video sources, then this media source cannot be played back
        if (mediaSources.isEmpty()) return null

        // Below are auxiliary media sources

        // Create subtitle sources
        val subtitlesStreams = info.subtitles
        if (subtitlesStreams != null) {
            // Torrent and non URL subtitles are not supported by ExoPlayer
            val nonTorrentAndUrlStreams: List<SubtitlesStream> = getUrlAndNonTorrentStreams(subtitlesStreams)
            for (subtitle in nonTorrentAndUrlStreams) {
                val mediaFormat = subtitle.format
                if (mediaFormat != null) {
                    val textRoleFlag: @RoleFlags Int = if (subtitle.isAutoGenerated) C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND else C.ROLE_FLAG_CAPTION
                    val textMediaItem = SubtitleConfiguration.Builder(
                        Uri.parse(subtitle.content))
                        .setMimeType(mediaFormat.getMimeType())
                        .setRoleFlags(textRoleFlag)
                        .setLanguage(PlayerHelper.captionLanguageOf(context, subtitle))
                        .build()
                    val textSource: MediaSource = dataSource.singleSampleMediaSourceFactory
                        .createMediaSource(textMediaItem, C.TIME_UNSET)
                    mediaSources.add(textSource)
                }
            }
        }

        return if (mediaSources.size == 1) {
            mediaSources[0]
        } else {
            MergingMediaSource(true, *mediaSources.toTypedArray<MediaSource>())
        }
    }

    /**
     * Returns the last resolved [StreamInfo]'s [source type][SourceType].
     *
     * @return [Optional.empty] if nothing was resolved, otherwise the [SourceType]
     * of the last resolved [StreamInfo] inside an [Optional]
     */
    fun getStreamSourceType(): Optional<SourceType> {
        return Optional.ofNullable(streamSourceType)
    }

    interface QualityResolver {
        fun getDefaultResolutionIndex(sortedVideos: List<VideoStream>?): Int

        fun getOverrideResolutionIndex(sortedVideos: List<VideoStream>?, playbackQuality: String?): Int
    }

    companion object {
        private val TAG: String = VideoPlaybackResolver::class.java.simpleName
    }
}
