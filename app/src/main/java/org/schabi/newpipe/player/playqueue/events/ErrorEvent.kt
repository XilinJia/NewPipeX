package org.schabi.newpipe.player.playqueue.events

class ErrorEvent(@JvmField val errorIndex: Int, @JvmField val queueIndex: Int) : PlayQueueEvent {
    override fun type(): PlayQueueEventType? {
        return PlayQueueEventType.ERROR
    }
}
