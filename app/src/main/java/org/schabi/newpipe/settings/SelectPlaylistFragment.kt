package org.schabi.newpipe.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.disposables.Disposable
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.LocalItem
import org.schabi.newpipe.database.playlist.PlaylistLocalItem
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil.Companion.showSnackbar
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.local.playlist.LocalPlaylistManager
import org.schabi.newpipe.local.playlist.RemotePlaylistManager
import org.schabi.newpipe.settings.SelectPlaylistFragment.SelectPlaylistAdapter.SelectPlaylistItemHolder
import org.schabi.newpipe.util.image.PicassoHelper.loadPlaylistThumbnail
import java.util.*

class SelectPlaylistFragment : DialogFragment() {
    private var onSelectedListener: OnSelectedListener? = null

    private var progressBar: ProgressBar? = null
    private var emptyView: TextView? = null
    private var recyclerView: RecyclerView? = null
    private var disposable: Disposable? = null

    private var playlists: List<PlaylistLocalItem> = Vector()

    fun setOnSelectedListener(listener: OnSelectedListener?) {
        onSelectedListener = listener
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Fragment's Lifecycle
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.select_playlist_fragment, container, false)
        progressBar = v.findViewById(R.id.progressBar)
        recyclerView = v.findViewById(R.id.items_list)
        emptyView = v.findViewById(R.id.empty_state_view)

        recyclerView!!.setLayoutManager(LinearLayoutManager(context))
        val playlistAdapter = SelectPlaylistAdapter()
        recyclerView!!.setAdapter(playlistAdapter)

        loadPlaylists()
        return v
    }

    override fun onDestroy() {
        super.onDestroy()
        if (disposable != null) {
            disposable!!.dispose()
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Load and display playlists
    ////////////////////////////////////////////////////////////////////////// */
    private fun loadPlaylists() {
        progressBar!!.visibility = View.VISIBLE
        recyclerView!!.visibility = View.GONE
        emptyView!!.visibility = View.GONE

        val database = NewPipeDatabase.getInstance(requireContext())
        val localPlaylistManager = LocalPlaylistManager(database)
        val remotePlaylistManager = RemotePlaylistManager(database)

        disposable = Flowable.combineLatest(localPlaylistManager.playlists,
            remotePlaylistManager.playlists) { localPlaylists: List<PlaylistMetadataEntry>, remotePlaylists: List<PlaylistRemoteEntity> ->
            PlaylistLocalItem.merge(localPlaylists, remotePlaylists)
        }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ newPlaylists: List<PlaylistLocalItem> -> this.displayPlaylists(newPlaylists) },
                { e: Throwable? -> this.onError(e) })
    }

    private fun displayPlaylists(newPlaylists: List<PlaylistLocalItem>) {
        playlists = newPlaylists
        progressBar!!.visibility = View.GONE
        emptyView!!.visibility = if (newPlaylists.isEmpty()) View.VISIBLE else View.GONE
        recyclerView!!.visibility = if (newPlaylists.isEmpty()) View.GONE else View.VISIBLE
    }

    protected fun onError(e: Throwable?) {
        showSnackbar(requireActivity(), ErrorInfo(e!!,
            UserAction.UI_ERROR, "Loading playlists"))
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Handle actions
    ////////////////////////////////////////////////////////////////////////// */
    private fun clickedItem(position: Int) {
        if (onSelectedListener != null) {
            val selectedItem: LocalItem = playlists[position]

            if (selectedItem is PlaylistMetadataEntry) {
                val entry = selectedItem
                onSelectedListener!!.onLocalPlaylistSelected(entry.uid, entry.name)
            } else if (selectedItem is PlaylistRemoteEntity) {
                val entry = selectedItem
                onSelectedListener!!.onRemotePlaylistSelected(
                    entry.serviceId, entry.url, entry.name)
            }
        }
        dismiss()
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Interfaces
    ////////////////////////////////////////////////////////////////////////// */
    interface OnSelectedListener {
        fun onLocalPlaylistSelected(id: Long, name: String?)
        fun onRemotePlaylistSelected(serviceId: Int, url: String?, name: String?)
    }

    private inner class SelectPlaylistAdapter

        : RecyclerView.Adapter<SelectPlaylistItemHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup,
                                        viewType: Int
        ): SelectPlaylistItemHolder {
            val item = LayoutInflater.from(parent.context)
                .inflate(R.layout.list_playlist_mini_item, parent, false)
            return SelectPlaylistItemHolder(item)
        }

        override fun onBindViewHolder(holder: SelectPlaylistItemHolder,
                                      position: Int
        ) {
            val selectedItem = playlists[position]

            if (selectedItem is PlaylistMetadataEntry) {
                val entry = selectedItem

                holder.titleView.text = entry.name
                holder.view.setOnClickListener { view: View? -> clickedItem(position) }
                loadPlaylistThumbnail(entry.thumbnailUrl).into(holder.thumbnailView)
            } else if (selectedItem is PlaylistRemoteEntity) {
                val entry = selectedItem

                holder.titleView.text = entry.name
                holder.view.setOnClickListener { view: View? -> clickedItem(position) }
                loadPlaylistThumbnail(entry.thumbnailUrl)
                    .into(holder.thumbnailView)
            }
        }

        override fun getItemCount(): Int {
            return playlists.size
        }

        inner class SelectPlaylistItemHolder internal constructor(val view: View) : RecyclerView.ViewHolder(view) {
            val thumbnailView: ImageView = view.findViewById<ImageView>(R.id.itemThumbnailView)
            val titleView: TextView = view.findViewById<TextView>(R.id.itemTitleView)
        }
    }
}
