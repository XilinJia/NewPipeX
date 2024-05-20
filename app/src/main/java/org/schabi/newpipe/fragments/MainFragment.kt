package org.schabi.newpipe.fragments

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.RelativeLayout
import androidx.annotation.ColorInt
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapterMenuWorkaround
import androidx.media3.common.util.UnstableApi
import androidx.preference.PreferenceManager
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayout.OnTabSelectedListener
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentMainBinding
import org.schabi.newpipe.error.ErrorUtil.Companion.showUiErrorSnackbar
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.local.playlist.LocalPlaylistFragment
import org.schabi.newpipe.settings.tabs.Tab
import org.schabi.newpipe.settings.tabs.TabsManager
import org.schabi.newpipe.settings.tabs.TabsManager.Companion.getManager
import org.schabi.newpipe.settings.tabs.TabsManager.SavedTabsChangeListener
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.util.NavigationHelper.openSearchFragment
import org.schabi.newpipe.util.ServiceHelper.getSelectedServiceId
import org.schabi.newpipe.util.ThemeHelper.resolveColorFromAttr

class MainFragment : BaseFragment(), OnTabSelectedListener {
    private var binding: FragmentMainBinding? = null
    private var pagerAdapter: SelectedTabsPagerAdapter? = null

    private val tabsList: MutableList<Tab> = ArrayList()
    private var tabsManager: TabsManager? = null

    private var hasTabsChanged = false

    private var prefs: SharedPreferences? = null
    private var youtubeRestrictedModeEnabled = false
    private var youtubeRestrictedModeEnabledKey: String? = null
    private var mainTabsPositionBottom = false
    private var mainTabsPositionKey: String? = null

    /*//////////////////////////////////////////////////////////////////////////
    // Fragment's LifeCycle
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        tabsManager = getManager(requireActivity())
        tabsManager!!.setSavedTabsListener {
            Logd(TAG, "TabsManager.SavedTabsChangeListener: onTabsChanged called, isResumed = $isResumed")
            if (isResumed) setupTabs()
            else hasTabsChanged = true
        }

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        youtubeRestrictedModeEnabledKey = getString(R.string.youtube_restricted_mode_enabled)
        if (prefs!= null) youtubeRestrictedModeEnabled = prefs!!.getBoolean(youtubeRestrictedModeEnabledKey, false)
        mainTabsPositionKey = getString(R.string.main_tabs_position_key)
        if (prefs!= null) mainTabsPositionBottom = prefs!!.getBoolean(mainTabsPositionKey, false)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Logd(TAG, "onCreateView")
        return inflater.inflate(R.layout.fragment_main, container, false)
    }

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)

        binding = FragmentMainBinding.bind(rootView!!)

        binding!!.mainTabLayout.setupWithViewPager(binding!!.pager)
        binding!!.mainTabLayout.addOnTabSelectedListener(this)

        setupTabs()
        updateTabLayoutPosition()
    }

    override fun onResume() {
        super.onResume()

        val newYoutubeRestrictedModeEnabled = prefs!!.getBoolean(youtubeRestrictedModeEnabledKey, false)
        if (youtubeRestrictedModeEnabled != newYoutubeRestrictedModeEnabled || hasTabsChanged) {
            youtubeRestrictedModeEnabled = newYoutubeRestrictedModeEnabled
            setupTabs()
        }

        val newMainTabsPosition = prefs!!.getBoolean(mainTabsPositionKey, false)
        if (mainTabsPositionBottom != newMainTabsPosition) {
            mainTabsPositionBottom = newMainTabsPosition
            updateTabLayoutPosition()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tabsManager!!.unsetSavedTabsListener()
        if (binding != null) {
            binding!!.pager.adapter = null
            binding = null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Menu
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        Logd(TAG, "onCreateOptionsMenu() called with: menu = [$menu], inflater = [$inflater]")
        inflater.inflate(R.menu.menu_main_fragment, menu)

        val supportActionBar = activity?.supportActionBar
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
    }

    @OptIn(UnstableApi::class) override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_search) {
            try {
                openSearchFragment(fM!!, getSelectedServiceId(requireActivity()), "")
            } catch (e: Exception) {
                showUiErrorSnackbar(this, "Opening search fragment", e)
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Tabs
    ////////////////////////////////////////////////////////////////////////// */
    private fun setupTabs() {
        tabsList.clear()
        tabsList.addAll(tabsManager!!.tabs)

        if (pagerAdapter == null || !pagerAdapter!!.sameTabs(tabsList))
            pagerAdapter = SelectedTabsPagerAdapter(requireContext(), childFragmentManager, tabsList)

        binding!!.pager.adapter = null
        binding!!.pager.adapter = pagerAdapter

        updateTabsIconAndDescription()
        updateTitleForTab(binding!!.pager.currentItem)

        hasTabsChanged = false
    }

