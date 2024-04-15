package org.schabi.newpipe.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import org.schabi.newpipe.database.Migrations.DB_VER_7
import org.schabi.newpipe.database.feed.dao.FeedDAO
import org.schabi.newpipe.database.feed.dao.FeedGroupDAO
import org.schabi.newpipe.database.feed.model.FeedEntity
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.feed.model.FeedGroupSubscriptionEntity
import org.schabi.newpipe.database.feed.model.FeedLastUpdatedEntity
import org.schabi.newpipe.database.history.dao.SearchHistoryDAO
import org.schabi.newpipe.database.history.dao.StreamHistoryDAO
import org.schabi.newpipe.database.history.model.SearchHistoryEntry
import org.schabi.newpipe.database.history.model.StreamHistoryEntity
import org.schabi.newpipe.database.playlist.dao.PlaylistDAO
import org.schabi.newpipe.database.playlist.dao.PlaylistRemoteDAO
import org.schabi.newpipe.database.playlist.dao.PlaylistStreamDAO
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.dao.StreamDAO
import org.schabi.newpipe.database.stream.dao.StreamStateDAO
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.database.subscription.SubscriptionDAO
import org.schabi.newpipe.database.subscription.SubscriptionEntity

@TypeConverters(Converters::class)
@Database(entities = [SubscriptionEntity::class, SearchHistoryEntry::class, StreamEntity::class, StreamHistoryEntity::class, StreamStateEntity::class, PlaylistEntity::class, PlaylistStreamEntity::class, PlaylistRemoteEntity::class, FeedEntity::class, FeedGroupEntity::class, FeedGroupSubscriptionEntity::class, FeedLastUpdatedEntity::class
], version = DB_VER_7)
abstract class AppDatabase : RoomDatabase() {
    abstract fun searchHistoryDAO(): SearchHistoryDAO

    abstract fun streamDAO(): StreamDAO

    abstract fun streamHistoryDAO(): StreamHistoryDAO

    abstract fun streamStateDAO(): StreamStateDAO

    abstract fun playlistDAO(): PlaylistDAO

    abstract fun playlistStreamDAO(): PlaylistStreamDAO

    abstract fun playlistRemoteDAO(): PlaylistRemoteDAO

    abstract fun feedDAO(): FeedDAO

    abstract fun feedGroupDAO(): FeedGroupDAO

    abstract fun subscriptionDAO(): SubscriptionDAO

    companion object {
        const val DATABASE_NAME: String = "newpipe.db"
    }
}
