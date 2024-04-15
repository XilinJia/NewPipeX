package org.schabi.newpipe.util

import android.content.Context
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.util.ListHelper.getAudioFormatComparator
import org.schabi.newpipe.util.ListHelper.getAudioIndexByHighestRank
import org.schabi.newpipe.util.ListHelper.isLimitingDataUsage
import org.schabi.newpipe.util.StreamItemAdapter.StreamInfoWrapper

class SecondaryStreamHelper<T : Stream>(private val streams: StreamInfoWrapper<T>, selectedStream: T) {

    private val position = streams.streamsList.indexOf(selectedStream)

    init {
        if (this.position < 0) {
            throw RuntimeException("selected stream not found")
        }
    }

    val stream: T
        get() = streams.streamsList[position]

    val sizeInBytes: Long
        get() = streams.getSizeInBytes(position)

    companion object {
        /**
         * Finds an audio stream compatible with the provided video-only stream, so that the two streams
         * can be combined in a single file by the downloader. If there are multiple available audio
         * streams, chooses either the highest or the lowest quality one based on
         * [ListHelper.isLimitingDataUsage].
         *
         * @param context      Android context
         * @param audioStreams list of audio streams
         * @param videoStream  desired video-ONLY stream
         * @return the selected audio stream or null if a candidate was not found
         */
        fun getAudioStreamFor(context: Context,
                              audioStreams: List<AudioStream?>,
                              videoStream: VideoStream
        ): AudioStream? {
            val mediaFormat = videoStream.format ?: return null

            when (mediaFormat) {
                MediaFormat.WEBM, MediaFormat.MPEG_4 -> {}
                else -> return null
            }
            val m4v = mediaFormat == MediaFormat.MPEG_4
            val isLimitingDataUsage = isLimitingDataUsage(context)

            var comparator = getAudioFormatComparator(if (m4v) MediaFormat.M4A else MediaFormat.WEBMA, isLimitingDataUsage)
            var preferredAudioStreamIndex = getAudioIndexByHighestRank(
                audioStreams, comparator)

            if (preferredAudioStreamIndex == -1) {
                if (m4v) {
                    return null
                }

                comparator = getAudioFormatComparator(
                    MediaFormat.WEBMA_OPUS, isLimitingDataUsage)
                preferredAudioStreamIndex = getAudioIndexByHighestRank(
                    audioStreams, comparator)

                if (preferredAudioStreamIndex == -1) {
                    return null
                }
            }

            return audioStreams[preferredAudioStreamIndex]
        }
    }
}
