package org.schabi.newpipe.player.datasource

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import org.schabi.newpipe.extractor.utils.Utils
import java.nio.charset.StandardCharsets

/**
 * A [HlsDataSourceFactory] which allows playback of non-URI media HLS playlists for
 * [HlsMediaSource][com.google.android.exoplayer2.source.hls.HlsMediaSource]s.
 *
 *
 *
 * If media requests are relative, the URI from which the manifest comes from (either the
 * manifest URI (preferred) or the master URI (if applicable)) must be returned, otherwise the
 * content will be not playable, as it will be an invalid URL, or it may be treat as something
 * unexpected, for instance as a file for
 * [DefaultDataSource][com.google.android.exoplayer2.upstream.DefaultDataSource]s.
 *
 *
 *
 *
 * See [.createDataSource] for changes and implementation details.
 *
 */
@UnstableApi class NonUriHlsDataSourceFactory
/**
 * Create a [NonUriHlsDataSourceFactory] instance.
 *
 * @param dataSourceFactory       the [DataSource.Factory] which will be used to build
 * non manifests [DataSource]s, which must not be null
 * @param playlistStringByteArray a byte array of the HLS playlist, which must not be null
 */ private constructor(private val dataSourceFactory: DataSource.Factory,
                        private val playlistStringByteArray: ByteArray
) : HlsDataSourceFactory {
    /**
     * Builder class of [NonUriHlsDataSourceFactory] instances.
     */
    class Builder {
        private var dataSourceFactory: DataSource.Factory? = null
        private var playlistString: String? = null

        /**
         * Set the [DataSource.Factory] which will be used to create non manifest contents
         * [DataSource]s.
         *
         * @param dataSourceFactoryForNonManifestContents the [DataSource.Factory] which will
         * be used to create non manifest contents
         * [DataSource]s, which cannot be null
         */
        fun setDataSourceFactory(
                dataSourceFactoryForNonManifestContents: DataSource.Factory
        ) {
            this.dataSourceFactory = dataSourceFactoryForNonManifestContents
        }

        /**
         * Set the HLS playlist which will be used for manifests requests.
         *
         * @param hlsPlaylistString the string which correspond to the response of the HLS
         * manifest, which cannot be null or empty
         */
        fun setPlaylistString(hlsPlaylistString: String) {
            this.playlistString = hlsPlaylistString
        }

        /**
         * Create a new [NonUriHlsDataSourceFactory] with the given data source factory and
         * the given HLS playlist.
         *
         * @return a [NonUriHlsDataSourceFactory]
         * @throws IllegalArgumentException if the data source factory is null or if the HLS
         * playlist string set is null or empty
         */
        fun build(): NonUriHlsDataSourceFactory {
            requireNotNull(dataSourceFactory) { "No DataSource.Factory valid instance has been specified." }

            require(!Utils.isNullOrEmpty(playlistString)) { "No HLS valid playlist has been specified." }

            return NonUriHlsDataSourceFactory(dataSourceFactory!!,
                playlistString!!.toByteArray(StandardCharsets.UTF_8))
        }
    }

    /**
     * Create a [DataSource] for the given data type.
     *
     *
     *
     * Contrary to [ ExoPlayer&#39;s default implementation][com.google.android.exoplayer2.source.hls.DefaultHlsDataSourceFactory], this implementation is not always using the
     * [DataSource.Factory] passed to the
     * [ HlsMediaSource.Factory][com.google.android.exoplayer2.source.hls.HlsMediaSource.Factory] constructor, only when it's not
     * [the manifest type][C.DATA_TYPE_MANIFEST].
     *
     *
     *
     *
     * This change allow playback of non-URI HLS contents, when the manifest is not a master
     * manifest/playlist (otherwise, endless loops should be encountered because the
     * [DataSource]s created for media playlists should use the master playlist response
     * instead).
     *
     *
     * @param dataType the data type for which the [DataSource] will be used, which is one of
     * [C] `.DATA_TYPE_*` constants
     * @return a [DataSource] for the given data type
     */
    override fun createDataSource(dataType: Int): DataSource {
        // The manifest is already downloaded and provided with playlistStringByteArray, so we
        // don't need to download it again and we can use a ByteArrayDataSource instead
        if (dataType == C.DATA_TYPE_MANIFEST) {
            return ByteArrayDataSource(playlistStringByteArray)
        }

        return dataSourceFactory.createDataSource()
    }
}
