package org.schabi.newpipe.player

import android.content.*
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.core.math.MathUtils
import androidx.media3.common.*
import androidx.media3.common.C.RoleFlags
import androidx.media3.common.MediaItem.LiveConfiguration
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.Player.DiscontinuityReason
import androidx.media3.common.Player.PositionInfo
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.smoothstreaming.manifest.SsManifest
import androidx.media3.exoplayer.smoothstreaming.manifest.SsManifestParser
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.preference.PreferenceManager
import com.squareup.picasso.Picasso.LoadedFrom
import com.squareup.picasso.Target
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.disposables.SerialDisposable
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.databinding.PlayerBinding
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil.Companion.createNotification
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.CreationException
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.*
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.player.PlayerManager.PlaybackResolver.Companion.buildMediaSource
import org.schabi.newpipe.player.PlayerManager.PlaybackResolver.Companion.cacheKeyOf
import org.schabi.newpipe.player.PlayerManager.PlaybackResolver.Companion.maybeBuildLiveMediaSource
import org.schabi.newpipe.ui.detail.VideoDetailFragment
import org.schabi.newpipe.ui.local.history.HistoryRecordManager
import org.schabi.newpipe.player.PlayerType.Companion.retrieveFromIntent
import org.schabi.newpipe.player.datasource.NonUriHlsDataSourceFactory
import org.schabi.newpipe.player.event.PlayerEventListener
import org.schabi.newpipe.player.event.PlayerServiceEventListener
import org.schabi.newpipe.player.helper.*
import org.schabi.newpipe.player.mediaitem.MediaItemTag
import org.schabi.newpipe.player.mediaitem.StreamInfoTag
import org.schabi.newpipe.player.mediasession.MediaSessionPlayerUi
import org.schabi.newpipe.player.notification.NotificationConstants
import org.schabi.newpipe.player.notification.NotificationPlayerUi
import org.schabi.newpipe.player.playback.MediaSourceManager
import org.schabi.newpipe.player.playback.PlaybackListener
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.PlayQueueItem
import org.schabi.newpipe.player.ui.*
import org.schabi.newpipe.util.DependentPreferenceHelper.getResumePlaybackEnabled
import org.schabi.newpipe.util.ListHelper.getAudioFormatIndex
import org.schabi.newpipe.util.ListHelper.getDefaultResolutionIndex
import org.schabi.newpipe.util.ListHelper.getFilteredAudioStreams
import org.schabi.newpipe.util.ListHelper.getPlayableStreams
import org.schabi.newpipe.util.ListHelper.getPopupDefaultResolutionIndex
import org.schabi.newpipe.util.ListHelper.getPopupResolutionIndex
import org.schabi.newpipe.util.ListHelper.getResolutionIndex
import org.schabi.newpipe.util.ListHelper.getSortedStreamVideosList
import org.schabi.newpipe.util.ListHelper.getUrlAndNonTorrentStreams
import org.schabi.newpipe.util.Localization.assureCorrectAppLanguage
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.util.NavigationHelper.sendPlayerStartedEvent
import org.schabi.newpipe.util.SerializedCache
import org.schabi.newpipe.util.StreamTypeUtil.isAudio
import org.schabi.newpipe.util.StreamTypeUtil.isLiveStream
import org.schabi.newpipe.util.StreamTypeUtil.isVideo
import org.schabi.newpipe.util.image.PicassoHelper.cancelTag
import org.schabi.newpipe.util.image.PicassoHelper.loadScaledDownThumbnail
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.stream.IntStream
import kotlin.math.max

//TODO try to remove and replace everything with context
@UnstableApi
class PlayerManager(@JvmField val service: PlayerService) : PlaybackListener, Player.Listener {
    // play queue might be null e.g. while player is starting
    var playQueue: PlayQueue? = null
        private set

    private var playQueueManager: MediaSourceManager? = null

    var currentItem: PlayQueueItem? = null
        private set
    var currentMetadata: MediaItemTag? = null
        private set
    var thumbnail: Bitmap? = null
        private set

    var exoPlayer: ExoPlayer? = null
        private set
    var audioReactor: AudioReactor? = null
        private set

    @JvmField
    val trackSelector: DefaultTrackSelector
    private val loadController: LoadController
    private val renderFactory: DefaultRenderersFactory

    private val videoResolver: VideoPlaybackResolver
    private val audioResolver: AudioPlaybackResolver

    var playerType: PlayerType = PlayerType.MAIN
        private set
    var currentState: Int = STATE_PREFLIGHT
        private set

    // audio only mode does not mean that player type is background, but that the player was
    // minimized to background but will resume automatically to the original player type
    var isAudioOnly: Boolean = false
        private set
    private var isPrepared = false

    // keep the unusual member name
    val UIs: PlayerUiList

    private lateinit var broadcastReceiver: BroadcastReceiver
    private lateinit var intentFilter: IntentFilter

    private var fragmentListener: PlayerServiceEventListener? = null
    private var activityListener: PlayerEventListener? = null

    private val progressUpdateDisposable = SerialDisposable()
    private val databaseUpdateDisposable = CompositeDisposable()

    // This is the only listener we need for thumbnail loading, since there is always at most only
    // one thumbnail being loaded at a time. This field is also here to maintain a strong reference,
    // which would otherwise be garbage collected since Picasso holds weak references to targets.
    private val currentThumbnailTarget: Target

    @JvmField
    val context: Context = service

    @JvmField
    val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val recordManager = HistoryRecordManager(context)

    var playbackSpeed: Float
        //endregion
        get() = playbackParameters.speed
        set(speed) {
            setPlaybackParameters(speed, playbackPitch, playbackSkipSilence)
        }

    val playbackPitch: Float
        get() = playbackParameters.pitch

    val playbackSkipSilence: Boolean
        get() = !exoPlayerIsNull() && exoPlayer!!.skipSilenceEnabled

    val playbackParameters: PlaybackParameters
        get() {
            if (exoPlayerIsNull()) return PlaybackParameters.DEFAULT
            return exoPlayer!!.playbackParameters
        }

    private val qualityResolver: VideoPlaybackResolver.QualityResolver
        get() = object : VideoPlaybackResolver.QualityResolver {
            override fun getDefaultResolutionIndex(sortedVideos: List<VideoStream>?): Int {
                return when {
                    sortedVideos == null -> 0
                    videoPlayerSelected() -> getDefaultResolutionIndex(context, sortedVideos)
                    else -> getPopupDefaultResolutionIndex(context, sortedVideos)
                }
            }

            override fun getOverrideResolutionIndex(sortedVideos: List<VideoStream>?, playbackQuality: String?): Int {
                return when {
                    sortedVideos == null -> 0
                    videoPlayerSelected() -> getResolutionIndex(context, sortedVideos, playbackQuality)
                    else -> getPopupResolutionIndex(context, sortedVideos, playbackQuality)
                }
            }
        }

    val selectedVideoStream: Optional<VideoStream>
        get() = Optional.ofNullable(currentMetadata)
            .flatMap { obj: MediaItemTag -> obj.maybeQuality }
            .filter { quality: MediaItemTag.Quality ->
                val selectedStreamIndex = quality.selectedVideoStreamIndex
                (selectedStreamIndex >= 0 && selectedStreamIndex < quality.sortedVideoStreams.size)
            }
            .map { quality: MediaItemTag.Quality -> quality.sortedVideoStreams[quality.selectedVideoStreamIndex] }

    val selectedAudioStream: Optional<AudioStream?>?
        get() = Optional.ofNullable<MediaItemTag>(currentMetadata)
            .flatMap { obj: MediaItemTag? -> obj?.maybeAudioTrack }
            .map { it.selectedAudioStream }

    val captionRendererIndex: Int
        //endregion
        get() {
            if (exoPlayerIsNull()) return RENDERER_UNAVAILABLE
            for (t in 0 until exoPlayer!!.rendererCount) {
                if (exoPlayer!!.getRendererType(t) == C.TRACK_TYPE_TEXT) return t
            }
            return RENDERER_UNAVAILABLE
        }

    val currentStreamInfo: Optional<StreamInfo>
        //endregion
        get() = Optional.ofNullable(currentMetadata).flatMap { obj: MediaItemTag -> obj.maybeStreamInfo }

    val isStopped: Boolean
        get() = exoPlayerIsNull() || exoPlayer!!.playbackState == ExoPlayer.STATE_IDLE

    val isPlaying: Boolean
        get() = !exoPlayerIsNull() && exoPlayer!!.isPlaying

    val playWhenReady: Boolean
        get() = !exoPlayerIsNull() && exoPlayer!!.playWhenReady

    val isLoading: Boolean
        get() = !exoPlayerIsNull() && exoPlayer!!.isLoading

    private val isLive: Boolean
        get() {
            try {
                return !exoPlayerIsNull() && exoPlayer!!.isCurrentMediaItemDynamic
            } catch (e: IndexOutOfBoundsException) {
                // Why would this even happen =(... but lets log it anyway, better safe than sorry
                Logd(TAG, "player.isCurrentWindowDynamic() failed: $e")
                return false
            }
        }

    private val videoRendererIndex: Int
        /**
         * Get the video renderer index of the current playing stream.
         * This method returns the video renderer index of the current
         * [MappingTrackSelector.MappedTrackInfo] or [.RENDERER_UNAVAILABLE] if the current
         * [MappingTrackSelector.MappedTrackInfo] is null or if there is no video renderer index.
         *
         * @return the video renderer index or [.RENDERER_UNAVAILABLE] if it cannot be get
         */
        get() {
            val mappedTrackInfo = trackSelector.currentMappedTrackInfo ?: return RENDERER_UNAVAILABLE

            // Check every renderer
            return IntStream.range(0,
                mappedTrackInfo.rendererCount) // Check the renderer is a video renderer and has at least one track
                // Return the first index found (there is at most one renderer per renderer type)
                .filter { i: Int -> (!mappedTrackInfo.getTrackGroups(i).isEmpty && exoPlayer!!.getRendererType(i) == C.TRACK_TYPE_VIDEO) }
                .findFirst() // No video renderer index with at least one track found: return unavailable index
                .orElse(RENDERER_UNAVAILABLE)
        } //endregion

    val isProgressLoopRunning: Boolean
        get() = progressUpdateDisposable.get() != null

    var repeatMode: @Player.RepeatMode Int
        //endregion
        get() = if (exoPlayerIsNull()) Player.REPEAT_MODE_OFF else exoPlayer!!.repeatMode
        set(repeatMode) {
            if (!exoPlayerIsNull()) exoPlayer!!.repeatMode = repeatMode
        }

    val isMuted: Boolean
        get() = !exoPlayerIsNull() && exoPlayer!!.volume == 0f

    val isLiveEdge: Boolean
        /**
         * Checks if the current playback is a livestream AND is playing at or beyond the live edge.
         *
         * @return whether the livestream is playing at or beyond the edge
         */
        get() {
            if (exoPlayerIsNull() || !isLive) return false

            val currentTimeline = exoPlayer!!.currentTimeline
            val currentWindowIndex = exoPlayer!!.currentMediaItemIndex
            if (currentTimeline.isEmpty || currentWindowIndex < 0 || currentWindowIndex >= currentTimeline.windowCount) return false

            val timelineWindow = Timeline.Window()
            currentTimeline.getWindow(currentWindowIndex, timelineWindow)
            return timelineWindow.defaultPositionMs <= exoPlayer!!.currentPosition
        }

    val videoUrl: String
        get() = currentMetadata?.streamUrl ?: context.getString(R.string.unknown_content)

    val videoUrlAtCurrentTime: String
        get() {
            val timeSeconds = exoPlayer!!.currentPosition / 1000
            var videoUrl = videoUrl
            // Timestamp doesn't make sense in a live stream so drop it
            if (!isLive && timeSeconds >= 0 && currentMetadata?.serviceId == ServiceList.YouTube.serviceId) videoUrl += ("&t=$timeSeconds")
            return videoUrl
        }

    val videoTitle: String
        get() = currentMetadata?.title ?: context.getString(R.string.unknown_content)

    val uploaderName: String
        get() = currentMetadata?.uploaderName ?: context.getString(R.string.unknown_content)

