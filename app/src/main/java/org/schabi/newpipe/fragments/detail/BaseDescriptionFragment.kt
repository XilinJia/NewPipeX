package org.schabi.newpipe.fragments.detail

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.widget.TooltipCompat
import androidx.core.text.HtmlCompat
import com.google.android.material.chip.Chip
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentDescriptionBinding
import org.schabi.newpipe.databinding.ItemMetadataBinding
import org.schabi.newpipe.databinding.ItemMetadataTagsBinding
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.Image.ResolutionLevel
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.util.NavigationHelper.openSearchFragment
import org.schabi.newpipe.util.external_communication.ShareUtils.copyToClipboard
import org.schabi.newpipe.util.external_communication.ShareUtils.openUrlInBrowser
import org.schabi.newpipe.util.image.ImageStrategy.choosePreferredImage
import org.schabi.newpipe.util.text.TextLinkifier.SET_LINK_MOVEMENT_METHOD
import org.schabi.newpipe.util.text.TextLinkifier.fromDescription
import org.schabi.newpipe.util.text.TextLinkifier.fromPlainText
import java.util.function.Consumer

abstract class BaseDescriptionFragment : BaseFragment() {
    private val descriptionDisposables = CompositeDisposable()
    @JvmField
    protected var binding: FragmentDescriptionBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentDescriptionBinding.inflate(inflater, container, false)
        Logd(TAG, "onCreateView")
        setupDescription()
        setupMetadata(inflater, binding!!.detailMetadataLayout)
        addTagsMetadataItem(inflater, binding!!.detailMetadataLayout)
        return binding!!.root
    }

    override fun onDestroy() {
        descriptionDisposables.clear()
        super.onDestroy()
    }

    protected abstract val description: Description?
        /**
         * Get the description to display.
         * @return description object
         */
        get

    protected abstract val service: StreamingService?
        /**
         * Get the streaming service. Used for generating description links.
         * @return streaming service
         */
        get

    protected abstract val serviceId: Int
        /**
         * Get the streaming service ID. Used for tag links.
         * @return service ID
         */
        get

    protected abstract val streamUrl: String?
        /**
         * Get the URL of the described video or audio, used to generate description links.
         * @return stream URL
         */
        get

    /**
     * Get the list of tags to display below the description.
     * @return tag list
     */
    abstract val tags: List<String>?

    /**
     * Add additional metadata to display.
     * @param inflater LayoutInflater
     * @param layout detailMetadataLayout
     */
    protected abstract fun setupMetadata(inflater: LayoutInflater?, layout: LinearLayout?)

    private fun setupDescription() {
        val description = description
        if (description == null || TextUtils.isEmpty(description.content) || description === Description.EMPTY_DESCRIPTION) {
            binding!!.detailDescriptionView.visibility = View.GONE
            binding!!.detailSelectDescriptionButton.visibility = View.GONE
            return
        }

        // start with disabled state. This also loads description content (!)
        disableDescriptionSelection()

        binding!!.detailSelectDescriptionButton.setOnClickListener { v: View? ->
            if (binding!!.detailDescriptionNoteView.visibility == View.VISIBLE) disableDescriptionSelection()
            // enable selection only when button is clicked to prevent flickering
            else enableDescriptionSelection()
        }
    }

    private fun enableDescriptionSelection() {
        binding!!.detailDescriptionNoteView.visibility = View.VISIBLE
        binding!!.detailDescriptionView.setTextIsSelectable(true)

        val buttonLabel = getString(R.string.description_select_disable)
        binding!!.detailSelectDescriptionButton.contentDescription = buttonLabel
        TooltipCompat.setTooltipText(binding!!.detailSelectDescriptionButton, buttonLabel)
        binding!!.detailSelectDescriptionButton.setImageResource(R.drawable.ic_close)
    }

    private fun disableDescriptionSelection() {
        // show description content again, otherwise some links are not clickable
        val description = description
        if (description != null) {
            fromDescription(binding!!.detailDescriptionView,
                description, HtmlCompat.FROM_HTML_MODE_LEGACY,
                service, streamUrl,
                descriptionDisposables, SET_LINK_MOVEMENT_METHOD as Consumer<TextView?>?)
        }

        binding!!.detailDescriptionNoteView.visibility = View.GONE
        binding!!.detailDescriptionView.setTextIsSelectable(false)

        val buttonLabel = getString(R.string.description_select_enable)
        binding!!.detailSelectDescriptionButton.contentDescription = buttonLabel
        TooltipCompat.setTooltipText(binding!!.detailSelectDescriptionButton, buttonLabel)
        binding!!.detailSelectDescriptionButton.setImageResource(R.drawable.ic_select_all)
    }

    protected fun addMetadataItem(inflater: LayoutInflater?, layout: LinearLayout, linkifyContent: Boolean, @StringRes type: Int, content: String?) {
        if (Utils.isBlank(content)) return

        val itemBinding = ItemMetadataBinding.inflate(inflater!!, layout, false)

        itemBinding.metadataTypeView.setText(type)
        itemBinding.metadataTypeView.setOnLongClickListener { v: View? ->
            copyToClipboard(requireContext(), content)
            true
        }

        if (linkifyContent)
            fromPlainText(itemBinding.metadataContentView, content!!, null, null,
                descriptionDisposables, SET_LINK_MOVEMENT_METHOD as Consumer<TextView?>?)
        else itemBinding.metadataContentView.text = content

        itemBinding.metadataContentView.isClickable = true

        layout.addView(itemBinding.root)
    }

    private fun imageSizeToText(heightOrWidth: Int): String {
        return if (heightOrWidth < 0) getString(R.string.question_mark) else heightOrWidth.toString()
    }

    protected fun addImagesMetadataItem(inflater: LayoutInflater?, layout: LinearLayout, @StringRes type: Int, images: List<Image>) {
        val preferredImageUrl = choosePreferredImage(images) ?: return  // null will be returned in case there is no image

        val itemBinding = ItemMetadataBinding.inflate(inflater!!, layout, false)
        itemBinding.metadataTypeView.setText(type)

        val urls = SpannableStringBuilder()
        for (image in images) {
            if (urls.isNotEmpty()) urls.append(", ")

            val entryBegin = urls.length

            // if even the resolution level is unknown, ?x? will be shown
            if (image.height != Image.HEIGHT_UNKNOWN || image.width != Image.WIDTH_UNKNOWN || image.estimatedResolutionLevel == ResolutionLevel.UNKNOWN) {
                urls.append(imageSizeToText(image.height))
                urls.append('x')
                urls.append(imageSizeToText(image.width))
            } else {
                when (image.estimatedResolutionLevel) {
                    ResolutionLevel.LOW -> urls.append(getString(R.string.image_quality_low))
                    ResolutionLevel.MEDIUM -> urls.append(getString(R.string.image_quality_medium))
                    ResolutionLevel.HIGH -> urls.append(getString(R.string.image_quality_high))
                    else -> urls.append(getString(R.string.image_quality_medium))
                }
            }

            urls.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    openUrlInBrowser(requireContext(), image.url)
                }
            }, entryBegin, urls.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            if (preferredImageUrl == image.url) {
                urls.setSpan(StyleSpan(Typeface.BOLD), entryBegin, urls.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        itemBinding.metadataContentView.text = urls
        itemBinding.metadataContentView.movementMethod = LinkMovementMethod.getInstance()
        layout.addView(itemBinding.root)
    }

    private fun addTagsMetadataItem(inflater: LayoutInflater, layout: LinearLayout) {
        val tags = tags

        if (!tags.isNullOrEmpty()) {
            val itemBinding = ItemMetadataTagsBinding.inflate(inflater, layout, false)

            tags.stream().sorted(java.lang.String.CASE_INSENSITIVE_ORDER).forEach { tag: String? ->
                val chip = inflater.inflate(R.layout.chip, itemBinding.metadataTagsChips, false) as Chip
                chip.text = tag
                chip.setOnClickListener { chip: View -> this.onTagClick(chip) }
                chip.setOnLongClickListener { chip: View -> this.onTagLongClick(chip) }
                itemBinding.metadataTagsChips.addView(chip)
            }

            layout.addView(itemBinding.root)
        }
    }

    private fun onTagClick(chip: View) {
        if (parentFragment != null) {
            openSearchFragment(requireParentFragment().parentFragmentManager, serviceId, (chip as Chip).text.toString())
        }
    }

    private fun onTagLongClick(chip: View): Boolean {
        copyToClipboard(requireContext(), (chip as Chip).text.toString())
        return true
    }
}
