package org.schabi.newpipe.database.playlist.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import io.reactivex.rxjava3.core.Flowable
import org.schabi.newpipe.database.BasicDAO
import org.schabi.newpipe.database.playlist.PlaylistDuplicatesEntry
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.stream.model.StreamStateEntity

@Dao
interface PlaylistStreamDAO : BasicDAO<PlaylistStreamEntity> {
    @Query("SELECT * FROM " + PlaylistStreamEntity.PLAYLIST_STREAM_JOIN_TABLE)
    override fun getAll(): Flowable<List<PlaylistStreamEntity>>

    @Query("DELETE FROM " + PlaylistStreamEntity.PLAYLIST_STREAM_JOIN_TABLE)
    override fun deleteAll(): Int

    override fun listByService(serviceId: Int): Flowable<List<PlaylistStreamEntity>> {
        throw UnsupportedOperationException()
    }

    @Query("DELETE FROM " + PlaylistStreamEntity.PLAYLIST_STREAM_JOIN_TABLE
            + " WHERE " + PlaylistStreamEntity.JOIN_PLAYLIST_ID + " = :playlistId")
    fun deleteBatch(playlistId: Long)

    @Query("SELECT COALESCE(MAX(" + PlaylistStreamEntity.JOIN_INDEX + "), -1)"
            + " FROM " + PlaylistStreamEntity.PLAYLIST_STREAM_JOIN_TABLE
            + " WHERE " + PlaylistStreamEntity.JOIN_PLAYLIST_ID + " = :playlistId")
    fun getMaximumIndexOf(playlistId: Long): Flowable<Int>

    @Query("SELECT CASE WHEN COUNT(*) != 0 then " + StreamEntity.STREAM_ID
            + " ELSE " + PlaylistEntity.DEFAULT_THUMBNAIL_ID + " END"
            + " FROM " + StreamEntity.STREAM_TABLE
            + " LEFT JOIN " + PlaylistStreamEntity.PLAYLIST_STREAM_JOIN_TABLE
            + " ON " + StreamEntity.STREAM_ID + " = " + PlaylistStreamEntity.JOIN_STREAM_ID
            + " WHERE " + PlaylistStreamEntity.JOIN_PLAYLIST_ID + " = :playlistId "
            + " LIMIT 1")
    fun getAutomaticThumbnailStreamId(playlistId: Long): Flowable<Long>

    @RewriteQueriesToDropUnusedColumns
    @Transaction
    @Query("SELECT * FROM " + StreamEntity.STREAM_TABLE + " INNER JOIN " // get ids of streams of the given playlist
            + "(SELECT " + PlaylistStreamEntity.JOIN_STREAM_ID + "," + PlaylistStreamEntity.JOIN_INDEX
            + " FROM " + PlaylistStreamEntity.PLAYLIST_STREAM_JOIN_TABLE
            + " WHERE " + PlaylistStreamEntity.JOIN_PLAYLIST_ID + " = :playlistId)" // then merge with the stream metadata

            + " ON " + StreamEntity.STREAM_ID + " = " + PlaylistStreamEntity.JOIN_STREAM_ID

            + " LEFT JOIN "
            + "(SELECT " + PlaylistStreamEntity.JOIN_STREAM_ID + " AS " + StreamStateEntity.JOIN_STREAM_ID_ALIAS + ", "
            + StreamStateEntity.STREAM_PROGRESS_MILLIS
            + " FROM " + StreamStateEntity.STREAM_STATE_TABLE + " )"
            + " ON " + StreamEntity.STREAM_ID + " = " + StreamStateEntity.JOIN_STREAM_ID_ALIAS

            + " ORDER BY " + PlaylistStreamEntity.JOIN_INDEX + " ASC")
    fun getOrderedStreamsOf(playlistId: Long): Flowable<List<PlaylistStreamEntry>>

    @Transaction
    @Query("SELECT " + PlaylistEntity.PLAYLIST_ID + ", " + PlaylistEntity.PLAYLIST_NAME + ","

            + " CASE WHEN " + PlaylistEntity.PLAYLIST_THUMBNAIL_STREAM_ID + " = "
            + PlaylistEntity.DEFAULT_THUMBNAIL_ID + " THEN " + "'" + PlaylistEntity.DEFAULT_THUMBNAIL + "'"
            + " ELSE (SELECT " + StreamEntity.STREAM_THUMBNAIL_URL
            + " FROM " + StreamEntity.STREAM_TABLE
            + " WHERE " + StreamEntity.STREAM_TABLE + "." + StreamEntity.STREAM_ID + " = " + PlaylistEntity.PLAYLIST_THUMBNAIL_STREAM_ID
            + " ) END AS " + PlaylistEntity.PLAYLIST_THUMBNAIL_URL + ", "

            + "COALESCE(COUNT(" + PlaylistStreamEntity.JOIN_PLAYLIST_ID + "), 0) AS " + PlaylistMetadataEntry.PLAYLIST_STREAM_COUNT
            + " FROM " + PlaylistEntity.PLAYLIST_TABLE
            + " LEFT JOIN " + PlaylistStreamEntity.PLAYLIST_STREAM_JOIN_TABLE
            + " ON " + PlaylistEntity.PLAYLIST_TABLE + "." + PlaylistEntity.PLAYLIST_ID + " = " + PlaylistStreamEntity.JOIN_PLAYLIST_ID
            + " GROUP BY " + PlaylistEntity.PLAYLIST_ID
            + " ORDER BY " + PlaylistEntity.PLAYLIST_NAME + " COLLATE NOCASE ASC")
    fun getPlaylistMetadata(): Flowable<List<PlaylistMetadataEntry>>

