package us.shandian.giga.postprocessing

import android.util.Log
import org.schabi.newpipe.streams.SrtFromTtmlWriter
import org.schabi.newpipe.streams.io.SharpStream
import java.io.IOException

/**
 * @author kapodamy
 */
internal class TtmlConverter : Postprocessing(false, true, ALGORITHM_TTML_CONVERTER) {
    @Throws(IOException::class)
    override fun process(out: SharpStream?, vararg sources: SharpStream?): Int {
        // check if the subtitle is already in srt and copy, this should never happen
        val format = getArgumentAt(0, "")
        val ignoreEmptyFrames = getArgumentAt(1, "true") == "true"

        when (format) {
            "", "ttml" -> {
                val writer = if (out != null) SrtFromTtmlWriter(out, ignoreEmptyFrames) else null

                try {
                    writer?.build((sources.filterNotNull().toTypedArray())[0])
                } catch (err: Exception) {
                    Log.e(TAG, "subtitle parse failed", err)
                    return if (err is IOException) 1 else 8
                }

                return OK_RESULT.toInt()
            }
            "srt" -> {
                val buffer = ByteArray(8 * 1024)
                var read: Int
                while (((sources.filterNotNull().toTypedArray())[0].read(buffer).also { read = it }) > 0) {
                    out?.write(buffer, 0, read)
                }
                return OK_RESULT.toInt()
            }
            else -> throw UnsupportedOperationException("Can't convert this subtitle, unimplemented format: $format")
        }
    }

    companion object {
        private const val TAG = "TtmlConverter"
    }
}
