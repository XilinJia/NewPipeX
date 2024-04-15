package org.schabi.newpipe.settings.tabs

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import junit.framework.Assert.assertEquals
import org.junit.Assert
import org.junit.Test
import org.schabi.newpipe.settings.tabs.Tab.*
import org.schabi.newpipe.settings.tabs.Tab.Companion.from
import org.schabi.newpipe.settings.tabs.TabsJsonHelper.InvalidJsonException
import org.schabi.newpipe.settings.tabs.TabsJsonHelper.defaultTabs
import org.schabi.newpipe.settings.tabs.TabsJsonHelper.getJsonToSave
import org.schabi.newpipe.settings.tabs.TabsJsonHelper.getTabsFromJson
import java.util.*

class TabsJsonHelperTest {
    @Test
    @Throws(InvalidJsonException::class)
    fun testEmptyAndNullRead() {
        val defaultTabs = defaultTabs

        val emptyTabsJson = "{\"" + JSON_TABS_ARRAY_KEY + "\":[]}"
        var items: List<Tab?> = getTabsFromJson(emptyTabsJson)
        Assert.assertEquals(items, defaultTabs)

        val nullSource: String? = null
        items = getTabsFromJson(nullSource)
        Assert.assertEquals(items, defaultTabs)
    }

    @Test
    @Throws(InvalidJsonException::class)
    fun testInvalidIdRead() {
        val blankTabId = Tab.Type.BLANK.tabId
        val emptyTabsJson = ("{\"" + JSON_TABS_ARRAY_KEY + "\":["
                + "{\"" + JSON_TAB_ID_KEY + "\":" + blankTabId + "},"
                + "{\"" + JSON_TAB_ID_KEY + "\":" + 12345678 + "}" + "]}")
        val items = getTabsFromJson(emptyTabsJson)

        Assert.assertEquals("Should ignore the tab with invalid id", 1, items.size.toLong())
        assertEquals(blankTabId, items[0].tabId)
    }

    @Test
    fun testInvalidRead() {
        val invalidList: List<String> = mutableListOf(
            "{\"notTabsArray\":[]}",
            "{invalidJSON]}",
            "{}"
        )

        for (invalidContent in invalidList) {
            try {
                getTabsFromJson(invalidContent)

                Assert.fail("didn't throw exception")
            } catch (e: Exception) {
                val isExpectedException =
                    e is InvalidJsonException
                Assert.assertTrue("\"" + e.javaClass.simpleName
                        + "\" is not the expected exception", isExpectedException)
            }
        }
    }

    @Test
    @Throws(JsonParserException::class)
    fun testEmptyAndNullSave() {
        val emptyList = emptyList<Tab>()
        var returnedJson = getJsonToSave(emptyList)
        Assert.assertTrue(isTabsArrayEmpty(returnedJson))

        val nullList: List<Tab>? = null
        returnedJson = getJsonToSave(nullList)
        Assert.assertTrue(isTabsArrayEmpty(returnedJson))
    }

    @Throws(JsonParserException::class)
    private fun isTabsArrayEmpty(returnedJson: String): Boolean {
        val jsonObject = JsonParser.`object`().from(returnedJson)
        Assert.assertTrue(jsonObject.containsKey(JSON_TABS_ARRAY_KEY))
        return jsonObject.getArray(JSON_TABS_ARRAY_KEY).isEmpty()
    }

    @Test
    @Throws(JsonParserException::class)
    fun testSaveAndReading() {
        // Saving
        val blankTab = BlankTab()
        val defaultKioskTab = DefaultKioskTab()
        val subscriptionsTab = SubscriptionsTab()
        val channelTab = ChannelTab(
            666, "https://example.org", "testName")
        val kioskTab = KioskTab(123, "trending_key")

        val tabs = Arrays.asList(
            blankTab, defaultKioskTab, subscriptionsTab, channelTab, kioskTab)
        val returnedJson = getJsonToSave(tabs)

        // Reading
        val jsonObject = JsonParser.`object`().from(returnedJson)
        Assert.assertTrue(jsonObject.containsKey(JSON_TABS_ARRAY_KEY))
        val tabsFromArray = jsonObject.getArray(JSON_TABS_ARRAY_KEY)

        Assert.assertEquals(tabs.size.toLong(), tabsFromArray.size.toLong())

        val blankTabFromReturnedJson = Objects.requireNonNull(from(
            (tabsFromArray[0] as JsonObject)) as BlankTab?)
        Assert.assertEquals(blankTab.tabId.toLong(), blankTabFromReturnedJson!!.tabId.toLong())

        val defaultKioskTabFromReturnedJson = Objects.requireNonNull(
            from((tabsFromArray[1] as JsonObject)) as DefaultKioskTab?)
        Assert.assertEquals(defaultKioskTab.tabId.toLong(), defaultKioskTabFromReturnedJson!!.tabId.toLong())

        val subscriptionsTabFromReturnedJson = Objects.requireNonNull(
            from((tabsFromArray[2] as JsonObject)) as SubscriptionsTab?)
        Assert.assertEquals(subscriptionsTab.tabId.toLong(), subscriptionsTabFromReturnedJson!!.tabId.toLong())

        val channelTabFromReturnedJson = Objects.requireNonNull(from(
            (tabsFromArray[3] as JsonObject)) as ChannelTab?)
        Assert.assertEquals(channelTab.tabId.toLong(), channelTabFromReturnedJson!!.tabId.toLong())
        Assert.assertEquals(channelTab.channelServiceId.toLong(),
            channelTabFromReturnedJson.channelServiceId.toLong())
        Assert.assertEquals(channelTab.channelUrl, channelTabFromReturnedJson.channelUrl)
        Assert.assertEquals(channelTab.channelName, channelTabFromReturnedJson.channelName)

        val kioskTabFromReturnedJson = Objects.requireNonNull(from(
            (tabsFromArray[4] as JsonObject)) as KioskTab?)
        Assert.assertEquals(kioskTab.tabId.toLong(), kioskTabFromReturnedJson!!.tabId.toLong())
        Assert.assertEquals(kioskTab.kioskServiceId.toLong(), kioskTabFromReturnedJson.kioskServiceId.toLong())
        Assert.assertEquals(kioskTab.kioskId, kioskTabFromReturnedJson.kioskId)
    }

    companion object {
        private const val JSON_TABS_ARRAY_KEY = "tabs"
        private const val JSON_TAB_ID_KEY = "tab_id"
    }
}
