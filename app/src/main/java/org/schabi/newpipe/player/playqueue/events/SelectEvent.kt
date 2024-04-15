package org.schabi.newpipe.player.playqueue.events

class SelectEvent(@JvmField val oldIndex: Int, @JvmField val newIndex: Int) : PlayQueueEvent {
    override fun type(): PlayQueueEventType? {
        return PlayQueueEventType.SELECT
    }
}
