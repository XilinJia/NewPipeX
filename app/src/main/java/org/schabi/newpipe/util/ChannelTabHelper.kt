package org.schabi.newpipe.util

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler

object ChannelTabHelper {
    /**
     * @param tab the channel tab to check
     * @return whether the tab should contain (playable) streams or not
     */
    fun isStreamsTab(tab: String?): Boolean {
        return when (tab) {
            ChannelTabs.VIDEOS, ChannelTabs.TRACKS, ChannelTabs.SHORTS, ChannelTabs.LIVESTREAMS -> true
            else -> false
        }
    }

    /**
     * @param tab the channel tab link handler to check
     * @return whether the tab should contain (playable) streams or not
     */
    @JvmStatic
    fun isStreamsTab(tab: ListLinkHandler): Boolean {
        val contentFilters = tab.contentFilters
        return if (contentFilters.isEmpty()) {
            false // this should never happen, but check just to be sure
        } else {
            isStreamsTab(contentFilters[0])
        }
    }

    @StringRes
    private fun getShowTabKey(tab: String): Int {
        return when (tab) {
            ChannelTabs.VIDEOS -> R.string.show_channel_tabs_videos
            ChannelTabs.TRACKS -> R.string.show_channel_tabs_tracks
            ChannelTabs.SHORTS -> R.string.show_channel_tabs_shorts
            ChannelTabs.LIVESTREAMS -> R.string.show_channel_tabs_livestreams
            ChannelTabs.CHANNELS -> R.string.show_channel_tabs_channels
            ChannelTabs.PLAYLISTS -> R.string.show_channel_tabs_playlists
            ChannelTabs.ALBUMS -> R.string.show_channel_tabs_albums
            else -> -1
        }
    }

    @StringRes
    private fun getFetchFeedTabKey(tab: String): Int {
        return when (tab) {
            ChannelTabs.VIDEOS -> R.string.fetch_channel_tabs_videos
            ChannelTabs.TRACKS -> R.string.fetch_channel_tabs_tracks
            ChannelTabs.SHORTS -> R.string.fetch_channel_tabs_shorts
            ChannelTabs.LIVESTREAMS -> R.string.fetch_channel_tabs_livestreams
            else -> -1
        }
    }

    @JvmStatic
    @StringRes
    fun getTranslationKey(tab: String?): Int {
        return when (tab) {
            ChannelTabs.VIDEOS -> R.string.channel_tab_videos
            ChannelTabs.TRACKS -> R.string.channel_tab_tracks
            ChannelTabs.SHORTS -> R.string.channel_tab_shorts
            ChannelTabs.LIVESTREAMS -> R.string.channel_tab_livestreams
            ChannelTabs.CHANNELS -> R.string.channel_tab_channels
            ChannelTabs.PLAYLISTS -> R.string.channel_tab_playlists
            ChannelTabs.ALBUMS -> R.string.channel_tab_albums
            else -> R.string.unknown_content
        }
    }

    @JvmStatic
    fun showChannelTab(context: Context,
                       sharedPreferences: SharedPreferences,
                       @StringRes key: Int
    ): Boolean {
        val enabledTabs = sharedPreferences.getStringSet(
            context.getString(R.string.show_channel_tabs_key), null)
        return enabledTabs?.contains(context.getString(key)) ?: true // default to true
    }

    @JvmStatic
    fun showChannelTab(context: Context,
                       sharedPreferences: SharedPreferences,
                       tab: String
    ): Boolean {
        val key = getShowTabKey(tab)
        if (key == -1) {
            return false
        }
        return showChannelTab(context, sharedPreferences, key)
    }

    fun fetchFeedChannelTab(context: Context,
                            sharedPreferences: SharedPreferences,
                            tab: ListLinkHandler
    ): Boolean {
        val contentFilters = tab.contentFilters
        if (contentFilters.isEmpty()) {
            return false // this should never happen, but check just to be sure
        }

        val key = getFetchFeedTabKey(contentFilters[0])
        if (key == -1) {
            return false
        }

        val enabledTabs = sharedPreferences.getStringSet(
            context.getString(R.string.feed_fetch_channel_tabs_key), null)
        return enabledTabs?.contains(context.getString(key)) ?: true // default to true
    }
}
