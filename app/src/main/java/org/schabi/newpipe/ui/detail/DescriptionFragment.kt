package org.schabi.newpipe.ui.detail

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.StringRes
import icepick.State
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamExtractor.Privacy
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.util.Localization.getAppLocale
import org.schabi.newpipe.util.Localization.localizeUploadDate

class DescriptionFragment : BaseDescriptionFragment {
    @JvmField
    @State
    var streamInfo: StreamInfo? = null

    constructor()

    constructor(streamInfo: StreamInfo?) {
        this.streamInfo = streamInfo
    }

    override val description: Description?
        get() {
            if (streamInfo == null) return null
            return streamInfo!!.description
        }

    override val service: StreamingService?
        get() {
            if (streamInfo == null) return null
            return streamInfo!!.service
        }

    override val serviceId: Int
        get() {
            if (streamInfo == null) return -1
            return streamInfo!!.serviceId
        }

    override val streamUrl: String?
        get() {
            if (streamInfo == null) return null
            return streamInfo!!.url
        }

    override val tags: List<String>?
        get() {
            if (streamInfo == null) return null
            return streamInfo!!.tags
        }

    override fun setupMetadata(inflater: LayoutInflater?, layout: LinearLayout?) {
        if (streamInfo?.uploadDate != null) {
            binding!!.detailUploadDateView.text = localizeUploadDate(requireActivity(), streamInfo!!.uploadDate.offsetDateTime())
        } else {
            binding!!.detailUploadDateView.visibility = View.GONE
        }

        if (streamInfo == null) return

        addMetadataItem(inflater, layout!!, false, R.string.metadata_category, streamInfo!!.category)

        addMetadataItem(inflater, layout, false, R.string.metadata_licence, streamInfo!!.licence)

        addPrivacyMetadataItem(inflater, layout)

        if (streamInfo!!.ageLimit != StreamExtractor.NO_AGE_LIMIT) {
            addMetadataItem(inflater, layout, false, R.string.metadata_age_limit, streamInfo!!.ageLimit.toString())
        }

        if (streamInfo!!.languageInfo != null) {
            addMetadataItem(inflater, layout, false, R.string.metadata_language,
                streamInfo!!.languageInfo.getDisplayLanguage(getAppLocale(requireContext())))
        }

        addMetadataItem(inflater, layout, true, R.string.metadata_support, streamInfo!!.supportInfo)
        addMetadataItem(inflater, layout, true, R.string.metadata_host, streamInfo!!.host)

        addImagesMetadataItem(inflater, layout, R.string.metadata_thumbnails, streamInfo!!.thumbnails)
        addImagesMetadataItem(inflater, layout, R.string.metadata_uploader_avatars, streamInfo!!.uploaderAvatars)
        addImagesMetadataItem(inflater, layout, R.string.metadata_subchannel_avatars, streamInfo!!.subChannelAvatars)
    }

    private fun addPrivacyMetadataItem(inflater: LayoutInflater?, layout: LinearLayout?) {
        if (streamInfo!!.privacy != null) {
            @StringRes val contentRes = when (streamInfo!!.privacy) {
                Privacy.PUBLIC -> R.string.metadata_privacy_public
                Privacy.UNLISTED -> R.string.metadata_privacy_unlisted
                Privacy.PRIVATE -> R.string.metadata_privacy_private
                Privacy.INTERNAL -> R.string.metadata_privacy_internal
                Privacy.OTHER -> 0
                else -> 0
            }
            if (contentRes != 0) {
                addMetadataItem(inflater, layout!!, false, R.string.metadata_privacy, getString(contentRes))
            }
        }
    }
}