    init {
        setupBroadcastReceiver()

        trackSelector = DefaultTrackSelector(context, PlayerHelper.qualitySelector)
        val dataSource = PlayerDataSource(context, DefaultBandwidthMeter.Builder(context).build())
        loadController = LoadController()

        renderFactory =
            if (prefs.getBoolean(context.getString(R.string.always_use_exoplayer_set_output_surface_workaround_key), false)) CustomRenderersFactory(context)
            else DefaultRenderersFactory(context)

        renderFactory.setEnableDecoderFallback(prefs.getBoolean(context.getString(R.string.use_exoplayer_decoder_fallback_key), false))

        videoResolver = VideoPlaybackResolver(context, dataSource, qualityResolver)
        audioResolver = AudioPlaybackResolver(context, dataSource)

        currentThumbnailTarget = getCurrentThumbnailTarget()

        // The UIs added here should always be present. They will be initialized when the player
        // reaches the initialization step. Make sure the media session ui is before the
        // notification ui in the UIs list, since the notification depends on the media session in
        // PlayerUi#initPlayer(), and UIs.call() guarantees UI order is preserved.
        UIs = PlayerUiList(MediaSessionPlayerUi(this), NotificationPlayerUi(this))
    }

    fun handleIntent(intent: Intent) {
        // fail fast if no play queue was provided
        val queueCache = intent.getStringExtra(PLAY_QUEUE_KEY) ?: return
        val newQueue: PlayQueue = SerializedCache.instance.take(queueCache, PlayQueue::class.java) ?: return

        val oldPlayerType = playerType
        playerType = retrieveFromIntent(intent)
        initUIsForCurrentPlayerType()
        // We need to setup audioOnly before super(), see "sourceOf"
        isAudioOnly = audioPlayerSelected()

        if (intent.hasExtra(PLAYBACK_QUALITY)) videoResolver.playbackQuality = intent.getStringExtra(PLAYBACK_QUALITY)

        // Resolve enqueue intents// If playerType changes from one to another we should reload the player
        // (to disable/enable video stream or to set quality)
// Good to go...
        // In a case of equal PlayQueues we can re-init old one but only when it is disposed
// Completed but not found in history
        // In case any error we can start playback without history
        // resume playback only if the stream was not played to the end
        // Do not place initPlayback() in doFinally() because
        // it restarts playback after destroy()
        //.doFinally()
// Do not re-init the same PlayQueue. Save time
        // Player can have state = IDLE when playback is stopped or failed
        // and we should retry in this case
        // Player can have state = IDLE when playback is stopped or failed
        // and we should retry in this case
        when {
            intent.getBooleanExtra(ENQUEUE, false) && playQueue != null -> {
                playQueue!!.append(newQueue.streams)
                return
                // Resolve enqueue next intents
            }
            intent.getBooleanExtra(ENQUEUE_NEXT, false) && playQueue != null -> {
                val currentIndex = playQueue!!.index
                playQueue!!.append(newQueue.streams)
                playQueue!!.move(playQueue!!.size() - 1, currentIndex + 1)
                return
            }

            /*
             * TODO As seen in #7427 this does not work:
             * There are 3 situations when playback shouldn't be started from scratch (zero timestamp):
             * 1. User pressed on a timestamp link and the same video should be rewound to the timestamp
             * 2. User changed a player from, for example. main to popup, or from audio to main, etc
             * 3. User chose to resume a video based on a saved timestamp from history of played videos
             * In those cases time will be saved because re-init of the play queue is a not an instant
             *  task and requires network calls
             * */
            // seek to timestamp if stream is already playing
            else -> {
                val savedParameters = PlayerHelper.retrievePlaybackParametersFromPrefs(this)
                val playbackSpeed = savedParameters.speed
                val playbackPitch = savedParameters.pitch
                val playbackSkipSilence = prefs.getBoolean(context.getString(R.string.playback_skip_silence_key), playbackSkipSilence)

                val samePlayQueue = playQueue != null && playQueue!!.equalStreamsAndIndex(newQueue)
                val repeatMode = intent.getIntExtra(REPEAT_MODE, repeatMode)
                val playWhenReady = intent.getBooleanExtra(PLAY_WHEN_READY, true)
                val isMuted = intent.getBooleanExtra(IS_MUTED, isMuted)

                /*
             * TODO As seen in #7427 this does not work:
             * There are 3 situations when playback shouldn't be started from scratch (zero timestamp):
             * 1. User pressed on a timestamp link and the same video should be rewound to the timestamp
             * 2. User changed a player from, for example. main to popup, or from audio to main, etc
             * 3. User chose to resume a video based on a saved timestamp from history of played videos
             * In those cases time will be saved because re-init of the play queue is a not an instant
             *  task and requires network calls
             * */
                // seek to timestamp if stream is already playing
                when {
                    !exoPlayerIsNull() && newQueue.size() == 1 && newQueue.item != null && playQueue != null && playQueue!!.size() == 1
                            && playQueue!!.item != null && newQueue.item!!.url == playQueue!!.item!!.url
                            && newQueue.item!!.recoveryPosition != PlayQueueItem.RECOVERY_UNSET -> {
                        // Player can have state = IDLE when playback is stopped or failed
                        // and we should retry in this case
                        if (exoPlayer!!.playbackState == Player.STATE_IDLE) exoPlayer!!.prepare()
                        exoPlayer!!.seekTo(playQueue!!.index, newQueue.item!!.recoveryPosition)
                        exoPlayer!!.playWhenReady = playWhenReady
                    }
                    (!exoPlayerIsNull() && samePlayQueue) && playQueue != null && !playQueue!!.isDisposed -> {
                        // Do not re-init the same PlayQueue. Save time
                        // Player can have state = IDLE when playback is stopped or failed
                        // and we should retry in this case
                        if (exoPlayer!!.playbackState == Player.STATE_IDLE) exoPlayer!!.prepare()
                        exoPlayer!!.playWhenReady = playWhenReady
                    }
                    (intent.getBooleanExtra(RESUME_PLAYBACK, false) && getResumePlaybackEnabled(context) && !samePlayQueue && !newQueue.isEmpty)
                            && newQueue.item != null && newQueue.item!!.recoveryPosition == PlayQueueItem.RECOVERY_UNSET -> {
                        databaseUpdateDisposable.add(recordManager.loadStreamState(newQueue.item!!)
                            .observeOn(AndroidSchedulers.mainThread()) // Do not place initPlayback() in doFinally() because
                            // it restarts playback after destroy()
                            //.doFinally()
                            .subscribe(
                                { state: StreamStateEntity ->
                                    // resume playback only if the stream was not played to the end
                                    if (!state.isFinished(newQueue.item!!.duration)) newQueue.setRecovery(newQueue.index, state.progressMillis)
                                    initPlayback(newQueue, repeatMode, playbackSpeed, playbackPitch, playbackSkipSilence, playWhenReady, isMuted)
                                },
                                { error: Throwable? ->
                                    Log.w(TAG, "Failed to start playback", error)
                                    // In case any error we can start playback without history
                                    initPlayback(newQueue, repeatMode, playbackSpeed, playbackPitch, playbackSkipSilence, playWhenReady, isMuted)
                                },
                                {
                                    // Completed but not found in history
                                    initPlayback(newQueue, repeatMode, playbackSpeed, playbackPitch, playbackSkipSilence, playWhenReady, isMuted)
                                }
                            ))
                    }
                    // Good to go...
                    // In a case of equal PlayQueues we can re-init old one but only when it is disposed
                    else ->
                        initPlayback((if (samePlayQueue) playQueue else newQueue)!!, repeatMode, playbackSpeed, playbackPitch, playbackSkipSilence, playWhenReady, isMuted)
                }

                if (oldPlayerType != playerType && playQueue != null) {
                    // If playerType changes from one to another we should reload the player
                    // (to disable/enable video stream or to set quality)
                    setRecovery()
                    reloadPlayQueueManager()
                }

                UIs.call { obj: PlayerUi -> obj.setupAfterIntent() }
                sendPlayerStartedEvent(context)
            }
        }
    }

    private fun initUIsForCurrentPlayerType() {
        // correct UI already in place
        if ((UIs.get(MainPlayerUi::class.java).isPresent && playerType == PlayerType.MAIN)
                || (UIs.get(PopupPlayerUi::class.java).isPresent && playerType == PlayerType.POPUP)) return

        // try to reuse binding if possible
        val binding = UIs.get(VideoPlayerUi::class.java).map { obj: VideoPlayerUi -> obj.binding }.orElseGet {
            if (playerType == PlayerType.AUDIO) return@orElseGet null
            else return@orElseGet PlayerBinding.inflate(LayoutInflater.from(context))
        }

        when (playerType) {
            PlayerType.MAIN -> {
                UIs.destroyAll(PopupPlayerUi::class.java)
                UIs.addAndPrepare(MainPlayerUi(this, binding!!))
            }
            PlayerType.POPUP -> {
                UIs.destroyAll(MainPlayerUi::class.java)
                UIs.addAndPrepare(PopupPlayerUi(this, binding!!))
            }
            PlayerType.AUDIO -> UIs.destroyAll(VideoPlayerUi::class.java)
        }
    }

    private fun initPlayback(queue: PlayQueue, repeatMode: @Player.RepeatMode Int, playbackSpeed: Float,
                             playbackPitch: Float, playbackSkipSilence: Boolean, playOnReady: Boolean, isMuted: Boolean) {
        destroyPlayer()
        initPlayer(playOnReady)
        this.repeatMode = repeatMode
        setPlaybackParameters(playbackSpeed, playbackPitch, playbackSkipSilence)

        playQueue = queue
        playQueue!!.init()
        reloadPlayQueueManager()

        UIs.call { obj: PlayerUi -> obj.initPlayback() }

        exoPlayer!!.volume = (if (isMuted) 0 else 1).toFloat()
        notifyQueueUpdateToListeners()
    }

