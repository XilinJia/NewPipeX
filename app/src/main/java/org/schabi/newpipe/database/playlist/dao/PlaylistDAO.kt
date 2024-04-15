package org.schabi.newpipe.database.playlist.dao

import androidx.room.Dao
import androidx.room.Query
import io.reactivex.rxjava3.core.Flowable
import org.schabi.newpipe.database.BasicDAO
import org.schabi.newpipe.database.playlist.model.PlaylistEntity

@Dao
interface PlaylistDAO : BasicDAO<PlaylistEntity> {
    @Query("SELECT * FROM " + PlaylistEntity.PLAYLIST_TABLE)
    override fun getAll(): Flowable<List<PlaylistEntity>>

    @Query("DELETE FROM " + PlaylistEntity.PLAYLIST_TABLE)
    override fun deleteAll(): Int

    override fun listByService(serviceId: Int): Flowable<List<PlaylistEntity>>? {
        throw UnsupportedOperationException()
    }

    @Query("SELECT * FROM " + PlaylistEntity.PLAYLIST_TABLE + " WHERE " + PlaylistEntity.PLAYLIST_ID + " = :playlistId")
    fun getPlaylist(playlistId: Long): Flowable<List<PlaylistEntity>>?

    @Query("DELETE FROM " + PlaylistEntity.PLAYLIST_TABLE + " WHERE " + PlaylistEntity.PLAYLIST_ID + " = :playlistId")
    fun deletePlaylist(playlistId: Long): Int

    @get:Query("SELECT COUNT(*) FROM " + PlaylistEntity.PLAYLIST_TABLE)
    val count: Flowable<Long>?
}
