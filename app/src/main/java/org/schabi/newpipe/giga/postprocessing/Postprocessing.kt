package org.schabi.newpipe.giga.postprocessing

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.schabi.newpipe.giga.streams.DataReader
import org.schabi.newpipe.giga.streams.Mp4DashReader
import org.schabi.newpipe.giga.streams.Mp4FromDashWriter
import org.schabi.newpipe.giga.streams.WebMReader
import org.schabi.newpipe.giga.streams.WebMReader.*
import org.schabi.newpipe.giga.io.SharpStream
import org.schabi.newpipe.util.Logd
import org.schabi.newpipe.giga.get.DownloadMission
import org.schabi.newpipe.giga.io.FileStream
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

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

                    val checker: CircularFileWriter.OffsetChecker = CircularFileWriter.OffsetChecker {
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
                        out.onWriteError = CircularFileWriter.WriteErrorHandle { err: Exception? ->
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

    internal class M4aNoDash : Postprocessing(false, true, ALGORITHM_M4A_NO_DASH) {
        @Throws(IOException::class)
        override fun test(vararg sources: SharpStream?): Boolean {
            // check if the mp4 file is DASH (youtube)

            val reader = Mp4DashReader(sources[0])
            reader.parse()

            return when (reader.getBrands()[0]) {
                0x64617368, 0x69736F35 -> true
                else -> false
            }
        }

        @Throws(IOException::class)
        override fun process(out: SharpStream?, vararg sources: SharpStream?): Int {
            val muxer = Mp4FromDashWriter((sources.filterNotNull().toTypedArray())[0])
            muxer.setMainBrand(0x4D344120) // binary string "M4A "
            muxer.parseSources()
            muxer.selectTracks(0)
            if (out != null) muxer.build(out)

            return OK_RESULT.toInt()
        }
    }

    internal class Mp4FromDashMuxer : Postprocessing(true, true, ALGORITHM_MP4_FROM_DASH_MUXER) {
        @Throws(IOException::class)
        override fun process(out: SharpStream?, vararg sources: SharpStream?): Int {
            val muxer = Mp4FromDashWriter(*sources.filterNotNull().toTypedArray())
            muxer.parseSources()
            muxer.selectTracks(0, 0)
            if (out != null) muxer.build(out)

            return OK_RESULT.toInt()
        }
    }

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

        class SrtFromTtmlWriter(private val out: SharpStream, private val ignoreEmptyFrames: Boolean) {
            private val charset: Charset = StandardCharsets.UTF_8

            private var frameIndex = 0

            @Throws(IOException::class)
            private fun writeFrame(begin: String, end: String, text: StringBuilder) {
                writeString(frameIndex++.toString())
                writeString(NEW_LINE)
                writeString(begin)
                writeString(" --> ")
                writeString(end)
                writeString(NEW_LINE)
                writeString(text.toString())
                writeString(NEW_LINE)
                writeString(NEW_LINE)
            }

            @Throws(IOException::class)
            private fun writeString(text: String) {
                out.write(text.toByteArray(charset))
            }

            @Throws(IOException::class)
            fun build(ttml: SharpStream) {
                /*
                 * TTML parser with BASIC support
                 * multiple CUE is not supported
                 * styling is not supported
                 * tag timestamps (in auto-generated subtitles) are not supported, maybe in the future
                 * also TimestampTagOption enum is not applicable
                 * Language parsing is not supported
                 */

                // parse XML

                val buffer = ByteArray(ttml.available().toInt())
                ttml.read(buffer)
                val doc = Jsoup.parse(ByteArrayInputStream(buffer), "UTF-8", "",
                    Parser.xmlParser())

                val text = StringBuilder(128)
                val paragraphList = doc.select("body > div > p")

                // check if has frames
                if (paragraphList.size < 1) {
                    return
                }

                for (paragraph in paragraphList) {
                    text.setLength(0)

                    for (children in paragraph.childNodes()) {
                        if (children is TextNode) {
                            text.append(children.text())
                        } else if (children is Element
                                && children.tagName().equals("br", ignoreCase = true)) {
                            text.append(NEW_LINE)
                        }
                    }

                    if (ignoreEmptyFrames && text.length < 1) {
                        continue
                    }

                    val begin = getTimestamp(paragraph, "begin")
                    val end = getTimestamp(paragraph, "end")

                    writeFrame(begin, end, text)
                }
            }

            companion object {
                private const val NEW_LINE = "\r\n"

                private fun getTimestamp(frame: Element, attr: String): String {
                    return frame
                        .attr(attr)
                        .replace('.', ',') // SRT subtitles uses comma as decimal separator
                }
            }
        }

        companion object {
            private const val TAG = "TtmlConverter"
        }
    }

    internal class WebMMuxer : Postprocessing(true, true, ALGORITHM_WEBM_MUXER) {
        @Throws(IOException::class)
        override fun process(out: SharpStream?, vararg sources: SharpStream?): Int {
            val muxer = WebMWriter(*sources.filterNotNull().toTypedArray())
            muxer.parseSources()

            // youtube uses a webm with a fake video track that acts as a "cover image"
            val indexes = IntArray(sources.size)

            var i = 0
            while (i < sources.size) {
                val tracks = muxer.getTracksFromSource(i)
                for (j in tracks!!.indices) {
                    if (tracks[j].kind == WebMReader.TrackKind.Audio) {
                        indexes[i] = j
                        i = sources.size
                        break
                    }
                }
                i++
            }

            muxer.selectTracks(*indexes)
            if (out != null ) muxer.build(out)

            return OK_RESULT.toInt()
        }

        class WebMWriter(vararg source: SharpStream) : Closeable {
            private var infoTracks: Array<WebMTrack?>?
            private var sourceTracks: Array<SharpStream>?

            private var readers: Array<WebMReader?>?

            var isDone: Boolean = false
                private set
            private var parsed = false

            private var written: Long = 0

            private var readersSegment: Array<WebMReader.Segment?>? = null
            private var readersCluster: Array<Cluster?>? = null

            private var clustersOffsetsSizes: ArrayList<ClusterInfo>?

            private var outBuffer: ByteArray?
            private var outByteBuffer: ByteBuffer?

            init {
                sourceTracks = source as? Array<SharpStream>
                readers = arrayOfNulls(sourceTracks!!.size)
                infoTracks = arrayOfNulls(sourceTracks!!.size)
                outBuffer = ByteArray(BUFFER_SIZE)
                outByteBuffer = ByteBuffer.wrap(outBuffer)
                clustersOffsetsSizes = ArrayList(256)
            }

            @Throws(IllegalStateException::class)
            fun getTracksFromSource(sourceIndex: Int): Array<WebMTrack>? {
                check(!isDone) { "already done" }
                check(parsed) { "All sources must be parsed first" }

                return readers!![sourceIndex]!!.availableTracks
            }

            @Throws(IOException::class, IllegalStateException::class)
            fun parseSources() {
                check(!isDone) { "already done" }
                check(!parsed) { "already parsed" }

                try {
                    for (i in readers!!.indices) {
                        readers!![i] = WebMReader(sourceTracks!![i])
                        readers!![i]!!.parse()
                    }
                } finally {
                    parsed = true
                }
            }

            @Throws(IOException::class)
            fun selectTracks(vararg trackIndex: Int) {
                try {
                    readersSegment = arrayOfNulls(readers!!.size)
                    readersCluster = arrayOfNulls(readers!!.size)

                    for (i in readers!!.indices) {
                        infoTracks!![i] = readers!![i]!!.selectTrack(trackIndex[i])
                        readersSegment!![i] = readers!![i]!!.nextSegment
                    }
                } finally {
                    parsed = true
                }
            }

            override fun close() {
                isDone = true
                parsed = true

                for (src in sourceTracks!!) {
                    src.close()
                }

                sourceTracks = null
                readers = null
                infoTracks = null
                readersSegment = null
                readersCluster = null
                outBuffer = null
                outByteBuffer = null
                clustersOffsetsSizes = null
            }

            @Throws(IOException::class, RuntimeException::class)
            fun build(out: SharpStream) {
                if (!out.canRewind()) {
                    throw IOException("The output stream must be allow seek")
                }

                makeEBML(out)

                val offsetSegmentSizeSet = written + 5
                val offsetInfoDurationSet = written + 94
                val offsetClusterSet = written + 58
                val offsetCuesSet = written + 75

                val listBuffer = ArrayList<ByteArray?>(4)

                /* segment */
                listBuffer.add(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67, 0x01,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 // segment content size
                ))

                val segmentOffset = written + listBuffer[0]!!.size

                /* seek head */
                listBuffer.add(byteArrayOf(0x11, 0x4d, 0x9b.toByte(), 0x74, 0xbe.toByte(),
                    0x4d, 0xbb.toByte(), 0x8b.toByte(),
                    0x53, 0xab.toByte(), 0x84.toByte(), 0x15, 0x49, 0xa9.toByte(), 0x66, 0x53,
                    0xac.toByte(), 0x81.toByte(),  /*info offset*/
                    0x43,
                    0x4d, 0xbb.toByte(), 0x8b.toByte(), 0x53, 0xab.toByte(),
                    0x84.toByte(), 0x16, 0x54, 0xae.toByte(), 0x6b, 0x53, 0xac.toByte(), 0x81.toByte(),  /*tracks offset*/
                    0x56,
                    0x4d, 0xbb.toByte(), 0x8e.toByte(), 0x53, 0xab.toByte(), 0x84.toByte(), 0x1f,
                    0x43, 0xb6.toByte(), 0x75, 0x53, 0xac.toByte(), 0x84.toByte(),  /*cluster offset [2]*/
                    0x00, 0x00, 0x00, 0x00,
                    0x4d, 0xbb.toByte(), 0x8e.toByte(), 0x53, 0xab.toByte(), 0x84.toByte(), 0x1c, 0x53,
                    0xbb.toByte(), 0x6b, 0x53, 0xac.toByte(), 0x84.toByte(),  /*cues offset [7]*/
                    0x00, 0x00, 0x00, 0x00
                ))

                /* info */
                listBuffer.add(byteArrayOf(0x15, 0x49, 0xa9.toByte(), 0x66, 0x8e.toByte(), 0x2a, 0xd7.toByte(), 0xb1.toByte()
                ))
                // the segment duration MUST NOT exceed 4 bytes
                listBuffer.add(encode(DEFAULT_TIMECODE_SCALE.toLong(), true))
                listBuffer.add(byteArrayOf(
                    0x44, 0x89.toByte(), 0x84.toByte(),
                    0x00, 0x00, 0x00, 0x00,  // info.duration
                ))

                /* tracks */
                listBuffer.addAll(makeTracks())

                dump(listBuffer, out)

                // reserve space for Cues element
                val cueOffset = written
                makeEbmlVoid(out, CUE_RESERVE_SIZE, true)

                val defaultSampleDuration = IntArray(infoTracks!!.size)
                val duration = LongArray(infoTracks!!.size)

                for (i in infoTracks!!.indices) {
                    if (infoTracks!![i]!!.defaultDuration < 0) {
                        defaultSampleDuration[i] = -1 // not available
                    } else {
                        defaultSampleDuration[i] = ceil((infoTracks!![i]!!.defaultDuration
                                / DEFAULT_TIMECODE_SCALE.toFloat()).toDouble()).toInt()
                    }
                    duration[i] = -1
                }

                // Select a track for the cue
                val cuesForTrackId = selectTrackForCue()
                var nextCueTime = (if (infoTracks!![cuesForTrackId]!!.trackType == 1) -1 else 0).toLong()
                val keyFrames = ArrayList<KeyFrame>(32)

                var firstClusterOffset = written.toInt()
                var currentClusterOffset = makeCluster(out, 0, 0, true)

                var baseTimecode: Long = 0
                var limitTimecode: Long = -1
                var limitTimecodeByTrackId = cuesForTrackId

                var blockWritten = Int.MAX_VALUE

                var newClusterByTrackId = -1

                while (blockWritten > 0) {
                    blockWritten = 0
                    var i = 0
                    while (i < readers!!.size) {
                        val bloq = getNextBlockFrom(i)
                        if (bloq == null) {
                            i++
                            continue
                        }

                        if (bloq.data == null) {
                            blockWritten = 1 // fake block
                            newClusterByTrackId = i
                            i++
                            continue
                        }

                        if (newClusterByTrackId == i) {
                            limitTimecodeByTrackId = i
                            newClusterByTrackId = -1
                            baseTimecode = bloq.absoluteTimecode
                            limitTimecode = baseTimecode + INTERV
                            currentClusterOffset = makeCluster(out, baseTimecode, currentClusterOffset,
                                true)
                        }

                        if (cuesForTrackId == i) {
                            if ((nextCueTime > -1 && bloq.absoluteTimecode >= nextCueTime)
                                    || (nextCueTime < 0 && bloq.isKeyframe)) {
                                if (nextCueTime > -1) {
                                    nextCueTime += DEFAULT_CUES_EACH_MS.toLong()
                                }
                                keyFrames.add(KeyFrame(segmentOffset, currentClusterOffset, written,
                                    bloq.absoluteTimecode))
                            }
                        }

                        writeBlock(out, bloq, baseTimecode)
                        blockWritten++

                        if (defaultSampleDuration[i] < 0 && duration[i] >= 0) {
                            // if the sample duration in unknown,
                            // calculate using current_duration - previous_duration
                            defaultSampleDuration[i] = (bloq.absoluteTimecode - duration[i]).toInt()
                        }
                        duration[i] = bloq.absoluteTimecode

                        if (limitTimecode < 0) {
                            limitTimecode = bloq.absoluteTimecode + INTERV
                            continue
                        }

                        if (bloq.absoluteTimecode >= limitTimecode) {
                            if (limitTimecodeByTrackId != i) {
                                limitTimecode += INTERV - (bloq.absoluteTimecode - limitTimecode)
                            }
                            i++
                        }
                    }
                }

                makeCluster(out, -1, currentClusterOffset, false)

                val segmentSize = written - offsetSegmentSizeSet - 7

                /* Segment size */
                seekTo(out, offsetSegmentSizeSet)
                outByteBuffer!!.putLong(0, segmentSize)
                out.write(outBuffer, 1, DataReader.LONG_SIZE - 1)

                /* Segment duration */
                var longestDuration: Long = 0
                for (i in duration.indices) {
                    if (defaultSampleDuration[i] > 0) {
                        duration[i] += defaultSampleDuration[i].toLong()
                    }
                    if (duration[i] > longestDuration) {
                        longestDuration = duration[i]
                    }
                }
                seekTo(out, offsetInfoDurationSet)
                outByteBuffer!!.putFloat(0, longestDuration.toFloat())
                dump(outBuffer, DataReader.FLOAT_SIZE, out)

                /* first Cluster offset */
                firstClusterOffset -= segmentOffset.toInt()
                writeInt(out, offsetClusterSet, firstClusterOffset)

                seekTo(out, cueOffset)

                /* Cue */
                var cueSize: Short = 0
                dump(byteArrayOf(0x1c, 0x53, 0xbb.toByte(), 0x6b, 0x20, 0x00, 0x00), out) // header size is 7

                for (keyFrame in keyFrames) {
                    val size = makeCuePoint(cuesForTrackId, keyFrame, outBuffer)

                    if ((cueSize + size + 7 + MINIMUM_EBML_VOID_SIZE) > CUE_RESERVE_SIZE) {
                        break // no space left
                    }

                    cueSize = (cueSize + size).toShort()
                    dump(outBuffer, size, out)
                }

                makeEbmlVoid(out, CUE_RESERVE_SIZE - cueSize - 7, false)

                seekTo(out, cueOffset + 5)
                outByteBuffer!!.putShort(0, cueSize)
                dump(outBuffer, DataReader.SHORT_SIZE, out)

                /* seek head, seek for cues element */
                writeInt(out, offsetCuesSet, (cueOffset - segmentOffset).toInt())

                for (cluster in clustersOffsetsSizes!!) {
                    writeInt(out, cluster.offset, cluster.size or 0x10000000)
                }
            }

            @Throws(IOException::class)
            private fun getNextBlockFrom(internalTrackId: Int): Block? {
                if (readersSegment!![internalTrackId] == null) {
                    readersSegment!![internalTrackId] = readers!![internalTrackId]!!.nextSegment
                    if (readersSegment!![internalTrackId] == null) {
                        return null // no more blocks in the selected track
                    }
                }

                if (readersCluster!![internalTrackId] == null) {
                    readersCluster!![internalTrackId] = readersSegment!![internalTrackId]!!.nextCluster
                    if (readersCluster!![internalTrackId] == null) {
                        readersSegment!![internalTrackId] = null
                        return getNextBlockFrom(internalTrackId)
                    }
                }

                val res = readersCluster!![internalTrackId]!!.nextSimpleBlock
                if (res == null) {
                    readersCluster!![internalTrackId] = null
                    return Block() // fake block to indicate the end of the cluster
                }

                val bloq = Block()
                bloq.data = res.data
                bloq.dataSize = res.dataSize
                bloq.trackNumber = internalTrackId
                bloq.flags = res.flags
                bloq.absoluteTimecode = res.absoluteTimeCodeNs / DEFAULT_TIMECODE_SCALE

                return bloq
            }

            @Throws(IOException::class)
            private fun seekTo(stream: SharpStream, offset: Long) {
                if (stream.canSeek()) {
                    stream.seek(offset)
                } else {
                    if (offset > written) {
                        stream.skip(offset - written)
                    } else {
                        stream.rewind()
                        stream.skip(offset)
                    }
                }

                written = offset
            }

            @Throws(IOException::class)
            private fun writeInt(stream: SharpStream, offset: Long, number: Int) {
                seekTo(stream, offset)
                outByteBuffer!!.putInt(0, number)
                dump(outBuffer, DataReader.INTEGER_SIZE, stream)
            }

            @Throws(IOException::class)
            private fun writeBlock(stream: SharpStream, bloq: Block, clusterTimecode: Long) {
                val relativeTimeCode = bloq.absoluteTimecode - clusterTimecode

                if (relativeTimeCode < Short.MIN_VALUE || relativeTimeCode > Short.MAX_VALUE) {
                    throw IndexOutOfBoundsException("SimpleBlock timecode overflow.")
                }

                val listBuffer = ArrayList<ByteArray?>(5)
                listBuffer.add(byteArrayOf(0xa3.toByte()))
                listBuffer.add(null) // block size
                listBuffer.add(encode((bloq.trackNumber + 1).toLong(), false))
                listBuffer.add(ByteBuffer.allocate(DataReader.SHORT_SIZE).putShort(relativeTimeCode.toShort())
                    .array())
                listBuffer.add(byteArrayOf(bloq.flags))

                var blockSize = bloq.dataSize
                for (i in 2 until listBuffer.size) {
                    blockSize += listBuffer[i]!!.size
                }
                listBuffer[1] = encode(blockSize.toLong(), false)

                dump(listBuffer, stream)

                var read: Int
                while ((bloq.data!!.read(outBuffer).also { read = it }) > 0) {
                    dump(outBuffer, read, stream)
                }
            }

            @Throws(IOException::class)
            private fun makeCluster(stream: SharpStream, timecode: Long, offsetStart: Long,
                                    create: Boolean
            ): Long {
                var cluster: ClusterInfo
                var offset = offsetStart

                if (offset > 0) {
                    // save the size of the previous cluster (maximum 256 MiB)
                    cluster = clustersOffsetsSizes!![clustersOffsetsSizes!!.size - 1]
                    cluster.size = (written - offset - CLUSTER_HEADER_SIZE).toInt()
                }

                offset = written

                if (create) {
                    /* cluster */
                    dump(byteArrayOf(0x1f, 0x43, 0xb6.toByte(), 0x75), stream)

                    cluster = ClusterInfo()
                    cluster.offset = written
                    clustersOffsetsSizes!!.add(cluster)

                    dump(byteArrayOf(0x10, 0x00, 0x00, 0x00,  /* timestamp */
                        0xe7.toByte()
                    ), stream)

                    dump(encode(timecode, true), stream)
                }

                return offset
            }

            @Throws(IOException::class)
            private fun makeEBML(stream: SharpStream) {
                // default values
                dump(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x01, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x1F, 0x42, 0x86.toByte(), 0x81.toByte(), 0x01,
                    0x42, 0xF7.toByte(), 0x81.toByte(), 0x01, 0x42, 0xF2.toByte(), 0x81.toByte(), 0x04,
                    0x42, 0xF3.toByte(), 0x81.toByte(), 0x08, 0x42, 0x82.toByte(), 0x84.toByte(), 0x77,
                    0x65, 0x62, 0x6D, 0x42, 0x87.toByte(), 0x81.toByte(), 0x02,
                    0x42, 0x85.toByte(), 0x81.toByte(), 0x02
                ), stream)
            }

            private fun makeTracks(): ArrayList<ByteArray?> {
                val buffer = ArrayList<ByteArray?>(1)
                buffer.add(byteArrayOf(0x16, 0x54, 0xae.toByte(), 0x6b))
                buffer.add(null)

                for (i in infoTracks!!.indices) {
                    buffer.addAll(makeTrackEntry(i, infoTracks!![i]))
                }

                return lengthFor(buffer)
            }

            private fun makeTrackEntry(internalTrackId: Int, track: WebMTrack?): ArrayList<ByteArray?> {
                val id = encode((internalTrackId + 1).toLong(), true)
                val buffer = ArrayList<ByteArray?>(12)

                /* track */
                buffer.add(byteArrayOf(0xae.toByte()))
                buffer.add(null)

                /* track number */
                buffer.add(byteArrayOf(0xd7.toByte()))
                buffer.add(id)

                /* track uid */
                buffer.add(byteArrayOf(0x73, 0xc5.toByte()))
                buffer.add(id)

                /* flag lacing */
                buffer.add(byteArrayOf(0x9c.toByte(), 0x81.toByte(), 0x00))

                /* lang */
                buffer.add(byteArrayOf(0x22, 0xb5.toByte(), 0x9c.toByte(), 0x83.toByte(), 0x75, 0x6e, 0x64))

                /* codec id */
                buffer.add(byteArrayOf(0x86.toByte()))
                buffer.addAll(encode(track!!.codecId))

                /* codec delay*/
                if (track.codecDelay >= 0) {
                    buffer.add(byteArrayOf(0x56, 0xAA.toByte()))
                    buffer.add(encode(track.codecDelay, true))
                }

                /* codec seek pre-roll*/
                if (track.seekPreRoll >= 0) {
                    buffer.add(byteArrayOf(0x56, 0xBB.toByte()))
                    buffer.add(encode(track.seekPreRoll, true))
                }

                /* type */
                buffer.add(byteArrayOf(0x83.toByte()))
                buffer.add(encode(track.trackType.toLong(), true))

                /* default duration */
                if (track.defaultDuration >= 0) {
                    buffer.add(byteArrayOf(0x23, 0xe3.toByte(), 0x83.toByte()))
                    buffer.add(encode(track.defaultDuration, true))
                }

                /* audio/video */
                if ((track.trackType == 1 || track.trackType == 2) && valid(track.bMetadata)) {
                    buffer.add(byteArrayOf((if (track.trackType == 1) 0xe0 else 0xe1).toByte()))
                    buffer.add(encode(track.bMetadata.size.toLong(), false))
                    buffer.add(track.bMetadata)
                }

                /* codec private*/
                if (valid(track.codecPrivate)) {
                    buffer.add(byteArrayOf(0x63, 0xa2.toByte()))
                    buffer.add(encode(track.codecPrivate.size.toLong(), false))
                    buffer.add(track.codecPrivate)
                }

                return lengthFor(buffer)
            }

            private fun makeCuePoint(internalTrackId: Int, keyFrame: KeyFrame,
                                     buffer: ByteArray?
            ): Int {
                val cue = ArrayList<ByteArray?>(5)

                /* CuePoint */
                cue.add(byteArrayOf(0xbb.toByte()))
                cue.add(null)

                /* CueTime */
                cue.add(byteArrayOf(0xb3.toByte()))
                cue.add(encode(keyFrame.duration, true))

                /* CueTrackPosition */
                cue.addAll(makeCueTrackPosition(internalTrackId, keyFrame))

                var size = 0
                lengthFor(cue)

                for (buff in cue) {
                    System.arraycopy(buff, 0, buffer, size, buff!!.size)
                    size += buff.size
                }

                return size
            }

            private fun makeCueTrackPosition(internalTrackId: Int,
                                             keyFrame: KeyFrame
            ): ArrayList<ByteArray?> {
                val buffer = ArrayList<ByteArray?>(8)

                /* CueTrackPositions */
                buffer.add(byteArrayOf(0xb7.toByte()))
                buffer.add(null)

                /* CueTrack */
                buffer.add(byteArrayOf(0xf7.toByte()))
                buffer.add(encode((internalTrackId + 1).toLong(), true))

                /* CueClusterPosition */
                buffer.add(byteArrayOf(0xf1.toByte()))
                buffer.add(encode(keyFrame.clusterPosition, true))

                /* CueRelativePosition */
                if (keyFrame.relativePosition > 0) {
                    buffer.add(byteArrayOf(0xf0.toByte()))
                    buffer.add(encode(keyFrame.relativePosition.toLong(), true))
                }

                return lengthFor(buffer)
            }

            @Throws(IOException::class)
            private fun makeEbmlVoid(out: SharpStream, amount: Int, wipe: Boolean) {
                var size = amount

                /* ebml void */
                outByteBuffer!!.putShort(0, 0xec20.toShort())
                outByteBuffer!!.putShort(2, (size - 4).toShort())

                dump(outBuffer, 4, out)

                if (wipe) {
                    size -= 4
                    while (size > 0) {
                        val write = min(size, outBuffer!!.size)
                        dump(outBuffer, write, out)
                        size -= write
                    }
                }
            }

            @Throws(IOException::class)
            private fun dump(buffer: ByteArray, stream: SharpStream) {
                dump(buffer, buffer.size, stream)
            }

            @Throws(IOException::class)
            private fun dump(buffer: ByteArray?, count: Int, stream: SharpStream) {
                stream.write(buffer, 0, count)
                written += count.toLong()
            }

            @Throws(IOException::class)
            private fun dump(buffers: ArrayList<ByteArray?>, stream: SharpStream) {
                for (buffer in buffers) {
                    stream.write(buffer)
                    written += buffer!!.size.toLong()
                }
            }

            private fun lengthFor(buffer: ArrayList<ByteArray?>): ArrayList<ByteArray?> {
                var size: Long = 0
                for (i in 2 until buffer.size) {
                    size += buffer[i]!!.size.toLong()
                }
                buffer[1] = encode(size, false)
                return buffer
            }

            private fun encode(number: Long, withLength: Boolean): ByteArray {
                var length = -1
                for (i in 1..7) {
                    if (number < (2.0).pow((7 * i).toDouble())) {
                        length = i
                        break
                    }
                }

                if (length < 1) {
                    throw ArithmeticException("Can't encode a number of bigger than 7 bytes")
                }

                if (number.toDouble() == ((2.0).pow((7 * length).toDouble())) - 1) {
                    length++
                }

                val offset = if (withLength) 1 else 0
                val buffer = ByteArray(offset + length)
                val marker = Math.floorDiv(length - 1, 8).toLong()

                var shift = 0
                var i = length - 1
                while (i >= 0) {
                    var b = number ushr shift
                    if (!withLength && i.toLong() == marker) {
                        b = b or (0x80 ushr (length - 1)).toLong()
                    }
                    buffer[offset + i] = b.toByte()
                    i--
                    shift += 8
                }

                if (withLength) {
                    buffer[0] = (0x80 or length).toByte()
                }

                return buffer
            }

            private fun encode(value: String?): ArrayList<ByteArray?> {
                val str = value!!.toByteArray(StandardCharsets.UTF_8) // or use "utf-8"

                val buffer = ArrayList<ByteArray?>(2)
                buffer.add(encode(str.size.toLong(), false))
                buffer.add(str)

                return buffer
            }

            private fun valid(buffer: ByteArray?): Boolean {
                return buffer != null && buffer.size > 0
            }

            private fun selectTrackForCue(): Int {
                var i = 0
                var videoTracks = 0
                var audioTracks = 0

                while (i < infoTracks!!.size) {
                    when (infoTracks!![i]!!.trackType) {
                        1 -> videoTracks++
                        2 -> audioTracks++
                    }
                    i++
                }
                val kind = if (audioTracks == infoTracks!!.size) {
                    2
                } else if (videoTracks == infoTracks!!.size) {
                    1
                } else if (videoTracks > 0) {
                    1
                } else if (audioTracks > 0) {
                    2
                } else {
                    return 0
                }

                // TODO: in the above code, find and select the shortest track for the desired kind
                i = 0
                while (i < infoTracks!!.size) {
                    if (kind == infoTracks!![i]!!.trackType) {
                        return i
                    }
                    i++
                }

                return 0
            }

            internal class KeyFrame(segment: Long, cluster: Long, block: Long, val duration: Long) {
                val clusterPosition: Long = cluster - segment
                val relativePosition: Int = (block - cluster - CLUSTER_HEADER_SIZE).toInt()
            }

            internal class Block {
                var data: InputStream? = null
                var trackNumber: Int = 0
                var flags: Byte = 0
                var dataSize: Int = 0
                var absoluteTimecode: Long = 0

                val isKeyframe: Boolean
                    get() = (flags.toInt() and 0x80) == 0x80

                override fun toString(): String {
                    return String.format("trackNumber=%s  isKeyFrame=%S  absoluteTimecode=%s", trackNumber,
                        isKeyframe, absoluteTimecode)
                }
            }

            internal class ClusterInfo {
                var offset: Long = 0
                var size: Int = 0
            }

            companion object {
                private const val BUFFER_SIZE = 8 * 1024
                private const val DEFAULT_TIMECODE_SCALE = 1000000
                private const val INTERV = 100 // 100ms on 1000000us timecode scale
                private const val DEFAULT_CUES_EACH_MS = 5000 // 5000ms on 1000000us timecode scale
                private const val CLUSTER_HEADER_SIZE: Byte = 8
                private const val CUE_RESERVE_SIZE = 65535
                private const val MINIMUM_EBML_VOID_SIZE: Byte = 4
            }
        }
    }

    internal class OggFromWebmDemuxer : Postprocessing(true, true, ALGORITHM_OGG_FROM_WEBM_DEMUXER) {
        @Throws(IOException::class)
        override fun test(vararg sources: SharpStream?): Boolean {
            val buffer = ByteBuffer.allocate(4)
            sources[0]?.read(buffer.array())

            when (buffer.getInt()) {
                0x1a45dfa3 -> return true // webm/mkv
                0x4F676753 -> return false // ogg
            }
            throw UnsupportedOperationException("file not recognized, failed to demux the audio stream")
        }

        @Throws(IOException::class)
        override fun process(out: SharpStream?, vararg sources: SharpStream?): Int {
            if (out != null) {
                val demuxer = OggFromWebMWriter((sources.filterNotNull().toTypedArray())[0], out)
                demuxer.parseSource()
                demuxer.selectTrack(0)
                demuxer.build()
            }
            return OK_RESULT.toInt()
        }

        class OggFromWebMWriter(source: SharpStream, target: SharpStream) : Closeable {
            var isDone: Boolean = false
                private set
            var isParsed: Boolean = false
                private set

            private val source: SharpStream
            private val output: SharpStream

            private var sequenceCount = 0
            private val streamId: Int
            private var packetFlag = FLAG_FIRST

            private var webm: WebMReader? = null
            private var webmTrack: WebMTrack? = null
            private var webmSegment: WebMReader.Segment? = null
            private var webmCluster: Cluster? = null
            private var webmBlock: SimpleBlock? = null

            private var webmBlockLastTimecode: Long = 0
            private var webmBlockNearDuration: Long = 0

            private var segmentTableSize: Short = 0
            private val segmentTable = ByteArray(255)
            private var segmentTableNextTimestamp = TIME_SCALE_NS.toLong()

            private val crc32Table = IntArray(256)

            init {
                require(!(!source.canRead() || !source.canRewind())) { "source stream must be readable and allows seeking" }
                require(!(!target.canWrite() || !target.canRewind())) { "output stream must be writable and allows seeking" }

                this.source = source
                this.output = target

                this.streamId = System.currentTimeMillis().toInt()

                populateCrc32Table()
            }

            @get:Throws(IllegalStateException::class)
            val tracksFromSource: Array<WebMTrack>
                get() {
                    check(isParsed) { "source must be parsed first" }

                    return webm!!.availableTracks?: arrayOf()
                }

            @Throws(IOException::class, IllegalStateException::class)
            fun parseSource() {
                check(!isDone) { "already done" }
                check(!isParsed) { "already parsed" }

                try {
                    webm = WebMReader(source)
                    webm!!.parse()
                    webmSegment = webm!!.nextSegment
                } finally {
                    isParsed = true
                }
            }

            @Throws(IOException::class)
            fun selectTrack(trackIndex: Int) {
                check(isParsed) { "source must be parsed first" }
                if (isDone) {
                    throw IOException("already done")
                }
                if (webmTrack != null) {
                    throw IOException("tracks already selected")
                }

                if (!webm?.availableTracks.isNullOrEmpty()) {
                    when (webm!!.availableTracks!![trackIndex].kind) {
                        WebMReader.TrackKind.Audio, WebMReader.TrackKind.Video -> {}
                        else -> throw UnsupportedOperationException("the track must an audio or video stream")
                    }
                }
                try {
                    webmTrack = webm!!.selectTrack(trackIndex)
                } finally {
                    isParsed = true
                }
            }

            @Throws(IOException::class)
            override fun close() {
                isDone = true
                isParsed = true

                webmTrack = null
                webm = null

                if (!output.isClosed) {
                    output.flush()
                }

                source.close()
                output.close()
            }

            @Throws(IOException::class)
            fun build() {
                var resolution: Float = 0f
                var bloq: SimpleBlock?
                val header = ByteBuffer.allocate(27 + (255 * 255))
                val page = ByteBuffer.allocate(64 * 1024)

                header.order(ByteOrder.LITTLE_ENDIAN)

                when (webmTrack!!.kind) {
                    WebMReader.TrackKind.Audio -> {
                        resolution = getSampleFrequencyFromTrack(webmTrack!!.bMetadata)
                        if (resolution == 0f) {
                            throw RuntimeException("cannot get the audio sample rate")
                        }
                    }
                    WebMReader.TrackKind.Video -> {
                        // WARNING: untested
                        if (webmTrack!!.defaultDuration == 0L) {
                            throw RuntimeException("missing default frame time")
                        }
                        if (webmSegment?.info != null) resolution = 1000f / (webmTrack!!.defaultDuration.toFloat() / webmSegment!!.info!!.timecodeScale)
                    }
                    else -> throw RuntimeException("not implemented")
                }
                /* step 2: create packet with code init data */
                if (webmTrack!!.codecPrivate != null) {
                    addPacketSegment(webmTrack!!.codecPrivate.size)
                    makePacketheader(0x00, header, webmTrack!!.codecPrivate)
                    write(header)
                    output.write(webmTrack!!.codecPrivate)
                }

                /* step 3: create packet with metadata */
                val buffer = makeMetadata()
                if (buffer != null) {
                    addPacketSegment(buffer.size)
                    makePacketheader(0x00, header, buffer)
                    write(header)
                    output.write(buffer)
                }

                /* step 4: calculate amount of packets */
                while (webmSegment != null) {
                    bloq = nextBlock

                    if (bloq != null && addPacketSegment(bloq)) {
                        val pos = page.position()
                        bloq.data?.read(page.array(), pos, bloq.dataSize)
                        page.position(pos + bloq.dataSize)
                        continue
                    }

                    // calculate the current packet duration using the next block
                    var elapsedNs = webmTrack!!.codecDelay.toDouble()

                    if (bloq == null) {
                        packetFlag = FLAG_LAST // note: if the flag is FLAG_CONTINUED, is changed
                        elapsedNs += webmBlockLastTimecode.toDouble()

                        elapsedNs += if (webmTrack!!.defaultDuration > 0) {
                            webmTrack!!.defaultDuration.toDouble()
                        } else {
                            // hardcoded way, guess the sample duration
                            webmBlockNearDuration.toDouble()
                        }
                    } else {
                        elapsedNs += bloq.absoluteTimeCodeNs.toDouble()
                    }

                    // get the sample count in the page
                    elapsedNs = elapsedNs / TIME_SCALE_NS
                    elapsedNs = ceil(elapsedNs * resolution)

                    // create header and calculate page checksum
                    var checksum = makePacketheader(elapsedNs.toLong(), header, null)
                    checksum = calcCrc32(checksum, page.array(), page.position())

                    header.putInt(HEADER_CHECKSUM_OFFSET.toInt(), checksum)

                    // dump data
                    write(header)
                    write(page)

                    webmBlock = bloq
                }
            }

            private fun makePacketheader(granPos: Long, buffer: ByteBuffer,
                                         immediatePage: ByteArray?
            ): Int {
                var length = HEADER_SIZE.toShort()

                buffer.putInt(0x5367674f) // "OggS" binary string in little-endian
                buffer.put(0x00.toByte()) // version
                buffer.put(packetFlag) // type

                buffer.putLong(granPos) // granulate position

                buffer.putInt(streamId) // bitstream serial number
                buffer.putInt(sequenceCount++) // page sequence number

                buffer.putInt(0x00) // page checksum

                buffer.put(segmentTableSize.toByte()) // segment table
                buffer.put(segmentTable, 0, segmentTableSize.toInt()) // segment size

                length = (length + segmentTableSize).toShort()

                clearSegmentTable() // clear segment table for next header

                var checksumCrc32 = calcCrc32(0x00, buffer.array(), length.toInt())

                if (immediatePage != null) {
                    checksumCrc32 = calcCrc32(checksumCrc32, immediatePage, immediatePage.size)
                    buffer.putInt(HEADER_CHECKSUM_OFFSET.toInt(), checksumCrc32)
                    segmentTableNextTimestamp -= TIME_SCALE_NS.toLong()
                }

                return checksumCrc32
            }

            private fun makeMetadata(): ByteArray? {
                if ("A_OPUS" == webmTrack!!.codecId) {
                    return byteArrayOf(0x4F, 0x70, 0x75, 0x73, 0x54, 0x61, 0x67, 0x73,  // "OpusTags" binary string
                        0x00, 0x00, 0x00, 0x00,  // writing application string size (not present)
                        0x00, 0x00, 0x00, 0x00 // additional tags count (zero means no tags)
                    )
                } else if ("A_VORBIS" == webmTrack!!.codecId) {
                    return byteArrayOf(0x03,  // ¿¿¿???
                        0x76, 0x6f, 0x72, 0x62, 0x69, 0x73,  // "vorbis" binary string
                        0x00, 0x00, 0x00, 0x00,  // writing application string size (not present)
                        0x00, 0x00, 0x00, 0x00 // additional tags count (zero means no tags)
                    )
                }

                // not implemented for the desired codec
                return null
            }

            @Throws(IOException::class)
            private fun write(buffer: ByteBuffer) {
                output.write(buffer.array(), 0, buffer.position())
                buffer.position(0)
            }

            @get:Throws(IOException::class)
            private val nextBlock: SimpleBlock?
                get() {
                    val res: SimpleBlock?

                    if (webmBlock != null) {
                        res = webmBlock
                        webmBlock = null
                        return res
                    }

                    if (webmSegment == null) {
                        webmSegment = webm!!.nextSegment
                        if (webmSegment == null) {
                            return null // no more blocks in the selected track
                        }
                    }

                    if (webmCluster == null) {
                        webmCluster = webmSegment!!.nextCluster
                        if (webmCluster == null) {
                            webmSegment = null
                            return nextBlock
                        }
                    }

                    res = webmCluster!!.nextSimpleBlock
                    if (res == null) {
                        webmCluster = null
                        return nextBlock
                    }

                    webmBlockNearDuration = res.absoluteTimeCodeNs - webmBlockLastTimecode
                    webmBlockLastTimecode = res.absoluteTimeCodeNs

                    return res
                }

            private fun getSampleFrequencyFromTrack(bMetadata: ByteArray): Float {
                // hardcoded way
                val buffer = ByteBuffer.wrap(bMetadata)

                while (buffer.remaining() >= 6) {
                    val id = buffer.getShort().toInt() and 0xFFFF
                    if (id == 0x0000B584) {
                        return buffer.getFloat()
                    }
                }

                return 0.0f
            }

            private fun clearSegmentTable() {
                segmentTableNextTimestamp += TIME_SCALE_NS.toLong()
                packetFlag = FLAG_UNSET
                segmentTableSize = 0
            }

            private fun addPacketSegment(block: SimpleBlock): Boolean {
                val timestamp = block.absoluteTimeCodeNs + webmTrack!!.codecDelay

                if (timestamp >= segmentTableNextTimestamp) {
                    return false
                }

                return addPacketSegment(block.dataSize)
            }

            private fun addPacketSegment(size: Int): Boolean {
                if (size > 65025) {
                    throw UnsupportedOperationException("page size cannot be larger than 65025")
                }

                var available = (segmentTable.size - segmentTableSize) * 255
                val extra = (size % 255) == 0

                if (extra) {
                    // add a zero byte entry in the table
                    // required to indicate the sample size is multiple of 255
                    available -= 255
                }

                // check if possible add the segment, without overflow the table
                if (available < size) {
                    return false // not enough space on the page
                }

                var seg = size
                while (seg > 0) {
                    segmentTable[segmentTableSize++.toInt()] = min(seg.toDouble(), 255.0).toInt().toByte()
                    seg -= 255
                }

                if (extra) {
                    segmentTable[segmentTableSize++.toInt()] = 0x00
                }

                return true
            }

            private fun populateCrc32Table() {
                for (i in 0..0xff) {
                    var crc = i shl 24
                    for (j in 0..7) {
                        val b = (crc ushr 31).toLong()
                        crc = crc shl 1
                        crc = crc xor ((0x100000000L - b).toInt() and 0x04c11db7)
                    }
                    crc32Table[i] = crc
                }
            }

            private fun calcCrc32(initialCrc: Int, buffer: ByteArray, size: Int): Int {
                var crc = initialCrc
                for (i in 0 until size) {
                    val reg = (crc ushr 24) and 0xff
                    crc = (crc shl 8) xor crc32Table[reg xor (buffer[i].toInt() and 0xff)]
                }

                return crc
            }

            companion object {
                private const val FLAG_UNSET: Byte = 0x00

                //private static final byte FLAG_CONTINUED = 0x01;
                private const val FLAG_FIRST: Byte = 0x02
                private const val FLAG_LAST: Byte = 0x04

                private const val HEADER_CHECKSUM_OFFSET: Byte = 22
                private const val HEADER_SIZE: Byte = 27

                private const val TIME_SCALE_NS = 1000000000
            }
        }
    }

    class ChunkFileInputStream(private var source: SharpStream?, private val offset: Long, end: Long, private val onProgress: ProgressReport?)
        : SharpStream() {

        private val length = end - offset
        private var position: Long = 0

        private var progressReport: Long

        init {
            progressReport = REPORT_INTERVAL.toLong()

            if (length < 1) {
                source!!.close()
                throw IOException("The chunk is empty or invalid")
            }
            if (source!!.length() < end) {
                try {
                    throw IOException(String.format("invalid file length. expected = %s  found = %s", end, source!!.length()))
                } finally {
                    source!!.close()
                }
            }

            source!!.seek(offset)
        }

        val filePointer: Long
            /**
             * Get absolute position on file
             *
             * @return the position
             */
            get() = offset + position

        @Throws(IOException::class)
        override fun read(): Int {
            if ((position + 1) > length) return 0

            val res = source!!.read()
            if (res >= 0) position++

            return res
        }

        @Throws(IOException::class)
        override fun read(b: ByteArray?): Int {
            return read(b, 0, b!!.size)
        }

        @Throws(IOException::class)
        override fun read(b: ByteArray?, off: Int, len: Int): Int {
            var len = len
            if ((position + len) > length) len = (length - position).toInt()

            if (len == 0) return 0

            val res = source!!.read(b, off, len)
            position += res.toLong()

            if (onProgress != null && position > progressReport) {
                onProgress.report(position)
                progressReport = position + REPORT_INTERVAL
            }

            return res
        }

        @Throws(IOException::class)
        override fun skip(pos: Long): Long {
            var pos = pos
            pos = min((pos + position), length)

            if (pos == 0L) return 0

            source!!.seek(offset + pos)

            val oldPos = position
            position = pos

            return pos - oldPos
        }

        override fun available(): Long {
            return length - position
        }

        override fun close() {
            source!!.close()
            source = null
        }

        override val isClosed: Boolean
            get() = source == null

        @Throws(IOException::class)
        override fun rewind() {
            position = 0
            source!!.seek(offset)
        }

        override fun canRewind(): Boolean {
            return true
        }

        override fun canRead(): Boolean {
            return true
        }

        override fun canWrite(): Boolean {
            return false
        }

        override fun write(value: Byte) {
        }

        override fun write(buffer: ByteArray?) {
        }

        override fun write(buffer: ByteArray?, offset: Int, count: Int) {
        }

        companion object {
            private const val REPORT_INTERVAL = 256 * 1024
        }
    }

    class CircularFileWriter(target: SharpStream, temp: File, checker: OffsetChecker) : SharpStream() {
        private val callback: OffsetChecker

        @JvmField
        var onProgress: ProgressReport? = null
        @JvmField
        var onWriteError: WriteErrorHandle? = null

        private var reportPosition: Long
        private var maxLengthKnown: Long = -1

        private var out: BufferedFile?
        private var aux: BufferedFile?

        init {
            Objects.requireNonNull(checker)

            if (!temp.exists()) {
                if (!temp.createNewFile()) throw IOException("Cannot create a temporal file")
            }

            aux = BufferedFile(temp)
            out = BufferedFile(target)

            callback = checker

            reportPosition = NOTIFY_BYTES_INTERVAL.toLong()
        }

        @Throws(IOException::class)
        private fun flushAuxiliar(amount: Long) {
            var amount = amount
            if (aux!!.length < 1) return

            out!!.flush()
            aux!!.flush()

            val underflow = aux!!.getAbsOffset() < aux!!.length || out!!.getAbsOffset() < out!!.length
            val buffer = ByteArray(COPY_BUFFER_SIZE)

            aux!!.target.seek(0)
            out!!.target.seek(out!!.length)

            var length = amount
            while (length > 0) {
                var read = min(length, Long.MAX_VALUE).toInt()
                read = aux!!.target.read(buffer, 0, min(read, buffer.size))

                if (read < 1) {
                    amount -= length
                    break
                }

                out!!.writeProof(buffer, read)
                length -= read.toLong()
            }

            if (underflow) {
                if (out!!.getAbsOffset() >= out!!.length) {
                    // calculate the aux underflow pointer
                    if (aux!!.getAbsOffset() < amount) {
                        out!!.offset += aux!!.getAbsOffset()
                        aux!!.offset = 0
                        out!!.target.seek(out!!.getAbsOffset())
                    } else {
                        aux!!.offset -= amount
                        out!!.offset = out!!.length + amount
                    }
                } else {
                    aux!!.offset = 0
                }
            } else {
                out!!.offset += amount
                aux!!.offset -= amount
            }

            out!!.length += amount

            if (out!!.length > maxLengthKnown) {
                maxLengthKnown = out!!.length
            }

            if (amount < aux!!.length) {
                // move the excess data to the beginning of the file
                var readOffset = amount
                var writeOffset: Long = 0

                aux!!.length -= amount
                length = aux!!.length
                while (length > 0) {
                    var read = min(length, Long.MAX_VALUE).toInt()
                    read = aux!!.target.read(buffer, 0, min(read, buffer.size))
                    Logd("CircularFileWriter", "writeOffset: $writeOffset read: $read")
                    if (read <= 0) break

                    aux!!.target.seek(writeOffset)
                    aux!!.writeProof(buffer, read)

                    writeOffset += read.toLong()
                    readOffset += read.toLong()
                    length -= read.toLong()

                    aux!!.target.seek(readOffset)
                }

                aux!!.target.setLength(aux!!.length)
                return
            }

            if (aux!!.length > THRESHOLD_AUX_LENGTH) {
                aux!!.target.setLength(THRESHOLD_AUX_LENGTH.toLong()) // or setLength(0);
            }

            aux!!.reset()
        }

        /**
         * Flush any buffer and close the output file. Use this method if the
         * operation is successful
         *
         * @return the final length of the file
         * @throws IOException if an I/O error occurs
         */
        @Throws(IOException::class)
        fun finalizeFile(): Long {
            flushAuxiliar(aux!!.length)

            out!!.flush()

            // change file length (if required)
            val length = max(maxLengthKnown, out!!.length)
            if (length != out!!.target.length()) {
                out!!.target.setLength(length)
            }

            close()

            return length
        }

        /**
         * Close the file without flushing any buffer
         */
        override fun close() {
            out?.close()
            out = null
            aux?.close()
            aux = null
        }

        @Throws(IOException::class)
        override fun write(b: Byte) {
            write(byteArrayOf(b), 0, 1)
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray?) {
            write(b, 0, b!!.size)
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray?, off: Int, len: Int) {
            var off = off
            var len = len
            if (len == 0) return

            var available: Long
            val offsetOut = out!!.getAbsOffset()
            val offsetAux = aux!!.getAbsOffset()
            val end = callback.check()

            available = if (end == -1L) {
                Int.MAX_VALUE.toLong()
            } else if (end < offsetOut) {
                throw IOException("The reported offset is invalid: $end<$offsetOut")
            } else {
                end - offsetOut
            }

            val usingAux = aux!!.length > 0 && offsetOut >= out!!.length
            val underflow = offsetAux < aux!!.length || offsetOut < out!!.length

            if (usingAux) {
                // before continue calculate the final length of aux
                var length = offsetAux + len
                if (underflow) {
                    if (aux!!.length > length) length = aux!!.length // the length is not changed
                } else {
                    length = aux!!.length + len
                }

                aux!!.write(b, off, len)

                if (length >= THRESHOLD_AUX_LENGTH && length <= available) flushAuxiliar(available)

            } else {
                if (underflow) available = out!!.length - offsetOut

                val length = min(len.toLong(), min(Long.MAX_VALUE, available)).toInt()
                out!!.write(b, off, length)

                len -= length
                off += length

                if (len > 0) aux!!.write(b, off, len)
            }

            if (onProgress != null) {
                val absoluteOffset = out!!.getAbsOffset() + aux!!.getAbsOffset()
                if (absoluteOffset > reportPosition) {
                    reportPosition = absoluteOffset + NOTIFY_BYTES_INTERVAL
                    onProgress!!.report(absoluteOffset)
                }
            }
        }

        @Throws(IOException::class)
        override fun flush() {
            aux!!.flush()
            out!!.flush()

            val total = out!!.length + aux!!.length
            if (total > maxLengthKnown) {
                maxLengthKnown = total // save the current file length in case the method {@code rewind()} is called
            }
        }

        @Throws(IOException::class)
        override fun skip(amount: Long): Long {
            seek(out!!.getAbsOffset() + aux!!.getAbsOffset() + amount)
            return amount
        }

        @Throws(IOException::class)
        override fun rewind() {
            onProgress?.report(0) // rollback the whole progress

            seek(0)

            reportPosition = NOTIFY_BYTES_INTERVAL.toLong()
        }

        @Throws(IOException::class)
        override fun seek(offset: Long) {
            val total = out!!.length + aux!!.length

            if (offset == total) {
                // do not ignore the seek offset if a underflow exists
                val relativeOffset = out!!.getAbsOffset() + aux!!.getAbsOffset()
                if (relativeOffset == total) return
            }

            // flush everything, avoid any underflow
            flush()

            if (offset < 0 || offset > total) throw IOException("desired offset is outside of range=0-$total offset=$offset")

            if (offset > out!!.length) {
                out!!.seek(out!!.length)
                aux!!.seek(offset - out!!.length)
            } else {
                out!!.seek(offset)
                aux!!.seek(0)
            }
        }

        override val isClosed: Boolean
            get() = out == null

        override fun canRewind(): Boolean {
            return true
        }

        override fun canWrite(): Boolean {
            return true
        }

        override fun canSeek(): Boolean {
            return true
        }

        // <editor-fold defaultstate="collapsed" desc="stub read methods">
        override fun canRead(): Boolean {
            return false
        }

        override fun read(): Int {
            throw UnsupportedOperationException("write-only")
        }

        override fun read(buffer: ByteArray?
        ): Int {
            throw UnsupportedOperationException("write-only")
        }

        override fun read(buffer: ByteArray?, offset: Int, count: Int
        ): Int {
            throw UnsupportedOperationException("write-only")
        }

        override fun available(): Long {
            throw UnsupportedOperationException("write-only")
        }

        //</editor-fold>
        fun interface OffsetChecker {
            /**
             * Checks the amount of available space ahead
             *
             * @return absolute offset in the file where no more data SHOULD NOT be
             * written. If the value is -1 the whole file will be used
             */
            fun check(): Long
        }

        fun interface WriteErrorHandle {
            /**
             * Attempts to handle a I/O exception
             *
             * @param err the cause
             * @return `true` to retry and continue, otherwise, `false`
             * and throw the exception
             */
            fun handle(err: Exception?): Boolean
        }

        internal inner class BufferedFile {
            val target: SharpStream

            var offset: Long = 0

            var length: Long = 0

            private var queue: ByteArray? = ByteArray(QUEUE_BUFFER_SIZE)
            private var queueSize = 0

            constructor(file: File?) {
                this.target = FileStream(file!!)
            }

            constructor(target: SharpStream) {
                this.target = target
            }

            //        TODO: rename may mess up things, but otherwise it has the same signature as offset
            fun getAbsOffset(): Long {
                return offset + queueSize // absolute offset in the file
            }

            fun close() {
                queue = null
                target.close()
            }

            @Throws(IOException::class)
            fun write(b: ByteArray?, off: Int, len: Int) {
                var off = off
                var len = len
                while (len > 0) {
                    // if the queue is full, the method available() will flush the queue
                    val read = min(available(), len)

                    // enqueue incoming buffer
                    System.arraycopy(b, off, queue, queueSize, read)
                    queueSize += read

                    len -= read
                    off += read
                }

                val total = getAbsOffset() + queueSize
                if (total > length) {
                    length = total // save length
                }
            }

            @Throws(IOException::class)
            fun flush() {
                writeProof(queue, queueSize)
                offset += queueSize.toLong()
                queueSize = 0
            }

            @Throws(IOException::class)
            protected fun rewind() {
                offset = 0
                target.seek(0)
            }

            @Throws(IOException::class)
            fun available(): Int {
                if (queueSize >= queue!!.size) {
                    flush()
                    return queue!!.size
                }

                return queue!!.size - queueSize
            }

            @Throws(IOException::class)
            fun reset() {
                offset = 0
                length = 0
                target.seek(0)
            }

            @Throws(IOException::class)
            fun seek(absoluteOffset: Long) {
                if (absoluteOffset == getAbsOffset()) return  // nothing to do

                offset = absoluteOffset
                target.seek(absoluteOffset)
            }

            @Throws(IOException::class)
            fun writeProof(buffer: ByteArray?, length: Int) {
                if (onWriteError == null) {
                    target.write(buffer, 0, length)
                    return
                }

//            TODO: while loop is useless
//            while (true) {
                try {
                    target.write(buffer, 0, length)
                    return
                } catch (e: Exception) {
                    if (!onWriteError!!.handle(e)) throw e // give up
                }
//            }
            }

            override fun toString(): String {
                var absLength = try {
                    target.length().toString()
                } catch (e: IOException) {
                    "[" + e.localizedMessage + "]"
                }

                return String.format("offset=%s  length=%s  queue=%s  absLength=%s", getAbsOffset(), length, queueSize, absLength
                )
            }
        }

        companion object {
            private const val QUEUE_BUFFER_SIZE = 8 * 1024 // 8 KiB
            private const val COPY_BUFFER_SIZE = 128 * 1024 // 128 KiB
            private const val NOTIFY_BYTES_INTERVAL = 64 * 1024 // 64 KiB
            private const val THRESHOLD_AUX_LENGTH = 15 * 1024 * 1024 // 15 MiB
        }
    }

    fun interface ProgressReport {
        /**
         * Report the size of the new file
         * @param progress the new size
         */
        fun report(progress: Long)
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
