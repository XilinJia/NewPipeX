package org.schabi.newpipe.info_list.holder

import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.info_list.InfoItemBuilder
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.util.DeviceUtils.dpToPx
import org.schabi.newpipe.util.DeviceUtils.isTv
import org.schabi.newpipe.util.Localization.concatenateStrings
import org.schabi.newpipe.util.Localization.likeCount
import org.schabi.newpipe.util.Localization.relativeTimeOrTextual
import org.schabi.newpipe.util.Localization.replyCount
import org.schabi.newpipe.util.NavigationHelper.openCommentAuthorIfPresent
import org.schabi.newpipe.util.NavigationHelper.openCommentRepliesFragment
import org.schabi.newpipe.util.ServiceHelper.getServiceById
import org.schabi.newpipe.util.external_communication.ShareUtils.copyToClipboard
import org.schabi.newpipe.util.image.ImageStrategy.shouldLoadImages
import org.schabi.newpipe.util.image.PicassoHelper.loadAvatar
import org.schabi.newpipe.util.text.CommentTextOnTouchListener
import org.schabi.newpipe.util.text.TextEllipsizer

class CommentInfoItemHolder(infoItemBuilder: InfoItemBuilder, parent: ViewGroup?)
    : InfoItemHolder(infoItemBuilder, R.layout.list_comment_item, parent) {

    private val commentHorizontalPadding = infoItemBuilder.context
        .resources.getDimension(R.dimen.comments_horizontal_padding).toInt()
    private val commentVerticalPadding = infoItemBuilder.context
        .resources.getDimension(R.dimen.comments_vertical_padding).toInt()

    private val itemRoot: RelativeLayout = itemView.findViewById(R.id.itemRoot)
    private val itemThumbnailView: ImageView = itemView.findViewById(R.id.itemThumbnailView)
    private val itemContentView: TextView = itemView.findViewById(R.id.itemCommentContentView)
    private val itemThumbsUpView: ImageView = itemView.findViewById(R.id.detail_thumbs_up_img_view)
    private val itemLikesCountView: TextView = itemView.findViewById(R.id.detail_thumbs_up_count_view)
    private val itemTitleView: TextView = itemView.findViewById(R.id.itemTitleView)
    private val itemHeartView: ImageView = itemView.findViewById(R.id.detail_heart_image_view)
    private val itemPinnedView: ImageView = itemView.findViewById(R.id.detail_pinned_view)
    private val repliesButton: Button = itemView.findViewById(R.id.replies_button)

    private val textEllipsizer: TextEllipsizer

    init {
        textEllipsizer = TextEllipsizer(itemContentView, COMMENT_DEFAULT_LINES, null)
        textEllipsizer.setStateChangeListener { isEllipsized: Boolean ->
            if (java.lang.Boolean.TRUE == isEllipsized) {
                denyLinkFocus()
            } else {
                determineMovementMethod()
            }
        }
    }

    override fun updateFromItem(infoItem: InfoItem?, historyRecordManager: HistoryRecordManager?) {
        if (infoItem !is CommentsInfoItem) {
            return
        }
        val item = infoItem


        // load the author avatar
        loadAvatar(item.uploaderAvatars).into(itemThumbnailView)
        if (shouldLoadImages()) {
            itemThumbnailView.visibility = View.VISIBLE
            itemRoot.setPadding(commentVerticalPadding, commentVerticalPadding,
                commentVerticalPadding, commentVerticalPadding)
        } else {
            itemThumbnailView.visibility = View.GONE
            itemRoot.setPadding(commentHorizontalPadding, commentVerticalPadding,
                commentHorizontalPadding, commentVerticalPadding)
        }
        itemThumbnailView.setOnClickListener { view: View? -> openCommentAuthor(item) }

        // setup the top row, with pinned icon, author name and comment date
        itemPinnedView.visibility = if (item.isPinned) View.VISIBLE else View.GONE
        itemTitleView.text = concatenateStrings(item.uploaderName,
            relativeTimeOrTextual(itemBuilder.context, item.uploadDate, item.textualUploadDate?:""))

        // setup bottom row, with likes, heart and replies button
        itemLikesCountView.text = likeCount(itemBuilder.context, item.likeCount)

        itemHeartView.visibility = if (item.isHeartedByUploader) View.VISIBLE else View.GONE

        val hasReplies = item.replies != null
        repliesButton.setOnClickListener(if (hasReplies) View.OnClickListener { v: View? -> openCommentReplies(item) } else null)
        repliesButton.visibility = if (hasReplies) View.VISIBLE else View.GONE
        repliesButton.text = if (hasReplies) replyCount(itemBuilder.context, item.replyCount) else ""

        (itemThumbsUpView.layoutParams as RelativeLayout.LayoutParams).topMargin =
            if (hasReplies) 0 else dpToPx(6, itemBuilder.context)

        // setup comment content and click listeners to expand/ellipsize it
        textEllipsizer.setStreamingService(getServiceById(item.serviceId))
        textEllipsizer.setStreamUrl(item.url)
        if (item.commentText != null) textEllipsizer.setContent(item.commentText)
        textEllipsizer.ellipsize()

        itemContentView.setOnTouchListener(CommentTextOnTouchListener.INSTANCE)

        itemView.setOnClickListener { view: View? ->
            textEllipsizer.toggle()
            itemBuilder.onCommentsSelectedListener?.selected(item)
        }

        itemView.setOnLongClickListener { view: View? ->
            if (isTv(itemBuilder.context)) {
                openCommentAuthor(item)
            } else {
                val text = itemContentView.text
                if (text != null) {
                    copyToClipboard(itemBuilder.context, text.toString())
                }
            }
            true
        }
    }

    private fun openCommentAuthor(item: CommentsInfoItem) {
        openCommentAuthorIfPresent((itemBuilder.context as FragmentActivity),
            item)
    }

    private fun openCommentReplies(item: CommentsInfoItem) {
        openCommentRepliesFragment((itemBuilder.context as FragmentActivity),
            item)
    }

    private fun allowLinkFocus() {
        itemContentView.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun denyLinkFocus() {
        itemContentView.movementMethod = null
    }

    private fun shouldFocusLinks(): Boolean {
        if (itemView.isInTouchMode) {
            return false
        }

        val urls = itemContentView.urls

        return urls != null && urls.size != 0
    }

    private fun determineMovementMethod() {
        if (shouldFocusLinks()) {
            allowLinkFocus()
        } else {
            denyLinkFocus()
        }
    }

    companion object {
        private const val COMMENT_DEFAULT_LINES = 2
    }
}
