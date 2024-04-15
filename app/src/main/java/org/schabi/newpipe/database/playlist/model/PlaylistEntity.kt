package org.schabi.newpipe.database.playlist.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.schabi.newpipe.R
import org.schabi.newpipe.database.playlist.model.PlaylistEntity

@Entity(tableName = PlaylistEntity.PLAYLIST_TABLE, indices = [Index(value = [PlaylistEntity.PLAYLIST_NAME])])
class PlaylistEntity(@JvmField @field:ColumnInfo(name = PLAYLIST_NAME) var name: String, @JvmField @field:ColumnInfo(
    name = PLAYLIST_THUMBNAIL_PERMANENT) var isThumbnailPermanent: Boolean,
                     @JvmField @field:ColumnInfo(name = PLAYLIST_THUMBNAIL_STREAM_ID) var thumbnailStreamId: Long
) {
    @JvmField
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = PLAYLIST_ID)
    var uid: Long = 0

    constructor(): this("", false, 0L)

    companion object {
        const val DEFAULT_THUMBNAIL: String = ("drawable://"
                + R.drawable.placeholder_thumbnail_playlist)
        const val DEFAULT_THUMBNAIL_ID: Long = -1

        const val PLAYLIST_TABLE: String = "playlists"
        const val PLAYLIST_ID: String = "uid"
        const val PLAYLIST_NAME: String = "name"
        const val PLAYLIST_THUMBNAIL_URL: String = "thumbnail_url"
        const val PLAYLIST_THUMBNAIL_PERMANENT: String = "is_thumbnail_permanent"
        const val PLAYLIST_THUMBNAIL_STREAM_ID: String = "thumbnail_stream_id"
    }
}
