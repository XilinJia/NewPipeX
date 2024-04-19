package org.schabi.newpipe.settings.tabs

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.fragment.app.Fragment
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonStringWriter
import org.schabi.newpipe.R
import org.schabi.newpipe.database.LocalItem.LocalItemType
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil.Companion.showSnackbar
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.fragments.BlankFragment
import org.schabi.newpipe.fragments.list.channel.ChannelFragment
import org.schabi.newpipe.fragments.list.kiosk.DefaultKioskFragment
import org.schabi.newpipe.fragments.list.kiosk.KioskFragment
import org.schabi.newpipe.fragments.list.playlist.PlaylistFragment
import org.schabi.newpipe.local.bookmark.BookmarkFragment
import org.schabi.newpipe.local.feed.FeedFragment
import org.schabi.newpipe.local.history.StatisticsPlaylistFragment
import org.schabi.newpipe.local.playlist.LocalPlaylistFragment
import org.schabi.newpipe.local.subscription.SubscriptionFragment
import org.schabi.newpipe.util.KioskTranslator.getKioskIcon
import org.schabi.newpipe.util.KioskTranslator.getTranslatedKioskName
import org.schabi.newpipe.util.ServiceHelper.getSelectedServiceId
import java.util.*

abstract class Tab {
    internal constructor()

    internal constructor(jsonObject: JsonObject) {
        readDataFromJson(jsonObject)
    }

    abstract val tabId: Int

    abstract fun getTabName(context: Context): String?

    @DrawableRes
    abstract fun getTabIconRes(context: Context?): Int

    /**
     * Return a instance of the fragment that this tab represent.
     *
     * @param context Android app context
     * @return the fragment this tab represents
     */
    @Throws(ExtractionException::class)
    abstract fun getFragment(context: Context?): Fragment

    override fun equals(obj: Any?): Boolean {
        if (obj !is Tab) {
            return false
        }
        return tabId == obj.tabId
    }

