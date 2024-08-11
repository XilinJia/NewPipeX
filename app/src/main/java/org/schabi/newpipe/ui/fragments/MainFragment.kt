package org.schabi.newpipe.ui.fragments

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.view.*
import android.widget.RelativeLayout
import androidx.annotation.ColorInt
import androidx.annotation.IntDef
import androidx.annotation.OptIn
import androidx.core.os.BundleCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.preference.PreferenceManager
import androidx.viewpager.widget.PagerAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayout.OnTabSelectedListener
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentMainBinding
import org.schabi.newpipe.error.ErrorUtil.Companion.showUiErrorSnackbar
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.ui.local.playlist.LocalPlaylistFragment
import org.schabi.newpipe.settings.tabs.Tab
import org.schabi.newpipe.settings.tabs.TabsManager
import org.schabi.newpipe.settings.tabs.TabsManager.Companion.getManager
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
        binding = FragmentMainBinding.bind(rootView)
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (binding != null) {
            binding!!.pager.adapter = null
            binding = null
        }
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

        if (pagerAdapter?.sameTabs(tabsList) != true) pagerAdapter = SelectedTabsPagerAdapter(requireContext(), childFragmentManager, tabsList)

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

    // TODO: Replace this deprecated class with its ViewPager2 counterpart
    /**
     * This is a copy from [androidx.fragment.app.FragmentStatePagerAdapter].
     *
     * It includes a workaround to fix the menu visibility when the adapter is restored.
     * When restoring the state of this adapter, all the fragments' menu visibility were set to false,
     * effectively disabling the menu from the user until he switched pages or another event
     * that triggered the menu to be visible again happened.
     *
     * **Check out the changes in:**
     *
     *  * [.saveState]
     *  * [.restoreState]
     */
    /**
     * Constructor for [FragmentStatePagerAdapterMenuWorkaround].
     *
     * If [.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT] is passed in, then only the current
     * Fragment is in the [Lifecycle.State.RESUMED] state, while all other fragments are
     * capped at [Lifecycle.State.STARTED]. If [.BEHAVIOR_SET_USER_VISIBLE_HINT] is
     * passed, all fragments are in the [Lifecycle.State.RESUMED] state and there will be
     * callbacks to [Fragment.setUserVisibleHint].
     *
     * @param fm fragment manager that will interact with this adapter
     * @param behavior determines if only current fragments are in a resumed state
     */
    @Suppress("deprecation")
    @Deprecated("""Switch to {@link androidx.viewpager2.widget.ViewPager2} and use
  {@link androidx.viewpager2.adapter.FragmentStateAdapter} instead.""")
    abstract class FragmentStatePagerAdapterMenuWorkaround(private val mFragmentManager: FragmentManager, @param:Behavior private val mBehavior: Int)
        : PagerAdapter() {

        @Retention(AnnotationRetention.SOURCE)
        @IntDef(Behavior.BEHAVIOR_SET_USER_VISIBLE_HINT, Behavior.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT)

        protected annotation class Behavior {
            companion object {
                const val BEHAVIOR_SET_USER_VISIBLE_HINT = 0
                const val BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT = 1
            }
        }

        private var mCurTransaction: FragmentTransaction? = null

        private val mSavedState = ArrayList<Fragment.SavedState?>()
        private val mFragments = ArrayList<Fragment?>()
        private var mCurrentPrimaryItem: Fragment? = null
        private var mExecutingFinishUpdate = false

        /**
         * Constructor for [FragmentStatePagerAdapterMenuWorkaround]
         * that sets the fragment manager for the adapter. This is the equivalent of calling
         * [.FragmentStatePagerAdapterMenuWorkaround] and passing in
         * [.BEHAVIOR_SET_USER_VISIBLE_HINT].
         *
         *
         * Fragments will have [Fragment.setUserVisibleHint] called whenever the
         * current Fragment changes.
         *
         * @param fm fragment manager that will interact with this adapter
         */
        @Deprecated("""use {@link #FragmentStatePagerAdapterMenuWorkaround(FragmentManager, int)} with
      {@link #BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT}""")
        constructor(fm: FragmentManager) : this(fm, Behavior.BEHAVIOR_SET_USER_VISIBLE_HINT)

        /**
         * @param position the position of the item you want
         * @return the [Fragment] associated with a specified position
         */
        abstract fun getItem(position: Int): Fragment

        override fun startUpdate(container: ViewGroup) {
            check(container.id != View.NO_ID) {
                ("ViewPager with adapter $this requires a view id")
            }
        }

        @Suppress("deprecation")
        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            // If we already have this item instantiated, there is nothing
            // to do.  This can happen when we are restoring the entire pager
            // from its saved state, where the fragment manager has already
            // taken care of restoring the fragments we previously had instantiated.
            if (mFragments.size > position) {
                val f = mFragments[position]
                if (f != null) return f
            }

            if (mCurTransaction == null) mCurTransaction = mFragmentManager.beginTransaction()

            val fragment = getItem(position)
            Logd(TAG, "Adding item #$position: f=$fragment")

            if (mSavedState.size > position) {
                val fss = mSavedState[position]
                if (fss != null) fragment.setInitialSavedState(fss)
            }
            while (mFragments.size <= position) {
                mFragments.add(null)
            }
            fragment.setMenuVisibility(false)
            if (mBehavior == Behavior.BEHAVIOR_SET_USER_VISIBLE_HINT) fragment.userVisibleHint = false

            mFragments[position] = fragment
            mCurTransaction!!.add(container.id, fragment)

            if (mBehavior == Behavior.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) mCurTransaction!!.setMaxLifecycle(fragment, Lifecycle.State.STARTED)

            return fragment
        }

        override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
            val fragment = `object` as Fragment
            if (mCurTransaction == null) mCurTransaction = mFragmentManager.beginTransaction()

            Logd(TAG, "Removing item #$position: f=$`object` v=${`object`.view}")

            while (mSavedState.size <= position) {
                mSavedState.add(null)
            }
            mSavedState[position] = if (fragment.isAdded) mFragmentManager.saveFragmentInstanceState(fragment) else null
            mFragments.set(position, null)

            mCurTransaction!!.remove(fragment)
            if (fragment == mCurrentPrimaryItem) mCurrentPrimaryItem = null
        }

        @Suppress("deprecation")
        override fun setPrimaryItem(container: ViewGroup, position: Int, `object`: Any) {
            val fragment = `object` as Fragment
            if (fragment !== mCurrentPrimaryItem) {
                if (mCurrentPrimaryItem != null) {
                    mCurrentPrimaryItem!!.setMenuVisibility(false)
                    if (mBehavior == Behavior.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
                        if (mCurTransaction == null) mCurTransaction = mFragmentManager.beginTransaction()
                        mCurTransaction!!.setMaxLifecycle(mCurrentPrimaryItem!!, Lifecycle.State.STARTED)
                    } else mCurrentPrimaryItem!!.userVisibleHint = false
                }
                fragment.setMenuVisibility(true)
                if (mBehavior == Behavior.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
                    if (mCurTransaction == null) mCurTransaction = mFragmentManager.beginTransaction()
                    mCurTransaction!!.setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
                } else fragment.userVisibleHint = true

                mCurrentPrimaryItem = fragment
            }
        }

        override fun finishUpdate(container: ViewGroup) {
            if (mCurTransaction != null) {
                // We drop any transactions that attempt to be committed
                // from a re-entrant call to finishUpdate(). We need to
                // do this as a workaround for Robolectric running measure/layout
                // calls inline rather than allowing them to be posted
                // as they would on a real device.
                if (!mExecutingFinishUpdate) {
                    try {
                        mExecutingFinishUpdate = true
                        mCurTransaction!!.commitNowAllowingStateLoss()
                    } finally {
                        mExecutingFinishUpdate = false
                    }
                }
                mCurTransaction = null
            }
        }

        override fun isViewFromObject(view: View, `object`: Any): Boolean {
            return (`object` as Fragment).view === view
        }

        //!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
        private val selectedFragment = "selected_fragment"

        //!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
        override fun saveState(): Parcelable? {
            var state: Bundle? = null
            if (!mSavedState.isEmpty()) {
                state = Bundle()
                state.putParcelableArrayList("states", mSavedState)
            }
            for (i in mFragments.indices) {
                val f = mFragments[i]
                if (f != null && f.isAdded) {
                    if (state == null) state = Bundle()
                    val key = "f$i"
                    mFragmentManager.putFragment(state, key, f)

                    //!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                    // Check if it's the same fragment instance
                    if (f === mCurrentPrimaryItem) state.putString(selectedFragment, key)
                    //!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                }
            }
            return state
        }

        override fun restoreState(state: Parcelable?, loader: ClassLoader?) {
            if (state != null) {
                val bundle = state as Bundle
                bundle.classLoader = loader
                val states = BundleCompat.getParcelableArrayList(bundle, "states", Fragment.SavedState::class.java)
                mSavedState.clear()
                mFragments.clear()
                if (states != null) mSavedState.addAll(states)

                val keys: Iterable<String> = bundle.keySet()
                for (key in keys) {
                    if (key.startsWith("f")) {
                        val f = mFragmentManager.findFragmentByTag(key)
                        if (f != null && f.isAdded) {
                            val index = key.substring(1).toInt()
                            while (mFragments.size <= index) {
                                mFragments.add(null)
                            }
                            //!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                            val wasSelected = (bundle.getString(selectedFragment, "") == key)
                            f.setMenuVisibility(wasSelected)
                            //!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                            mFragments[index] = f
                        } else Log.w(TAG, "Bad fragment at key $key")
                    } else Logd(TAG, "key not starting with f: $key")
                }
            }
        }

        companion object {
            private const val TAG = "FragmentStatePagerAdapt"
            private const val DEBUG = false

            /**
             * Indicates that [Fragment.setUserVisibleHint] will be called when the current
             * fragment changes.
             *
             * @see .FragmentStatePagerAdapterMenuWorkaround
             */
//        @Deprecated("""This behavior relies on the deprecated
//      {@link Fragment#setUserVisibleHint(boolean)} API. Use
//      {@link #BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT} to switch to its replacement,
//      {@link FragmentTransaction#setMaxLifecycle}.
//      """)
//        const val BEHAVIOR_SET_USER_VISIBLE_HINT: Int = 0

            /**
             * Indicates that only the current fragment will be in the [Lifecycle.State.RESUMED]
             * state. All other Fragments are capped at [Lifecycle.State.STARTED].
             *
             * @see .FragmentStatePagerAdapterMenuWorkaround
             */
//        const val BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT: Int = 1
        }
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
