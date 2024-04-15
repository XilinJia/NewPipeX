package us.shandian.giga.ui.adapter

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Message
import android.util.Log
import android.view.*
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.HandlerCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorInfo.Companion.throwableToStringList
import org.schabi.newpipe.error.ErrorUtil.Companion.createNotification
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.streams.io.StoredFileHelper
import org.schabi.newpipe.util.Localization.deletedDownloadCount
import org.schabi.newpipe.util.NavigationHelper.getIntentByLink
import org.schabi.newpipe.util.external_communication.ShareUtils.copyToClipboard
import org.schabi.newpipe.util.external_communication.ShareUtils.openIntentInApp
import us.shandian.giga.get.DownloadMission
import us.shandian.giga.get.FinishedMission
import us.shandian.giga.get.Mission
import us.shandian.giga.service.DownloadManager
import us.shandian.giga.service.DownloadManager.MissionItem
import us.shandian.giga.service.DownloadManager.MissionIterator
import us.shandian.giga.service.DownloadManagerService
import us.shandian.giga.ui.common.Deleter
import us.shandian.giga.ui.common.ProgressDrawable
import us.shandian.giga.util.Utility
import java.io.File
import java.net.URI
import java.util.*
import kotlin.math.ceil

class MissionAdapter(private val mContext: Context,
                     private val mDownloadManager: DownloadManager,
                     private val mEmptyMessage: View,
                     root: View
) : RecyclerView.Adapter<RecyclerView.ViewHolder?>(), Handler.Callback {
    private val mInflater: LayoutInflater = LayoutInflater.from(mContext)
    private val mDeleter: Deleter
    private var mLayout: Int
    private val mIterator: MissionIterator
    private val mPendingDownloadsItems = ArrayList<ViewHolderItem>()
    private val mHandler: Handler
    private var mClear: MenuItem? = null
    private var mStartButton: MenuItem? = null
    private var mPauseButton: MenuItem? = null
    private var mRecover: RecoverHelper? = null
    private val mView: View
    private val mHidden: ArrayList<Mission>
    private var mSnackbar: Snackbar? = null

    private val compositeDisposable = CompositeDisposable()

    init {
        mLayout = R.layout.mission_item

        mHandler = Handler(mContext.mainLooper)

        mIterator = mDownloadManager.iterator

        mDeleter = Deleter(root, mContext, this, mDownloadManager, mIterator, mHandler)

        mView = root

        mHidden = ArrayList()

        checkEmptyMessageVisibility()
        onResume()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        when (viewType) {
            DownloadManager.SPECIAL_PENDING, DownloadManager.SPECIAL_FINISHED -> return ViewHolderHeader(mInflater.inflate(
                R.layout.missions_header,
                parent,
                false))
        }
        return ViewHolderItem(mInflater.inflate(mLayout, parent, false))
    }

    override fun onViewRecycled(view: RecyclerView.ViewHolder) {
        super.onViewRecycled(view)

        if (view is ViewHolderHeader) return
        val h = view as ViewHolderItem

        if (h.item!!.mission is DownloadMission) {
            mPendingDownloadsItems.remove(h)
            if (mPendingDownloadsItems.size < 1) {
                checkMasterButtonsVisibility()
            }
        }

        h.popupMenu.dismiss()
        h.item = null
        h.resetSpeedMeasure()
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(view: RecyclerView.ViewHolder, @SuppressLint("RecyclerView") pos: Int) {
        val item = mIterator.getItem(pos)

        if (view is ViewHolderHeader) {
            if (item.special == DownloadManager.SPECIAL_NOTHING) return
            val str: Int
            if (item.special == DownloadManager.SPECIAL_PENDING) {
                str = R.string.missions_header_pending
            } else {
                str = R.string.missions_header_finished
                if (mClear != null) mClear!!.setVisible(true)
            }

            view.header.setText(str)
            return
        }

        val h = view as ViewHolderItem
        h.item = item

        val type = Utility.getFileType(item.mission!!.kind, item.mission!!.storage!!.name?:"")

        h.icon.setImageResource(Utility.getIconForFileType(type))
        h.name.text = item.mission!!.storage!!.name

        h.progress.setColors(Utility.getBackgroundForFileType(mContext, type),
            Utility.getForegroundForFileType(mContext, type))

        if (h.item!!.mission is DownloadMission) {
            val mission = item.mission as DownloadMission?
            var length = Utility.formatBytes(mission!!.length)
            if (mission.running && !mission.isPsRunning) length += " --.- kB/s"

            h.size.text = length
            h.pause.setTitle(if (mission.unknownLength) R.string.stop else R.string.pause)
            updateProgress(h)
            mPendingDownloadsItems.add(h)
        } else {
            h.progress.setMarquee(false)
            h.status.text = "100%"
            h.progress.setProgress(1.0)
            h.size.text = Utility.formatBytes(item.mission!!.length)
        }
    }

    override fun getItemCount(): Int {
        return mIterator.oldListSize
    }

    override fun getItemViewType(position: Int): Int {
        return mIterator.getSpecialAtItem(position)
    }

    @SuppressLint("DefaultLocale")
    private fun updateProgress(h: ViewHolderItem?) {
        if (h?.item == null || h.item!!.mission is FinishedMission) return

        val mission = h.item!!.mission as DownloadMission?
        val done = mission!!.done.toDouble()
        val length = mission.length
        val now = System.currentTimeMillis()
        val hasError = mission.errCode != DownloadMission.ERROR_NOTHING

        // hide on error
        // show if current resource length is not fetched
        // show if length is unknown
        h.progress.setMarquee(mission.isRecovering || !hasError && (!mission.isInitialized || mission.unknownLength))

        val progress: Double
        if (mission.unknownLength) {
            progress = Double.NaN
            h.progress.setProgress(0.0)
        } else {
            progress = done / length
        }

        if (hasError) {
            h.progress.setProgress(if (isNotFinite(progress)) 1.0 else progress)
            h.status.setText(R.string.msg_error)
        } else if (isNotFinite(progress)) {
            h.status.text = UNDEFINED_PROGRESS
        } else {
            h.status.text = String.format("%.2f%%", progress * 100)
            h.progress.setProgress(progress)
        }

        @StringRes val state: Int
        val sizeStr = Utility.formatBytes(length) + "  "

        if (mission.isPsFailed || mission.errCode == DownloadMission.ERROR_POSTPROCESSING_HOLD) {
            h.size.text = sizeStr
            return
        } else if (!mission.running) {
            state = if (mission.enqueued) R.string.queued else R.string.paused
        } else if (mission.isPsRunning) {
            state = R.string.post_processing
        } else if (mission.isRecovering) {
            state = R.string.recovering
        } else {
            state = 0
        }

        if (state != 0) {
            // update state without download speed
            h.size.text = "$sizeStr(" + mContext.getString(state) + ")"
            h.resetSpeedMeasure()
            return
        }

        if (h.lastTimestamp < 0) {
            h.size.text = sizeStr
            h.lastTimestamp = now
            h.lastDone = done
            return
        }

        val deltaTime = now - h.lastTimestamp
        val deltaDone = done - h.lastDone

        if (h.lastDone > done) {
            h.lastDone = done
            h.size.text = sizeStr
            return
        }

        if (deltaDone > 0 && deltaTime > 0) {
            val speed = ((deltaDone * 1000.0) / deltaTime).toFloat()
            var averageSpeed = speed

            if (h.lastSpeedIdx < 0) {
                Arrays.fill(h.lastSpeed, speed)
                h.lastSpeedIdx = 0
            } else {
                for (i in h.lastSpeed.indices) {
                    averageSpeed += h.lastSpeed[i]
                }
                averageSpeed /= h.lastSpeed.size + 1.0f
            }

            val speedStr = Utility.formatSpeed(averageSpeed.toDouble())
            val etaStr: String

            if (mission.unknownLength) {
                etaStr = ""
            } else {
                val eta = ceil((length - done) / averageSpeed).toLong()
                etaStr = Utility.formatBytes(done.toLong()) + "/" + Utility.stringifySeconds(eta) + "  "
            }

            h.size.text = sizeStr + etaStr + speedStr

            h.lastTimestamp = now
            h.lastDone = done
            h.lastSpeed[h.lastSpeedIdx++] = speed

            if (h.lastSpeedIdx >= h.lastSpeed.size) h.lastSpeedIdx = 0
        }
    }

    private fun viewWithFileProvider(mission: Mission?) {
        if (checkInvalidFile(mission!!)) return

        val mimeType = resolveMimeType(mission)

        if (BuildConfig.DEBUG) Log.v(TAG, "Mime: " + mimeType + " package: " + BuildConfig.APPLICATION_ID + ".provider")

        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(resolveShareableUri(mission), mimeType)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        openIntentInApp(mContext, intent)
    }

    private fun shareFile(mission: Mission?) {
        if (checkInvalidFile(mission!!)) return

        val shareIntent = Intent(Intent.ACTION_SEND)
        shareIntent.setType(resolveMimeType(mission))
        shareIntent.putExtra(Intent.EXTRA_STREAM, resolveShareableUri(mission))
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val intent = Intent(Intent.ACTION_CHOOSER)
        intent.putExtra(Intent.EXTRA_INTENT, shareIntent)
        // unneeded to set a title to the chooser on Android P and higher because the system
        // ignores this title on these versions
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            intent.putExtra(Intent.EXTRA_TITLE, mContext.getString(R.string.share_dialog_title))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        mContext.startActivity(intent)
    }

    /**
     * Returns an Uri which can be shared to other applications.
     *
     * @see [
     * https://stackoverflow.com/questions/38200282/android-os-fileuriexposedexception-file-storage-emulated-0-test-txt-exposed](https://stackoverflow.com/questions/38200282/android-os-fileuriexposedexception-file-storage-emulated-0-test-txt-exposed)
     */
    private fun resolveShareableUri(mission: Mission?): Uri {
        return if (mission!!.storage!!.isDirect) {
            FileProvider.getUriForFile(
                mContext,
                BuildConfig.APPLICATION_ID + ".provider",
                File(URI.create(mission.storage!!.uri.toString()))
            )
        } else {
            mission.storage!!.uri
        }
    }

    private fun checkInvalidFile(mission: Mission): Boolean {
        if (mission.storage!!.existsAsFile()) return false

        Toast.makeText(mContext, R.string.missing_file, Toast.LENGTH_SHORT).show()
        return true
    }

    private fun getViewHolder(mission: Any): ViewHolderItem? {
        for (h in mPendingDownloadsItems) {
            if (h.item!!.mission === mission) return h
        }
        return null
    }

    override fun handleMessage(msg: Message): Boolean {
        if (mStartButton != null && mPauseButton != null) {
            checkMasterButtonsVisibility()
        }

        when (msg.what) {
            DownloadManagerService.MESSAGE_ERROR, DownloadManagerService.MESSAGE_FINISHED, DownloadManagerService.MESSAGE_DELETED, DownloadManagerService.MESSAGE_PAUSED -> {}
            else -> return false
        }
        val h = getViewHolder(msg.obj) ?: return false

        when (msg.what) {
            DownloadManagerService.MESSAGE_FINISHED, DownloadManagerService.MESSAGE_DELETED -> {
                // DownloadManager should mark the download as finished
                applyChanges()
                return true
            }
        }
        updateProgress(h)
        return true
    }

    private fun showError(mission: DownloadMission) {
        @StringRes var msg = R.string.general_error
        var msgEx: String? = null

        when (mission.errCode) {
            416 -> msg = R.string.error_http_unsupported_range
            404 -> msg = R.string.error_http_not_found
            DownloadMission.ERROR_NOTHING -> return  // this never should happen
            DownloadMission.ERROR_FILE_CREATION -> msg = R.string.error_file_creation
            DownloadMission.ERROR_HTTP_NO_CONTENT -> msg = R.string.error_http_no_content
            DownloadMission.ERROR_PATH_CREATION -> msg = R.string.error_path_creation
            DownloadMission.ERROR_PERMISSION_DENIED -> msg = R.string.permission_denied
            DownloadMission.ERROR_SSL_EXCEPTION -> msg = R.string.error_ssl_exception
            DownloadMission.ERROR_UNKNOWN_HOST -> msg = R.string.error_unknown_host
            DownloadMission.ERROR_CONNECT_HOST -> msg = R.string.error_connect_host
            DownloadMission.ERROR_POSTPROCESSING_STOPPED -> msg = R.string.error_postprocessing_stopped
            DownloadMission.ERROR_POSTPROCESSING, DownloadMission.ERROR_POSTPROCESSING_HOLD -> {
                showError(mission, UserAction.DOWNLOAD_POSTPROCESSING, R.string.error_postprocessing_failed)
                return
            }
            DownloadMission.ERROR_INSUFFICIENT_STORAGE -> msg = R.string.error_insufficient_storage
            DownloadMission.ERROR_UNKNOWN_EXCEPTION -> if (mission.errObject != null) {
                showError(mission, UserAction.DOWNLOAD_FAILED, R.string.general_error)
                return
            } else {
                msg = R.string.msg_error
            }
            DownloadMission.ERROR_PROGRESS_LOST -> msg = R.string.error_progress_lost
            DownloadMission.ERROR_TIMEOUT -> msg = R.string.error_timeout
            DownloadMission.ERROR_RESOURCE_GONE -> msg = R.string.error_download_resource_gone
            else -> msgEx = if (mission.errCode >= 100 && mission.errCode < 600) {
                "HTTP " + mission.errCode
            } else if (mission.errObject == null) {
                "(not_decelerated_error_code)"
            } else {
                showError(mission, UserAction.DOWNLOAD_FAILED, msg)
                return
            }
        }
        val builder = AlertDialog.Builder(mContext)

        if (msgEx != null) builder.setMessage(msgEx)
        else builder.setMessage(msg)

        // add report button for non-HTTP errors (range 100-599)
        if (mission.errObject != null && (mission.errCode < 100 || mission.errCode >= 600)) {
            @StringRes val mMsg = msg
            builder.setPositiveButton(R.string.error_report_title
            ) { dialog: DialogInterface?, which: Int -> showError(mission, UserAction.DOWNLOAD_FAILED, mMsg) }
        }

        builder.setNegativeButton(R.string.ok) { dialog: DialogInterface, which: Int -> dialog.cancel() }
            .setTitle(mission.storage!!.name)
            .show()
    }

    private fun showError(mission: DownloadMission, action: UserAction, @StringRes reason: Int) {
        val request = StringBuilder(256)
        request.append(mission.source)

        request.append(" [")
        if (mission.recoveryInfo != null) {
            for (recovery in mission.recoveryInfo!!) request.append(' ')
                .append(recovery.toString())
                .append(' ')
        }
        request.append("]")
        var service = try {
            NewPipe.getServiceByUrl(mission.source).serviceInfo.name
        } catch (e: Exception) {
            ErrorInfo.SERVICE_NONE
        }

        createNotification(mContext,
            ErrorInfo(throwableToStringList(mission.errObject!!), action,
                service!!, request.toString(), reason))
    }

    fun clearFinishedDownloads(delete: Boolean) {
        if (delete && mIterator.hasFinishedMissions() && mHidden.isEmpty()) {
            for (i in 0 until mIterator.oldListSize) {
                val mission =
                    if (mIterator.getItem(i).mission is FinishedMission) mIterator.getItem(i).mission as FinishedMission? else null
                if (mission != null) {
                    mIterator.hide(mission)
                    mHidden.add(mission)
                }
            }
            applyChanges()

            val msg = deletedDownloadCount(mContext, mHidden.size)
            mSnackbar = Snackbar.make(mView, msg, Snackbar.LENGTH_INDEFINITE)
            mSnackbar!!.setAction(R.string.undo) { s: View? ->
                val i = mHidden.iterator()
                while (i.hasNext()) {
                    mIterator.unHide(i.next())
                    i.remove()
                }
                applyChanges()
                mHandler.removeCallbacksAndMessages(DELETE)
            }
            mSnackbar!!.setActionTextColor(Color.YELLOW)
            mSnackbar!!.show()

            HandlerCompat.postDelayed(mHandler, { this.deleteFinishedDownloads() }, DELETE, 5000)
        } else if (!delete) {
            mDownloadManager.forgetFinishedDownloads()
            applyChanges()
        }
    }

    private fun deleteFinishedDownloads() {
        if (mSnackbar != null) mSnackbar!!.dismiss()

        val i = mHidden.iterator()
        while (i.hasNext()) {
            val mission = i.next()
            if (mission != null) {
                mDownloadManager.deleteMission(mission)
                mContext.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, mission.storage!!.uri))
            }
            i.remove()
        }
    }

    private fun handlePopupItem(h: ViewHolderItem, option: MenuItem): Boolean {
        if (h.item == null) return true

        val id = option.itemId
        val mission = if (h.item!!.mission is DownloadMission) h.item!!.mission as DownloadMission? else null

        if (mission != null) {
            when (id) {
                R.id.start -> {
                    h.status.text = UNDEFINED_PROGRESS
                    mDownloadManager.resumeMission(mission)
                    return true
                }
                R.id.pause -> {
                    mDownloadManager.pauseMission(mission)
                    return true
                }
                R.id.error_message_view -> {
                    showError(mission)
                    return true
                }
                R.id.queue -> {
                    val flag = !h.queue.isChecked
                    h.queue.setChecked(flag)
                    mission.setEnqueued(flag)
                    updateProgress(h)
                    return true
                }
                R.id.retry -> {
                    if (mission.isPsRunning) {
                        mission.psContinue(true)
                    } else {
                        mDownloadManager.tryRecover(mission)
                        if (mission.storage!!.isInvalid) mRecover!!.tryRecover(mission)
                        else recoverMission(mission)
                    }
                    return true
                }
                R.id.cancel -> {
                    mission.psContinue(false)
                    return false
                }
            }
        }

        when (id) {
            R.id.menu_item_share -> {
                shareFile(h.item!!.mission)
                return true
            }
            R.id.delete -> {
                if (h.item?.mission != null) mDeleter.append(h.item!!.mission!!)
                applyChanges()
                checkMasterButtonsVisibility()
                return true
            }
            R.id.md5, R.id.sha1 -> {
                val notificationManager = ContextCompat.getSystemService(mContext, NotificationManager::class.java)
                val progressNotificationBuilder = NotificationCompat.Builder(mContext,
                    mContext.getString(R.string.hash_channel_id))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setSmallIcon(R.drawable.ic_newpipe_triangle_white)
                    .setContentTitle(mContext.getString(R.string.msg_calculating_hash))
                    .setContentText(mContext.getString(R.string.msg_wait))
                    .setProgress(0, 0, true)
                    .setOngoing(true)

                notificationManager!!.notify(HASH_NOTIFICATION_ID, progressNotificationBuilder.build())
                val storage = h.item!!.mission!!.storage
                if (storage != null) compositeDisposable.add(
                    Observable.fromCallable { Utility.checksum(storage, id) }
                        .subscribeOn(Schedulers.computation())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe { result: String? ->
                            copyToClipboard(mContext, result)
                            notificationManager.cancel(HASH_NOTIFICATION_ID)
                        }
                )
                return true
            }
            R.id.source -> {
                /*Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(h.item.mission.source));
                mContext.startActivity(intent);*/
                try {
                    val intent = getIntentByLink(mContext, h.item!!.mission!!.source!!)
                    intent.addFlags(Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP)
                    mContext.startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Selected item has a invalid source", e)
                }
                return true
            }
            else -> return false
        }
    }

    fun applyChanges() {
        mIterator.start()
        DiffUtil.calculateDiff(mIterator, true).dispatchUpdatesTo(this)
        mIterator.end()

        checkEmptyMessageVisibility()
        if (mClear != null) mClear!!.setVisible(mIterator.hasFinishedMissions())
    }

    fun forceUpdate() {
        mIterator.start()
        mIterator.end()

        for (item in mPendingDownloadsItems) {
            item.resetSpeedMeasure()
        }

        notifyDataSetChanged()
    }

    fun setLinear(isLinear: Boolean) {
        mLayout = if (isLinear) R.layout.mission_item_linear else R.layout.mission_item
    }

    fun setClearButton(clearButton: MenuItem) {
        if (mClear == null) clearButton.setVisible(mIterator.hasFinishedMissions())

        mClear = clearButton
    }

    fun setMasterButtons(startButton: MenuItem?, pauseButton: MenuItem?) {
        val init = mStartButton == null || mPauseButton == null

        mStartButton = startButton
        mPauseButton = pauseButton

        if (init) checkMasterButtonsVisibility()
    }

    private fun checkEmptyMessageVisibility() {
        val flag = if (mIterator.oldListSize > 0) View.GONE else View.VISIBLE
        if (mEmptyMessage.visibility != flag) mEmptyMessage.visibility = flag
    }

    fun checkMasterButtonsVisibility() {
        val state = mIterator.hasValidPendingMissions()
        Log.d(TAG, "checkMasterButtonsVisibility() running=" + state[0] + " paused=" + state[1])
        setButtonVisible(mPauseButton, state[0])
        setButtonVisible(mStartButton, state[1])
    }

    fun refreshMissionItems() {
        for (h in mPendingDownloadsItems) {
            if ((h.item!!.mission as DownloadMission?)!!.running) continue
            updateProgress(h)
            h.resetSpeedMeasure()
        }
    }

    fun onDestroy() {
        compositeDisposable.dispose()
        mDeleter.dispose()
    }

    fun onResume() {
        mDeleter.resume()
        HandlerCompat.postDelayed(mHandler, { this.updater() }, UPDATER, 0)
    }

    fun onPaused() {
        mDeleter.pause()
        mHandler.removeCallbacksAndMessages(UPDATER)
    }

    fun recoverMission(mission: DownloadMission) {
        val h = getViewHolder(mission) ?: return

        mission.errObject = null
        mission.resetState(true, false, DownloadMission.ERROR_NOTHING)

        h.status.text = UNDEFINED_PROGRESS
        h.size.text = Utility.formatBytes(mission.length)
        h.progress.setMarquee(true)

        mDownloadManager.resumeMission(mission)
    }

    private fun updater() {
        for (h in mPendingDownloadsItems) {
            // check if the mission is running first
            if (!(h.item!!.mission as DownloadMission?)!!.running) continue

            updateProgress(h)
        }

        HandlerCompat.postDelayed(mHandler, { this.updater() }, UPDATER, 1000)
    }

    private fun isNotFinite(value: Double): Boolean {
        return java.lang.Double.isNaN(value) || java.lang.Double.isInfinite(value)
    }

    fun setRecover(callback: RecoverHelper) {
        mRecover = callback
    }


    internal inner class ViewHolderItem(view: View?) : RecyclerView.ViewHolder(view!!) {
        var item: MissionItem? = null

        var status: TextView
        var icon: ImageView
        var name: TextView
        var size: TextView
        var progress: ProgressDrawable = ProgressDrawable()

        var popupMenu: PopupMenu
        var retry: MenuItem
        var cancel: MenuItem
        var start: MenuItem
        var pause: MenuItem
        var open: MenuItem
        var queue: MenuItem
        var showError: MenuItem
        var delete: MenuItem
        var source: MenuItem
        var checksum: MenuItem

        var lastTimestamp: Long = -1
        var lastDone: Double = 0.0
        var lastSpeedIdx: Int = 0
        var lastSpeed: FloatArray = FloatArray(3)
        var estimatedTimeArrival: String = UNDEFINED_ETA

        init {
            itemView.findViewById<View>(R.id.item_bkg).background = progress

            status = itemView.findViewById(R.id.item_status)
            name = itemView.findViewById(R.id.item_name)
            icon = itemView.findViewById(R.id.item_icon)
            size = itemView.findViewById(R.id.item_size)

            name.isSelected = true

            val button = itemView.findViewById<ImageView>(R.id.item_more)
            popupMenu = buildPopup(button)
            button.setOnClickListener { v: View? -> showPopupMenu() }

            val menu = popupMenu.menu
            retry = menu.findItem(R.id.retry)
            cancel = menu.findItem(R.id.cancel)
            start = menu.findItem(R.id.start)
            pause = menu.findItem(R.id.pause)
            open = menu.findItem(R.id.menu_item_share)
            queue = menu.findItem(R.id.queue)
            showError = menu.findItem(R.id.error_message_view)
            delete = menu.findItem(R.id.delete)
            source = menu.findItem(R.id.source)
            checksum = menu.findItem(R.id.checksum)

            itemView.isHapticFeedbackEnabled = true

            itemView.setOnClickListener { v: View? ->
                if (item!!.mission is FinishedMission) viewWithFileProvider(
                    item!!.mission)
            }

            itemView.setOnLongClickListener { v: View ->
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showPopupMenu()
                true
            }
        }

        private fun showPopupMenu() {
            retry.setVisible(false)
            cancel.setVisible(false)
            start.setVisible(false)
            pause.setVisible(false)
            open.setVisible(false)
            queue.setVisible(false)
            showError.setVisible(false)
            delete.setVisible(false)
            source.setVisible(false)
            checksum.setVisible(false)

            val mission = if (item!!.mission is DownloadMission) item!!.mission as DownloadMission? else null

            if (mission != null) {
                if (mission.hasInvalidStorage()) {
                    retry.setVisible(true)
                    delete.setVisible(true)
                    showError.setVisible(true)
                } else if (mission.isPsRunning) {
                    when (mission.errCode) {
                        DownloadMission.ERROR_INSUFFICIENT_STORAGE, DownloadMission.ERROR_POSTPROCESSING_HOLD -> {
                            retry.setVisible(true)
                            cancel.setVisible(true)
                            showError.setVisible(true)
                        }
                    }
                } else {
                    if (mission.running) {
                        pause.setVisible(true)
                    } else {
                        if (mission.errCode != DownloadMission.ERROR_NOTHING) {
                            showError.setVisible(true)
                        }

                        queue.setChecked(mission.enqueued)

                        delete.setVisible(true)

                        val flag = !mission.isPsFailed && mission.urls.size > 0
                        start.setVisible(flag)
                        queue.setVisible(flag)
                    }
                }
            } else {
                open.setVisible(true)
                delete.setVisible(true)
                checksum.setVisible(true)
            }

            if (item!!.mission!!.source != null && !item!!.mission!!.source!!.isEmpty()) {
                source.setVisible(true)
            }

            popupMenu.show()
        }

        private fun buildPopup(button: View): PopupMenu {
            val popup = PopupMenu(mContext, button)
            popup.inflate(R.menu.mission)
            popup.setOnMenuItemClickListener { option: MenuItem -> handlePopupItem(this, option) }

            return popup
        }

        fun resetSpeedMeasure() {
            estimatedTimeArrival = UNDEFINED_ETA
            lastTimestamp = -1
            lastSpeedIdx = -1
        }
    }

    internal class ViewHolderHeader(view: View?) : RecyclerView.ViewHolder(view!!) {
        var header: TextView = itemView.findViewById(R.id.item_name)
    }

    fun interface RecoverHelper {
        fun tryRecover(mission: DownloadMission?)
    }

    companion object {
        private const val TAG = "MissionAdapter"
        private const val UNDEFINED_PROGRESS = "--.-%"
        private const val DEFAULT_MIME_TYPE = "*/*"
        private const val UNDEFINED_ETA = "--:--"

        private const val UPDATER = "updater"
        private const val DELETE = "deleteFinishedDownloads"

        private const val HASH_NOTIFICATION_ID = 123790

        private fun resolveMimeType(mission: Mission): String {
            var mimeType: String?

            if (!mission.storage!!.isInvalid) {
                mimeType = mission.storage!!.type
                if (mimeType != null && mimeType.length > 0 && mimeType != StoredFileHelper.DEFAULT_MIME) return mimeType
            }

            val ext = Utility.getFileExt(mission.storage!!.name?:"") ?: return DEFAULT_MIME_TYPE

            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.substring(1))

            return mimeType ?: DEFAULT_MIME_TYPE
        }

        private fun setButtonVisible(button: MenuItem?, visible: Boolean) {
            if (button!!.isVisible != visible) button.setVisible(visible)
        }
    }
}
