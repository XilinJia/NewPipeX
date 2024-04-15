package org.schabi.newpipe.database.playlist.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import io.reactivex.rxjava3.core.Flowable
import org.schabi.newpipe.database.BasicDAO
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity

@Dao
interface PlaylistRemoteDAO : BasicDAO<PlaylistRemoteEntity> {
    @Query("SELECT * FROM " + PlaylistRemoteEntity.REMOTE_PLAYLIST_TABLE)
    override fun getAll(): Flowable<List<PlaylistRemoteEntity>>

    @Query("DELETE FROM " + PlaylistRemoteEntity.REMOTE_PLAYLIST_TABLE)
    override fun deleteAll(): Int

    @Query("SELECT * FROM " + PlaylistRemoteEntity.REMOTE_PLAYLIST_TABLE
            + " WHERE " + PlaylistRemoteEntity.REMOTE_PLAYLIST_SERVICE_ID + " = :serviceId")
    override fun listByService(serviceId: Int): Flowable<List<PlaylistRemoteEntity>>?

    @Query("SELECT * FROM " + PlaylistRemoteEntity.REMOTE_PLAYLIST_TABLE + " WHERE "
            + PlaylistRemoteEntity.REMOTE_PLAYLIST_URL + " = :url AND " + PlaylistRemoteEntity.REMOTE_PLAYLIST_SERVICE_ID + " = :serviceId")
    fun getPlaylist(serviceId: Long, url: String?): Flowable<List<PlaylistRemoteEntity>>?

    @Query("SELECT " + PlaylistRemoteEntity.REMOTE_PLAYLIST_ID + " FROM " + PlaylistRemoteEntity.REMOTE_PLAYLIST_TABLE
            + " WHERE " + PlaylistRemoteEntity.REMOTE_PLAYLIST_URL + " = :url "
            + "AND " + PlaylistRemoteEntity.REMOTE_PLAYLIST_SERVICE_ID + " = :serviceId")
    fun getPlaylistIdInternal(serviceId: Long, url: String?): Long

    @Transaction
    fun upsert(playlist: PlaylistRemoteEntity): Long {
        val playlistId = getPlaylistIdInternal(playlist.serviceId.toLong(), playlist.url)

        if (playlistId == null) {
            return insert(playlist)
        } else {
            playlist.uid = playlistId
            update(playlist)
            return playlistId
        }
    }

    @Query("DELETE FROM " + PlaylistRemoteEntity.REMOTE_PLAYLIST_TABLE
            + " WHERE " + PlaylistRemoteEntity.REMOTE_PLAYLIST_ID + " = :playlistId")
    fun deletePlaylist(playlistId: Long): Int
}