    override fun hashCode(): Int {
        return Objects.hashCode(tabId)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // JSON Handling
    ////////////////////////////////////////////////////////////////////////// */
    fun writeJsonOn(jsonSink: JsonStringWriter) {
        jsonSink.`object`()

        jsonSink.value(JSON_TAB_ID_KEY, tabId)
        writeDataToJson(jsonSink)

        jsonSink.end()
    }

    protected open fun writeDataToJson(writerSink: JsonStringWriter) {
        // No-op
    }

    protected open fun readDataFromJson(jsonObject: JsonObject) {
        // No-op
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Implementations
    ////////////////////////////////////////////////////////////////////////// */
    enum class Type(@JvmField val tab: Tab) {
        BLANK(BlankTab()),
        DEFAULT_KIOSK(DefaultKioskTab()),
        SUBSCRIPTIONS(SubscriptionsTab()),
        FEED(FeedTab()),
        BOOKMARKS(BookmarksTab()),
        HISTORY(HistoryTab()),
        KIOSK(KioskTab()),
        CHANNEL(ChannelTab()),
        PLAYLIST(PlaylistTab());

        val tabId: Int
            get() = tab.tabId
    }

    class BlankTab : Tab() {
//        override fun getTabId(): Int {
//            return ID
//        }
        override val tabId: Int = ID

        override fun getTabName(context: Context): String {
            // TODO: find a better name for the blank tab (maybe "blank_tab") or replace it with
            //       context.getString(R.string.app_name);
            return "NewPipe" // context.getString(R.string.blank_page_summary);
        }

        @DrawableRes
        override fun getTabIconRes(context: Context?): Int {
            return R.drawable.ic_crop_portrait
        }

        override fun getFragment(context: Context?): BlankFragment {
            return BlankFragment()
        }

        companion object {
            const val ID: Int = 0
        }
    }

    class SubscriptionsTab : Tab() {
//        override fun getTabId(): Int {
//            return ID
//        }
        override val tabId: Int = ID

        override fun getTabName(context: Context): String {
            return context.getString(R.string.tab_subscriptions)
        }

        @DrawableRes
        override fun getTabIconRes(context: Context?): Int {
            return R.drawable.ic_tv
        }

        override fun getFragment(context: Context?): SubscriptionFragment {
            return SubscriptionFragment()
        }

        companion object {
            const val ID: Int = 1
        }
    }

    class FeedTab : Tab() {
//        override fun getTabId(): Int {
//            return ID
//        }
        override val tabId: Int = ID

        override fun getTabName(context: Context): String {
            return context.getString(R.string.fragment_feed_title)
        }

        @DrawableRes
        override fun getTabIconRes(context: Context?): Int {
            return R.drawable.ic_subscriptions
        }

        override fun getFragment(context: Context?): FeedFragment {
            return FeedFragment()
        }

        companion object {
            const val ID: Int = 2
        }
    }

    class BookmarksTab : Tab() {
//        override fun getTabId(): Int {
//            return ID
//        }
        override val tabId: Int = ID
        override fun getTabName(context: Context): String {
            return context.getString(R.string.tab_bookmarks)
        }

        @DrawableRes
        override fun getTabIconRes(context: Context?): Int {
            return R.drawable.ic_bookmark
        }

        override fun getFragment(context: Context?): BookmarkFragment {
            return BookmarkFragment()
        }

        companion object {
            const val ID: Int = 3
        }
    }

    class HistoryTab : Tab() {
//        override fun getTabId(): Int {
//            return ID
//        }
        override val tabId: Int = ID
        override fun getTabName(context: Context): String {
            return context.getString(R.string.title_activity_history)
        }

        @DrawableRes
        override fun getTabIconRes(context: Context?): Int {
            return R.drawable.ic_history
        }

        override fun getFragment(context: Context?): StatisticsPlaylistFragment {
            return StatisticsPlaylistFragment()
        }

        companion object {
            const val ID: Int = 4
        }
    }

    class KioskTab : Tab {
        var kioskServiceId: Int = 0
            private set
        var kioskId: String? = null
            private set

        internal constructor() : this(-1, NO_ID)

        constructor(kioskServiceId: Int, kioskId: String?) {
            this.kioskServiceId = kioskServiceId
            this.kioskId = kioskId
        }

        constructor(jsonObject: JsonObject) : super(jsonObject)

//        override fun getTabId(): Int {
//            return ID
//        }
        override val tabId: Int = ID

        override fun getTabName(context: Context): String {
            return getTranslatedKioskName(kioskId!!, context)
        }

        @DrawableRes
        override fun getTabIconRes(context: Context?): Int {
            val kioskIcon = getKioskIcon(kioskId)

            check(kioskIcon > 0) { "Kiosk ID is not valid: \"$kioskId\"" }

            return kioskIcon
        }

        @Throws(ExtractionException::class)
        override fun getFragment(context: Context?): KioskFragment {
            return KioskFragment.getInstance(kioskServiceId, kioskId?:"")
        }

        override fun writeDataToJson(writerSink: JsonStringWriter) {
            writerSink.value(JSON_KIOSK_SERVICE_ID_KEY, kioskServiceId).value(JSON_KIOSK_ID_KEY, kioskId)
        }

        override fun readDataFromJson(jsonObject: JsonObject) {
            kioskServiceId = jsonObject.getInt(JSON_KIOSK_SERVICE_ID_KEY, -1)
            kioskId = jsonObject.getString(JSON_KIOSK_ID_KEY, NO_ID)
        }

        override fun equals(obj: Any?): Boolean {
            if (obj !is KioskTab) return false

            val other = obj
            return super.equals(obj) && kioskServiceId == other.kioskServiceId && kioskId == other.kioskId
        }

        override fun hashCode(): Int {
            return Objects.hash(tabId, kioskServiceId, kioskId)
        }

        companion object {
            const val ID: Int = 5
            private const val JSON_KIOSK_SERVICE_ID_KEY = "service_id"
            private const val JSON_KIOSK_ID_KEY = "kiosk_id"
        }
    }

    class ChannelTab : Tab {
        var channelServiceId: Int = 0
            private set
        var channelUrl: String? = null
            private set
        var channelName: String? = null
            private set

        constructor() : this(-1, NO_URL, NO_NAME)

        constructor(channelServiceId: Int, channelUrl: String?, channelName: String?) {
            this.channelServiceId = channelServiceId
            this.channelUrl = channelUrl
            this.channelName = channelName
        }

        constructor(jsonObject: JsonObject) : super(jsonObject)

//        override fun getTabId(): Int {
//            return ID
//        }
        override val tabId: Int = ID

        override fun getTabName(context: Context): String? {
            return channelName
        }

        @DrawableRes
        override fun getTabIconRes(context: Context?): Int {
            return R.drawable.ic_tv
        }

        override fun getFragment(context: Context?): ChannelFragment {
            return ChannelFragment.getInstance(channelServiceId, channelUrl?:"", channelName?:"")
        }

        override fun writeDataToJson(writerSink: JsonStringWriter) {
            writerSink.value(JSON_CHANNEL_SERVICE_ID_KEY, channelServiceId)
                .value(JSON_CHANNEL_URL_KEY, channelUrl)
                .value(JSON_CHANNEL_NAME_KEY, channelName)
        }

        override fun readDataFromJson(jsonObject: JsonObject) {
            channelServiceId = jsonObject.getInt(JSON_CHANNEL_SERVICE_ID_KEY, -1)
            channelUrl = jsonObject.getString(JSON_CHANNEL_URL_KEY, NO_URL)
            channelName = jsonObject.getString(JSON_CHANNEL_NAME_KEY, NO_NAME)
        }

        override fun equals(obj: Any?): Boolean {
            if (obj !is ChannelTab) return false

            val other = obj
            return super.equals(obj) && channelServiceId == other.channelServiceId && channelUrl == other.channelName && channelName == other.channelName
        }

        override fun hashCode(): Int {
            return Objects.hash(tabId, channelServiceId, channelUrl, channelName)
        }

        companion object {
            const val ID: Int = 6
            private const val JSON_CHANNEL_SERVICE_ID_KEY = "channel_service_id"
            private const val JSON_CHANNEL_URL_KEY = "channel_url"
            private const val JSON_CHANNEL_NAME_KEY = "channel_name"
        }
    }

    class DefaultKioskTab : Tab() {
//        override fun getTabId(): Int {
//            return ID
//        }
        override val tabId: Int = ID

        override fun getTabName(context: Context): String {
            return getTranslatedKioskName(getDefaultKioskId(context), context)
        }

        @DrawableRes
        override fun getTabIconRes(context: Context?): Int {
            return getKioskIcon(getDefaultKioskId(context))
        }

        override fun getFragment(context: Context?): DefaultKioskFragment {
            return DefaultKioskFragment()
        }

        private fun getDefaultKioskId(context: Context?): String {
            val kioskServiceId = getSelectedServiceId(context!!)

            var kioskId = ""
            try {
                val service = NewPipe.getService(kioskServiceId)
                kioskId = service.kioskList.defaultKioskId
            } catch (e: ExtractionException) {
                showSnackbar(context, ErrorInfo(e,
                    UserAction.REQUESTED_KIOSK, "Loading default kiosk for selected service"))
            }
            return kioskId
        }

        companion object {
            const val ID: Int = 7
        }
    }

    class PlaylistTab : Tab {
        var playlistServiceId: Int = 0
            private set
        var playlistUrl: String? = null
            private set
        var playlistName: String? = null
            private set
        var playlistId: Long = 0
            private set
        var playlistType: LocalItemType? = null
            private set

        constructor() : this(-1, NO_NAME)

        constructor(playlistId: Long, playlistName: String?) {
            this.playlistName = playlistName
            this.playlistId = playlistId
            this.playlistType = LocalItemType.PLAYLIST_LOCAL_ITEM
            this.playlistServiceId = -1
            this.playlistUrl = NO_URL
        }

        constructor(playlistServiceId: Int, playlistUrl: String?,
                    playlistName: String?
        ) {
            this.playlistServiceId = playlistServiceId
            this.playlistUrl = playlistUrl
            this.playlistName = playlistName
            this.playlistType = LocalItemType.PLAYLIST_REMOTE_ITEM
            this.playlistId = -1
        }

        constructor(jsonObject: JsonObject) : super(jsonObject)

//        override fun getTabId(): Int {
//            return ID
//        }
        override val tabId: Int = ID

        override fun getTabName(context: Context): String? {
            return playlistName
        }

        @DrawableRes
        override fun getTabIconRes(context: Context?): Int {
            return R.drawable.ic_bookmark
        }

        override fun getFragment(context: Context?): Fragment {
            return if (playlistType == LocalItemType.PLAYLIST_LOCAL_ITEM) {
                LocalPlaylistFragment.getInstance(playlistId, playlistName?:"")
            } else { // playlistType == LocalItemType.PLAYLIST_REMOTE_ITEM
                PlaylistFragment.getInstance(playlistServiceId, playlistUrl, playlistName)
            }
        }

        override fun writeDataToJson(writerSink: JsonStringWriter) {
            writerSink.value(JSON_PLAYLIST_SERVICE_ID_KEY, playlistServiceId)
                .value(JSON_PLAYLIST_URL_KEY, playlistUrl)
                .value(JSON_PLAYLIST_NAME_KEY, playlistName)
                .value(JSON_PLAYLIST_ID_KEY, playlistId)
                .value(JSON_PLAYLIST_TYPE_KEY, playlistType.toString())
        }

        override fun readDataFromJson(jsonObject: JsonObject) {
            playlistServiceId = jsonObject.getInt(JSON_PLAYLIST_SERVICE_ID_KEY, -1)
            playlistUrl = jsonObject.getString(JSON_PLAYLIST_URL_KEY, NO_URL)
            playlistName = jsonObject.getString(JSON_PLAYLIST_NAME_KEY, NO_NAME)
            playlistId = jsonObject.getInt(JSON_PLAYLIST_ID_KEY, -1).toLong()
            playlistType = LocalItemType.valueOf(jsonObject.getString(JSON_PLAYLIST_TYPE_KEY, LocalItemType.PLAYLIST_LOCAL_ITEM.toString())
            )
        }

        override fun equals(obj: Any?): Boolean {
            if (obj !is PlaylistTab) return false
            val other = obj
            return super.equals(obj) && playlistServiceId == other.playlistServiceId && playlistId == other.playlistId && playlistUrl == other.playlistUrl && playlistName == other.playlistName && playlistType == other.playlistType
        }

        override fun hashCode(): Int {
            return Objects.hash(tabId, playlistServiceId, playlistId, playlistUrl, playlistName, playlistType)
        }

        companion object {
            const val ID: Int = 8
            private const val JSON_PLAYLIST_SERVICE_ID_KEY = "playlist_service_id"
            private const val JSON_PLAYLIST_URL_KEY = "playlist_url"
            private const val JSON_PLAYLIST_NAME_KEY = "playlist_name"
            private const val JSON_PLAYLIST_ID_KEY = "playlist_id"
            private const val JSON_PLAYLIST_TYPE_KEY = "playlist_type"
        }
    }

    companion object {
        private const val JSON_TAB_ID_KEY = "tab_id"

        private const val NO_NAME = "<no-name>"
        private const val NO_ID = "<no-id>"
        private const val NO_URL = "<no-url>"

        /*//////////////////////////////////////////////////////////////////////////
    // Tab Handling
    ////////////////////////////////////////////////////////////////////////// */
        @JvmStatic
        fun from(jsonObject: JsonObject): Tab? {
            val tabId = jsonObject.getInt(JSON_TAB_ID_KEY, -1)

            if (tabId == -1) return null
            return from(tabId, jsonObject)
        }

        fun from(tabId: Int): Tab? {
            return from(tabId, null)
        }

        fun typeFrom(tabId: Int): Type? {
            for (available in Type.entries) {
                if (available.tabId == tabId) return available
            }
            return null
        }

        private fun from(tabId: Int, jsonObject: JsonObject?): Tab? {
            val type = typeFrom(tabId) ?: return null

            if (jsonObject != null) {
                when (type) {
                    Type.KIOSK -> return KioskTab(jsonObject)
                    Type.CHANNEL -> return ChannelTab(jsonObject)
                    Type.PLAYLIST -> return PlaylistTab(jsonObject)
                    else -> {}
                }
            }

            return type.tab
        }
    }
}
