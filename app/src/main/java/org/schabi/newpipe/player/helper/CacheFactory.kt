package org.schabi.newpipe.player.helper

import android.content.Context
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.FileDataSource
import com.google.android.exoplayer2.upstream.TransferListener
import com.google.android.exoplayer2.upstream.cache.CacheDataSink
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.SimpleCache

internal class CacheFactory(private val context: Context,
                            private val transferListener: TransferListener,
                            private val cache: SimpleCache,
                            private val upstreamDataSourceFactory: DataSource.Factory
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        val dataSource = DefaultDataSource.Factory(context,
            upstreamDataSourceFactory)
            .setTransferListener(transferListener)
            .createDataSource()

        val fileSource = FileDataSource()
        val dataSink =
            CacheDataSink(cache, PlayerHelper.preferredCacheSize)
        return CacheDataSource(cache, dataSource, fileSource, dataSink, CACHE_FLAGS, null)
    }

    companion object {
        private const val CACHE_FLAGS = CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
    }
}
