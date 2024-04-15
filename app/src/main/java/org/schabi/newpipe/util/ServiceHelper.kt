package org.schabi.newpipe.util

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.services.peertube.PeertubeInstance
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.function.Function

object ServiceHelper {
    private val DEFAULT_FALLBACK_SERVICE: StreamingService = ServiceList.YouTube

    @JvmStatic
    @DrawableRes
    fun getIcon(serviceId: Int): Int {
        return when (serviceId) {
            0 -> R.drawable.ic_smart_display
            1 -> R.drawable.ic_cloud
            2 -> R.drawable.ic_placeholder_media_ccc
            3 -> R.drawable.ic_placeholder_peertube
            4 -> R.drawable.ic_placeholder_bandcamp
            else -> R.drawable.ic_circle
        }
    }

    @JvmStatic
    fun getTranslatedFilterString(filter: String, c: Context): String {
        return when (filter) {
            "all" -> c.getString(R.string.all)
            "videos", "sepia_videos", "music_videos" -> c.getString(R.string.videos_string)
            "channels" -> c.getString(R.string.channels)
            "playlists", "music_playlists" -> c.getString(R.string.playlists)
            "tracks" -> c.getString(R.string.tracks)
            "users" -> c.getString(R.string.users)
            "conferences" -> c.getString(R.string.conferences)
            "events" -> c.getString(R.string.events)
            "music_songs" -> c.getString(R.string.songs)
            "music_albums" -> c.getString(R.string.albums)
            "music_artists" -> c.getString(R.string.artists)
            else -> filter
        }
    }

    /**
     * Get a resource string with instructions for importing subscriptions for each service.
     *
     * @param serviceId service to get the instructions for
     * @return the string resource containing the instructions or -1 if the service don't support it
     */
    @JvmStatic
    @StringRes
    fun getImportInstructions(serviceId: Int): Int {
        return when (serviceId) {
            0 -> R.string.import_youtube_instructions
            1 -> R.string.import_soundcloud_instructions
            else -> -1
        }
    }

    /**
     * For services that support importing from a channel url, return a hint that will
     * be used in the EditText that the user will type in his channel url.
     *
     * @param serviceId service to get the hint for
     * @return the hint's string resource or -1 if the service don't support it
     */
    @JvmStatic
    @StringRes
    fun getImportInstructionsHint(serviceId: Int): Int {
        return when (serviceId) {
            1 -> R.string.import_soundcloud_instructions_hint
            else -> -1
        }
    }

    @JvmStatic
    fun getSelectedServiceId(context: Context): Int {
        return Optional.ofNullable(getSelectedService(context))
            .orElse(DEFAULT_FALLBACK_SERVICE)
            .serviceId
    }

    @JvmStatic
    fun getSelectedService(context: Context): StreamingService? {
        val serviceName = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(context.getString(R.string.current_service_key),
                context.getString(R.string.default_service_value))

        return try {
            NewPipe.getService(serviceName)
        } catch (e: ExtractionException) {
            null
        }
    }

    @JvmStatic
    fun getNameOfServiceById(serviceId: Int): String {
        return ServiceList.all().stream()
            .filter { s: StreamingService -> s.serviceId == serviceId }
            .findFirst()
            .map<StreamingService.ServiceInfo> { obj: StreamingService -> obj.serviceInfo }
            .map<String>(Function<StreamingService.ServiceInfo, String> { it.name })
            .orElse("<unknown>")
    }

    /**
     * @param serviceId the id of the service
     * @return the service corresponding to the provided id
     * @throws java.util.NoSuchElementException if there is no service with the provided id
     */
    @JvmStatic
    fun getServiceById(serviceId: Int): StreamingService {
        return ServiceList.all().stream()
            .filter { s: StreamingService -> s.serviceId == serviceId }
            .findFirst()
            .orElseThrow()
    }

    @JvmStatic
    fun setSelectedServiceId(context: Context, serviceId: Int) {
        var serviceName = try {
            NewPipe.getService(serviceId).serviceInfo.name
        } catch (e: ExtractionException) {
            DEFAULT_FALLBACK_SERVICE.serviceInfo.name
        }

        setSelectedServicePreferences(context, serviceName)
    }

    private fun setSelectedServicePreferences(context: Context,
                                              serviceName: String
    ) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(context.getString(R.string.current_service_key), serviceName).apply()
    }

    @JvmStatic
    fun getCacheExpirationMillis(serviceId: Int): Long {
        return if (serviceId == ServiceList.SoundCloud.serviceId) {
            TimeUnit.MILLISECONDS.convert(5, TimeUnit.MINUTES)
        } else {
            TimeUnit.MILLISECONDS.convert(1, TimeUnit.HOURS)
        }
    }

    fun initService(context: Context, serviceId: Int) {
        if (serviceId == ServiceList.PeerTube.serviceId) {
            val sharedPreferences = PreferenceManager
                .getDefaultSharedPreferences(context)
            val json = sharedPreferences.getString(context.getString(
                R.string.peertube_selected_instance_key), null)
            if (null == json) {
                return
            }

            val jsonObject: JsonObject
            try {
                jsonObject = JsonParser.`object`().from(json)
            } catch (e: JsonParserException) {
                return
            }
            val name = jsonObject.getString("name")
            val url = jsonObject.getString("url")
            val instance = PeertubeInstance(url, name)
            ServiceList.PeerTube.instance = instance
        }
    }

    @JvmStatic
    fun initServices(context: Context) {
        for (s in ServiceList.all()) {
            initService(context, s.serviceId)
        }
    }
}
