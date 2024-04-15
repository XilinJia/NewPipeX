package org.schabi.newpipe.database.history.dao

import org.schabi.newpipe.database.BasicDAO

interface HistoryDAO<T> : BasicDAO<T> {
    fun getLatestEntry(): T
}
