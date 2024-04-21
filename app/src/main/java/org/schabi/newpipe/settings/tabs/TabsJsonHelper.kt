package org.schabi.newpipe.settings.tabs

import com.grack.nanojson.*
import org.schabi.newpipe.settings.tabs.Tab.Companion.from

/**
 * Class to get a JSON representation of a list of tabs, and the other way around.
 */
object TabsJsonHelper {
    private const val JSON_TABS_ARRAY_KEY = "tabs"

    val defaultTabs: List<Tab> = listOf(
        Tab.Type.DEFAULT_KIOSK.tab,
        Tab.Type.FEED.tab,
        Tab.Type.SUBSCRIPTIONS.tab,
        Tab.Type.BOOKMARKS.tab)

    /**
     * Try to reads the passed JSON and returns the list of tabs if no error were encountered.
     *
     *
     * If the JSON is null or empty, or the list of tabs that it represents is empty, the
     * [fallback list][.getDefaultTabs] will be returned.
     *
     *
     * Tabs with invalid ids (i.e. not in the [Tab.Type] enum) will be ignored.
     *
     * @param tabsJson a JSON string got from [.getJsonToSave].
     * @return a list of [tabs][Tab].
     * @throws InvalidJsonException if the JSON string is not valid
     */
    @JvmStatic
    @Throws(InvalidJsonException::class)
    fun getTabsFromJson(tabsJson: String?): List<Tab> {
        if (tabsJson == null || tabsJson.isEmpty()) {
            return defaultTabs
        }

        val returnTabs: MutableList<Tab> = ArrayList()

        val outerJsonObject: JsonObject
        try {
            outerJsonObject = JsonParser.`object`().from(tabsJson)

            if (!outerJsonObject.has(JSON_TABS_ARRAY_KEY)) {
                throw InvalidJsonException("JSON doesn't contain \"" + JSON_TABS_ARRAY_KEY
                        + "\" array")
            }

            val tabsArray = outerJsonObject.getArray(JSON_TABS_ARRAY_KEY)

            for (o in tabsArray) {
                if (o !is JsonObject) {
                    continue
                }

                val tab = from(o)

                if (tab != null) {
                    returnTabs.add(tab)
                }
            }
        } catch (e: JsonParserException) {
            throw InvalidJsonException(e)
        }

        if (returnTabs.isEmpty()) {
            return defaultTabs
        }

        return returnTabs
    }

    /**
     * Get a JSON representation from a list of tabs.
     *
     * @param tabList a list of [tabs][Tab].
     * @return a JSON string representing the list of tabs
     */
    fun getJsonToSave(tabList: List<Tab>?): String {
        val jsonWriter = JsonWriter.string()
        jsonWriter.`object`()

        jsonWriter.array(JSON_TABS_ARRAY_KEY)
        if (tabList != null) {
            for (tab in tabList) {
                tab.writeJsonOn(jsonWriter)
            }
        }
        jsonWriter.end()

        jsonWriter.end()
        return jsonWriter.done()
    }

    class InvalidJsonException : Exception {
        private constructor() : super()

        internal constructor(message: String) : super(message)

        constructor(cause: Throwable) : super(cause)
    }
}
