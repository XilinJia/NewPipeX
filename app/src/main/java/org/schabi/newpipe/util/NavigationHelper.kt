package org.schabi.newpipe.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.media3.common.util.UnstableApi
import com.jakewharton.processphoenix.ProcessPhoenix
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.database.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.RouterActivity
import org.schabi.newpipe.ui.about.AboutActivity
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.ui.download.DownloadActivity
import org.schabi.newpipe.error.ErrorUtil.Companion.showUiErrorSnackbar
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.StreamingService.LinkType
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.*
import org.schabi.newpipe.ui.fragments.MainFragment
import org.schabi.newpipe.ui.detail.VideoDetailFragment
import org.schabi.newpipe.ui.list.ChannelFragment
import org.schabi.newpipe.ui.list.CommentRepliesFragment
import org.schabi.newpipe.ui.list.KioskFragment
import org.schabi.newpipe.ui.list.PlaylistFragment
import org.schabi.newpipe.ui.list.SearchFragment
import org.schabi.newpipe.ui.local.BookmarkFragment
import org.schabi.newpipe.ui.local.feed.FeedFragment.Companion.newInstance
import org.schabi.newpipe.ui.local.history.StatisticsPlaylistFragment
import org.schabi.newpipe.ui.local.playlist.LocalPlaylistFragment
import org.schabi.newpipe.ui.local.subscription.SubscriptionFragment
import org.schabi.newpipe.ui.local.subscription.SubscriptionsImportFragment
import org.schabi.newpipe.player.PlayQueueActivity
import org.schabi.newpipe.player.PlayerManager
import org.schabi.newpipe.player.PlayerService
import org.schabi.newpipe.player.PlayerType
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.player.helper.PlayerHolder
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.settings.SettingsActivity
import org.schabi.newpipe.util.ListHelper.getDefaultAudioFormat
import org.schabi.newpipe.util.ListHelper.getDefaultResolutionIndex
import org.schabi.newpipe.util.ListHelper.getSortedStreamVideosList
import org.schabi.newpipe.util.ListHelper.getUrlAndNonTorrentStreams
import org.schabi.newpipe.util.external_communication.ShareUtils.installApp
import org.schabi.newpipe.util.external_communication.ShareUtils.tryOpenIntentInApp

@UnstableApi object NavigationHelper {
    const val MAIN_FRAGMENT_TAG: String = "main_fragment_tag"
    const val SEARCH_FRAGMENT_TAG: String = "search_fragment_tag"

    private val TAG: String = NavigationHelper::class.java.simpleName

    /*//////////////////////////////////////////////////////////////////////////
    // Players
    ////////////////////////////////////////////////////////////////////////// */
    /* INTENT */
    @JvmStatic
    fun <T> getPlayerIntent(context: Context, targetClazz: Class<T>, playQueue: PlayQueue?, resumePlayback: Boolean): Intent {
        val intent = Intent(context, targetClazz)

        if (playQueue != null) {
            val cacheKey = SerializedCache.instance.put(playQueue, PlayQueue::class.java)
            if (cacheKey != null) intent.putExtra(PlayerManager.PLAY_QUEUE_KEY, cacheKey)
        }
        intent.putExtra(PlayerManager.PLAYER_TYPE, PlayerType.MAIN.valueForIntent())
        intent.putExtra(PlayerManager.RESUME_PLAYBACK, resumePlayback)

        return intent
    }

    @JvmStatic
    fun <T> getPlayerIntent(context: Context, targetClazz: Class<T>, playQueue: PlayQueue?, resumePlayback: Boolean, playWhenReady: Boolean): Intent {
        return getPlayerIntent(context, targetClazz, playQueue, resumePlayback).putExtra(PlayerManager.PLAY_WHEN_READY, playWhenReady)
    }

    fun <T> getPlayerEnqueueIntent(context: Context, targetClazz: Class<T>, playQueue: PlayQueue?): Intent {
        // when enqueueing `resumePlayback` is always `false` since:
        // - if there is a video already playing, the value of `resumePlayback` just doesn't make
        //   any difference.
        // - if there is nothing already playing, it is useful for the enqueue action to have a
        //   slightly different behaviour than the normal play action: the latter resumes playback,
        //   the former doesn't. (note that enqueue can be triggered when nothing is playing only
        //   by long pressing the video detail fragment, playlist or channel controls
        return getPlayerIntent(context, targetClazz, playQueue, false).putExtra(PlayerManager.ENQUEUE, true)
    }

