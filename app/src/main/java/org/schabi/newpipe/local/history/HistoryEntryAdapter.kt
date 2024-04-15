package org.schabi.newpipe.local.history

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.util.Localization.getPreferredLocale
import org.schabi.newpipe.util.Localization.shortViewCount
import java.text.DateFormat
import java.util.*

/**
 * This is an adapter for history entries.
 *
 * @param <E>  the type of the entries
 * @param <VH> the type of the view holder
</VH></E> */
abstract class HistoryEntryAdapter<E, VH : RecyclerView.ViewHolder>
    (private val mContext: Context) : RecyclerView.Adapter<VH>() {

        private val mEntries = ArrayList<E>()
    private val mDateFormat: DateFormat =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM,
            getPreferredLocale(mContext))
    private var onHistoryItemClickListener: OnHistoryItemClickListener<E>? = null

    fun setEntries(historyEntries: Collection<E>) {
        mEntries.clear()
        mEntries.addAll(historyEntries)
        notifyDataSetChanged()
    }

    val items: Collection<E>
        get() = mEntries

    fun clear() {
        mEntries.clear()
        notifyDataSetChanged()
    }

    protected fun getFormattedDate(date: Date?): String {
        return mDateFormat.format(date)
    }

    protected fun getFormattedViewString(viewCount: Long): String {
        return shortViewCount(mContext, viewCount)
    }

    override fun getItemCount(): Int {
        return mEntries.size
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = mEntries[position]
        holder!!.itemView.setOnClickListener { v: View? ->
            if (onHistoryItemClickListener != null) {
                onHistoryItemClickListener!!.onHistoryItemClick(entry)
            }
        }

        holder.itemView.setOnLongClickListener { view: View? ->
            if (onHistoryItemClickListener != null) {
                onHistoryItemClickListener!!.onHistoryItemLongClick(entry)
                return@setOnLongClickListener true
            }
            false
        }

        onBindViewHolder(holder, entry, position)
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder!!.itemView.setOnClickListener(null)
    }

    abstract fun onBindViewHolder(holder: VH, entry: E, position: Int)

    fun setOnHistoryItemClickListener(
            onHistoryItemClickListener: OnHistoryItemClickListener<E>?
    ) {
        this.onHistoryItemClickListener = onHistoryItemClickListener
    }

    val isEmpty: Boolean
        get() = mEntries.isEmpty()

    interface OnHistoryItemClickListener<E> {
        fun onHistoryItemClick(item: E)

        fun onHistoryItemLongClick(item: E)
    }
}
