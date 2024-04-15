package org.schabi.newpipe.local.holder

import android.view.View
import android.view.ViewGroup
import org.schabi.newpipe.database.LocalItem
import org.schabi.newpipe.database.playlist.PlaylistDuplicatesEntry
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry
import org.schabi.newpipe.local.LocalItemBuilder
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.util.Localization.localizeStreamCountMini
import org.schabi.newpipe.util.image.PicassoHelper.loadPlaylistThumbnail
import java.time.format.DateTimeFormatter

open class LocalPlaylistItemHolder : PlaylistItemHolder {
    constructor(infoItemBuilder: LocalItemBuilder?, parent: ViewGroup?) : super(infoItemBuilder, parent)

    internal constructor(infoItemBuilder: LocalItemBuilder?, layoutId: Int,
                         parent: ViewGroup?
    ) : super(infoItemBuilder, layoutId, parent)

    override fun updateFromItem(localItem: LocalItem?,
                                historyRecordManager: HistoryRecordManager?,
                                dateTimeFormatter: DateTimeFormatter?
    ) {
        if (localItem !is PlaylistMetadataEntry) {
            return
        }
        val item = localItem

        itemTitleView.text = item.name
        itemStreamCountView.text = localizeStreamCountMini(
            itemStreamCountView.context, item.streamCount)
        itemUploaderView.visibility = View.INVISIBLE

        loadPlaylistThumbnail(item.thumbnailUrl).into(itemThumbnailView)

        if (item is PlaylistDuplicatesEntry
                && item.timesStreamIsContained > 0) {
            itemView.alpha = GRAYED_OUT_ALPHA
        } else {
            itemView.alpha = 1.0f
        }

        super.updateFromItem(localItem, historyRecordManager, dateTimeFormatter)
    }

    companion object {
        private const val GRAYED_OUT_ALPHA = 0.6f
    }
}
