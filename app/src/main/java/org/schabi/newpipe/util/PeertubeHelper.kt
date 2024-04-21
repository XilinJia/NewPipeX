package org.schabi.newpipe.util

import android.content.Context
import androidx.preference.PreferenceManager
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import com.grack.nanojson.JsonWriter
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.peertube.PeertubeInstance

object PeertubeHelper {
    @JvmStatic
    fun getInstanceList(context: Context): List<PeertubeInstance> {
        val sharedPreferences = PreferenceManager
            .getDefaultSharedPreferences(context)
        val savedInstanceListKey = context.getString(R.string.peertube_instance_list_key)
        val savedJson = sharedPreferences.getString(savedInstanceListKey, null)
            ?: return listOf(currentInstance)

        try {
            val array = JsonParser.`object`().from(savedJson).getArray("instances")
            val result: MutableList<PeertubeInstance> = ArrayList()
            for (o in array) {
                if (o is JsonObject) {
                    val instance = o
                    val name = instance.getString("name")
                    val url = instance.getString("url")
                    result.add(PeertubeInstance(url, name))
                }
            }
            return result
        } catch (e: JsonParserException) {
            return listOf(currentInstance)
        }
    }

    @JvmStatic
    fun selectInstance(instance: PeertubeInstance,
                       context: Context
    ): PeertubeInstance {
        val sharedPreferences = PreferenceManager
            .getDefaultSharedPreferences(context)
        val selectedInstanceKey =
            context.getString(R.string.peertube_selected_instance_key)
        val jsonWriter = JsonWriter.string().`object`()
        jsonWriter.value("name", instance.name)
        jsonWriter.value("url", instance.url)
        val jsonToSave = jsonWriter.end().done()
        sharedPreferences.edit().putString(selectedInstanceKey, jsonToSave).apply()
        ServiceList.PeerTube.instance = instance
        return instance
    }

    @JvmStatic
    val currentInstance: PeertubeInstance
        get() = ServiceList.PeerTube.instance
}