    private fun updateTabsIconAndDescription() {
        for (i in tabsList.indices) {
            val tabToSet = binding!!.mainTabLayout.getTabAt(i)
            if (tabToSet != null) {
                val tab = tabsList[i]
                tabToSet.setIcon(tab.getTabIconRes(requireContext()))
                tabToSet.setContentDescription(tab.getTabName(requireContext()))
            }
        }
    }

    private fun updateTitleForTab(tabPosition: Int) {
        setTitle(tabsList[tabPosition].getTabName(requireContext())!!)
    }

    fun commitPlaylistTabs() {
        pagerAdapter!!.getLocalPlaylistFragments()
            .stream()
            .forEach { obj: LocalPlaylistFragment -> obj.commitChanges() }
    }

    private fun updateTabLayoutPosition() {
        val tabLayout = binding!!.mainTabLayout
        val viewPager = binding!!.pager
        val bottom = mainTabsPositionBottom

        // change layout params to make the tab layout appear either at the top or at the bottom
        val tabParams = tabLayout.layoutParams as RelativeLayout.LayoutParams
        val pagerParams = viewPager.layoutParams as RelativeLayout.LayoutParams

        tabParams.removeRule(if (bottom) RelativeLayout.ALIGN_PARENT_TOP else RelativeLayout.ALIGN_PARENT_BOTTOM)
        tabParams.addRule(if (bottom) RelativeLayout.ALIGN_PARENT_BOTTOM else RelativeLayout.ALIGN_PARENT_TOP)
        pagerParams.removeRule(if (bottom) RelativeLayout.BELOW else RelativeLayout.ABOVE)
        pagerParams.addRule(if (bottom) RelativeLayout.ABOVE else RelativeLayout.BELOW, R.id.main_tab_layout)
        tabLayout.setSelectedTabIndicatorGravity(if (bottom) TabLayout.INDICATOR_GRAVITY_TOP else TabLayout.INDICATOR_GRAVITY_BOTTOM)

        tabLayout.layoutParams = tabParams
        viewPager.layoutParams = pagerParams

        // change the background and icon color of the tab layout:
        // service-colored at the top, app-background-colored at the bottom
        tabLayout.setBackgroundColor(resolveColorFromAttr(requireContext(), if (bottom) R.attr.colorSecondary else R.attr.colorPrimary))

        @ColorInt val iconColor = if (bottom) resolveColorFromAttr(requireContext(), R.attr.colorAccent) else Color.WHITE
        tabLayout.tabRippleColor = ColorStateList.valueOf(iconColor).withAlpha(32)
        tabLayout.tabIconTint = ColorStateList.valueOf(iconColor)
        tabLayout.setSelectedTabIndicatorColor(iconColor)
    }

    override fun onTabSelected(selectedTab: TabLayout.Tab) {
        Logd(TAG, "onTabSelected() called with: selectedTab = [$selectedTab]")
        updateTitleForTab(selectedTab.position)
    }

    override fun onTabUnselected(tab: TabLayout.Tab) {}

    override fun onTabReselected(tab: TabLayout.Tab) {
        Logd(TAG, "onTabReselected() called with: tab = [$tab]")
        updateTitleForTab(tab.position)
    }

    class SelectedTabsPagerAdapter(private val context: Context, fragmentManager: FragmentManager, tabsList: List<Tab>)
        : FragmentStatePagerAdapterMenuWorkaround(fragmentManager, Behavior.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        private val internalTabsList: List<Tab> = ArrayList(tabsList)

        /**
         * Keep reference to LocalPlaylistFragments, because their data can be modified by the user
         * during runtime and changes are not committed immediately. However, in some cases,
         * the changes need to be committed immediately by calling
         * [LocalPlaylistFragment.commitChanges].
         * The fragments are removed when [LocalPlaylistFragment.onDestroy] is called.
         */
        internal val localPlaylistFragments: MutableList<LocalPlaylistFragment> = ArrayList()

        override fun getItem(position: Int): Fragment {
            val tab = internalTabsList[position]

            val fragment: Fragment
            try {
                fragment = tab.getFragment(context)
            } catch (e: ExtractionException) {
                showUiErrorSnackbar(context, "Getting fragment item", e)
                return BlankFragment()
            }

            if (fragment is BaseFragment) fragment.useAsFrontPage(true)
            if (fragment is LocalPlaylistFragment) localPlaylistFragments.add(fragment)

            return fragment
        }

        fun getLocalPlaylistFragments(): List<LocalPlaylistFragment> {
            return localPlaylistFragments
        }

        override fun getItemPosition(`object`: Any): Int {
            // Causes adapter to reload all Fragments when
            // notifyDataSetChanged is called
            return POSITION_NONE
        }

        override fun getCount(): Int {
            return internalTabsList.size
        }

        fun sameTabs(tabsToCompare: List<Tab>): Boolean {
            return internalTabsList == tabsToCompare
        }
    }
}
