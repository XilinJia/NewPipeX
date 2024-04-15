package org.schabi.newpipe.fragments.list.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ItemSearchSuggestionBinding
import org.schabi.newpipe.fragments.list.search.SuggestionListAdapter.SuggestionItemHolder

class SuggestionListAdapter : ListAdapter<SuggestionItem, SuggestionItemHolder>(SuggestionItemCallback()) {

    private var listener: OnSuggestionItemSelected? = null

    fun setListener(listener: OnSuggestionItemSelected?) {
        this.listener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) : SuggestionItemHolder {
        return SuggestionItemHolder(ItemSearchSuggestionBinding
            .inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: SuggestionItemHolder, position: Int) {
        val currentItem = getItem(position)
        holder.updateFrom(currentItem)
        holder.itemBinding.suggestionSearch.setOnClickListener { v: View? ->
            listener?.onSuggestionItemSelected(currentItem)
        }
        holder.itemBinding.suggestionSearch.setOnLongClickListener { v: View? ->
            listener?.onSuggestionItemLongClick(currentItem)
            true
        }
        holder.itemBinding.suggestionInsert.setOnClickListener { v: View? ->
            listener?.onSuggestionItemInserted(currentItem)
        }
    }

    interface OnSuggestionItemSelected {
        fun onSuggestionItemSelected(item: SuggestionItem?)

        fun onSuggestionItemInserted(item: SuggestionItem?)

        fun onSuggestionItemLongClick(item: SuggestionItem?)
    }

    class SuggestionItemHolder internal constructor(val itemBinding: ItemSearchSuggestionBinding) :
        RecyclerView.ViewHolder(itemBinding.root) {
        fun updateFrom(item: SuggestionItem?) {
            itemBinding.itemSuggestionIcon.setImageResource(if (item!!.fromHistory) R.drawable.ic_history
            else R.drawable.ic_search)
            itemBinding.itemSuggestionQuery.text = item.query
        }
    }

    private class SuggestionItemCallback : DiffUtil.ItemCallback<SuggestionItem>() {
        override fun areItemsTheSame(oldItem: SuggestionItem, newItem: SuggestionItem): Boolean {
            return oldItem.fromHistory == newItem.fromHistory && oldItem.query == newItem.query
        }

        override fun areContentsTheSame(oldItem: SuggestionItem, newItem: SuggestionItem): Boolean {
            return true // items' contents never change; the list of items themselves does
        }
    }
}
