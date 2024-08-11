package org.schabi.newpipe.ui.local

import android.content.Context
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R
import org.schabi.newpipe.database.LocalItem
import org.schabi.newpipe.database.LocalItem.LocalItemType
import org.schabi.newpipe.database.playlist.PlaylistDuplicatesEntry
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity
import org.schabi.newpipe.database.stream.StreamStatisticsEntry
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.ui.info_list.ItemViewMode
import org.schabi.newpipe.ui.ktx.animate
import org.schabi.newpipe.ui.local.history.HistoryRecordManager
import org.schabi.newpipe.util.DependentPreferenceHelper.getPositionsInListsEnabled
import org.schabi.newpipe.util.FallbackViewHolder
import org.schabi.newpipe.util.Localization.concatenateStrings
import org.schabi.newpipe.util.Localization.getDurationString
import org.schabi.newpipe.util.Localization.getPreferredLocale
import org.schabi.newpipe.util.Localization.localizeStreamCountMini
import org.schabi.newpipe.util.Localization.shortViewCount
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.util.OnClickGesture
import org.schabi.newpipe.util.ServiceHelper.getNameOfServiceById
import org.schabi.newpipe.util.image.PicassoHelper.loadPlaylistThumbnail
import org.schabi.newpipe.util.image.PicassoHelper.loadThumbnail
import org.schabi.newpipe.ui.views.AnimatedProgressBar
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.TimeUnit

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
    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(getPreferredLocale(context!!))

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
        if (itemsList.isEmpty()) return
        itemsList.clear()
        notifyDataSetChanged()
    }

    fun setItemViewMode(itemViewMode: ItemViewMode) {
        this.itemViewMode = itemViewMode
    }

    fun setHeader(header: View) {
        val changed = header !== this.header
        this.header = header
        if (changed) notifyDataSetChanged()
    }

    fun setFooter(view: View?) {
        this.footer = view
    }

    fun showFooter(show: Boolean) {
        Logd(TAG, "showFooter() called with: show = [$show]")
        if (show == showFooter) return
        showFooter = show
        if (show) notifyItemInserted(sizeConsideringHeader())
        else notifyItemRemoved(sizeConsideringHeader())
    }

    private fun adapterOffsetWithoutHeader(offset: Int): Int {
        return offset - (if (header != null) 1 else 0)
    }

    private fun sizeConsideringHeader(): Int {
        return itemsList.size + (if (header != null) 1 else 0)
    }

    override fun getItemCount(): Int {
        var count = itemsList.size
        if (header != null) count++
        if (footer != null && showFooter) count++
        Logd(TAG, "getItemCount() called, count = $count, localItems.size() = ${itemsList.size}, header = $header, footer = $footer, showFooter = $showFooter")
        return count
    }

    override fun getItemViewType(position: Int): Int {
        var position = position
        Logd(TAG, "getItemViewType() called with: position = [$position]")

        if (header != null && position == 0) return HEADER_TYPE
        else if (header != null) position--

        if (footer != null && position == itemsList.size && showFooter) return FOOTER_TYPE

        val item = itemsList[position]
        when (item.localItemType) {
            LocalItemType.PLAYLIST_LOCAL_ITEM -> return when (itemViewMode) {
                ItemViewMode.CARD -> LOCAL_PLAYLIST_CARD_HOLDER_TYPE
                ItemViewMode.GRID -> LOCAL_PLAYLIST_GRID_HOLDER_TYPE
                else -> LOCAL_PLAYLIST_HOLDER_TYPE
            }
            LocalItemType.PLAYLIST_REMOTE_ITEM -> return when (itemViewMode) {
                ItemViewMode.CARD -> REMOTE_PLAYLIST_CARD_HOLDER_TYPE
                ItemViewMode.GRID -> REMOTE_PLAYLIST_GRID_HOLDER_TYPE
                else -> REMOTE_PLAYLIST_HOLDER_TYPE
            }
            LocalItemType.PLAYLIST_STREAM_ITEM -> return when (itemViewMode) {
                ItemViewMode.CARD -> STREAM_PLAYLIST_CARD_HOLDER_TYPE
                ItemViewMode.GRID -> STREAM_PLAYLIST_GRID_HOLDER_TYPE
                else -> STREAM_PLAYLIST_HOLDER_TYPE
            }
            LocalItemType.STATISTIC_STREAM_ITEM -> return when (itemViewMode) {
                ItemViewMode.CARD -> STREAM_STATISTICS_CARD_HOLDER_TYPE
                ItemViewMode.GRID -> STREAM_STATISTICS_GRID_HOLDER_TYPE
                else -> STREAM_STATISTICS_HOLDER_TYPE
            }
            else -> {
                Log.e(TAG, "No holder type has been considered for item: [${item.localItemType}]")
                return -1
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
        Logd(TAG, "onCreateViewHolder() called with: parent = [$parent], type = [$type]")
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
        Logd(TAG, "onBindViewHolder() called with: holder = [${holder.javaClass.simpleName}], position = [$position]")
        when {
            holder is LocalItemHolder -> {
                // If header isn't null, offset the items by -1
                if (header != null) position--
                holder.updateFromItem(itemsList[position], recordManager, dateTimeFormatter)
            }
            holder is HeaderFooterHolder && position == 0 && header != null -> holder.view = header!!
            holder is HeaderFooterHolder && position == sizeConsideringHeader() && footer != null && showFooter -> holder.view = footer!!
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        if (!payloads.isEmpty() && holder is LocalItemHolder) {
            for (payload in payloads) {
                if (payload is StreamStateEntity) holder.updateState(itemsList[if (header == null) position else position - 1], recordManager)
                else if (payload is Boolean) holder.updateState(itemsList[if (header == null) position else position - 1], recordManager)
            }
        } else onBindViewHolder(holder, position)
    }

    fun getSpanSizeLookup(spanCount: Int): SpanSizeLookup {
        return object : SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val type = getItemViewType(position)
                return if (type == HEADER_TYPE || type == FOOTER_TYPE) spanCount else 1
            }
        }
    }

    class LocalItemBuilder(val context: Context) {
        var onItemSelectedListener: OnClickGesture<LocalItem>? = null
    }

    class HeaderFooterHolder(@JvmField var view: View) : RecyclerView.ViewHolder(view)

    abstract class LocalItemHolder(@JvmField protected val itemBuilder: LocalItemBuilder, layoutId: Int, parent: ViewGroup?)
        : RecyclerView.ViewHolder(LayoutInflater.from(itemBuilder.context).inflate(layoutId, parent, false)) {
        abstract fun updateFromItem(item: LocalItem?, historyRecordManager: HistoryRecordManager?, dateTimeFormatter: DateTimeFormatter?)
        open fun updateState(localItem: LocalItem?, historyRecordManager: HistoryRecordManager?) {}
    }

    open class RemotePlaylistItemHolder : PlaylistItemHolder {
        constructor(infoItemBuilder: LocalItemBuilder?, parent: ViewGroup?) : super(infoItemBuilder, parent)
        internal constructor(infoItemBuilder: LocalItemBuilder?, layoutId: Int, parent: ViewGroup?) : super(infoItemBuilder, layoutId, parent)

        override fun updateFromItem(localItem: LocalItem?, historyRecordManager: HistoryRecordManager?, dateTimeFormatter: DateTimeFormatter?) {
            if (localItem !is PlaylistRemoteEntity) return
            val item = localItem

            itemTitleView.text = item.name
            itemStreamCountView.text = localizeStreamCountMini(itemStreamCountView.context, item.streamCount)
            // Here is where the uploader name is set in the bookmarked playlists library
            if (!TextUtils.isEmpty(item.uploader)) itemUploaderView.text = concatenateStrings(item.uploader, getNameOfServiceById(item.serviceId))
            else itemUploaderView.text = getNameOfServiceById(item.serviceId)

            loadPlaylistThumbnail(item.thumbnailUrl).into(itemThumbnailView)
            super.updateFromItem(localItem, historyRecordManager, dateTimeFormatter)
        }
    }

    class RemotePlaylistGridItemHolder(infoItemBuilder: LocalItemBuilder?, parent: ViewGroup?)
        : RemotePlaylistItemHolder(infoItemBuilder, R.layout.list_playlist_grid_item, parent)

    class RemotePlaylistCardItemHolder(infoItemBuilder: LocalItemBuilder?, parent: ViewGroup?)
        : RemotePlaylistItemHolder(infoItemBuilder, R.layout.list_playlist_card_item, parent)

    open class LocalStatisticStreamItemHolder internal constructor(infoItemBuilder: LocalItemBuilder?, layoutId: Int, parent: ViewGroup?)
        : LocalItemHolder(infoItemBuilder!!, layoutId, parent) {

        val itemThumbnailView: ImageView = itemView.findViewById(R.id.itemThumbnailView)
        val itemVideoTitleView: TextView = itemView.findViewById(R.id.itemVideoTitleView)
        val itemUploaderView: TextView = itemView.findViewById(R.id.itemUploaderView)
        val itemDurationView: TextView = itemView.findViewById(R.id.itemDurationView)
        val itemAdditionalDetails: TextView? = itemView.findViewById(R.id.itemAdditionalDetails)
        private val itemProgressView: AnimatedProgressBar = itemView.findViewById(R.id.itemProgressView)

        constructor(itemBuilder: LocalItemBuilder?, parent: ViewGroup?) : this(itemBuilder, R.layout.list_stream_item, parent)

        private fun getStreamInfoDetailLine(entry: StreamStatisticsEntry, dateTimeFormatter: DateTimeFormatter?): String {
            return concatenateStrings( // watchCount
                shortViewCount(itemBuilder.context, entry.watchCount),
                dateTimeFormatter!!.format(entry.latestAccessDate),  // serviceName
                getNameOfServiceById(entry.streamEntity.serviceId))
        }

        override fun updateFromItem(localItem: LocalItem?, historyRecordManager: HistoryRecordManager?, dateTimeFormatter: DateTimeFormatter?) {
            if (localItem !is StreamStatisticsEntry) return
            val item = localItem

            itemVideoTitleView.text = item.streamEntity.title
            itemUploaderView.text = item.streamEntity.uploader

            if (item.streamEntity.duration > 0) {
                itemDurationView.setText(getDurationString(item.streamEntity.duration))
                itemDurationView.setBackgroundColor(ContextCompat.getColor(itemBuilder.context, R.color.duration_background_color))
                itemDurationView.visibility = View.VISIBLE

                if (getPositionsInListsEnabled(itemProgressView.context) && item.progressMillis > 0) {
                    itemProgressView.visibility = View.VISIBLE
                    itemProgressView.max = item.streamEntity.duration.toInt()
                    itemProgressView.progress = TimeUnit.MILLISECONDS.toSeconds(item.progressMillis).toInt()
                } else itemProgressView.visibility = View.GONE
            } else {
                itemDurationView.visibility = View.GONE
                itemProgressView.visibility = View.GONE
            }

            if (itemAdditionalDetails != null) itemAdditionalDetails.text = getStreamInfoDetailLine(item, dateTimeFormatter)

            // Default thumbnail is shown on error, while loading and if the url is empty
            loadThumbnail(item.streamEntity.thumbnailUrl).into(itemThumbnailView)

            itemView.setOnClickListener { view: View? ->
                itemBuilder.onItemSelectedListener?.selected(item)
            }

            itemView.isLongClickable = true
            itemView.setOnLongClickListener { view: View? ->
                itemBuilder.onItemSelectedListener?.held(item)
                true
            }
        }

        override fun updateState(localItem: LocalItem?, historyRecordManager: HistoryRecordManager?) {
            if (localItem !is StreamStatisticsEntry) return
            val item = localItem

            if (getPositionsInListsEnabled(itemProgressView.context) && item.progressMillis > 0 && item.streamEntity.duration > 0) {
                itemProgressView.max = item.streamEntity.duration.toInt()
                if (itemProgressView.visibility == View.VISIBLE)
                    itemProgressView.setProgressAnimated(TimeUnit.MILLISECONDS.toSeconds(item.progressMillis).toInt())
                else {
                    itemProgressView.progress = TimeUnit.MILLISECONDS.toSeconds(item.progressMillis).toInt()
                    itemProgressView.animate(true, 500)
                }
            } else if (itemProgressView.visibility == View.VISIBLE) itemProgressView.animate(false, 500)
        }
    }

    abstract class PlaylistItemHolder(infoItemBuilder: LocalItemBuilder?, layoutId: Int, parent: ViewGroup?)
        : LocalItemHolder(infoItemBuilder!!, layoutId, parent) {

        @JvmField
        val itemThumbnailView: ImageView = itemView.findViewById(R.id.itemThumbnailView)
        @JvmField
        val itemStreamCountView: TextView = itemView.findViewById(R.id.itemStreamCountView)
        @JvmField
        val itemTitleView: TextView = itemView.findViewById(R.id.itemTitleView)
        @JvmField
        val itemUploaderView: TextView = itemView.findViewById(R.id.itemUploaderView)

        constructor(infoItemBuilder: LocalItemBuilder?, parent: ViewGroup?) : this(infoItemBuilder, R.layout.list_playlist_mini_item, parent)

        override fun updateFromItem(localItem: LocalItem?, historyRecordManager: HistoryRecordManager?, dateTimeFormatter: DateTimeFormatter?) {
            itemView.setOnClickListener { view: View? ->
                if (localItem != null) itemBuilder.onItemSelectedListener?.selected(localItem)
            }
            itemView.isLongClickable = true
            itemView.setOnLongClickListener { view: View? ->
                if (localItem != null) itemBuilder.onItemSelectedListener?.held(localItem)
                true
            }
        }
    }

    open class LocalPlaylistStreamItemHolder internal constructor(infoItemBuilder: LocalItemBuilder?, layoutId: Int, parent: ViewGroup?)
        : LocalItemHolder(infoItemBuilder!!, layoutId, parent) {

        val itemThumbnailView: ImageView = itemView.findViewById(R.id.itemThumbnailView)
        val itemVideoTitleView: TextView = itemView.findViewById(R.id.itemVideoTitleView)
        private val itemAdditionalDetailsView: TextView = itemView.findViewById(R.id.itemAdditionalDetails)
        val itemDurationView: TextView = itemView.findViewById(R.id.itemDurationView)
        private val itemHandleView: View = itemView.findViewById(R.id.itemHandle)
        private val itemProgressView: AnimatedProgressBar = itemView.findViewById(R.id.itemProgressView)

        constructor(infoItemBuilder: LocalItemBuilder?, parent: ViewGroup?) : this(infoItemBuilder, R.layout.list_stream_playlist_item, parent)

        override fun updateFromItem(localItem: LocalItem?, historyRecordManager: HistoryRecordManager?, dateTimeFormatter: DateTimeFormatter?) {
            if (localItem !is PlaylistStreamEntry) return
            val item = localItem

            itemVideoTitleView.text = item.streamEntity.title
            itemAdditionalDetailsView.text = concatenateStrings(item.streamEntity.uploader, getNameOfServiceById(item.streamEntity.serviceId))

            if (item.streamEntity.duration > 0) {
                itemDurationView.text = getDurationString(item.streamEntity.duration)
                itemDurationView.setBackgroundColor(ContextCompat.getColor(itemBuilder.context, R.color.duration_background_color))
                itemDurationView.visibility = View.VISIBLE

                if (getPositionsInListsEnabled(itemProgressView.context) && item.progressMillis > 0) {
                    itemProgressView.visibility = View.VISIBLE
                    itemProgressView.max = item.streamEntity.duration.toInt()
                    itemProgressView.progress = TimeUnit.MILLISECONDS.toSeconds(item.progressMillis).toInt()
                } else itemProgressView.visibility = View.GONE
            } else itemDurationView.visibility = View.GONE

            // Default thumbnail is shown on error, while loading and if the url is empty
            loadThumbnail(item.streamEntity.thumbnailUrl).into(itemThumbnailView)

            itemView.setOnClickListener { view: View? ->
                itemBuilder.onItemSelectedListener?.selected(item)
            }
            itemView.isLongClickable = true
            itemView.setOnLongClickListener { view: View? ->
                itemBuilder.onItemSelectedListener?.held(item)
                true
            }
            itemHandleView.setOnTouchListener(getOnTouchListener(item))
        }

        override fun updateState(localItem: LocalItem?, historyRecordManager: HistoryRecordManager?) {
            if (localItem !is PlaylistStreamEntry) return
            val item = localItem

            if (getPositionsInListsEnabled(itemProgressView.context) && item.progressMillis > 0 && item.streamEntity.duration > 0) {
                itemProgressView.max = item.streamEntity.duration.toInt()
                if (itemProgressView.visibility == View.VISIBLE)
                    itemProgressView.setProgressAnimated(TimeUnit.MILLISECONDS.toSeconds(item.progressMillis).toInt())
                else {
                    itemProgressView.progress = TimeUnit.MILLISECONDS.toSeconds(item.progressMillis).toInt()
                    itemProgressView.animate(true, 500)
                }
            } else if (itemProgressView.visibility == View.VISIBLE) itemProgressView.animate(false, 500)
        }

        private fun getOnTouchListener(item: PlaylistStreamEntry): OnTouchListener {
            return OnTouchListener { view: View, motionEvent: MotionEvent ->
                view.performClick()
                if (motionEvent.actionMasked == MotionEvent.ACTION_DOWN)
                    itemBuilder?.onItemSelectedListener?.drag(item, this@LocalPlaylistStreamItemHolder)
                false
            }
        }
    }

    class LocalStatisticStreamGridItemHolder(infoItemBuilder: LocalItemBuilder?, parent: ViewGroup?)
        : LocalStatisticStreamItemHolder(infoItemBuilder, R.layout.list_stream_grid_item, parent)

    class LocalStatisticStreamCardItemHolder(infoItemBuilder: LocalItemBuilder?, parent: ViewGroup?)
        : LocalStatisticStreamItemHolder(infoItemBuilder, R.layout.list_stream_card_item, parent)

    class LocalPlaylistStreamGridItemHolder(infoItemBuilder: LocalItemBuilder?, parent: ViewGroup?)
        : LocalPlaylistStreamItemHolder(infoItemBuilder, R.layout.list_stream_playlist_grid_item, parent)

    class LocalPlaylistStreamCardItemHolder(infoItemBuilder: LocalItemBuilder?, parent: ViewGroup?)
        : LocalPlaylistStreamItemHolder(infoItemBuilder, R.layout.list_stream_playlist_card_item, parent)

    open class LocalPlaylistItemHolder : PlaylistItemHolder {
        constructor(infoItemBuilder: LocalItemBuilder?, parent: ViewGroup?) : super(infoItemBuilder, parent)

        internal constructor(infoItemBuilder: LocalItemBuilder?, layoutId: Int, parent: ViewGroup?) : super(infoItemBuilder, layoutId, parent)

        override fun updateFromItem(localItem: LocalItem?, historyRecordManager: HistoryRecordManager?, dateTimeFormatter: DateTimeFormatter?) {
            if (localItem !is PlaylistMetadataEntry) return

            val item = localItem

            itemTitleView.text = item.name
            itemStreamCountView.text = localizeStreamCountMini(itemStreamCountView.context, item.streamCount)
            itemUploaderView.visibility = View.INVISIBLE

            loadPlaylistThumbnail(item.thumbnailUrl).into(itemThumbnailView)
            if (item is PlaylistDuplicatesEntry && item.timesStreamIsContained > 0) itemView.alpha = GRAYED_OUT_ALPHA
            else itemView.alpha = 1.0f
            super.updateFromItem(localItem, historyRecordManager, dateTimeFormatter)
        }

        companion object {
            private const val GRAYED_OUT_ALPHA = 0.6f
        }
    }

    class LocalPlaylistCardItemHolder(infoItemBuilder: LocalItemBuilder?, parent: ViewGroup?)
        : LocalPlaylistItemHolder(infoItemBuilder, R.layout.list_playlist_card_item, parent)

    class LocalPlaylistGridItemHolder(infoItemBuilder: LocalItemBuilder?, parent: ViewGroup?)
        : LocalPlaylistItemHolder(infoItemBuilder, R.layout.list_playlist_grid_item, parent)


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
