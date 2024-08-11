package org.schabi.newpipe.giga.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.*
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.WifiLock
import android.os.*
import android.os.PowerManager.WakeLock
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.collection.SparseArrayCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.preference.PreferenceManager
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.R
import org.schabi.newpipe.ui.download.DownloadActivity
import org.schabi.newpipe.giga.io.StoredDirectoryHelper
import org.schabi.newpipe.giga.io.StoredFileHelper
import org.schabi.newpipe.util.Localization.downloadCount
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.giga.get.DownloadMission
import org.schabi.newpipe.giga.get.MissionRecoveryInfo
import org.schabi.newpipe.giga.postprocessing.Postprocessing.Companion.getAlgorithm
import org.schabi.newpipe.giga.service.DownloadManager.Companion.pickAvailableTemporalDir
import java.io.File
import java.io.IOException
import java.util.*

class DownloadManagerService : Service() {
    private lateinit var mBinder: DownloadManagerBinder
    private lateinit var mManager: DownloadManager
    private lateinit var mNotification: Notification
    private lateinit var mPrefs: SharedPreferences
    private lateinit var mNetworkStateListenerL: ConnectivityManager.NetworkCallback
    private lateinit var mLock: LockManager

    private var icLauncher: Bitmap? = null

    private var mHandler: Handler? = null
    private var mNotificationManager: NotificationManager? = null
    private var mConnectivityManager: ConnectivityManager? = null

    private var mForeground = false
    private var mDownloadNotificationEnable = true

    private var downloadDoneCount = 0
    private var downloadDoneNotification: NotificationCompat.Builder? = null
    private var downloadDoneList: StringBuilder? = null

    private val mEchoObservers: MutableList<Handler.Callback> = ArrayList(1)

    private val mPrefChangeListener = OnSharedPreferenceChangeListener { prefs: SharedPreferences?, key: String? ->
        this.handlePreferenceChange(prefs, key!!)
    }

    private var mLockAcquired = false

    private var downloadFailedNotificationID = DOWNLOADS_NOTIFICATION_ID + 1
    private var downloadFailedNotification: NotificationCompat.Builder? = null
    private val mFailedDownloads = SparseArrayCompat<DownloadMission>(5)

    private var icDownloadDone: Bitmap? = null
    private var icDownloadFailed: Bitmap? = null

    private var mOpenDownloadList: PendingIntent? = null

