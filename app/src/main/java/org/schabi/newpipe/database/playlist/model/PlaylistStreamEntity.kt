package org.schabi.newpipe.database.playlist.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.Index
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity

@Entity(tableName = PlaylistStreamEntity.PLAYLIST_STREAM_JOIN_TABLE,
    primaryKeys = [PlaylistStreamEntity.JOIN_PLAYLIST_ID, PlaylistStreamEntity.JOIN_INDEX],
    indices = [Index(value = [PlaylistStreamEntity.JOIN_PLAYLIST_ID, PlaylistStreamEntity.JOIN_INDEX],
        unique = true), Index(value = [PlaylistStreamEntity.JOIN_STREAM_ID])],
    foreignKeys = [ForeignKey(entity = PlaylistEntity::class,
        parentColumns = arrayOf(PlaylistEntity.PLAYLIST_ID),
        childColumns = arrayOf(PlaylistStreamEntity.JOIN_PLAYLIST_ID),
        onDelete = CASCADE,
        onUpdate = CASCADE,
        deferred = true), ForeignKey(entity = StreamEntity::class,
        parentColumns = arrayOf(StreamEntity.STREAM_ID),
        childColumns = arrayOf(PlaylistStreamEntity.JOIN_STREAM_ID),
        onDelete = CASCADE,
        onUpdate = CASCADE,
        deferred = true)])
class PlaylistStreamEntity(@JvmField @field:ColumnInfo(name = JOIN_PLAYLIST_ID) var playlistUid: Long, @JvmField @field:ColumnInfo(
    name = JOIN_STREAM_ID) var streamUid: Long, @JvmField @field:ColumnInfo(name = JOIN_INDEX) var index: Int
) {
    constructor(): this(0L, 0L, 0)

    companion object {
        const val PLAYLIST_STREAM_JOIN_TABLE: String = "playlist_stream_join"
        const val JOIN_PLAYLIST_ID: String = "playlist_id"
        const val JOIN_STREAM_ID: String = "stream_id"
        const val JOIN_INDEX: String = "join_index"
    }
}
