package org.schabi.newpipe.settings

import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.jakewharton.rxbinding4.widget.textChanges
import icepick.Icepick
import icepick.State
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.SettingsLayoutBinding
import org.schabi.newpipe.settings.SettingsResourceRegistry.SettingRegistryEntry
import org.schabi.newpipe.settings.preferencesearch.*
import org.schabi.newpipe.util.DeviceUtils.isTv
import org.schabi.newpipe.util.KeyboardUtil.hideKeyboard
import org.schabi.newpipe.util.KeyboardUtil.showKeyboard
import org.schabi.newpipe.util.Localization.assureCorrectAppLanguage
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.util.ReleaseVersionUtil.isReleaseApk
import org.schabi.newpipe.util.ThemeHelper.getSettingsThemeStyle
import org.schabi.newpipe.views.FocusOverlayView.Companion.setupFocusObserver
import java.util.concurrent.TimeUnit
import java.util.function.Function
import java.util.function.Predicate

/*
* Created by Christian Schabesberger on 31.08.15.
*
* Copyright (C) Christian Schabesberger 2015 <chris.schabesberger@mailbox.org>
* SettingsActivity.java is part of NewPipe.
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
class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback,
    PreferenceSearchResultListener {
    private var searchFragment: PreferenceSearchFragment? = null

    private var menuSearchItem: MenuItem? = null

    private var searchContainer: View? = null
    private var searchEditText: EditText? = null

    // State
    @JvmField
    @State
    var searchText: String? = null

    @JvmField
    @State
    var wasSearchActive: Boolean = false

    override fun onCreate(savedInstanceBundle: Bundle?) {
        setTheme(getSettingsThemeStyle(this))
        assureCorrectAppLanguage(this)

        super.onCreate(savedInstanceBundle)
        Icepick.restoreInstanceState(this, savedInstanceBundle)
        val restored = savedInstanceBundle != null

        val settingsLayoutBinding =
            SettingsLayoutBinding.inflate(layoutInflater)
        setContentView(settingsLayoutBinding.root)
        initSearch(settingsLayoutBinding, restored)

        setSupportActionBar(settingsLayoutBinding.settingsToolbarLayout.toolbar)

        if (restored) {
            // Restore state
            if (this.wasSearchActive) {
                isSearchActive = true
                if (!TextUtils.isEmpty(this.searchText)) {
                    searchEditText!!.setText(this.searchText)
                }
            }
        } else {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_fragment_holder, MainSettingsFragment())
                .commit()
        }

        if (isTv(this)) {
            setupFocusObserver(this)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Icepick.saveInstanceState(this, outState)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val actionBar = supportActionBar
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true)
            actionBar.setDisplayShowTitleEnabled(true)
        }

        return super.onCreateOptionsMenu(menu)
    }

    override fun onBackPressed() {
        if (isSearchActive) {
            isSearchActive = false
            return
        }
        super.onBackPressed()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        if (id == android.R.id.home) {
            // Check if the search is active and if so: Close it
            if (isSearchActive) {
                isSearchActive = false
                return true
            }

            if (supportFragmentManager.backStackEntryCount == 0) {
                finish()
            } else {
                supportFragmentManager.popBackStack()
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat,
                                           preference: Preference
    ): Boolean {
        showSettingsFragment(instantiateFragment(preference.fragment!!))
        return true
    }

    private fun instantiateFragment(className: String): Fragment {
        return supportFragmentManager
            .fragmentFactory
            .instantiate(this.classLoader, className)
    }

    private fun showSettingsFragment(fragment: Fragment?) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.animator.custom_fade_in, R.animator.custom_fade_out,
                R.animator.custom_fade_in, R.animator.custom_fade_out)
            .replace(FRAGMENT_HOLDER_ID, fragment!!)
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroy() {
        setMenuSearchItem(null)
        searchFragment = null
        super.onDestroy()
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Search
    ////////////////////////////////////////////////////////////////////////// */
    //region Search
    private fun initSearch(
            settingsLayoutBinding: SettingsLayoutBinding,
            restored: Boolean
    ) {
        searchContainer = settingsLayoutBinding.settingsToolbarLayout.toolbar.findViewById(R.id.toolbar_search_container)

        // Configure input field for search
        searchEditText = searchContainer!!.findViewById(R.id.toolbar_search_edit_text)
        searchEditText!!.textChanges() // Wait some time after the last input before actually searching
            .debounce(200, TimeUnit.MILLISECONDS)
            .subscribe { v: CharSequence? -> runOnUiThread { this.onSearchChanged() } }

        // Configure clear button
        searchContainer!!.findViewById<View>(R.id.toolbar_search_clear).setOnClickListener { ev: View? -> resetSearchText() }

        ensureSearchRepresentsApplicationState()

        // Build search configuration using SettingsResourceRegistry
        val config = PreferenceSearchConfiguration()


        // Build search items
        val searchContext = applicationContext
        assureCorrectAppLanguage(searchContext)
        val parser = PreferenceParser(searchContext, config)
        val searcher = PreferenceSearcher(config)

        // Find all searchable SettingsResourceRegistry fragments
        // Add it to the searcher
        SettingsResourceRegistry.instance.allEntries.stream()
            .filter { it?.isSearchable?:false }
            .map<Int>(Function<SettingRegistryEntry?, Int> { it.preferencesResId })
            .map<List<PreferenceSearchItem>> { resId: Int? ->
                parser.parse(resId!!)
            }
            .forEach { items: List<PreferenceSearchItem> -> searcher.add(items) }

        if (restored) {
            searchFragment = supportFragmentManager
                .findFragmentByTag(PreferenceSearchFragment.NAME) as PreferenceSearchFragment?
            if (searchFragment != null) {
                // Hide/Remove the search fragment otherwise we get an exception
                // when adding it (because it's already present)
                hideSearchFragment()
            }
        }
        if (searchFragment == null) {
            searchFragment = PreferenceSearchFragment()
        }
        searchFragment!!.setSearcher(searcher)
    }

    /**
     * Ensures that the search shows the correct/available search results.
     * <br></br>
     * Some features are e.g. only available for debug builds, these should not
     * be found when searching inside a release.
     */
    private fun ensureSearchRepresentsApplicationState() {
        // Check if the update settings are available
        if (!isReleaseApk()) {
            SettingsResourceRegistry.instance
                .getEntryByPreferencesResId(R.xml.update_settings)
                ?.setSearchable(false)
        }

        // Hide debug preferences in RELEASE build variant
        if (DEBUG) {
            SettingsResourceRegistry.instance.getEntryByPreferencesResId(R.xml.debug_settings)?.setSearchable(true)
        }
    }

    fun setMenuSearchItem(menuSearchItem: MenuItem?) {
        this.menuSearchItem = menuSearchItem

        // Ensure that the item is in the correct state when adding it. This is due to
        // Android's lifecycle (the Activity is recreated before the Fragment that registers this)
        menuSearchItem?.setVisible(!isSearchActive)
    }

    private fun hideSearchFragment() {
        supportFragmentManager.beginTransaction().remove(searchFragment!!).commit()
    }

    private fun resetSearchText() {
        searchEditText!!.setText("")
    }

    var isSearchActive: Boolean = false
        get() = searchContainer!!.visibility == View.VISIBLE
        set(active) {
            Logd(TAG, "setSearchActive called active=$active")
            // Ignore if search is already in correct state
            if (field == active) return

            wasSearchActive = active

            searchContainer!!.visibility = if (active) View.VISIBLE else View.GONE
            if (menuSearchItem != null) {
                menuSearchItem!!.setVisible(!active)
            }

            if (active) {
                supportFragmentManager
                    .beginTransaction()
                    .add(FRAGMENT_HOLDER_ID, searchFragment!!, PreferenceSearchFragment.NAME)
                    .addToBackStack(PreferenceSearchFragment.NAME)
                    .commit()

                showKeyboard(this, searchEditText)
            } else if (searchFragment != null) {
                hideSearchFragment()
                supportFragmentManager.popBackStack(PreferenceSearchFragment.NAME, FragmentManager.POP_BACK_STACK_INCLUSIVE)
                hideKeyboard(this, searchEditText)
            }

            resetSearchText()
        }

    private fun onSearchChanged() {
        if (!isSearchActive) return

        if (searchFragment != null) {
            searchText = searchEditText!!.text.toString()
            if (searchText != null) searchFragment!!.updateSearchResults(searchText!!)
        }
    }

    override fun onSearchResultClicked(result: PreferenceSearchItem) {
        Logd(TAG, "onSearchResultClicked called result=$result")

        // Hide the search
        isSearchActive = false

        // -- Highlight the result --
        // Find out which fragment class we need
        val targetedFragmentClass = SettingsResourceRegistry.instance.getFragmentClass(result.searchIndexItemResId)

        if (targetedFragmentClass == null) {
            // This should never happen
            Log.w(TAG, "Unable to locate fragment class for resId=${result.searchIndexItemResId}")
            return
        }

        // Check if the currentFragment is the one which contains the result
        var currentFragment =
            supportFragmentManager.findFragmentById(FRAGMENT_HOLDER_ID)
        if (targetedFragmentClass != currentFragment!!.javaClass) {
            // If it's not the correct one display the correct one
            currentFragment = instantiateFragment(targetedFragmentClass.name)
            showSettingsFragment(currentFragment)
        }

        // Run the highlighting
        if (currentFragment is PreferenceFragmentCompat) {
            PreferenceSearchResultHighlighter.highlight(result, currentFragment)
        }
    } //endregion

    companion object {
        private const val TAG = "SettingsActivity"
        private val DEBUG = MainActivity.DEBUG

        @IdRes
        private val FRAGMENT_HOLDER_ID = R.id.settings_fragment_holder
    }
}
