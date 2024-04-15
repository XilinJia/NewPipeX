package org.schabi.newpipe.util

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.util.StreamItemAdapter.StreamInfoWrapper
import java.io.Serializable
import java.util.stream.Collectors

/**
 * A list adapter for groups of [AudioStream]s (audio tracks).
 */
class AudioTrackAdapter(private val tracksWrapper: AudioTracksWrapper) : BaseAdapter() {
    override fun getCount(): Int {
        return tracksWrapper.size()
    }

    override fun getItem(position: Int): List<AudioStream> {
        return tracksWrapper.tracksList[position].streamsList
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getView(position: Int, convertView: View, parent: ViewGroup): View {
        val context = parent.context
        val view = convertView

        val woSoundIconView = view.findViewById<ImageView>(R.id.wo_sound_icon)
        val formatNameView = view.findViewById<TextView>(R.id.stream_format_name)
        val qualityView = view.findViewById<TextView>(R.id.stream_quality)
        val sizeView = view.findViewById<TextView>(R.id.stream_size)

        val streams = getItem(position)
        val stream = streams[0]

        woSoundIconView.visibility = View.GONE
        sizeView.visibility = View.VISIBLE

        if (stream.audioTrackId != null) {
            formatNameView.text = stream.audioTrackId
        }
        qualityView.text = Localization.audioTrackName(context, stream)

        return view
    }

    class AudioTracksWrapper(groupedAudioStreams: List<List<AudioStream>>,
                             context: Context?
    ) : Serializable {
        @JvmField
        val tracksList: List<StreamInfoWrapper<AudioStream>> =
            groupedAudioStreams.stream().map { streams: List<AudioStream> -> StreamInfoWrapper(streams, context) }
                .collect(Collectors.toList())

        fun size(): Int {
            return tracksList.size
        }
    }
}
