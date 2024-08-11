package org.schabi.newpipe.giga.service

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Handler
import android.util.Log
import androidx.recyclerview.widget.DiffUtil
import org.schabi.newpipe.giga.io.StoredDirectoryHelper
import org.schabi.newpipe.giga.io.StoredFileHelper
import org.schabi.newpipe.giga.io.StoredFileHelper.Companion.deserialize
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.giga.get.DownloadMission
import org.schabi.newpipe.giga.get.FinishedMission
import org.schabi.newpipe.giga.get.Mission
import org.schabi.newpipe.giga.util.Utility
import java.io.File
import java.io.IOException
import java.util.*

class DownloadManager internal constructor(context: Context, handler: Handler, storageVideo: StoredDirectoryHelper?, storageAudio: StoredDirectoryHelper?) {
    enum class NetworkState {
        Unavailable, Operating, MeteredOperating
    }

    enum class MissionState {
        None, Pending, PendingRunning, Finished
    }

    private val mFinishedMissionStore: FinishedMissionStore

    private val mMissionsPending = ArrayList<DownloadMission>()
    private val mMissionsFinished: ArrayList<FinishedMission>

    private val mHandler: Handler
    private val mPendingMissionsDir: File?

    private var mLastNetworkStatus = NetworkState.Unavailable

    @JvmField
    var mPrefMaxRetry: Int = 0
    @JvmField
    var mPrefMeteredDownloads: Boolean = false
    @JvmField
    var mPrefQueueLimit: Boolean = false
    private var mSelfMissionsControl = false

    @JvmField
    var mMainStorageAudio: StoredDirectoryHelper?
    @JvmField
    var mMainStorageVideo: StoredDirectoryHelper?

    init {
        Logd(TAG, "new DownloadManager instance. 0x${Integer.toHexString(this.hashCode())}")
        mFinishedMissionStore = FinishedMissionStore(context)
        mHandler = handler
        mMainStorageAudio = storageAudio
        mMainStorageVideo = storageVideo
        mMissionsFinished = loadFinishedMissions()
        mPendingMissionsDir = getPendingDir(context)

        loadPendingMissions(context)
    }

    /**
     * Loads finished missions from the data source and forgets finished missions whose file does
     * not exist anymore.
     */
    private fun loadFinishedMissions(): ArrayList<FinishedMission> {
        val finishedMissions = mFinishedMissionStore.loadFinishedMissions()

        // check if the files exists, otherwise, forget the download
        for (i in finishedMissions.indices.reversed()) {
            val mission = finishedMissions[i]

            if (!mission.storage!!.existsAsFile()) {
                Logd(TAG, "downloaded file removed: " + mission.storage!!.name)

                mFinishedMissionStore.deleteMission(mission)
                finishedMissions.removeAt(i)
            }
        }

        return finishedMissions
    }

    private fun loadPendingMissions(ctx: Context) {
        val subs = mPendingMissionsDir!!.listFiles()
        if (subs == null) {
            Log.e(TAG, "listFiles() returned null")
            return
        }
        if (subs.isEmpty()) return
        Logd(TAG, "Loading pending downloads from directory: " + mPendingMissionsDir.absolutePath)
        val tempDir = pickAvailableTemporalDir(ctx)
        Log.i(TAG, "using '$tempDir' as temporal directory")

        for (sub in subs) {
            if (!sub.isFile) continue
            if (sub.name == ".tmp") continue

            val mis = Utility.readFromFile<DownloadMission>(sub)
            if (mis == null || mis.isFinished || mis.hasInvalidStorage()) {
                sub.delete()
                continue
            }

            mis.threads = arrayOfNulls(0)

            var exists: Boolean
            try {
                mis.storage = deserialize(mis.storage!!, ctx)
                exists = !mis.storage!!.isInvalid && mis.storage!!.existsAsFile()
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to load the file source of " + mis.storage.toString(), ex)
                mis.storage!!.invalidate()
                exists = false
            }

            when {
                mis.isPsRunning -> {
                    if (mis.psAlgorithm!!.worksOnSameFile) {
                        // Incomplete post-processing results in a corrupted download file
                        // because the selected algorithm works on the same file to save space.
                        // the file will be deleted if the storage API
                        // is Java IO (avoid showing the "Save as..." dialog)
                        if (exists && mis.storage!!.isDirect && !mis.storage!!.delete()) Log.w(TAG, "Unable to delete incomplete download file: " + sub.path)
                    }
                    mis.psState = 0
                    mis.errCode = DownloadMission.ERROR_POSTPROCESSING_STOPPED
                }
                !exists -> {
                    tryRecover(mis)
                    // the progress is lost, reset mission state
                    if (mis.isInitialized) mis.resetState(rollback = true, persistChanges = true, errorCode = DownloadMission.ERROR_PROGRESS_LOST)
                }
            }

            mis.psAlgorithm?.cleanupTemporalDir()
            mis.psAlgorithm?.setTemporalDir(tempDir!!)

            mis.metadata = sub
            mis.maxRetry = mPrefMaxRetry
            mis.mHandler = mHandler

            mMissionsPending.add(mis)
        }

//        if (mMissionsPending.size > 1) Collections.sort<DownloadMission>(mMissionsPending,
//            Comparator.comparingLong<Any>(Mission::timestamp))
        mMissionsPending.sortBy { it.timestamp }
    }

