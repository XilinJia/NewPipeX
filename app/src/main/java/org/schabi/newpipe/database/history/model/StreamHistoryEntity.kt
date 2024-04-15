package org.schabi.newpipe.database.history.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.Index
import org.schabi.newpipe.database.history.model.StreamHistoryEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import java.time.OffsetDateTime

@Entity(tableName = StreamHistoryEntity.STREAM_HISTORY_TABLE,
    primaryKeys = [StreamHistoryEntity.JOIN_STREAM_ID, StreamHistoryEntity.STREAM_ACCESS_DATE],
    indices = [Index(value = [StreamHistoryEntity.JOIN_STREAM_ID])],
    foreignKeys = [ForeignKey(entity = StreamEntity::class,
        parentColumns = arrayOf(StreamEntity.STREAM_ID),
        childColumns = arrayOf(StreamHistoryEntity.JOIN_STREAM_ID),
        onDelete = CASCADE,
        onUpdate = CASCADE)])
class StreamHistoryEntity
/**
 * @param streamUid the stream id this history item will refer to
 * @param accessDate the last time the stream was accessed
 * @param repeatCount the total number of views this stream received
 */(@JvmField @field:ColumnInfo(name = JOIN_STREAM_ID) var streamUid: Long,
    @JvmField @field:ColumnInfo(name = STREAM_ACCESS_DATE) var accessDate: OffsetDateTime,
    @JvmField @field:ColumnInfo(name = STREAM_REPEAT_COUNT) var repeatCount: Long
) {
     constructor(): this(0L, OffsetDateTime.now(), 0L)
    companion object {
        const val STREAM_HISTORY_TABLE: String = "stream_history"
        const val JOIN_STREAM_ID: String = "stream_id"
        const val STREAM_ACCESS_DATE: String = "access_date"
        const val STREAM_REPEAT_COUNT: String = "repeat_count"
    }
}
