package org.schabi.newpipe.util.text

import android.content.Context
import android.view.View
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.util.external_communication.ShareUtils
import org.schabi.newpipe.util.text.InternalUrlsHandler.playOnPopup
import org.schabi.newpipe.util.text.TimestampExtractor.TimestampMatchDTO

internal class TimestampLongPressClickableSpan(private val context: Context,
                                               private val descriptionText: String,
                                               private val disposables: CompositeDisposable,
                                               private val relatedInfoService: StreamingService,
                                               private val relatedStreamUrl: String,
                                               private val timestampMatchDTO: TimestampMatchDTO
) : LongPressClickableSpan() {
    override fun onClick(view: View) {
        playOnPopup(context, relatedStreamUrl, relatedInfoService,
            timestampMatchDTO.seconds(), disposables)
    }

    override fun onLongClick(view: View) {
        ShareUtils.copyToClipboard(context, getTimestampTextToCopy(
            relatedInfoService, relatedStreamUrl, descriptionText, timestampMatchDTO))
    }

    companion object {
        private fun getTimestampTextToCopy(
                relatedInfoService: StreamingService,
                relatedStreamUrl: String,
                descriptionText: String,
                timestampMatchDTO: TimestampMatchDTO
        ): String {
            // TODO: use extractor methods to get timestamps when this feature will be implemented in it
            if (relatedInfoService === ServiceList.YouTube) {
                return relatedStreamUrl + "&t=" + timestampMatchDTO.seconds()
            } else if (relatedInfoService === ServiceList.SoundCloud
                    || relatedInfoService === ServiceList.MediaCCC) {
                return relatedStreamUrl + "#t=" + timestampMatchDTO.seconds()
            } else if (relatedInfoService === ServiceList.PeerTube) {
                return relatedStreamUrl + "?start=" + timestampMatchDTO.seconds()
            }

            // Return timestamp text for other services
            return descriptionText.subSequence(timestampMatchDTO.timestampStart(),
                timestampMatchDTO.timestampEnd()).toString()
        }
    }
}
