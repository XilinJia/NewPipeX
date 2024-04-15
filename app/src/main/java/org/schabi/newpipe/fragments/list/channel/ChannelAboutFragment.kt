package org.schabi.newpipe.fragments.list.channel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import icepick.State
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.fragments.detail.BaseDescriptionFragment
import org.schabi.newpipe.util.DeviceUtils.dpToPx
import org.schabi.newpipe.util.Localization.localizeNumber

class ChannelAboutFragment : BaseDescriptionFragment() {
    @JvmField
    @State
    var channelInfo: ChannelInfo? = null

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)
        binding!!.constraintLayout.setPadding(0, dpToPx(8, requireContext()), 0, 0)
    }

    override val description: Description?
        get() {
            if (channelInfo == null) return null
            return Description(channelInfo!!.description, Description.PLAIN_TEXT)
        }

    override val service: StreamingService?
        get() {
            if (channelInfo == null) return null
            return channelInfo!!.service
        }

    override val serviceId: Int
        get() {
            if (channelInfo == null) return -1
            return channelInfo!!.serviceId
        }

    override val streamUrl: String?
        get() = null

    override val tags: List<String>?
        get() {
            if (channelInfo == null) return null
            return channelInfo!!.tags
        }

    override fun setupMetadata(inflater: LayoutInflater?,
                               layout: LinearLayout?) {
        // There is no upload date available for channels, so hide the relevant UI element
        binding!!.detailUploadDateView.visibility = View.GONE

        if (channelInfo == null) return

        val context = context
        if (channelInfo!!.subscriberCount != StreamExtractor.UNKNOWN_SUBSCRIBER_COUNT) {
            addMetadataItem(inflater, layout!!, false, R.string.metadata_subscribers,
                localizeNumber(context!!, channelInfo!!.subscriberCount))
        }

        addImagesMetadataItem(inflater, layout!!, R.string.metadata_avatars, channelInfo!!.avatars)
        addImagesMetadataItem(inflater, layout, R.string.metadata_banners, channelInfo!!.banners)
    }

    companion object {
        fun getInstance(channelInfo: ChannelInfo?): ChannelAboutFragment {
            val fragment = ChannelAboutFragment()
            fragment.channelInfo = channelInfo
            return fragment
        }
    }
}
