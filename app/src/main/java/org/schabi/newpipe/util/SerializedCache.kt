package org.schabi.newpipe.util

import android.util.Log
import androidx.collection.LruCache
import org.schabi.newpipe.MainActivity
import java.io.*
import java.util.*

class SerializedCache private constructor() {
    fun <T> take(key: String, type: Class<T>): T? {

        Logd(TAG, "take() called with: key = [$key]")

        synchronized(LRU_CACHE) {
            return if (LRU_CACHE[key] != null) getItem(
                LRU_CACHE.remove(key)!!, type) else null
        }
    }

    fun <T> get(key: String, type: Class<T>): T? {

        Logd(TAG, "get() called with: key = [$key]")

        synchronized(LRU_CACHE) {
            val data = LRU_CACHE[key]
            return if (data != null) getItem(data, type) else null
        }
    }

    fun <T : Serializable?> put(item: T,
                                type: Class<T>
    ): String? {
        val key = UUID.randomUUID().toString()
        return if (put(key, item, type)) key else null
    }

    fun <T : Serializable?> put(key: String, item: T,
                                type: Class<T>
    ): Boolean {

        Logd(TAG, "put() called with: key = [$key], item = [$item]")

        synchronized(LRU_CACHE) {
            try {
                LRU_CACHE.put(key, CacheData(clone(item, type), type))
                return true
            } catch (error: Exception) {
                Log.e(TAG, "Serialization failed for: ", error)
            }
        }
        return false
    }

    fun clear() {

        Logd(TAG, "clear() called")

        synchronized(LRU_CACHE) {
            LRU_CACHE.evictAll()
        }
    }

    fun size(): Long {
        synchronized(LRU_CACHE) {
            return LRU_CACHE.size().toLong()
        }
    }

    private fun <T> getItem(data: CacheData<*>, type: Class<T>): T? {
        return if (type.isAssignableFrom(data.type)) type.cast(data.item) else null
    }

    @Throws(Exception::class)
    private fun <T : Serializable?> clone(item: T,
                                          type: Class<T>
    ): T {
        val bytesOutput = ByteArrayOutputStream()
        ObjectOutputStream(bytesOutput).use { objectOutput ->
            objectOutput.writeObject(item)
            objectOutput.flush()
        }
        val clone = ObjectInputStream(
            ByteArrayInputStream(bytesOutput.toByteArray())).readObject()
        return type.cast(clone)
    }

    private class CacheData<T>(val item: T, val type: Class<T>)
    companion object {
        private val DEBUG = MainActivity.DEBUG
        val instance: SerializedCache = SerializedCache()
        private const val MAX_ITEMS_ON_CACHE = 5
        private val LRU_CACHE = LruCache<String, CacheData<*>>(MAX_ITEMS_ON_CACHE)
        private const val TAG = "SerializedCache"
    }
}
