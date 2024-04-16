package org.schabi.newpipe.player.helper

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.smoothstreaming.DefaultSsChunkSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.player.datasource.NonUriHlsDataSourceFactory
import org.schabi.newpipe.player.datasource.YoutubeHttpDataSource
import org.schabi.newpipe.player.helper.PlayerHelper.getProgressiveLoadIntervalBytes
import org.schabi.newpipe.player.helper.PlayerHelper.preferredCacheSize
import java.io.File

@UnstableApi class PlayerDataSource(context: Context, transferListener: TransferListener?) {
    private val progressiveLoadIntervalBytes = getProgressiveLoadIntervalBytes(context)

    // Generic Data Source Factories (without or with cache)
    private val cachelessDataSourceFactory: DataSource.Factory
    private val cacheDataSourceFactory: CacheFactory

    // YouTube-specific Data Source Factories (with cache)
    // They use YoutubeHttpDataSource.Factory, with different parameters each
    private val ytHlsCacheDataSourceFactory: CacheFactory
    private val ytDashCacheDataSourceFactory: CacheFactory
    private val ytProgressiveDashCacheDataSourceFactory: CacheFactory


    init {
        // make sure the static cache was created: needed by CacheFactories below
        instantiateCacheIfNeeded(context)

        // generic data source factories use DefaultHttpDataSource.Factory
        cachelessDataSourceFactory = DefaultDataSource.Factory(context,
            DefaultHttpDataSource.Factory().setUserAgent(DownloaderImpl.USER_AGENT))
            .setTransferListener(transferListener)
        cacheDataSourceFactory = CacheFactory(context, transferListener!!, cache!!,
            DefaultHttpDataSource.Factory().setUserAgent(DownloaderImpl.USER_AGENT))

        // YouTube-specific data source factories use getYoutubeHttpDataSourceFactory()
        ytHlsCacheDataSourceFactory = CacheFactory(context, transferListener, cache!!,
            getYoutubeHttpDataSourceFactory(false, false))
        ytDashCacheDataSourceFactory = CacheFactory(context, transferListener, cache!!,
            getYoutubeHttpDataSourceFactory(true, true))
        ytProgressiveDashCacheDataSourceFactory = CacheFactory(context, transferListener, cache!!,
            getYoutubeHttpDataSourceFactory(false, true))

        // set the maximum size to manifest creators
        YoutubeProgressiveDashManifestCreator.getCache().setMaximumSize(MAX_MANIFEST_CACHE_SIZE)
        YoutubeOtfDashManifestCreator.getCache().setMaximumSize(MAX_MANIFEST_CACHE_SIZE)
        YoutubePostLiveStreamDvrDashManifestCreator.getCache().setMaximumSize(
            MAX_MANIFEST_CACHE_SIZE)
    }


    val liveSsMediaSourceFactory: SsMediaSource.Factory
        //region Live media source factories
        get() = sSMediaSourceFactory.setLivePresentationDelayMs(LIVE_STREAM_EDGE_GAP_MILLIS.toLong())

    val liveHlsMediaSourceFactory: HlsMediaSource.Factory
        get() = HlsMediaSource.Factory(cachelessDataSourceFactory)
            .setAllowChunklessPreparation(true)
            .setPlaylistTrackerFactory { dataSourceFactory: HlsDataSourceFactory?, loadErrorHandlingPolicy: LoadErrorHandlingPolicy?, playlistParserFactory: HlsPlaylistParserFactory? ->
                DefaultHlsPlaylistTracker(
                    dataSourceFactory!!, loadErrorHandlingPolicy!!,
                    playlistParserFactory!!,
                    PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT)
            }

    val liveDashMediaSourceFactory: DashMediaSource.Factory
        get() = DashMediaSource.Factory(
            getDefaultDashChunkSourceFactory(cachelessDataSourceFactory),
            cachelessDataSourceFactory)


    //endregion
    //region Generic media source factories
    fun getHlsMediaSourceFactory(
            hlsDataSourceFactoryBuilder: NonUriHlsDataSourceFactory.Builder?
    ): HlsMediaSource.Factory {
        if (hlsDataSourceFactoryBuilder != null) {
            hlsDataSourceFactoryBuilder.setDataSourceFactory(cacheDataSourceFactory)
            return HlsMediaSource.Factory(hlsDataSourceFactoryBuilder.build())
        }

        return HlsMediaSource.Factory(cacheDataSourceFactory)
    }

