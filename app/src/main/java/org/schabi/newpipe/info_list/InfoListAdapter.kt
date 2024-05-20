package org.schabi.newpipe.info_list

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.databinding.PignateFooterBinding
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.InfoItem.InfoType
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.info_list.holder.*
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.util.FallbackViewHolder
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.util.OnClickGesture
import java.util.function.Supplier

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
class InfoListAdapter(context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val layoutInflater: LayoutInflater = LayoutInflater.from(context)
    private val infoItemBuilder = InfoItemBuilder(context)
    private val infoItemList: MutableList<InfoItem> = ArrayList()
    private val recordManager = HistoryRecordManager(context)

    private var useMiniVariant = false
    private var showFooter = false

    private var itemMode = ItemViewMode.LIST

    private var headerSupplier: Supplier<View>? = null

    fun setOnStreamSelectedListener(listener: OnClickGesture<StreamInfoItem>?) {
        infoItemBuilder.onStreamSelectedListener = listener
    }

    fun setOnChannelSelectedListener(listener: OnClickGesture<ChannelInfoItem>?) {
        infoItemBuilder.onChannelSelectedListener = listener
    }

    fun setOnPlaylistSelectedListener(listener: OnClickGesture<PlaylistInfoItem>?) {
        infoItemBuilder.onPlaylistSelectedListener = listener
    }

    fun setOnCommentsSelectedListener(listener: OnClickGesture<CommentsInfoItem>?) {
        infoItemBuilder.onCommentsSelectedListener = listener
    }

    fun setUseMiniVariant(useMiniVariant: Boolean) {
        this.useMiniVariant = useMiniVariant
    }

    fun setItemViewMode(itemViewMode: ItemViewMode) {
        this.itemMode = itemViewMode
    }

    fun addInfoItemList(data: List<InfoItem>?) {
        if (data == null) return

        Logd(TAG, "addInfoItemList() before > infoItemList.size() = ${infoItemList.size}, data.size() = ${data.size}")

        val offsetStart = sizeConsideringHeaderOffset()
        infoItemList.addAll(data)

        Logd(TAG, "addInfoItemList() after > offsetStart = $offsetStart, infoItemList.size() = ${infoItemList.size}, hasHeader = ${hasHeader()}, showFooter = $showFooter")

        notifyItemRangeInserted(offsetStart, data.size)

        if (showFooter) {
            val footerNow = sizeConsideringHeaderOffset()
            notifyItemMoved(offsetStart, footerNow)
            Logd(TAG, "addInfoItemList() footer from $offsetStart to $footerNow")
        }
    }

    fun clearStreamItemList() {
        if (infoItemList.isEmpty()) return

        infoItemList.clear()
        notifyDataSetChanged()
    }

    fun setHeaderSupplier(headerSupplier: Supplier<View>?) {
        val changed = headerSupplier !== this.headerSupplier
        this.headerSupplier = headerSupplier
        if (changed) {
            notifyDataSetChanged()
        }
    }

    protected fun hasHeader(): Boolean {
        return this.headerSupplier != null
    }

    fun showFooter(show: Boolean) {

        Logd(TAG, "showFooter() called with: show = [$show]")

        if (show == showFooter) return

        showFooter = show
        if (show) {
            notifyItemInserted(sizeConsideringHeaderOffset())
        } else {
            notifyItemRemoved(sizeConsideringHeaderOffset())
        }
    }

    private fun sizeConsideringHeaderOffset(): Int {
        val i = infoItemList.size + (if (hasHeader()) 1 else 0)

        Logd(TAG, "sizeConsideringHeaderOffset() called → $i")

        return i
    }

    val itemsList: MutableList<InfoItem>
        get() = infoItemList

    override fun getItemCount(): Int {
        var count = infoItemList.size
        if (hasHeader()) {
            count++
        }
        if (showFooter) {
            count++
        }

        Logd(TAG,
            "getItemCount() called with: count = $count, infoItemList.size() = ${infoItemList.size}, hasHeader = ${hasHeader()}, showFooter = $showFooter")

        return count
    }

    override fun getItemViewType(position: Int): Int {
        var position = position

        Logd(TAG, "getItemViewType() called with: position = [$position]")


        when {
            hasHeader() && position == 0 -> {
                return HEADER_TYPE
            }
            hasHeader() -> {
                position--
            }
        }
        if (position == infoItemList.size && showFooter) {
            return FOOTER_TYPE
        }
        val item = infoItemList[position]
        return when (item.infoType) {
            InfoType.STREAM -> {
                when {
                    itemMode == ItemViewMode.CARD -> {
                        CARD_STREAM_HOLDER_TYPE
                    }
                    itemMode == ItemViewMode.GRID -> {
                        GRID_STREAM_HOLDER_TYPE
                    }
                    useMiniVariant -> {
                        MINI_STREAM_HOLDER_TYPE
                    }
                    else -> {
                        STREAM_HOLDER_TYPE
                    }
                }
            }
            InfoType.CHANNEL -> {
                when {
                    itemMode == ItemViewMode.CARD -> {
                        CARD_CHANNEL_HOLDER_TYPE
                    }
                    itemMode == ItemViewMode.GRID -> {
                        GRID_CHANNEL_HOLDER_TYPE
                    }
                    useMiniVariant -> {
                        MINI_CHANNEL_HOLDER_TYPE
                    }
                    else -> {
                        CHANNEL_HOLDER_TYPE
                    }
                }
            }
            InfoType.PLAYLIST -> {
                when {
                    itemMode == ItemViewMode.CARD -> {
                        CARD_PLAYLIST_HOLDER_TYPE
                    }
                    itemMode == ItemViewMode.GRID -> {
                        GRID_PLAYLIST_HOLDER_TYPE
                    }
                    useMiniVariant -> {
                        MINI_PLAYLIST_HOLDER_TYPE
                    }
                    else -> {
                        PLAYLIST_HOLDER_TYPE
                    }
                }
            }
            InfoType.COMMENT -> COMMENT_HOLDER_TYPE
            else -> -1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {

        Logd(TAG, "onCreateViewHolder() called with: "
                + "parent = [" + parent + "], type = [" + type + "]")

        return when (type) {
            HEADER_TYPE -> HFHolder(headerSupplier!!.get())
            FOOTER_TYPE -> HFHolder(PignateFooterBinding.inflate(layoutInflater, parent, false).root)
            MINI_STREAM_HOLDER_TYPE -> StreamMiniInfoItemHolder(infoItemBuilder, parent)
            STREAM_HOLDER_TYPE -> StreamInfoItemHolder(infoItemBuilder, parent)
            GRID_STREAM_HOLDER_TYPE -> StreamGridInfoItemHolder(infoItemBuilder, parent)
            CARD_STREAM_HOLDER_TYPE -> StreamCardInfoItemHolder(infoItemBuilder, parent)
            MINI_CHANNEL_HOLDER_TYPE -> ChannelMiniInfoItemHolder(infoItemBuilder, parent)
            CHANNEL_HOLDER_TYPE -> ChannelInfoItemHolder(infoItemBuilder, parent)
            CARD_CHANNEL_HOLDER_TYPE -> ChannelCardInfoItemHolder(infoItemBuilder, parent)
            GRID_CHANNEL_HOLDER_TYPE -> ChannelGridInfoItemHolder(infoItemBuilder, parent)
            MINI_PLAYLIST_HOLDER_TYPE -> PlaylistMiniInfoItemHolder(infoItemBuilder, parent)
            PLAYLIST_HOLDER_TYPE -> PlaylistInfoItemHolder(infoItemBuilder, parent)
            GRID_PLAYLIST_HOLDER_TYPE -> PlaylistGridInfoItemHolder(infoItemBuilder, parent)
            CARD_PLAYLIST_HOLDER_TYPE -> PlaylistCardInfoItemHolder(infoItemBuilder, parent)
            COMMENT_HOLDER_TYPE -> CommentInfoItemHolder(infoItemBuilder, parent)
            else -> FallbackViewHolder(View(parent.context))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {

        Logd(TAG, "onBindViewHolder() called with: "
                + "holder = [" + holder.javaClass.simpleName + "], "
                + "position = [" + position + "]")

        if (holder is InfoItemHolder) {
            holder.updateFromItem( // If header is present, offset the items by -1
                infoItemList[if (hasHeader()) position - 1 else position], recordManager)
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

    internal class HFHolder(v: View) : RecyclerView.ViewHolder(v)
    companion object {
        private val TAG: String = InfoListAdapter::class.java.simpleName
        private const val DEBUG = false

        private const val HEADER_TYPE = 0
        private const val FOOTER_TYPE = 1

        private const val MINI_STREAM_HOLDER_TYPE = 0x100
        private const val STREAM_HOLDER_TYPE = 0x101
        private const val GRID_STREAM_HOLDER_TYPE = 0x102
        private const val CARD_STREAM_HOLDER_TYPE = 0x103
        private const val MINI_CHANNEL_HOLDER_TYPE = 0x200
        private const val CHANNEL_HOLDER_TYPE = 0x201
        private const val GRID_CHANNEL_HOLDER_TYPE = 0x202
        private const val CARD_CHANNEL_HOLDER_TYPE = 0x203
        private const val MINI_PLAYLIST_HOLDER_TYPE = 0x300
        private const val PLAYLIST_HOLDER_TYPE = 0x301
        private const val GRID_PLAYLIST_HOLDER_TYPE = 0x302
        private const val CARD_PLAYLIST_HOLDER_TYPE = 0x303
        private const val COMMENT_HOLDER_TYPE = 0x400
    }
}
