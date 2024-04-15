package org.schabi.newpipe.database.stream.dao

import androidx.room.*
import io.reactivex.rxjava3.core.Flowable
import org.schabi.newpipe.database.BasicDAO
import org.schabi.newpipe.database.stream.model.StreamStateEntity

@Dao
interface StreamStateDAO : BasicDAO<StreamStateEntity> {
    @Query("SELECT * FROM " + StreamStateEntity.STREAM_STATE_TABLE)
    override fun getAll(): Flowable<List<StreamStateEntity>>

    @Query("DELETE FROM " + StreamStateEntity.STREAM_STATE_TABLE)
    override fun deleteAll(): Int

    override fun listByService(serviceId: Int): Flowable<List<StreamStateEntity>>? {
        throw UnsupportedOperationException()
    }

    @Query("SELECT * FROM " + StreamStateEntity.STREAM_STATE_TABLE + " WHERE " + StreamStateEntity.JOIN_STREAM_ID + " = :streamId")
    fun getState(streamId: Long): Flowable<List<StreamStateEntity>>

    @Query("DELETE FROM " + StreamStateEntity.STREAM_STATE_TABLE + " WHERE " + StreamStateEntity.JOIN_STREAM_ID + " = :streamId")
    fun deleteState(streamId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun silentInsertInternal(streamState: StreamStateEntity)

    @Transaction
    fun upsert(stream: StreamStateEntity): Long {
        silentInsertInternal(stream)
        return update(stream).toLong()
    }
}
