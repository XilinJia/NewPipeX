/*
 * Copyright 2017 Mauricio Colli <mauriciocolli@outlook.com>
 * InfoCache.java is part of NewPipe
 *
 * License: GPL-3.0+
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.schabi.newpipe.util

import android.util.Log
import androidx.collection.LruCache
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.extractor.Info
import org.schabi.newpipe.extractor.InfoItem.InfoType

class InfoCache private constructor() {
    private val TAG: String = javaClass.simpleName
    fun getFromKey(serviceId: Int, url: String, infoType: InfoType): Info? {
        if (DEBUG) {
            Log.d(TAG, "getFromKey() called with: "
                    + "serviceId = [" + serviceId + "], url = [" + url + "]")
        }
        synchronized(LRU_CACHE) {
            return getInfo(keyOf(serviceId, url, infoType))
        }
    }

    fun putInfo(serviceId: Int, url: String, info: Info, infoType: InfoType) {
        if (DEBUG) {
            Log.d(TAG, "putInfo() called with: info = [$info]")
        }

        val expirationMillis = ServiceHelper.getCacheExpirationMillis(info.serviceId)
        synchronized(LRU_CACHE) {
            val data = CacheData(info, expirationMillis)
            LRU_CACHE.put(keyOf(serviceId, url, infoType), data)
        }
    }

    fun removeInfo(serviceId: Int, url: String, infoType: InfoType) {
        if (DEBUG) {
            Log.d(TAG, "removeInfo() called with: "
                    + "serviceId = [" + serviceId + "], url = [" + url + "]")
        }
        synchronized(LRU_CACHE) {
            LRU_CACHE.remove(keyOf(serviceId, url, infoType))
        }
    }

    fun clearCache() {
        if (DEBUG) {
            Log.d(TAG, "clearCache() called")
        }
        synchronized(LRU_CACHE) {
            LRU_CACHE.evictAll()
        }
    }

    fun trimCache() {
        if (DEBUG) {
            Log.d(TAG, "trimCache() called")
        }
        synchronized(LRU_CACHE) {
            removeStaleCache()
            LRU_CACHE.trimToSize(TRIM_CACHE_TO)
        }
    }

    val size: Long
        get() {
            synchronized(LRU_CACHE) {
                return LRU_CACHE.size().toLong()
            }
        }

    private class CacheData(val info: Info, timeoutMillis: Long) {
        private val expireTimestamp = System.currentTimeMillis() + timeoutMillis

        val isExpired: Boolean
            get() = System.currentTimeMillis() > expireTimestamp
    }

    companion object {
        private val DEBUG = MainActivity.DEBUG

        val instance: InfoCache = InfoCache()
        private const val MAX_ITEMS_ON_CACHE = 60

        /**
         * Trim the cache to this size.
         */
        private const val TRIM_CACHE_TO = 30

        private val LRU_CACHE = LruCache<String, CacheData>(MAX_ITEMS_ON_CACHE)

        private fun keyOf(serviceId: Int, url: String, infoType: InfoType): String {
            return serviceId.toString() + url + infoType.toString()
        }

        private fun removeStaleCache() {
            for ((key, data) in LRU_CACHE.snapshot()) {
                if (data != null && data.isExpired) {
                    LRU_CACHE.remove(key)
                }
            }
        }

        private fun getInfo(key: String): Info? {
            val data = LRU_CACHE[key] ?: return null

            if (data.isExpired) {
                LRU_CACHE.remove(key)
                return null
            }

            return data.info
        }
    }
}
