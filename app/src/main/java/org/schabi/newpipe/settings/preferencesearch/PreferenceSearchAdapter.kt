package org.schabi.newpipe.settings.preferencesearch

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.databinding.SettingsPreferencesearchListItemResultBinding
import java.util.function.Consumer

internal class PreferenceSearchAdapter

    : ListAdapter<PreferenceSearchItem?, PreferenceSearchAdapter.PreferenceViewHolder?>(PreferenceCallback()) {
    private var onItemClickListener: Consumer<PreferenceSearchItem?>? = null

    override fun onCreateViewHolder(parent: ViewGroup,
                                    viewType: Int
    ): PreferenceViewHolder {
        return PreferenceViewHolder(SettingsPreferencesearchListItemResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
        val item = getItem(position)

        holder.binding.title.text = item!!.title

        if (item.summary.isEmpty()) {
            holder.binding.summary.visibility = View.GONE
        } else {
            holder.binding.summary.visibility = View.VISIBLE
            holder.binding.summary.text = item.summary
        }

        if (item.breadcrumbs.isEmpty()) {
            holder.binding.breadcrumbs.visibility = View.GONE
        } else {
            holder.binding.breadcrumbs.visibility = View.VISIBLE
            holder.binding.breadcrumbs.text = item.breadcrumbs
        }

        holder.itemView.setOnClickListener { v: View? ->
            if (onItemClickListener != null) {
                onItemClickListener!!.accept(item)
            }
        }
    }

    fun setOnItemClickListener(onItemClickListener: Consumer<PreferenceSearchItem?>?) {
        this.onItemClickListener = onItemClickListener
    }

    internal class PreferenceViewHolder(val binding: SettingsPreferencesearchListItemResultBinding) :
        RecyclerView.ViewHolder(
            binding.root)

    private class PreferenceCallback : DiffUtil.ItemCallback<PreferenceSearchItem?>() {
        override fun areItemsTheSame(oldItem: PreferenceSearchItem,
                                     newItem: PreferenceSearchItem
        ): Boolean {
            return oldItem.key == newItem.key
        }

        override fun areContentsTheSame(oldItem: PreferenceSearchItem,
                                        newItem: PreferenceSearchItem
        ): Boolean {
            return oldItem.allRelevantSearchFields == newItem.allRelevantSearchFields
        }
    }
}
