package org.schabi.newpipe.fragments.list.videos

import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.CompoundButton
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.core.Single
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.RelatedItemsHeaderBinding
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.fragments.list.BaseListInfoFragment
import org.schabi.newpipe.info_list.ItemViewMode
import org.schabi.newpipe.ktx.slideUp
import java.util.function.Supplier

class RelatedItemsFragment : BaseListInfoFragment<InfoItem, RelatedItemsInfo>(UserAction.REQUESTED_STREAM), OnSharedPreferenceChangeListener {
    private var relatedItemsInfo: RelatedItemsInfo? = null

    /*//////////////////////////////////////////////////////////////////////////
    // Views
    ////////////////////////////////////////////////////////////////////////// */
    private var headerBinding: RelatedItemsHeaderBinding? = null

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreateView(inflater: LayoutInflater,
                              container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        Log.d(TAG, "onCreateView")
        return inflater.inflate(R.layout.fragment_related_items, container, false)
    }

    override fun onDestroyView() {
        headerBinding = null
        super.onDestroyView()
    }

    override val listHeaderSupplier: Supplier<View>?
        get() {
            if (relatedItemsInfo == null || relatedItemsInfo!!.relatedItems == null) return null

            headerBinding = RelatedItemsHeaderBinding.inflate(requireActivity().layoutInflater, itemsList, false)

            val pref = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val autoplay = pref.getBoolean(getString(R.string.auto_queue_key), false)
            headerBinding!!.autoplaySwitch.isChecked = autoplay
            headerBinding!!.autoplaySwitch.setOnCheckedChangeListener { compoundButton: CompoundButton?, b: Boolean ->
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putBoolean(getString(R.string.auto_queue_key), b).apply()
            }

            return Supplier { headerBinding!!.root }
        }

    override fun loadMoreItemsLogic(): Single<InfoItemsPage<InfoItem>> {
        return Single.fromCallable { InfoItemsPage.emptyPage() }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    ////////////////////////////////////////////////////////////////////////// */
    override fun loadResult(forceLoad: Boolean): Single<RelatedItemsInfo> {
        return Single.fromCallable({ relatedItemsInfo!! })
    }

    override fun showLoading() {
        super.showLoading()
        headerBinding?.root?.visibility = View.INVISIBLE
    }

    override fun handleResult(result: RelatedItemsInfo) {
        super.handleResult(result)
        headerBinding?.root?.visibility = View.VISIBLE
        requireView().slideUp(120, 96, 0.06f)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    ////////////////////////////////////////////////////////////////////////// */
    override fun setTitle(title: String) {
        // Nothing to do - override parent
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        // Nothing to do - override parent
    }

    private fun setInitialData(info: StreamInfo) {
        super.setInitialData(info.serviceId, info.url, info.name)
        if (this.relatedItemsInfo == null) {
            this.relatedItemsInfo = RelatedItemsInfo(info)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(INFO_KEY, relatedItemsInfo)
    }

    override fun onRestoreInstanceState(savedState: Bundle) {
        super.onRestoreInstanceState(savedState)
        val serializable = savedState.getSerializable(INFO_KEY)
        if (serializable is RelatedItemsInfo) {
            this.relatedItemsInfo = serializable
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (headerBinding != null && getString(R.string.auto_queue_key) == key) {
            headerBinding!!.autoplaySwitch.isChecked = sharedPreferences.getBoolean(key, false)
        }
    }

    override val itemViewMode: ItemViewMode
        get() {
            var mode = super.itemViewMode
            // Only list mode is supported. Either List or card will be used.
            if (mode != ItemViewMode.LIST && mode != ItemViewMode.CARD) {
                mode = ItemViewMode.LIST
            }
            return mode
        }

    companion object {
        private const val INFO_KEY = "related_info_key"

        fun getInstance(info: StreamInfo): RelatedItemsFragment {
            val instance = RelatedItemsFragment()
            instance.setInitialData(info)
            return instance
        }
    }
}
