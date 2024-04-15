package org.schabi.newpipe

import android.content.Context
import androidx.room.Room.databaseBuilder
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.Migrations
import kotlin.concurrent.Volatile

object NewPipeDatabase {
    @Volatile
    private var databaseInstance: AppDatabase? = null

    private fun getDatabase(context: Context): AppDatabase {
        return databaseBuilder(context.applicationContext, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .addMigrations(Migrations.MIGRATION_1_2,
                Migrations.MIGRATION_2_3,
                Migrations.MIGRATION_3_4,
                Migrations.MIGRATION_4_5,
                Migrations.MIGRATION_5_6,
                Migrations.MIGRATION_6_7)
            .build()
    }

    @JvmStatic
    fun getInstance(context: Context): AppDatabase {
        var result = databaseInstance
        if (result == null) {
            synchronized(NewPipeDatabase::class.java) {
                result = databaseInstance
                if (result == null) {
                    databaseInstance = getDatabase(context)
                    result = databaseInstance
                }
            }
        }

        return result!!
    }

    fun checkpoint() {
        checkNotNull(databaseInstance) { "database is not initialized" }
        val c = databaseInstance!!.query("pragma wal_checkpoint(full)", null)
        if (c.moveToFirst() && c.getInt(0) == 1) {
            throw RuntimeException("Checkpoint was blocked from completing")
        }
    }

    fun close() {
        if (databaseInstance != null) {
            synchronized(NewPipeDatabase::class.java) {
                if (databaseInstance != null) {
                    databaseInstance!!.close()
                    databaseInstance = null
                }
            }
        }
    }
}
