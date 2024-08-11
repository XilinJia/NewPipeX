package org.schabi.newpipe.ui.download

import android.app.Activity
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.text.Editable
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.menu.ActionMenuItemView
import androidx.appcompat.widget.Toolbar
import androidx.collection.SparseArrayCompat
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.DialogFragment
import androidx.preference.PreferenceManager
import com.nononsenseapps.filepicker.Utils
import icepick.Icepick
import icepick.State
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.DownloadDialogBinding
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil.Companion.createNotification
import org.schabi.newpipe.error.ErrorUtil.Companion.showSnackbar
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.*
import org.schabi.newpipe.settings.NewPipeSettings.getDir
import org.schabi.newpipe.settings.NewPipeSettings.useStorageAccessFramework
import org.schabi.newpipe.giga.io.NoFileManagerSafeGuard.launchSafe
import org.schabi.newpipe.giga.io.StoredDirectoryHelper
import org.schabi.newpipe.giga.io.StoredDirectoryHelper.Companion.getPicker
import org.schabi.newpipe.giga.io.StoredFileHelper
import org.schabi.newpipe.giga.io.StoredFileHelper.Companion.getNewPicker
import org.schabi.newpipe.util.*
import org.schabi.newpipe.util.FilePickerActivityHelper.Companion.isOwnFileUri
import org.schabi.newpipe.util.FilenameUtils.createFilename
import org.schabi.newpipe.util.ListHelper.getDefaultAudioFormat
import org.schabi.newpipe.util.ListHelper.getDefaultAudioTrackGroup
import org.schabi.newpipe.util.ListHelper.getDefaultResolutionIndex
import org.schabi.newpipe.util.ListHelper.getGroupedAudioStreams
import org.schabi.newpipe.util.ListHelper.getSortedStreamVideosList
import org.schabi.newpipe.util.ListHelper.getStreamsOfSpecifiedDelivery
import org.schabi.newpipe.util.Localization.assureCorrectAppLanguage
import org.schabi.newpipe.util.PermissionHelper.checkStoragePermissions
import org.schabi.newpipe.util.StreamItemAdapter.StreamInfoWrapper
import org.schabi.newpipe.util.StreamItemAdapter.StreamInfoWrapper.Companion.empty
import org.schabi.newpipe.util.StreamItemAdapter.StreamInfoWrapper.Companion.fetchMoreInfoForWrapper
import org.schabi.newpipe.util.ThemeHelper.getDialogTheme
import org.schabi.newpipe.giga.get.MissionRecoveryInfo
import org.schabi.newpipe.giga.postprocessing.Postprocessing
import org.schabi.newpipe.giga.service.DownloadManager
import org.schabi.newpipe.giga.service.DownloadManager.MissionState
import org.schabi.newpipe.giga.service.DownloadManagerService
import org.schabi.newpipe.giga.service.DownloadManagerService.Companion.startMission
import org.schabi.newpipe.giga.service.DownloadManagerService.DownloadManagerBinder
import java.io.IOException
import java.io.Serializable
import java.util.*
import java.util.stream.Collectors

class DownloadDialog : DialogFragment, RadioGroup.OnCheckedChangeListener, AdapterView.OnItemSelectedListener {
    @JvmField
    @State
    var currentInfo: StreamInfo? = null

    @JvmField
    @State
    var wrappedVideoStreams: StreamInfoWrapper<VideoStream>? = null

    @JvmField
    @State
    var wrappedSubtitleStreams: StreamInfoWrapper<SubtitlesStream>? = null

    @JvmField
    @State
    var wrappedAudioTracks: AudioTrackAdapter.AudioTracksWrapper? = null

    @JvmField
    @State
    var selectedAudioTrackIndex: Int = 0

    @JvmField
    @State
    var selectedVideoIndex: Int = 0 // set in the constructor

    @JvmField
    @State
    var selectedAudioIndex: Int = 0 // default to the first item

    @JvmField
    @State
    var selectedSubtitleIndex: Int = 0 // default to the first item

    private var onDismissListener: DialogInterface.OnDismissListener? = null

    private var mainStorageAudio: StoredDirectoryHelper? = null
    private var mainStorageVideo: StoredDirectoryHelper? = null
    private var downloadManager: DownloadManager? = null
    private var okButton: ActionMenuItemView? = null
    private var context: Context? = null
    private var askForSavePath = false

    private var audioTrackAdapter: AudioTrackAdapter? = null
    private var audioStreamsAdapter: StreamItemAdapter<AudioStream, Stream>? = null
    private var videoStreamsAdapter: StreamItemAdapter<VideoStream, AudioStream>? = null
    private var subtitleStreamsAdapter: StreamItemAdapter<SubtitlesStream, Stream>? = null

    private val disposables = CompositeDisposable()

    private var dBinding: DownloadDialogBinding? = null

    private var prefs: SharedPreferences? = null

