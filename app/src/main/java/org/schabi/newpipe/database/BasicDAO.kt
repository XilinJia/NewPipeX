package org.schabi.newpipe.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Update
import io.reactivex.rxjava3.core.Flowable

@Dao
interface BasicDAO<Entity> {
    /* Inserts */
    @Insert
    fun insert(entity: Entity): Long

    @Insert
    fun insertAll(entities: Collection<Entity>): List<Long?>?

    fun getAll(): Flowable<List<Entity>>

    fun listByService(serviceId: Int): Flowable<List<Entity>>?

    /* Deletes */
    @Delete
    fun delete(entity: Entity)

    fun deleteAll(): Int

    /* Updates */
    @Update
    fun update(entity: Entity): Int

    @Update
    fun update(entities: Collection<Entity>)
}