    private fun initPlayer(playOnReady: Boolean) {
        Logd(TAG, "initPlayer() called with: playOnReady = [$playOnReady]")

        exoPlayer = ExoPlayer.Builder(context, renderFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadController)
            .setUsePlatformDiagnostics(false)
            .build()
        exoPlayer!!.addListener(this)
        exoPlayer!!.playWhenReady = playOnReady
        exoPlayer!!.setSeekParameters(PlayerHelper.getSeekParameters(context))
        exoPlayer!!.setWakeMode(C.WAKE_MODE_NETWORK)
        exoPlayer!!.setHandleAudioBecomingNoisy(true)

        audioReactor = AudioReactor(context, exoPlayer!!)

        registerBroadcastReceiver()

        // Setup UIs
        UIs.call { obj: PlayerUi -> obj.initPlayer() }

        // Disable media tunneling if requested by the user from ExoPlayer settings
        if (!PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(R.string.disable_media_tunneling_key), false))
            trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(true))
    }

    private fun destroyPlayer() {
        Logd(TAG, "destroyPlayer() called")

        UIs.call { obj: PlayerUi -> obj.destroyPlayer() }

        if (!exoPlayerIsNull()) {
            exoPlayer?.removeListener(this)
//            exoPlayer?.stop()     // TODO: test
//            exoPlayer?.release()  // to be release in mediasession
        }
        if (isProgressLoopRunning) stopProgressLoop()

        playQueue?.dispose()
        audioReactor?.dispose()
        playQueueManager?.dispose()
    }

    fun destroy() {
        Logd(TAG, "destroy() called")

        saveStreamProgressState()
        setRecovery()
        stopActivityBinding()

        destroyPlayer()
        unregisterBroadcastReceiver()

        databaseUpdateDisposable.clear()
        progressUpdateDisposable.set(null)
        cancelLoadingCurrentThumbnail()

        UIs.destroyAll(Any::class.java) // destroy every UI: obviously every UI extends Object
    }

    fun setRecovery() {
        if (playQueue == null || exoPlayerIsNull()) return

        val queuePos = playQueue!!.index
        val windowPos = exoPlayer!!.currentPosition
        val duration = exoPlayer!!.duration

        // No checks due to https://github.com/TeamNewPipe/NewPipe/pull/7195#issuecomment-962624380
        setRecovery(queuePos, MathUtils.clamp(windowPos, 0, duration))
    }

    private fun setRecovery(queuePos: Int, windowPos: Long) {
        if (playQueue == null || playQueue!!.size() <= queuePos) return
        Logd(TAG, "Setting recovery, queue: $queuePos, pos: $windowPos")

        playQueue!!.setRecovery(queuePos, windowPos)
    }

    fun reloadPlayQueueManager() {
        playQueueManager?.dispose()

        if (playQueue != null) playQueueManager = MediaSourceManager(this, playQueue!!)
    }

    // own playback listener
    override fun onPlaybackShutdown() {
        Logd(TAG, "onPlaybackShutdown() called")
        // destroys the service, which in turn will destroy the player
        service.stopService()
    }

    fun smoothStopForImmediateReusing() {
        // Pausing would make transition from one stream to a new stream not smooth, so only stop
        exoPlayer!!.stop()
        setRecovery()
        UIs.call { obj: PlayerUi -> obj.smoothStopForImmediateReusing() }
    }

    /**
     * This function prepares the broadcast receiver and is called only in the constructor.
     * Therefore if you want any PlayerUi to receive a broadcast action, you should add it here,
     * even if that player ui might never be added to the player. In that case the received
     * broadcast would not do anything.
     */
    private fun setupBroadcastReceiver() {
        Logd(TAG, "setupBroadcastReceiver() called")

        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                onBroadcastReceived(intent)
            }
        }
        intentFilter = IntentFilter()

        intentFilter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)

        intentFilter.addAction(NotificationConstants.ACTION_CLOSE)
        intentFilter.addAction(NotificationConstants.ACTION_PLAY_PAUSE)
        intentFilter.addAction(NotificationConstants.ACTION_PLAY_PREVIOUS)
        intentFilter.addAction(NotificationConstants.ACTION_PLAY_NEXT)
        intentFilter.addAction(NotificationConstants.ACTION_FAST_REWIND)
        intentFilter.addAction(NotificationConstants.ACTION_FAST_FORWARD)
        intentFilter.addAction(NotificationConstants.ACTION_REPEAT)
        intentFilter.addAction(NotificationConstants.ACTION_SHUFFLE)
        intentFilter.addAction(NotificationConstants.ACTION_RECREATE_NOTIFICATION)

        intentFilter.addAction(VideoDetailFragment.ACTION_VIDEO_FRAGMENT_RESUMED)
        intentFilter.addAction(VideoDetailFragment.ACTION_VIDEO_FRAGMENT_STOPPED)

        intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        intentFilter.addAction(Intent.ACTION_SCREEN_ON)
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF)
        intentFilter.addAction(Intent.ACTION_HEADSET_PLUG)
    }

    private fun onBroadcastReceived(intent: Intent?) {
//        if (intent?.action == null) return
        Logd(TAG, "onBroadcastReceived() called with: intent = [$intent]")

        when (intent?.action) {
            AudioManager.ACTION_AUDIO_BECOMING_NOISY -> pause()
            NotificationConstants.ACTION_CLOSE -> service.stopService()
            NotificationConstants.ACTION_PLAY_PAUSE -> playPause()
            NotificationConstants.ACTION_PLAY_PREVIOUS -> playPrevious()
            NotificationConstants.ACTION_PLAY_NEXT -> playNext()
            NotificationConstants.ACTION_FAST_REWIND -> fastRewind()
            NotificationConstants.ACTION_FAST_FORWARD -> fastForward()
            NotificationConstants.ACTION_REPEAT -> cycleNextRepeatMode()
            NotificationConstants.ACTION_SHUFFLE -> toggleShuffleModeEnabled()
            Intent.ACTION_CONFIGURATION_CHANGED -> {
                assureCorrectAppLanguage(service)
                Logd(TAG, "ACTION_CONFIGURATION_CHANGED received")
            }
        }
        UIs.call { playerUi: PlayerUi -> playerUi.onBroadcastReceived(intent) }
    }

    private fun registerBroadcastReceiver() {
        // Try to unregister current first
        unregisterBroadcastReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            context.registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(broadcastReceiver, intentFilter)
    }

    private fun unregisterBroadcastReceiver() {
        try {
            context.unregisterReceiver(broadcastReceiver)
        } catch (unregisteredException: IllegalArgumentException) {
            Log.w(TAG, "Broadcast receiver already unregistered: " + unregisteredException.message)
        }
    }

    private fun getCurrentThumbnailTarget(): Target {
        // a Picasso target is just a listener for thumbnail loading events
        return object : Target {
            override fun onBitmapLoaded(bitmap: Bitmap, from: LoadedFrom) {
                Logd(TAG, "Thumbnail - onBitmapLoaded() called with: bitmap = [$bitmap -> ${bitmap.width}x${bitmap.height}], from = [$from]")
                // there is a new thumbnail, so e.g. the end screen thumbnail needs to change, too.
                onThumbnailLoaded(bitmap)
            }
            override fun onBitmapFailed(e: Exception, errorDrawable: Drawable) {
                Log.e(TAG, "Thumbnail - onBitmapFailed() called", e)
                // there is a new thumbnail, so e.g. the end screen thumbnail needs to change, too.
                onThumbnailLoaded(null)
            }
            override fun onPrepareLoad(placeHolderDrawable: Drawable) {
                Logd(TAG, "Thumbnail - onPrepareLoad() called")
            }
        }
    }

    private fun loadCurrentThumbnail(thumbnails: List<Image>) {
        Logd(TAG, "Thumbnail - loadCurrentThumbnail() called with thumbnails = [${thumbnails.size}]")

        // first cancel any previous loading
        cancelLoadingCurrentThumbnail()

        // Unset currentThumbnail, since it is now outdated. This ensures it is not used in media
        // session metadata while the new thumbnail is being loaded by Picasso.
        onThumbnailLoaded(null)
        if (thumbnails.isEmpty()) return

        // scale down the notification thumbnail for performance
        loadScaledDownThumbnail(context, thumbnails)
            .tag(PICASSO_PLAYER_THUMBNAIL_TAG)
            .into(currentThumbnailTarget)
    }

    private fun cancelLoadingCurrentThumbnail() {
        // cancel the Picasso job associated with the player thumbnail, if any
        cancelTag(PICASSO_PLAYER_THUMBNAIL_TAG)
    }

    private fun onThumbnailLoaded(bitmap: Bitmap?) {
        // Avoid useless thumbnail updates, if the thumbnail has not actually changed. Based on the
        // thumbnail loading code, this if would be skipped only when both bitmaps are `null`, since
        // onThumbnailLoaded won't be called twice with the same nonnull bitmap by Picasso's target.
        if (thumbnail != bitmap) {
            thumbnail = bitmap
            UIs.call { playerUi: PlayerUi -> playerUi.onThumbnailLoaded(bitmap) }
        }
    }

    /**
     * Sets the playback parameters of the player, and also saves them to shared preferences.
     * Speed and pitch are rounded up to 2 decimal places before being used or saved.
     *
     * @param speed       the playback speed, will be rounded to up to 2 decimal places
     * @param pitch       the playback pitch, will be rounded to up to 2 decimal places
     * @param skipSilence skip silence during playback
     */
    fun setPlaybackParameters(speed: Float, pitch: Float, skipSilence: Boolean) {
        val roundedSpeed = Math.round(speed * 100.0f) / 100.0f
        val roundedPitch = Math.round(pitch * 100.0f) / 100.0f

        PlayerHelper.savePlaybackParametersToPrefs(this, roundedSpeed, roundedPitch, skipSilence)
        exoPlayer!!.playbackParameters = PlaybackParameters(roundedSpeed, roundedPitch)
        exoPlayer!!.skipSilenceEnabled = skipSilence
    }

    private fun onUpdateProgress(currentProgress: Int, duration: Int, bufferPercent: Int) {
        if (isPrepared) {
            UIs.call { ui: PlayerUi -> ui.onUpdateProgress(currentProgress, duration, bufferPercent) }
            notifyProgressUpdateToListeners(currentProgress, duration, bufferPercent)
        }
    }

    fun startProgressLoop() {
        progressUpdateDisposable.set(getProgressUpdateDisposable())
    }

    private fun stopProgressLoop() {
        progressUpdateDisposable.set(null)
    }

    fun triggerProgressUpdate() {
        if (exoPlayerIsNull()) return

        onUpdateProgress(max(exoPlayer!!.currentPosition.toInt().toDouble(), 0.0).toInt(),
            exoPlayer!!.duration.toInt(), exoPlayer!!.bufferedPercentage)
    }

    private fun getProgressUpdateDisposable(): Disposable {
        return Observable.interval(PROGRESS_LOOP_INTERVAL_MILLIS.toLong(), TimeUnit.MILLISECONDS,
            AndroidSchedulers.mainThread())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ ignored: Long? -> triggerProgressUpdate() },
                { error: Throwable? -> Log.e(TAG, "Progress update failure: ", error) })
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        Logd(TAG, "ExoPlayer - onPlayWhenReadyChanged() called with: playWhenReady = [$playWhenReady], reason = [$reason]")
        val playbackState = if (exoPlayerIsNull()) Player.STATE_IDLE else exoPlayer!!.playbackState
        updatePlaybackState(playWhenReady, playbackState)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        Logd(TAG, "ExoPlayer - onPlaybackStateChanged() called with: playbackState = [$playbackState]")
        updatePlaybackState(playWhenReady, playbackState)
    }

    private fun updatePlaybackState(playWhenReady: Boolean, playbackState: Int) {
        Logd(TAG, "ExoPlayer - updatePlaybackState() called with: playWhenReady = [$playWhenReady], playbackState = [$playbackState]")

        if (currentState == STATE_PAUSED_SEEK) {
            Logd(TAG, "updatePlaybackState() is currently blocked")
            return
        }

        when (playbackState) {
            Player.STATE_IDLE -> isPrepared = false
            Player.STATE_BUFFERING -> if (isPrepared) changeState(STATE_BUFFERING)
            Player.STATE_READY -> {
                if (!isPrepared) {
                    isPrepared = true
                    onPrepared(playWhenReady)
                }
                changeState(if (playWhenReady) STATE_PLAYING else STATE_PAUSED)
            }
            Player.STATE_ENDED -> {
                changeState(STATE_COMPLETED)
                saveStreamProgressStateCompleted()
                isPrepared = false
            }
        }
    }

    // exoplayer listener
    override fun onIsLoadingChanged(isLoading: Boolean) {
        when {
            !isLoading && currentState == STATE_PAUSED && isProgressLoopRunning -> stopProgressLoop()
            isLoading && !isProgressLoopRunning -> startProgressLoop()
        }
    }

    // own playback listener
    override fun onPlaybackBlock() {
        if (exoPlayerIsNull()) return
        Logd(TAG, "Playback - onPlaybackBlock() called")

        currentItem = null
        currentMetadata = null
        exoPlayer!!.stop()
        isPrepared = false

        changeState(STATE_BLOCKED)
    }

    // own playback listener
    override fun onPlaybackUnblock(mediaSource: MediaSource) {
        Logd(TAG, "Playback - onPlaybackUnblock() called")
        if (exoPlayerIsNull()) return

        if (currentState == STATE_BLOCKED) changeState(STATE_BUFFERING)
        exoPlayer!!.setMediaSource(mediaSource, false)
        exoPlayer!!.prepare()
    }

    fun changeState(state: Int) {
        Logd(TAG, "changeState() called with: state = [$state]")
        currentState = state
        when (state) {
            STATE_BLOCKED -> onBlocked()
            STATE_PLAYING -> onPlaying()
            STATE_BUFFERING -> onBuffering()
            STATE_PAUSED -> onPaused()
            STATE_PAUSED_SEEK -> onPausedSeek()
            STATE_COMPLETED -> onCompleted()
        }
        notifyPlaybackUpdateToListeners()
    }

    private fun onPrepared(playWhenReady: Boolean) {
        Logd(TAG, "onPrepared() called with: playWhenReady = [$playWhenReady]")
        UIs.call { obj: PlayerUi -> obj.onPrepared() }

        if (playWhenReady && !isMuted) audioReactor!!.requestAudioFocus()
    }

    private fun onBlocked() {
        Logd(TAG, "onBlocked() called")
        if (!isProgressLoopRunning) startProgressLoop()

        UIs.call { obj: PlayerUi -> obj.onBlocked() }
    }

    private fun onPlaying() {
        Logd(TAG, "onPlaying() called")
        if (!isProgressLoopRunning) startProgressLoop()
        UIs.call { obj: PlayerUi -> obj.onPlaying() }
    }

    private fun onBuffering() {
        Logd(TAG, "onBuffering() called")
        UIs.call { obj: PlayerUi -> obj.onBuffering() }
    }

    private fun onPaused() {
        Logd(TAG, "onPaused() called")
        if (isProgressLoopRunning) stopProgressLoop()
        UIs.call { obj: PlayerUi -> obj.onPaused() }
    }

    private fun onPausedSeek() {
        Logd(TAG, "onPausedSeek() called")
        UIs.call { obj: PlayerUi -> obj.onPausedSeek() }
    }

    private fun onCompleted() {
        Logd(TAG, "onCompleted() called" + (if (playQueue == null) ". playQueue is null" else ""))
        if (playQueue == null) return
        UIs.call { obj: PlayerUi -> obj.onCompleted() }

        if (playQueue!!.index < playQueue!!.size() - 1) playQueue!!.offsetIndex(+1)
        if (isProgressLoopRunning) stopProgressLoop()
    }

    fun cycleNextRepeatMode() {
        repeatMode = PlayerHelper.nextRepeatMode(repeatMode)
    }

    override fun onRepeatModeChanged(repeatMode: @Player.RepeatMode Int) {
        Logd(TAG, "ExoPlayer - onRepeatModeChanged() called with: repeatMode = [$repeatMode]")
        UIs.call { playerUi: PlayerUi -> playerUi.onRepeatModeChanged(repeatMode) }
        notifyPlaybackUpdateToListeners()
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        Logd(TAG, "ExoPlayer - onShuffleModeEnabledChanged() called with: mode = [$shuffleModeEnabled]")

        if (shuffleModeEnabled) playQueue?.shuffle()
        else playQueue?.unshuffle()

        UIs.call { playerUi: PlayerUi -> playerUi.onShuffleModeEnabledChanged(shuffleModeEnabled) }
        notifyPlaybackUpdateToListeners()
    }

    fun toggleShuffleModeEnabled() {
        if (!exoPlayerIsNull()) exoPlayer!!.shuffleModeEnabled = !exoPlayer!!.shuffleModeEnabled
    }

    fun toggleMute() {
        val wasMuted = isMuted
        exoPlayer!!.volume = (if (wasMuted) 1 else 0).toFloat()
        if (wasMuted) audioReactor!!.requestAudioFocus()
        else audioReactor!!.abandonAudioFocus()

        UIs.call { playerUi: PlayerUi -> playerUi.onMuteUnmuteChanged(!wasMuted) }
        notifyPlaybackUpdateToListeners()
    }

    /**
     * Listens for event or state changes on ExoPlayer. When any event happens, we check for
     * changes in the currently-playing metadata and update the encapsulating
     * [PlayerManager]. Downstream listeners are also informed.
     * When the renewed metadata contains any error, it is reported as a notification.
     * This is done because not all source resolution errors are [PlaybackException], which
     * are also captured by [ExoPlayer] and stops the playback.
     * @param player The [androidx.media3.common.Player] whose state changed.
     * @param events The [androidx.media3.common.Player.Events] that has triggered
     * the player state changes.
     */
    override fun onEvents(player: Player, events: Player.Events) {
        super.onEvents(player, events)
        MediaItemTag.from(player.currentMediaItem).ifPresent { tag: MediaItemTag ->
            if (tag === currentMetadata) return@ifPresent  // we still have the same metadata, no need to do anything

            val previousInfo = Optional.ofNullable(currentMetadata).flatMap { obj: MediaItemTag -> obj.maybeStreamInfo }.orElse(null)
            val previousAudioTrack = Optional.ofNullable(currentMetadata).flatMap { obj: MediaItemTag -> obj.maybeAudioTrack }.orElse(null)
            currentMetadata = tag

            if (!currentMetadata?.errors.isNullOrEmpty()) {
                // new errors might have been added even if previousInfo == tag.getMaybeStreamInfo()
                val errorInfo = ErrorInfo(currentMetadata!!.errors!!.filterNotNull(), UserAction.PLAY_STREAM,
                    "Loading failed for [" + currentMetadata!!.title + "]: " + currentMetadata!!.streamUrl!!, currentMetadata!!.serviceId)
                createNotification(context, errorInfo)
            }
            currentMetadata!!.maybeStreamInfo.ifPresent { info: StreamInfo ->
                Logd(TAG, "ExoPlayer - onEvents() update stream info: " + info.name)
                when {
                    // only update with the new stream info if it has actually changed
                    previousInfo == null || previousInfo.url != info.url -> updateMetadataWith(info)
                    previousAudioTrack == null || tag.maybeAudioTrack
                        .map { t: MediaItemTag.AudioTrack -> t.selectedAudioStreamIndex != previousAudioTrack.selectedAudioStreamIndex }
                        .orElse(false) -> {
                        notifyAudioTrackUpdateToListeners()
                    }
                }
            }
        }
    }

    override fun onTracksChanged(tracks: Tracks) {
        Logd(TAG, "ExoPlayer - onTracksChanged(), track group size = ${tracks.groups.size}")
        UIs.call { playerUi: PlayerUi -> playerUi.onTextTracksChanged(tracks) }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        Logd(TAG, "ExoPlayer - playbackParameters(), speed = [${playbackParameters.speed}], pitch = [${playbackParameters.pitch}]")
        UIs.call { playerUi: PlayerUi -> playerUi.onPlaybackParametersChanged(playbackParameters) }
    }

    override fun onPositionDiscontinuity(oldPosition: PositionInfo, newPosition: PositionInfo, discontinuityReason: @DiscontinuityReason Int) {
        Logd(TAG, "ExoPlayer - onPositionDiscontinuity() called with oldPositionIndex = [${oldPosition.mediaItemIndex}], oldPositionMs = [${oldPosition.positionMs}], newPositionIndex = [${newPosition.mediaItemIndex}], newPositionMs = [${newPosition.positionMs}], discontinuityReason = [$discontinuityReason]")

        if (playQueue == null) return

        // Refresh the playback if there is a transition to the next video
        val newIndex = newPosition.mediaItemIndex
        when (discontinuityReason) {
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION, Player.DISCONTINUITY_REASON_REMOVE -> {
                // When player is in single repeat mode and a period transition occurs,
                // we need to register a view count here since no metadata has changed
                if (repeatMode == Player.REPEAT_MODE_ONE && newIndex == playQueue!!.index) {
                    registerStreamViewed()
                } else {
                    Logd(TAG, "ExoPlayer - onSeekProcessed() called")
                    if (isPrepared) saveStreamProgressState()

                    // Player index may be invalid when playback is blocked
                    if (currentState != STATE_BLOCKED && newIndex != playQueue!!.index) {
                        saveStreamProgressStateCompleted() // current stream has ended
                        playQueue!!.index = newIndex
                    }
                }
            }
            Player.DISCONTINUITY_REASON_SEEK -> {
                Logd(TAG, "ExoPlayer - onSeekProcessed() called")
                if (isPrepared) saveStreamProgressState()
                if (currentState != STATE_BLOCKED && newIndex != playQueue!!.index) {
                    saveStreamProgressStateCompleted()
                    playQueue!!.index = newIndex
                }
            }
            Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT, Player.DISCONTINUITY_REASON_INTERNAL ->
                if (currentState != STATE_BLOCKED && newIndex != playQueue!!.index) {
                    saveStreamProgressStateCompleted()
                    playQueue!!.index = newIndex
                }
            Player.DISCONTINUITY_REASON_SKIP -> {}
            Player.DISCONTINUITY_REASON_SILENCE_SKIP -> {}
        }
    }

    override fun onRenderedFirstFrame() {
        UIs.call { obj: PlayerUi -> obj.onRenderedFirstFrame() }
    }

    override fun onCues(cueGroup: CueGroup) {
        UIs.call { playerUi: PlayerUi -> playerUi.onCues(cueGroup.cues) }
    }

    /**
     * Process exceptions produced by [ExoPlayer][com.google.android.exoplayer2.ExoPlayer].
     *
     * There are multiple types of errors:
     *
     *  * [BEHIND_LIVE_WINDOW][PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW]:
     * If the playback on livestreams are lagged too far behind the current playable
     * window. Then we seek to the latest timestamp and restart the playback.
     * This error is **catchable**.
     *
     *  * From [BAD_IO][PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE] to
     * [UNSUPPORTED_FORMATS][PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED]:
     * If the stream source is validated by the extractor but not recognized by the player,
     * then we can try to recover playback by signalling an error on the [PlayQueue].
     *  * For [PLAYER_TIMEOUT][PlaybackException.ERROR_CODE_TIMEOUT],
     * [MEDIA_SOURCE_RESOLVER_TIMEOUT][PlaybackException.ERROR_CODE_IO_UNSPECIFIED] and
     * [NO_NETWORK][PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED]:
     * We can keep set the recovery record and keep to player at the current state until
     * it is ready to play by restarting the [MediaSourceManager].
     *  * On any ExoPlayer specific issue internal to its device interaction, such as
     * [DECODER_ERROR][PlaybackException.ERROR_CODE_DECODER_INIT_FAILED]:
     * We terminate the playback.
     *  * For any other unspecified issue internal: We set a recovery and try to restart
     * the playback.
     * For any error above that is **not** explicitly **catchable**, the player will
     * create a notification so users are aware.
     *
     *
     * @see androidx.media3.common.Player.Listener.onPlayerError
     */
    // Any error code not explicitly covered here are either unrelated to NewPipe use case
    // (e.g. DRM) or not recoverable (e.g. Decoder error). In both cases, the player should
    // shutdown.
    override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "ExoPlayer - onPlayerError() called with:", error)

        saveStreamProgressState()
        var isCatchableException = false

        when (error.errorCode) {
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                isCatchableException = true
                exoPlayer!!.seekToDefaultPosition()
                exoPlayer!!.prepare()
                // Inform the user that we are reloading the stream by
                // switching to the buffering state
                onBuffering()
            }
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->                 // Source errors, signal on playQueue and move on:
                if (!exoPlayerIsNull()) playQueue?.error()
            PlaybackException.ERROR_CODE_TIMEOUT, PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_UNSPECIFIED -> {
                // Reload playback on unexpected errors:
                setRecovery()
                reloadPlayQueueManager()
            }
            // API, remote and renderer errors belong here:
            else -> onPlaybackShutdown()
        }
        if (!isCatchableException) createErrorNotification(error)

        fragmentListener?.onPlayerError(error, isCatchableException)
    }

    private fun createErrorNotification(error: PlaybackException) {
        val errorInfo = if (currentMetadata == null)
            ErrorInfo(error, UserAction.PLAY_STREAM, "Player error[type=" + error.errorCodeName + "] occurred, currentMetadata is null")
        else
            ErrorInfo(error, UserAction.PLAY_STREAM, "Player error[type=${error.errorCodeName}] occurred while playing ${currentMetadata!!.streamUrl}", currentMetadata!!.serviceId)

        createNotification(context, errorInfo)
    }


    override fun isApproachingPlaybackEdge(timeToEndMillis: Long): Boolean {
        // If live, then not near playback edge
        // If not playing, then not approaching playback edge
        if (exoPlayerIsNull() || isLive || !isPlaying) return false

        val currentPositionMillis = exoPlayer!!.currentPosition
        val currentDurationMillis = exoPlayer!!.duration
        return currentDurationMillis - currentPositionMillis < timeToEndMillis
    }

    // own playback listener
    override fun onPlaybackSynchronize(item: PlayQueueItem, wasBlocked: Boolean) {
        Logd(TAG, "Playback - onPlaybackSynchronize(was blocked: $wasBlocked) called with item=[${item.title}], url=[${item.url}]")

        if (exoPlayerIsNull() || playQueue == null || currentItem === item) return  // nothing to synchronize

        val playQueueIndex = playQueue!!.indexOf(item)
        val playlistIndex = exoPlayer!!.currentMediaItemIndex
        val playlistSize = exoPlayer!!.currentTimeline.windowCount
        val removeThumbnailBeforeSync =
            currentItem == null || currentItem!!.serviceId != item.serviceId || currentItem!!.url != item.url

        currentItem = item

        when {
            // wrong window (this should be impossible, as this method is called with
            // `item=playQueue.getItem()`, so the index of that item must be equal to `getIndex()`)
            playQueueIndex != playQueue!!.index -> Log.e(TAG, "Playback - Play Queue may be not in sync: item index=[$playQueueIndex], queue index=[${playQueue!!.index}]")
            // the queue and the player's timeline are not in sync, since the play queue index
            // points outside of the timeline
            playlistSize in 1..playQueueIndex || playQueueIndex < 0 -> Log.e(TAG, "Playback - Trying to seek to invalid index=[$playQueueIndex] with playlist length=[$playlistSize]")
            // either the player needs to be unblocked, or the play queue index has just been
            // changed and needs to be synchronized, or the player is not playing
            wasBlocked || playlistIndex != playQueueIndex || !isPlaying -> {
                Logd(TAG, "Playback - Rewinding to correct index=[$playQueueIndex], from=[$playlistIndex], size=[$playlistSize].")
                // unset the current (now outdated) thumbnail to ensure it is not used during sync
                if (removeThumbnailBeforeSync) onThumbnailLoaded(null)

                // sync the player index with the queue index, and seek to the correct position
                if (item.recoveryPosition != PlayQueueItem.RECOVERY_UNSET) {
                    exoPlayer!!.seekTo(playQueueIndex, item.recoveryPosition)
                    playQueue!!.unsetRecovery(playQueueIndex)
                } else exoPlayer!!.seekToDefaultPosition(playQueueIndex)
            }
        }
    }

    fun seekTo(positionMillis: Long) {
        Logd(TAG, "seekBy() called with: position = [$positionMillis]")
        // prevent invalid positions when fast-forwarding/-rewinding
        if (!exoPlayerIsNull()) exoPlayer!!.seekTo(MathUtils.clamp(positionMillis, 0, exoPlayer!!.duration))
    }

    private fun seekBy(offsetMillis: Long) {
        Logd(TAG, "seekBy() called with: offsetMillis = [$offsetMillis]")
        seekTo(exoPlayer!!.currentPosition + offsetMillis)
    }

    fun seekToDefault() {
        if (!exoPlayerIsNull()) exoPlayer!!.seekToDefaultPosition()
    }

    fun play() {
        Logd(TAG, "play() called")
        if (audioReactor == null || playQueue == null || exoPlayerIsNull()) return

        if (!isMuted) audioReactor!!.requestAudioFocus()

        if (currentState == STATE_COMPLETED) {
            if (playQueue!!.index == 0) seekToDefault()
            else playQueue!!.index = 0
        }

        exoPlayer!!.play()
        saveStreamProgressState()
    }

    fun pause() {
        Logd(TAG, "pause() called")
        if (audioReactor == null || exoPlayerIsNull()) return

        audioReactor!!.abandonAudioFocus()
        exoPlayer!!.pause()
        saveStreamProgressState()
    }

    fun playPause() {
        Logd(TAG, "onPlayPause() called")
        // When state is completed (replay button is shown) then (re)play and do not pause
        if (playWhenReady && currentState != STATE_COMPLETED) pause()
        else play()
    }

    fun playPrevious() {
        Logd(TAG, "onPlayPrevious() called")
        if (exoPlayerIsNull() || playQueue == null) return

        /* If current playback has run for PLAY_PREV_ACTIVATION_LIMIT_MILLIS milliseconds,
         * restart current track. Also restart the track if the current track
         * is the first in a queue.*/
        if (exoPlayer!!.currentPosition > PLAY_PREV_ACTIVATION_LIMIT_MILLIS || playQueue!!.index == 0) {
            seekToDefault()
            playQueue!!.offsetIndex(0)
        } else {
            saveStreamProgressState()
            playQueue!!.offsetIndex(-1)
        }
        triggerProgressUpdate()
    }

    fun playNext() {
        Logd(TAG, "onPlayNext() called")
        if (playQueue == null) return

        saveStreamProgressState()
        playQueue!!.offsetIndex(+1)
        triggerProgressUpdate()
    }

    fun fastForward() {
        Logd(TAG, "fastRewind() called")
        seekBy(PlayerHelper.retrieveSeekDurationFromPreferences(this).toLong())
        triggerProgressUpdate()
    }

    fun fastRewind() {
        Logd(TAG, "fastRewind() called")
        seekBy(-PlayerHelper.retrieveSeekDurationFromPreferences(this).toLong())
        triggerProgressUpdate()
    }

    private fun registerStreamViewed() {
        currentStreamInfo.ifPresent { info: StreamInfo? ->
            databaseUpdateDisposable.add(recordManager.onViewed(info).onErrorComplete().subscribe())
        }
    }

    private fun saveStreamProgressState(progressMillis: Long) {
        currentStreamInfo.ifPresent { info: StreamInfo ->
            if (!prefs.getBoolean(context.getString(R.string.enable_watch_history_key), true)) return@ifPresent
            Logd(TAG, "saveStreamProgressState() called with: progressMillis=$progressMillis, currentMetadata=[${info.name}]")

            databaseUpdateDisposable.add(recordManager.saveStreamState(info, progressMillis)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnError { e: Throwable -> if (DEBUG) e.printStackTrace() }
                .onErrorComplete()
                .subscribe())
        }
    }

    fun saveStreamProgressState() {
        // Make sure play queue and current window index are equal, to prevent saving state for
        // the wrong stream on discontinuity (e.g. when the stream just changed but the
        // playQueue index and currentMetadata still haven't updated)
        if (exoPlayerIsNull() || currentMetadata == null || playQueue == null || playQueue!!.index != exoPlayer!!.currentMediaItemIndex) return

        // Save current position. It will help to restore this position once a user
        // wants to play prev or next stream from the queue
        playQueue!!.setRecovery(playQueue!!.index, exoPlayer!!.contentPosition)
        saveStreamProgressState(exoPlayer!!.currentPosition)
    }

    fun saveStreamProgressStateCompleted() {
        // current stream has ended, so the progress is its duration (+1 to overcome rounding)
        currentStreamInfo.ifPresent { info: StreamInfo -> saveStreamProgressState((info.duration + 1) * 1000) }
    }

    private fun updateMetadataWith(info: StreamInfo) {
        Logd(TAG, "Playback - onMetadataChanged() called, playing: " + info.name)
        if (exoPlayerIsNull()) return

        maybeAutoQueueNextStream(info)

        loadCurrentThumbnail(info.thumbnails)
        registerStreamViewed()

        notifyMetadataUpdateToListeners()
        notifyAudioTrackUpdateToListeners()
        UIs.call { playerUi: PlayerUi -> playerUi.onMetadataChanged(info) }
    }

    private fun maybeAutoQueueNextStream(info: StreamInfo) {
        if (playQueue == null || playQueue!!.index != playQueue!!.size() - 1 || repeatMode != Player.REPEAT_MODE_OFF
                || !PlayerHelper.isAutoQueueEnabled(context)) return

        // auto queue when starting playback on the last item when not repeating
        val autoQueue = PlayerHelper.autoQueueOf(info, playQueue!!.streams)
        if (autoQueue != null) playQueue!!.append(autoQueue.streams)
    }

    fun selectQueueItem(item: PlayQueueItem?) {
        if (playQueue == null || exoPlayerIsNull()) return

        val index = playQueue!!.indexOf(item!!)
        if (index == -1) return

        if (playQueue!!.index == index && exoPlayer!!.currentMediaItemIndex == index) seekToDefault()
        else saveStreamProgressState()

        playQueue!!.index = index
    }

    override fun onPlayQueueEdited() {
        notifyPlaybackUpdateToListeners()
        UIs.call { obj: PlayerUi -> obj.onPlayQueueEdited() }
    }

    override fun sourceOf(item: PlayQueueItem?, info: StreamInfo?): MediaSource? {
        if (audioPlayerSelected()) return audioResolver.resolve(info!!)

        // If the current info has only video streams with audio and if the stream is played as
        // audio, we need to use the audio resolver, otherwise the video stream will be played
        // in background.
        if (isAudioOnly && videoResolver.getStreamSourceType().orElse(VideoPlaybackResolver.SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY) == VideoPlaybackResolver.SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY)
            return audioResolver.resolve(info!!)

        // Even if the stream is played in background, we need to use the video resolver if the
        // info played is separated video-only and audio-only streams; otherwise, if the audio
        // resolver was called when the app was in background, the app will only stream audio when
        // the user come back to the app and will never fetch the video stream.
        // Note that the video is not fetched when the app is in background because the video
        // renderer is fully disabled (see useVideoSource method), except for HLS streams
        // (see https://github.com/google/ExoPlayer/issues/9282).
        return videoResolver.resolve(info!!)
    }

    fun disablePreloadingOfCurrentTrack() {
        loadController.disablePreloadingOfCurrentTrack()
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        Logd(TAG, "onVideoSizeChanged() called with: width / height = [${videoSize.width} / ${videoSize.height} = ${(videoSize.width.toFloat()) / videoSize.height}], unappliedRotationDegrees = [${videoSize.unappliedRotationDegrees}], pixelWidthHeightRatio = [${videoSize.pixelWidthHeightRatio}]")

        if (videoSize.width > 0 && videoSize.height > 0) UIs.call { playerUi: PlayerUi -> playerUi.onVideoSizeChanged(videoSize) }
    }

    fun setFragmentListener(listener: PlayerServiceEventListener?) {
        fragmentListener = listener
        UIs.call { obj: PlayerUi -> obj.onFragmentListenerSet() }
        notifyQueueUpdateToListeners()
        notifyMetadataUpdateToListeners()
        notifyPlaybackUpdateToListeners()
        triggerProgressUpdate()
    }

    fun removeFragmentListener(listener: PlayerServiceEventListener) {
        if (fragmentListener === listener) fragmentListener = null
    }

    fun setActivityListener(listener: PlayerEventListener?) {
        activityListener = listener
        // TODO: why not queue update?
        notifyMetadataUpdateToListeners()
        notifyPlaybackUpdateToListeners()
        triggerProgressUpdate()
    }

    fun removeActivityListener(listener: PlayerEventListener) {
        if (activityListener === listener) activityListener = null
    }

    fun stopActivityBinding() {
        fragmentListener?.onServiceStopped()
        fragmentListener = null
        activityListener?.onServiceStopped()
        activityListener = null
    }

    private fun notifyQueueUpdateToListeners() {
        if (playQueue != null) {
            fragmentListener?.onQueueUpdate(playQueue)
            activityListener?.onQueueUpdate(playQueue)
        }
    }

    private fun notifyMetadataUpdateToListeners() {
        currentStreamInfo.ifPresent { info: StreamInfo? ->
            fragmentListener?.onMetadataUpdate(info, playQueue)
            activityListener?.onMetadataUpdate(info, playQueue)
        }
    }

    private fun notifyPlaybackUpdateToListeners() {
        if (!exoPlayerIsNull() && playQueue != null)
            fragmentListener?.onPlaybackUpdate(currentState, repeatMode, playQueue!!.isShuffled, exoPlayer!!.playbackParameters)

        if (!exoPlayerIsNull() && playQueue != null)
            activityListener?.onPlaybackUpdate(currentState, repeatMode, playQueue!!.isShuffled, playbackParameters)
    }

    private fun notifyProgressUpdateToListeners(currentProgress: Int, duration: Int, bufferPercent: Int) {
        fragmentListener?.onProgressUpdate(currentProgress, duration, bufferPercent)
        activityListener?.onProgressUpdate(currentProgress, duration, bufferPercent)
    }

    private fun notifyAudioTrackUpdateToListeners() {
        fragmentListener?.onAudioTrackUpdate()
        activityListener?.onAudioTrackUpdate()
    }

    fun useVideoSource(videoEnabled: Boolean) {
        if (playQueue == null || audioPlayerSelected()) return

        isAudioOnly = !videoEnabled
        currentStreamInfo.ifPresentOrElse({ info: StreamInfo ->
            // In case we don't know the source type, fall back to either video-with-audio, or
            // audio-only source type
            val sourceType = videoResolver.getStreamSourceType().orElse(VideoPlaybackResolver.SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY)
            if (playQueueManagerReloadingNeeded(sourceType, info, videoRendererIndex)) reloadPlayQueueManager()
            setRecovery()
            // Disable or enable video and subtitles renderers depending of the videoEnabled value
            trackSelector.setParameters(trackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !videoEnabled)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !videoEnabled))
        }, {
            /*
            The current metadata may be null sometimes (for e.g. when using an unstable connection
            in livestreams) so we will be not able to execute the block below

            Reload the play queue manager in this case, which is the behavior when we don't know the
            index of the video renderer or playQueueManagerReloadingNeeded returns true
            */
            reloadPlayQueueManager()
            setRecovery()
        })
    }

    /**
     * Return whether the play queue manager needs to be reloaded when switching player type.
     *
     *
     *
     * The play queue manager needs to be reloaded if the video renderer index is not known and if
     * the content is not an audio content, but also if none of the following cases is met:
     *
     *
     *  * the content is an [audio stream][StreamType.AUDIO_STREAM], an
     * [audio live stream][StreamType.AUDIO_LIVE_STREAM], or a
     * [ended audio live stream][StreamType.POST_LIVE_AUDIO_STREAM];
     *  * the content is a [live stream][StreamType.LIVE_STREAM] and the source type is a
     * [live source][SourceType.LIVE_STREAM];
     *  * the content's source is [a video stream][SourceType.VIDEO_WITH_SEPARATED_AUDIO] or has no audio-only streams available **and** is a
     * [video stream][StreamType.VIDEO_STREAM], an
     * [ended live stream][StreamType.POST_LIVE_STREAM], or a
     * [live stream][StreamType.LIVE_STREAM].
     *
     *
     *
     *
     * @param sourceType         the [SourceType] of the stream
     * @param streamInfo         the [StreamInfo] of the stream
     * @param videoRendererIndex the video renderer index of the video source, if that's a video
     * source (or [.RENDERER_UNAVAILABLE])
     * @return whether the play queue manager needs to be reloaded
     */
    private fun playQueueManagerReloadingNeeded(sourceType: VideoPlaybackResolver.SourceType, streamInfo: StreamInfo, videoRendererIndex: Int): Boolean {
        val streamType = streamInfo.streamType
        val isStreamTypeAudio = isAudio(streamType)

        if (videoRendererIndex == RENDERER_UNAVAILABLE && !isStreamTypeAudio) return true

        // The content is an audio stream, an audio live stream, or a live stream with a live
        // source: it's not needed to reload the play queue manager because the stream source will
        // be the same
        if (isStreamTypeAudio || (streamType == StreamType.LIVE_STREAM && sourceType == VideoPlaybackResolver.SourceType.LIVE_STREAM)) return false

        // The content's source is a video with separated audio or a video with audio -> the video
        // and its fetch may be disabled
        // The content's source is a video with embedded audio and the content has no separated
        // audio stream available: it's probably not needed to reload the play queue manager
        // because the stream source will be probably the same as the current played
        // It's not needed to reload the play queue manager only if the content's stream type
        // is a video stream, a live stream or an ended live stream
        if (sourceType == VideoPlaybackResolver.SourceType.VIDEO_WITH_SEPARATED_AUDIO
                || (sourceType == VideoPlaybackResolver.SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY && Utils.isNullOrEmpty(streamInfo.audioStreams))) return !isVideo(streamType)

        // Other cases: the play queue manager reload is needed
        return true
    }

    fun exoPlayerIsNull(): Boolean {
        return exoPlayer == null
    }

    fun setPlaybackQuality(quality: String?) {
        saveStreamProgressState()
        setRecovery()
        videoResolver.playbackQuality = quality
        reloadPlayQueueManager()
    }

    fun setAudioTrack(audioTrackId: String?) {
        saveStreamProgressState()
        setRecovery()
        videoResolver.audioTrack = audioTrackId
        audioResolver.audioTrack = audioTrackId
        reloadPlayQueueManager()
    }

    fun audioPlayerSelected(): Boolean {
        return playerType == PlayerType.AUDIO
    }

    fun videoPlayerSelected(): Boolean {
        return playerType == PlayerType.MAIN
    }

    fun popupPlayerSelected(): Boolean {
        return playerType == PlayerType.POPUP
    }

    fun getFragmentListener(): Optional<PlayerServiceEventListener> {
        return Optional.ofNullable(fragmentListener)
    }

    @OptIn(UnstableApi::class) class LoadController : DefaultLoadControl() {
        private var preloadingEnabled = true

        override fun onPrepared() {
            preloadingEnabled = true
            super.onPrepared()
        }

        override fun onStopped() {
            preloadingEnabled = true
            super.onStopped()
        }

        override fun onReleased() {
            preloadingEnabled = true
            super.onReleased()
        }

        override fun shouldContinueLoading(playbackPositionUs: Long,
                                           bufferedDurationUs: Long,
                                           playbackSpeed: Float
        ): Boolean {
            if (!preloadingEnabled) {
                return false
            }
            return super.shouldContinueLoading(
                playbackPositionUs, bufferedDurationUs, playbackSpeed)
        }

        fun disablePreloadingOfCurrentTrack() {
            preloadingEnabled = false
        }

        companion object {
            const val TAG: String = "LoadController"
        }
    }

    @UnstableApi
    class CustomRenderersFactory(context: Context?) : DefaultRenderersFactory(context!!) {
        override fun buildVideoRenderers(context: Context,
                                         extensionRendererMode: @ExtensionRendererMode Int,
                                         mediaCodecSelector: MediaCodecSelector,
                                         enableDecoderFallback: Boolean,
                                         eventHandler: Handler,
                                         eventListener: VideoRendererEventListener,
                                         allowedVideoJoiningTimeMs: Long,
                                         out: ArrayList<Renderer>
        ) {
            out.add(CustomMediaCodecVideoRenderer(context, codecAdapterFactory,
                mediaCodecSelector, allowedVideoJoiningTimeMs, enableDecoderFallback, eventHandler,
                eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY))
        }

        @UnstableApi
        class CustomMediaCodecVideoRenderer(context: Context?,
                                            codecAdapterFactory: MediaCodecAdapter.Factory?,
                                            mediaCodecSelector: MediaCodecSelector?,
                                            allowedJoiningTimeMs: Long,
                                            enableDecoderFallback: Boolean,
                                            eventHandler: Handler?,
                                            eventListener: VideoRendererEventListener?,
                                            maxDroppedFramesToNotify: Int
        ) : MediaCodecVideoRenderer(context!!, codecAdapterFactory!!, mediaCodecSelector!!, allowedJoiningTimeMs,
            enableDecoderFallback, eventHandler, eventListener, maxDroppedFramesToNotify) {
            override fun codecNeedsSetOutputSurfaceWorkaround(name: String): Boolean {
                return true
            }
        }
    }

    @UnstableApi class AudioPlaybackResolver(private val context: Context,
                                             private val dataSource: PlayerDataSource) : PlaybackResolver {
        var audioTrack: String? = null

        /**
         * Get a media source providing audio. If a service has no separate [AudioStream]s we
         * use a video stream as audio source to support audio background playback.
         *
         * @param info of the stream
         * @return the audio source to use or null if none could be found
         */
        override fun resolve(info: StreamInfo): MediaSource? {
            val liveSource = maybeBuildLiveMediaSource(dataSource, info)
            if (liveSource != null) return liveSource

            val audioStreams: List<AudioStream> = getFilteredAudioStreams(context, info.audioStreams).filterNotNull()
            val stream: Stream?
            val tag: MediaItemTag

            if (audioStreams.isNotEmpty()) {
                val audioIndex = getAudioFormatIndex(context, audioStreams, audioTrack)
                stream = getStreamForIndex(audioIndex, audioStreams)
                tag = StreamInfoTag.of(info, audioStreams, audioIndex)
            } else {
                val videoStreams: List<VideoStream> = getPlayableStreams(info.videoStreams, info.serviceId)
                if (videoStreams.isNotEmpty()) {
                    val index = getDefaultResolutionIndex(context, videoStreams)
                    stream = getStreamForIndex(index, videoStreams)
                    tag = StreamInfoTag.of(info)
                } else {
                    return null
                }
            }

            try {
                return buildMediaSource(dataSource, stream!!, info, cacheKeyOf(info, stream)!!, tag)
            } catch (e: PlaybackResolver.ResolverException) {
                Log.e(TAG, "Unable to create audio source", e)
                return null
            }
        }

        fun getStreamForIndex(index: Int, streams: List<Stream?>): Stream? {
            if (index >= 0 && index < streams.size) return streams[index]
            return null
        }

        companion object {
            private val TAG: String = AudioPlaybackResolver::class.java.simpleName
        }
    }

    @UnstableApi class VideoPlaybackResolver(private val context: Context,
                                             private val dataSource: PlayerDataSource,
                                             private val qualityResolver: QualityResolver) : PlaybackResolver {

        internal var streamSourceType: SourceType? = null

        var playbackQuality: String? = null
        var audioTrack: String? = null

        enum class SourceType {
            LIVE_STREAM,
            VIDEO_WITH_SEPARATED_AUDIO,
            VIDEO_WITH_AUDIO_OR_AUDIO_ONLY
        }

        override fun resolve(info: StreamInfo): MediaSource? {
            val liveSource = maybeBuildLiveMediaSource(dataSource, info)
            if (liveSource != null) {
                streamSourceType = SourceType.LIVE_STREAM
                return liveSource
            }

            val mediaSources: MutableList<MediaSource> = ArrayList()

            // Create video stream source
            val videoStreamsList = getSortedStreamVideosList(context, getPlayableStreams(info.videoStreams, info.serviceId),
                getPlayableStreams(info.videoOnlyStreams, info.serviceId), false, true)
            val audioStreamsList: List<AudioStream> = getFilteredAudioStreams(context, info.audioStreams).filterNotNull()
            val videoIndex = when {
                videoStreamsList.isEmpty() -> -1
                playbackQuality == null -> qualityResolver.getDefaultResolutionIndex(videoStreamsList)
                else -> qualityResolver.getOverrideResolutionIndex(videoStreamsList, playbackQuality)
            }

            val audioIndex = getAudioFormatIndex(context, audioStreamsList, audioTrack)
            val tag: MediaItemTag = StreamInfoTag.of(info, videoStreamsList, videoIndex, audioStreamsList, audioIndex)
            val video = tag.maybeQuality
                .map<VideoStream?> { it.selectedVideoStream }
                .orElse(null)
            val audio = tag.maybeAudioTrack
                .map<AudioStream?> { it.selectedAudioStream }
                .orElse(null)

            if (video != null) {
                try {
                    val streamSource = buildMediaSource(dataSource, video, info, cacheKeyOf(info, video)!!, tag)
                    if (streamSource != null) mediaSources.add(streamSource)
                } catch (e: PlaybackResolver.ResolverException) {
                    Log.e(TAG, "Unable to create video source", e)
                    return null
                }
            }

            // Use the audio stream if there is no video stream, or
            // merge with audio stream in case if video does not contain audio
            if (audio != null && (video == null || video.isVideoOnly() || audioTrack != null)) {
                try {
                    val audioSource = buildMediaSource(dataSource, audio, info, cacheKeyOf(info, audio)!!, tag)
                    if (audioSource != null) mediaSources.add(audioSource)
                    streamSourceType = SourceType.VIDEO_WITH_SEPARATED_AUDIO
                } catch (e: PlaybackResolver.ResolverException) {
                    Log.e(TAG, "Unable to create audio source", e)
                    return null
                }
            } else {
                streamSourceType = SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY
            }

            // If there is no audio or video sources, then this media source cannot be played back
            if (mediaSources.isEmpty()) return null

            // Below are auxiliary media sources

            // Create subtitle sources
            val subtitlesStreams = info.subtitles
            if (subtitlesStreams != null) {
                // Torrent and non URL subtitles are not supported by ExoPlayer
                val nonTorrentAndUrlStreams: List<SubtitlesStream> = getUrlAndNonTorrentStreams(subtitlesStreams)
                for (subtitle in nonTorrentAndUrlStreams) {
                    val mediaFormat = subtitle.format
                    if (mediaFormat != null) {
                        val textRoleFlag: @RoleFlags Int = if (subtitle.isAutoGenerated) C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND else C.ROLE_FLAG_CAPTION
                        val textMediaItem = SubtitleConfiguration.Builder(
                            Uri.parse(subtitle.content))
                            .setMimeType(mediaFormat.getMimeType())
                            .setRoleFlags(textRoleFlag)
                            .setLanguage(PlayerHelper.captionLanguageOf(context, subtitle))
                            .build()
                        val textSource: MediaSource = dataSource.singleSampleMediaSourceFactory
                            .createMediaSource(textMediaItem, C.TIME_UNSET)
                        mediaSources.add(textSource)
                    }
                }
            }

            return if (mediaSources.size == 1) {
                mediaSources[0]
            } else {
                MergingMediaSource(true, *mediaSources.toTypedArray<MediaSource>())
            }
        }

        /**
         * Returns the last resolved [StreamInfo]'s [source type][SourceType].
         *
         * @return [Optional.empty] if nothing was resolved, otherwise the [SourceType]
         * of the last resolved [StreamInfo] inside an [Optional]
         */
        fun getStreamSourceType(): Optional<SourceType> {
            return Optional.ofNullable(streamSourceType)
        }

        interface QualityResolver {
            fun getDefaultResolutionIndex(sortedVideos: List<VideoStream>?): Int

            fun getOverrideResolutionIndex(sortedVideos: List<VideoStream>?, playbackQuality: String?): Int
        }

        companion object {
            private val TAG: String = VideoPlaybackResolver::class.java.simpleName
        }
    }

    @OptIn(UnstableApi::class)
    class PlayerUiList(vararg initialPlayerUis: PlayerUi) {
        val playerUis: MutableList<PlayerUi> = ArrayList()

        /**
         * Creates a [PlayerUiList] starting with the provided player uis. The provided player uis
         * will not be prepared like those passed to [.addAndPrepare], because when
         * the [PlayerUiList] constructor is called, the player is still not running and it
         * wouldn't make sense to initialize uis then. Instead the player will initialize them by doing
         * proper calls to [.call].
         *
         * @param initialPlayerUis the player uis this list should start with; the order will be kept
         */
        init {
            playerUis.addAll(listOf(*initialPlayerUis))
        }

        /**
         * Adds the provided player ui to the list and calls on it the initialization functions that
         * apply based on the current player state. The preparation step needs to be done since when UIs
         * are removed and re-added, the player will not call e.g. initPlayer again since the exoplayer
         * is already initialized, but we need to notify the newly built UI that the player is ready
         * nonetheless.
         * @param playerUi the player ui to prepare and add to the list; its [                 ][PlayerUi.getPlayer] will be used to query information about the player
         * state
         */
        fun addAndPrepare(playerUi: PlayerUi) {
            if (playerUi.playerManager.getFragmentListener().isPresent) {
                // make sure UIs know whether a service is connected or not
                playerUi.onFragmentListenerSet()
            }

            if (!playerUi.playerManager.exoPlayerIsNull()) {
                playerUi.initPlayer()
                if (playerUi.playerManager.playQueue != null) {
                    playerUi.initPlayback()
                }
            }

            playerUis.add(playerUi)
        }

        /**
         * Destroys all matching player UIs and removes them from the list.
         * @param playerUiType the class of the player UI to destroy; the [                     ][Class.isInstance] method will be used, so even subclasses will be
         * destroyed and removed
         * @param <T>          the class type parameter
        </T> */
        fun <T> destroyAll(playerUiType: Class<T>) {
            playerUis.stream()
                .filter { obj: PlayerUi? -> playerUiType.isInstance(obj) }
                .forEach { playerUi: PlayerUi ->
                    playerUi.destroyPlayer()
                    playerUi.destroy()
                }
            playerUis.removeIf { obj: PlayerUi? -> playerUiType.isInstance(obj) }
        }

        /**
         * @param playerUiType the class of the player UI to return; the [                     ][Class.isInstance] method will be used, so even subclasses could
         * be returned
         * @param <T>          the class type parameter
         * @return the first player UI of the required type found in the list, or an empty [         ] otherwise
        </T> */
        fun <T> get(playerUiType: Class<T>): Optional<T> {
            return playerUis.stream()
                .filter { obj: PlayerUi? -> playerUiType.isInstance(obj) }
                .map { obj: PlayerUi? -> playerUiType.cast(obj) }
                .findFirst()
        }

        /**
         * Calls the provided consumer on all player UIs in the list, in order of addition.
         * @param consumer the consumer to call with player UIs
         */
        fun call(consumer: Consumer<PlayerUi>?) {
            playerUis.stream().forEachOrdered(consumer)
        }
    }

    interface Resolver<Source, Product> {
        fun resolve(source: Source): Product?
    }

    /**
     * This interface is just a shorthand for [Resolver] with [StreamInfo] as source and
     * [MediaSource] as product. It contains many static methods that can be used by classes
     * implementing this interface, and nothing else.
     */
    @UnstableApi interface PlaybackResolver : Resolver<StreamInfo, MediaSource> {
        //endregion
        //region Resolver exception
        class ResolverException : Exception {
            constructor(message: String?) : super(message)

            constructor(message: String?, cause: Throwable?) : super(message, cause)
        } //endregion


        @UnstableApi companion object {
            //region Cache key generation
            private fun commonCacheKeyOf(info: StreamInfo,
                                         stream: Stream,
                                         resolutionOrBitrateUnknown: Boolean
            ): StringBuilder {
                // stream info service id
                val cacheKey = StringBuilder(info.serviceId)

                // stream info id
                cacheKey.append(" ")
                cacheKey.append(info.id)

                // stream id (even if unknown)
                cacheKey.append(" ")
                cacheKey.append(stream.id)

                // mediaFormat (if not null)
                val mediaFormat = stream.format
                if (mediaFormat != null) {
                    cacheKey.append(" ")
                    cacheKey.append(mediaFormat.getName())
                }

                // content (only if other information is missing)
                // If the media format and the resolution/bitrate are both missing, then we don't have
                // enough information to distinguish this stream from other streams.
                // So, only in that case, we use the content (i.e. url or manifest) to differentiate
                // between streams.
                // Note that if the content were used even when other information is present, then two
                // streams with the same stats but with different contents (e.g. because the url was
                // refreshed) will be considered different (i.e. with a different cacheKey), making the
                // cache useless.
                if (resolutionOrBitrateUnknown && mediaFormat == null) {
                    cacheKey.append(" ")
                    cacheKey.append(Objects.hash(stream.content, stream.manifestUrl))
                }

                return cacheKey
            }

            /**
             * Builds the cache key of a [video stream][VideoStream].
             *
             *
             *
             * A cache key is unique to the features of the provided video stream, and when possible
             * independent of *transient* parameters (such as the URL of the stream).
             * This ensures that there are no conflicts, but also that the cache is used as much as
             * possible: the same cache should be used for two streams which have the same features but
             * e.g. a different URL, since the URL might have been reloaded in the meantime, but the stream
             * actually referenced by the URL is still the same.
             *
             *
             * @param info        the [stream info][StreamInfo], to distinguish between streams with
             * the same features but coming from different stream infos
             * @param videoStream the [video stream][VideoStream] for which the cache key should be
             * created
             * @return a key to be used to store the cache of the provided [video stream][VideoStream]
             */
            @JvmStatic
            fun cacheKeyOf(info: StreamInfo, videoStream: VideoStream): String? {
                val resolutionUnknown = videoStream.getResolution() == VideoStream.RESOLUTION_UNKNOWN
                val cacheKey = commonCacheKeyOf(info, videoStream, resolutionUnknown)

                // resolution (if known)
                if (!resolutionUnknown) {
                    cacheKey.append(" ")
                    cacheKey.append(videoStream.getResolution())
                }

                // isVideoOnly
                cacheKey.append(" ")
                cacheKey.append(videoStream.isVideoOnly())

                return cacheKey.toString()
            }

            /**
             * Builds the cache key of an audio stream.
             *
             *
             *
             * A cache key is unique to the features of the provided [audio stream][AudioStream], and
             * when possible independent of *transient* parameters (such as the URL of the stream).
             * This ensures that there are no conflicts, but also that the cache is used as much as
             * possible: the same cache should be used for two streams which have the same features but
             * e.g. a different URL, since the URL might have been reloaded in the meantime, but the stream
             * actually referenced by the URL is still the same.
             *
             *
             * @param info        the [stream info][StreamInfo], to distinguish between streams with
             * the same features but coming from different stream infos
             * @param audioStream the [audio stream][AudioStream] for which the cache key should be
             * created
             * @return a key to be used to store the cache of the provided [audio stream][AudioStream]
             */
            @JvmStatic
            fun cacheKeyOf(info: StreamInfo, audioStream: AudioStream): String? {
                val averageBitrateUnknown = audioStream.averageBitrate == AudioStream.UNKNOWN_BITRATE
                val cacheKey = commonCacheKeyOf(info, audioStream, averageBitrateUnknown)

                // averageBitrate (if known)
                if (!averageBitrateUnknown) {
                    cacheKey.append(" ")
                    cacheKey.append(audioStream.averageBitrate)
                }

                if (audioStream.audioTrackId != null) {
                    cacheKey.append(" ")
                    cacheKey.append(audioStream.audioTrackId)
                }

                if (audioStream.audioLocale != null) {
                    cacheKey.append(" ")
                    cacheKey.append(audioStream.audioLocale!!.isO3Language)
                }

                return cacheKey.toString()
            }

            /**
             * Use common base type [Stream] to handle [AudioStream] or [VideoStream]
             * transparently. For more info see [.cacheKeyOf] or
             * [.cacheKeyOf].
             *
             * @param info   the [stream info][StreamInfo], to distinguish between streams with
             * the same features but coming from different stream infos
             * @param stream the [Stream] ([AudioStream] or [VideoStream])
             * for which the cache key should be created
             * @return a key to be used to store the cache of the provided [Stream]
             */
            @JvmStatic
            fun cacheKeyOf(info: StreamInfo, stream: Stream?): String? {
                if (stream is AudioStream) {
                    return cacheKeyOf(info, stream)
                } else if (stream is VideoStream) {
                    return cacheKeyOf(info, stream)
                }
                throw RuntimeException("no audio or video stream. That should never happen")
            }


            //endregion
            //region Live media sources
            @JvmStatic
            fun maybeBuildLiveMediaSource(dataSource: PlayerDataSource,
                                          info: StreamInfo
            ): MediaSource? {
                if (!isLiveStream(info.streamType)) {
                    return null
                }

                try {
                    val tag = StreamInfoTag.of(info)
                    if (!info.hlsUrl.isEmpty()) {
                        return buildLiveMediaSource(dataSource, info.hlsUrl, C.CONTENT_TYPE_HLS, tag)
                    } else if (!info.dashMpdUrl.isEmpty()) {
                        return buildLiveMediaSource(
                            dataSource, info.dashMpdUrl, C.CONTENT_TYPE_DASH, tag)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error when generating live media source, falling back to standard sources",
                        e)
                }

                return null
            }

            @Throws(ResolverException::class)
            fun buildLiveMediaSource(dataSource: PlayerDataSource,
                                     sourceUrl: String?,
                                     type: @C.ContentType Int,
                                     metadata: MediaItemTag?
            ): MediaSource? {
                val factory: MediaSource.Factory = when (type) {
                    C.CONTENT_TYPE_SS -> dataSource.liveSsMediaSourceFactory
                    C.CONTENT_TYPE_DASH -> dataSource.liveDashMediaSourceFactory
                    C.CONTENT_TYPE_HLS -> dataSource.liveHlsMediaSourceFactory
                    C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_RTSP -> throw ResolverException("Unsupported type: $type")
                    else -> throw ResolverException("Unsupported type: $type")
                }
                return factory.createMediaSource(
                    MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(Uri.parse(sourceUrl))
                        .setLiveConfiguration(
                            LiveConfiguration.Builder()
                                .setTargetOffsetMs(PlayerDataSource.LIVE_STREAM_EDGE_GAP_MILLIS.toLong())
                                .build())
                        .build())
            }


            //endregion
            //region Generic media sources
            @JvmStatic
            @Throws(ResolverException::class)
            fun buildMediaSource(dataSource: PlayerDataSource,
                                 stream: Stream,
                                 streamInfo: StreamInfo,
                                 cacheKey: String,
                                 metadata: MediaItemTag
            ): MediaSource? {
                if (streamInfo.service === ServiceList.YouTube) {
                    return createYoutubeMediaSource(stream, streamInfo, dataSource, cacheKey, metadata)
                }

                val deliveryMethod = stream.deliveryMethod
                return when (deliveryMethod) {
                    DeliveryMethod.PROGRESSIVE_HTTP -> buildProgressiveMediaSource(dataSource,
                        stream,
                        cacheKey,
                        metadata)
                    DeliveryMethod.DASH -> buildDashMediaSource(dataSource, stream, cacheKey, metadata)
                    DeliveryMethod.HLS -> buildHlsMediaSource(dataSource, stream, cacheKey, metadata)
                    DeliveryMethod.SS -> buildSSMediaSource(dataSource, stream, cacheKey, metadata)
                    else -> throw ResolverException("Unsupported delivery type: $deliveryMethod")
                }
            }

            @Throws(ResolverException::class)
            private fun buildProgressiveMediaSource(
                    dataSource: PlayerDataSource,
                    stream: Stream,
                    cacheKey: String,
                    metadata: MediaItemTag
            ): ProgressiveMediaSource {
                if (!stream.isUrl) {
                    throw ResolverException("Non URI progressive contents are not supported")
                }
                throwResolverExceptionIfUrlNullOrEmpty(stream.content)
                return dataSource.progressiveMediaSourceFactory.createMediaSource(
                    MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(Uri.parse(stream.content))
                        .setCustomCacheKey(cacheKey)
                        .build())
            }

            @Throws(ResolverException::class)
            private fun buildDashMediaSource(dataSource: PlayerDataSource,
                                             stream: Stream,
                                             cacheKey: String,
                                             metadata: MediaItemTag
            ): DashMediaSource {
                if (stream.isUrl) {
                    throwResolverExceptionIfUrlNullOrEmpty(stream.content)
                    return dataSource.dashMediaSourceFactory.createMediaSource(
                        MediaItem.Builder()
                            .setTag(metadata)
                            .setUri(Uri.parse(stream.content))
                            .setCustomCacheKey(cacheKey)
                            .build())
                }

                try {
                    return dataSource.dashMediaSourceFactory.createMediaSource(
                        createDashManifest(stream.content, stream),
                        MediaItem.Builder()
                            .setTag(metadata)
                            .setUri(manifestUrlToUri(stream.manifestUrl))
                            .setCustomCacheKey(cacheKey)
                            .build())
                } catch (e: IOException) {
                    throw ResolverException(
                        "Could not create a DASH media source/manifest from the manifest text", e)
                }
            }

            @Throws(IOException::class)
            private fun createDashManifest(manifestContent: String,
                                           stream: Stream
            ): DashManifest {
                return DashManifestParser().parse(manifestUrlToUri(stream.manifestUrl),
                    ByteArrayInputStream(manifestContent.toByteArray(StandardCharsets.UTF_8)))
            }

            @Throws(ResolverException::class)
            private fun buildHlsMediaSource(dataSource: PlayerDataSource,
                                            stream: Stream,
                                            cacheKey: String,
                                            metadata: MediaItemTag
            ): HlsMediaSource {
                if (stream.isUrl) {
                    throwResolverExceptionIfUrlNullOrEmpty(stream.content)
                    return dataSource.getHlsMediaSourceFactory(null).createMediaSource(
                        MediaItem.Builder()
                            .setTag(metadata)
                            .setUri(Uri.parse(stream.content))
                            .setCustomCacheKey(cacheKey)
                            .build())
                }

                val hlsDataSourceFactoryBuilder =
                    NonUriHlsDataSourceFactory.Builder()
                hlsDataSourceFactoryBuilder.setPlaylistString(stream.content)

                return dataSource.getHlsMediaSourceFactory(hlsDataSourceFactoryBuilder)
                    .createMediaSource(MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(manifestUrlToUri(stream.manifestUrl))
                        .setCustomCacheKey(cacheKey)
                        .build())
            }

            @Throws(ResolverException::class)
            private fun buildSSMediaSource(dataSource: PlayerDataSource,
                                           stream: Stream,
                                           cacheKey: String,
                                           metadata: MediaItemTag
            ): SsMediaSource {
                if (stream.isUrl) {
                    throwResolverExceptionIfUrlNullOrEmpty(stream.content)
                    return dataSource.sSMediaSourceFactory.createMediaSource(
                        MediaItem.Builder()
                            .setTag(metadata)
                            .setUri(Uri.parse(stream.content))
                            .setCustomCacheKey(cacheKey)
                            .build())
                }

                val manifestUri = manifestUrlToUri(stream.manifestUrl)

                val smoothStreamingManifest: SsManifest
                try {
                    val smoothStreamingManifestInput = ByteArrayInputStream(
                        stream.content.toByteArray(StandardCharsets.UTF_8))
                    smoothStreamingManifest = SsManifestParser().parse(manifestUri,
                        smoothStreamingManifestInput)
                } catch (e: IOException) {
                    throw ResolverException("Error when parsing manual SS manifest", e)
                }

                return dataSource.sSMediaSourceFactory.createMediaSource(
                    smoothStreamingManifest,
                    MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(manifestUri)
                        .setCustomCacheKey(cacheKey)
                        .build())
            }


            //endregion
            //region YouTube media sources
            @Throws(ResolverException::class)
            private fun createYoutubeMediaSource(stream: Stream,
                                                 streamInfo: StreamInfo,
                                                 dataSource: PlayerDataSource,
                                                 cacheKey: String,
                                                 metadata: MediaItemTag
            ): MediaSource {
                if (!(stream is AudioStream || stream is VideoStream)) {
                    throw ResolverException("Generation of YouTube DASH manifest for " + stream.javaClass.simpleName + " is not supported")
                }

                val streamType = streamInfo.streamType
                when (streamType) {
                    StreamType.VIDEO_STREAM -> {
                        return createYoutubeMediaSourceOfVideoStreamType(dataSource, stream, streamInfo,
                            cacheKey, metadata)
                    }
                    StreamType.POST_LIVE_STREAM -> {
                        // If the content is not an URL, uses the DASH delivery method and if the stream type
                        // of the stream is a post live stream, it means that the content is an ended
                        // livestream so we need to generate the manifest corresponding to the content
                        // (which is the last segment of the stream)

                        try {
                            val itagItem = stream.itagItem ?: throw ResolverException("itagItem is null")
                            val manifestString = YoutubePostLiveStreamDvrDashManifestCreator
                                .fromPostLiveStreamDvrStreamingUrl(stream.content,
                                    itagItem,
                                    itagItem.targetDurationSec,
                                    streamInfo.duration)
                            return buildYoutubeManualDashMediaSource(dataSource,
                                createDashManifest(manifestString, stream), stream, cacheKey,
                                metadata)
                        } catch (e: CreationException) {
                            throw ResolverException(
                                "Error when generating the DASH manifest of YouTube ended live stream", e)
                        } catch (e: IOException) {
                            throw ResolverException(
                                "Error when generating the DASH manifest of YouTube ended live stream", e)
                        } catch (e: NullPointerException) {
                            throw ResolverException(
                                "Error when generating the DASH manifest of YouTube ended live stream", e)
                        }
                    }
                    else -> {
                        throw ResolverException(
                            "DASH manifest generation of YouTube livestreams is not supported")
                    }
                }
            }

            @Throws(ResolverException::class)
            private fun createYoutubeMediaSourceOfVideoStreamType(
                    dataSource: PlayerDataSource,
                    stream: Stream,
                    streamInfo: StreamInfo,
                    cacheKey: String,
                    metadata: MediaItemTag
            ): MediaSource {
                val deliveryMethod = stream.deliveryMethod
                when (deliveryMethod) {
                    DeliveryMethod.PROGRESSIVE_HTTP -> if ((stream is VideoStream && stream.isVideoOnly()) || stream is AudioStream) {
                        try {
                            if (stream.itagItem == null) throw ResolverException("stream.itagItem is null")
                            val manifestString = YoutubeProgressiveDashManifestCreator
                                .fromProgressiveStreamingUrl(stream.content,
                                    stream.itagItem!!,
                                    streamInfo.duration)
                            return buildYoutubeManualDashMediaSource(dataSource,
                                createDashManifest(manifestString, stream), stream, cacheKey,
                                metadata)
                        } catch (e: CreationException) {
                            Log.w(TAG, "Error when generating or parsing DASH manifest of "
                                    + "YouTube progressive stream, falling back to a "
                                    + "ProgressiveMediaSource.", e)
                            return buildYoutubeProgressiveMediaSource(dataSource, stream, cacheKey,
                                metadata)
                        } catch (e: IOException) {
                            Log.w(TAG, "Error when generating or parsing DASH manifest of "
                                    + "YouTube progressive stream, falling back to a "
                                    + "ProgressiveMediaSource.", e)
                            return buildYoutubeProgressiveMediaSource(dataSource, stream, cacheKey,
                                metadata)
                        } catch (e: NullPointerException) {
                            Log.w(TAG, "Error when generating or parsing DASH manifest of "
                                    + "YouTube progressive stream, falling back to a "
                                    + "ProgressiveMediaSource.", e)
                            return buildYoutubeProgressiveMediaSource(dataSource, stream, cacheKey,
                                metadata)
                        }
                    } else {
                        // Legacy progressive streams, subtitles are handled by
                        // VideoPlaybackResolver
                        return buildYoutubeProgressiveMediaSource(dataSource, stream, cacheKey,
                            metadata)
                    }
                    DeliveryMethod.DASH -> {
                        // If the content is not a URL, uses the DASH delivery method and if the stream
                        // type of the stream is a video stream, it means the content is an OTF stream
                        // so we need to generate the manifest corresponding to the content (which is
                        // the base URL of the OTF stream).
                        try {
                            if (stream.itagItem == null) throw ResolverException("stream.itagItem is null")
                            val manifestString = YoutubeOtfDashManifestCreator
                                .fromOtfStreamingUrl(stream.content,
                                    stream.itagItem!!,
                                    streamInfo.duration)
                            return buildYoutubeManualDashMediaSource(dataSource,
                                createDashManifest(manifestString, stream), stream, cacheKey,
                                metadata)
                        } catch (e: CreationException) {
                            Log.e(TAG,
                                "Error when generating the DASH manifest of YouTube OTF stream", e)
                            throw ResolverException(
                                "Error when generating the DASH manifest of YouTube OTF stream", e)
                        } catch (e: IOException) {
                            Log.e(TAG,
                                "Error when generating the DASH manifest of YouTube OTF stream", e)
                            throw ResolverException(
                                "Error when generating the DASH manifest of YouTube OTF stream", e)
                        } catch (e: NullPointerException) {
                            Log.e(TAG,
                                "Error when generating the DASH manifest of YouTube OTF stream", e)
                            throw ResolverException(
                                "Error when generating the DASH manifest of YouTube OTF stream", e)
                        }
                        return dataSource.youtubeHlsMediaSourceFactory.createMediaSource(
                            MediaItem.Builder()
                                .setTag(metadata)
                                .setUri(Uri.parse(stream.content))
                                .setCustomCacheKey(cacheKey)
                                .build())
                    }
                    DeliveryMethod.HLS -> return dataSource.youtubeHlsMediaSourceFactory.createMediaSource(
                        MediaItem.Builder()
                            .setTag(metadata)
                            .setUri(Uri.parse(stream.content))
                            .setCustomCacheKey(cacheKey)
                            .build())
                    else -> throw ResolverException("Unsupported delivery method for YouTube contents: "
                            + deliveryMethod)
                }
            }

            private fun buildYoutubeManualDashMediaSource(
                    dataSource: PlayerDataSource,
                    dashManifest: DashManifest,
                    stream: Stream,
                    cacheKey: String,
                    metadata: MediaItemTag
            ): DashMediaSource {
                return dataSource.youtubeDashMediaSourceFactory.createMediaSource(dashManifest,
                    MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(Uri.parse(stream.content))
                        .setCustomCacheKey(cacheKey)
                        .build())
            }

            private fun buildYoutubeProgressiveMediaSource(
                    dataSource: PlayerDataSource,
                    stream: Stream,
                    cacheKey: String,
                    metadata: MediaItemTag
            ): ProgressiveMediaSource {
                return dataSource.youtubeProgressiveMediaSourceFactory
                    .createMediaSource(MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(Uri.parse(stream.content))
                        .setCustomCacheKey(cacheKey)
                        .build())
            }


            //endregion
            //region Utils
            private fun manifestUrlToUri(manifestUrl: String?): Uri {
                return Uri.parse(Objects.requireNonNullElse(manifestUrl, ""))
            }

            @Throws(ResolverException::class)
            private fun throwResolverExceptionIfUrlNullOrEmpty(url: String?) {
                if (url == null) {
                    throw ResolverException("Null stream URL")
                } else if (url.isEmpty()) {
                    throw ResolverException("Empty stream URL")
                }
            }

            val TAG: String = PlaybackResolver::class.java.simpleName
        }
    }

    companion object {
        const val DEBUG: Boolean = MainActivity.DEBUG
        val TAG: String = PlayerManager::class.java.simpleName

        /*//////////////////////////////////////////////////////////////////////////
    // States
    ////////////////////////////////////////////////////////////////////////// */
        const val STATE_PREFLIGHT: Int = -1
        const val STATE_BLOCKED: Int = 123
        const val STATE_PLAYING: Int = 124
        const val STATE_BUFFERING: Int = 125
        const val STATE_PAUSED: Int = 126
        const val STATE_PAUSED_SEEK: Int = 127
        const val STATE_COMPLETED: Int = 128

        /*//////////////////////////////////////////////////////////////////////////
    // Intent
    ////////////////////////////////////////////////////////////////////////// */
        const val REPEAT_MODE: String = "repeat_mode"
        const val PLAYBACK_QUALITY: String = "playback_quality"
        const val PLAY_QUEUE_KEY: String = "play_queue_key"
        const val ENQUEUE: String = "enqueue"
        const val ENQUEUE_NEXT: String = "enqueue_next"
        const val RESUME_PLAYBACK: String = "resume_playback"
        const val PLAY_WHEN_READY: String = "play_when_ready"
        const val PLAYER_TYPE: String = "player_type"
        const val IS_MUTED: String = "is_muted"

        /*//////////////////////////////////////////////////////////////////////////
    // Time constants
    ////////////////////////////////////////////////////////////////////////// */
        const val PLAY_PREV_ACTIVATION_LIMIT_MILLIS: Int = 5000 // 5 seconds
        const val PROGRESS_LOOP_INTERVAL_MILLIS: Int = 1000 // 1 second

        /*//////////////////////////////////////////////////////////////////////////
    // Other constants
    ////////////////////////////////////////////////////////////////////////// */
        const val RENDERER_UNAVAILABLE: Int = -1
        private const val PICASSO_PLAYER_THUMBNAIL_TAG = "PICASSO_PLAYER_THUMBNAIL_TAG"
    }
}