    val dashMediaSourceFactory: DashMediaSource.Factory
        get() = DashMediaSource.Factory(
            getDefaultDashChunkSourceFactory(cacheDataSourceFactory),
            cacheDataSourceFactory)

    val progressiveMediaSourceFactory: ProgressiveMediaSource.Factory
        get() = ProgressiveMediaSource.Factory(cacheDataSourceFactory)
            .setContinueLoadingCheckIntervalBytes(progressiveLoadIntervalBytes)

    val sSMediaSourceFactory: SsMediaSource.Factory
        get() = SsMediaSource.Factory(
            DefaultSsChunkSource.Factory(cachelessDataSourceFactory),
            cachelessDataSourceFactory)

    val singleSampleMediaSourceFactory: SingleSampleMediaSource.Factory
        get() = SingleSampleMediaSource.Factory(cacheDataSourceFactory)


    val youtubeHlsMediaSourceFactory: HlsMediaSource.Factory
        //endregion
        get() = HlsMediaSource.Factory(ytHlsCacheDataSourceFactory)

    val youtubeDashMediaSourceFactory: DashMediaSource.Factory
        get() = DashMediaSource.Factory(
            getDefaultDashChunkSourceFactory(ytDashCacheDataSourceFactory),
            ytDashCacheDataSourceFactory)

    val youtubeProgressiveMediaSourceFactory: ProgressiveMediaSource.Factory
        get() = ProgressiveMediaSource.Factory(ytProgressiveDashCacheDataSourceFactory)
            .setContinueLoadingCheckIntervalBytes(progressiveLoadIntervalBytes)


    @UnstableApi companion object {
        val TAG: String = PlayerDataSource::class.java.simpleName

        const val LIVE_STREAM_EDGE_GAP_MILLIS: Int = 10000

        /**
         * An approximately 4.3 times greater value than the
         * [default][DefaultHlsPlaylistTracker.DEFAULT_PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT]
         * to ensure that (very) low latency livestreams which got stuck for a moment don't crash too
         * early.
         */
        private const val PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT = 15.0

        /**
         * The maximum number of generated manifests per cache, in
         * [YoutubeProgressiveDashManifestCreator], [YoutubeOtfDashManifestCreator] and
         * [YoutubePostLiveStreamDvrDashManifestCreator].
         */
        private const val MAX_MANIFEST_CACHE_SIZE = 500

        /**
         * The folder name in which the ExoPlayer cache will be written.
         */
        private const val CACHE_FOLDER_NAME = "exoplayer"

        /**
         * The [SimpleCache] instance which will be used to build
         * [com.google.android.exoplayer2.upstream.cache.CacheDataSource]s instances (with
         * [CacheFactory]).
         */
        private var cache: SimpleCache? = null


        //endregion
        //region Static methods
        private fun getDefaultDashChunkSourceFactory(
                dataSourceFactory: DataSource.Factory
        ): DefaultDashChunkSource.Factory {
            return DefaultDashChunkSource.Factory(dataSourceFactory)
        }

        private fun getYoutubeHttpDataSourceFactory(
                rangeParameterEnabled: Boolean,
                rnParameterEnabled: Boolean
        ): YoutubeHttpDataSource.Factory {
            return YoutubeHttpDataSource.Factory()
                .setRangeParameterEnabled(rangeParameterEnabled)
                .setRnParameterEnabled(rnParameterEnabled)
        }

        private fun instantiateCacheIfNeeded(context: Context) {
            if (cache == null) {
                val cacheDir = File(context.externalCacheDir, CACHE_FOLDER_NAME)
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "instantiateCacheIfNeeded: cacheDir = " + cacheDir.absolutePath)
                }
                if (!cacheDir.exists() && !cacheDir.mkdir()) {
                    Log.w(TAG, "instantiateCacheIfNeeded: could not create cache dir")
                }

                val evictor = LeastRecentlyUsedCacheEvictor(preferredCacheSize)
                cache = SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(context))
            }
        } //endregion
    }
}