    /**
     * notify media scanner on downloaded media file ...
     *
     * @param file the downloaded file uri
     */
    private fun notifyMediaScanner(file: Uri) {
        sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, file))
    }

    override fun onCreate() {
        super.onCreate()
        Logd(TAG, "onCreate")

        mBinder = DownloadManagerBinder()
        mHandler = Handler { msg: Message -> this.handleMessage(msg) }

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this)

        mManager = DownloadManager(this, mHandler!!, loadMainVideoStorage(), loadMainAudioStorage())

        val openDownloadListIntent = Intent(this, DownloadActivity::class.java).setAction(Intent.ACTION_MAIN)

        mOpenDownloadList = PendingIntentCompat.getActivity(this, 0, openDownloadListIntent, PendingIntent.FLAG_UPDATE_CURRENT, false)

        icLauncher = BitmapFactory.decodeResource(this.resources, R.mipmap.ic_launcher)

        val builder = NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
            .setContentIntent(mOpenDownloadList)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setLargeIcon(icLauncher)
            .setContentTitle(getString(R.string.msg_running))
            .setContentText(getString(R.string.msg_running_detail))

        mNotification = builder.build()

        mNotificationManager = ContextCompat.getSystemService(this, NotificationManager::class.java)
        mConnectivityManager = ContextCompat.getSystemService(this, ConnectivityManager::class.java)

        mNetworkStateListenerL = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handleConnectivityState(false)
            }
            override fun onLost(network: Network) {
                handleConnectivityState(false)
            }
        }
        mConnectivityManager!!.registerNetworkCallback(NetworkRequest.Builder().build(), mNetworkStateListenerL)

        mPrefs.registerOnSharedPreferenceChangeListener(mPrefChangeListener)

        handlePreferenceChange(mPrefs, getString(R.string.downloads_cross_network))
        handlePreferenceChange(mPrefs, getString(R.string.downloads_maximum_retry))
        handlePreferenceChange(mPrefs, getString(R.string.downloads_queue_limit))

        mLock = LockManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logd(TAG, if (intent == null) "Restarting" else "Starting")

        if (intent == null) return START_NOT_STICKY

        Log.i(TAG, "Got intent: $intent")
        val action = intent.action
        if (action != null) {
            if (action == Intent.ACTION_RUN) {
                mHandler!!.post { startMission(intent) }
            } else if (downloadDoneNotification != null) {
                if (action == ACTION_RESET_DOWNLOAD_FINISHED || action == ACTION_OPEN_DOWNLOADS_FINISHED) {
                    downloadDoneCount = 0
                    downloadDoneList!!.setLength(0)
                }
                if (action == ACTION_OPEN_DOWNLOADS_FINISHED) {
                    startActivity(Intent(this, DownloadActivity::class.java)
                        .setAction(Intent.ACTION_MAIN)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Logd(TAG, "Destroying")

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

        if (mNotificationManager != null && downloadDoneNotification != null) {
            downloadDoneNotification!!.setDeleteIntent(null) // prevent NewPipe running when is killed, cleared from recent, etc
            mNotificationManager!!.notify(DOWNLOADS_NOTIFICATION_ID, downloadDoneNotification!!.build())
        }

        manageLock(false)

        mConnectivityManager!!.unregisterNetworkCallback(mNetworkStateListenerL)

        mPrefs.unregisterOnSharedPreferenceChangeListener(mPrefChangeListener)

        icDownloadDone?.recycle()
        icDownloadFailed?.recycle()
        icLauncher?.recycle()

        mHandler = null
        mManager.pauseAllMissions(true)
    }

    override fun onBind(intent: Intent): IBinder {
        return mBinder
    }

    private fun handleMessage(msg: Message): Boolean {
        if (mHandler == null) return true

        val mission = msg.obj as DownloadMission

        when (msg.what) {
            MESSAGE_FINISHED -> {
                notifyMediaScanner(mission.storage!!.uri)
                notifyFinishedDownload(mission.storage!!.name)
                mManager.setFinished(mission)
                handleConnectivityState(false)
                updateForegroundState(mManager.runMissions())
            }
            MESSAGE_RUNNING -> updateForegroundState(true)
            MESSAGE_ERROR -> {
                notifyFailedDownload(mission)
                handleConnectivityState(false)
                updateForegroundState(mManager.runMissions())
            }
            MESSAGE_PAUSED -> updateForegroundState(mManager.runningMissionsCount > 0)
        }
        if (msg.what != MESSAGE_ERROR) mFailedDownloads.remove(mFailedDownloads.indexOfValue(mission))

        for (observer in mEchoObservers) observer.handleMessage(msg)

        return true
    }

    private fun handleConnectivityState(updateOnly: Boolean) {
        val info = mConnectivityManager!!.activeNetworkInfo
        val status: DownloadManager.NetworkState

        if (info == null) {
            status = DownloadManager.NetworkState.Unavailable
            Log.i(TAG, "Active network [connectivity is unavailable]")
        } else {
            val connected = info.isConnected
            val metered = mConnectivityManager!!.isActiveNetworkMetered

            status =
                if (connected) if (metered) DownloadManager.NetworkState.MeteredOperating else DownloadManager.NetworkState.Operating
                else DownloadManager.NetworkState.Unavailable

            Log.i(TAG, "Active network [connected=$connected metered=$metered] $info")
        }

        if (mManager == null) return  // avoid race-conditions while the service is starting

        mManager.handleConnectivityState(status, updateOnly)
    }

    private fun handlePreferenceChange(prefs: SharedPreferences?, key: String) {
        when (key) {
            getString(R.string.downloads_maximum_retry) -> {
                try {
                    val value = prefs?.getString(key, getString(R.string.downloads_maximum_retry_default))
                    mManager.mPrefMaxRetry = value?.toInt() ?: 0
                } catch (e: Exception) {
                    mManager.mPrefMaxRetry = 0
                }
                mManager.updateMaximumAttempts()
            }
            getString(R.string.downloads_cross_network) -> {
                mManager.mPrefMeteredDownloads = prefs?.getBoolean(key, false) ?: false
            }
            getString(R.string.downloads_queue_limit) -> {
                mManager.mPrefQueueLimit = prefs?.getBoolean(key, true) ?: true
            }
            getString(R.string.download_path_video_key) -> {
                mManager.mMainStorageVideo = loadMainVideoStorage()
            }
            getString(R.string.download_path_audio_key) -> {
                mManager.mMainStorageAudio = loadMainAudioStorage()
            }
        }
    }

    fun updateForegroundState(state: Boolean) {
        if (state == mForeground) return
        Logd(TAG, "updateForegroundState state: $state")
        if (state) {
//            startForeground(FOREGROUND_NOTIFICATION_ID, mNotification)
            val intent = Intent(this, DownloadManagerService::class.java)
            startService(intent)
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }

        manageLock(state)

        mForeground = state
    }

    private fun startMission(intent: Intent) {
        val urls = intent.getStringArrayExtra(EXTRA_URLS)
        val path = IntentCompat.getParcelableExtra(intent, EXTRA_PATH, Uri::class.java)
        val parentPath = IntentCompat.getParcelableExtra(intent, EXTRA_PARENT_PATH, Uri::class.java)
        val threads = intent.getIntExtra(EXTRA_THREADS, 1)
        val kind = intent.getCharExtra(EXTRA_KIND, '?')
        val psName = intent.getStringExtra(EXTRA_POSTPROCESSING_NAME)
        val psArgs = intent.getStringArrayExtra(EXTRA_POSTPROCESSING_ARGS)
        val source = intent.getStringExtra(EXTRA_SOURCE)
        val nearLength = intent.getLongExtra(EXTRA_NEAR_LENGTH, 0)
        val tag = intent.getStringExtra(EXTRA_STORAGE_TAG)
        val recovery = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_RECOVERY_INFO, MissionRecoveryInfo::class.java)
        Objects.requireNonNull(recovery)

        val storage: StoredFileHelper
        try {
            storage = StoredFileHelper(this, parentPath, path!!, tag)
        } catch (e: IOException) {
            throw RuntimeException(e) // this never should happen
        }
        val ps = if (psName == null) null
        else getAlgorithm(psName, psArgs)

        val mission = DownloadMission(urls!!, storage, kind, ps)
        mission.threadCount = threads
        mission.source = source
        mission.nearLength = nearLength
        mission.recoveryInfo = recovery?.toTypedArray()
        ps?.setTemporalDir(pickAvailableTemporalDir(this)!!)

        handleConnectivityState(true) // first check the actual network status

        mManager.startMission(mission)
    }

    fun notifyFinishedDownload(name: String?) {
        if (!mDownloadNotificationEnable || mNotificationManager == null) return

        if (downloadDoneNotification == null) {
            downloadDoneList = StringBuilder(name!!.length)

            icDownloadDone = BitmapFactory.decodeResource(this.resources, android.R.drawable.stat_sys_download_done)
            downloadDoneNotification = NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
                .setAutoCancel(true)
                .setLargeIcon(icDownloadDone)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setDeleteIntent(makePendingIntent(ACTION_RESET_DOWNLOAD_FINISHED))
                .setContentIntent(makePendingIntent(ACTION_OPEN_DOWNLOADS_FINISHED))
        }

        downloadDoneCount++
        if (downloadDoneCount == 1) {
            downloadDoneList!!.append(name)

            downloadDoneNotification!!.setContentTitle(null)
            downloadDoneNotification!!.setContentText(downloadCount(this, downloadDoneCount))
            downloadDoneNotification!!.setStyle(NotificationCompat.BigTextStyle()
                .setBigContentTitle(downloadCount(this, downloadDoneCount)).bigText(name)
            )
        } else {
            downloadDoneList!!.append('\n')
            downloadDoneList!!.append(name)

            downloadDoneNotification!!.setStyle(NotificationCompat.BigTextStyle().bigText(downloadDoneList))
            downloadDoneNotification!!.setContentTitle(downloadCount(this, downloadDoneCount))
            downloadDoneNotification!!.setContentText(downloadDoneList)
        }

        mNotificationManager!!.notify(DOWNLOADS_NOTIFICATION_ID, downloadDoneNotification!!.build())
    }

    fun notifyFailedDownload(mission: DownloadMission) {
        if (!mDownloadNotificationEnable || mFailedDownloads.containsValue(mission)) return

        val id = downloadFailedNotificationID++
        mFailedDownloads.put(id, mission)

        if (downloadFailedNotification == null) {
            icDownloadFailed = BitmapFactory.decodeResource(this.resources, android.R.drawable.stat_sys_warning)
            downloadFailedNotification = NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
                .setAutoCancel(true)
                .setLargeIcon(icDownloadFailed)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(mOpenDownloadList)
        }

        downloadFailedNotification!!.setContentTitle(getString(R.string.download_failed))
        downloadFailedNotification!!.setContentText(mission.storage!!.name)
        downloadFailedNotification!!.setStyle(NotificationCompat.BigTextStyle().bigText(mission.storage!!.name))

        mNotificationManager!!.notify(id, downloadFailedNotification!!.build())
    }

    private fun makePendingIntent(action: String): PendingIntent? {
        val intent = Intent(this, DownloadManagerService::class.java).setAction(action)
        return PendingIntentCompat.getService(this, intent.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT, false)
    }

    private fun manageLock(acquire: Boolean) {
        if (acquire == mLockAcquired) return

        if (acquire) mLock.acquireWifiAndCpu()
        else mLock.releaseWifiAndCpu()

        mLockAcquired = acquire
    }

    private fun loadMainVideoStorage(): StoredDirectoryHelper? {
        return loadMainStorage(R.string.download_path_video_key, DownloadManager.TAG_VIDEO)
    }

    private fun loadMainAudioStorage(): StoredDirectoryHelper? {
        return loadMainStorage(R.string.download_path_audio_key, DownloadManager.TAG_AUDIO)
    }

    private fun loadMainStorage(@StringRes prefKey: Int, tag: String): StoredDirectoryHelper? {
        var path = mPrefs.getString(getString(prefKey), null)

        if (path.isNullOrEmpty()) {
            Log.e(TAG, "loadMainStorage path: $path")
            Toast.makeText(this, R.string.no_available_dir, Toast.LENGTH_LONG).show()
            return null
        }

        if (path[0] == File.separatorChar) {
            Log.i(TAG, "Old save path style present: $path")
            path = ""
            mPrefs.edit().putString(getString(prefKey), "").apply()
        }

        try {
            return StoredDirectoryHelper(this, Uri.parse(path), tag)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load the storage of $tag from $path", e)
            Toast.makeText(this, R.string.no_available_dir, Toast.LENGTH_LONG).show()
        }

        return null
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Wrappers for DownloadManager
    ////////////////////////////////////////////////////////////////////////////////////////////////
    inner class DownloadManagerBinder : Binder() {
        val downloadManager: DownloadManager
            get() = mManager

        val mainStorageVideo: StoredDirectoryHelper?
            get() = mManager.mMainStorageVideo

        val mainStorageAudio: StoredDirectoryHelper?
            get() = mManager.mMainStorageAudio

        fun askForSavePath(): Boolean {
            return mPrefs.getBoolean(
                this@DownloadManagerService.getString(R.string.downloads_storage_ask),
                false
            )
        }

        fun addMissionEventListener(handler: Handler.Callback) {
            mEchoObservers.add(handler)
        }

        fun removeMissionEventListener(handler: Handler.Callback) {
            mEchoObservers.remove(handler)
        }

        fun clearDownloadNotifications() {
            if (mNotificationManager == null) return
            if (downloadDoneNotification != null) {
                mNotificationManager!!.cancel(DOWNLOADS_NOTIFICATION_ID)
                downloadDoneList!!.setLength(0)
                downloadDoneCount = 0
            }
            if (downloadFailedNotification != null) {
                while (downloadFailedNotificationID > DOWNLOADS_NOTIFICATION_ID) {
                    mNotificationManager!!.cancel(downloadFailedNotificationID)
                    downloadFailedNotificationID--
                }
                mFailedDownloads.clear()
                downloadFailedNotificationID++
            }
        }

        fun enableNotifications(enable: Boolean) {
            mDownloadNotificationEnable = enable
        }
    }

    class LockManager(context: Context) {
        private val TAG = "LockManager@" + hashCode()

        private val powerManager = ContextCompat.getSystemService(context.applicationContext,
            PowerManager::class.java)
        private val wifiManager = ContextCompat.getSystemService(context, WifiManager::class.java)

        private var wakeLock: WakeLock? = null
        private var wifiLock: WifiLock? = null

        fun acquireWifiAndCpu() {
            Logd(TAG, "acquireWifiAndCpu() called")
            if (wakeLock != null && wakeLock!!.isHeld && wifiLock != null && wifiLock!!.isHeld) {
                return
            }

            wakeLock = powerManager!!.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
            wifiLock = wifiManager!!.createWifiLock(WifiManager.WIFI_MODE_FULL, TAG)

            if (wakeLock != null) {
                wakeLock!!.acquire()
            }
            if (wifiLock != null) {
                wifiLock!!.acquire()
            }
        }

        fun releaseWifiAndCpu() {
            Logd(TAG, "releaseWifiAndCpu() called")
            if (wakeLock != null && wakeLock!!.isHeld) {
                wakeLock!!.release()
            }
            if (wifiLock != null && wifiLock!!.isHeld) {
                wifiLock!!.release()
            }

            wakeLock = null
            wifiLock = null
        }
    }

    companion object {
        private const val TAG = "DownloadManagerService"

        const val MESSAGE_RUNNING: Int = 0
        const val MESSAGE_PAUSED: Int = 1
        const val MESSAGE_FINISHED: Int = 2
        const val MESSAGE_ERROR: Int = 3
        const val MESSAGE_DELETED: Int = 4

        private const val FOREGROUND_NOTIFICATION_ID = 1000
        private const val DOWNLOADS_NOTIFICATION_ID = 1001

        private const val EXTRA_URLS = "DownloadManagerService.extra.urls"
        private const val EXTRA_KIND = "DownloadManagerService.extra.kind"
        private const val EXTRA_THREADS = "DownloadManagerService.extra.threads"
        private const val EXTRA_POSTPROCESSING_NAME = "DownloadManagerService.extra.postprocessingName"
        private const val EXTRA_POSTPROCESSING_ARGS = "DownloadManagerService.extra.postprocessingArgs"
        private const val EXTRA_SOURCE = "DownloadManagerService.extra.source"
        private const val EXTRA_NEAR_LENGTH = "DownloadManagerService.extra.nearLength"
        private const val EXTRA_PATH = "DownloadManagerService.extra.storagePath"
        private const val EXTRA_PARENT_PATH = "DownloadManagerService.extra.storageParentPath"
        private const val EXTRA_STORAGE_TAG = "DownloadManagerService.extra.storageTag"
        private const val EXTRA_RECOVERY_INFO = "DownloadManagerService.extra.recoveryInfo"

        private const val ACTION_RESET_DOWNLOAD_FINISHED = BuildConfig.APPLICATION_ID + ".reset_download_finished"
        private const val ACTION_OPEN_DOWNLOADS_FINISHED = BuildConfig.APPLICATION_ID + ".open_downloads_finished"

        /**
         * Start a new download mission
         *
         * @param context      the activity context
         * @param urls         array of urls to download
         * @param storage      where the file is saved
         * @param kind         type of file (a: audio  v: video  s: subtitle ?: file-extension defined)
         * @param threads      the number of threads maximal used to download chunks of the file.
         * @param psName       the name of the required post-processing algorithm, or `null` to ignore.
         * @param source       source url of the resource
         * @param psArgs       the arguments for the post-processing algorithm.
         * @param nearLength   the approximated final length of the file
         * @param recoveryInfo array of MissionRecoveryInfo, in case is required recover the download
         */
        @JvmStatic
        fun startMission(context: Context, urls: Array<String?>?, storage: StoredFileHelper,
                         kind: Char, threads: Int, source: String?, psName: String?,
                         psArgs: Array<String?>?, nearLength: Long,
                         recoveryInfo: ArrayList<MissionRecoveryInfo?>?) {
            val intent = Intent(context, DownloadManagerService::class.java)
                .setAction(Intent.ACTION_RUN)
                .putExtra(EXTRA_URLS, urls)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_THREADS, threads)
                .putExtra(EXTRA_SOURCE, source)
                .putExtra(EXTRA_POSTPROCESSING_NAME, psName)
                .putExtra(EXTRA_POSTPROCESSING_ARGS, psArgs)
                .putExtra(EXTRA_NEAR_LENGTH, nearLength)
                .putExtra(EXTRA_RECOVERY_INFO, recoveryInfo)
                .putExtra(EXTRA_PARENT_PATH, storage.parentUri)
                .putExtra(EXTRA_PATH, storage.uri)
                .putExtra(EXTRA_STORAGE_TAG, storage.tag)

            context.startService(intent)
        }
    }
}
