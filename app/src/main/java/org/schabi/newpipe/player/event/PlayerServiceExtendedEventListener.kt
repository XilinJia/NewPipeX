package org.schabi.newpipe.player.event

import org.schabi.newpipe.player.PlayerManager
import org.schabi.newpipe.player.PlayerService

interface PlayerServiceExtendedEventListener : PlayerServiceEventListener {
    fun onServiceConnected(playerManager: PlayerManager?, playerService: PlayerService?, playAfterConnect: Boolean)

    fun onServiceDisconnected()
}