    @RewriteQueriesToDropUnusedColumns
    @Transaction
    @Query("SELECT *, MIN(" + PlaylistStreamEntity.JOIN_INDEX + ")"
            + " FROM " + StreamEntity.STREAM_TABLE + " INNER JOIN"
            + " (SELECT " + PlaylistStreamEntity.JOIN_STREAM_ID + "," + PlaylistStreamEntity.JOIN_INDEX
            + " FROM " + PlaylistStreamEntity.PLAYLIST_STREAM_JOIN_TABLE
            + " WHERE " + PlaylistStreamEntity.JOIN_PLAYLIST_ID + " = :playlistId)"
            + " ON " + StreamEntity.STREAM_ID + " = " + PlaylistStreamEntity.JOIN_STREAM_ID
            + " LEFT JOIN "
            + "(SELECT " + PlaylistStreamEntity.JOIN_STREAM_ID + " AS " + StreamStateEntity.JOIN_STREAM_ID_ALIAS + ", "
            + StreamStateEntity.STREAM_PROGRESS_MILLIS
            + " FROM " + StreamStateEntity.STREAM_STATE_TABLE + " )"
            + " ON " + StreamEntity.STREAM_ID + " = " + StreamStateEntity.JOIN_STREAM_ID_ALIAS
            + " GROUP BY " + StreamEntity.STREAM_ID
            + " ORDER BY MIN(" + PlaylistStreamEntity.JOIN_INDEX + ") ASC")
    fun getStreamsWithoutDuplicates(playlistId: Long): Flowable<List<PlaylistStreamEntry>>

    @Transaction
    @Query("SELECT " + PlaylistEntity.PLAYLIST_TABLE + "." + PlaylistEntity.PLAYLIST_ID + ", "
            + PlaylistEntity.PLAYLIST_NAME + ", "

            + " CASE WHEN " + PlaylistEntity.PLAYLIST_THUMBNAIL_STREAM_ID + " = "
            + PlaylistEntity.DEFAULT_THUMBNAIL_ID + " THEN " + "'" + PlaylistEntity.DEFAULT_THUMBNAIL + "'"
            + " ELSE (SELECT " + StreamEntity.STREAM_THUMBNAIL_URL
            + " FROM " + StreamEntity.STREAM_TABLE
            + " WHERE " + StreamEntity.STREAM_TABLE + "." + StreamEntity.STREAM_ID + " = " + PlaylistEntity.PLAYLIST_THUMBNAIL_STREAM_ID
            + " ) END AS " + PlaylistEntity.PLAYLIST_THUMBNAIL_URL + ", "

            + "COALESCE(COUNT(" + PlaylistStreamEntity.JOIN_PLAYLIST_ID + "), 0) AS " + PlaylistMetadataEntry.PLAYLIST_STREAM_COUNT + ", "
            + "COALESCE(SUM(" + StreamEntity.STREAM_URL + " = :streamUrl), 0) AS "
            + PlaylistDuplicatesEntry.PLAYLIST_TIMES_STREAM_IS_CONTAINED

            + " FROM " + PlaylistEntity.PLAYLIST_TABLE
            + " LEFT JOIN " + PlaylistStreamEntity.PLAYLIST_STREAM_JOIN_TABLE
            + " ON " + PlaylistEntity.PLAYLIST_TABLE + "." + PlaylistEntity.PLAYLIST_ID + " = " + PlaylistStreamEntity.JOIN_PLAYLIST_ID

            + " LEFT JOIN " + StreamEntity.STREAM_TABLE
            + " ON " + StreamEntity.STREAM_TABLE + "." + StreamEntity.STREAM_ID + " = " + PlaylistStreamEntity.JOIN_STREAM_ID
            + " AND :streamUrl = :streamUrl"

            + " GROUP BY " + PlaylistStreamEntity.JOIN_PLAYLIST_ID
            + " ORDER BY " + PlaylistEntity.PLAYLIST_NAME + " COLLATE NOCASE ASC")
    fun getPlaylistDuplicatesMetadata(streamUrl: String?): Flowable<List<PlaylistDuplicatesEntry>>
}
