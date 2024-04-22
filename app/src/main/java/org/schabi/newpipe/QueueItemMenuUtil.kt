package org.schabi.newpipe

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentManager
import androidx.media3.common.util.UnstableApi
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.download.DownloadDialog
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.local.dialog.PlaylistDialog
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.PlayQueueItem
import org.schabi.newpipe.util.NavigationHelper.openChannelFragmentUsingIntent
import org.schabi.newpipe.util.NavigationHelper.openVideoDetail
import org.schabi.newpipe.util.SparseItemUtil.fetchStreamInfoAndSaveToDatabase
import org.schabi.newpipe.util.SparseItemUtil.fetchUploaderUrlIfSparse
import org.schabi.newpipe.util.external_communication.ShareUtils.shareText
import java.util.function.Consumer

object QueueItemMenuUtil {
    @OptIn(UnstableApi::class) @JvmStatic
    fun openPopupMenu(playQueue: PlayQueue, item: PlayQueueItem, view: View?, hideDetails: Boolean, fragmentManager: FragmentManager?, context: Context?) {
        val themeWrapper = ContextThemeWrapper(context, R.style.DarkPopupMenu)

        val popupMenu = PopupMenu(themeWrapper, view)
        popupMenu.inflate(R.menu.menu_play_queue_item)

        if (hideDetails) {
            popupMenu.menu.findItem(R.id.menu_item_details).setVisible(false)
        }

        popupMenu.setOnMenuItemClickListener { menuItem: MenuItem ->
            when (menuItem.itemId) {
                R.id.menu_item_remove -> {
                    val index = playQueue.indexOf(item)
                    playQueue.remove(index)
                    return@setOnMenuItemClickListener true
                }
                R.id.menu_item_details -> {
                    // playQueue is null since we don't want any queue change
                    openVideoDetail(context!!, item.serviceId, item.url, item.title, null, false)
                    return@setOnMenuItemClickListener true
                }
                R.id.menu_item_append_playlist -> {
                    PlaylistDialog.createCorrespondingDialog(context, listOf(StreamEntity(item))) { dialog: PlaylistDialog ->
                        dialog.show(fragmentManager!!, "QueueItemMenuUtil@append_playlist")
                    }

                    return@setOnMenuItemClickListener true
                }
                R.id.menu_item_channel_details -> {
                    fetchUploaderUrlIfSparse(context!!, item.serviceId, item.url, item.uploaderUrl)  // An intent must be used here.
                    // Opening with FragmentManager transactions is not working,
                    // as PlayQueueActivity doesn't use fragments.
                    { uploaderUrl: String? ->
                        openChannelFragmentUsingIntent(context, item.serviceId, uploaderUrl, item.uploader
                        )
                    }
                    return@setOnMenuItemClickListener true
                }
                R.id.menu_item_share -> {
                    shareText(context!!, item.title, item.url, item.thumbnails)
                    return@setOnMenuItemClickListener true
                }
                R.id.menu_item_download -> {
                    fetchStreamInfoAndSaveToDatabase(context!!, item.serviceId, item.url,
                        Consumer { info: StreamInfo? ->
                            val downloadDialog = DownloadDialog(context, info!!)
                            downloadDialog.show(fragmentManager!!, "downloadDialog")
                        })
                    return@setOnMenuItemClickListener true
                }
            }
            false
        }

        popupMenu.show()
    }
}
