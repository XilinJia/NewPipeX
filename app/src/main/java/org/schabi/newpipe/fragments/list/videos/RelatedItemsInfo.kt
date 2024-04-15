package org.schabi.newpipe.fragments.list.videos

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.stream.StreamInfo

class RelatedItemsInfo(info: StreamInfo)
    : ListInfo<InfoItem>(info.serviceId, ListLinkHandler(info.originalUrl, info.url, info.id, emptyList(), null), info.name) {
    /**
     * This class is used to wrap the related items of a StreamInfo into a ListInfo object.
     *
     * @param info the stream info from which to get related items
     */
    init {
        relatedItems = ArrayList(info.relatedItems)
    }
}
