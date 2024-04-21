package org.schabi.newpipe.info_list.dialog

import android.net.Uri
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.download.DownloadDialog
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.info_list.dialog.StreamDialogEntry.StreamDialogEntryAction
import org.schabi.newpipe.local.dialog.PlaylistAppendDialog
import org.schabi.newpipe.local.dialog.PlaylistDialog
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.NavigationHelper.enqueueNextOnPlayer
import org.schabi.newpipe.util.NavigationHelper.enqueueOnPlayer
import org.schabi.newpipe.util.NavigationHelper.openChannelFragment
import org.schabi.newpipe.util.NavigationHelper.playOnBackgroundPlayer
import org.schabi.newpipe.util.NavigationHelper.playOnPopupPlayer
import org.schabi.newpipe.util.SparseItemUtil.fetchItemInfoIfSparse
import org.schabi.newpipe.util.SparseItemUtil.fetchStreamInfoAndSaveToDatabase
import org.schabi.newpipe.util.SparseItemUtil.fetchUploaderUrlIfSparse
import org.schabi.newpipe.util.external_communication.KoreUtils.playWithKore
import org.schabi.newpipe.util.external_communication.ShareUtils.openUrlInBrowser
import org.schabi.newpipe.util.external_communication.ShareUtils.shareText
import java.util.function.Consumer

/**
 *
 *
 * This enum provides entries that are accepted
 * by the [InfoItemDialog.Builder].
 *
 *
 *
 * These entries contain a String [.resource] which is displayed in the dialog and
 * a default [.action] that is executed
 * when the entry is selected (via `onClick()`).
 * <br></br>
 * They action can be overridden by using the Builder's
 * [InfoItemDialog.Builder.setAction]
 * method.
 *
 */
enum class StreamDialogDefaultEntry(@field:StringRes @param:StringRes val resource: Int,
                                    val action: StreamDialogEntryAction
) {
    SHOW_CHANNEL_DETAILS(R.string.show_channel_details,
        StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
            fetchUploaderUrlIfSparse(
                fragment!!.requireContext(), item!!.serviceId, item.url,
                item.uploaderUrl, Consumer { url: String? ->
                    openChannelFragment(
                        fragment, item, url)
                })
        }
    ),

    /**
     * Enqueues the stream automatically to the current PlayerType.
     */
    ENQUEUE(R.string.enqueue_stream, StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
        fetchItemInfoIfSparse(
            fragment!!.requireContext(), item!!, Consumer { singlePlayQueue: SinglePlayQueue? ->
                enqueueOnPlayer(
                    fragment.requireContext(), singlePlayQueue)
            })
    }
    ),

    /**
     * Enqueues the stream automatically to the current PlayerType
     * after the currently playing stream.
     */
    ENQUEUE_NEXT(R.string.enqueue_next_stream, StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
        fetchItemInfoIfSparse(
            fragment!!.requireContext(), item!!, Consumer { singlePlayQueue: SinglePlayQueue? ->
                enqueueNextOnPlayer(
                    fragment.requireContext(), singlePlayQueue)
            })
    }
    ),

    START_HERE_ON_BACKGROUND(R.string.start_here_on_background,
        StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
            fetchItemInfoIfSparse(
                fragment!!.requireContext(), item!!, Consumer { singlePlayQueue: SinglePlayQueue? ->
                    playOnBackgroundPlayer(
                        fragment.requireContext(), singlePlayQueue, true)
                })
        }),

    START_HERE_ON_POPUP(R.string.start_here_on_popup,
        StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
            fetchItemInfoIfSparse(
                fragment!!.requireContext(), item!!, Consumer { singlePlayQueue: SinglePlayQueue? ->
                    playOnPopupPlayer(
                        fragment.requireContext(), singlePlayQueue, true)
                })
        }),

    SET_AS_PLAYLIST_THUMBNAIL(R.string.set_as_playlist_thumbnail,
        StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
            throw UnsupportedOperationException(
                "This needs to be implemented manually "
                        + "by using InfoItemDialog.Builder.setAction()")
        }),

    DELETE(R.string.delete, StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
        throw UnsupportedOperationException(
            "This needs to be implemented manually "
                    + "by using InfoItemDialog.Builder.setAction()")
    }),

    /**
     * Opens a [PlaylistDialog] to either append the stream to a playlist
     * or create a new playlist if there are no local playlists.
     */
    APPEND_PLAYLIST(R.string.add_to_playlist, StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
        PlaylistDialog.createCorrespondingDialog(
            fragment!!.context,
            kotlin.collections.listOf(StreamEntity(item!!))
        ) { dialog ->
            dialog.show(
                fragment.parentFragmentManager,
                "StreamDialogEntry@"
                        + (if (dialog is PlaylistAppendDialog) "append" else "create")
                        + "_playlist"
            )
        }
    }
    ),

    PLAY_WITH_KODI(R.string.play_with_kodi_title,
        StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
            playWithKore(
                fragment!!.requireContext(), Uri.parse(item!!.url))
        }),

    SHARE(R.string.share, StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
        shareText(
            fragment!!.requireContext(), item!!.name, item.url,
            item.thumbnails)
    }),

    /**
     * Opens a [DownloadDialog] after fetching some stream info.
     * If the user quits the current fragment, it will not open a DownloadDialog.
     */
    DOWNLOAD(R.string.download, StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
        fetchStreamInfoAndSaveToDatabase(
            fragment!!.requireContext(), item!!.serviceId,
            item.url, Consumer { info: StreamInfo? ->
                if (fragment.context != null) {
                    val downloadDialog =
                        DownloadDialog(fragment.requireContext(), info!!)
                    downloadDialog.show(fragment.childFragmentManager,
                        "downloadDialog")
                }
            })
    }
    ),

    OPEN_IN_BROWSER(R.string.open_in_browser, StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
        openUrlInBrowser(
            fragment!!.requireContext(), item!!.url)
    }),


    MARK_AS_WATCHED(R.string.mark_as_watched, StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
        HistoryRecordManager(
            fragment!!.requireContext())
            .markAsWatched(item!!)
            .onErrorComplete()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe()
    }
    );


    fun toStreamDialogEntry(): StreamDialogEntry {
        return StreamDialogEntry(resource, action)
    }
}
