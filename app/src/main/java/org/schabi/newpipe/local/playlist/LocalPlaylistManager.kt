package org.schabi.newpipe.local.playlist

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.PlaylistDuplicatesEntry
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry
import org.schabi.newpipe.database.playlist.dao.PlaylistDAO
import org.schabi.newpipe.database.playlist.dao.PlaylistStreamDAO
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.dao.StreamDAO
import org.schabi.newpipe.database.stream.model.StreamEntity
import java.util.concurrent.Callable

class LocalPlaylistManager(private val database: AppDatabase) {
    private val streamTable: StreamDAO = database.streamDAO()
    private val playlistTable: PlaylistDAO = database.playlistDAO()
    private val playlistStreamTable: PlaylistStreamDAO = database.playlistStreamDAO()

    fun createPlaylist(name: String, streams: List<StreamEntity>): Maybe<List<Long>> {
        // Disallow creation of empty playlists
        if (streams.isEmpty()) {
            return Maybe.empty()
        }

        return Maybe.fromCallable(Callable<List<Long>> {
            database.runInTransaction<List<Long>>(
                Callable {
                    val streamIds = streamTable.upsertAll(streams)
                    val newPlaylist = PlaylistEntity(name, false, streamIds[0])
                    insertJoinEntities(playlistTable.insert(newPlaylist), streamIds, 0)
                    streamIds
                }
            )
        }).subscribeOn(Schedulers.io())
    }

    fun appendToPlaylist(playlistId: Long, streams: List<StreamEntity>): Maybe<List<Long>> {
        return playlistStreamTable.getMaximumIndexOf(playlistId)
            ?.firstElement()
            ?.map { maxJoinIndex: Int ->
                database.runInTransaction<List<Long>> {
                    val streamIds = streamTable.upsertAll(streams)
                    insertJoinEntities(playlistId, streamIds, maxJoinIndex + 1)?.filterNotNull() ?: listOf()
                }
            }?.subscribeOn(Schedulers.io()) ?: Maybe.empty()
    }

    private fun insertJoinEntities(playlistId: Long, streamIds: List<Long>, indexOffset: Int): List<Long?>? {
        val joinEntities: MutableList<PlaylistStreamEntity> = ArrayList(streamIds.size)

        for (index in streamIds.indices) {
            joinEntities.add(PlaylistStreamEntity(playlistId, streamIds[index], index + indexOffset))
        }
        return playlistStreamTable.insertAll(joinEntities)
    }

    fun updateJoin(playlistId: Long, streamIds: List<Long?>): Completable {
        val joinEntities: MutableList<PlaylistStreamEntity> = ArrayList(streamIds.size)
        for (i in streamIds.indices) {
            joinEntities.add(PlaylistStreamEntity(playlistId, streamIds[i]!!, i))
        }

        return Completable.fromRunnable {
            database.runInTransaction {
                playlistStreamTable.deleteBatch(playlistId)
                playlistStreamTable.insertAll(joinEntities)
            }
        }.subscribeOn(Schedulers.io())
    }

    val playlists: Flowable<List<PlaylistMetadataEntry>>
        get() = playlistStreamTable.getPlaylistMetadata().subscribeOn(Schedulers.io())

    fun getDistinctPlaylistStreams(playlistId: Long): Flowable<List<PlaylistStreamEntry>> {
        return playlistStreamTable.getStreamsWithoutDuplicates(playlistId).subscribeOn(Schedulers.io())
    }

    /**
     * Get playlists with attached information about how many times the provided stream is already
     * contained in each playlist.
     *
     * @param streamUrl the stream url for which to check for duplicates
     * @return a list of [PlaylistDuplicatesEntry]
     */
    fun getPlaylistDuplicates(streamUrl: String?): Flowable<List<PlaylistDuplicatesEntry>> {
        return playlistStreamTable.getPlaylistDuplicatesMetadata(streamUrl)
            .subscribeOn(Schedulers.io())
    }

    fun getPlaylistStreams(playlistId: Long): Flowable<List<PlaylistStreamEntry>> {
        return playlistStreamTable.getOrderedStreamsOf(playlistId).subscribeOn(Schedulers.io())
    }

    fun deletePlaylist(playlistId: Long): Single<Int> {
        return Single.fromCallable { playlistTable.deletePlaylist(playlistId) }
            .subscribeOn(Schedulers.io())
    }

    fun renamePlaylist(playlistId: Long, name: String?): Maybe<Int> {
        return modifyPlaylist(playlistId, name, THUMBNAIL_ID_LEAVE_UNCHANGED, false)
    }

    fun changePlaylistThumbnail(playlistId: Long,
                                thumbnailStreamId: Long,
                                isPermanent: Boolean
    ): Maybe<Int> {
        return modifyPlaylist(playlistId, null, thumbnailStreamId, isPermanent)
    }

    fun getPlaylistThumbnailStreamId(playlistId: Long): Long {
        val item = playlistTable.getPlaylist(playlistId)
        return if (item == null) 0L else item.blockingFirst()[0].thumbnailStreamId
    }

    fun getIsPlaylistThumbnailPermanent(playlistId: Long): Boolean {
        val item = playlistTable.getPlaylist(playlistId)
        return if (item == null) false else item.blockingFirst()[0].isThumbnailPermanent
    }

    fun getAutomaticPlaylistThumbnailStreamId(playlistId: Long): Long {
        val streamId = playlistStreamTable.getAutomaticThumbnailStreamId(playlistId).blockingFirst()
        if (streamId < 0) {
            return PlaylistEntity.DEFAULT_THUMBNAIL_ID
        }
        return streamId
    }

    private fun modifyPlaylist(playlistId: Long,
                               name: String?,
                               thumbnailStreamId: Long,
                               isPermanent: Boolean
    ): Maybe<Int> {
        return playlistTable.getPlaylist(playlistId)
            ?.firstElement()
            ?.filter { playlistEntities: List<PlaylistEntity?> -> !playlistEntities.isEmpty() }
            ?.map { playlistEntities: List<PlaylistEntity> ->
                val playlist = playlistEntities[0]
                if (name != null) {
                    playlist.name = name
                }
                if (thumbnailStreamId != THUMBNAIL_ID_LEAVE_UNCHANGED) {
                    playlist.thumbnailStreamId = thumbnailStreamId
                    playlist.isThumbnailPermanent = isPermanent
                }
                playlistTable.update(playlist)
            }?.subscribeOn(Schedulers.io()) ?: Maybe.empty()
    }

    fun hasPlaylists(): Maybe<Boolean> {
        return playlistTable.count
            ?.firstElement()
            ?.map { count: Long -> count > 0 }
            ?.subscribeOn(Schedulers.io()) ?: Maybe.empty()
    }

    companion object {
        private const val THUMBNAIL_ID_LEAVE_UNCHANGED: Long = -2
    }
}