    fun <T> getPlayerEnqueueNextIntent(context: Context, targetClazz: Class<T>, playQueue: PlayQueue?): Intent {
        // see comment in `getPlayerEnqueueIntent` as to why `resumePlayback` is false
        return getPlayerIntent(context, targetClazz, playQueue, false).putExtra(PlayerManager.ENQUEUE_NEXT, true)
    }

    /* PLAY */
    @JvmStatic
    fun playOnMainPlayer(activity: AppCompatActivity, playQueue: PlayQueue) {
        val item = playQueue.item
        if (item != null)
            openVideoDetailFragment(activity, activity.supportFragmentManager, item.serviceId, item.url, item.title, playQueue, false)
    }

    @JvmStatic
    fun playOnMainPlayer(context: Context, playQueue: PlayQueue, switchingPlayers: Boolean) {
        val item = playQueue.item
        if (item != null) openVideoDetail(context, item.serviceId, item.url, item.title, playQueue, switchingPlayers)
    }

    @JvmStatic
    fun playOnPopupPlayer(context: Context, queue: PlayQueue?, resumePlayback: Boolean) {
        if (!PermissionHelper.isPopupEnabledElseAsk(context)) return
        Toast.makeText(context, R.string.popup_playing_toast, Toast.LENGTH_SHORT).show()
        val intent = getPlayerIntent(context, PlayerService::class.java, queue, resumePlayback)
        intent.putExtra(PlayerManager.PLAYER_TYPE, PlayerType.POPUP.valueForIntent())
        ContextCompat.startForegroundService(context, intent)
    }

    @JvmStatic
    fun playOnBackgroundPlayer(context: Context, queue: PlayQueue?, resumePlayback: Boolean) {
        Toast.makeText(context, R.string.background_player_playing_toast, Toast.LENGTH_SHORT).show()
        val intent = getPlayerIntent(context, PlayerService::class.java, queue, resumePlayback)
        intent.putExtra(PlayerManager.PLAYER_TYPE, PlayerType.AUDIO.valueForIntent())
        ContextCompat.startForegroundService(context, intent)
    }

    /* ENQUEUE */
    @JvmStatic
    fun enqueueOnPlayer(context: Context, queue: PlayQueue?, playerType: PlayerType) {
        if (playerType == PlayerType.POPUP && !PermissionHelper.isPopupEnabledElseAsk(context)) return
        Toast.makeText(context, R.string.enqueued, Toast.LENGTH_SHORT).show()
        val intent = getPlayerEnqueueIntent(context, PlayerService::class.java, queue)
        intent.putExtra(PlayerManager.PLAYER_TYPE, playerType.valueForIntent())
        ContextCompat.startForegroundService(context, intent)
    }

    @JvmStatic
    fun enqueueOnPlayer(context: Context, queue: PlayQueue?) {
        var playerType = PlayerHolder.instance?.type
        if (playerType == null) {
            Log.e(TAG, "Enqueueing but no player is open; defaulting to background player")
            playerType = PlayerType.AUDIO
        }
        enqueueOnPlayer(context, queue, playerType)
    }

