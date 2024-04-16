package org.schabi.newpipe.player.helper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import org.schabi.newpipe.App.Companion.getApp
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.PlayerService
import org.schabi.newpipe.player.PlayerService.LocalBinder
import org.schabi.newpipe.player.PlayerType
import org.schabi.newpipe.player.event.PlayerServiceEventListener
import org.schabi.newpipe.player.event.PlayerServiceExtendedEventListener
import org.schabi.newpipe.player.playqueue.PlayQueue

class PlayerHolder private constructor() {
    private var listener: PlayerServiceExtendedEventListener? = null

    private val serviceConnection = PlayerServiceConnection()
    var isBound: Boolean = false
        private set
    private var playerService: PlayerService? = null
    private var player: Player? = null

    val type: PlayerType?
        /**
         * Returns the current [PlayerType] of the [PlayerService] service,
         * otherwise `null` if no service is running.
         *
         * @return Current PlayerType
         */
        get() {
            if (player == null) return null
            return player!!.playerType
        }

    val isPlaying: Boolean
        get() {
            if (player == null) return false
            return player!!.isPlaying
        }

    val isPlayerOpen: Boolean
        get() = player != null

    val isPlayQueueReady: Boolean
        /**
         * Use this method to only allow the user to manipulate the play queue (e.g. by enqueueing via
         * the stream long press menu) when there actually is a play queue to manipulate.
         * @return true only if the player is open and its play queue is ready (i.e. it is not null)
         */
        get() = player != null && player!!.playQueue != null

    val queueSize: Int
        get() {
            // player play queue might be null e.g. while player is starting
            if (player?.playQueue == null) return 0
            return player!!.playQueue!!.size()
        }

    val queuePosition: Int
        get() {
            if (player?.playQueue == null) return 0
            return player!!.playQueue!!.index
        }

    fun setListener(newListener: PlayerServiceExtendedEventListener?) {
        listener = newListener

        if (listener == null) return

        // Force reload data from service
        if (player != null) {
            listener!!.onServiceConnected(player, playerService, false)
            startPlayerListener()
        }
    }

    private val commonContext: Context
        // helper to handle context in common place as using the same
        get() = getApp()

    fun startService(playAfterConnect: Boolean, newListener: PlayerServiceExtendedEventListener?) {
        val context = commonContext
        setListener(newListener)
        if (isBound) return

        // startService() can be called concurrently and it will give a random crashes
        // and NullPointerExceptions inside the service because the service will be
        // bound twice. Prevent it with unbinding first
        unbind(context)
        ContextCompat.startForegroundService(context, Intent(context, PlayerService::class.java))
        serviceConnection.doPlayAfterConnect(playAfterConnect)
        bind(context)
    }

    fun stopService() {
        val context = commonContext
        unbind(context)
        context.stopService(Intent(context, PlayerService::class.java))
    }

    internal inner class PlayerServiceConnection : ServiceConnection {
        private var playAfterConnect = false

        fun doPlayAfterConnect(playAfterConnection: Boolean) {
            this.playAfterConnect = playAfterConnection
        }

        override fun onServiceDisconnected(compName: ComponentName) {
            if (DEBUG) {
                Log.d(TAG, "Player service is disconnected")
            }

            val context: Context = commonContext
            unbind(context)
        }

        override fun onServiceConnected(compName: ComponentName, service: IBinder) {
            if (DEBUG) {
                Log.d(TAG, "Player service is connected")
            }
            val localBinder = service as LocalBinder

            playerService = localBinder.service
            player = localBinder.getPlayer()
            listener?.onServiceConnected(player, playerService, playAfterConnect)
            startPlayerListener()
        }
    }

    private fun bind(context: Context) {
        if (DEBUG) {
            Log.d(TAG, "bind() called")
        }

        val serviceIntent = Intent(context, PlayerService::class.java)
        isBound = context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!isBound) {
            context.unbindService(serviceConnection)
        }
    }

    private fun unbind(context: Context) {
        if (DEBUG) {
            Log.d(TAG, "unbind() called")
        }

        if (isBound) {
            context.unbindService(serviceConnection)
            isBound = false
            stopPlayerListener()
            playerService = null
            player = null
            listener?.onServiceDisconnected()
        }
    }

    private fun startPlayerListener() {
        player?.setFragmentListener(internalListener)
    }

    private fun stopPlayerListener() {
        player?.removeFragmentListener(internalListener)
    }

    private val internalListener: PlayerServiceEventListener = object : PlayerServiceEventListener {
        override fun onViewCreated() {
            listener?.onViewCreated()
        }

        override fun onFullscreenStateChanged(fullscreen: Boolean) {
            listener?.onFullscreenStateChanged(fullscreen)
        }

        override fun onScreenRotationButtonClicked() {
            listener?.onScreenRotationButtonClicked()
        }

        override fun onMoreOptionsLongClicked() {
            listener?.onMoreOptionsLongClicked()
        }

        override fun onPlayerError(error: PlaybackException?, isCatchableException: Boolean) {
            listener?.onPlayerError(error, isCatchableException)
        }

        override fun hideSystemUiIfNeeded() {
            listener?.hideSystemUiIfNeeded()
        }

        override fun onQueueUpdate(queue: PlayQueue?) {
            listener?.onQueueUpdate(queue)
        }

        override fun onPlaybackUpdate(state: Int, repeatMode: Int, shuffled: Boolean, parameters: PlaybackParameters?) {
            listener?.onPlaybackUpdate(state, repeatMode, shuffled, parameters)
        }

        override fun onProgressUpdate(currentProgress: Int, duration: Int, bufferPercent: Int) {
            listener?.onProgressUpdate(currentProgress, duration, bufferPercent)
        }

        override fun onMetadataUpdate(info: StreamInfo?, queue: PlayQueue?) {
            listener?.onMetadataUpdate(info, queue)
        }

        override fun onServiceStopped() {
            listener?.onServiceStopped()
            unbind(commonContext)
        }
    }

    companion object {
        @get:Synchronized
        var instance: PlayerHolder? = null
            get() {
                if (field == null) field = PlayerHolder()
                return field
            }
            private set

        private const val DEBUG = MainActivity.DEBUG
        private val TAG: String = PlayerHolder::class.java.simpleName
    }
}
