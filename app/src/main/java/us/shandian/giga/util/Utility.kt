package us.shandian.giga.util

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import okio.ByteString
import org.schabi.newpipe.R
import org.schabi.newpipe.streams.io.SharpInputStream
import org.schabi.newpipe.streams.io.StoredFileHelper
import java.io.*
import java.net.HttpURLConnection
import java.util.*

object Utility {
    fun formatBytes(bytes: Long): String {
        val locale = Locale.getDefault()
        return when {
            bytes < 1024 -> {
                String.format(locale, "%d B", bytes)
            }
            bytes < 1024 * 1024 -> {
                String.format(locale, "%.2f kB", bytes / 1024.0)
            }
            bytes < 1024 * 1024 * 1024 -> {
                String.format(locale, "%.2f MB", bytes / 1024.0 / 1024.0)
            }
            else -> {
                String.format(locale, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
            }
        }
    }

    fun formatSpeed(speed: Double): String {
        val locale = Locale.getDefault()
        return when {
            speed < 1024 -> {
                String.format(locale, "%.2f B/s", speed)
            }
            speed < 1024 * 1024 -> {
                String.format(locale, "%.2f kB/s", speed / 1024)
            }
            speed < 1024 * 1024 * 1024 -> {
                String.format(locale, "%.2f MB/s", speed / 1024 / 1024)
            }
            else -> {
                String.format(locale, "%.2f GB/s", speed / 1024 / 1024 / 1024)
            }
        }
    }

    fun writeToFile(file: File, serializable: Serializable) {
        try {
            ObjectOutputStream(BufferedOutputStream(FileOutputStream(file))).use { objectOutputStream ->
                objectOutputStream.writeObject(serializable)
            }
        } catch (e: Exception) {
            //nothing to do
        }
        //nothing to do
    }

    fun <T> readFromFile(file: File?): T? {
        var `object`: T?

        try {
            ObjectInputStream(FileInputStream(file)).use { objectInputStream ->
                `object` = objectInputStream.readObject() as T
            }
        } catch (e: Exception) {
            Log.e("Utility", "Failed to deserialize the object", e)
            `object` = null
        }

        return `object`
    }

    fun getFileExt(url: String): String? {
        var url = url
        var index: Int
        if ((url.indexOf("?").also { index = it }) > -1) {
            url = url.substring(0, index)
        }

        index = url.lastIndexOf(".")
        if (index == -1) return null

        var ext = url.substring(index)
        if ((ext.indexOf("%").also { index = it }) > -1) ext = ext.substring(0, index)

        if ((ext.indexOf("/").also { index = it }) > -1) ext = ext.substring(0, index)

        return ext.lowercase(Locale.getDefault())
    }

    fun getFileType(kind: Char, file: String): FileType {
        when (kind) {
            'v' -> return FileType.VIDEO
            'a' -> return FileType.MUSIC
            's' -> return FileType.SUBTITLE
        }
        when {
            file.endsWith(".srt") || file.endsWith(".vtt") || file.endsWith(".ssa") -> {
                return FileType.SUBTITLE
            }
            file.endsWith(".mp3") || file.endsWith(".wav") || file.endsWith(".flac") || file.endsWith(".m4a") || file.endsWith(
                ".opus") -> {
                return FileType.MUSIC
            }
            file.endsWith(".mp4") || file.endsWith(".mpeg") || file.endsWith(".rm") || file.endsWith(".rmvb")
                    || file.endsWith(".flv") || file.endsWith(".webp") || file.endsWith(".webm") -> {
                return FileType.VIDEO
            }
            else -> return FileType.UNKNOWN
        }
    }

    @ColorInt
    fun getBackgroundForFileType(ctx: Context?, type: FileType?): Int {
        val colorRes = when (type) {
            FileType.MUSIC -> R.color.audio_left_to_load_color
            FileType.VIDEO -> R.color.video_left_to_load_color
            FileType.SUBTITLE -> R.color.subtitle_left_to_load_color
            else -> R.color.gray
        }
        return ContextCompat.getColor(ctx!!, colorRes)
    }

    @ColorInt
    fun getForegroundForFileType(ctx: Context?, type: FileType?): Int {
        val colorRes = when (type) {
            FileType.MUSIC -> R.color.audio_already_load_color
            FileType.VIDEO -> R.color.video_already_load_color
            FileType.SUBTITLE -> R.color.subtitle_already_load_color
            else -> R.color.gray
        }
        return ContextCompat.getColor(ctx!!, colorRes)
    }

    @DrawableRes
    fun getIconForFileType(type: FileType?): Int {
        return when (type) {
            FileType.MUSIC -> R.drawable.ic_headset
            FileType.VIDEO -> R.drawable.ic_movie
            FileType.SUBTITLE -> R.drawable.ic_subtitles
            else -> R.drawable.ic_movie
        }
    }

    @OptIn(UnstableApi::class) @Throws(IOException::class)
    fun checksum(source: StoredFileHelper, algorithmId: Int): String {
        var byteString: ByteString
        SharpInputStream(source.stream).use { inputStream ->
            byteString = ByteString.of(*Util.toByteArray(inputStream))
        }
        when (algorithmId) {
            R.id.md5 -> {
                byteString = byteString.md5()
            }
            R.id.sha1 -> {
                byteString = byteString.sha1()
            }
        }
        return byteString.hex()
    }

    fun mkdir(p: File, allDirs: Boolean): Boolean {
        if (p.exists()) return true

        if (allDirs) p.mkdirs()
        else p.mkdir()

        return p.exists()
    }

    fun getContentLength(connection: HttpURLConnection): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return connection.contentLengthLong

        try {
            return connection.getHeaderField("Content-Length").toLong()
        } catch (err: Exception) {
            // nothing to do
        }

        return -1
    }

    /**
     * Get the content length of the entire file even if the HTTP response is partial
     * (response code 206).
     * @param connection http connection
     * @return content length
     */
    fun getTotalContentLength(connection: HttpURLConnection): Long {
        try {
            if (connection.responseCode == 206) {
                val rangeStr = connection.getHeaderField("Content-Range")
                val bytesStr = rangeStr.split("/".toRegex(), limit = 2).toTypedArray()[1]
                return bytesStr.toLong()
            } else {
                return getContentLength(connection)
            }
        } catch (err: Exception) {
            // nothing to do
        }

        return -1
    }

    private fun pad(number: Int): String {
        return if (number < 10) ("0$number") else number.toString()
    }

    fun stringifySeconds(seconds: Long): String {
        val h = Math.floorDiv(seconds, 3600).toInt()
        val m = Math.floorDiv(seconds - (h * 3600L), 60).toInt()
        val s = (seconds - (h * 3600) - (m * 60)).toInt()

        var str = ""

        if (h < 1 && m < 1) {
            str = "00:"
        } else {
            if (h > 0) str = pad(h) + ":"
            if (m > 0) str += pad(m) + ":"
        }

        return str + pad(s)
    }

    enum class FileType {
        VIDEO,
        MUSIC,
        SUBTITLE,
        UNKNOWN
    }
}