    /* ENQUEUE NEXT */
    @JvmStatic
    fun enqueueNextOnPlayer(context: Context, queue: PlayQueue?) {
        var playerType = PlayerHolder.instance?.type
        if (playerType == null) {
            Log.e(TAG, "Enqueueing next but no player is open; defaulting to background player")
            playerType = PlayerType.AUDIO
        }
        Toast.makeText(context, R.string.enqueued_next, Toast.LENGTH_SHORT).show()
        val intent = getPlayerEnqueueNextIntent(context, PlayerService::class.java, queue)
        intent.putExtra(PlayerManager.PLAYER_TYPE, playerType.valueForIntent())
        ContextCompat.startForegroundService(context, intent)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // External Players
    ////////////////////////////////////////////////////////////////////////// */
    @JvmStatic
    fun playOnExternalAudioPlayer(context: Context, info: StreamInfo) {
        val audioStreams = info.audioStreams
        if (audioStreams == null || audioStreams.isEmpty()) {
            Toast.makeText(context, R.string.audio_streams_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val audioStreamsForExternalPlayers: List<AudioStream?> = getUrlAndNonTorrentStreams(audioStreams)
        if (audioStreamsForExternalPlayers.isEmpty()) {
            Toast.makeText(context, R.string.no_audio_streams_available_for_external_players, Toast.LENGTH_SHORT).show()
            return
        }
        val index = getDefaultAudioFormat(context, audioStreamsForExternalPlayers)
        val audioStream = audioStreamsForExternalPlayers[index]!!
        playOnExternalPlayer(context, info.name, info.uploaderName, audioStream)
    }

    @JvmStatic
    fun playOnExternalVideoPlayer(context: Context, info: StreamInfo) {
        val videoStreams = info.videoStreams
        if (videoStreams == null || videoStreams.isEmpty()) {
            Toast.makeText(context, R.string.video_streams_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val videoStreamsForExternalPlayers = getSortedStreamVideosList(context,
            getUrlAndNonTorrentStreams(videoStreams), null, false, false)
        if (videoStreamsForExternalPlayers.isEmpty()) {
            Toast.makeText(context, R.string.no_video_streams_available_for_external_players, Toast.LENGTH_SHORT).show()
            return
        }
        val index = getDefaultResolutionIndex(context, videoStreamsForExternalPlayers)
        val videoStream = videoStreamsForExternalPlayers[index]
        playOnExternalPlayer(context, info.name, info.uploaderName, videoStream)
    }

    @JvmStatic
    fun playOnExternalPlayer(context: Context, name: String?, artist: String?, stream: Stream) {
        val deliveryMethod = stream.deliveryMethod
        if (!stream.isUrl || deliveryMethod == DeliveryMethod.TORRENT) {
            Toast.makeText(context, R.string.selected_stream_external_player_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        val mimeType = when (deliveryMethod) {
            DeliveryMethod.PROGRESSIVE_HTTP ->
                if (stream.format == null) {
                    when (stream) {
                        is AudioStream -> "audio/*"
                        is VideoStream -> "video/*"
                        // This should never be reached, because subtitles are not opened in
                        // external players
                        else -> return
                    }
                } else stream.format!!.getMimeType()
            DeliveryMethod.HLS -> "application/x-mpegURL"
            DeliveryMethod.DASH -> "application/dash+xml"
            DeliveryMethod.SS -> "application/vnd.ms-sstr+xml"
            // Torrent streams are not exposed to external players
            else -> ""
        }
        val intent = Intent()
        intent.setAction(Intent.ACTION_VIEW)
        intent.setDataAndType(Uri.parse(stream.content), mimeType)
        intent.putExtra(Intent.EXTRA_TITLE, name)
        intent.putExtra("title", name)
        intent.putExtra("artist", artist)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        resolveActivityOrAskToInstall(context, intent)
    }

    fun resolveActivityOrAskToInstall(context: Context, intent: Intent) {
        if (!tryOpenIntentInApp(context, intent)) {
            if (context is Activity) AlertDialog.Builder(context)
                .setMessage(R.string.no_player_found)
                .setPositiveButton(R.string.install) { dialog: DialogInterface?, which: Int -> installApp(context, context.getString(R.string.vlc_package)) }
                .setNegativeButton(R.string.cancel) { dialog: DialogInterface?, which: Int -> Log.i("NavigationHelper", "You unlocked a secret unicorn.") }
                .show()
            else Toast.makeText(context, R.string.no_player_found_toast, Toast.LENGTH_LONG).show()
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Through FragmentManager
    ////////////////////////////////////////////////////////////////////////// */
    @SuppressLint("CommitTransaction")
    private fun defaultTransaction(fragmentManager: FragmentManager): FragmentTransaction {
        return fragmentManager.beginTransaction()
            .setCustomAnimations(R.animator.custom_fade_in, R.animator.custom_fade_out, R.animator.custom_fade_in, R.animator.custom_fade_out)
    }

    @JvmStatic
    fun gotoMainFragment(fragmentManager: FragmentManager) {
//        if calling popBackStackImmediate directly, it can throw an exception of IllegalStateException: Fragment no longer exists for key f0
        if (fragmentManager.findFragmentByTag(MAIN_FRAGMENT_TAG) != null) fragmentManager.popBackStack(MAIN_FRAGMENT_TAG, 0)
//            fragmentManager.popBackStackImmediate(MAIN_FRAGMENT_TAG, 0)
        else openMainFragment(fragmentManager)
    }

    @JvmStatic
    fun openMainFragment(fragmentManager: FragmentManager) {
        InfoCache.instance.trimCache()
        fragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, MainFragment())
            .addToBackStack(MAIN_FRAGMENT_TAG)
            .commit()
    }

    @JvmStatic
    fun tryGotoSearchFragment(fragmentManager: FragmentManager): Boolean {
        for (i in 0 until fragmentManager.backStackEntryCount) {
            Logd("NavigationHelper", "tryGoToSearchFragment() [$i] = [${fragmentManager.getBackStackEntryAt(i)}]")
        }
        return fragmentManager.popBackStackImmediate(SEARCH_FRAGMENT_TAG, 0)
    }

    @JvmStatic
    fun openSearchFragment(fragmentManager: FragmentManager, serviceId: Int, searchString: String) {
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, SearchFragment.getInstance(serviceId, searchString))
            .addToBackStack(SEARCH_FRAGMENT_TAG)
            .commit()
    }

    fun expandMainPlayer(context: Context) {
        context.sendBroadcast(Intent(VideoDetailFragment.ACTION_SHOW_MAIN_PLAYER).setPackage(context.getPackageName()))
    }

    @JvmStatic
    fun sendPlayerStartedEvent(context: Context) {
        context.sendBroadcast(Intent(VideoDetailFragment.ACTION_PLAYER_STARTED).setPackage(context.getPackageName()))
    }

    @JvmStatic
    fun showMiniPlayer(fragmentManager: FragmentManager) {
        val instance = VideoDetailFragment.instanceInCollapsedState
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_player_holder, instance)
            .runOnCommit { sendPlayerStartedEvent(instance.requireActivity()) }
            .commitAllowingStateLoss()
    }

    @JvmStatic
    fun openVideoDetailFragment(context: Context, fragmentManager: FragmentManager, serviceId: Int, url: String?, title: String, playQueue: PlayQueue?, switchingPlayers: Boolean) {
        val autoPlay: Boolean
        val playerType = PlayerHolder.instance?.type
        Logd(TAG, "openVideoDetailFragment: $playerType")
        autoPlay = when {
            // no player open
            playerType == null -> PlayerHelper.isAutoplayAllowedByUser(context)
            // switching player to main player
            // keep play/pause state
            switchingPlayers -> PlayerHolder.instance?.isPlaying ?: false
            // opening new stream while already playing in main player
            playerType == PlayerType.MAIN -> PlayerHelper.isAutoplayAllowedByUser(context)
            // opening new stream while already playing in another player
            else -> false
        }

        val onVideoDetailFragmentReady: RunnableWithVideoDetailFragment = object: RunnableWithVideoDetailFragment {
            override fun run(detailFragment: VideoDetailFragment?) {
                if (detailFragment == null) return
                expandMainPlayer(detailFragment.requireActivity())
                detailFragment.setAutoPlay(autoPlay)
                // Situation when user switches from players to main player. All needed data is
                // here, we can start watching (assuming newQueue equals playQueue).
                // Starting directly in fullscreen if the previous player type was popup.
                if (switchingPlayers) detailFragment.openVideoPlayer(
                    playerType == PlayerType.POPUP || PlayerHelper.isStartMainPlayerFullscreenEnabled(context))
                else detailFragment.selectAndLoadVideo(serviceId, url, title, playQueue)
                detailFragment.scrollToTop()
            }
        }

        val fragment = fragmentManager.findFragmentById(R.id.fragment_player_holder)
        if (fragment is VideoDetailFragment && fragment.isVisible()) onVideoDetailFragmentReady.run(fragment as VideoDetailFragment?)
        else {
            val instance = VideoDetailFragment.getInstance(serviceId, url, title, playQueue)
            instance.setAutoPlay(autoPlay)
            defaultTransaction(fragmentManager)
                .replace(R.id.fragment_player_holder, instance)
                .runOnCommit { onVideoDetailFragmentReady.run(instance) }
                .commit()
        }
    }

    @JvmStatic
    fun openChannelFragment(fragmentManager: FragmentManager, serviceId: Int, url: String?, name: String) {
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, ChannelFragment.getInstance(serviceId, url?:"", name))
            .addToBackStack(null)
            .commit()
    }

    @JvmStatic
    fun openChannelFragment(fragment: Fragment, item: StreamInfoItem, uploaderUrl: String?) {
        // For some reason `getParentFragmentManager()` doesn't work, but this does.
        openChannelFragment(fragment.requireActivity().supportFragmentManager, item.serviceId, uploaderUrl, item.uploaderName)
    }

    /**
     * Opens the comment author channel fragment, if the [CommentsInfoItem.getUploaderUrl]
     * of `comment` is non-null. Shows a UI-error snackbar if something goes wrong.
     *
     * @param activity the activity with the fragment manager and in which to show the snackbar
     * @param comment the comment whose uploader/author will be opened
     */
    @JvmStatic
    fun openCommentAuthorIfPresent(activity: FragmentActivity, comment: CommentsInfoItem) {
        if (TextUtils.isEmpty(comment.uploaderUrl)) return
        try {
            openChannelFragment(activity.supportFragmentManager, comment.serviceId, comment.uploaderUrl, comment.uploaderName)
        } catch (e: Exception) {
            showUiErrorSnackbar(activity, "Opening channel fragment", e)
        }
    }

    @JvmStatic
    fun openCommentRepliesFragment(activity: FragmentActivity, comment: CommentsInfoItem) {
        defaultTransaction(activity.supportFragmentManager)
            .replace(R.id.fragment_holder, CommentRepliesFragment(comment), CommentRepliesFragment.TAG)
            .addToBackStack(CommentRepliesFragment.TAG)
            .commit()
    }

    @JvmStatic
    fun openPlaylistFragment(fragmentManager: FragmentManager, serviceId: Int, url: String?, name: String) {
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, PlaylistFragment.getInstance(serviceId, url, name))
            .addToBackStack(null)
            .commit()
    }

    @JvmOverloads
    fun openFeedFragment(fragmentManager: FragmentManager, groupId: Long = FeedGroupEntity.GROUP_ALL_ID, groupName: String? = null) {
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, newInstance(groupId, groupName))
            .addToBackStack(null)
            .commit()
    }

    @JvmStatic
    fun openBookmarksFragment(fragmentManager: FragmentManager) {
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, BookmarkFragment())
            .addToBackStack(null)
            .commit()
    }

    @JvmStatic
    fun openSubscriptionFragment(fragmentManager: FragmentManager) {
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, SubscriptionFragment())
            .addToBackStack(null)
            .commit()
    }

    @JvmStatic
    @Throws(ExtractionException::class)
    fun openKioskFragment(fragmentManager: FragmentManager, serviceId: Int, kioskId: String?) {
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, KioskFragment.getInstance(serviceId, kioskId?:""))
            .addToBackStack(null)
            .commit()
    }

