package org.schabi.newpipe.ui.local.history

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.database.NewPipeDatabase.getInstance
import org.schabi.newpipe.R
import org.schabi.newpipe.database.LocalItem
import org.schabi.newpipe.database.history.dao.SearchHistoryDAO
import org.schabi.newpipe.database.history.dao.StreamHistoryDAO
import org.schabi.newpipe.database.history.model.SearchHistoryEntry
import org.schabi.newpipe.database.history.model.StreamHistoryEntity
import org.schabi.newpipe.database.history.model.StreamHistoryEntry
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry
import org.schabi.newpipe.database.stream.StreamStatisticsEntry
import org.schabi.newpipe.database.stream.dao.StreamDAO
import org.schabi.newpipe.database.stream.dao.StreamStateDAO
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.player.playqueue.PlayQueueItem
import org.schabi.newpipe.util.ExtractorHelper.getStreamInfo
import java.time.OffsetDateTime
import java.time.ZoneOffset

class HistoryRecordManager(context: Context) {
    private val database = getInstance(context)
    private val streamTable: StreamDAO = database.streamDAO()
    private val streamHistoryTable: StreamHistoryDAO = database.streamHistoryDAO()
    private val searchHistoryTable: SearchHistoryDAO = database.searchHistoryDAO()
    private val streamStateTable: StreamStateDAO = database.streamStateDAO()
    private val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val searchHistoryKey = context.getString(R.string.enable_search_history_key)
    private val streamHistoryKey = context.getString(R.string.enable_watch_history_key)

    ///////////////////////////////////////////////////////
    // Watch History
    ///////////////////////////////////////////////////////
    /**
     * Marks a stream item as watched such that it is hidden from the feed if watched videos are
     * hidden. Adds a history entry and updates the stream progress to 100%.
     *
     * @see FeedViewModel.setSaveShowPlayedItems
     *
     * @param info the item to mark as watched
     * @return a Maybe containing the ID of the item if successful
     */
    fun markAsWatched(info: StreamInfoItem): Maybe<Long> {
        if (!isStreamHistoryEnabled) {
            return Maybe.empty()
        }

        val currentTime = OffsetDateTime.now(ZoneOffset.UTC)
        return Maybe.fromCallable {
            database.runInTransaction<Long> {
                val streamId: Long
                val duration: Long
                // Duration will not exist if the item was loaded with fast mode, so fetch it if empty
                if (info.duration < 0) {
                    val completeInfo = getStreamInfo(
                        info.serviceId,
                        info.url,
                        false
                    )
                        .subscribeOn(Schedulers.io())
                        .blockingGet()
                    duration = completeInfo.duration
                    streamId = streamTable.upsert(StreamEntity(completeInfo))
                } else {
                    duration = info.duration
                    streamId = streamTable.upsert(StreamEntity(info))
                }

                // Update the stream progress to the full duration of the video
                val entity = StreamStateEntity(
                    streamId,
                    duration * 1000
                )
                streamStateTable.upsert(entity)

                // Add a history entry
                val latestEntry = streamHistoryTable.getLatestEntry(streamId)
                if (latestEntry == null) {
                    // never actually viewed: add history entry but with 0 views
                    return@runInTransaction streamHistoryTable.insert(StreamHistoryEntity(streamId, currentTime, 0))
                } else {
                    return@runInTransaction 0L
                }
            }
        }.subscribeOn(Schedulers.io())
    }

    fun onViewed(info: StreamInfo?): Maybe<Long> {
        if (!isStreamHistoryEnabled) {
            return Maybe.empty()
        }

        val currentTime = OffsetDateTime.now(ZoneOffset.UTC)
        return Maybe.fromCallable {
            database.runInTransaction<Long> {
                val streamId = streamTable.upsert(StreamEntity(info!!))
                val latestEntry = streamHistoryTable.getLatestEntry(streamId)
                if (latestEntry != null) {
                    streamHistoryTable.delete(latestEntry)
                    latestEntry.accessDate = currentTime
                    latestEntry.repeatCount = latestEntry.repeatCount + 1
                    return@runInTransaction streamHistoryTable.insert(latestEntry)
                } else {
                    // just viewed for the first time: set 1 view
                    return@runInTransaction streamHistoryTable.insert(StreamHistoryEntity(streamId, currentTime, 1))
                }
            }
        }.subscribeOn(Schedulers.io())
    }

    fun deleteStreamHistoryAndState(streamId: Long): Completable {
        return Completable.fromAction {
            streamStateTable.deleteState(streamId)
            streamHistoryTable.deleteStreamHistory(streamId)
        }.subscribeOn(Schedulers.io())
    }

    fun deleteWholeStreamHistory(): Single<Int> {
        return Single.fromCallable { streamHistoryTable.deleteAll() }
            .subscribeOn(Schedulers.io())
    }

    fun deleteCompleteStreamStateHistory(): Single<Int> {
        return Single.fromCallable { streamStateTable.deleteAll() }
            .subscribeOn(Schedulers.io())
    }

    val streamHistorySortedById: Flowable<List<StreamHistoryEntry>>
        get() = streamHistoryTable.historySortedById?.subscribeOn(Schedulers.io()) ?: Flowable.empty()

    val streamStatistics: Flowable<List<StreamStatisticsEntry>>
        get() = streamHistoryTable.getStatistics()?.subscribeOn(Schedulers.io()) ?: Flowable.empty()

    private val isStreamHistoryEnabled: Boolean
        get() = sharedPreferences.getBoolean(streamHistoryKey, false)

