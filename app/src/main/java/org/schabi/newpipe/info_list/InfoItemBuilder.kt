package org.schabi.newpipe.info_list

import android.content.Context
import android.view.View
import android.view.ViewGroup
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.InfoItem.InfoType
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.info_list.holder.*
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.util.OnClickGesture

/*
* Created by Christian Schabesberger on 26.09.16.
* <p>
* Copyright (C) Christian Schabesberger 2016 <chris.schabesberger@mailbox.org>
* InfoItemBuilder.java is part of NewPipe.
* </p>
* <p>
* NewPipe is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
* </p>
* <p>
* NewPipe is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
* </p>
* <p>
* You should have received a copy of the GNU General Public License
* along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
* </p>
*/
class InfoItemBuilder(val context: Context) {
    @JvmField
    var onStreamSelectedListener: OnClickGesture<StreamInfoItem>? = null
    @JvmField
    var onChannelSelectedListener: OnClickGesture<ChannelInfoItem>? = null
    @JvmField
    var onPlaylistSelectedListener: OnClickGesture<PlaylistInfoItem>? = null
    @JvmField
    var onCommentsSelectedListener: OnClickGesture<CommentsInfoItem>? = null

    @JvmOverloads
    fun buildView(parent: ViewGroup, infoItem: InfoItem,
                  historyRecordManager: HistoryRecordManager?,
                  useMiniVariant: Boolean = false
    ): View {
        val holder =
            holderFromInfoType(parent, infoItem.infoType, useMiniVariant)
        holder.updateFromItem(infoItem, historyRecordManager)
        return holder.itemView
    }

    private fun holderFromInfoType(parent: ViewGroup,
                                   infoType: InfoType,
                                   useMiniVariant: Boolean
    ): InfoItemHolder {
        return when (infoType) {
            InfoType.STREAM -> if (useMiniVariant) StreamMiniInfoItemHolder(this, parent)
            else StreamInfoItemHolder(this, parent)
            InfoType.CHANNEL -> if (useMiniVariant) ChannelMiniInfoItemHolder(this, parent)
            else ChannelInfoItemHolder(this, parent)
            InfoType.PLAYLIST -> if (useMiniVariant) PlaylistMiniInfoItemHolder(this, parent)
            else PlaylistInfoItemHolder(this, parent)
            InfoType.COMMENT -> CommentInfoItemHolder(this, parent)
            else -> throw RuntimeException("InfoType not expected = " + infoType.name)
        }
    }
}
