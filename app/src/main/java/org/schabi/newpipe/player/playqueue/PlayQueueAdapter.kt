package org.schabi.newpipe.player.playqueue

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.core.Observer
import io.reactivex.rxjava3.disposables.Disposable
import org.schabi.newpipe.R
import org.schabi.newpipe.player.playqueue.events.*
import org.schabi.newpipe.util.FallbackViewHolder

/**
 * Created by Christian Schabesberger on 01.08.16.
 *
 *
 * Copyright (C) Christian Schabesberger 2016 <chris.schabesberger></chris.schabesberger>@mailbox.org>
 * InfoListAdapter.java is part of NewPipe.
 *
 *
 *
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *
 *
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *
 *
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http:></http:>//www.gnu.org/licenses/>.
 *
 */
class PlayQueueAdapter(context: Context?, playQueue: PlayQueue) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val playQueueItemBuilder: PlayQueueItemBuilder
    private val playQueue: PlayQueue
    private var showFooter = false
    private var footer: View? = null

    private var playQueueReactor: Disposable? = null

    init {
        checkNotNull(playQueue.broadcastReceiver) { "Play Queue has not been initialized." }

        this.playQueueItemBuilder = PlayQueueItemBuilder(context)
        this.playQueue = playQueue

        playQueue.broadcastReceiver!!.toObservable().subscribe(reactor)
    }

    private val reactor: Observer<PlayQueueEvent>
        get() = object : Observer<PlayQueueEvent> {
            override fun onSubscribe(d: Disposable) {
                if (playQueueReactor != null) {
                    playQueueReactor!!.dispose()
                }
                playQueueReactor = d
            }

            override fun onNext(playQueueMessage: PlayQueueEvent) {
                if (playQueueReactor != null) {
                    onPlayQueueChanged(playQueueMessage)
                }
            }

            override fun onError(e: Throwable) {}

            override fun onComplete() {
                dispose()
            }
        }

    private fun onPlayQueueChanged(message: PlayQueueEvent) {
        when (message.type()) {
            PlayQueueEventType.RECOVERY -> {}
            PlayQueueEventType.SELECT -> {
                val selectEvent = message as SelectEvent
                notifyItemChanged(selectEvent.oldIndex)
                notifyItemChanged(selectEvent.newIndex)
            }
            PlayQueueEventType.APPEND -> {
                val appendEvent = message as AppendEvent
                notifyItemRangeInserted(playQueue.size(), appendEvent.amount)
            }
            PlayQueueEventType.ERROR -> {
                val errorEvent = message as ErrorEvent
                notifyItemChanged(errorEvent.errorIndex)
                notifyItemChanged(errorEvent.queueIndex)
            }
            PlayQueueEventType.REMOVE -> {
                val removeEvent = message as RemoveEvent
                notifyItemRemoved(removeEvent.removeIndex)
                notifyItemChanged(removeEvent.queueIndex)
            }
            PlayQueueEventType.MOVE -> {
                val moveEvent = message as MoveEvent
                notifyItemMoved(moveEvent.fromIndex, moveEvent.toIndex)
            }
            PlayQueueEventType.INIT, PlayQueueEventType.REORDER -> notifyDataSetChanged()
            else -> notifyDataSetChanged()
        }
    }

    fun dispose() {
        if (playQueueReactor != null) {
            playQueueReactor!!.dispose()
        }
        playQueueReactor = null
    }

    fun setSelectedListener(listener: PlayQueueItemBuilder.OnSelectedListener?) {
        playQueueItemBuilder.setOnSelectedListener(listener)
    }

    fun unsetSelectedListener() {
        playQueueItemBuilder.setOnSelectedListener(null)
    }

    fun setFooter(footer: View?) {
        this.footer = footer
        notifyItemChanged(playQueue.size())
    }

    fun showFooter(show: Boolean) {
        showFooter = show
        notifyItemChanged(playQueue.size())
    }

    val items: List<PlayQueueItem?>
        get() = playQueue.getStreams()

    override fun getItemCount(): Int {
        var count = playQueue.getStreams().size
        if (footer != null && showFooter) {
            count++
        }
        return count
    }

    override fun getItemViewType(position: Int): Int {
        if (footer != null && position == playQueue.getStreams().size && showFooter) {
            return FOOTER_VIEW_TYPE_ID
        }

        return ITEM_VIEW_TYPE_ID
    }

    override fun onCreateViewHolder(parent: ViewGroup,
                                    type: Int
    ): RecyclerView.ViewHolder {
        when (type) {
            FOOTER_VIEW_TYPE_ID -> return HFHolder(footer)
            ITEM_VIEW_TYPE_ID -> return PlayQueueItemHolder(LayoutInflater.from(parent.context)
                .inflate(R.layout.play_queue_item, parent, false))
            else -> {
                Log.e(TAG, "Attempting to create view holder with undefined type: $type")
                return FallbackViewHolder(View(parent.context))
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder,
                                  position: Int
    ) {
        if (holder is PlayQueueItemHolder) {
            val itemHolder = holder

            val item = playQueue.getStreams()[position]
            // Build the list item
            if (item != null) playQueueItemBuilder.buildStreamInfoItem(itemHolder, item)

            // Check if the current item should be selected/highlighted
            val isSelected = playQueue.index == position
            itemHolder.itemView.isSelected = isSelected
        } else if (holder is HFHolder && position == playQueue.getStreams().size && footer != null && showFooter) {
            holder.view = footer
        }
    }

    class HFHolder(var view: View?) : RecyclerView.ViewHolder(view!!)
    companion object {
        private val TAG = PlayQueueAdapter::class.java.toString()

        private const val ITEM_VIEW_TYPE_ID = 0
        private const val FOOTER_VIEW_TYPE_ID = 1
    }
}
