/*
 * Copyright 2018 Mauricio Colli <mauriciocolli@outlook.com>
 * ImportExportJsonHelper.java is part of NewPipe
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
package org.schabi.newpipe.ui.local.subscription.services

import com.grack.nanojson.*
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.extractor.subscription.SubscriptionExtractor.InvalidSourceException
import org.schabi.newpipe.extractor.subscription.SubscriptionItem
import java.io.InputStream
import java.io.OutputStream

/**
 * A JSON implementation capable of importing and exporting subscriptions, it has the advantage
 * of being able to transfer subscriptions to any device.
 */
object ImportExportJsonHelper {
    /*//////////////////////////////////////////////////////////////////////////
    // Json implementation
    ////////////////////////////////////////////////////////////////////////// */
    private const val JSON_APP_VERSION_KEY = "app_version"
    private const val JSON_APP_VERSION_INT_KEY = "app_version_int"

    private const val JSON_SUBSCRIPTIONS_ARRAY_KEY = "subscriptions"

    private const val JSON_SERVICE_ID_KEY = "service_id"
    private const val JSON_URL_KEY = "url"
    private const val JSON_NAME_KEY = "name"

    /**
     * Read a JSON source through the input stream.
     *
     * @param in            the input stream (e.g. a file)
     * @param eventListener listener for the events generated
     * @return the parsed subscription items
     */
    @JvmStatic
    @Throws(InvalidSourceException::class)
    fun readFrom(
            `in`: InputStream?, eventListener: ImportExportEventListener?
    ): List<SubscriptionItem> {
        if (`in` == null) {
            throw InvalidSourceException("input is null")
        }

        val channels: MutableList<SubscriptionItem> = ArrayList()

        try {
            val parentObject = JsonParser.`object`().from(`in`)

            if (!parentObject.has(JSON_SUBSCRIPTIONS_ARRAY_KEY)) {
                throw InvalidSourceException("Channels array is null")
            }

            val channelsArray = parentObject.getArray(JSON_SUBSCRIPTIONS_ARRAY_KEY)

            eventListener?.onSizeReceived(channelsArray.size)

            for (o in channelsArray) {
                if (o is JsonObject) {
                    val itemObject = o
                    val serviceId = itemObject.getInt(JSON_SERVICE_ID_KEY, 0)
                    val url = itemObject.getString(JSON_URL_KEY)
                    val name = itemObject.getString(JSON_NAME_KEY)

                    if (url != null && name != null && !url.isEmpty() && !name.isEmpty()) {
                        channels.add(SubscriptionItem(serviceId, url, name))
                        eventListener?.onItemCompleted(name)
                    }
                }
            }
        } catch (e: Throwable) {
            throw InvalidSourceException("Couldn't parse json", e)
        }

        return channels
    }

    /**
     * Write the subscriptions items list as JSON to the output.
     *
     * @param items         the list of subscriptions items
     * @param out           the output stream (e.g. a file)
     * @param eventListener listener for the events generated
     */
    fun writeTo(items: List<SubscriptionItem>, out: OutputStream?,
                eventListener: ImportExportEventListener?
    ) {
        val writer = JsonWriter.on(out)
        writeTo(items, writer, eventListener)
        writer.done()
    }

    /**
     * @see .writeTo
     * @param items         the list of subscriptions items
     * @param writer        the output [JsonAppendableWriter]
     * @param eventListener listener for the events generated
     */
    fun writeTo(items: List<SubscriptionItem>,
                writer: JsonAppendableWriter,
                eventListener: ImportExportEventListener?
    ) {
        eventListener?.onSizeReceived(items.size)

        writer.`object`()

        writer.value(JSON_APP_VERSION_KEY, BuildConfig.VERSION_NAME)
        writer.value(JSON_APP_VERSION_INT_KEY, BuildConfig.VERSION_CODE)

        writer.array(JSON_SUBSCRIPTIONS_ARRAY_KEY)
        for (item in items) {
            writer.`object`()
            writer.value(JSON_SERVICE_ID_KEY, item.serviceId)
            writer.value(JSON_URL_KEY, item.url)
            writer.value(JSON_NAME_KEY, item.name)
            writer.end()

            eventListener?.onItemCompleted(item.name)
        }
        writer.end()

        writer.end()
    }
}
