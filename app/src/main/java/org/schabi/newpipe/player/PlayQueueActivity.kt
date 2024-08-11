package org.schabi.newpipe.player

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import org.schabi.newpipe.ui.QueueItemMenuUtil.openPopupMenu
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ActivityPlayerQueueControlBinding
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.ui.OnScrollBelowItemsListener
import org.schabi.newpipe.ui.local.dialog.PlaylistDialog.Companion.showForPlayQueue
import org.schabi.newpipe.player.PlayerService.LocalBinder
import org.schabi.newpipe.player.event.PlayerEventListener
import org.schabi.newpipe.player.helper.PlaybackParameterDialog
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.player.mediaitem.MediaItemTag
import org.schabi.newpipe.player.playqueue.*
import org.schabi.newpipe.util.Localization.assureCorrectAppLanguage
import org.schabi.newpipe.util.Localization.audioTrackName
import org.schabi.newpipe.util.Localization.getDurationString
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.util.NavigationHelper.openSettings
import org.schabi.newpipe.util.NavigationHelper.playOnBackgroundPlayer
import org.schabi.newpipe.util.NavigationHelper.playOnMainPlayer
import org.schabi.newpipe.util.NavigationHelper.playOnPopupPlayer
import org.schabi.newpipe.util.PermissionHelper.isPopupEnabledElseAsk
import org.schabi.newpipe.util.ServiceHelper.getSelectedServiceId
import org.schabi.newpipe.util.ThemeHelper.setTheme
import java.util.*
import java.util.function.Function
import kotlin.math.abs
import kotlin.math.min

