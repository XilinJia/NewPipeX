package org.schabi.newpipe.settings.preferencesearch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import org.schabi.newpipe.databinding.SettingsPreferencesearchFragmentBinding

/**
 * Displays the search results.
 */
class PreferenceSearchFragment : Fragment() {
    private var searcher: PreferenceSearcher? = null

    private var binding: SettingsPreferencesearchFragmentBinding? = null
    private var adapter: PreferenceSearchAdapter? = null

    fun setSearcher(searcher: PreferenceSearcher?) {
        this.searcher = searcher
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        binding = SettingsPreferencesearchFragmentBinding.inflate(inflater, container, false)

        binding!!.searchResults.layoutManager = LinearLayoutManager(context)

        adapter = PreferenceSearchAdapter()
        adapter!!.setOnItemClickListener { item: PreferenceSearchItem? -> this.onItemClicked(item) }
        binding!!.searchResults.adapter = adapter

        return binding!!.root
    }

    fun updateSearchResults(keyword: String) {
        if (adapter == null || searcher == null) return

        val results = searcher!!.searchFor(keyword)
        adapter!!.submitList(results)
        setEmptyViewShown(results.isEmpty())
    }

    private fun setEmptyViewShown(shown: Boolean) {
        binding!!.emptyStateView.visibility = if (shown) View.VISIBLE else View.GONE
        binding!!.searchResults.visibility = if (shown) View.GONE else View.VISIBLE
    }

    fun onItemClicked(item: PreferenceSearchItem?) {
        if (activity !is PreferenceSearchResultListener) {
            throw ClassCastException(
                activity.toString() + " must implement SearchPreferenceResultListener")
        }

        (activity as PreferenceSearchResultListener?)!!.onSearchResultClicked(item!!)
    }

    companion object {
        val NAME: String = PreferenceSearchFragment::class.java.simpleName
    }
}
