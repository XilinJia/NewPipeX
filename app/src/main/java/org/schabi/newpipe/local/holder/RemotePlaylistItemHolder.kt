package org.schabi.newpipe.local.holder

import android.text.TextUtils
import android.view.ViewGroup
import org.schabi.newpipe.database.LocalItem
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity
import org.schabi.newpipe.local.LocalItemBuilder
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.util.Localization.concatenateStrings
import org.schabi.newpipe.util.Localization.localizeStreamCountMini
import org.schabi.newpipe.util.ServiceHelper.getNameOfServiceById
import org.schabi.newpipe.util.image.PicassoHelper.loadPlaylistThumbnail
import java.time.format.DateTimeFormatter

open class RemotePlaylistItemHolder : PlaylistItemHolder {
    constructor(infoItemBuilder: LocalItemBuilder?,
                parent: ViewGroup?
    ) : super(infoItemBuilder, parent)

    internal constructor(infoItemBuilder: LocalItemBuilder?, layoutId: Int,
                         parent: ViewGroup?
    ) : super(infoItemBuilder, layoutId, parent)

    override fun updateFromItem(localItem: LocalItem?,
                                historyRecordManager: HistoryRecordManager?,
                                dateTimeFormatter: DateTimeFormatter?
    ) {
        if (localItem !is PlaylistRemoteEntity) {
            return
        }
        val item = localItem

        itemTitleView.text = item.name
        itemStreamCountView.text = localizeStreamCountMini(
            itemStreamCountView.context, item.streamCount)
        // Here is where the uploader name is set in the bookmarked playlists library
        if (!TextUtils.isEmpty(item.uploader)) {
            itemUploaderView.text = concatenateStrings(item.uploader,
                getNameOfServiceById(item.serviceId))
        } else {
            itemUploaderView.text = getNameOfServiceById(item.serviceId)
        }

        loadPlaylistThumbnail(item.thumbnailUrl).into(itemThumbnailView)

        super.updateFromItem(localItem, historyRecordManager, dateTimeFormatter)
    }
}