    /**
     * Start a new download mission
     *
     * @param mission the new download mission to add and run (if possible)
     */
    fun startMission(mission: DownloadMission) {
        synchronized(this) {
            mission.timestamp = System.currentTimeMillis()
            mission.mHandler = mHandler
            mission.maxRetry = mPrefMaxRetry

            // create metadata file
            while (true) {
                mission.metadata = File(mPendingMissionsDir, mission.timestamp.toString())
                if (!mission.metadata!!.isFile && !mission.metadata!!.exists()) {
                    try {
                        if (!mission.metadata!!.createNewFile()) throw RuntimeException("Cant create download metadata file")
                    } catch (e: IOException) {
                        throw RuntimeException(e)
                    }
                    break
                }
                mission.timestamp = System.currentTimeMillis()
            }

            mSelfMissionsControl = true
            mMissionsPending.add(mission)

            // Before continue, save the metadata in case the internet connection is not available
            Utility.writeToFile(mission.metadata!!, mission)

            if (mission.storage == null) {
                // noting to do here
                mission.errCode = DownloadMission.ERROR_FILE_CREATION
                if (mission.errObject != null) mission.errObject = IOException("DownloadMission.storage == NULL")
                return
            }

            val start = !mPrefQueueLimit || runningMissionsCount < 1
            if (canDownloadInCurrentNetwork() && start) {
                mission.start()
            }
        }
    }


    fun resumeMission(mission: DownloadMission) {
        if (!mission.running) mission.start()
    }

    fun pauseMission(mission: DownloadMission) {
        if (mission.running) {
            mission.setEnqueued(false)
            mission.pause()
        }
    }

    fun deleteMission(mission: Mission) {
        synchronized(this) {
            when (mission) {
                is DownloadMission -> mMissionsPending.remove(mission)
                is FinishedMission -> {
                    mMissionsFinished.remove(mission)
                    mFinishedMissionStore.deleteMission(mission)
                }
            }
            mission.delete()
        }
    }

    fun forgetMission(storage: StoredFileHelper) {
        synchronized(this) {
            val mission = getAnyMission(storage) ?: return
            when (mission) {
                is DownloadMission -> mMissionsPending.remove(mission)
                is FinishedMission -> {
                    mMissionsFinished.remove(mission)
                    mFinishedMissionStore.deleteMission(mission)
                }
            }
            mission.storage = null
            mission.delete()
        }
    }

    fun tryRecover(mission: DownloadMission) {
        val mainStorage = getMainStorage(mission.storage!!.tag!!)
        if (!mission.storage!!.isInvalid && mission.storage!!.create()) return
        // using javaIO cannot recreate the file
        // using SAF in older devices (no tree available)
        //
        // force the user to pick again the save path
        mission.storage!!.invalidate()
        if (mainStorage == null) return
        // if the user has changed the save path before this download, the original save path will be lost
        val newStorage = mainStorage.createFile(mission.storage!!.name!!, mission.storage!!.type!!)
        if (newStorage != null) mission.storage = newStorage
    }

    /**
     * Get a pending mission by its path
     *
     * @param storage where the file possible is stored
     * @return the mission or null if no such mission exists
     */
    private fun getPendingMission(storage: StoredFileHelper): DownloadMission? {
        for (mission in mMissionsPending) {
            if (mission.storage!!.equals(storage)) return mission
        }
        return null
    }

