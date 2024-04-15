package org.schabi.newpipe.local.holder

import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.schabi.newpipe.R
import org.schabi.newpipe.database.LocalItem
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry
import org.schabi.newpipe.ktx.animate
import org.schabi.newpipe.local.LocalItemBuilder
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.util.DependentPreferenceHelper.getPositionsInListsEnabled
import org.schabi.newpipe.util.Localization.concatenateStrings
import org.schabi.newpipe.util.Localization.getDurationString
import org.schabi.newpipe.util.ServiceHelper.getNameOfServiceById
import org.schabi.newpipe.util.image.PicassoHelper.loadThumbnail
import org.schabi.newpipe.views.AnimatedProgressBar
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

open class LocalPlaylistStreamItemHolder internal constructor(infoItemBuilder: LocalItemBuilder?, layoutId: Int,
                                                              parent: ViewGroup?
) : LocalItemHolder(infoItemBuilder!!, layoutId, parent) {
    val itemThumbnailView: ImageView = itemView.findViewById(R.id.itemThumbnailView)
    val itemVideoTitleView: TextView = itemView.findViewById(R.id.itemVideoTitleView)
    private val itemAdditionalDetailsView: TextView = itemView.findViewById(R.id.itemAdditionalDetails)
    val itemDurationView: TextView = itemView.findViewById(R.id.itemDurationView)
    private val itemHandleView: View = itemView.findViewById(R.id.itemHandle)
    private val itemProgressView: AnimatedProgressBar = itemView.findViewById(R.id.itemProgressView)

    constructor(infoItemBuilder: LocalItemBuilder?,
                parent: ViewGroup?
    ) : this(infoItemBuilder, R.layout.list_stream_playlist_item, parent)

    override fun updateFromItem(localItem: LocalItem?,
                                historyRecordManager: HistoryRecordManager?,
                                dateTimeFormatter: DateTimeFormatter?
    ) {
        if (localItem !is PlaylistStreamEntry) {
            return
        }
        val item = localItem

        itemVideoTitleView.text = item.streamEntity.title
        itemAdditionalDetailsView.text = concatenateStrings(item.streamEntity.uploader,
            getNameOfServiceById(item.streamEntity.serviceId))

        if (item.streamEntity.duration > 0) {
            itemDurationView.text = getDurationString(item.streamEntity.duration)
            itemDurationView.setBackgroundColor(ContextCompat.getColor(itemBuilder.context,
                R.color.duration_background_color))
            itemDurationView.visibility = View.VISIBLE

            if (getPositionsInListsEnabled(itemProgressView.context)
                    && item.progressMillis > 0) {
                itemProgressView.visibility = View.VISIBLE
                itemProgressView.max = item.streamEntity.duration.toInt()
                itemProgressView.progress = TimeUnit.MILLISECONDS
                    .toSeconds(item.progressMillis).toInt()
            } else {
                itemProgressView.visibility = View.GONE
            }
        } else {
            itemDurationView.visibility = View.GONE
        }

        // Default thumbnail is shown on error, while loading and if the url is empty
        loadThumbnail(item.streamEntity.thumbnailUrl)
            .into(itemThumbnailView)

        itemView.setOnClickListener { view: View? ->
            itemBuilder.onItemSelectedListener?.selected(item)
        }

        itemView.isLongClickable = true
        itemView.setOnLongClickListener { view: View? ->
            itemBuilder.onItemSelectedListener?.held(item)
            true
        }

        itemHandleView.setOnTouchListener(getOnTouchListener(item))
    }

    override fun updateState(localItem: LocalItem?, historyRecordManager: HistoryRecordManager?) {
        if (localItem !is PlaylistStreamEntry) {
            return
        }
        val item = localItem

        if (getPositionsInListsEnabled(itemProgressView.context) && item.progressMillis > 0 && item.streamEntity.duration > 0) {
            itemProgressView.max = item.streamEntity.duration.toInt()
            if (itemProgressView.visibility == View.VISIBLE) {
                itemProgressView.setProgressAnimated(TimeUnit.MILLISECONDS
                    .toSeconds(item.progressMillis).toInt())
            } else {
                itemProgressView.progress = TimeUnit.MILLISECONDS
                    .toSeconds(item.progressMillis).toInt()
                itemProgressView.animate(true, 500)
            }
        } else if (itemProgressView.visibility == View.VISIBLE) {
            itemProgressView.animate(false, 500)
        }
    }

    private fun getOnTouchListener(item: PlaylistStreamEntry): OnTouchListener {
        return OnTouchListener { view: View, motionEvent: MotionEvent ->
            view.performClick()
            if (motionEvent.actionMasked == MotionEvent.ACTION_DOWN) {
                itemBuilder?.onItemSelectedListener?.drag(item, this@LocalPlaylistStreamItemHolder)
            }
            false
        }
    }
}
