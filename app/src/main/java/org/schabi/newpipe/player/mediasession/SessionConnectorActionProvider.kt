package org.schabi.newpipe.player.mediasession

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.common.Player
//import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector.CustomActionProvider
import org.schabi.newpipe.player.notification.NotificationActionData
import java.lang.ref.WeakReference

//class SessionConnectorActionProvider(private val data: NotificationActionData?, context: Context
//) : MediaSessionConnector.CustomActionProvider {
//    private val context = WeakReference(context)
//
//    override fun onCustomAction(player: Player,
//                                action: String,
//                                extras: Bundle?
//    ) {
//        val actualContext = context.get()
//        actualContext?.sendBroadcast(Intent(action))
//    }
//
//    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
//        return if (data != null) PlaybackStateCompat.CustomAction.Builder(data.action(), data.name(), data.icon()).build()
//        else null
//    }
//}
