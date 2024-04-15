package org.schabi.newpipe.database.playlist

import androidx.room.ColumnInfo

/**
 * This class adds a field to [PlaylistMetadataEntry] that contains an integer representing
 * how many times a specific stream is already contained inside a local playlist. Used to be able
 * to grey out playlists which already contain the current stream in the playlist append dialog.
 * @see org.schabi.newpipe.local.playlist.LocalPlaylistManager.getPlaylistDuplicates
 */
class PlaylistDuplicatesEntry(uid: Long,
                              name: String?,
                              thumbnailUrl: String?,
                              streamCount: Long,
                              @field:ColumnInfo(name = PLAYLIST_TIMES_STREAM_IS_CONTAINED) var timesStreamIsContained: Long
) : PlaylistMetadataEntry(uid, name!!, thumbnailUrl!!, streamCount) {

    constructor(): this(0L, null, null, 0L, 0L)
    companion object {
        const val PLAYLIST_TIMES_STREAM_IS_CONTAINED: String = "timesStreamIsContained"
    }
}
