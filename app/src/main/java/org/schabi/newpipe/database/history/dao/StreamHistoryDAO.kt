package org.schabi.newpipe.database.history.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import io.reactivex.rxjava3.core.Flowable
import org.schabi.newpipe.database.history.model.StreamHistoryEntity
import org.schabi.newpipe.database.history.model.StreamHistoryEntry
import org.schabi.newpipe.database.stream.StreamStatisticsEntry
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.stream.model.StreamStateEntity

@Dao
abstract class StreamHistoryDAO : HistoryDAO<StreamHistoryEntity> {
    @Query("SELECT * FROM " + StreamHistoryEntity.STREAM_HISTORY_TABLE
            + " WHERE " + StreamHistoryEntity.STREAM_ACCESS_DATE + " = "
            + "(SELECT MAX(" + StreamHistoryEntity.STREAM_ACCESS_DATE + ") FROM " + StreamHistoryEntity.STREAM_HISTORY_TABLE + ")")
    abstract override fun getLatestEntry(): StreamHistoryEntity

    @Query("SELECT * FROM " + StreamHistoryEntity.STREAM_HISTORY_TABLE)
    abstract override fun getAll(): Flowable<List<StreamHistoryEntity>>

    @Query("DELETE FROM " + StreamHistoryEntity.STREAM_HISTORY_TABLE)
    abstract override fun deleteAll(): Int

    override fun listByService(serviceId: Int): Flowable<List<StreamHistoryEntity>>? {
        throw UnsupportedOperationException()
    }

    @get:Query("SELECT * FROM " + StreamEntity.STREAM_TABLE
            + " INNER JOIN " + StreamHistoryEntity.STREAM_HISTORY_TABLE
            + " ON " + StreamEntity.STREAM_ID + " = " + StreamHistoryEntity.JOIN_STREAM_ID
            + " ORDER BY " + StreamHistoryEntity.STREAM_ACCESS_DATE + " DESC")
    abstract val history: Flowable<List<StreamHistoryEntry>>?


    @get:Query("SELECT * FROM " + StreamEntity.STREAM_TABLE
            + " INNER JOIN " + StreamHistoryEntity.STREAM_HISTORY_TABLE
            + " ON " + StreamEntity.STREAM_ID + " = " + StreamHistoryEntity.JOIN_STREAM_ID
            + " ORDER BY " + StreamEntity.STREAM_ID + " ASC")
    abstract val historySortedById: Flowable<List<StreamHistoryEntry>>?

    @Query("SELECT * FROM " + StreamHistoryEntity.STREAM_HISTORY_TABLE + " WHERE " + StreamHistoryEntity.JOIN_STREAM_ID
            + " = :streamId ORDER BY " + StreamHistoryEntity.STREAM_ACCESS_DATE + " DESC LIMIT 1")
    abstract fun getLatestEntry(streamId: Long): StreamHistoryEntity?

    @Query("DELETE FROM " + StreamHistoryEntity.STREAM_HISTORY_TABLE + " WHERE " + StreamHistoryEntity.JOIN_STREAM_ID + " = :streamId")
    abstract fun deleteStreamHistory(streamId: Long): Int

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM " + StreamEntity.STREAM_TABLE // Select the latest entry and watch count for each stream id on history table

            + " INNER JOIN "
            + "(SELECT " + StreamHistoryEntity.JOIN_STREAM_ID + ", "
            + "  MAX(" + StreamHistoryEntity.STREAM_ACCESS_DATE + ") AS " + StreamStatisticsEntry.STREAM_LATEST_DATE + ", "
            + "  SUM(" + StreamHistoryEntity.STREAM_REPEAT_COUNT + ") AS " + StreamStatisticsEntry.STREAM_WATCH_COUNT
            + " FROM " + StreamHistoryEntity.STREAM_HISTORY_TABLE + " GROUP BY " + StreamHistoryEntity.JOIN_STREAM_ID + ")"

            + " ON " + StreamEntity.STREAM_ID + " = " + StreamHistoryEntity.JOIN_STREAM_ID

            + " LEFT JOIN "
            + "(SELECT " + StreamHistoryEntity.JOIN_STREAM_ID + " AS " + StreamStateEntity.JOIN_STREAM_ID_ALIAS + ", "
            + StreamStateEntity.STREAM_PROGRESS_MILLIS
            + " FROM " + StreamStateEntity.STREAM_STATE_TABLE + " )"
            + " ON " + StreamEntity.STREAM_ID + " = " + StreamStateEntity.JOIN_STREAM_ID_ALIAS)
    abstract fun getStatistics(): Flowable<List<StreamStatisticsEntry>>?
}