    ///////////////////////////////////////////////////////
    // Search History
    ///////////////////////////////////////////////////////
    fun onSearched(serviceId: Int, search: String?): Maybe<Long> {
        if (!isSearchHistoryEnabled) {
            return Maybe.empty()
        }

        val currentTime = OffsetDateTime.now(ZoneOffset.UTC)
        val newEntry = SearchHistoryEntry(currentTime, serviceId, search)

        return Maybe.fromCallable {
            database.runInTransaction<Long> {
                val latestEntry = searchHistoryTable.getLatestEntry()
                if (latestEntry != null && latestEntry.hasEqualValues(newEntry)) {
                    latestEntry.creationDate = currentTime
                    return@runInTransaction searchHistoryTable.update(latestEntry).toLong()
                } else {
                    return@runInTransaction searchHistoryTable.insert(newEntry)
                }
            }
        }.subscribeOn(Schedulers.io())
    }

    fun deleteSearchHistory(search: String?): Single<Int> {
        return Single.fromCallable { searchHistoryTable.deleteAllWhereQuery(search) }
            .subscribeOn(Schedulers.io())
    }

    fun deleteCompleteSearchHistory(): Single<Int> {
        return Single.fromCallable { searchHistoryTable.deleteAll() }
            .subscribeOn(Schedulers.io())
    }

    fun getRelatedSearches(query: String,
                           similarQueryLimit: Int,
                           uniqueQueryLimit: Int
    ): Flowable<List<String>> {
        val ret = if (query.length > 0) searchHistoryTable.getSimilarEntries(query, similarQueryLimit)
        else searchHistoryTable.getUniqueEntries(uniqueQueryLimit)
        return ret ?: Flowable.empty()
    }

    private val isSearchHistoryEnabled: Boolean
        get() = sharedPreferences.getBoolean(searchHistoryKey, false)

    ///////////////////////////////////////////////////////
    // Stream State History
    ///////////////////////////////////////////////////////
    fun loadStreamState(queueItem: PlayQueueItem): Maybe<StreamStateEntity> {
        return queueItem.stream
            .map { info: StreamInfo? ->
                streamTable.upsert(StreamEntity(
                    info!!))
            }
            .flatMapPublisher { streamId: Long? ->
                streamStateTable.getState(
                    streamId!!)
            }
            .firstElement()
            .flatMap { list: List<StreamStateEntity> ->
                if (list.isEmpty()) Maybe.empty() else Maybe.just(
                    list[0])
            }
            .filter { state: StreamStateEntity -> state.isValid(queueItem.duration) }
            .subscribeOn(Schedulers.io())
    }

    fun loadStreamState(info: StreamInfo): Maybe<StreamStateEntity> {
        return Single.fromCallable { streamTable.upsert(StreamEntity(info)) }
            .flatMapPublisher { streamId: Long? ->
                streamStateTable.getState(
                    streamId!!)
            }
            .firstElement()
            .flatMap { list: List<StreamStateEntity> ->
                if (list.isEmpty()) Maybe.empty() else Maybe.just(
                    list[0])
            }
            .filter { state: StreamStateEntity -> state.isValid(info.duration) }
            .subscribeOn(Schedulers.io())
    }

    fun saveStreamState(info: StreamInfo, progressMillis: Long): Completable {
        return Completable.fromAction {
            database.runInTransaction {
                val streamId = streamTable.upsert(StreamEntity(info))
                val state = StreamStateEntity(streamId, progressMillis)
                if (state.isValid(info.duration)) {
                    streamStateTable.upsert(state)
                }
            }
        }.subscribeOn(Schedulers.io())
    }

    fun loadStreamState(info: InfoItem): Single<Array<StreamStateEntity>> {
        return Single.fromCallable<Array<StreamStateEntity>> {
            val entities = streamTable
                .getStream(info.serviceId.toLong(), info.url).blockingFirst()
            if (entities.isEmpty()) {
                return@fromCallable arrayOf<StreamStateEntity>()
            }
            val states = streamStateTable
                .getState(entities[0].uid).blockingFirst()
            if (states.isEmpty()) {
                return@fromCallable arrayOf<StreamStateEntity>()
            }
            arrayOf(states[0])
        }.subscribeOn(Schedulers.io())
    }

    fun loadLocalStreamStateBatch(items: List<LocalItem>
    ): Single<List<StreamStateEntity?>> {
        return Single.fromCallable<List<StreamStateEntity?>> {
            val result: MutableList<StreamStateEntity?> = ArrayList(items.size)
            for (item in items) {
                val streamId = when (item) {
                    is StreamStatisticsEntry -> {
                        item.streamId
                    }
//                    TODO: not compatible
//                    is PlaylistStreamEntity -> {
//                        (item as PlaylistStreamEntity).streamUid
//                    }
                    is PlaylistStreamEntry -> {
                        item.streamId
                    }
                    else -> {
                        result.add(null)
                        continue
                    }
                }
                val states = streamStateTable.getState(streamId)
                    .blockingFirst()
                if (states.isEmpty()) {
                    result.add(null)
                } else {
                    result.add(states[0])
                }
            }
            result
        }.subscribeOn(Schedulers.io())
    }

    ///////////////////////////////////////////////////////
    // Utility
    ///////////////////////////////////////////////////////
    fun removeOrphanedRecords(): Single<Int> {
        return Single.fromCallable { streamTable.deleteOrphans() }.subscribeOn(Schedulers.io())
    }
}
