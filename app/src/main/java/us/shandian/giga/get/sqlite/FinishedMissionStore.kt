package us.shandian.giga.get.sqlite

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log
import org.schabi.newpipe.streams.io.StoredFileHelper
import us.shandian.giga.get.DownloadMission
import us.shandian.giga.get.FinishedMission
import us.shandian.giga.get.Mission
import java.io.File
import java.util.*

/**
 * SQLite helper to store finished [us.shandian.giga.get.FinishedMission]'s
 */
class FinishedMissionStore(private val context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(MISSIONS_CREATE_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var oldVersion = oldVersion
        if (oldVersion == 2) {
            db.execSQL("ALTER TABLE " + MISSIONS_TABLE_NAME_v2 + " ADD COLUMN " + KEY_KIND + " TEXT;")
            oldVersion++
        }

        if (oldVersion == 3) {
            val KEY_LOCATION = "location"
            val KEY_NAME = "name"

            db.execSQL(MISSIONS_CREATE_TABLE)

            val cursor = db.query(MISSIONS_TABLE_NAME_v2, null, null,
                null, null, null, KEY_TIMESTAMP)

            val count = cursor.count
            if (count > 0) {
                db.beginTransaction()
                while (cursor.moveToNext()) {
                    val values = ContentValues()
                    val ks = cursor.getColumnIndex(KEY_SOURCE)
                    if (ks >= 0) values.put(KEY_SOURCE, cursor.getString(ks))
                    val kd = cursor.getColumnIndex(KEY_DONE)
                    if (kd >= 0) values.put(KEY_DONE, cursor.getString(kd))
                    val kt = cursor.getColumnIndex(KEY_TIMESTAMP)
                    values.put(KEY_TIMESTAMP, cursor.getLong(kt))
                    val kk = cursor.getColumnIndex(KEY_KIND)
                    if (kk >= 0) values.put(KEY_KIND, cursor.getString(kk))
                    val kl = cursor.getColumnIndex(KEY_LOCATION)
                    val kn = cursor.getColumnIndex(KEY_NAME)
                    if (kl >= 0 && kn >=0) values.put(KEY_PATH, Uri.fromFile(
                        File(cursor.getString(kl), cursor.getString(kn))
                    ).toString())

                    db.insert(FINISHED_TABLE_NAME, null, values)
                }
                db.setTransactionSuccessful()
                db.endTransaction()
            }

            cursor.close()
            db.execSQL("DROP TABLE " + MISSIONS_TABLE_NAME_v2)
        }
    }

    /**
     * Returns all values of the download mission as ContentValues.
     *
     * @param downloadMission the download mission
     * @return the content values
     */
    private fun getValuesOfMission(downloadMission: Mission): ContentValues {
        val values = ContentValues()
        values.put(KEY_SOURCE, downloadMission.source)
        values.put(KEY_PATH, downloadMission.storage!!.uri.toString())
        values.put(KEY_DONE, downloadMission.length)
        values.put(KEY_TIMESTAMP, downloadMission.timestamp)
        values.put(KEY_KIND, downloadMission.kind.toString())
        return values
    }

    private fun getMissionFromCursor(cursor: Cursor): FinishedMission {
        var kind: String? = null
        val kk = cursor.getColumnIndex(KEY_KIND)
        if (kk >= 0) kind = Objects.requireNonNull(cursor).getString(kk)
        if (kind.isNullOrEmpty()) kind = "?"

        val path = cursor.getString(cursor.getColumnIndexOrThrow(KEY_PATH))

        val mission = FinishedMission()

        mission.source = cursor.getString(cursor.getColumnIndexOrThrow(KEY_SOURCE))
        mission.length = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_DONE))
        mission.timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_TIMESTAMP))
        mission.kind = kind[0]

        try {
            mission.storage = StoredFileHelper(context, null, Uri.parse(path), "")
        } catch (e: Exception) {
            Log.e("FinishedMissionStore", "failed to load the storage path of: $path", e)
            mission.storage = StoredFileHelper(null, path, "", "")
        }

        return mission
    }


    //////////////////////////////////
    // Data source methods
    ///////////////////////////////////
    fun loadFinishedMissions(): ArrayList<FinishedMission> {
        val database = readableDatabase
        val cursor = database.query(FINISHED_TABLE_NAME, null, null,
            null, null, null, KEY_TIMESTAMP + " DESC")

        val count = cursor.count
        if (count == 0) return ArrayList(1)

        val result = ArrayList<FinishedMission>(count)
        while (cursor.moveToNext()) {
            result.add(getMissionFromCursor(cursor))
        }

        return result
    }

    fun addFinishedMission(downloadMission: DownloadMission) {
        val values = getValuesOfMission(Objects.requireNonNull(downloadMission))
        val database = writableDatabase
        database.insert(FINISHED_TABLE_NAME, null, values)
    }

    fun deleteMission(mission: Mission) {
        val ts = Objects.requireNonNull(mission).timestamp.toString()

        val database = writableDatabase

        if (mission is FinishedMission) {
            if (mission.storage!!.isInvalid) {
                database.delete(FINISHED_TABLE_NAME, KEY_TIMESTAMP + " = ?", arrayOf(ts))
            } else {
                database.delete(FINISHED_TABLE_NAME,
                    KEY_TIMESTAMP + " = ? AND " + KEY_PATH + " = ?",
                    arrayOf(ts, mission.storage!!.uri.toString()
                    ))
            }
        } else {
            throw UnsupportedOperationException("DownloadMission")
        }
    }

    fun updateMission(mission: Mission) {
        val values = getValuesOfMission(Objects.requireNonNull(mission))
        val database = writableDatabase
        val ts = mission.timestamp.toString()

        val rowsAffected: Int

        if (mission is FinishedMission) {
            rowsAffected = if (mission.storage!!.isInvalid) {
                database.update(FINISHED_TABLE_NAME,
                    values,
                    KEY_TIMESTAMP + " = ?",
                    arrayOf(ts))
            } else {
                database.update(FINISHED_TABLE_NAME,
                    values,
                    KEY_PATH + " = ?",
                    arrayOf(
                        mission.storage!!.uri.toString()
                    ))
            }
        } else {
            throw UnsupportedOperationException("DownloadMission")
        }

        if (rowsAffected != 1) {
            Log.e("FinishedMissionStore", "Expected 1 row to be affected by update but got $rowsAffected")
        }
    }

    companion object {
        // TODO: use NewPipeSQLiteHelper ('s constants) when playlist branch is merged (?)
        private const val DATABASE_NAME = "downloads.db"

        private const val DATABASE_VERSION = 4

        /**
         * The table name of download missions (old)
         */
        private const val MISSIONS_TABLE_NAME_v2 = "download_missions"

        /**
         * The table name of download missions
         */
        private const val FINISHED_TABLE_NAME = "finished_missions"

        /**
         * The key to the urls of a mission
         */
        private const val KEY_SOURCE = "url"


        /**
         * The key to the done.
         */
        private const val KEY_DONE = "bytes_downloaded"

        private const val KEY_TIMESTAMP = "timestamp"

        private const val KEY_KIND = "kind"

        private const val KEY_PATH = "path"

        /**
         * The statement to create the table
         */
        private const val MISSIONS_CREATE_TABLE = "CREATE TABLE " + FINISHED_TABLE_NAME + " (" +
                KEY_PATH + " TEXT NOT NULL, " +
                KEY_SOURCE + " TEXT NOT NULL, " +
                KEY_DONE + " INTEGER NOT NULL, " +
                KEY_TIMESTAMP + " INTEGER NOT NULL, " +
                KEY_KIND + " TEXT NOT NULL, " +
                " UNIQUE(" + KEY_TIMESTAMP + ", " + KEY_PATH + "));"
    }
}
