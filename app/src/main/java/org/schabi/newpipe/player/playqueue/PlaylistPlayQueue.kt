package org.schabi.newpipe.player.playqueue

import androidx.media3.common.util.UnstableApi
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.util.ExtractorHelper.getMorePlaylistItems
import org.schabi.newpipe.util.ExtractorHelper.getPlaylistInfo

@UnstableApi class PlaylistPlayQueue : AbstractInfoPlayQueue<PlaylistInfo> {
    constructor(info: PlaylistInfo) : super(info)

    constructor(serviceId: Int, url: String?, nextPage: Page?, streams: List<StreamInfoItem>, index: Int) :
            super(serviceId, url!!, nextPage!!, streams, index)

    override val tag: String
        get() = "PlaylistPlayQueue@" + Integer.toHexString(hashCode())

    override fun fetch() {
        if (this.isInitial) {
            getPlaylistInfo(this.serviceId, this.baseUrl, false)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(headListObserver)
        } else {
            getMorePlaylistItems(this.serviceId, this.baseUrl, this.nextPage)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(nextPageObserver)
        }
    }
}
