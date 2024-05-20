package org.schabi.newpipe.local

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.database.LocalItem
import org.schabi.newpipe.database.LocalItem.LocalItemType
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.info_list.ItemViewMode
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.local.holder.*
import org.schabi.newpipe.util.FallbackViewHolder
import org.schabi.newpipe.util.Localization.getPreferredLocale
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.util.OnClickGesture
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/*
* Created by Christian Schabesberger on 01.08.16.
*
* Copyright (C) Christian Schabesberger 2016 <chris.schabesberger@mailbox.org>
* InfoListAdapter.java is part of NewPipe.
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
class LocalItemListAdapter(context: Context?) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val localItemBuilder = LocalItemBuilder(context!!)
    val itemsList: ArrayList<LocalItem> = ArrayList()
    private val recordManager = HistoryRecordManager(context!!)
    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
        .withLocale(getPreferredLocale(context!!))

    private var showFooter = false
    private var header: View? = null
    private var footer: View? = null
    private var itemViewMode = ItemViewMode.LIST

    fun setSelectedListener(listener: OnClickGesture<LocalItem>?) {
        localItemBuilder.onItemSelectedListener = listener
    }

    fun unsetSelectedListener() {
        localItemBuilder.onItemSelectedListener = null
    }

    fun addItems(data: List<LocalItem>?) {
        if (data == null) return
        Logd(TAG, "addItems() before > localItems.size() = ${itemsList.size}, data.size() = ${data.size}")

        val offsetStart = sizeConsideringHeader()
        itemsList.addAll(data)
        Logd(TAG, "addItems() after > offsetStart = $offsetStart, localItems.size() = ${itemsList.size}, header = $header, footer = $footer, showFooter = $showFooter")

        notifyItemRangeInserted(offsetStart, data.size)

        if (footer != null && showFooter) {
            val footerNow = sizeConsideringHeader()
            notifyItemMoved(offsetStart, footerNow)
            Logd(TAG, "addItems() footer from $offsetStart to $footerNow")
        }
    }

    fun removeItem(data: LocalItem) {
        val index = itemsList.indexOf(data)
        if (index != -1) {
            itemsList.removeAt(index)
            notifyItemRemoved(index + (if (header != null) 1 else 0))
        } else {
            // this happens when
            // 1) removeItem is called on infoItemDuplicate as in showStreamItemDialog of
            // LocalPlaylistFragment in this case need to implement delete object by it's duplicate

            // OR

            // 2)data not in itemList and UI is still not updated so notifyDataSetChanged()

            notifyDataSetChanged()
        }
    }

    fun swapItems(fromAdapterPosition: Int, toAdapterPosition: Int): Boolean {
        val actualFrom = adapterOffsetWithoutHeader(fromAdapterPosition)
        val actualTo = adapterOffsetWithoutHeader(toAdapterPosition)

        if (actualFrom < 0 || actualTo < 0) return false
        if (actualFrom >= itemsList.size || actualTo >= itemsList.size) return false

        itemsList.add(actualTo, itemsList.removeAt(actualFrom))
        notifyItemMoved(fromAdapterPosition, toAdapterPosition)
        return true
    }

    fun clearStreamItemList() {
        if (itemsList.isEmpty()) {
            return
        }
        itemsList.clear()
        notifyDataSetChanged()
    }

    fun setItemViewMode(itemViewMode: ItemViewMode) {
        this.itemViewMode = itemViewMode
    }

    fun setHeader(header: View) {
        val changed = header !== this.header
        this.header = header
        if (changed) {
            notifyDataSetChanged()
        }
    }

    fun setFooter(view: View?) {
        this.footer = view
    }

    fun showFooter(show: Boolean) {

        Logd(TAG, "showFooter() called with: show = [$show]")

        if (show == showFooter) {
            return
        }

        showFooter = show
        if (show) {
            notifyItemInserted(sizeConsideringHeader())
        } else {
            notifyItemRemoved(sizeConsideringHeader())
        }
    }

    private fun adapterOffsetWithoutHeader(offset: Int): Int {
        return offset - (if (header != null) 1 else 0)
    }

    private fun sizeConsideringHeader(): Int {
        return itemsList.size + (if (header != null) 1 else 0)
    }

    override fun getItemCount(): Int {
        var count = itemsList.size
        if (header != null) {
            count++
        }
        if (footer != null && showFooter) {
            count++
        }


        Logd(TAG, "getItemCount() called, count = " + count + ", "
                + "localItems.size() = " + itemsList.size + ", "
                + "header = " + header + ", footer = " + footer + ", "
                + "showFooter = " + showFooter)

        return count
    }

    override fun getItemViewType(position: Int): Int {
        var position = position

        Logd(TAG, "getItemViewType() called with: position = [$position]")


        if (header != null && position == 0) {
            return HEADER_TYPE
        } else if (header != null) {
            position--
        }
        if (footer != null && position == itemsList.size && showFooter) {
            return FOOTER_TYPE
        }
        val item = itemsList[position]
        when (item.localItemType) {
            LocalItemType.PLAYLIST_LOCAL_ITEM -> return if (itemViewMode == ItemViewMode.CARD) {
                LOCAL_PLAYLIST_CARD_HOLDER_TYPE
            } else if (itemViewMode == ItemViewMode.GRID) {
                LOCAL_PLAYLIST_GRID_HOLDER_TYPE
            } else {
                LOCAL_PLAYLIST_HOLDER_TYPE
            }
            LocalItemType.PLAYLIST_REMOTE_ITEM -> return if (itemViewMode == ItemViewMode.CARD) {
                REMOTE_PLAYLIST_CARD_HOLDER_TYPE
            } else if (itemViewMode == ItemViewMode.GRID) {
                REMOTE_PLAYLIST_GRID_HOLDER_TYPE
            } else {
                REMOTE_PLAYLIST_HOLDER_TYPE
            }
            LocalItemType.PLAYLIST_STREAM_ITEM -> return if (itemViewMode == ItemViewMode.CARD) {
                STREAM_PLAYLIST_CARD_HOLDER_TYPE
            } else if (itemViewMode == ItemViewMode.GRID) {
                STREAM_PLAYLIST_GRID_HOLDER_TYPE
            } else {
                STREAM_PLAYLIST_HOLDER_TYPE
            }
            LocalItemType.STATISTIC_STREAM_ITEM -> return if (itemViewMode == ItemViewMode.CARD) {
                STREAM_STATISTICS_CARD_HOLDER_TYPE
            } else if (itemViewMode == ItemViewMode.GRID) {
                STREAM_STATISTICS_GRID_HOLDER_TYPE
            } else {
                STREAM_STATISTICS_HOLDER_TYPE
            }
            else -> {
                Log.e(TAG, "No holder type has been considered for item: ["
                        + item.localItemType + "]")
                return -1
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup,
                                    type: Int
    ): RecyclerView.ViewHolder {

        Logd(TAG, "onCreateViewHolder() called with: "
                + "parent = [" + parent + "], type = [" + type + "]")

        when (type) {
            HEADER_TYPE -> return HeaderFooterHolder(header!!)
            FOOTER_TYPE -> return HeaderFooterHolder(footer!!)
            LOCAL_PLAYLIST_HOLDER_TYPE -> return LocalPlaylistItemHolder(localItemBuilder, parent)
            LOCAL_PLAYLIST_GRID_HOLDER_TYPE -> return LocalPlaylistGridItemHolder(localItemBuilder, parent)
            LOCAL_PLAYLIST_CARD_HOLDER_TYPE -> return LocalPlaylistCardItemHolder(localItemBuilder, parent)
            REMOTE_PLAYLIST_HOLDER_TYPE -> return RemotePlaylistItemHolder(localItemBuilder, parent)
            REMOTE_PLAYLIST_GRID_HOLDER_TYPE -> return RemotePlaylistGridItemHolder(localItemBuilder, parent)
            REMOTE_PLAYLIST_CARD_HOLDER_TYPE -> return RemotePlaylistCardItemHolder(localItemBuilder, parent)
            STREAM_PLAYLIST_HOLDER_TYPE -> return LocalPlaylistStreamItemHolder(localItemBuilder, parent)
            STREAM_PLAYLIST_GRID_HOLDER_TYPE -> return LocalPlaylistStreamGridItemHolder(localItemBuilder, parent)
            STREAM_PLAYLIST_CARD_HOLDER_TYPE -> return LocalPlaylistStreamCardItemHolder(localItemBuilder, parent)
            STREAM_STATISTICS_HOLDER_TYPE -> return LocalStatisticStreamItemHolder(localItemBuilder, parent)
            STREAM_STATISTICS_GRID_HOLDER_TYPE -> return LocalStatisticStreamGridItemHolder(localItemBuilder, parent)
            STREAM_STATISTICS_CARD_HOLDER_TYPE -> return LocalStatisticStreamCardItemHolder(localItemBuilder, parent)
            else -> {
                Log.e(TAG, "No view type has been considered for holder: [$type]")
                return FallbackViewHolder(View(parent.context))
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        var position = position

        Logd(TAG, "onBindViewHolder() called with: "
                + "holder = [" + holder.javaClass.simpleName + "], "
                + "position = [" + position + "]")


        if (holder is LocalItemHolder) {
            // If header isn't null, offset the items by -1
            if (header != null) {
                position--
            }

            holder
                .updateFromItem(itemsList[position], recordManager, dateTimeFormatter)
        } else if (holder is HeaderFooterHolder && position == 0 && header != null) {
            holder.view = header!!
        } else if (holder is HeaderFooterHolder && position == sizeConsideringHeader() && footer != null && showFooter) {
            holder.view = footer!!
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int,
                                  payloads: List<Any>
    ) {
        if (!payloads.isEmpty() && holder is LocalItemHolder) {
            for (payload in payloads) {
                if (payload is StreamStateEntity) {
                    holder.updateState(itemsList[if (header == null) position else position - 1], recordManager)
                } else if (payload is Boolean) {
                    holder.updateState(itemsList[if (header == null) position else position - 1], recordManager)
                }
            }
        } else {
            onBindViewHolder(holder, position)
        }
    }

    fun getSpanSizeLookup(spanCount: Int): SpanSizeLookup {
        return object : SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val type = getItemViewType(position)
                return if (type == HEADER_TYPE || type == FOOTER_TYPE) spanCount else 1
            }
        }
    }

    companion object {
        private val TAG: String = LocalItemListAdapter::class.java.simpleName
        private const val DEBUG = false

        private const val HEADER_TYPE = 0
        private const val FOOTER_TYPE = 1

        private const val STREAM_STATISTICS_HOLDER_TYPE = 0x1000
        private const val STREAM_PLAYLIST_HOLDER_TYPE = 0x1001
        private const val STREAM_STATISTICS_GRID_HOLDER_TYPE = 0x1002
        private const val STREAM_STATISTICS_CARD_HOLDER_TYPE = 0x1003
        private const val STREAM_PLAYLIST_GRID_HOLDER_TYPE = 0x1004
        private const val STREAM_PLAYLIST_CARD_HOLDER_TYPE = 0x1005

        private const val LOCAL_PLAYLIST_HOLDER_TYPE = 0x2000
        private const val LOCAL_PLAYLIST_GRID_HOLDER_TYPE = 0x2001
        private const val LOCAL_PLAYLIST_CARD_HOLDER_TYPE = 0x2002

        private const val REMOTE_PLAYLIST_HOLDER_TYPE = 0x3000
        private const val REMOTE_PLAYLIST_GRID_HOLDER_TYPE = 0x3001
        private const val REMOTE_PLAYLIST_CARD_HOLDER_TYPE = 0x3002
    }
}
