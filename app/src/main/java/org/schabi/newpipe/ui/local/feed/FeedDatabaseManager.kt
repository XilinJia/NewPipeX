package org.schabi.newpipe.ui.local.feed

import android.content.Context
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.schedulers.Schedulers
//import org.schabi.newpipe.MainActivity.DEBUG
import org.schabi.newpipe.database.NewPipeDatabase
import org.schabi.newpipe.database.feed.model.FeedEntity
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.feed.model.FeedLastUpdatedEntity
import org.schabi.newpipe.database.stream.StreamWithState
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.subscription.NotificationMode
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.ui.local.subscription.FeedGroupIcon
import org.schabi.newpipe.util.Logd
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class FeedDatabaseManager(context: Context) {
    private val database = NewPipeDatabase.getInstance(context)
    private val feedTable = database.feedDAO()
    private val feedGroupTable = database.feedGroupDAO()
    private val streamTable = database.streamDAO()

    companion object {
        /**
         * Only items that are newer than this will be saved.
         */
        val FEED_OLDEST_ALLOWED_DATE: OffsetDateTime =
            LocalDate.now().minusWeeks(13).atStartOfDay().atOffset(ZoneOffset.UTC)
    }

    fun groups() = feedGroupTable.getAll()

    fun database() = database

    fun getStreams(
            groupId: Long,
            includePlayedStreams: Boolean,
            includePartiallyPlayedStreams: Boolean,
            includeFutureStreams: Boolean,
    ): Maybe<List<StreamWithState>>? {
        return feedTable?.getStreams(
            groupId,
            includePlayedStreams,
            includePartiallyPlayedStreams,
            if (includeFutureStreams) null else OffsetDateTime.now(),
        )
    }

    fun outdatedSubscriptions(outdatedThreshold: OffsetDateTime) = feedTable?.getAllOutdated(outdatedThreshold)

    fun outdatedSubscriptionsWithNotificationMode(
            outdatedThreshold: OffsetDateTime,
            @NotificationMode notificationMode: Int,
    ) = feedTable?.getOutdatedWithNotificationMode(outdatedThreshold, notificationMode)

    fun notLoadedCount(groupId: Long = FeedGroupEntity.GROUP_ALL_ID): Flowable<Long> {
        if (feedTable == null) return Flowable.empty()
        return when (groupId) {
            FeedGroupEntity.GROUP_ALL_ID -> feedTable.notLoadedCount()
            else -> feedTable.notLoadedCountForGroup(groupId)
        }
    }

    fun outdatedSubscriptionsForGroup(
            groupId: Long = FeedGroupEntity.GROUP_ALL_ID,
            outdatedThreshold: OffsetDateTime,
    ) = feedTable?.getAllOutdatedForGroup(groupId, outdatedThreshold)

    fun markAsOutdated(subscriptionId: Long) =
        feedTable?.setLastUpdatedForSubscription(FeedLastUpdatedEntity(subscriptionId, null))

    fun doesStreamExist(stream: StreamInfoItem): Boolean {
        return streamTable?.exists(stream.serviceId, stream.url) ?: false
    }

    fun upsertAll(
            subscriptionId: Long,
            items: List<StreamInfoItem>,
            oldestAllowedDate: OffsetDateTime = FEED_OLDEST_ALLOWED_DATE,
    ) {
        val itemsToInsert = ArrayList<StreamInfoItem>()
        loop@ for (streamItem in items) {
            val uploadDate = streamItem.uploadDate

            itemsToInsert +=
                when {
                    uploadDate == null && streamItem.streamType == StreamType.LIVE_STREAM -> streamItem
                    uploadDate != null && uploadDate.offsetDateTime() >= oldestAllowedDate -> streamItem
                    else -> continue@loop
                }
        }

        feedTable?.unlinkOldLivestreams(subscriptionId)

        if (itemsToInsert.isNotEmpty()) {
            val streamEntities = itemsToInsert.map { StreamEntity(it) }
            val streamIds = streamTable?.upsertAll(streamEntities) ?: listOf()
            val feedEntities = streamIds.map { FeedEntity(it, subscriptionId) }

            feedTable?.insertAll(feedEntities)
        }

        feedTable?.setLastUpdatedForSubscription(
            FeedLastUpdatedEntity(subscriptionId, OffsetDateTime.now(ZoneOffset.UTC)),
        )
    }

    fun removeOrphansOrOlderStreams(oldestAllowedDate: OffsetDateTime = FEED_OLDEST_ALLOWED_DATE) {
        feedTable?.unlinkStreamsOlderThan(oldestAllowedDate)
        streamTable?.deleteOrphans()
    }

    fun clear() {
        feedTable?.deleteAll()
        val deletedOrphans = streamTable?.deleteOrphans() ?: 0

        Logd(this::class.java.simpleName, "clear() → streamTable.deleteOrphans() → $deletedOrphans")
    }

    // /////////////////////////////////////////////////////////////////////////
    // Feed Groups
    // /////////////////////////////////////////////////////////////////////////

    fun subscriptionIdsForGroup(groupId: Long): Flowable<List<Long>> {
        return feedGroupTable.getSubscriptionIdsFor(groupId)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun updateSubscriptionsForGroup(groupId: Long, subscriptionIds: List<Long>): Completable {
        return Completable
            .fromCallable { feedGroupTable.updateSubscriptionsForGroup(groupId, subscriptionIds) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun createGroup(name: String, icon: FeedGroupIcon): Maybe<Long> {
        return Maybe.fromCallable { feedGroupTable.insert(FeedGroupEntity(0, name, icon)) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun getGroup(groupId: Long): Maybe<FeedGroupEntity> {
        return feedGroupTable.getGroup(groupId)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun updateGroup(feedGroupEntity: FeedGroupEntity): Completable {
        return Completable.fromCallable { feedGroupTable.update(feedGroupEntity) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun deleteGroup(groupId: Long): Completable {
        return Completable.fromCallable { feedGroupTable.delete(groupId) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun updateGroupsOrder(groupIdList: List<Long>): Completable {
        var index = 0L
        val orderMap = groupIdList.associateBy({ it }, { index++ })

        return Completable.fromCallable { feedGroupTable.updateOrder(orderMap) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun oldestSubscriptionUpdate(groupId: Long): Flowable<List<OffsetDateTime>> {
        if (feedTable == null) return Flowable.empty()
        return when (groupId) {
            FeedGroupEntity.GROUP_ALL_ID -> feedTable.oldestSubscriptionUpdateFromAll()
            else -> feedTable.oldestSubscriptionUpdate(groupId)
        }
    }
}
