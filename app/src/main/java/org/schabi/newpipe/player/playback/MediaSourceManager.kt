package org.schabi.newpipe.player.playback

import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.collection.ArraySet
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.*
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
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
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.mediaitem.MediaItemTag
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.PlayQueueItem
import org.schabi.newpipe.player.playqueue.events.*
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.util.NO_SERVICE_ID
import org.schabi.newpipe.util.ServiceHelper.getCacheExpirationMillis
import org.schabi.newpipe.util.image.ImageStrategy.choosePreferredImage
import java.io.IOException
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

    /*//////////////////////////////////////////////////////////////////////////
// Event Reactor
////////////////////////////////////////////////////////////////////////// */
    private val reactor: Subscriber<PlayQueueEvent>
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

    /*//////////////////////////////////////////////////////////////////////////
// Playback Locking
////////////////////////////////////////////////////////////////////////// */
    private val isPlayQueueReady: Boolean
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

    /*//////////////////////////////////////////////////////////////////////////
    // MediaSource Loading
    ////////////////////////////////////////////////////////////////////////// */
    private val edgeIntervalSignal: Observable<Long>
        get() = Observable.interval(progressUpdateIntervalMillis, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
            .filter { ignored: Long? -> playbackListener.isApproachingPlaybackEdge(playbackNearEndGapMillis) }


    constructor(listener: PlaybackListener, playQueue: PlayQueue) : this(listener, playQueue, 400L,
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
                        FailedMediaSource.of(stream, FailedMediaSource.MediaSourceResolutionException(message))
                    }
            }
            .onErrorReturn { throwable: Throwable? ->
                if (throwable is ExtractionException)
                    return@onErrorReturn FailedMediaSource.of(stream, FailedMediaSource.StreamInfoLoadException(throwable))

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

    interface ManagedMediaSource : MediaSource {
        /**
         * Determines whether or not this [ManagedMediaSource] can be replaced.
         *
         * @param newIdentity     a stream the [ManagedMediaSource] should encapsulate over, if
         * it is different from the existing stream in the
         * [ManagedMediaSource], then it should be replaced.
         * @param isInterruptable specifies if this [ManagedMediaSource] potentially
         * being played.
         * @return whether this could be replaces
         */
        fun shouldBeReplacedWith(newIdentity: PlayQueueItem, isInterruptable: Boolean): Boolean

        /**
         * Determines if the [PlayQueueItem] is the one the
         * [ManagedMediaSource] encapsulates over.
         *
         * @param stream play queue item to check
         * @return whether this source is for the specified stream
         */
        fun isStreamEqual(stream: PlayQueueItem): Boolean
    }

    @UnstableApi class FailedMediaSource(val stream: PlayQueueItem, val error: Exception, private val retryTimestamp: Long)
        : BaseMediaSource(), ManagedMediaSource {

        private val TAG = "FailedMediaSource@" + Integer.toHexString(hashCode())
        private val mediaItem = ExceptionTag.of(stream, listOf(error)).withExtras(this).asMediaItem()

        private fun canRetry(): Boolean {
            return System.currentTimeMillis() >= retryTimestamp
        }

        override fun getMediaItem(): MediaItem {
            return mediaItem
        }

        /**
         * Prepares the source with [Timeline] info on the silence playback when the error
         * is classed as [FailedMediaSourceException], for example, when the error is
         * [ExtractionException][org.schabi.newpipe.extractor.exceptions.ExtractionException].
         * These types of error are swallowed by [FailedMediaSource], and the underlying
         * exception is carried to the [MediaItem] metadata during playback.
         * <br></br><br></br>
         * If the exception is not known, e.g. [java.net.UnknownHostException] or some
         * other network issue, then no source info is refreshed and
         * [.maybeThrowSourceInfoRefreshError] be will triggered.
         * <br></br><br></br>
         * Note that this method is called only once until [.releaseSourceInternal] is called,
         * so if no action is done in here, playback will stall unless
         * [.maybeThrowSourceInfoRefreshError] is called.
         *
         * @param mediaTransferListener No data transfer listener needed, ignored here.
         */
        override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
            Log.e(TAG, "Loading failed source: ", error)
            if (error is FailedMediaSourceException) {
                if (mediaItem != null) refreshSourceInfo(makeSilentMediaTimeline(SILENCE_DURATION_US, mediaItem))
            }
        }

        /**
         * If the error is not known, e.g. network issue, then the exception is not swallowed here in
         * [FailedMediaSource]. The exception is then propagated to the player, which
         * [Player][org.schabi.newpipe.player.PlayerManager] can react to inside
         * [androidx.media3.common.Player.Listener.onPlayerError].
         *
         * @throws IOException An error which will always result in
         * [androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED].
         */
        @Throws(IOException::class)
        override fun maybeThrowSourceInfoRefreshError() {
            if (error !is FailedMediaSourceException) throw IOException(error)
        }

        /**
         * This method is only called if [.prepareSourceInternal]
         * refreshes the source info with no exception. All parameters are ignored as this
         * returns a static and reused piece of silent audio.
         *
         * @param id                The identifier of the period.
         * @param allocator         An [Allocator] from which to obtain media buffer allocations.
         * @param startPositionUs   The expected start position, in microseconds.
         * @return The common [MediaPeriod] holding the silence.
         */
        override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod {
            return SILENT_MEDIA
        }

        override fun releasePeriod(mediaPeriod: MediaPeriod) {
            /* Do Nothing (we want to keep re-using the Silent MediaPeriod) */
        }

        override fun releaseSourceInternal() {
            /* Do Nothing, no clean-up for processing/extra thread is needed by this MediaSource */
        }

        override fun shouldBeReplacedWith(newIdentity: PlayQueueItem, isInterruptable: Boolean): Boolean {
            return newIdentity != stream || canRetry()
        }

        override fun isStreamEqual(stream: PlayQueueItem): Boolean {
            return this.stream == stream
        }

        open class FailedMediaSourceException : Exception {
            internal constructor(message: String?) : super(message)
            internal constructor(cause: Throwable?) : super(cause)
        }

        class MediaSourceResolutionException(message: String?) : FailedMediaSourceException(message)

        class StreamInfoLoadException(cause: Throwable?) : FailedMediaSourceException(cause)

        /**
         * This [MediaItemTag] object is designed to contain metadata for a stream
         * that has failed to load. It supplies metadata from an underlying
         * [PlayQueueItem], which is used by the internal players to resolve actual
         * playback info.
         *
         * This [MediaItemTag] does not contain any [StreamInfo] that can be
         * used to start playback and can be detected by checking [ExceptionTag.getErrors]
         * when in generic form.
         */
        class ExceptionTag private constructor(private val item: PlayQueueItem,
                                               override val errors: List<Exception>,
                                               private val extras: Any?) : MediaItemTag {
            override val serviceId: Int
                get() = item.serviceId

            override val title: String
                get() = item.title

            override val uploaderName: String
                get() = item.uploader

            override val durationSeconds: Long
                get() = item.duration

            override val streamUrl: String
                get() = item.url

            override val thumbnailUrl: String?
                get() = choosePreferredImage(item.thumbnails)

            override val uploaderUrl: String
                get() = item.uploaderUrl

            override val streamType: StreamType
                get() = item.streamType

            override fun <T> getMaybeExtras(type: Class<T>): Optional<T>? {
                return Optional.ofNullable(extras).map { obj: Any? -> type.cast(obj) }
            }

            override fun <T> withExtras(extra: T): MediaItemTag {
                return ExceptionTag(item, errors, extra)
            }

            companion object {
                fun of(playQueueItem: PlayQueueItem, errors: List<Exception>): ExceptionTag {
                    return ExceptionTag(playQueueItem, errors, null)
                }
            }
        }

        companion object {
            /**
             * Play 2 seconds of silenced audio when a stream fails to resolve due to a known issue,
             * such as [org.schabi.newpipe.extractor.exceptions.ExtractionException].
             *
             * This silence duration allows user to react and have time to jump to a previous stream,
             * while still provide a smooth playback experience. A duration lower than 1 second is
             * not recommended, it may cause ExoPlayer to buffer for a while.
             */
            val SILENCE_DURATION_US: Long = TimeUnit.SECONDS.toMicros(2)
            val SILENT_MEDIA: MediaPeriod = makeSilentMediaPeriod(SILENCE_DURATION_US)

            fun of(playQueueItem: PlayQueueItem, error: FailedMediaSourceException): FailedMediaSource {
                return FailedMediaSource(playQueueItem, error, Long.MAX_VALUE)
            }

            fun of(playQueueItem: PlayQueueItem, error: Exception, retryWaitMillis: Long): FailedMediaSource {
                return FailedMediaSource(playQueueItem, error, System.currentTimeMillis() + retryWaitMillis)
            }

            private fun makeSilentMediaTimeline(durationUs: Long, mediaItem: MediaItem): Timeline {
                return SinglePeriodTimeline(durationUs, true, false, false, null, mediaItem)
            }

            private fun makeSilentMediaPeriod(durationUs: Long): MediaPeriod {
                val mediaSource = SilenceMediaSource.Factory()
                    .setDurationUs(durationUs)
                    .createMediaSource()
                val mediaPeriodId = MediaSource.MediaPeriodId(0)
                val allocator = DefaultAllocator(false, 0)
                return mediaSource.createPeriod(mediaPeriodId, allocator, 0)
//            return SilenceMediaSource.Factory()
//                .setDurationUs(durationUs)
//                .createMediaSource()
//                .createPeriod(null, null, 0)
            }
        }
    }

    @OptIn(UnstableApi::class)
    class LoadedMediaSource(source: MediaSource, tag: MediaItemTag, val stream: PlayQueueItem, private val expireTimestamp: Long)
        : WrappingMediaSource(source), ManagedMediaSource {

        private val mediaItem = tag.withExtras(this)!!.asMediaItem()

        private val isExpired: Boolean
            get() = System.currentTimeMillis() >= expireTimestamp

        override fun getMediaItem(): MediaItem {
            return mediaItem
        }

        override fun shouldBeReplacedWith(newIdentity: PlayQueueItem, isInterruptable: Boolean): Boolean {
            return newIdentity != stream || (isInterruptable && isExpired)
        }

        override fun isStreamEqual(otherStream: PlayQueueItem): Boolean {
            return this.stream == otherStream
        }
    }

    @UnstableApi class ManagedMediaSourcePlaylist {
        /*isPlaylistAtomic=*/
        val parentMediaSource: ConcatenatingMediaSource = ConcatenatingMediaSource( false, ShuffleOrder.UnshuffledShuffleOrder(0))

        /*//////////////////////////////////////////////////////////////////////////
        // MediaSource Delegations
        ////////////////////////////////////////////////////////////////////////// */
        fun size(): Int {
            return parentMediaSource.size
        }

        /**
         * Returns the [ManagedMediaSource] at the given index of the playlist.
         * If the index is invalid, then null is returned.
         *
         * @param index index of [ManagedMediaSource] to get from the playlist
         * @return the [ManagedMediaSource] at the given index of the playlist
         */
        operator fun get(index: Int): ManagedMediaSource? {
            if (index < 0 || index >= size()) return null

            return MediaItemTag
                .from(parentMediaSource.getMediaSource(index).mediaItem)
                .flatMap { tag -> tag.getMaybeExtras(ManagedMediaSource::class.java) }
                .orElse(null)
        }

        /*//////////////////////////////////////////////////////////////////////////
        // Playlist Manipulation
        ////////////////////////////////////////////////////////////////////////// */
        /**
         * Expands the [ConcatenatingMediaSource] by appending it with a
         * [PlaceholderMediaSource].
         *
         * @see .append
         */
        @Synchronized
        fun expand() {
            append(PlaceholderMediaSource.COPY)
        }

        /**
         * Appends a [ManagedMediaSource] to the end of [ConcatenatingMediaSource].
         *
         * @see ConcatenatingMediaSource.addMediaSource
         *
         * @param source [ManagedMediaSource] to append
         */
        @Synchronized
        fun append(source: ManagedMediaSource) {
            parentMediaSource.addMediaSource(source)
        }

        /**
         * Removes a [ManagedMediaSource] from [ConcatenatingMediaSource]
         * at the given index. If this index is out of bound, then the removal is ignored.
         *
         * @see ConcatenatingMediaSource.removeMediaSource
         * @param index of [ManagedMediaSource] to be removed
         */
        @Synchronized
        fun remove(index: Int) {
            if (index < 0 || index > parentMediaSource.size) return

            parentMediaSource.removeMediaSource(index)
        }

        /**
         * Moves a [ManagedMediaSource] in [ConcatenatingMediaSource]
         * from the given source index to the target index. If either index is out of bound,
         * then the call is ignored.
         *
         * @see ConcatenatingMediaSource.moveMediaSource
         * @param source original index of [ManagedMediaSource]
         * @param target new index of [ManagedMediaSource]
         */
        @Synchronized
        fun move(source: Int, target: Int) {
            if (source < 0 || target < 0) return
            if (source >= parentMediaSource.size || target >= parentMediaSource.size) return

            parentMediaSource.moveMediaSource(source, target)
        }

        /**
         * Invalidates the [ManagedMediaSource] at the given index by replacing it
         * with a [PlaceholderMediaSource].
         *
         * @see .update
         * @param index            index of [ManagedMediaSource] to invalidate
         * @param handler          the [Handler] to run `finalizingAction`
         * @param finalizingAction a [Runnable] which is executed immediately
         * after the media source has been removed from the playlist
         */
        @Synchronized
        fun invalidate(index: Int, handler: Handler?, finalizingAction: Runnable?) {
            if (get(index) === PlaceholderMediaSource.COPY) return
            update(index, PlaceholderMediaSource.COPY, handler, finalizingAction)
        }

        /**
         * Updates the [ManagedMediaSource] in [ConcatenatingMediaSource]
         * at the given index with a given [ManagedMediaSource].
         *
         * @see .update
         * @param index  index of [ManagedMediaSource] to update
         * @param source new [ManagedMediaSource] to use
         */
        @Synchronized
        fun update(index: Int, source: ManagedMediaSource) {
            update(index, source, null,  /*doNothing=*/null)
        }

        /**
         * Updates the [ManagedMediaSource] in [ConcatenatingMediaSource]
         * at the given index with a given [ManagedMediaSource]. If the index is out of bound,
         * then the replacement is ignored.
         *
         * @see ConcatenatingMediaSource.addMediaSource
         *
         * @see ConcatenatingMediaSource.removeMediaSource
         * @param index            index of [ManagedMediaSource] to update
         * @param source           new [ManagedMediaSource] to use
         * @param handler          the [Handler] to run `finalizingAction`
         * @param finalizingAction a [Runnable] which is executed immediately
         * after the media source has been removed from the playlist
         */
        @Synchronized
        fun update(index: Int, source: ManagedMediaSource, handler: Handler?, finalizingAction: Runnable?) {
            if (index < 0 || index >= parentMediaSource.size) return

            // Add and remove are sequential on the same thread, therefore here, the exoplayer
            // message queue must receive and process add before remove, effectively treating them
            // as atomic.

            // Since the finalizing action occurs strictly after the timeline has completed
            // all its changes on the playback thread, thus, it is possible, in the meantime,
            // other calls that modifies the playlist media source occur in between. This makes
            // it unsafe to call remove as the finalizing action of add.
            parentMediaSource.addMediaSource(index + 1, source)

            // Because of the above race condition, it is thus only safe to synchronize the player
            // in the finalizing action AFTER the removal is complete and the timeline has changed.
            parentMediaSource.removeMediaSource(index, handler!!, finalizingAction!!)
        }

        @UnstableApi internal class PlaceholderMediaSource private constructor() : CompositeMediaSource<Void?>(), ManagedMediaSource {
            override fun getMediaItem(): MediaItem {
                return MEDIA_ITEM
            }

            override fun onChildSourceInfoRefreshed(id: Void?, mediaSource: MediaSource, timeline: Timeline) {
                /* Do nothing, no timeline updates or error will stall playback */
            }

            override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod {
                return PlaceholderMediaSource() as MediaPeriod
            }

            override fun releasePeriod(mediaPeriod: MediaPeriod) {}

            override fun shouldBeReplacedWith(newIdentity: PlayQueueItem, isInterruptable: Boolean): Boolean {
                return true
            }

            override fun isStreamEqual(stream: PlayQueueItem): Boolean {
                return false
            }

            /**
             * This is a Placeholding [MediaItemTag], designed as a dummy metadata object for
             * any stream that has not been resolved.
             *
             * This object cannot be instantiated and does not hold real metadata of any form.
             */
            class PlaceholderTag private constructor(private val extras: Any?) : MediaItemTag {
                override val errors: List<Exception>
                    get() = emptyList()

                override val serviceId: Int
                    get() = NO_SERVICE_ID

                override val title: String
                    get() = UNKNOWN_VALUE_INTERNAL

                override val uploaderName: String
                    get() = UNKNOWN_VALUE_INTERNAL

                override val streamUrl: String
                    get() = UNKNOWN_VALUE_INTERNAL

                override val thumbnailUrl: String
                    get() = UNKNOWN_VALUE_INTERNAL

                override val durationSeconds: Long
                    get() = 0

                override val streamType: StreamType
                    get() = StreamType.NONE

                override val uploaderUrl: String
                    get() = PlaceholderTag.UNKNOWN_VALUE_INTERNAL

                override fun <T> getMaybeExtras(type: Class<T>): Optional<T>? {
                    return Optional.ofNullable(extras).map { obj: Any? -> type.cast(obj) }
                }

                override fun <T> withExtras(extra: T): MediaItemTag? {
                    return PlaceholderTag(extra)
                }

                companion object {
                    @JvmField
                    val EMPTY: PlaceholderTag = PlaceholderTag(null)
                    private val UNKNOWN_VALUE_INTERNAL: String = "Placeholder"
                }
            }

            companion object {
                val COPY: PlaceholderMediaSource = PlaceholderMediaSource()
                private val MEDIA_ITEM = PlaceholderTag.EMPTY.withExtras(COPY)!!.asMediaItem()
            }
        }
    }

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
