package org.schabi.newpipe.local.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.schabi.newpipe.NewPipeDatabase.getInstance
import org.schabi.newpipe.R
import org.schabi.newpipe.database.LocalItem
import org.schabi.newpipe.database.playlist.PlaylistDuplicatesEntry
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.local.LocalItemListAdapter
import org.schabi.newpipe.local.playlist.LocalPlaylistManager
import org.schabi.newpipe.util.OnClickGesture

class PlaylistAppendDialog : PlaylistDialog() {
    private var playlistRecyclerView: RecyclerView? = null
    private var playlistAdapter: LocalItemListAdapter? = null
    private var playlistDuplicateIndicator: TextView? = null

    private val playlistDisposables = CompositeDisposable()

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle - Creation
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_playlists, container)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val playlistManager =
            LocalPlaylistManager(getInstance(requireContext()))

        playlistAdapter = LocalItemListAdapter(activity)
        playlistAdapter!!.setSelectedListener { selectedItem: LocalItem? ->
            val entities = streamEntities
            if (selectedItem is PlaylistDuplicatesEntry && entities != null) {
                onPlaylistSelected(playlistManager,
                    selectedItem, entities)
            }
        }

        playlistRecyclerView = view.findViewById(R.id.playlist_list)
        playlistRecyclerView?.setLayoutManager(LinearLayoutManager(requireContext()))
        playlistRecyclerView?.setAdapter(playlistAdapter)

        playlistDuplicateIndicator = view.findViewById(R.id.playlist_duplicate)

        val newPlaylistButton = view.findViewById<View>(R.id.newPlaylist)
        newPlaylistButton.setOnClickListener { ignored: View? -> openCreatePlaylistDialog() }

        playlistDisposables.add(playlistManager
            .getPlaylistDuplicates(streamEntities!![0].url)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { playlists: List<PlaylistDuplicatesEntry> -> this.onPlaylistsReceived(playlists) })
    }

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle - Destruction
    ////////////////////////////////////////////////////////////////////////// */
    override fun onDestroyView() {
        super.onDestroyView()
        playlistDisposables.dispose()
        if (playlistAdapter != null) {
            playlistAdapter!!.unsetSelectedListener()
        }

        playlistDisposables.clear()
        playlistRecyclerView = null
        playlistAdapter = null
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Helper
    ////////////////////////////////////////////////////////////////////////// */
    /** Display create playlist dialog.  */
    fun openCreatePlaylistDialog() {
        if (streamEntities == null || !isAdded) {
            return
        }

        val playlistCreationDialog =
            PlaylistCreationDialog.newInstance(streamEntities)
        // Move the dismissListener to the new dialog.
        playlistCreationDialog.onDismissListener = this.onDismissListener
        this.onDismissListener = null

        playlistCreationDialog.show(parentFragmentManager, TAG)
        requireDialog().dismiss()
    }

    private fun onPlaylistsReceived(playlists: List<PlaylistDuplicatesEntry>) {
        if (playlistAdapter != null && playlistRecyclerView != null && playlistDuplicateIndicator != null) {
            playlistAdapter!!.clearStreamItemList()
            playlistAdapter!!.addItems(playlists)
            playlistRecyclerView!!.visibility = View.VISIBLE
            playlistDuplicateIndicator!!.visibility =
                if (anyPlaylistContainsDuplicates(playlists)) View.VISIBLE else View.GONE
        }
    }

    private fun anyPlaylistContainsDuplicates(playlists: List<PlaylistDuplicatesEntry>): Boolean {
        return playlists.stream()
            .anyMatch { playlist: PlaylistDuplicatesEntry -> playlist.timesStreamIsContained > 0 }
    }

    private fun onPlaylistSelected(manager: LocalPlaylistManager,
                                   playlist: PlaylistDuplicatesEntry,
                                   streams: List<StreamEntity>
    ) {
        val toastText = if (playlist.timesStreamIsContained > 0) {
            getString(R.string.playlist_add_stream_success_duplicate,
                playlist.timesStreamIsContained)
        } else {
            getString(R.string.playlist_add_stream_success)
        }

        val successToast = Toast.makeText(context, toastText, Toast.LENGTH_SHORT)

        playlistDisposables.add(manager.appendToPlaylist(playlist.uid, streams)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { ignored: List<Long?>? ->
                successToast.show()
                if (playlist.thumbnailUrl == PlaylistEntity.DEFAULT_THUMBNAIL) {
                    playlistDisposables.add(manager
                        .changePlaylistThumbnail(playlist.uid, streams[0].uid,
                            false)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe { ignore: Int? -> successToast.show() })
                }
            })

        requireDialog().dismiss()
    }

    companion object {
        private val TAG: String = PlaylistAppendDialog::class.java.canonicalName

        /**
         * Create a new instance of [PlaylistAppendDialog].
         *
         * @param streamEntities    a list of [StreamEntity] to be added to playlists
         * @return a new instance of [PlaylistAppendDialog]
         */
        fun newInstance(streamEntities: List<StreamEntity>?): PlaylistAppendDialog {
            val dialog = PlaylistAppendDialog()
            dialog.streamEntities = streamEntities
            return dialog
        }
    }
}
