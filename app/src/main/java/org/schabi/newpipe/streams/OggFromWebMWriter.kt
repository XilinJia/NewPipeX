package org.schabi.newpipe.streams

import org.schabi.newpipe.streams.WebMReader.*
import org.schabi.newpipe.streams.io.SharpStream
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.min

/**
 * @author kapodamy
 */
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
