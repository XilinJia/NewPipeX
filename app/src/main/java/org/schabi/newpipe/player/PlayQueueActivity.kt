package org.schabi.newpipe.player

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import org.schabi.newpipe.QueueItemMenuUtil.openPopupMenu
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ActivityPlayerQueueControlBinding
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.fragments.OnScrollBelowItemsListener
import org.schabi.newpipe.local.dialog.PlaylistDialog.Companion.showForPlayQueue
import org.schabi.newpipe.player.PlayerService
import org.schabi.newpipe.player.PlayerService.LocalBinder
import org.schabi.newpipe.player.event.PlayerEventListener
import org.schabi.newpipe.player.helper.PlaybackParameterDialog
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.player.mediaitem.MediaItemTag
import org.schabi.newpipe.player.playqueue.*
import org.schabi.newpipe.util.Localization.assureCorrectAppLanguage
import org.schabi.newpipe.util.Localization.audioTrackName
import org.schabi.newpipe.util.Localization.getDurationString
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
    private var player: Player? = null

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
        if (player != null) {
            onPlaybackParameterChanged(player!!.playbackParameters)
        }
        return true
    }

    // Allow to setup visibility of menuItems
    override fun onPrepareOptionsMenu(m: Menu): Boolean {
        if (player != null) {
            menu!!.findItem(R.id.action_switch_popup)
                .setVisible(!player!!.popupPlayerSelected())
            menu!!.findItem(R.id.action_switch_background)
                .setVisible(!player!!.audioPlayerSelected())
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
                showForPlayQueue(player!!, supportFragmentManager)
                return true
            }
            R.id.action_playback_speed -> {
                openPlaybackParameterDialog()
                return true
            }
            R.id.action_mute -> {
                player?.toggleMute()
                return true
            }
            R.id.action_system_audio -> {
                startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
                return true
            }
            R.id.action_switch_main -> {
                player?.setRecovery()
                playOnMainPlayer(this, player!!.playQueue!!, true)
                return true
            }
            R.id.action_switch_popup -> {
                if (player != null && isPopupEnabledElseAsk(this)) {
                    player?.setRecovery()
                    playOnPopupPlayer(this, player!!.playQueue, true)
                }
                return true
            }
            R.id.action_switch_background -> {
                if (player != null) {
                    player?.setRecovery()
                    playOnBackgroundPlayer(this, player!!.playQueue, true)
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
            player?.removeActivityListener(this)

            onQueueUpdate(null)
            itemTouchHelper?.attachToRecyclerView(null)

            itemTouchHelper = null
            player = null
        }
    }

    private fun getServiceConnection(): ServiceConnection {
        return object : ServiceConnection {
            override fun onServiceDisconnected(name: ComponentName) {
                Log.d(TAG, "Player service is disconnected")
            }

            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                Log.d(TAG, "Player service is connected")

                if (service is LocalBinder) {
                    player = service.getPlayer()
                }

                if (player == null || player!!.playQueue == null || player!!.exoPlayerIsNull()) {
                    unbind()
                } else {
                    onQueueUpdate(player!!.playQueue)
                    buildComponents()
                    player?.setActivityListener(this@PlayQueueActivity)
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
                if (player != null && player!!.playQueue != null && !player!!.playQueue!!.isComplete) {
                    player!!.playQueue!!.fetch()
                } else {
                    queueControlBinding!!.playQueue.clearOnScrollListeners()
                }
            }
        }

    private val itemTouchCallback: ItemTouchHelper.SimpleCallback
        get() = object : PlayQueueItemTouchCallback() {
            override fun onMove(sourceIndex: Int, targetIndex: Int) {
                player?.playQueue?.move(sourceIndex, targetIndex)
            }

            override fun onSwiped(index: Int) {
                if (index != -1) {
                    player!!.playQueue!!.remove(index)
                }
            }
        }

    private val onSelectedListener: PlayQueueItemBuilder.OnSelectedListener
        get() = object : PlayQueueItemBuilder.OnSelectedListener {
            override fun selected(item: PlayQueueItem, view: View) {
                player?.selectQueueItem(item)
            }

            override fun held(item: PlayQueueItem, view: View) {
                if (player != null && player!!.playQueue!!.indexOf(item) != -1) {
                    openPopupMenu(player!!.playQueue!!, item, view, false,
                        supportFragmentManager, this@PlayQueueActivity)
                }
            }

            override fun onStartDrag(viewHolder: PlayQueueItemHolder) {
                itemTouchHelper?.startDrag(viewHolder)
            }
        }

    private fun scrollToSelected() {
        if (player == null) return

        val currentPlayingIndex = player!!.playQueue!!.index
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
        if (player == null) return

        when (view.id) {
            queueControlBinding!!.controlRepeat.id -> {
                player!!.cycleNextRepeatMode()
            }
            queueControlBinding!!.controlBackward.id -> {
                player!!.playPrevious()
            }
            queueControlBinding!!.controlFastRewind.id -> {
                player!!.fastRewind()
            }
            queueControlBinding!!.controlPlayPause.id -> {
                player!!.playPause()
            }
            queueControlBinding!!.controlFastForward.id -> {
                player!!.fastForward()
            }
            queueControlBinding!!.controlForward.id -> {
                player!!.playNext()
            }
            queueControlBinding!!.controlShuffle.id -> {
                player!!.toggleShuffleModeEnabled()
            }
            queueControlBinding!!.metadata.id -> {
                scrollToSelected()
            }
            queueControlBinding!!.liveSync.id -> {
                player!!.seekToDefault()
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////
    // Playback Parameters
    ////////////////////////////////////////////////////////////////////////////
    private fun openPlaybackParameterDialog() {
        if (player == null) return

        PlaybackParameterDialog.newInstance(player!!.playbackSpeed.toDouble(), player!!.playbackPitch.toDouble(),
            player!!.playbackSkipSilence, this).show(supportFragmentManager, TAG)
    }

    override fun onPlaybackParameterChanged(playbackTempo: Float, playbackPitch: Float,
                                            playbackSkipSilence: Boolean
    ) {
        if (player != null) {
            player!!.setPlaybackParameters(playbackTempo, playbackPitch, playbackSkipSilence)
            onPlaybackParameterChanged(player!!.playbackParameters)
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
        if (player != null) {
            player!!.seekTo(seekBar.progress.toLong())
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

        if (player != null) {
            queueControlBinding!!.liveSync.isClickable = !player!!.isLiveEdge
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
            Player.STATE_PAUSED -> {
                playPauseButton.setImageResource(R.drawable.ic_play_arrow)
                playPauseButton.contentDescription = getString(R.string.play)
            }
            Player.STATE_PLAYING -> {
                playPauseButton.setImageResource(R.drawable.ic_pause)
                playPauseButton.contentDescription = getString(R.string.pause)
            }
            Player.STATE_COMPLETED -> {
                playPauseButton.setImageResource(R.drawable.ic_replay)
                playPauseButton.contentDescription = getString(R.string.replay)
            }
            else -> {}
        }
        when (state) {
            Player.STATE_PAUSED, Player.STATE_PLAYING, Player.STATE_COMPLETED -> {
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
        if (parameters != null && menu != null && player != null) {
            val item = menu!!.findItem(R.id.action_playback_speed)
            item.setTitle(PlayerHelper.formatSpeed(parameters.speed.toDouble()))
        }
    }

    private fun onMaybeMuteChanged() {
        if (menu != null && player != null) {
            val item = menu!!.findItem(R.id.action_mute)

            //Change the mute-button item in ActionBar
            //1) Text change:
            item.setTitle(if (player!!.isMuted) R.string.unmute else R.string.mute)

            //2) Icon change accordingly to current App Theme
            // using rootView.getContext() because getApplicationContext() didn't work
            item.setIcon(if (player!!.isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        }
    }

    override fun onAudioTrackUpdate() {
        buildAudioTrackMenu()
    }

    private fun buildAudioTrackMenu() {
        if (menu == null) return

        val audioTrackSelector = menu!!.findItem(R.id.action_audio_track)
        val availableStreams = Optional.ofNullable<Player>(player)
            .map<MediaItemTag>(Player::currentMetadata)
            .flatMap<MediaItemTag.AudioTrack> { obj: MediaItemTag -> obj.maybeAudioTrack }
            .map<List<AudioStream>?>(Function<MediaItemTag.AudioTrack, List<AudioStream>?> { it.audioStreams })
            .orElse(null)
        val selectedAudioStream = Optional.ofNullable(player).flatMap(Player::selectedAudioStream)

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
        if (player!!.currentMetadata == null) return

        player!!.currentMetadata!!.maybeAudioTrack.ifPresent { audioTrack: MediaItemTag.AudioTrack ->
            val availableStreams = audioTrack.audioStreams
            val selectedStreamIndex = audioTrack.selectedAudioStreamIndex
            if (selectedStreamIndex == itemId || availableStreams.size <= itemId) return@ifPresent

            val newAudioTrack = availableStreams[itemId].audioTrackId
            player!!.setAudioTrack(newAudioTrack)
        }
    }

    companion object {
        private val TAG: String = PlayQueueActivity::class.java.simpleName

        private const val SMOOTH_SCROLL_MAXIMUM_DISTANCE = 80

        private const val MENU_ID_AUDIO_TRACK = 71
    }
}
