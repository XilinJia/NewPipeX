package us.shandian.giga.postprocessing

import android.util.Log
import org.schabi.newpipe.streams.io.SharpStream
import us.shandian.giga.get.DownloadMission
import us.shandian.giga.io.ChunkFileInputStream
import us.shandian.giga.io.CircularFileWriter
import us.shandian.giga.io.CircularFileWriter.OffsetChecker
import us.shandian.giga.io.CircularFileWriter.WriteErrorHandle
import us.shandian.giga.io.ProgressReport
import java.io.File
import java.io.IOException
import java.io.Serializable
import kotlin.math.max

abstract class Postprocessing // for debugging only
internal constructor(
        /**
         * Indicates whether the selected algorithm needs space reserved at the beginning of the file
         */
        var reserveSpace: Boolean,
        /**
         * Get a boolean value that indicate if the given algorithm work on the same
         * file
         */
        @JvmField var worksOnSameFile: Boolean,
        /**
         * Gets the given algorithm short name
         */
        private val name: String
) : Serializable {
    private var args: Array<String>? = null

    @Transient
    private var mission: DownloadMission? = null

    @Transient
    private var tempFile: File? = null

    fun setTemporalDir(directory: File) {
        val rnd = (Math.random() * 100000.0f).toInt().toLong()
        tempFile = File(directory, rnd.toString() + "_" + System.nanoTime() + ".tmp")
    }

    fun cleanupTemporalDir() {
        if (tempFile != null && tempFile!!.exists()) {
            try {
                tempFile!!.delete()
            } catch (e: Exception) {
                // nothing to do
            }
        }
    }


    @Throws(IOException::class)
    fun run(target: DownloadMission?) {
        this.mission = target

        var result: Int
        var finalLength: Long = -1

        mission!!.done = 0

        val length = mission!!.storage!!.length() - mission!!.offsets[0]
        mission!!.length = max(length, mission!!.nearLength)

        val readProgress: ProgressReport = ProgressReport { position: Long ->
            var position = position
            position -= mission!!.offsets[0]
            if (position > mission!!.done) mission!!.done = position
        }

        if (worksOnSameFile) {
            val sources = arrayOfNulls<ChunkFileInputStream>(mission!!.urls.size)
            try {
                var i = 0
                var j = 1
                while (i < sources.size) {
                    val source = mission!!.storage!!.stream
                    val end = if (j < sources.size) mission!!.offsets[j] else source.length()

                    sources[i] = ChunkFileInputStream(source, mission!!.offsets[i], end, readProgress)
                    i++
                    j++
                }

                if (test(*sources)) {
                    for (source in sources) source!!.rewind()

                    val checker: OffsetChecker = OffsetChecker {
                        for (source in sources) {
                            /*
                             * WARNING: never use rewind() in any chunk after any writing (especially on first chunks)
                             *          or the CircularFileWriter can lead to unexpected results
                             */
                            if (source!!.isClosed || source.available() < 1) {
                                continue  // the selected source is not used anymore
                            }

                            return@OffsetChecker source.filePointer - 1
                        }
                        -1
                    }

                    CircularFileWriter(mission!!.storage!!.stream, tempFile!!, checker).use { out ->
                        out.onProgress = ProgressReport { position: Long -> mission!!.done = position }
                        out.onWriteError = WriteErrorHandle { err: Exception? ->
                            mission!!.psState = 3
                            mission!!.notifyError(DownloadMission.ERROR_POSTPROCESSING_HOLD, err)

                            try {
                                synchronized(this) {
                                    while (mission!!.psState == 3) (this as Object).wait()
                                }
                            } catch (e: InterruptedException) {
                                // nothing to do
                                Log.e(javaClass.simpleName, "got InterruptedException")
                            }
                            mission!!.errCode == DownloadMission.ERROR_NOTHING
                        }

                        result = process(out, *sources)
                        if (result == OK_RESULT.toInt()) finalLength = out.finalizeFile()
                    }
                } else {
                    result = OK_RESULT.toInt()
                }
            } finally {
                for (source in sources) {
                    if (source != null && !source.isClosed) source.close()
                }
                tempFile?.delete()
                tempFile = null
            }
        } else {
            result = if (test()) process(null) else OK_RESULT.toInt()
        }

        if (result == OK_RESULT.toInt()) {
            if (finalLength != -1L) mission!!.length = finalLength
        } else {
            mission!!.errCode = DownloadMission.ERROR_POSTPROCESSING
            mission!!.errObject = RuntimeException("post-processing algorithm returned $result")
        }

        if (result != OK_RESULT.toInt() && worksOnSameFile) mission!!.storage!!.delete()

        this.mission = null
    }

    /**
     * Test if the post-processing algorithm can be skipped
     *
     * @param sources files to be processed
     * @return `true` if the post-processing is required, otherwise, `false`
     * @throws IOException if an I/O error occurs.
     */
    @Throws(IOException::class)
    open fun test(vararg sources: SharpStream?): Boolean {
        return true
    }

    /**
     * Abstract method to execute the post-processing algorithm
     *
     * @param out     output stream
     * @param sources files to be processed
     * @return an error code, `OK_RESULT` means the operation was successful
     * @throws IOException if an I/O error occurs.
     */
    @Throws(IOException::class)
    abstract fun process(out: SharpStream?, vararg sources: SharpStream?): Int

    fun getArgumentAt(index: Int, defaultValue: String): String {
        if (args == null || index >= args!!.size) return defaultValue

        return args!![index]
    }

    override fun toString(): String {
        val str = StringBuilder()

        str.append("{ name=").append(name).append('[')

        if (args != null) {
            for (arg in args!!) {
                str.append(", ")
                str.append(arg)
            }
            str.delete(0, 1)
        }

        return str.append("] }").toString()
    }

    companion object {
        @JvmField
        @Transient
        val OK_RESULT: Byte = DownloadMission.ERROR_NOTHING.toByte()

        @JvmField
        @Transient
        val ALGORITHM_TTML_CONVERTER: String = "ttml"

        @JvmField
        @Transient
        val ALGORITHM_WEBM_MUXER: String = "webm"

        @JvmField
        @Transient
        val ALGORITHM_MP4_FROM_DASH_MUXER: String = "mp4D-mp4"

        @JvmField
        @Transient
        val ALGORITHM_M4A_NO_DASH: String = "mp4D-m4a"

        @JvmField
        @Transient
        val ALGORITHM_OGG_FROM_WEBM_DEMUXER: String = "webm-ogg-d"

        @JvmStatic
        fun getAlgorithm(algorithmName: String, args: Array<String>?): Postprocessing {
            val instance = when (algorithmName) {
                ALGORITHM_TTML_CONVERTER -> TtmlConverter()
                ALGORITHM_WEBM_MUXER -> WebMMuxer()
                ALGORITHM_MP4_FROM_DASH_MUXER -> Mp4FromDashMuxer()
                ALGORITHM_M4A_NO_DASH -> M4aNoDash()
                ALGORITHM_OGG_FROM_WEBM_DEMUXER -> OggFromWebmDemuxer()
                else -> throw UnsupportedOperationException("Unimplemented post-processing algorithm: $algorithmName")
            }
            instance.args = args
            return instance
        }
    }
}
