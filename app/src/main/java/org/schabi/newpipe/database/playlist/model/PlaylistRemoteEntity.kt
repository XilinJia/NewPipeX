package org.schabi.newpipe.database.playlist.model

import android.text.TextUtils
import androidx.room.*
import org.schabi.newpipe.database.LocalItem.LocalItemType
import org.schabi.newpipe.database.playlist.PlaylistLocalItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.util.NO_SERVICE_ID
import org.schabi.newpipe.util.image.ImageStrategy.imageListToDbUrl

@Entity(tableName = PlaylistRemoteEntity.REMOTE_PLAYLIST_TABLE,
    indices = [Index(value = [PlaylistRemoteEntity.REMOTE_PLAYLIST_NAME]), Index(value = [PlaylistRemoteEntity.REMOTE_PLAYLIST_SERVICE_ID, PlaylistRemoteEntity.REMOTE_PLAYLIST_URL],
        unique = true)])
class PlaylistRemoteEntity(serviceId: Int, name: String, url: String,
                           thumbnailUrl: String?, uploader: String,
                           streamCount: Long)
    : PlaylistLocalItem {

    @JvmField
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = REMOTE_PLAYLIST_ID)
    var uid: Long = 0

    @JvmField
    @ColumnInfo(name = REMOTE_PLAYLIST_SERVICE_ID)
    var serviceId: Int = NO_SERVICE_ID

    @ColumnInfo(name = REMOTE_PLAYLIST_NAME)
    val name: String? = null

    @JvmField
    @ColumnInfo(name = REMOTE_PLAYLIST_URL)
    var url: String

    @JvmField
    @ColumnInfo(name = REMOTE_PLAYLIST_THUMBNAIL_URL)
    var thumbnailUrl: String?

    @JvmField
    @ColumnInfo(name = REMOTE_PLAYLIST_UPLOADER_NAME)
    var uploader: String

    @JvmField
    @ColumnInfo(name = REMOTE_PLAYLIST_STREAM_COUNT)
    var streamCount: Long

    init {
        this.serviceId = serviceId
        this.url = url
        this.thumbnailUrl = thumbnailUrl
        this.uploader = uploader
        this.streamCount = streamCount
    }

    constructor() : this(0, "", "", null, "", 0L)

    @Ignore
    constructor(info: PlaylistInfo) : this(info.serviceId,
        info.name,
        info.url,  // use uploader avatar when no thumbnail is available
        imageListToDbUrl(if (info.thumbnails.isEmpty()
        ) info.uploaderAvatars else info.thumbnails),
        info.uploaderName,
        info.streamCount)

    @Ignore
    fun isIdenticalTo(info: PlaylistInfo): Boolean {
        /*
         * Returns boolean comparing the online playlist and the local copy.
         * (False if info changed such as playlist name or track count)
         */
        // we want to update the local playlist data even when either the remote thumbnail
        // URL changes, or the preferred image quality setting is changed by the user
        return (serviceId == info.serviceId && streamCount == info.streamCount && TextUtils.equals(name, info.name)
                && TextUtils.equals(url, info.url) && TextUtils.equals(thumbnailUrl, imageListToDbUrl(info.thumbnails))
                && TextUtils.equals(uploader, info.uploaderName))
    }

    override val localItemType: LocalItemType
        get() = LocalItemType.PLAYLIST_REMOTE_ITEM

    override fun getOrderingName(): String {
        return name?:""
    }

    companion object {
        const val REMOTE_PLAYLIST_TABLE: String = "remote_playlists"
        const val REMOTE_PLAYLIST_ID: String = "uid"
        const val REMOTE_PLAYLIST_SERVICE_ID: String = "service_id"
        const val REMOTE_PLAYLIST_NAME: String = "name"
        const val REMOTE_PLAYLIST_URL: String = "url"
        const val REMOTE_PLAYLIST_THUMBNAIL_URL: String = "thumbnail_url"
        const val REMOTE_PLAYLIST_UPLOADER_NAME: String = "uploader"
        const val REMOTE_PLAYLIST_STREAM_COUNT: String = "stream_count"
    }
}
