package org.schabi.newpipe.ui.local.playlist

import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.dao.PlaylistRemoteDAO
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity
import org.schabi.newpipe.extractor.playlist.PlaylistInfo

class RemotePlaylistManager(db: AppDatabase) {
    private val playlistRemoteTable: PlaylistRemoteDAO = db.playlistRemoteDAO()

    val playlists: Flowable<List<PlaylistRemoteEntity>>
        get() = playlistRemoteTable.getAll().subscribeOn(Schedulers.io())

    fun getPlaylist(info: PlaylistInfo): Flowable<List<PlaylistRemoteEntity>> {
        return playlistRemoteTable.getPlaylist(info.serviceId.toLong(), info.url)?.subscribeOn(Schedulers.io()) ?: Flowable.empty()
    }

    fun deletePlaylist(playlistId: Long): Single<Int> {
        return Single.fromCallable { playlistRemoteTable.deletePlaylist(playlistId) }
            .subscribeOn(Schedulers.io())
    }

    fun onBookmark(playlistInfo: PlaylistInfo): Single<Long> {
        return Single.fromCallable {
            val playlist = PlaylistRemoteEntity(playlistInfo)
            playlistRemoteTable.upsert(playlist)
        }.subscribeOn(Schedulers.io())
    }

    fun onUpdate(playlistId: Long, playlistInfo: PlaylistInfo): Single<Int> {
        return Single.fromCallable {
            val playlist = PlaylistRemoteEntity(playlistInfo)
            playlist.uid = playlistId
            playlistRemoteTable.update(playlist)
        }.subscribeOn(Schedulers.io())
    }
}
