package org.schabi.newpipe.player.playback

import android.os.Handler
import android.util.Log
import androidx.collection.ArraySet
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.internal.subscriptions.EmptySubscription
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.PublishSubject
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.player.mediaitem.MediaItemTag
import org.schabi.newpipe.player.mediasource.FailedMediaSource
import org.schabi.newpipe.player.mediasource.FailedMediaSource.MediaSourceResolutionException
import org.schabi.newpipe.player.mediasource.FailedMediaSource.StreamInfoLoadException
import org.schabi.newpipe.player.mediasource.LoadedMediaSource
import org.schabi.newpipe.player.mediasource.ManagedMediaSource
import org.schabi.newpipe.player.mediasource.ManagedMediaSourcePlaylist
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.PlayQueueItem
import org.schabi.newpipe.player.playqueue.events.*
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.util.ServiceHelper.getCacheExpirationMillis
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

@UnstableApi
class MediaSourceManager private constructor(listener: PlaybackListener, playQueue: PlayQueue, loadDebounceMillis: Long,
                                             playbackNearEndGapMillis: Long, progressUpdateIntervalMillis: Long) {

    private val TAG = "MediaSourceManager@" + hashCode()

    private val playbackListener: PlaybackListener
    private val playQueue: PlayQueue

    /**
     * Determines the gap time between the playback position and the playback duration which
     * the [.getEdgeIntervalSignal] begins to request loading.
     *
     * @see .progressUpdateIntervalMillis
     */
    private val playbackNearEndGapMillis: Long

    /**
     * Determines the interval which the [.getEdgeIntervalSignal] waits for between
     * each request for loading, once [.playbackNearEndGapMillis] has reached.
     */
    private val progressUpdateIntervalMillis: Long

    private val nearEndIntervalSignal: Observable<Long>

    /**
     * Process only the last load order when receiving a stream of load orders (lessens I/O).
     *
     *
     * The higher it is, the less loading occurs during rapid noncritical timeline changes.
     *
     *
     *
     * Not recommended to go below 100ms.
     *
     *
     * @see .loadDebounced
     */
    private val loadDebounceMillis: Long

    private val debouncedLoader: Disposable
    private val debouncedSignal: PublishSubject<Long>

    private var playQueueReactor: Subscription

    private val loaderReactor: CompositeDisposable
    private val loadingItems: MutableSet<PlayQueueItem?>

    private val isBlocked: AtomicBoolean

    private var playlist: ManagedMediaSourcePlaylist

    private val removeMediaSourceHandler = Handler()

    constructor(listener: PlaybackListener, playQueue: PlayQueue)
            : this(listener, playQueue, 400L,
        TimeUnit.MILLISECONDS.convert(30, TimeUnit.SECONDS),
        TimeUnit.MILLISECONDS.convert(2, TimeUnit.SECONDS))

    init {
        requireNotNull(playQueue.broadcastReceiver) { "Play Queue has not been initialized." }
        require(playbackNearEndGapMillis >= progressUpdateIntervalMillis) {
            ("Playback end gap=[$playbackNearEndGapMillis ms] must be longer than update interval=[ $progressUpdateIntervalMillis ms] for them to be useful.")
        }

        this.playbackListener = listener
        this.playQueue = playQueue

        this.playbackNearEndGapMillis = playbackNearEndGapMillis
        this.progressUpdateIntervalMillis = progressUpdateIntervalMillis
        this.nearEndIntervalSignal = this.edgeIntervalSignal

        this.loadDebounceMillis = loadDebounceMillis
        this.debouncedSignal = PublishSubject.create()
        this.debouncedLoader = getDebouncedLoader()

        this.playQueueReactor = EmptySubscription.INSTANCE
        this.loaderReactor = CompositeDisposable()

        this.isBlocked = AtomicBoolean(false)

        this.playlist = ManagedMediaSourcePlaylist()

        this.loadingItems = Collections.synchronizedSet(ArraySet())

        playQueue.broadcastReceiver?.observeOn(AndroidSchedulers.mainThread())?.subscribe(reactor)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Exposed Methods
    ////////////////////////////////////////////////////////////////////////// */
    /**
     * Dispose the manager and releases all message buses and loaders.
     */
    fun dispose() {
        Logd(TAG, "close() called.")
        debouncedSignal.onComplete()
        debouncedLoader.dispose()

        playQueueReactor.cancel()
        loaderReactor.dispose()
    }

    private val reactor: Subscriber<PlayQueueEvent>
        /*//////////////////////////////////////////////////////////////////////////
    // Event Reactor
    ////////////////////////////////////////////////////////////////////////// */
        get() = object : Subscriber<PlayQueueEvent> {
            override fun onSubscribe(d: Subscription) {
                playQueueReactor.cancel()
                playQueueReactor = d
                playQueueReactor.request(1)
            }
            override fun onNext(playQueueMessage: PlayQueueEvent) {
                onPlayQueueChanged(playQueueMessage)
            }
            override fun onError(e: Throwable) {}
            override fun onComplete() {}
        }

    private fun onPlayQueueChanged(event: PlayQueueEvent) {
        if (playQueue.isEmpty && playQueue.isComplete) {
            playbackListener.onPlaybackShutdown()
            return
        }

        when (event.type()) {
            PlayQueueEventType.INIT, PlayQueueEventType.ERROR -> {
                maybeBlock()
                populateSources()
            }
            PlayQueueEventType.APPEND -> populateSources()
            PlayQueueEventType.SELECT -> maybeRenewCurrentIndex()
            PlayQueueEventType.REMOVE -> {
                val removeEvent = event as RemoveEvent
                playlist.remove(removeEvent.removeIndex)
            }
            PlayQueueEventType.MOVE -> {
                val moveEvent = event as MoveEvent
                playlist.move(moveEvent.fromIndex, moveEvent.toIndex)
            }
            PlayQueueEventType.REORDER -> {
                // Need to move to ensure the playing index from play queue matches that of
                // the source timeline, and then window correction can take care of the rest
                val reorderEvent = event as ReorderEvent
                playlist.move(reorderEvent.fromSelectedIndex, reorderEvent.toSelectedIndex)
            }
            PlayQueueEventType.RECOVERY -> {}
            else -> {}
        }
        when (event.type()) {
            PlayQueueEventType.INIT, PlayQueueEventType.REORDER, PlayQueueEventType.ERROR, PlayQueueEventType.SELECT -> loadImmediate() // low frequency, critical events
            PlayQueueEventType.APPEND, PlayQueueEventType.REMOVE, PlayQueueEventType.MOVE, PlayQueueEventType.RECOVERY -> loadDebounced() // high frequency or noncritical events
            else -> loadDebounced()
        }
        when (event.type()) {
            PlayQueueEventType.APPEND, PlayQueueEventType.REMOVE, PlayQueueEventType.MOVE, PlayQueueEventType.REORDER -> playbackListener.onPlayQueueEdited()
            else -> {}
        }
        if (!isPlayQueueReady) {
            maybeBlock()
            playQueue.fetch()
        }
        playQueueReactor.request(1)
    }

    private val isPlayQueueReady: Boolean
        /*//////////////////////////////////////////////////////////////////////////
    // Playback Locking
    ////////////////////////////////////////////////////////////////////////// */
        get() {
            val isWindowLoaded = playQueue.size() - playQueue.index > WINDOW_SIZE
            return playQueue.isComplete || isWindowLoaded
        }

    private val isPlaybackReady: Boolean
        get() {
            if (playlist.size() != playQueue.size()) return false

            val mediaSource = playlist[playQueue.index]
            val playQueueItem = playQueue.item
            if (mediaSource == null || playQueueItem == null) return false

            return mediaSource.isStreamEqual(playQueueItem)
        }

    private fun maybeBlock() {
        Logd(TAG, "maybeBlock() called.")
        if (isBlocked.get()) return
        playbackListener.onPlaybackBlock()
        resetSources()
        isBlocked.set(true)
    }

    private fun maybeUnblock(): Boolean {
        Logd(TAG, "maybeUnblock() called.")
        if (isBlocked.get()) {
            isBlocked.set(false)
            playbackListener.onPlaybackUnblock(playlist.parentMediaSource)
            return true
        }
        return false
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Metadata Synchronization
    ////////////////////////////////////////////////////////////////////////// */
    private fun maybeSync(wasBlocked: Boolean) {
        Logd(TAG, "maybeSync() called.")
        val currentItem = playQueue.item
        if (isBlocked.get() || currentItem == null) return
        playbackListener.onPlaybackSynchronize(currentItem, wasBlocked)
    }

    @Synchronized
    private fun maybeSynchronizePlayer() {
        if (isPlayQueueReady && isPlaybackReady) {
            val isBlockReleased = maybeUnblock()
            maybeSync(isBlockReleased)
        }
    }

    private val edgeIntervalSignal: Observable<Long>
        /*//////////////////////////////////////////////////////////////////////////
        // MediaSource Loading
        ////////////////////////////////////////////////////////////////////////// */
        get() = Observable.interval(progressUpdateIntervalMillis, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
            .filter { ignored: Long? -> playbackListener.isApproachingPlaybackEdge(playbackNearEndGapMillis) }

    private fun getDebouncedLoader(): Disposable {
        return debouncedSignal.mergeWith(nearEndIntervalSignal)
            .debounce(loadDebounceMillis, TimeUnit.MILLISECONDS)
            .subscribeOn(Schedulers.single())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { timestamp: Long? -> loadImmediate() }
    }

    private fun loadDebounced() {
        debouncedSignal.onNext(System.currentTimeMillis())
    }

    private fun loadImmediate() {
        Logd(TAG, "MediaSource - loadImmediate() called")
        val itemsToLoad = getItemsToLoad(playQueue) ?: return

        // Evict the previous items being loaded to free up memory, before start loading new ones
        maybeClearLoaders()

        maybeLoadItem(itemsToLoad.center)
        for (item in itemsToLoad.neighbors) {
            maybeLoadItem(item)
        }
    }

    private fun maybeLoadItem(item: PlayQueueItem) {
        Logd(TAG, "maybeLoadItem() called.")
        if (playQueue.indexOf(item) >= playlist.size()) return

        if (!loadingItems.contains(item) && isCorrectionNeeded(item)) {
            Logd(TAG, "MediaSource - Loading=[${item.title}] with url=[${item.url}]")
            loadingItems.add(item)
            val loader = getLoadedMediaSource(item)
                .observeOn(AndroidSchedulers.mainThread()) /* No exception handling since getLoadedMediaSource guarantees nonnull return */
                .subscribe { mediaSource: ManagedMediaSource -> onMediaSourceReceived(item, mediaSource) }
            loaderReactor.add(loader)
        }
    }

    private fun getLoadedMediaSource(stream: PlayQueueItem): Single<ManagedMediaSource> {
        return stream.stream
            .map { streamInfo: StreamInfo ->
                Optional.ofNullable(playbackListener.sourceOf(stream, streamInfo))
                    .flatMap<ManagedMediaSource> { source: MediaSource ->
                        MediaItemTag.from(source.mediaItem)
                            .map { tag: MediaItemTag? ->
                                val serviceId = streamInfo.serviceId
                                val expiration = (System.currentTimeMillis() + getCacheExpirationMillis(serviceId))
                                LoadedMediaSource(source, tag!!, stream, expiration)
                            }
                    }
                    .orElseGet {
                        val message = ("Unable to resolve source from stream info. URL: ${stream.url}, audio count: ${streamInfo.audioStreams.size}, video count: ${streamInfo.videoOnlyStreams.size}, ${streamInfo.videoStreams.size}")
                        FailedMediaSource.of(stream, MediaSourceResolutionException(message))
                    }
            }
            .onErrorReturn { throwable: Throwable? ->
                if (throwable is ExtractionException) return@onErrorReturn FailedMediaSource.of(stream, StreamInfoLoadException(throwable))

                // Non-source related error expected here (e.g. network),
                // should allow retry shortly after the error.
                val allowRetryIn = TimeUnit.MILLISECONDS.convert(3, TimeUnit.SECONDS)
                FailedMediaSource.of(stream, Exception(throwable), allowRetryIn)
            }
    }

    private fun onMediaSourceReceived(item: PlayQueueItem, mediaSource: ManagedMediaSource) {
        Logd(TAG, "MediaSource - Loaded=[${item.title}] with url=[${item.url}]")
        loadingItems.remove(item)

        val itemIndex = playQueue.indexOf(item)
        // Only update the playlist timeline for items at the current index or after.
        if (isCorrectionNeeded(item)) {
            Logd(TAG, "MediaSource - Updating index=[$itemIndex] with title=[${item.title}] at url=[${item.url}]")
            playlist.update(itemIndex, mediaSource, removeMediaSourceHandler) { this.maybeSynchronizePlayer() }
        }
    }

    /**
     * Checks if the corresponding MediaSource in
     * [com.google.android.exoplayer2.source.ConcatenatingMediaSource]
     * for a given [PlayQueueItem] needs replacement, either due to gapless playback
     * readiness or playlist desynchronization.
     *
     *
     * If the given [PlayQueueItem] is currently being played and is already loaded,
     * then correction is not only needed if the playlist is desynchronized. Otherwise, the
     * check depends on the status (e.g. expiration or placeholder) of the
     * [ManagedMediaSource].
     *
     *
     * @param item [PlayQueueItem] to check
     * @return whether a correction is needed
     */
    private fun isCorrectionNeeded(item: PlayQueueItem): Boolean {
        val index = playQueue.indexOf(item)
        val mediaSource = playlist[index]
        return mediaSource != null && mediaSource.shouldBeReplacedWith(item, index != playQueue.index)
    }

    /**
     * Checks if the current playing index contains an expired [ManagedMediaSource].
     * If so, the expired source is replaced by a dummy [ManagedMediaSource] and
     * [.loadImmediate] is called to reload the current item.
     * <br></br><br></br>
     * If not, then the media source at the current index is ready for playback, and
     * [.maybeSynchronizePlayer] is called.
     * <br></br><br></br>
     * Under both cases, [.maybeSync] will be called to ensure the listener
     * is up-to-date.
     */
    private fun maybeRenewCurrentIndex() {
        val currentIndex = playQueue.index
        val currentItem = playQueue.item
        val currentSource = playlist[currentIndex]
        if (currentItem == null || currentSource == null) return

        if (!currentSource.shouldBeReplacedWith(currentItem, true)) {
            maybeSynchronizePlayer()
            return
        }

        Logd(TAG, "MediaSource - Reloading currently playing, index=[$currentIndex], item=[${currentItem.title}]")
        playlist.invalidate(currentIndex, removeMediaSourceHandler) { this.loadImmediate() }
    }

    private fun maybeClearLoaders() {
        Logd(TAG, "MediaSource - maybeClearLoaders() called.")
        if (!loadingItems.contains(playQueue.item) && loaderReactor.size() > MAXIMUM_LOADER_SIZE) {
            loaderReactor.clear()
            loadingItems.clear()
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // MediaSource Playlist Helpers
    ////////////////////////////////////////////////////////////////////////// */
    private fun resetSources() {
        Logd(TAG, "resetSources() called.")
        playlist = ManagedMediaSourcePlaylist()
    }

    private fun populateSources() {
        Logd(TAG, "populateSources() called.")
        while (playlist.size() < playQueue.size()) {
            playlist.expand()
        }
    }

    private class ItemsToLoad(val center: PlayQueueItem, val neighbors: Collection<PlayQueueItem>)

    companion object {
        /**
         * Determines how many streams before and after the current stream should be loaded.
         * The default value (1) ensures seamless playback under typical network settings.
         *
         *
         * The streams after the current will be loaded into the playlist timeline while the
         * streams before will only be cached for future usage.
         *
         *
         * @see .onMediaSourceReceived
         */
        private const val WINDOW_SIZE = 1

        /**
         * Determines the maximum number of disposables allowed in the [.loaderReactor].
         * Once exceeded, new calls to [.loadImmediate] will evict all disposables in the
         * [.loaderReactor] in order to load a new set of items.
         *
         * @see .loadImmediate
         * @see .maybeLoadItem
         */
        private const val MAXIMUM_LOADER_SIZE = WINDOW_SIZE * 2 + 1

        /*//////////////////////////////////////////////////////////////////////////
    // Manager Helpers
    ////////////////////////////////////////////////////////////////////////// */
        private fun getItemsToLoad(playQueue: PlayQueue): ItemsToLoad? {
            // The current item has higher priority
            val currentIndex = playQueue.index
            val currentItem = playQueue.getItem(currentIndex) ?: return null

            // The rest are just for seamless playback
            // Although timeline is not updated prior to the current index, these sources are still
            // loaded into the cache for faster retrieval at a potentially later time.
            val leftBound = max(0.0, (currentIndex - WINDOW_SIZE).toDouble()).toInt()
            val rightLimit = currentIndex + WINDOW_SIZE + 1
            val rightBound = min(playQueue.size().toDouble(), rightLimit.toDouble()).toInt()
            val neighbors: MutableSet<PlayQueueItem> = ArraySet(playQueue.streams.subList(leftBound, rightBound))

            // Do a round robin
            val excess = rightLimit - playQueue.size()
            if (excess >= 0) neighbors.addAll(playQueue.streams.subList(0, min(playQueue.size().toDouble(), excess.toDouble()).toInt()))

            neighbors.remove(currentItem)

            return ItemsToLoad(currentItem, neighbors)
        }
    }
}