    /**
     * Get the index into [.mMissionsFinished] of a finished mission by its path, return
     * `-1` if there is no such mission. This function also checks if the matched mission's
     * file exists, and, if it does not, the related mission is forgotten about (like in [ ][.loadFinishedMissions]) and `-1` is returned.
     *
     * @param storage where the file would be stored
     * @return the mission index or -1 if no such mission exists
     */
    private fun getFinishedMissionIndex(storage: StoredFileHelper): Int {
        for (i in mMissionsFinished.indices) {
            if (mMissionsFinished[i].storage!!.equals(storage)) {
                // If the file does not exist the mission is not valid anymore. Also checking if
                // length == 0 since the file picker may create an empty file before yielding it,
                // but that does not mean the file really belonged to a previous mission.
                if (!storage.existsAsFile() || storage.length() == 0L) {
                    Logd(TAG, "matched downloaded file removed: ${storage.name}")
                    mFinishedMissionStore.deleteMission(mMissionsFinished[i])
                    mMissionsFinished.removeAt(i)
                    return -1 // finished mission whose associated file was removed
                }
                return i
            }
        }

        return -1
    }

    private fun getAnyMission(storage: StoredFileHelper): Mission? {
        synchronized(this) {
            val mission: Mission? = getPendingMission(storage)
            if (mission != null) return mission

            val idx = getFinishedMissionIndex(storage)
            if (idx >= 0) return mMissionsFinished[idx]
        }

        return null
    }

    val runningMissionsCount: Int
        get() {
            var count = 0
            synchronized(this) {
                for (mission in mMissionsPending) {
                    if (mission.running && !mission.isPsFailed && !mission.isFinished) count++
                }
            }

            return count
        }

    fun pauseAllMissions(force: Boolean) {
        synchronized(this) {
            for (mission in mMissionsPending) {
                if (!mission.running || mission.isPsRunning || mission.isFinished) continue
                if (force) {
                    // avoid waiting for threads
                    mission.init = null
                    mission.threads = arrayOfNulls(0)
                }
                mission.pause()
            }
        }
    }

    fun startAllMissions() {
        synchronized(this) {
            for (mission in mMissionsPending) {
                if (mission.running || mission.isCorrupt) continue
                mission.start()
            }
        }
    }

    /**
     * Set a pending download as finished
     *
     * @param mission the desired mission
     */
    fun setFinished(mission: DownloadMission) {
        synchronized(this) {
            mMissionsPending.remove(mission)
            mMissionsFinished.add(0, FinishedMission(mission))
            mFinishedMissionStore.addFinishedMission(mission)
        }
    }

    /**
     * runs one or multiple missions in from queue if possible
     *
     * @return true if one or multiple missions are running, otherwise, false
     */
    fun runMissions(): Boolean {
        synchronized(this) {
            if (mMissionsPending.size < 1) return false
            if (!canDownloadInCurrentNetwork()) return false

            if (mPrefQueueLimit)
                for (mission in mMissionsPending) {
                    if (!mission.isFinished && mission.running) return true
                }

            var flag = false
            for (mission in mMissionsPending) {
                if (mission.running || !mission.enqueued || mission.isFinished) continue
                resumeMission(mission)
                if (mission.errCode != DownloadMission.ERROR_NOTHING) continue

                if (mPrefQueueLimit) return true
                flag = true
            }
            return flag
        }
    }

    val iterator: MissionIterator
        get() {
            mSelfMissionsControl = true
            return MissionIterator()
        }

    /**
     * Forget all finished downloads, but, doesn't delete any file
     */
    fun forgetFinishedDownloads() {
        synchronized(this) {
            for (mission in mMissionsFinished) {
                mFinishedMissionStore.deleteMission(mission)
            }
            mMissionsFinished.clear()
        }
    }

    private fun canDownloadInCurrentNetwork(): Boolean {
        if (mLastNetworkStatus == NetworkState.Unavailable) return false
        return !(mPrefMeteredDownloads && mLastNetworkStatus == NetworkState.MeteredOperating)
    }

    fun handleConnectivityState(currentStatus: NetworkState, updateOnly: Boolean) {
        if (currentStatus == mLastNetworkStatus) return
        mLastNetworkStatus = currentStatus
        if (currentStatus == NetworkState.Unavailable) return
        if (!mSelfMissionsControl || updateOnly) return  // don't touch anything without the user interaction
        val isMetered = mPrefMeteredDownloads && mLastNetworkStatus == NetworkState.MeteredOperating

        synchronized(this) {
            for (mission in mMissionsPending) {
                if (mission.isCorrupt || mission.isPsRunning) continue

                when {
                    mission.running && isMetered -> mission.pause()
                    !mission.running && !isMetered && mission.enqueued -> {
                        mission.start()
                        if (mPrefQueueLimit) break
                    }
                }
            }
        }
    }