    @JvmStatic
    fun openLocalPlaylistFragment(fragmentManager: FragmentManager, playlistId: Long, name: String?) {
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, LocalPlaylistFragment.getInstance(playlistId, name ?: ""))
            .addToBackStack(null)
            .commit()
    }

    @JvmStatic
    fun openStatisticFragment(fragmentManager: FragmentManager) {
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, StatisticsPlaylistFragment())
            .addToBackStack(null)
            .commit()
    }

    fun openSubscriptionsImportFragment(fragmentManager: FragmentManager, serviceId: Int) {
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, SubscriptionsImportFragment.getInstance(serviceId))
            .addToBackStack(null)
            .commit()
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Through Intents
    ////////////////////////////////////////////////////////////////////////// */
    @JvmStatic
    fun openSearch(context: Context, serviceId: Int, searchString: String?) {
        val mIntent = Intent(context, MainActivity::class.java)
        mIntent.putExtra(KEY_SERVICE_ID, serviceId)
        mIntent.putExtra(KEY_SEARCH_STRING, searchString)
        mIntent.putExtra(KEY_OPEN_SEARCH, true)
        context.startActivity(mIntent)
    }

    @JvmStatic
    fun openVideoDetail(context: Context, serviceId: Int, url: String?, title: String, playQueue: PlayQueue?, switchingPlayers: Boolean) {
        val intent = getStreamIntent(context, serviceId, url, title).putExtra(VideoDetailFragment.KEY_SWITCHING_PLAYERS, switchingPlayers)
        if (playQueue != null) {
            val cacheKey = SerializedCache.instance.put(playQueue, PlayQueue::class.java)
            if (cacheKey != null) intent.putExtra(PlayerManager.PLAY_QUEUE_KEY, cacheKey)
        }
        context.startActivity(intent)
    }

    /**
     * Opens [ChannelFragment].
     * Use this instead of [.openChannelFragment]
     * when no fragments are used / no FragmentManager is available.
     * @param context
     * @param serviceId
     * @param url
     * @param title
     */
    @JvmStatic
    fun openChannelFragmentUsingIntent(context: Context, serviceId: Int, url: String?, title: String) {
        val intent = getOpenIntent(context, url, serviceId, LinkType.CHANNEL)
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra(KEY_TITLE, title)
        context.startActivity(intent)
    }

    @JvmStatic
    fun openMainActivity(context: Context) {
        val mIntent = Intent(context, MainActivity::class.java)
        mIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        mIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(mIntent)
    }

    fun openRouterActivity(context: Context, url: String?) {
        val mIntent = Intent(context, RouterActivity::class.java)
        mIntent.setData(Uri.parse(url))
        context.startActivity(mIntent)
    }

    @JvmStatic
    fun openAbout(context: Context) {
        val intent = Intent(context, AboutActivity::class.java)
        context.startActivity(intent)
    }

    @JvmStatic
    fun openSettings(context: Context) {
        val intent = Intent(context, SettingsActivity::class.java)
        context.startActivity(intent)
    }

    @JvmStatic
    fun openDownloads(activity: Activity) {
        if (PermissionHelper.checkStoragePermissions(activity, PermissionHelper.DOWNLOADS_REQUEST_CODE)) {
            val intent = Intent(activity, DownloadActivity::class.java)
            activity.startActivity(intent)
        }
    }

    @JvmStatic
    fun getPlayQueueActivityIntent(context: Context?): Intent {
        val intent = Intent(context, PlayQueueActivity::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    @JvmStatic
    fun openPlayQueue(context: Context) {
        val intent = Intent(context, PlayQueueActivity::class.java)
        context.startActivity(intent)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Link handling
    ////////////////////////////////////////////////////////////////////////// */
    private fun getOpenIntent(context: Context, url: String?, serviceId: Int, type: LinkType): Intent {
        val mIntent = Intent(context, MainActivity::class.java)
        mIntent.putExtra(KEY_SERVICE_ID, serviceId)
        mIntent.putExtra(KEY_URL, url)
        mIntent.putExtra(KEY_LINK_TYPE, type)
        return mIntent
    }

    @JvmStatic
    @Throws(ExtractionException::class)
    fun getIntentByLink(context: Context, url: String): Intent {
        return getIntentByLink(context, NewPipe.getServiceByUrl(url), url)
    }

    @JvmStatic
    @Throws(ExtractionException::class)
    fun getIntentByLink(context: Context, service: StreamingService, url: String): Intent {
        val linkType = service.getLinkTypeByUrl(url)
        if (linkType == LinkType.NONE) throw ExtractionException("Url not known to service. service=$service url=$url")
        return getOpenIntent(context, url, service.serviceId, linkType)
    }

    fun getChannelIntent(context: Context, serviceId: Int, url: String?): Intent {
        return getOpenIntent(context, url, serviceId, LinkType.CHANNEL)
    }

    fun getStreamIntent(context: Context, serviceId: Int, url: String?, title: String?): Intent {
        return getOpenIntent(context, url, serviceId, LinkType.STREAM)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(KEY_TITLE, title)
    }

    /**
     * Finish this `Activity` as well as all `Activities` running below it
     * and then start `MainActivity`.
     *
     * @param activity the activity to finish
     */
    @JvmStatic
    fun restartApp(activity: Activity) {
        NewPipeDatabase.close()
        ProcessPhoenix.triggerRebirth(activity.applicationContext)
    }

    private interface RunnableWithVideoDetailFragment {
        fun run(detailFragment: VideoDetailFragment?)
    }
}
