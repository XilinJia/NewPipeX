package org.schabi.newpipe.ui.list

import org.schabi.newpipe.player.playqueue.PlayQueue

/**
 * Interface for `R.layout.playlist_control` view holders
 * to give access to the play queue.
 */
interface PlaylistControlViewHolder {
    val playQueue: PlayQueue?
}
