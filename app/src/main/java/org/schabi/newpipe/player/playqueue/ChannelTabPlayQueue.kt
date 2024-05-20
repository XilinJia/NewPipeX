package org.schabi.newpipe.player.playqueue

import androidx.media3.common.util.UnstableApi
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.util.ExtractorHelper.getChannelTab
import org.schabi.newpipe.util.ExtractorHelper.getMoreChannelTabItems


@UnstableApi class ChannelTabPlayQueue @JvmOverloads constructor(serviceId: Int,
                                                                 val linkHandler: ListLinkHandler,
                                                                 nextPage: Page? = null,
                                                                 streams: List<StreamInfoItem> = emptyList(),
                                                                 index: Int = 0)
    : AbstractInfoPlayQueue<ChannelTabInfo>(serviceId, linkHandler.url, nextPage!!, streams, index) {

    override val tag: String
        get() = "ChannelTabPlayQueue@" + Integer.toHexString(hashCode())

    override fun fetch() {
        if (isInitial) {
            getChannelTab(this.serviceId, this.linkHandler, false)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(headListObserver)
        } else {
            getMoreChannelTabItems(this.serviceId, this.linkHandler, this.nextPage)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(nextPageObserver)
        }
    }
}
