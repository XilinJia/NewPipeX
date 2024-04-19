package org.schabi.newpipe.settings.preferencesearch

import android.content.Context
import android.text.TextUtils
import android.util.Log
import androidx.annotation.XmlRes
import androidx.preference.PreferenceManager
import org.schabi.newpipe.util.Localization.concatenateStrings
import org.xmlpull.v1.XmlPullParser
import java.util.*

/**
 * Parses the corresponding preference-file(s).
 */
class PreferenceParser(private val context: Context,
                       private val searchConfiguration: PreferenceSearchConfiguration) {
    private val allPreferences: Map<String, *> = PreferenceManager.getDefaultSharedPreferences(context).all

    fun parse(@XmlRes resId: Int): List<PreferenceSearchItem> {
        val results: MutableList<PreferenceSearchItem> = ArrayList()
        val xpp: XmlPullParser = context.resources.getXml(resId)

        try {
            xpp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            xpp.setFeature(XmlPullParser.FEATURE_REPORT_NAMESPACE_ATTRIBUTES, true)

            val breadcrumbs: MutableList<String?> = ArrayList()
            while (xpp.eventType != XmlPullParser.END_DOCUMENT) {
                if (xpp.eventType == XmlPullParser.START_TAG) {
                    val result = parseSearchResult(xpp, concatenateStrings(" > ", breadcrumbs), resId)

                    if (!searchConfiguration.parserIgnoreElements.contains(xpp.name) && result.hasData()
                            && "true" != getAttribute(xpp, NS_SEARCH, "ignore")) {
                        results.add(result)
                    }
                    if (searchConfiguration.parserContainerElements.contains(xpp.name)) {
                        // This code adds breadcrumbs for certain containers (e.g. PreferenceScreen)
                        // Example: Video and Audio > Player
                        breadcrumbs.add(if (result.title == null) "" else result.title)
                    }
                } else if (xpp.eventType == XmlPullParser.END_TAG && searchConfiguration.parserContainerElements.contains(xpp.name)) {
                    breadcrumbs.removeAt(breadcrumbs.size - 1)
                }

                xpp.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse resid=$resId", e)
        }
        return results
    }

    private fun getAttribute(xpp: XmlPullParser, attribute: String): String? {
        val nsSearchAttr = getAttribute(xpp, NS_SEARCH, attribute)
        if (nsSearchAttr != null) return nsSearchAttr

        return getAttribute(xpp, NS_ANDROID, attribute)
    }

    private fun getAttribute(xpp: XmlPullParser, namespace: String, attribute: String): String? {
        return xpp.getAttributeValue(namespace, attribute)
    }

    private fun parseSearchResult(xpp: XmlPullParser, breadcrumbs: String, @XmlRes searchIndexItemResId: Int): PreferenceSearchItem {
        val key = readString(getAttribute(xpp, "key"))
        val entries = readStringArray(getAttribute(xpp, "entries"))
        val entryValues = readStringArray(getAttribute(xpp, "entryValues"))

        return PreferenceSearchItem(
            key,
            tryFillInPreferenceValue(readString(getAttribute(xpp, "title")), key, entries.filterNotNull().toTypedArray(), entryValues),
            tryFillInPreferenceValue(readString(getAttribute(xpp, "summary")), key, entries.filterNotNull().toTypedArray(), entryValues),
            TextUtils.join(",", entries),
            breadcrumbs,
            searchIndexItemResId
        )
    }

    private fun readStringArray(s: String?): Array<String?> {
        if (s == null) return arrayOfNulls(0)

        if (s.startsWith("@")) {
            try {
                return context.resources.getStringArray(s.substring(1).toInt())
            } catch (e: Exception) {
                Log.w(TAG, "Unable to readStringArray from '$s'", e)
            }
        }
        return arrayOfNulls(0)
    }

    private fun readString(s: String?): String {
        if (s == null) return ""

        if (s.startsWith("@")) {
            try {
                return context.getString(s.substring(1).toInt())
            } catch (e: Exception) {
                Log.w(TAG, "Unable to readString from '$s'", e)
            }
        }
        return s
    }

    private fun tryFillInPreferenceValue(s: String?, key: String?, entries: Array<String>, entryValues: Array<String?>): String {
        if (s == null) return ""

        if (key == null) return s

        // Resolve value
        var prefValue = (allPreferences[key] as? String) ?: return s

        /*
         * Resolve ListPreference values
         *
         * entryValues = Values/Keys that are saved
         * entries     = Actual human readable names
         */
        if (entries.isNotEmpty() && entryValues.size == entries.size) {
            val entryIndex = listOf<String?>(*entryValues).indexOf(prefValue)
            if (entryIndex != -1) {
                prefValue = entries[entryIndex]
            }
        }

        return String.format(s, prefValue.toString())
    }

    companion object {
        private const val TAG = "PreferenceParser"

        private const val NS_ANDROID = "http://schemas.android.com/apk/res/android"
        private const val NS_SEARCH = "http://schemas.android.com/apk/preferencesearch"
    }
}