@UnstableApi class PlayQueueActivity : AppCompatActivity(), PlayerEventListener, OnSeekBarChangeListener, View.OnClickListener,
    PlaybackParameterDialog.Callback {
    private var playerManager: PlayerManager? = null

    private var serviceBound = false
    private var serviceConnection: ServiceConnection? = null

    private var seeking = false

    ////////////////////////////////////////////////////////////////////////////
    // Views
    ////////////////////////////////////////////////////////////////////////////
    private var queueControlBinding: ActivityPlayerQueueControlBinding? = null

    private var itemTouchHelper: ItemTouchHelper? = null

    private var menu: Menu? = null

    ////////////////////////////////////////////////////////////////////////////
    // Activity Lifecycle
    ////////////////////////////////////////////////////////////////////////////
    override fun onCreate(savedInstanceState: Bundle?) {
        assureCorrectAppLanguage(this)
        super.onCreate(savedInstanceState)
        setTheme(this, getSelectedServiceId(this))

        queueControlBinding = ActivityPlayerQueueControlBinding.inflate(layoutInflater)
        setContentView(queueControlBinding!!.root)

        setSupportActionBar(queueControlBinding!!.toolbar)
        if (supportActionBar != null) {
            supportActionBar!!.setDisplayHomeAsUpEnabled(true)
            supportActionBar!!.setTitle(R.string.title_activity_play_queue)
        }

        serviceConnection = getServiceConnection()
        bind()
    }

    override fun onCreateOptionsMenu(m: Menu): Boolean {
        this.menu = m
        menuInflater.inflate(R.menu.menu_play_queue, m)
        menuInflater.inflate(R.menu.menu_play_queue_bg, m)
        buildAudioTrackMenu()
        onMaybeMuteChanged()
        // to avoid null reference
        if (playerManager != null) {
            onPlaybackParameterChanged(playerManager!!.playbackParameters)
        }
        return true
    }

    // Allow to setup visibility of menuItems
    override fun onPrepareOptionsMenu(m: Menu): Boolean {
        if (playerManager != null) {
            menu!!.findItem(R.id.action_switch_popup)
                .setVisible(!playerManager!!.popupPlayerSelected())
            menu!!.findItem(R.id.action_switch_background)
                .setVisible(!playerManager!!.audioPlayerSelected())
        }
        return super.onPrepareOptionsMenu(m)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            R.id.action_settings -> {
                openSettings(this)
                return true
            }
            R.id.action_append_playlist -> {
                showForPlayQueue(playerManager!!, supportFragmentManager)
                return true
            }
            R.id.action_playback_speed -> {
                openPlaybackParameterDialog()
                return true
            }
            R.id.action_mute -> {
                playerManager?.toggleMute()
                return true
            }
            R.id.action_system_audio -> {
                startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
                return true
            }
            R.id.action_switch_main -> {
                playerManager?.setRecovery()
                playOnMainPlayer(this, playerManager!!.playQueue!!, true)
                return true
            }
            R.id.action_switch_popup -> {
                if (playerManager != null && isPopupEnabledElseAsk(this)) {
                    playerManager?.setRecovery()
                    playOnPopupPlayer(this, playerManager!!.playQueue, true)
                }
                return true
            }
            R.id.action_switch_background -> {
                if (playerManager != null) {
                    playerManager?.setRecovery()
                    playOnBackgroundPlayer(this, playerManager!!.playQueue, true)
                }
                return true
            }
        }
        if (item.groupId == MENU_ID_AUDIO_TRACK) {
            onAudioTrackClick(item.itemId)
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        unbind()
    }

    ////////////////////////////////////////////////////////////////////////////
    // Service Connection
    ////////////////////////////////////////////////////////////////////////////
    private fun bind() {
        val bindIntent = Intent(this, PlayerService::class.java)
        val success = bindService(bindIntent, serviceConnection!!, BIND_AUTO_CREATE)
        if (!success) {
            unbindService(serviceConnection!!)
        }
        serviceBound = success
    }

    private fun unbind() {
        if (serviceBound) {
            unbindService(serviceConnection!!)
            serviceBound = false
            playerManager?.removeActivityListener(this)

            onQueueUpdate(null)
            itemTouchHelper?.attachToRecyclerView(null)

            itemTouchHelper = null
            playerManager = null
        }
    }

    private fun getServiceConnection(): ServiceConnection {
        return object : ServiceConnection {
            override fun onServiceDisconnected(name: ComponentName) {
                Logd(TAG, "Player service is disconnected")
            }

            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                Logd(TAG, "Player service is connected")
                if (service is LocalBinder) playerManager = service.getPlayer()

                if (playerManager == null || playerManager!!.playQueue == null || playerManager!!.exoPlayerIsNull()) {
                    unbind()
                } else {
                    onQueueUpdate(playerManager!!.playQueue)
                    buildComponents()
                    playerManager?.setActivityListener(this@PlayQueueActivity)
                }
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////
    // Component Building
    ////////////////////////////////////////////////////////////////////////////
    private fun buildComponents() {
        buildQueue()
        buildMetadata()
        buildSeekBar()
        buildControls()
    }

    private fun buildQueue() {
        queueControlBinding!!.playQueue.layoutManager = LinearLayoutManager(this)
        queueControlBinding!!.playQueue.isClickable = true
        queueControlBinding!!.playQueue.isLongClickable = true
        queueControlBinding!!.playQueue.clearOnScrollListeners()
        queueControlBinding!!.playQueue.addOnScrollListener(queueScrollListener)

        itemTouchHelper = ItemTouchHelper(itemTouchCallback)
        itemTouchHelper!!.attachToRecyclerView(queueControlBinding!!.playQueue)
    }

    private fun buildMetadata() {
        queueControlBinding!!.metadata.setOnClickListener(this)
        queueControlBinding!!.songName.isSelected = true
        queueControlBinding!!.artistName.isSelected = true
    }

    private fun buildSeekBar() {
        queueControlBinding!!.seekBar.setOnSeekBarChangeListener(this)
        queueControlBinding!!.liveSync.setOnClickListener(this)
    }

    private fun buildControls() {
        queueControlBinding!!.controlRepeat.setOnClickListener(this)
        queueControlBinding!!.controlBackward.setOnClickListener(this)
        queueControlBinding!!.controlFastRewind.setOnClickListener(this)
        queueControlBinding!!.controlPlayPause.setOnClickListener(this)
        queueControlBinding!!.controlFastForward.setOnClickListener(this)
        queueControlBinding!!.controlForward.setOnClickListener(this)
        queueControlBinding!!.controlShuffle.setOnClickListener(this)
    }

    private val queueScrollListener: OnScrollBelowItemsListener
        ////////////////////////////////////////////////////////////////////////////
        get() = object : OnScrollBelowItemsListener() {
            override fun onScrolledDown(recyclerView: RecyclerView?) {
                if (playerManager != null && playerManager!!.playQueue != null && !playerManager!!.playQueue!!.isComplete) {
                    playerManager!!.playQueue!!.fetch()
                } else {
                    queueControlBinding!!.playQueue.clearOnScrollListeners()
                }
            }
        }

    private val itemTouchCallback: ItemTouchHelper.SimpleCallback
        get() = object : PlayQueueItemTouchCallback() {
            override fun onMove(sourceIndex: Int, targetIndex: Int) {
                playerManager?.playQueue?.move(sourceIndex, targetIndex)
            }

            override fun onSwiped(index: Int) {
                if (index != -1) {
                    playerManager!!.playQueue!!.remove(index)
                }
            }
        }

    private val onSelectedListener: PlayQueueItemBuilder.OnSelectedListener
        get() = object : PlayQueueItemBuilder.OnSelectedListener {
            override fun selected(item: PlayQueueItem, view: View) {
                playerManager?.selectQueueItem(item)
            }

            override fun held(item: PlayQueueItem, view: View) {
                if (playerManager != null && playerManager!!.playQueue!!.indexOf(item) != -1) {
                    openPopupMenu(playerManager!!.playQueue!!, item, view, false,
                        supportFragmentManager, this@PlayQueueActivity)
                }
            }

            override fun onStartDrag(viewHolder: PlayQueueItemHolder) {
                itemTouchHelper?.startDrag(viewHolder)
            }
        }

    private fun scrollToSelected() {
        if (playerManager == null) return

        val currentPlayingIndex = playerManager!!.playQueue!!.index
        val currentVisibleIndex: Int
        if (queueControlBinding!!.playQueue.layoutManager is LinearLayoutManager) {
            val layout =
                queueControlBinding!!.playQueue.layoutManager as LinearLayoutManager?
            currentVisibleIndex = layout!!.findFirstVisibleItemPosition()
        } else {
            currentVisibleIndex = 0
        }

        val distance = abs((currentPlayingIndex - currentVisibleIndex).toDouble()).toInt()
        if (distance < SMOOTH_SCROLL_MAXIMUM_DISTANCE) {
            queueControlBinding!!.playQueue.smoothScrollToPosition(currentPlayingIndex)
        } else {
            queueControlBinding!!.playQueue.scrollToPosition(currentPlayingIndex)
        }
    }

    ////////////////////////////////////////////////////////////////////////////
    // Component On-Click Listener
    ////////////////////////////////////////////////////////////////////////////
    override fun onClick(view: View) {
        if (playerManager == null) return

        when (view.id) {
            queueControlBinding!!.controlRepeat.id -> {
                playerManager!!.cycleNextRepeatMode()
            }
            queueControlBinding!!.controlBackward.id -> {
                playerManager!!.playPrevious()
            }
            queueControlBinding!!.controlFastRewind.id -> {
                playerManager!!.fastRewind()
            }
            queueControlBinding!!.controlPlayPause.id -> {
                playerManager!!.playPause()
            }
            queueControlBinding!!.controlFastForward.id -> {
                playerManager!!.fastForward()
            }
            queueControlBinding!!.controlForward.id -> {
                playerManager!!.playNext()
            }
            queueControlBinding!!.controlShuffle.id -> {
                playerManager!!.toggleShuffleModeEnabled()
            }
            queueControlBinding!!.metadata.id -> {
                scrollToSelected()
            }
            queueControlBinding!!.liveSync.id -> {
                playerManager!!.seekToDefault()
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////
    // Playback Parameters
    ////////////////////////////////////////////////////////////////////////////
    private fun openPlaybackParameterDialog() {
        if (playerManager == null) return

        PlaybackParameterDialog.newInstance(playerManager!!.playbackSpeed.toDouble(), playerManager!!.playbackPitch.toDouble(),
            playerManager!!.playbackSkipSilence, this).show(supportFragmentManager, TAG)
    }

    override fun onPlaybackParameterChanged(playbackTempo: Float, playbackPitch: Float,
                                            playbackSkipSilence: Boolean
    ) {
        if (playerManager != null) {
            playerManager!!.setPlaybackParameters(playbackTempo, playbackPitch, playbackSkipSilence)
            onPlaybackParameterChanged(playerManager!!.playbackParameters)
        }
    }

    ////////////////////////////////////////////////////////////////////////////
    // Seekbar Listener
    ////////////////////////////////////////////////////////////////////////////
    override fun onProgressChanged(seekBar: SeekBar, progress: Int,
                                   fromUser: Boolean
    ) {
        if (fromUser) {
            val seekTime = getDurationString((progress / 1000).toLong())
            queueControlBinding!!.currentTime.text = seekTime
            queueControlBinding!!.seekDisplay.text = seekTime
        }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        seeking = true
        queueControlBinding!!.seekDisplay.visibility = View.VISIBLE
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        if (playerManager != null) {
            playerManager!!.seekTo(seekBar.progress.toLong())
        }
        queueControlBinding!!.seekDisplay.visibility = View.GONE
        seeking = false
    }

    ////////////////////////////////////////////////////////////////////////////
    // Binding Service Listener
    ////////////////////////////////////////////////////////////////////////////
    override fun onQueueUpdate(queue: PlayQueue?) {
        if (queue == null) {
            queueControlBinding!!.playQueue.adapter = null
        } else {
            val adapter = PlayQueueAdapter(this, queue)
            adapter.setSelectedListener(onSelectedListener)
            queueControlBinding!!.playQueue.adapter = adapter
        }
    }

    override fun onPlaybackUpdate(state: Int, repeatMode: Int, shuffled: Boolean,
                                  parameters: PlaybackParameters?) {
        onStateChanged(state)
        onPlayModeChanged(repeatMode, shuffled)
        onPlaybackParameterChanged(parameters)
        onMaybeMuteChanged()
    }

    override fun onProgressUpdate(currentProgress: Int, duration: Int,
                                  bufferPercent: Int) {
        // Set buffer progress
        queueControlBinding!!.seekBar.secondaryProgress = (queueControlBinding!!.seekBar.max
                * (bufferPercent.toFloat() / 100)).toInt()

        // Set Duration
        queueControlBinding!!.seekBar.max = duration
        queueControlBinding!!.endTime.text = getDurationString((duration / 1000).toLong())

        // Set current time if not seeking
        if (!seeking) {
            queueControlBinding!!.seekBar.progress = currentProgress
            queueControlBinding!!.currentTime.text = getDurationString((currentProgress / 1000).toLong())
        }

        if (playerManager != null) {
            queueControlBinding!!.liveSync.isClickable = !playerManager!!.isLiveEdge
        }

        // this will make sure progressCurrentTime has the same width as progressEndTime
        val currentTimeParams = queueControlBinding!!.currentTime.layoutParams
        currentTimeParams.width = queueControlBinding!!.endTime.width
        queueControlBinding!!.currentTime.layoutParams = currentTimeParams
    }

    override fun onMetadataUpdate(info: StreamInfo?, queue: PlayQueue?) {
        if (info != null) {
            queueControlBinding!!.songName.text = info.name
            queueControlBinding!!.artistName.text = info.uploaderName

            queueControlBinding!!.endTime.visibility = View.GONE
            queueControlBinding!!.liveSync.visibility = View.GONE
            when (info.streamType) {
                StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM -> queueControlBinding!!.liveSync.visibility = View.VISIBLE
                else -> queueControlBinding!!.endTime.visibility = View.VISIBLE
            }
            scrollToSelected()
        }
    }

    override fun onServiceStopped() {
        unbind()
        finish()
    }

    ////////////////////////////////////////////////////////////////////////////
    // Binding Service Helper
    ////////////////////////////////////////////////////////////////////////////
    private fun onStateChanged(state: Int) {
        val playPauseButton = queueControlBinding!!.controlPlayPause
        when (state) {
            PlayerManager.STATE_PAUSED -> {
                playPauseButton.setImageResource(R.drawable.ic_play_arrow)
                playPauseButton.contentDescription = getString(R.string.play)
            }
            PlayerManager.STATE_PLAYING -> {
                playPauseButton.setImageResource(R.drawable.ic_pause)
                playPauseButton.contentDescription = getString(R.string.pause)
            }
            PlayerManager.STATE_COMPLETED -> {
                playPauseButton.setImageResource(R.drawable.ic_replay)
                playPauseButton.contentDescription = getString(R.string.replay)
            }
            else -> {}
        }
        when (state) {
            PlayerManager.STATE_PAUSED, PlayerManager.STATE_PLAYING, PlayerManager.STATE_COMPLETED -> {
                queueControlBinding!!.controlPlayPause.isClickable = true
                queueControlBinding!!.controlPlayPause.visibility = View.VISIBLE
                queueControlBinding!!.controlProgressBar.visibility = View.GONE
            }
            else -> {
                queueControlBinding!!.controlPlayPause.isClickable = false
                queueControlBinding!!.controlPlayPause.visibility = View.INVISIBLE
                queueControlBinding!!.controlProgressBar.visibility = View.VISIBLE
            }
        }
    }

    private fun onPlayModeChanged(repeatMode: Int, shuffled: Boolean) {
        when (repeatMode) {
            androidx.media3.common.Player.REPEAT_MODE_OFF -> queueControlBinding!!.controlRepeat
                .setImageResource(R.drawable.exo_styled_controls_repeat_off)
            androidx.media3.common.Player.REPEAT_MODE_ONE -> queueControlBinding!!.controlRepeat
                .setImageResource(R.drawable.exo_styled_controls_repeat_one)
            androidx.media3.common.Player.REPEAT_MODE_ALL -> queueControlBinding!!.controlRepeat
                .setImageResource(R.drawable.exo_styled_controls_repeat_all)
        }
        val shuffleAlpha = if (shuffled) 255 else 77
        queueControlBinding!!.controlShuffle.imageAlpha = shuffleAlpha
    }

    private fun onPlaybackParameterChanged(parameters: PlaybackParameters?) {
        if (parameters != null && menu != null && playerManager != null) {
            val item = menu!!.findItem(R.id.action_playback_speed)
            item.setTitle(PlayerHelper.formatSpeed(parameters.speed.toDouble()))
        }
    }

    private fun onMaybeMuteChanged() {
        if (menu != null && playerManager != null) {
            val item = menu!!.findItem(R.id.action_mute)

            //Change the mute-button item in ActionBar
            //1) Text change:
            item.setTitle(if (playerManager!!.isMuted) R.string.unmute else R.string.mute)

            //2) Icon change accordingly to current App Theme
            // using rootView.getContext() because getApplicationContext() didn't work
            item.setIcon(if (playerManager!!.isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        }
    }

    override fun onAudioTrackUpdate() {
        buildAudioTrackMenu()
    }

    private fun buildAudioTrackMenu() {
        if (menu == null) return

        val audioTrackSelector = menu!!.findItem(R.id.action_audio_track)
        val availableStreams = Optional.ofNullable<PlayerManager>(playerManager)
            .map<MediaItemTag>(PlayerManager::currentMetadata)
            .flatMap<MediaItemTag.AudioTrack> { obj: MediaItemTag -> obj.maybeAudioTrack }
            .map<List<AudioStream>?>(Function<MediaItemTag.AudioTrack, List<AudioStream>?> { it.audioStreams })
            .orElse(null)
        val selectedAudioStream = Optional.ofNullable(playerManager).flatMap(PlayerManager::selectedAudioStream)

        if (availableStreams == null || availableStreams.size < 2 || selectedAudioStream.isEmpty) {
            audioTrackSelector.setVisible(false)
        } else {
            val audioTrackMenu = audioTrackSelector.subMenu
            audioTrackMenu!!.clear()

            for (i in availableStreams.indices) {
                val audioStream = availableStreams[i]
                audioTrackMenu.add(MENU_ID_AUDIO_TRACK, i, Menu.NONE, audioTrackName(this, audioStream))
            }

            val s = selectedAudioStream.get()
            val trackName = audioTrackName(this, s)
            audioTrackSelector.setTitle(getString(R.string.play_queue_audio_track, trackName))

            val shortName = if (s.audioLocale != null) s.audioLocale!!.language else trackName!!
            audioTrackSelector.setTitleCondensed(shortName.substring(0, min(shortName.length.toDouble(), 2.0).toInt()))
            audioTrackSelector.setVisible(true)
        }
    }

    /**
     * Called when an item from the audio track selector is selected.
     *
     * @param itemId index of the selected item
     */
    private fun onAudioTrackClick(itemId: Int) {
        if (playerManager!!.currentMetadata == null) return

        playerManager!!.currentMetadata!!.maybeAudioTrack.ifPresent { audioTrack: MediaItemTag.AudioTrack ->
            val availableStreams = audioTrack.audioStreams
            val selectedStreamIndex = audioTrack.selectedAudioStreamIndex
            if (selectedStreamIndex == itemId || availableStreams.size <= itemId) return@ifPresent

            val newAudioTrack = availableStreams[itemId].audioTrackId
            playerManager!!.setAudioTrack(newAudioTrack)
        }
    }

    companion object {
        private val TAG: String = PlayQueueActivity::class.java.simpleName

        private const val SMOOTH_SCROLL_MAXIMUM_DISTANCE = 80

        private const val MENU_ID_AUDIO_TRACK = 71
    }
}
