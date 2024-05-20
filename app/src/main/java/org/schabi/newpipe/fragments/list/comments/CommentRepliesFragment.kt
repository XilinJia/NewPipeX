package org.schabi.newpipe.fragments.list.comments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.text.HtmlCompat
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.CommentRepliesHeaderBinding
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.fragments.list.BaseListInfoFragment
import org.schabi.newpipe.info_list.ItemViewMode
import org.schabi.newpipe.util.DeviceUtils.dpToPx
import org.schabi.newpipe.util.ExtractorHelper.getMoreCommentItems
import org.schabi.newpipe.util.Localization.likeCount
import org.schabi.newpipe.util.Localization.relativeTimeOrTextual
import org.schabi.newpipe.util.Localization.replyCount
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.util.NavigationHelper.openCommentAuthorIfPresent
import org.schabi.newpipe.util.ServiceHelper.getServiceById
import org.schabi.newpipe.util.image.ImageStrategy.shouldLoadImages
import org.schabi.newpipe.util.image.PicassoHelper.loadAvatar
import org.schabi.newpipe.util.text.TextLinkifier.fromDescription
import java.util.*
import java.util.function.Supplier

/*//////////////////////////////////////////////////////////////////////////
    // Constructors and lifecycle
    ////////////////////////////////////////////////////////////////////////// */
// only called by the Android framework, after which readFrom is called and restores all data
class CommentRepliesFragment()
    : BaseListInfoFragment<CommentsInfoItem, CommentRepliesInfo>(UserAction.REQUESTED_COMMENT_REPLIES) {
    /**
     * @return the comment to which the replies are shown
     */
    var commentsInfoItem: CommentsInfoItem? = null // the comment to show replies of
        private set
    private val disposables = CompositeDisposable()


    constructor(commentsInfoItem: CommentsInfoItem) : this() {
        this.commentsInfoItem = commentsInfoItem
        // setting "" as title since the title will be properly set right after
        setInitialData(commentsInfoItem.serviceId, commentsInfoItem.url, "")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Logd(TAG, "onCreateView")
        return inflater.inflate(R.layout.fragment_comments, container, false)
    }

    override fun onDestroyView() {
        disposables.clear()
        super.onDestroyView()
    }

    override val listHeaderSupplier: Supplier<View>
        get() = Supplier {
            val binding = CommentRepliesHeaderBinding.inflate(requireActivity().layoutInflater, itemsList, false)
            val item = commentsInfoItem

            // load the author avatar
            loadAvatar(item!!.uploaderAvatars).into(binding.authorAvatar)
            binding.authorAvatar.visibility = if (shouldLoadImages()) View.VISIBLE else View.GONE

            // setup author name and comment date
            binding.authorName.text = item.uploaderName
            binding.uploadDate.text = relativeTimeOrTextual(
                context, item.uploadDate, item.textualUploadDate)
            binding.authorTouchArea.setOnClickListener { v: View? ->
                openCommentAuthorIfPresent(requireActivity(), item)
            }

            // setup like count, hearted and pinned
            binding.thumbsUpCount.text = likeCount(requireContext(), item.likeCount)
            // for heartImage goneMarginEnd was used, but there is no way to tell ConstraintLayout
            // not to use a different margin only when both the next two views are gone
            (binding.thumbsUpCount.layoutParams as ConstraintLayout.LayoutParams).marginEnd =
                dpToPx((if (item.isHeartedByUploader || item.isPinned) 8 else 16), requireContext())
            binding.heartImage.visibility = if (item.isHeartedByUploader) View.VISIBLE else View.GONE
            binding.pinnedImage.visibility = if (item.isPinned) View.VISIBLE else View.GONE

            // setup comment content
            fromDescription(binding.commentContent, item.commentText, HtmlCompat.FROM_HTML_MODE_LEGACY, getServiceById(item.serviceId),
                item.url, disposables, null)
            binding.root
        }


    /*//////////////////////////////////////////////////////////////////////////
    // State saving
    ////////////////////////////////////////////////////////////////////////// */
    override fun writeTo(objectsToSave: Queue<Any>?) {
        super.writeTo(objectsToSave)
        objectsToSave!!.add(commentsInfoItem)
    }

    @Throws(Exception::class)
    override fun readFrom(savedObjects: Queue<Any>) {
        super.readFrom(savedObjects)
        commentsInfoItem = savedObjects.poll() as CommentsInfoItem
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Data loading
    ////////////////////////////////////////////////////////////////////////// */
    override fun loadResult(forceLoad: Boolean): Single<CommentRepliesInfo> {
        return Single.fromCallable {
            // the reply count string will be shown as the activity title
            CommentRepliesInfo(commentsInfoItem!!, replyCount(requireContext(), commentsInfoItem!!.replyCount))
        }
    }

    override fun loadMoreItemsLogic(): Single<InfoItemsPage<CommentsInfoItem>> {
        // commentsInfoItem.getUrl() should contain the url of the original
        // ListInfo<CommentsInfoItem>, which should be the stream url
        return getMoreCommentItems(serviceId, commentsInfoItem!!.url, currentNextPage)
    }


    /*//////////////////////////////////////////////////////////////////////////
// Utils
////////////////////////////////////////////////////////////////////////// */
    override val itemViewMode: ItemViewMode
        get() = ItemViewMode.LIST

    companion object {
        val TAG: String = CommentRepliesFragment::class.java.simpleName
    }
}
