package org.schabi.newpipe.local.holder

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.database.LocalItem
import org.schabi.newpipe.local.LocalItemBuilder
import org.schabi.newpipe.local.history.HistoryRecordManager
import java.time.format.DateTimeFormatter

/*
* Created by Christian Schabesberger on 12.02.17.
*
* Copyright (C) Christian Schabesberger 2016 <chris.schabesberger@mailbox.org>
* InfoItemHolder.java is part of NewPipe.
*
* NewPipe is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* NewPipe is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
*/
abstract class LocalItemHolder(@JvmField protected val itemBuilder: LocalItemBuilder, layoutId: Int,
                               parent: ViewGroup?
) : RecyclerView.ViewHolder(LayoutInflater.from(itemBuilder.context).inflate(layoutId, parent, false)) {
    abstract fun updateFromItem(item: LocalItem?, historyRecordManager: HistoryRecordManager?,
                                dateTimeFormatter: DateTimeFormatter?
    )

    open fun updateState(localItem: LocalItem?,
                         historyRecordManager: HistoryRecordManager?
    ) {
    }
}
