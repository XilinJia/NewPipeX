package org.schabi.newpipe.database.subscription

import androidx.room.*
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.util.NO_SERVICE_ID
import org.schabi.newpipe.util.image.ImageStrategy.dbUrlToImageList
import org.schabi.newpipe.util.image.ImageStrategy.imageListToDbUrl

@Entity(tableName = SubscriptionEntity.SUBSCRIPTION_TABLE,
    indices = [Index(value = [SubscriptionEntity.SUBSCRIPTION_SERVICE_ID, SubscriptionEntity.SUBSCRIPTION_URL],
        unique = true)])
class SubscriptionEntity {
    @JvmField
    @PrimaryKey(autoGenerate = true)
    var uid: Long = 0

    @JvmField
    @ColumnInfo(name = SUBSCRIPTION_SERVICE_ID)
    var serviceId: Int = NO_SERVICE_ID

    @JvmField
    @ColumnInfo(name = SUBSCRIPTION_URL)
    var url: String? = null

    @JvmField
    @ColumnInfo(name = SUBSCRIPTION_NAME)
    var name: String? = null

    @JvmField
    @ColumnInfo(name = SUBSCRIPTION_AVATAR_URL)
    var avatarUrl: String? = null

    @JvmField
    @ColumnInfo(name = SUBSCRIPTION_SUBSCRIBER_COUNT)
    var subscriberCount: Long? = null

    @JvmField
    @ColumnInfo(name = SUBSCRIPTION_DESCRIPTION)
    var description: String? = null

    @JvmField
    @get:NotificationMode
    @ColumnInfo(name = SUBSCRIPTION_NOTIFICATION_MODE)
    var notificationMode: Int = 0

    constructor() {}

    @Ignore
    fun setData(n: String?, au: String?, d: String?, sc: Long?) {
        this.name = n
        this.avatarUrl = au
        this.description = d
        this.subscriberCount = sc
    }

    @Ignore
    fun toChannelInfoItem(): ChannelInfoItem {
        val item = ChannelInfoItem(serviceId, url, name)
        item.thumbnails = dbUrlToImageList(avatarUrl)
        item.subscriberCount = subscriberCount!!
        item.description = description
        return item
    }


    // TODO: Remove these generated methods by migrating this class to a data class from Kotlin.
    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o == null || javaClass != o.javaClass) {
            return false
        }

        val that = o as SubscriptionEntity

        if (uid != that.uid) {
            return false
        }
        if (serviceId != that.serviceId) {
            return false
        }
        if (url != that.url) {
            return false
        }
        if (if (name != null) name != that.name else that.name != null) {
            return false
        }
        if (if (avatarUrl != null) avatarUrl != that.avatarUrl else that.avatarUrl != null) {
            return false
        }
        if (if (subscriberCount != null
                ) subscriberCount != that.subscriberCount
                else that.subscriberCount != null) {
            return false
        }
        return if (description != null
        ) description == that.description else that.description == null
    }

    override fun hashCode(): Int {
        var result = (uid xor (uid ushr 32)).toInt()
        result = 31 * result + serviceId
        result = 31 * result + url.hashCode()
        result = 31 * result + (if (name != null) name.hashCode() else 0)
        result = 31 * result + (if (avatarUrl != null) avatarUrl.hashCode() else 0)
        result = 31 * result + (if (subscriberCount != null) subscriberCount.hashCode() else 0)
        result = 31 * result + (if (description != null) description.hashCode() else 0)
        return result
    }

    companion object {
        const val SUBSCRIPTION_UID: String = "uid"
        const val SUBSCRIPTION_TABLE: String = "subscriptions"
        const val SUBSCRIPTION_SERVICE_ID: String = "service_id"
        const val SUBSCRIPTION_URL: String = "url"
        const val SUBSCRIPTION_NAME: String = "name"
        const val SUBSCRIPTION_AVATAR_URL: String = "avatar_url"
        const val SUBSCRIPTION_SUBSCRIBER_COUNT: String = "subscriber_count"
        const val SUBSCRIPTION_DESCRIPTION: String = "description"
        const val SUBSCRIPTION_NOTIFICATION_MODE: String = "notification_mode"

        @JvmStatic
        @Ignore
        fun from(info: ChannelInfo): SubscriptionEntity {
            val result = SubscriptionEntity()
            result.serviceId = info.serviceId
            result.url = info.url
            result.setData(info.name, imageListToDbUrl(info.avatars),
                info.description, info.subscriberCount)
            return result
        }
    }
}
