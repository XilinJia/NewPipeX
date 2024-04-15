package org.schabi.newpipe.settings.tabs

import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.widget.Toast
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R
import org.schabi.newpipe.settings.tabs.TabsJsonHelper.InvalidJsonException

class TabsManager private constructor(private val context: Context) {
    private val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val savedTabsKey = context.getString(R.string.saved_tabs_key)
    private var savedTabsChangeListener: SavedTabsChangeListener? = null
    private var preferenceChangeListener: OnSharedPreferenceChangeListener? = null

    val tabs: List<Tab>
        get() {
            val savedJson = sharedPreferences.getString(savedTabsKey, null)
            try {
                return TabsJsonHelper.getTabsFromJson(savedJson)
            } catch (e: InvalidJsonException) {
                Toast.makeText(context, R.string.saved_tabs_invalid_json, Toast.LENGTH_SHORT).show()
                return defaultTabs
            }
        }

    fun saveTabs(tabList: List<Tab>?) {
        val jsonToSave = TabsJsonHelper.getJsonToSave(tabList)
        sharedPreferences.edit().putString(savedTabsKey, jsonToSave).apply()
    }

    fun resetTabs() {
        sharedPreferences.edit().remove(savedTabsKey).apply()
    }

    val defaultTabs: List<Tab>
        get() = TabsJsonHelper.defaultTabs

    /*//////////////////////////////////////////////////////////////////////////
    // Listener
    ////////////////////////////////////////////////////////////////////////// */
    fun setSavedTabsListener(listener: SavedTabsChangeListener?) {
        if (preferenceChangeListener != null) {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        }
        savedTabsChangeListener = listener
        preferenceChangeListener = getPreferenceChangeListener()
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    fun unsetSavedTabsListener() {
        if (preferenceChangeListener != null) {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        }
        preferenceChangeListener = null
        savedTabsChangeListener = null
    }

    private fun getPreferenceChangeListener(): OnSharedPreferenceChangeListener {
        return OnSharedPreferenceChangeListener { sp: SharedPreferences?, key: String? ->
            if (savedTabsKey == key && savedTabsChangeListener != null) {
                savedTabsChangeListener!!.onTabsChanged()
            }
        }
    }

    fun interface SavedTabsChangeListener {
        fun onTabsChanged()
    }

    companion object {
        @JvmStatic
        fun getManager(context: Context): TabsManager {
            return TabsManager(context)
        }
    }
}
