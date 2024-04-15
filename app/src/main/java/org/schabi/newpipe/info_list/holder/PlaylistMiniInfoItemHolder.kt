package org.schabi.newpipe.info_list.holder

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.info_list.InfoItemBuilder
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.util.Localization.localizeStreamCountMini
import org.schabi.newpipe.util.image.PicassoHelper.loadPlaylistThumbnail

open class PlaylistMiniInfoItemHolder(infoItemBuilder: InfoItemBuilder?, layoutId: Int,
                                      parent: ViewGroup?
) : InfoItemHolder(infoItemBuilder!!, layoutId, parent) {
    val itemThumbnailView: ImageView = itemView.findViewById(R.id.itemThumbnailView)
    private val itemStreamCountView: TextView = itemView.findViewById(R.id.itemStreamCountView)
    val itemTitleView: TextView = itemView.findViewById(R.id.itemTitleView)
    val itemUploaderView: TextView = itemView.findViewById(R.id.itemUploaderView)

    constructor(infoItemBuilder: InfoItemBuilder?,
                parent: ViewGroup?
    ) : this(infoItemBuilder, R.layout.list_playlist_mini_item, parent)

    override fun updateFromItem(infoItem: InfoItem?,
                                historyRecordManager: HistoryRecordManager?
    ) {
        if (infoItem !is PlaylistInfoItem) {
            return
        }
        val item = infoItem

        itemTitleView.text = item.name
        itemStreamCountView.text = localizeStreamCountMini(itemStreamCountView.context, item.streamCount)
        itemUploaderView.text = item.uploaderName

        loadPlaylistThumbnail(item.thumbnails).into(itemThumbnailView)

        itemView.setOnClickListener { view: View? ->
            itemBuilder.onPlaylistSelectedListener?.selected(item)
        }

        itemView.isLongClickable = true
        itemView.setOnLongClickListener { view: View? ->
            itemBuilder.onPlaylistSelectedListener?.held(item)
            true
        }
    }
}
