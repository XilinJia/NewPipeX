package org.schabi.newpipe.streams

import org.schabi.newpipe.streams.WebMReader.Cluster
import org.schabi.newpipe.streams.WebMReader.WebMTrack
import org.schabi.newpipe.streams.io.SharpStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow

/**
 * @author kapodamy
 */
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
                val write = min(size.toDouble(), outBuffer!!.size.toDouble()).toInt()
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
