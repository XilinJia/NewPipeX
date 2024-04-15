package org.schabi.newpipe.database.history.dao

import androidx.room.Dao
import androidx.room.Query
import io.reactivex.rxjava3.core.Flowable
import org.schabi.newpipe.database.history.model.SearchHistoryEntry

@Dao
interface SearchHistoryDAO : HistoryDAO<SearchHistoryEntry> {
    @Query("SELECT * FROM " + SearchHistoryEntry.TABLE_NAME
            + " WHERE " + SearchHistoryEntry.ID + " = (SELECT MAX(" + SearchHistoryEntry.ID + ") FROM " + SearchHistoryEntry.TABLE_NAME + ")")
    override fun getLatestEntry(): SearchHistoryEntry

    @Query("DELETE FROM " + SearchHistoryEntry.TABLE_NAME)
    override fun deleteAll(): Int

    @Query("DELETE FROM " + SearchHistoryEntry.TABLE_NAME + " WHERE " + SearchHistoryEntry.SEARCH + " = :query")
    fun deleteAllWhereQuery(query: String?): Int

    @Query("SELECT * FROM " + SearchHistoryEntry.TABLE_NAME + ORDER_BY_CREATION_DATE)
    override fun getAll(): Flowable<List<SearchHistoryEntry>>

    @Query("SELECT " + SearchHistoryEntry.SEARCH + " FROM " + SearchHistoryEntry.TABLE_NAME + " GROUP BY " + SearchHistoryEntry.SEARCH
            + ORDER_BY_MAX_CREATION_DATE + " LIMIT :limit")
    fun getUniqueEntries(limit: Int): Flowable<List<String>>?

    @Query("SELECT * FROM " + SearchHistoryEntry.TABLE_NAME
            + " WHERE " + SearchHistoryEntry.SERVICE_ID + " = :serviceId" + ORDER_BY_CREATION_DATE)
    override fun listByService(serviceId: Int): Flowable<List<SearchHistoryEntry>>?

    @Query("SELECT " + SearchHistoryEntry.SEARCH + " FROM " + SearchHistoryEntry.TABLE_NAME + " WHERE " + SearchHistoryEntry.SEARCH + " LIKE :query || '%'"
            + " GROUP BY " + SearchHistoryEntry.SEARCH + ORDER_BY_MAX_CREATION_DATE + " LIMIT :limit")
    fun getSimilarEntries(query: String?, limit: Int): Flowable<List<String>>?

    companion object {
        const val ORDER_BY_CREATION_DATE: String = " ORDER BY " + SearchHistoryEntry.CREATION_DATE + " DESC"
        const val ORDER_BY_MAX_CREATION_DATE: String = " ORDER BY MAX(" + SearchHistoryEntry.CREATION_DATE + ") DESC"
    }
}
