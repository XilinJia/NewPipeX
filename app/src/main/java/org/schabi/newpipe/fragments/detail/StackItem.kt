package org.schabi.newpipe.fragments.detail

import org.schabi.newpipe.player.playqueue.PlayQueue
import java.io.Serializable

internal class StackItem(@JvmField val serviceId: Int, @JvmField var url: String,
                         @JvmField var title: String, @JvmField var playQueue: PlayQueue) : Serializable {
    override fun toString(): String {
        return serviceId.toString() + ":" + url + " > " + title
    }
}