    fun updateMaximumAttempts() {
        synchronized(this) {
            for (mission in mMissionsPending) mission.maxRetry = mPrefMaxRetry
        }
    }

    fun checkForExistingMission(storage: StoredFileHelper): MissionState {
        synchronized(this) {
            val pending = getPendingMission(storage)
            if (pending == null) {
                if (getFinishedMissionIndex(storage) >= 0) return MissionState.Finished
            } else {
                // this never should happen (race-condition)
                return if (pending.isFinished) MissionState.Finished
                else {
                    if (pending.running) MissionState.PendingRunning
                    else MissionState.Pending
                }
            }
        }
        return MissionState.None
    }

    private fun getMainStorage(tag: String): StoredDirectoryHelper? {
        if (tag == TAG_AUDIO) return mMainStorageAudio
        if (tag == TAG_VIDEO) return mMainStorageVideo
        Log.w(TAG, "Unknown download category, not [audio video]: $tag")
        return null // this never should happen
    }

    inner class MissionIterator internal constructor() : DiffUtil.Callback() {
        private val FINISHED: Any = Any()
        private val PENDING: Any = Any()

        private var snapshot: ArrayList<Any>?
        var current: ArrayList<Any>? = null
        private var hidden: ArrayList<Mission> = ArrayList(2)

        private var hasFinished: Boolean = false

        init {
            snapshot = specialItems
        }

        private val specialItems: ArrayList<Any>
            get() {
                synchronized(this@DownloadManager) {
                    val pending = ArrayList<Mission>(mMissionsPending)
                    val finished = ArrayList<Mission>(mMissionsFinished)
                    val remove: MutableList<Mission> = ArrayList(hidden)

                    // hide missions (if required)
                    remove.removeIf { mission: Mission -> pending.remove(mission) || finished.remove(mission) }

                    var fakeTotal = pending.size
                    if (fakeTotal > 0) fakeTotal++
                    fakeTotal += finished.size
                    if (finished.size > 0) fakeTotal++
                    val list = ArrayList<Any>(fakeTotal)
                    if (pending.size > 0) {
                        list.add(PENDING)
                        list.addAll(pending)
                    }
                    if (finished.size > 0) {
                        list.add(FINISHED)
                        list.addAll(finished)
                    }
                    hasFinished = finished.size > 0
                    return list
                }
            }

        fun getItem(position: Int): MissionItem {
            val `object` = snapshot!![position]
            if (`object` === PENDING) return MissionItem(SPECIAL_PENDING)
            if (`object` === FINISHED) return MissionItem(SPECIAL_FINISHED)
            return MissionItem(SPECIAL_NOTHING, `object` as Mission)
        }

        fun getSpecialAtItem(position: Int): Int {
            val `object` = snapshot!![position]
            if (`object` === PENDING) return SPECIAL_PENDING
            if (`object` === FINISHED) return SPECIAL_FINISHED
            return SPECIAL_NOTHING
        }


        fun start() {
            current = specialItems
        }

        fun end() {
            snapshot = current
            current = null
        }

        fun hide(mission: Mission) {
            hidden.add(mission)
        }

        fun unHide(mission: Mission) {
            hidden.remove(mission)
        }

        fun hasFinishedMissions(): Boolean {
            return hasFinished
        }

        /**
         * Check if exists missions running and paused. Corrupted and hidden missions are not counted
         *
         * @return two-dimensional array contains the current missions state.
         * 1° entry: true if has at least one mission running
         * 2° entry: true if has at least one mission paused
         */
        fun hasValidPendingMissions(): BooleanArray {
            var running = false
            var paused = false
            synchronized(this@DownloadManager) {
                for (mission in mMissionsPending) {
                    if (hidden.contains(mission) || mission.isCorrupt) continue
                    if (mission.running) running = true
                    else paused = true
                }
            }
            return booleanArrayOf(running, paused)
        }


        override fun getOldListSize(): Int {
            return snapshot!!.size
        }

        override fun getNewListSize(): Int {
            return current!!.size
        }

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return snapshot!![oldItemPosition] === current!![newItemPosition]
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val x = snapshot!![oldItemPosition]
            val y = current!![newItemPosition]
            if (x is Mission && y is Mission) return x.storage!!.equals(y.storage!!)
            return false
        }
    }

    class MissionItem @JvmOverloads internal constructor(@JvmField var special: Int, @JvmField var mission: Mission? = null)

    /**
     * SQLite helper to store finished [org.schabi.newpipe.giga.get.FinishedMission]'s
     */
    class FinishedMissionStore(private val context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(MISSIONS_CREATE_TABLE)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            var oldVersion = oldVersion
            if (oldVersion == 2) {
                db.execSQL("ALTER TABLE $MISSIONS_TABLE_NAME_v2 ADD COLUMN $KEY_KIND TEXT;")
                oldVersion++
            }

            if (oldVersion == 3) {
                val KEY_LOCATION = "location"
                val KEY_NAME = "name"

                db.execSQL(MISSIONS_CREATE_TABLE)

                val cursor = db.query(MISSIONS_TABLE_NAME_v2, null, null, null, null, null, KEY_TIMESTAMP)

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
                        if (kl >= 0 && kn >=0) values.put(KEY_PATH, Uri.fromFile(File(cursor.getString(kl), cursor.getString(kn))).toString())

                        db.insert(FINISHED_TABLE_NAME, null, values)
                    }
                    db.setTransactionSuccessful()
                    db.endTransaction()
                }
                cursor.close()
                db.execSQL("DROP TABLE $MISSIONS_TABLE_NAME_v2")
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
                null, null, null, "$KEY_TIMESTAMP DESC")

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
                if (mission.storage!!.isInvalid) database.delete(FINISHED_TABLE_NAME, "$KEY_TIMESTAMP = ?", arrayOf(ts))
                else database.delete(FINISHED_TABLE_NAME, "$KEY_TIMESTAMP = ? AND $KEY_PATH = ?",
                    arrayOf(ts, mission.storage!!.uri.toString()))
            } else throw UnsupportedOperationException("DownloadMission")
        }

        fun updateMission(mission: Mission) {
            val values = getValuesOfMission(Objects.requireNonNull(mission))
            val database = writableDatabase
            val ts = mission.timestamp.toString()

            val rowsAffected: Int
            if (mission is FinishedMission) {
                rowsAffected = if (mission.storage!!.isInvalid) database.update(FINISHED_TABLE_NAME, values, "$KEY_TIMESTAMP = ?", arrayOf(ts))
                else database.update(FINISHED_TABLE_NAME, values, "$KEY_PATH = ?", arrayOf(mission.storage!!.uri.toString()))
            } else throw UnsupportedOperationException("DownloadMission")

            if (rowsAffected != 1) Log.e("FinishedMissionStore", "Expected 1 row to be affected by update but got $rowsAffected")
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

    companion object {
        private val TAG: String = DownloadManager::class.java.simpleName

        const val SPECIAL_NOTHING: Int = 0
        const val SPECIAL_PENDING: Int = 1
        const val SPECIAL_FINISHED: Int = 2

        const val TAG_AUDIO: String = "audio"
        const val TAG_VIDEO: String = "video"
        private const val DOWNLOADS_METADATA_FOLDER = "pending_downloads"

        private fun getPendingDir(context: Context): File? {
            var dir = context.getExternalFilesDir(DOWNLOADS_METADATA_FOLDER)
            if (testDir(dir)) return dir

            dir = File(context.filesDir, DOWNLOADS_METADATA_FOLDER)
            if (testDir(dir)) return dir

            throw RuntimeException("path to pending downloads are not accessible")
        }

        private fun testDir(dir: File?): Boolean {
            if (dir == null) return false

            try {
                if (!Utility.mkdir(dir, false)) {
                    Log.e(TAG, "testDir() cannot create the directory in path: " + dir.absolutePath)
                    return false
                }

                val tmp = File(dir, ".tmp")
                if (!tmp.createNewFile()) return false
                return tmp.delete() // if the file was created, SHOULD BE deleted too
            } catch (e: Exception) {
                Log.e(TAG, "testDir() failed: " + dir.absolutePath, e)
                return false
            }
        }

        private fun isDirectoryAvailable(directory: File?): Boolean {
            return directory != null && directory.canWrite() && directory.exists()
        }

        @JvmStatic
        fun pickAvailableTemporalDir(ctx: Context): File? {
            var dir = ctx.getExternalFilesDir(null)
            if (isDirectoryAvailable(dir)) return dir

            dir = ctx.filesDir
            if (isDirectoryAvailable(dir)) return dir

            // this never should happen
            dir = ctx.getDir("muxing_tmp", Context.MODE_PRIVATE)
            if (isDirectoryAvailable(dir)) return dir

            // fallback to cache dir
            dir = ctx.cacheDir
            if (isDirectoryAvailable(dir)) return dir

            throw RuntimeException("Not temporal directories are available")
        }
    }
}
