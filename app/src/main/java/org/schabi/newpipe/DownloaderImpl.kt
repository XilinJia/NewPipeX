package org.schabi.newpipe

import android.content.Context
import androidx.preference.PreferenceManager
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request.Builder
import okhttp3.RequestBody
import org.schabi.newpipe.error.ReCaptchaActivity
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.util.InfoCache
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors
import java.util.stream.Stream

class DownloaderImpl private constructor(builder: OkHttpClient.Builder) : Downloader() {
    private val mCookies: MutableMap<String, String> = HashMap()
    private val client: OkHttpClient = builder
        .readTimeout(30, TimeUnit.SECONDS)
        //                .cache(new Cache(new File(context.getExternalCacheDir(), "okhttp"), 16 * 1024 * 1024))
        .build()

    private fun getCookies(url: String): String {
        val youtubeCookie = if (url.contains(YOUTUBE_DOMAIN)) getCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY) else null

        // Recaptcha cookie is always added TODO: not sure if this is necessary
        return Stream.of(youtubeCookie, getCookie(ReCaptchaActivity.RECAPTCHA_COOKIES_KEY))
            .filter { obj: String? -> Objects.nonNull(obj) }
            .flatMap { cookies: String? -> Arrays.stream(cookies!!.split("; *".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) }
            .distinct()
            .collect(Collectors.joining("; "))
    }

    private fun getCookie(key: String): String? {
        return mCookies[key]
    }

    fun setCookie(key: String, cookie: String) {
        mCookies[key] = cookie
    }

    private fun removeCookie(key: String) {
        mCookies.remove(key)
    }

    fun updateYoutubeRestrictedModeCookies(context: Context) {
        val restrictedModeEnabledKey = context.getString(R.string.youtube_restricted_mode_enabled)
        val restrictedModeEnabled = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(restrictedModeEnabledKey, false)
        updateYoutubeRestrictedModeCookies(restrictedModeEnabled)
    }

    private fun updateYoutubeRestrictedModeCookies(youtubeRestrictedModeEnabled: Boolean) {
        if (youtubeRestrictedModeEnabled) setCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY, YOUTUBE_RESTRICTED_MODE_COOKIE)
        else removeCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY)
        InfoCache.instance.clearCache()
    }

    /**
     * Get the size of the content that the url is pointing by firing a HEAD request.
     *
     * @param url an url pointing to the content
     * @return the size of the content, in bytes
     */
    @Throws(IOException::class)
    fun getContentLength(url: String?): Long {
        try {
            val response = head(url)
            return response.getHeader("Content-Length")!!.toLong()
        } catch (e: NumberFormatException) {
            throw IOException("Invalid content length", e)
        } catch (e: ReCaptchaException) {
            throw IOException(e)
        }
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        var requestBody: RequestBody? = null
        if (dataToSend != null) requestBody = RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), dataToSend)

        val requestBuilder: Builder = Builder()
            .method(httpMethod, requestBody).url(url)
            .addHeader("User-Agent", USER_AGENT)

        val cookies = getCookies(url)
        if (cookies.isNotEmpty()) requestBuilder.addHeader("Cookie", cookies)

        for ((headerName, headerValueList) in headers) {
            when {
                headerValueList.size > 1 -> {
                    requestBuilder.removeHeader(headerName)
                    for (headerValue in headerValueList) {
                        requestBuilder.addHeader(headerName, headerValue)
                    }
                }
                headerValueList.size == 1 -> requestBuilder.header(headerName, headerValueList[0])
            }
        }

        val response = client.newCall(requestBuilder.build()).execute()
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val body = response.body
        val responseBodyToReturn: String? = body?.string()

        val latestUrl = response.request.url.toString()
        return Response(response.code, response.message, response.headers.toMultimap(), responseBodyToReturn, latestUrl)
    }

    companion object {
        const val USER_AGENT: String = "Mozilla/5.0 (Windows NT 10.0; rv:91.0) Gecko/20100101 Firefox/91.0"
        const val YOUTUBE_RESTRICTED_MODE_COOKIE_KEY: String = "youtube_restricted_mode_key"
        const val YOUTUBE_RESTRICTED_MODE_COOKIE: String = "PREF=f2=8000000"
        const val YOUTUBE_DOMAIN: String = "youtube.com"

        @JvmStatic
        var instance: DownloaderImpl? = null
            private set

        /**
         * It's recommended to call exactly once in the entire lifetime of the application.
         *
         * @param builder if null, default builder will be used
         * @return a new instance of [DownloaderImpl]
         */
        fun init(builder: OkHttpClient.Builder?): DownloaderImpl? {
            instance = DownloaderImpl(builder ?: OkHttpClient.Builder())
            return instance
        }
    }
}