    // Variables for file name and MIME type when picking new folder because it's not set yet
    private var filenameTmp: String? = null
    private var mimeTmp: String? = null

    private val requestDownloadSaveAsLauncher = registerForActivityResult<Intent, ActivityResult>(
        ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        this.requestDownloadSaveAsResult(result)
    }
    private val requestDownloadPickAudioFolderLauncher = registerForActivityResult<Intent, ActivityResult>(
        ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        this.requestDownloadPickAudioFolderResult(result)
    }
    private val requestDownloadPickVideoFolderLauncher = registerForActivityResult<Intent, ActivityResult>(
        ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        this.requestDownloadPickVideoFolderResult(result)
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Instance creation
    ////////////////////////////////////////////////////////////////////////// */
    constructor()

    /**
     * Create a new download dialog with the video, audio and subtitle streams from the provided
     * stream info. Video streams and video-only streams will be put into a single list menu,
     * sorted according to their resolution and the default video resolution will be selected.
     *
     * @param context the context to use just to obtain preferences and strings (will not be stored)
     * @param info    the info from which to obtain downloadable streams and other info (e.g. title)
     */
    constructor(context: Context, info: StreamInfo) {
        this.currentInfo = info

        val audioStreams: List<AudioStream> = getStreamsOfSpecifiedDelivery(info.audioStreams, DeliveryMethod.PROGRESSIVE_HTTP)
        val groupedAudioStreams: List<List<AudioStream>> = getGroupedAudioStreams(context, audioStreams)
        this.wrappedAudioTracks = AudioTrackAdapter.AudioTracksWrapper(groupedAudioStreams, context)
        this.selectedAudioTrackIndex = getDefaultAudioTrackGroup(context, groupedAudioStreams)

        // TODO: Adapt this code when the downloader support other types of stream deliveries
        val videoStreams = getSortedStreamVideosList(context,
            getStreamsOfSpecifiedDelivery(info.videoStreams, DeliveryMethod.PROGRESSIVE_HTTP),
            getStreamsOfSpecifiedDelivery(info.videoOnlyStreams, DeliveryMethod.PROGRESSIVE_HTTP),
            false,  // If there are multiple languages available, prefer streams without audio
            // to allow language selection
            wrappedAudioTracks!!.size() > 1
        )

        this.wrappedVideoStreams = StreamInfoWrapper(videoStreams, context)
        this.wrappedSubtitleStreams = StreamInfoWrapper(getStreamsOfSpecifiedDelivery(info.subtitles, DeliveryMethod.PROGRESSIVE_HTTP), context)

        this.selectedVideoIndex = getDefaultResolutionIndex(context, videoStreams)
    }

    /**
     * @param onDismissListener the listener to call in [.onDismiss]
     */
    fun setOnDismissListener(onDismissListener: DialogInterface.OnDismissListener?) {
        this.onDismissListener = onDismissListener
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Android lifecycle
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logd(TAG, "onCreate() called with: savedInstanceState = [$savedInstanceState]")
        if (!checkStoragePermissions(requireActivity(), PermissionHelper.DOWNLOAD_DIALOG_REQUEST_CODE)) {
            dismiss()
            return
        }
        context = getContext()

        setStyle(STYLE_NO_TITLE, getDialogTheme(requireContext()))
        Icepick.restoreInstanceState(this, savedInstanceState)

        this.audioTrackAdapter = AudioTrackAdapter(wrappedAudioTracks!!)
        if (wrappedSubtitleStreams != null) this.subtitleStreamsAdapter = StreamItemAdapter(wrappedSubtitleStreams!!)
        updateSecondaryStreams()

        val intent = Intent(context, DownloadManagerService::class.java)
        requireContext().startService(intent)

        requireContext().bindService(intent, object : ServiceConnection {
            override fun onServiceConnected(cname: ComponentName, service: IBinder) {
                val mgr = service as DownloadManagerBinder
                mainStorageAudio = mgr.mainStorageAudio
                mainStorageVideo = mgr.mainStorageVideo
                downloadManager = mgr.downloadManager
                askForSavePath = mgr.askForSavePath()
                okButton!!.isEnabled = true
                requireContext().unbindService(this)
            }
            override fun onServiceDisconnected(name: ComponentName) {
                // nothing to do
            }
        }, Context.BIND_AUTO_CREATE)
    }

    /**
     * Update the displayed video streams based on the selected audio track.
     */
    private fun updateSecondaryStreams() {
        val audioStreams = wrappedAudioStreams
        val secondaryStreams = SparseArrayCompat<SecondaryStreamHelper<AudioStream>>(4)
        val videoStreams = wrappedVideoStreams!!.streamsList
        wrappedVideoStreams!!.resetInfo()

        for (i in videoStreams.indices) {
            if (!videoStreams[i].isVideoOnly()) continue

            val audioStream = SecondaryStreamHelper.getAudioStreamFor(requireContext(), audioStreams.streamsList, videoStreams[i])

            if (audioStream != null) secondaryStreams.append(i, SecondaryStreamHelper(audioStreams, audioStream))
            else if (DEBUG) {
                val mediaFormat = videoStreams[i].format
                if (mediaFormat != null) Log.w(TAG, "No audio stream candidates for video format ${mediaFormat.name}")
                else Log.w(TAG, "No audio stream candidates for unknown video format")
            }
        }

        this.videoStreamsAdapter = StreamItemAdapter(wrappedVideoStreams!!, secondaryStreams)
        this.audioStreamsAdapter = StreamItemAdapter(audioStreams)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Logd(TAG, "onCreateView() called with: inflater = [$inflater], container = [$container], savedInstanceState = [$savedInstanceState]")
        return inflater.inflate(R.layout.download_dialog, container)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dBinding = DownloadDialogBinding.bind(view)

        dBinding!!.fileName.setText(createFilename(requireContext(), currentInfo!!.name))
        selectedAudioIndex = getDefaultAudioFormat(requireContext(), wrappedAudioStreams.streamsList)

        selectedSubtitleIndex = getSubtitleIndexBy(subtitleStreamsAdapter!!.all)

        dBinding!!.qualitySpinner.onItemSelectedListener = this
        dBinding!!.audioStreamSpinner.onItemSelectedListener = this
        dBinding!!.audioTrackSpinner.onItemSelectedListener = this
        dBinding!!.videoAudioGroup.setOnCheckedChangeListener(this)

        initToolbar(dBinding!!.toolbarLayout.toolbar)
        setupDownloadOptions()

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val threads = prefs!!.getInt(getString(R.string.default_download_threads), 3)
        dBinding!!.threadsCount.text = threads.toString()
        dBinding!!.threads.progress = threads - 1
        dBinding!!.threads.setOnSeekBarChangeListener(object : SimpleOnSeekBarChangeListener() {
            override fun onProgressChanged(seekbar: SeekBar, progress: Int, fromUser: Boolean) {
                val newProgress = progress + 1
                prefs!!.edit().putInt(getString(R.string.default_download_threads), newProgress).apply()
                dBinding!!.threadsCount.text = newProgress.toString()
            }
        })
        fetchStreamsSize()
    }

    private fun initToolbar(toolbar: Toolbar) {
        Logd(TAG, "initToolbar() called with: toolbar = [$toolbar]")
        toolbar.setTitle(R.string.download_dialog_title)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.inflateMenu(R.menu.dialog_url)
        toolbar.setNavigationOnClickListener { v: View? -> dismiss() }
        toolbar.setNavigationContentDescription(R.string.cancel)

        okButton = toolbar.findViewById(R.id.okay)
        okButton?.setEnabled(false) // disable until the download service connection is done

        toolbar.setOnMenuItemClickListener { item: MenuItem ->
            if (item.itemId == R.id.okay) {
                prepareSelectedDownload()
                return@setOnMenuItemClickListener true
            }
            false
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.onDismiss(dialog)
    }

    override fun onDestroy() {
        super.onDestroy()
        disposables.clear()
    }

    override fun onDestroyView() {
        dBinding = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Icepick.saveInstanceState(this, outState)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Video, audio and subtitle spinners
    ////////////////////////////////////////////////////////////////////////// */
    private fun fetchStreamsSize() {
        disposables.clear()
        disposables.add(fetchMoreInfoForWrapper(wrappedVideoStreams!!).subscribe({ result: Boolean? ->
            if (dBinding!!.videoAudioGroup.checkedRadioButtonId == R.id.video_button) setupVideoSpinner() },
            { throwable: Throwable? -> showSnackbar(requireContext(), ErrorInfo(throwable!!, UserAction.DOWNLOAD_OPEN_DIALOG,
                "Downloading video stream size", currentInfo!!.serviceId)) }))
        disposables.add(fetchMoreInfoForWrapper(wrappedAudioStreams).subscribe({ result: Boolean? ->
                if (dBinding!!.videoAudioGroup.checkedRadioButtonId == R.id.audio_button) setupAudioSpinner() },
            { throwable: Throwable? -> showSnackbar(requireContext(), ErrorInfo(throwable!!, UserAction.DOWNLOAD_OPEN_DIALOG,
                    "Downloading audio stream size", currentInfo!!.serviceId)) }))
        disposables.add(fetchMoreInfoForWrapper(wrappedSubtitleStreams!!).subscribe({ result: Boolean? ->
            if (dBinding!!.videoAudioGroup.checkedRadioButtonId == R.id.subtitle_button) setupSubtitleSpinner() },
            { throwable: Throwable? -> showSnackbar(requireContext(), ErrorInfo(throwable!!, UserAction.DOWNLOAD_OPEN_DIALOG,
                "Downloading subtitle stream size", currentInfo!!.serviceId)) }))
    }

    private fun setupAudioTrackSpinner() {
        if (getContext() == null) return

        dBinding!!.audioTrackSpinner.adapter = audioTrackAdapter
        dBinding!!.audioTrackSpinner.setSelection(selectedAudioTrackIndex)
    }

    private fun setupAudioSpinner() {
        if (getContext() == null) return

        dBinding!!.qualitySpinner.visibility = View.GONE
        setRadioButtonsState(true)
        dBinding!!.audioStreamSpinner.adapter = audioStreamsAdapter
        dBinding!!.audioStreamSpinner.setSelection(selectedAudioIndex)
        dBinding!!.audioStreamSpinner.visibility = View.VISIBLE
        dBinding!!.audioTrackSpinner.visibility = if (wrappedAudioTracks!!.size() > 1) View.VISIBLE else View.GONE
        dBinding!!.audioTrackPresentInVideoText.visibility = View.GONE
    }

    private fun setupVideoSpinner() {
        if (getContext() == null) return

        dBinding!!.qualitySpinner.adapter = videoStreamsAdapter
        dBinding!!.qualitySpinner.setSelection(selectedVideoIndex)
        dBinding!!.qualitySpinner.visibility = View.VISIBLE
        setRadioButtonsState(true)
        dBinding!!.audioStreamSpinner.visibility = View.GONE
        onVideoStreamSelected()
    }

    private fun onVideoStreamSelected() {
        val isVideoOnly = videoStreamsAdapter!!.getItem(selectedVideoIndex).isVideoOnly()

        dBinding!!.audioTrackSpinner.visibility = if (isVideoOnly && wrappedAudioTracks!!.size() > 1) View.VISIBLE else View.GONE
        dBinding!!.audioTrackPresentInVideoText.visibility = if (!isVideoOnly && wrappedAudioTracks!!.size() > 1) View.VISIBLE else View.GONE
    }

    private fun setupSubtitleSpinner() {
        if (getContext() == null) return

        dBinding!!.qualitySpinner.adapter = subtitleStreamsAdapter
        dBinding!!.qualitySpinner.setSelection(selectedSubtitleIndex)
        dBinding!!.qualitySpinner.visibility = View.VISIBLE
        setRadioButtonsState(true)
        dBinding!!.audioStreamSpinner.visibility = View.GONE
        dBinding!!.audioTrackSpinner.visibility = View.GONE
        dBinding!!.audioTrackPresentInVideoText.visibility = View.GONE
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Activity results
    ////////////////////////////////////////////////////////////////////////// */
    private fun requestDownloadPickAudioFolderResult(result: ActivityResult) {
        requestDownloadPickFolderResult(result, getString(R.string.download_path_audio_key), DownloadManager.TAG_AUDIO)
    }

    private fun requestDownloadPickVideoFolderResult(result: ActivityResult) {
        requestDownloadPickFolderResult(result, getString(R.string.download_path_video_key), DownloadManager.TAG_VIDEO)
    }

    private fun requestDownloadSaveAsResult(result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) return

        if (result.data == null || result.data!!.data == null) {
            showFailedDialog(R.string.general_error)
            return
        }

        if (isOwnFileUri(requireContext(), result.data!!.data!!)) {
            val file = Utils.getFileForUri(result.data!!.data!!)
            checkSelectedDownload(null, Uri.fromFile(file), file.name, StoredFileHelper.DEFAULT_MIME)
            return
        }

        val docFile = DocumentFile.fromSingleUri(requireContext(), result.data!!.data!!)
        if (docFile == null) {
            showFailedDialog(R.string.general_error)
            return
        }

        // check if the selected file was previously used
        checkSelectedDownload(null, result.data!!.data, docFile.name, docFile.type)
    }

    private fun requestDownloadPickFolderResult(result: ActivityResult, key: String, tag: String) {
        if (result.resultCode != Activity.RESULT_OK) return

        if (result.data == null || result.data!!.data == null) {
            showFailedDialog(R.string.general_error)
            return
        }

        var uri = result.data!!.data
        if (isOwnFileUri(requireContext(), uri!!)) uri = Uri.fromFile(Utils.getFileForUri(uri))
        else requireContext().grantUriPermission(requireContext().packageName, uri, StoredDirectoryHelper.PERMISSION_FLAGS)

        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString(key, uri.toString()).apply()

        try {
            val mainStorage = StoredDirectoryHelper(requireContext(), uri!!, tag)
            checkSelectedDownload(mainStorage, mainStorage.findFile(filenameTmp!!), filenameTmp, mimeTmp)
        } catch (e: IOException) {
            showFailedDialog(R.string.general_error)
        }
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Listeners
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCheckedChanged(group: RadioGroup, @IdRes checkedId: Int) {
        Logd(TAG, "onCheckedChanged() called with: group = [$group], checkedId = [$checkedId]")
        var flag = true
        when (checkedId) {
            R.id.audio_button -> setupAudioSpinner()
            R.id.video_button -> setupVideoSpinner()
            R.id.subtitle_button -> {
                setupSubtitleSpinner()
                flag = false
            }
        }
        dBinding!!.threads.isEnabled = flag
    }

    override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
        Logd(TAG, "onItemSelected() called with: parent = [$parent], view = [$view], position = [$position], id = [$id]")

        when (parent.id) {
            R.id.quality_spinner -> {
                when (dBinding!!.videoAudioGroup.checkedRadioButtonId) {
                    R.id.video_button -> {
                        selectedVideoIndex = position
                        onVideoStreamSelected()
                    }
                    R.id.subtitle_button -> selectedSubtitleIndex = position
                }
                onItemSelectedSetFileName()
            }
            R.id.audio_track_spinner -> {
                val trackChanged = selectedAudioTrackIndex != position
                selectedAudioTrackIndex = position
                if (trackChanged) {
                    updateSecondaryStreams()
                    fetchStreamsSize()
                }
            }
            R.id.audio_stream_spinner -> selectedAudioIndex = position
        }
    }

    private fun onItemSelectedSetFileName() {
        val fileName = createFilename(requireContext(), currentInfo!!.name)
        val prevFileName = Optional.ofNullable(dBinding!!.fileName.text)
            .map { obj: Editable -> obj.toString() }
            .orElse("")

        if (prevFileName.isEmpty() || prevFileName == fileName || prevFileName.startsWith(getString(R.string.caption_file_name,
                    fileName,
                    ""))) {
            // only update the file name field if it was not edited by the user

            when (dBinding!!.videoAudioGroup.checkedRadioButtonId) {
                R.id.audio_button, R.id.video_button -> if (prevFileName != fileName) {
                    // since the user might have switched between audio and video, the correct
                    // text might already be in place, so avoid resetting the cursor position
                    dBinding!!.fileName.setText(fileName)
                }
                R.id.subtitle_button -> {
                    if (subtitleStreamsAdapter != null) {
                        val setSubtitleLanguageCode = subtitleStreamsAdapter!!.getItem(selectedSubtitleIndex).languageTag
                        // this will reset the cursor position, which is bad UX, but it can't be avoided
                        dBinding!!.fileName.setText(getString(R.string.caption_file_name,
                            fileName,
                            setSubtitleLanguageCode))
                    }
                }
            }
        }
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {}

    /*//////////////////////////////////////////////////////////////////////////
    // Download
    ////////////////////////////////////////////////////////////////////////// */
    protected fun setupDownloadOptions() {
        setRadioButtonsState(false)
        setupAudioTrackSpinner()

        val isVideoStreamsAvailable = videoStreamsAdapter!!.count > 0
        val isAudioStreamsAvailable = audioStreamsAdapter!!.count > 0
        val isSubtitleStreamsAvailable = subtitleStreamsAdapter!!.count > 0

        dBinding!!.audioButton.visibility = if (isAudioStreamsAvailable) View.VISIBLE else View.GONE
        dBinding!!.videoButton.visibility = if (isVideoStreamsAvailable) View.VISIBLE else View.GONE
        dBinding!!.subtitleButton.visibility = if (isSubtitleStreamsAvailable) View.VISIBLE else View.GONE

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val defaultMedia = prefs!!.getString(getString(R.string.last_used_download_type), getString(R.string.last_download_type_video_key))

        when {
            isVideoStreamsAvailable && (defaultMedia == getString(R.string.last_download_type_video_key)) -> {
                dBinding!!.videoButton.isChecked = true
                setupVideoSpinner()
            }
            isAudioStreamsAvailable && (defaultMedia == getString(R.string.last_download_type_audio_key)) -> {
                dBinding!!.audioButton.isChecked = true
                setupAudioSpinner()
            }
            isSubtitleStreamsAvailable && (defaultMedia == getString(R.string.last_download_type_subtitle_key)) -> {
                dBinding!!.subtitleButton.isChecked = true
                setupSubtitleSpinner()
            }
            isVideoStreamsAvailable -> {
                dBinding!!.videoButton.isChecked = true
                setupVideoSpinner()
            }
            isAudioStreamsAvailable -> {
                dBinding!!.audioButton.isChecked = true
                setupAudioSpinner()
            }
            isSubtitleStreamsAvailable -> {
                dBinding!!.subtitleButton.isChecked = true
                setupSubtitleSpinner()
            }
            else -> {
                Toast.makeText(getContext(), R.string.no_streams_available_download, Toast.LENGTH_SHORT).show()
                dismiss()
            }
        }
    }

    private fun setRadioButtonsState(enabled: Boolean) {
        dBinding!!.audioButton.isEnabled = enabled
        dBinding!!.videoButton.isEnabled = enabled
        dBinding!!.subtitleButton.isEnabled = enabled
    }

    private val wrappedAudioStreams: StreamInfoWrapper<AudioStream>
        get() {
            if (selectedAudioTrackIndex < 0 || selectedAudioTrackIndex > wrappedAudioTracks!!.size()) return empty()
            return wrappedAudioTracks!!.tracksList[selectedAudioTrackIndex]
        }

    private fun getSubtitleIndexBy(streams: List<SubtitlesStream>): Int {
        val preferredLocalization = NewPipe.getPreferredLocalization()

        var candidate = 0
        for (i in streams.indices) {
            val streamLocale = streams[i].locale

            val languageEquals = streamLocale.language != null && preferredLocalization.languageCode != null
                    && (streamLocale.language == Locale(preferredLocalization.languageCode).language)
            val countryEquals = streamLocale.country != null && streamLocale.country == preferredLocalization.countryCode

            if (languageEquals) {
                if (countryEquals) return i
                candidate = i
            }
        }
        return candidate
    }

    private val nameEditText: String
        get() {
            val str = Objects.requireNonNull(dBinding!!.fileName.text).toString().trim { it <= ' ' }
            return createFilename(requireContext(), if (str.isEmpty()) currentInfo!!.name else str)
        }

    private fun showFailedDialog(@StringRes msg: Int) {
        assureCorrectAppLanguage(requireContext())
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.general_error)
            .setMessage(msg)
            .setNegativeButton(getString(R.string.ok), null)
            .show()
    }

    private fun launchDirectoryPicker(launcher: ActivityResultLauncher<Intent>) {
        launchSafe(launcher, getPicker(requireContext()), TAG, context)
    }

    private fun prepareSelectedDownload() {
        val mainStorage: StoredDirectoryHelper?
        val format: MediaFormat?
        val selectedMediaType: String

        // first, build the filename and get the output folder (if possible)
        // later, run a very very very large file checking logic
        filenameTmp = nameEditText + "."

        when (dBinding!!.videoAudioGroup.checkedRadioButtonId) {
            R.id.audio_button -> {
                selectedMediaType = getString(R.string.last_download_type_audio_key)
                mainStorage = mainStorageAudio
                format = audioStreamsAdapter!!.getItem(selectedAudioIndex).format
                if (format == MediaFormat.WEBMA_OPUS) {
                    mimeTmp = "audio/ogg"
                    filenameTmp += "opus"
                } else if (format != null) {
                    mimeTmp = format.mimeType
                    filenameTmp += format.getSuffix()
                }
            }
            R.id.video_button -> {
                selectedMediaType = getString(R.string.last_download_type_video_key)
                mainStorage = mainStorageVideo
                format = videoStreamsAdapter!!.getItem(selectedVideoIndex).format
                if (format != null) {
                    mimeTmp = format.mimeType
                    filenameTmp += format.getSuffix()
                }
            }
            R.id.subtitle_button -> {
                selectedMediaType = getString(R.string.last_download_type_subtitle_key)
                mainStorage = mainStorageVideo // subtitle & video files go together
                format = subtitleStreamsAdapter!!.getItem(selectedSubtitleIndex).format
                if (format != null) mimeTmp = format.mimeType

                if (format == MediaFormat.TTML) filenameTmp += MediaFormat.SRT.getSuffix()
                else if (format != null) filenameTmp += format.getSuffix()
            }
            else -> throw RuntimeException("No stream selected")
        }
        if (!askForSavePath && (mainStorage == null || mainStorage.isDirect == useStorageAccessFramework(requireContext()) || mainStorage.isInvalidSafStorage)) {
            // Pick new download folder if one of:
            // - Download folder is not set
            // - Download folder uses SAF while SAF is disabled
            // - Download folder doesn't use SAF while SAF is enabled
            // - Download folder uses SAF but the user manually revoked access to it
            Toast.makeText(context, getString(R.string.no_dir_yet), Toast.LENGTH_LONG).show()

            if (dBinding!!.videoAudioGroup.checkedRadioButtonId == R.id.audio_button) launchDirectoryPicker(requestDownloadPickAudioFolderLauncher)
            else launchDirectoryPicker(requestDownloadPickVideoFolderLauncher)
            return
        }

        if (askForSavePath) {
            val initialPath: Uri?
            if (useStorageAccessFramework(requireContext())) initialPath = null
            else {
                val initialSavePath = if (dBinding!!.videoAudioGroup.checkedRadioButtonId == R.id.audio_button) getDir(Environment.DIRECTORY_MUSIC)
                else getDir(Environment.DIRECTORY_MOVIES)
                initialPath = Uri.parse(initialSavePath.absolutePath)
            }
            launchSafe(requestDownloadSaveAsLauncher, getNewPicker(requireContext(), filenameTmp, mimeTmp!!, initialPath), TAG, context)
            return
        }

        // check for existing file with the same name
        checkSelectedDownload(mainStorage, mainStorage!!.findFile(filenameTmp!!), filenameTmp, mimeTmp)

        // remember the last media type downloaded by the user
        prefs!!.edit().putString(getString(R.string.last_used_download_type), selectedMediaType).apply()
    }

    private fun checkSelectedDownload(mainStorage: StoredDirectoryHelper?, targetFile: Uri?, filename: String?, mime: String?) {
        var storage: StoredFileHelper?

        try {
            // using SAF on older android version
            storage = when {
                mainStorage == null -> StoredFileHelper(context, null, targetFile!!, "")
                // the file does not exist, but it is probably used in a pending download
                targetFile == null -> StoredFileHelper(mainStorage.uri, filename, mime, mainStorage.tag)
                // the target filename is already use, attempt to use it
                else -> StoredFileHelper(context, mainStorage.uri, targetFile, mainStorage.tag)
            }
        } catch (e: Exception) {
            createNotification(requireContext(), ErrorInfo(e, UserAction.DOWNLOAD_FAILED, "Getting storage"))
            return
        }

        // get state of potential mission referring to the same file
        val state = downloadManager!!.checkForExistingMission(storage)
        @StringRes val msgBtn: Int
        @StringRes val msgBody: Int

        when (state) {
            DownloadManager.MissionState.Finished -> {
                msgBtn = R.string.overwrite
                msgBody = R.string.overwrite_finished_warning
            }
            MissionState.Pending -> {
                msgBtn = R.string.overwrite
                msgBody = R.string.download_already_pending
            }
            MissionState.PendingRunning -> {
                msgBtn = R.string.generate_unique_name
                msgBody = R.string.download_already_running
            }
            MissionState.None -> {
                when {
                    mainStorage == null -> {
                        // This part is called if:
                        // * using SAF on older android version
                        // * save path not defined
                        // * if the file exists overwrite it, is not necessary ask
                        if (!storage.existsAsFile() && !storage.create()) {
                            showFailedDialog(R.string.error_file_creation)
                            return
                        }
                        continueSelectedDownload(storage)
                        return
                    }
                    targetFile == null -> {
                        // This part is called if:
                        // * the filename is not used in a pending/finished download
                        // * the file does not exists, create
                        if (!mainStorage.mkdirs()) {
                            showFailedDialog(R.string.error_path_creation)
                            return
                        }
                        storage = mainStorage.createFile(filename!!, mime!!)
                        if (storage == null || !storage.canWrite()) {
                            showFailedDialog(R.string.error_file_creation)
                            return
                        }
                        continueSelectedDownload(storage)
                        return
                    }
                    else -> {
                        msgBtn = R.string.overwrite
                        msgBody = R.string.overwrite_unrelated_warning
                    }
                }
            }
        }
        val askDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.download_dialog_title)
            .setMessage(msgBody)
            .setNegativeButton(R.string.cancel, null)
        val finalStorage: StoredFileHelper = storage


        if (mainStorage == null) {
            // This part is called if:
            // * using SAF on older android version
            // * save path not defined
            when (state) {
                MissionState.Pending, MissionState.Finished -> askDialog.setPositiveButton(msgBtn) { dialog: DialogInterface, which: Int ->
                    dialog.dismiss()
                    downloadManager!!.forgetMission(finalStorage)
                    continueSelectedDownload(finalStorage)
                }
                else -> {}
            }
            askDialog.show()
            return
        }

        askDialog.setPositiveButton(msgBtn) { dialog: DialogInterface, which: Int ->
            dialog.dismiss()
            var storageNew: StoredFileHelper?
            when (state) {
                MissionState.Finished, MissionState.Pending -> {
                    downloadManager!!.forgetMission(finalStorage)
                    if (targetFile == null) storageNew = mainStorage.createFile(filename!!, mime!!)
                    else {
                        try {
                            // try take (or steal) the file
                            storageNew = StoredFileHelper(context, mainStorage.uri, targetFile, mainStorage.tag)
                        } catch (e: IOException) {
                            Log.e(TAG, "Failed to take (or steal) the file in $targetFile")
                            storageNew = null
                        }
                    }

                    if (storageNew != null && storageNew.canWrite()) continueSelectedDownload(storageNew)
                    else showFailedDialog(R.string.error_file_creation)
                }
                MissionState.None -> {
                    if (targetFile == null) storageNew = mainStorage.createFile(filename!!, mime!!)
                    else {
                        try {
                            storageNew = StoredFileHelper(context, mainStorage.uri, targetFile, mainStorage.tag)
                        } catch (e: IOException) {
                            Log.e(TAG, "Failed to take (or steal) the file in $targetFile")
                            storageNew = null
                        }
                    }
                    if (storageNew != null && storageNew.canWrite()) continueSelectedDownload(storageNew)
                    else showFailedDialog(R.string.error_file_creation)
                }
                MissionState.PendingRunning -> {
                    storageNew = mainStorage.createUniqueFile(filename!!, mime!!)
                    if (storageNew == null) showFailedDialog(R.string.error_file_creation)
                    else continueSelectedDownload(storageNew)
                }
            }
        }

        askDialog.show()
    }

    private fun continueSelectedDownload(storage: StoredFileHelper) {
        if (!storage.canWrite()) {
            showFailedDialog(R.string.permission_denied)
            return
        }

        // check if the selected file has to be overwritten, by simply checking its length
        try {
            if (storage.length() > 0) storage.truncate()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to truncate the file: " + storage.uri.toString(), e)
            showFailedDialog(R.string.overwrite_failed)
            return
        }

        val selectedStream: Stream
        var secondaryStream: Stream? = null
        val kind: Char
        var threads = dBinding!!.threads.progress + 1
        val urls: Array<String?>
        val recoveryInfo: List<MissionRecoveryInfo?>
        var psName: String? = null
        var psArgs: Array<String?>? = null
        var nearLength: Long = 0

        when (dBinding!!.videoAudioGroup.checkedRadioButtonId) {
            R.id.audio_button -> {
                kind = 'a'
                selectedStream = audioStreamsAdapter!!.getItem(selectedAudioIndex)
                if (selectedStream.getFormat() == MediaFormat.M4A) psName = Postprocessing.ALGORITHM_M4A_NO_DASH
                else if (selectedStream.getFormat() == MediaFormat.WEBMA_OPUS) psName = Postprocessing.ALGORITHM_OGG_FROM_WEBM_DEMUXER
            }
            R.id.video_button -> {
                kind = 'v'
                selectedStream = videoStreamsAdapter!!.getItem(selectedVideoIndex)
                val secondary = videoStreamsAdapter!!.allSecondary[wrappedVideoStreams!!.streamsList.indexOf(selectedStream)]

                if (secondary != null) {
                    secondaryStream = secondary.stream
                    psName = if (selectedStream.getFormat() == MediaFormat.MPEG_4) Postprocessing.ALGORITHM_MP4_FROM_DASH_MUXER
                    else Postprocessing.ALGORITHM_WEBM_MUXER

                    val videoSize = wrappedVideoStreams!!.getSizeInBytes(selectedStream)

                    // set nearLength, only, if both sizes are fetched or known. This probably
                    // does not work on slow networks but is later updated in the downloader
                    if (secondary.sizeInBytes > 0 && videoSize > 0) nearLength = secondary.sizeInBytes + videoSize
                }
            }
            R.id.subtitle_button -> {
                threads = 1 // use unique thread for subtitles due small file size
                kind = 's'
                selectedStream = subtitleStreamsAdapter!!.getItem(selectedSubtitleIndex)

                if (selectedStream.getFormat() == MediaFormat.TTML) {
                    psName = Postprocessing.ALGORITHM_TTML_CONVERTER
                    psArgs = arrayOf(selectedStream.getFormat()!!.getSuffix(), "false") // ignore empty frames
                }
            }
            else -> return
        }
        if (secondaryStream == null) {
            urls = arrayOf(selectedStream.content)
            recoveryInfo = listOf(MissionRecoveryInfo(selectedStream))
        } else {
            require(secondaryStream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP) {
                ("Unsupported stream delivery format" + secondaryStream.deliveryMethod)
            }
            urls = arrayOf(selectedStream.content, secondaryStream.content)
            recoveryInfo = listOf(MissionRecoveryInfo(selectedStream), MissionRecoveryInfo(secondaryStream))
        }

        startMission(requireContext(), urls, storage, kind, threads, currentInfo!!.url, psName, psArgs, nearLength, ArrayList(recoveryInfo))

        Toast.makeText(context, getString(R.string.download_has_started), Toast.LENGTH_SHORT).show()
        dismiss()
    }

    class AudioTrackAdapter(private val tracksWrapper: AudioTracksWrapper) : BaseAdapter() {
        override fun getCount(): Int {
            return tracksWrapper.size()
        }

        override fun getItem(position: Int): List<AudioStream> {
            return tracksWrapper.tracksList[position].streamsList
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getView(position: Int, convertView: View, parent: ViewGroup): View {
            val context = parent.context
            val view = convertView

            val woSoundIconView = view.findViewById<ImageView>(R.id.wo_sound_icon)
            val formatNameView = view.findViewById<TextView>(R.id.stream_format_name)
            val qualityView = view.findViewById<TextView>(R.id.stream_quality)
            val sizeView = view.findViewById<TextView>(R.id.stream_size)

            val streams = getItem(position)
            val stream = streams[0]

            woSoundIconView.visibility = View.GONE
            sizeView.visibility = View.VISIBLE

            if (stream.audioTrackId != null) formatNameView.text = stream.audioTrackId
            qualityView.text = Localization.audioTrackName(context, stream)

            return view
        }

        class AudioTracksWrapper(groupedAudioStreams: List<List<AudioStream>>, context: Context?) : Serializable {
            @JvmField
            val tracksList: List<StreamInfoWrapper<AudioStream>> =
                groupedAudioStreams.stream().map { streams: List<AudioStream> -> StreamInfoWrapper(streams, context) }.collect(Collectors.toList())

            fun size(): Int {
                return tracksList.size
            }
        }
    }

    companion object {
        private const val TAG = "DialogFragment"
        private const val DEBUG = MainActivity.DEBUG
    }
}
