package org.schabi.newpipe.database

interface LocalItem {
    val localItemType: LocalItemType?

    enum class LocalItemType {
        PLAYLIST_LOCAL_ITEM,
        PLAYLIST_REMOTE_ITEM,

        PLAYLIST_STREAM_ITEM,
        STATISTIC_STREAM_ITEM,
    }
}
