package org.schabi.newpipe.local.holder

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import org.schabi.newpipe.R
import org.schabi.newpipe.database.LocalItem
import org.schabi.newpipe.local.LocalItemBuilder
import org.schabi.newpipe.local.history.HistoryRecordManager
import java.time.format.DateTimeFormatter

abstract class PlaylistItemHolder(infoItemBuilder: LocalItemBuilder?, layoutId: Int,
                                  parent: ViewGroup?
) : LocalItemHolder(infoItemBuilder!!, layoutId, parent) {
    @JvmField
    val itemThumbnailView: ImageView = itemView.findViewById(R.id.itemThumbnailView)
    @JvmField
    val itemStreamCountView: TextView = itemView.findViewById(R.id.itemStreamCountView)
    @JvmField
    val itemTitleView: TextView = itemView.findViewById(R.id.itemTitleView)
    @JvmField
    val itemUploaderView: TextView = itemView.findViewById(R.id.itemUploaderView)

    constructor(infoItemBuilder: LocalItemBuilder?, parent: ViewGroup?) : this(infoItemBuilder,
        R.layout.list_playlist_mini_item,
        parent)

    override fun updateFromItem(localItem: LocalItem?,
                                historyRecordManager: HistoryRecordManager?,
                                dateTimeFormatter: DateTimeFormatter?
    ) {
        itemView.setOnClickListener { view: View? ->
            if (localItem != null) itemBuilder.onItemSelectedListener?.selected(localItem)
        }

        itemView.isLongClickable = true
        itemView.setOnLongClickListener { view: View? ->
            if (localItem != null) itemBuilder.onItemSelectedListener?.held(localItem)
            true
        }
    }
}
