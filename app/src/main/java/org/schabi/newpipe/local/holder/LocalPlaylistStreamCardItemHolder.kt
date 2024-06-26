package org.schabi.newpipe.local.holder

import android.view.ViewGroup
import org.schabi.newpipe.R
import org.schabi.newpipe.local.LocalItemBuilder

/**
 * Local playlist stream UI. This also includes a handle to rearrange the videos.
 */
class LocalPlaylistStreamCardItemHolder(infoItemBuilder: LocalItemBuilder?,
                                        parent: ViewGroup?
) : LocalPlaylistStreamItemHolder(infoItemBuilder, R.layout.list_stream_playlist_card_item, parent)
