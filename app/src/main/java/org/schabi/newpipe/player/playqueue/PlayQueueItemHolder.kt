package org.schabi.newpipe.player.playqueue

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R

/**
 * Created by Christian Schabesberger on 01.08.16.
 *
 *
 * Copyright (C) Christian Schabesberger 2016 <chris.schabesberger></chris.schabesberger>@mailbox.org>
 * StreamInfoItemHolder.java is part of NewPipe.
 *
 *
 *
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *
 *
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *
 *
 * You should have received a copy of the GNU General Public License
 * along with NewPipe. If not, see <http:></http:>//www.gnu.org/licenses/>.
 *
 */
class PlayQueueItemHolder internal constructor(v: View) : RecyclerView.ViewHolder(v) {
    val itemVideoTitleView: TextView = v.findViewById(R.id.itemVideoTitleView)
    val itemDurationView: TextView = v.findViewById(R.id.itemDurationView)
    val itemAdditionalDetailsView: TextView = v.findViewById(R.id.itemAdditionalDetails)

    val itemThumbnailView: ImageView = v.findViewById(R.id.itemThumbnailView)
    val itemHandle: ImageView = v.findViewById(R.id.itemHandle)

    val itemRoot: View = v.findViewById(R.id.itemRoot)
}
