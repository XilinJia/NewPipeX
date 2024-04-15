package org.schabi.newpipe.util

import android.content.Context
import org.schabi.newpipe.R

/**
 * Created by Christian Schabesberger on 28.09.17.
 * KioskTranslator.java is part of NewPipe.
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
 * along with NewPipe.  If not, see <http:></http:>//www.gnu.org/licenses/>.
 *
 */
object KioskTranslator {
    @JvmStatic
    fun getTranslatedKioskName(kioskId: String, c: Context): String {
        return when (kioskId) {
            "Trending" -> c.getString(R.string.trending)
            "Top 50" -> c.getString(R.string.top_50)
            "New & hot" -> c.getString(R.string.new_and_hot)
            "Local" -> c.getString(R.string.local)
            "Recently added" -> c.getString(R.string.recently_added)
            "Most liked" -> c.getString(R.string.most_liked)
            "conferences" -> c.getString(R.string.conferences)
            "recent" -> c.getString(R.string.recent)
            "live" -> c.getString(R.string.duration_live)
            "Featured" -> c.getString(R.string.featured)
            "Radio" -> c.getString(R.string.radio)
            else -> kioskId
        }
    }

    @JvmStatic
    fun getKioskIcon(kioskId: String?): Int {
        return when (kioskId) {
            "Trending", "Top 50", "New & hot", "conferences" -> R.drawable.ic_whatshot
            "Local" -> R.drawable.ic_home
            "Recently added", "recent" -> R.drawable.ic_add_circle_outline
            "Most liked" -> R.drawable.ic_thumb_up
            "live" -> R.drawable.ic_live_tv
            "Featured" -> R.drawable.ic_stars
            "Radio" -> R.drawable.ic_radio
            else -> 0
        }
    }
}
