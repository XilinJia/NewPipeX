package org.schabi.newpipe.streams

import org.schabi.newpipe.streams.io.SharpStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * @author kapodamy
 */
class Mp4DashReader(source: SharpStream?) {
    private val stream = DataReader(source!!)

    var availableTracks: Array<Mp4Track?>? = null
        private set
    private var brands: IntArray? = null

    private var box: Box? = null
    private var moof: Moof? = null

    private var chunkZero = false

    private var selectedTrack = -1
    private var backupBox: Box? = null

    enum class TrackKind {
        Audio, Video, Subtitles, Other
    }

    @Throws(IOException::class, NoSuchElementException::class)
    fun parse() {
        if (selectedTrack > -1) return

        box = readBox(ATOM_FTYP)
        brands = parseFtyp(box)
        when (brands!![0]) {
            BRAND_DASH, BRAND_ISO5 -> {}
            else -> throw NoSuchElementException("Not a MPEG-4 DASH container, major brand is not 'dash' or 'iso5' is ${boxName(brands!![0])}")
        }
        var moov: Moov? = null

        while (box!!.type != ATOM_MOOF) {
            ensure(box)
            box = readBox()

            when (box!!.type) {
                ATOM_MOOV -> moov = parseMoov(box)
                ATOM_SIDX, ATOM_MFRA -> {}
            }
        }

        if (moov == null) {
            throw IOException("The provided Mp4 doesn't have the 'moov' box")
        }

        availableTracks = arrayOfNulls(moov.trak.size)

        var i = 0
        while (i < availableTracks!!.size) {
            availableTracks!![i] = Mp4Track()
            availableTracks!![i]!!.trak = moov.trak[i]

            if (moov.mvexTrex != null) {
                for (mvexTrex in moov.mvexTrex!!) {
                    if (availableTracks!![i]!!.trak!!.tkhd!!.trackId == mvexTrex.trackId) {
                        availableTracks!![i]!!.trex = mvexTrex
                    }
                }
            }

            when (moov.trak[i].mdia!!.hdlr!!.subType) {
                HANDLER_VIDE -> availableTracks!![i]!!.kind = TrackKind.Video
                HANDLER_SOUN -> availableTracks!![i]!!.kind = TrackKind.Audio
                HANDLER_SUBT -> availableTracks!![i]!!.kind = TrackKind.Subtitles
                else -> availableTracks!![i]!!.kind = TrackKind.Other
            }
            i++
        }

        backupBox = box
    }

    fun selectTrack(index: Int): Mp4Track? {
        selectedTrack = index
        return availableTracks!![index]
    }

    fun getBrands(): IntArray {
        return brands ?: intArrayOf()
    }

    @Throws(IOException::class)
    fun rewind() {
        if (!stream.canRewind()) throw IOException("The provided stream doesn't allow seek")

        if (box == null) return

        box = backupBox
        chunkZero = false

        stream.rewind()
        stream.skipBytes(backupBox!!.offset + (DataReader.INTEGER_SIZE * 2))
    }

    @Throws(IOException::class)
    fun getNextChunk(infoOnly: Boolean): Mp4DashChunk? {
        val track = availableTracks!![selectedTrack]

        while (stream.available()) {
            if (chunkZero) {
                ensure(box)
                if (!stream.available()) break

                box = readBox()
            } else {
                chunkZero = true
            }

            when (box!!.type) {
                ATOM_MOOF -> {
                    if (moof != null) throw IOException("moof found without mdat")

                    moof = parseMoof(box, track!!.trak!!.tkhd!!.trackId)

                    if (moof!!.traf != null) {
                        if (hasFlag(moof!!.traf!!.trun!!.bFlags, 0x0001)) {
                            moof!!.traf!!.trun!!.dataOffset -= (box!!.size + 8).toInt()
                            if (moof!!.traf!!.trun!!.dataOffset < 0) throw IOException("trun box has wrong data offset, points outside of concurrent mdat box")
                        }

                        if (moof!!.traf!!.trun!!.chunkSize < 1) {
                            if (hasFlag(moof!!.traf!!.tfhd!!.bFlags, 0x10)) {
                                moof!!.traf!!.trun!!.chunkSize = (moof!!.traf!!.tfhd!!.defaultSampleSize * moof!!.traf!!.trun!!.entryCount)
                            } else {
                                moof!!.traf!!.trun!!.chunkSize = (box!!.size - 8).toInt()
                            }
                        }
                        if (!hasFlag(moof!!.traf!!.trun!!.bFlags, 0x900) && moof!!.traf!!.trun!!.chunkDuration == 0) {
                            if (hasFlag(moof!!.traf!!.tfhd!!.bFlags, 0x20)) {
                                moof!!.traf!!.trun!!.chunkDuration = (moof!!.traf!!.tfhd!!.defaultSampleDuration * moof!!.traf!!.trun!!.entryCount)
                            }
                        }
                    }
                }
                ATOM_MDAT -> {
                    if (moof == null) throw IOException("mdat found without moof")

                    if (moof!!.traf == null) {
                        moof = null
                        continue  // find another chunk
                    }

                    val chunk = Mp4DashChunk()
                    chunk.moof = moof
                    if (!infoOnly) {
                        chunk.data = stream.getView(moof!!.traf!!.trun!!.chunkSize)
                    }

                    moof = null

                    stream.skipBytes(chunk.moof!!.traf!!.trun!!.dataOffset.toLong())
                    return chunk
                }
                else -> {}
            }
        }

        return null
    }

    private fun boxName(ref: Box?): String {
        return boxName(ref!!.type)
    }

    private fun boxName(type: Int): String {
        return String(ByteBuffer.allocate(4).putInt(type).array(), StandardCharsets.UTF_8)
    }

    @Throws(IOException::class)
    private fun readBox(): Box {
        val b = Box()
        b.offset = stream.position()
        b.size = stream.readUnsignedInt()
        b.type = stream.readInt()

        if (b.size == 1L) b.size = stream.readLong()

        return b
    }

    @Throws(IOException::class)
    private fun readBox(expected: Int): Box {
        val b = readBox()
        if (b.type != expected) throw NoSuchElementException("expected ${boxName(expected)} found ${boxName(b)}")

        return b
    }

    @Throws(IOException::class)
    private fun readFullBox(ref: Box): ByteArray {
        // full box reading is limited to 2 GiB, and should be enough
        val size = ref.size.toInt()

        val buffer = ByteBuffer.allocate(size)
        buffer.putInt(size)
        buffer.putInt(ref.type)

        val read = size - 8

        if (stream.read(buffer.array(), 8, read) != read)
            throw EOFException(String.format("EOF reached in box: type=%s offset=%s size=%s", boxName(ref.type), ref.offset, ref.size))

        return buffer.array()
    }

    @Throws(IOException::class)
    private fun ensure(ref: Box?) {
        if (ref == null || ref.size == 0L) return
        val skip = ref.offset + ref.size - stream.position()

        if (skip == 0L) return
        if (skip < 0)
            throw EOFException(String.format("parser go beyond limits of the box. type=%s offset=%s size=%s position=%s", boxName(ref), ref.offset, ref.size, stream.position()))

        stream.skipBytes(skip.toInt().toLong())
    }

    @Throws(IOException::class)
    private fun untilBox(ref: Box?, vararg expected: Int): Box? {
        if (ref == null) return null
        var b: Box
        while (stream.position() < (ref.offset + ref.size)) {
            b = readBox()
            for (type in expected) {
                if (b.type == type) return b
            }
            ensure(b)
        }

        return null
    }

    @Throws(IOException::class)
    private fun untilAnyBox(ref: Box?): Box? {
        if (ref == null) return null
        if (stream.position() >= (ref.offset + ref.size)) return null

        return readBox()
    }

    @Throws(IOException::class)
    private fun parseMoof(ref: Box?, trackId: Int): Moof {
        val obj = Moof()

        var b: Box? = readBox(ATOM_MFHD)
        obj.mfhdSequenceNumber = parseMfhd()
        ensure(b)

        while ((untilBox(ref, ATOM_TRAF).also { b = it }) != null) {
            obj.traf = parseTraf(b, trackId)
            ensure(b)

            if (obj.traf != null) return obj
        }

        return obj
    }

    @Throws(IOException::class)
    private fun parseMfhd(): Int {
        // version
        // flags
        stream.skipBytes(4)

        return stream.readInt()
    }

    @Throws(IOException::class)
    private fun parseTraf(ref: Box?, trackId: Int): Traf? {
        val traf = Traf()

        var b: Box? = readBox(ATOM_TFHD)
        traf.tfhd = parseTfhd(trackId)
        ensure(b)

        if (traf.tfhd == null) return null

        b = untilBox(ref, ATOM_TRUN, ATOM_TFDT)

        if (b!!.type == ATOM_TFDT) {
            traf.tfdt = parseTfdt()
            ensure(b)
            b = readBox(ATOM_TRUN)
        }

        traf.trun = parseTrun()
        ensure(b)

        return traf
    }

    @Throws(IOException::class)
    private fun parseTfhd(trackId: Int): Tfhd? {
        val obj = Tfhd()

        obj.bFlags = stream.readInt()
        obj.trackId = stream.readInt()

        if (trackId != -1 && obj.trackId != trackId) return null

        if (hasFlag(obj.bFlags, 0x01)) {
            stream.skipBytes(8)
        }
        if (hasFlag(obj.bFlags, 0x02)) {
            stream.skipBytes(4)
        }
        if (hasFlag(obj.bFlags, 0x08)) {
            obj.defaultSampleDuration = stream.readInt()
        }
        if (hasFlag(obj.bFlags, 0x10)) {
            obj.defaultSampleSize = stream.readInt()
        }
        if (hasFlag(obj.bFlags, 0x20)) {
            obj.defaultSampleFlags = stream.readInt()
        }

        return obj
    }

    @Throws(IOException::class)
    private fun parseTfdt(): Long {
        val version = stream.read()
        stream.skipBytes(3) // flags
        return if (version == 0) stream.readUnsignedInt() else stream.readLong()
    }

    @Throws(IOException::class)
    private fun parseTrun(): Trun {
        val obj = Trun()
        obj.bFlags = stream.readInt()
        obj.entryCount = stream.readInt() // unsigned int

        obj.entriesRowSize = 0
        if (hasFlag(obj.bFlags, 0x0100)) {
            obj.entriesRowSize += 4
        }
        if (hasFlag(obj.bFlags, 0x0200)) {
            obj.entriesRowSize += 4
        }
        if (hasFlag(obj.bFlags, 0x0400)) {
            obj.entriesRowSize += 4
        }
        if (hasFlag(obj.bFlags, 0x0800)) {
            obj.entriesRowSize += 4
        }
        obj.bEntries = ByteArray(obj.entriesRowSize * obj.entryCount)

        if (hasFlag(obj.bFlags, 0x0001)) {
            obj.dataOffset = stream.readInt()
        }
        if (hasFlag(obj.bFlags, 0x0004)) {
            obj.bFirstSampleFlags = stream.readInt()
        }

        stream.read(obj.bEntries)

        for (i in 0 until obj.entryCount) {
            val entry = obj.getEntry(i)
            if (hasFlag(obj.bFlags, 0x0100)) {
                obj.chunkDuration += entry.sampleDuration
            }
            if (hasFlag(obj.bFlags, 0x0200)) {
                obj.chunkSize += entry.sampleSize
            }
            if (hasFlag(obj.bFlags, 0x0800)) {
                if (!hasFlag(obj.bFlags, 0x0100)) {
                    obj.chunkDuration += entry.sampleCompositionTimeOffset
                }
            }
        }

        return obj
    }

    @Throws(IOException::class)
    private fun parseFtyp(ref: Box?): IntArray {
        var i = 0
        val list = IntArray(((ref!!.offset + ref.size - stream.position() - 4) / 4).toInt())

        list[i++] = stream.readInt() // major brand

        stream.skipBytes(4) // minor version

        while (i < list.size) {
            list[i] = stream.readInt() // compatible brands
            i++
        }

        return list
    }

    @Throws(IOException::class)
    private fun parseMvhd(): Mvhd {
        val version = stream.read()
        stream.skipBytes(3) // flags

        // creation entries_time
        // modification entries_time
        stream.skipBytes((2 * (if (version == 0) 4 else 8)).toLong())

        val obj = Mvhd()
        obj.timeScale = stream.readUnsignedInt()

        // chunkDuration
        stream.skipBytes((if (version == 0) 4 else 8).toLong())

        // rate
        // volume
        // reserved
        // matrix array
        // predefined
        stream.skipBytes(76)

        obj.nextTrackId = stream.readUnsignedInt()

        return obj
    }

    @Throws(IOException::class)
    private fun parseTkhd(): Tkhd {
        val version = stream.read()

        val obj = Tkhd()

        // flags
        // creation entries_time
        // modification entries_time
        stream.skipBytes((3 + (2 * (if (version == 0) 4 else 8))).toLong())

        obj.trackId = stream.readInt()

        stream.skipBytes(4) // reserved

        obj.duration = if (version == 0) stream.readUnsignedInt() else stream.readLong()

        stream.skipBytes((2 * 4).toLong()) // reserved

        obj.bLayer = stream.readShort()
        obj.bAlternateGroup = stream.readShort()
        obj.bVolume = stream.readShort()

        stream.skipBytes(2) // reserved

        obj.matrix = ByteArray(9 * 4)
        stream.read(obj.matrix)

        obj.bWidth = stream.readInt()
        obj.bHeight = stream.readInt()

        return obj
    }

    @Throws(IOException::class)
    private fun parseTrak(ref: Box?): Trak {
        val trak = Trak()

        var b: Box? = readBox(ATOM_TKHD)
        trak.tkhd = parseTkhd()
        ensure(b)

        while ((untilBox(ref, ATOM_MDIA, ATOM_EDTS).also { b = it }) != null) {
            if (b == null) continue
            when (b!!.type) {
                ATOM_MDIA -> trak.mdia = parseMdia(b!!)
                ATOM_EDTS -> trak.edstElst = parseEdts(b!!)
            }
            ensure(b)
        }

        return trak
    }

    @Throws(IOException::class)
    private fun parseMdia(ref: Box?): Mdia {
        val obj = Mdia()

        var b: Box?
        while ((untilBox(ref, ATOM_MDHD, ATOM_HDLR, ATOM_MINF).also { b = it }) != null) {
            if (b == null) continue
            when (b!!.type) {
                ATOM_MDHD -> {
                    obj.mdhd = readFullBox(b!!)

                    // read time scale
                    val buffer = ByteBuffer.wrap(obj.mdhd)
                    val version = buffer[8]
                    buffer.position(12 + ((if (version.toInt() == 0) 4 else 8) * 2))
                    obj.mdhdTimeScale = buffer.getInt()
                }
                ATOM_HDLR -> obj.hdlr = parseHdlr(b!!)
                ATOM_MINF -> obj.minf = parseMinf(b!!)
            }
            ensure(b)
        }

        return obj
    }

    @Throws(IOException::class)
    private fun parseHdlr(ref: Box): Hdlr {
        // version
        // flags
        stream.skipBytes(4)

        val obj = Hdlr()
        obj.bReserved = ByteArray(12)

        obj.type = stream.readInt()
        obj.subType = stream.readInt()
        stream.read(obj.bReserved)

        // component name (is a ansi/ascii string)
        stream.skipBytes((ref.offset + ref.size) - stream.position())

        return obj
    }

    @Throws(IOException::class)
    private fun parseMoov(ref: Box?): Moov {
        var b: Box? = readBox(ATOM_MVHD)
        val moov = Moov()
        moov.mvhd = parseMvhd()
        ensure(b)

        val tmp = ArrayList<Trak>(moov.mvhd!!.nextTrackId.toInt())
        while ((untilBox(ref, ATOM_TRAK, ATOM_MVEX).also { b = it }) != null) {
            if (b == null) continue
            when (b!!.type) {
                ATOM_TRAK -> tmp.add(parseTrak(b!!))
                ATOM_MVEX -> moov.mvexTrex = parseMvex(b!!, moov.mvhd!!.nextTrackId.toInt())
            }
            ensure(b)
        }

        moov.trak = tmp.toTypedArray<Trak>()

        return moov
    }

    @Throws(IOException::class)
    private fun parseMvex(ref: Box, possibleTrackCount: Int): Array<Trex> {
        val tmp = ArrayList<Trex>(possibleTrackCount)

        var b: Box?
        while ((untilBox(ref, ATOM_TREX).also { b = it }) != null) {
            tmp.add(parseTrex())
            ensure(b)
        }

        return tmp.toTypedArray<Trex>()
    }

    @Throws(IOException::class)
    private fun parseTrex(): Trex {
        // version
        // flags
        stream.skipBytes(4)

        val obj = Trex()
        obj.trackId = stream.readInt()
        obj.defaultSampleDescriptionIndex = stream.readInt()
        obj.defaultSampleDuration = stream.readInt()
        obj.defaultSampleSize = stream.readInt()
        obj.defaultSampleFlags = stream.readInt()

        return obj
    }

    @Throws(IOException::class)
    private fun parseEdts(ref: Box): Elst? {
        val b = untilBox(ref, ATOM_ELST) ?: return null

        val obj = Elst()

        val v1 = stream.read() == 1
        stream.skipBytes(3) // flags

        val entryCount = stream.readInt()
        if (entryCount < 1) {
            obj.bMediaRate = 0x00010000 // default media rate (1.0)
            return obj
        }

        if (v1) {
            stream.skipBytes(DataReader.LONG_SIZE.toLong()) // segment duration
            obj.mediaTime = stream.readLong()
            // ignore all remain entries
            stream.skipBytes(((entryCount - 1) * (DataReader.LONG_SIZE * 2)).toLong())
        } else {
            stream.skipBytes(DataReader.INTEGER_SIZE.toLong()) // segment duration
            obj.mediaTime = stream.readInt().toLong()
        }

        obj.bMediaRate = stream.readInt()

        return obj
    }

    @Throws(IOException::class)
    private fun parseMinf(ref: Box): Minf {
        val obj = Minf()

        var b: Box?
        while ((untilAnyBox(ref).also { b = it }) != null) {
            if (b == null) continue
            when (b!!.type) {
                ATOM_DINF -> obj.dinf = readFullBox(b!!)
                ATOM_STBL -> obj.stblStsd = parseStbl(b!!)
                ATOM_VMHD, ATOM_SMHD -> obj.mhd = readFullBox(b!!)
            }
            ensure(b)
        }

        return obj
    }

    /**
     * This only reads the "stsd" box inside.
     *
     * @param ref stbl box
     * @return stsd box inside
     */
    @Throws(IOException::class)
    private fun parseStbl(ref: Box): ByteArray {
        val b = untilBox(ref, ATOM_STSD) ?: return ByteArray(0) // this never should happens (missing codec startup data)

        return readFullBox(b)
    }

    internal class Box {
        var type: Int = 0
        var offset: Long = 0
        var size: Long = 0
    }

    class Moof {
        var mfhdSequenceNumber: Int = 0
        @JvmField
        var traf: Traf? = null
    }

    class Traf {
        @JvmField
        var tfhd: Tfhd? = null
        var tfdt: Long = 0
        @JvmField
        var trun: Trun? = null
    }

    class Tfhd {
        var bFlags: Int = 0
        var trackId: Int = 0
        @JvmField
        var defaultSampleDuration: Int = 0
        var defaultSampleSize: Int = 0
        var defaultSampleFlags: Int = 0
    }

    class TrunEntry {
        @JvmField
        var sampleDuration: Int = 0
        @JvmField
        var sampleSize: Int = 0
        var sampleFlags: Int = 0
        @JvmField
        var sampleCompositionTimeOffset: Int = 0

        @JvmField
        var hasCompositionTimeOffset: Boolean = false
        @JvmField
        var isKeyframe: Boolean = false
    }

    class Trun {
        @JvmField
        var chunkDuration: Int = 0
        @JvmField
        var chunkSize: Int = 0

        var bFlags: Int = 0
        var bFirstSampleFlags: Int = 0
        var dataOffset: Int = 0

        var entryCount: Int = 0
        var bEntries: ByteArray = byteArrayOf()
        var entriesRowSize: Int = 0

        fun getEntry(i: Int): TrunEntry {
            val buffer = ByteBuffer.wrap(bEntries, i * entriesRowSize, entriesRowSize)
            val entry = TrunEntry()

            if (hasFlag(bFlags, 0x0100)) {
                entry.sampleDuration = buffer.getInt()
            }
            if (hasFlag(bFlags, 0x0200)) {
                entry.sampleSize = buffer.getInt()
            }
            if (hasFlag(bFlags, 0x0400)) {
                entry.sampleFlags = buffer.getInt()
            }
            if (hasFlag(bFlags, 0x0800)) {
                entry.sampleCompositionTimeOffset = buffer.getInt()
            }

            entry.hasCompositionTimeOffset = hasFlag(bFlags, 0x0800)
            entry.isKeyframe = !hasFlag(entry.sampleFlags, 0x10000)

            return entry
        }

        fun getAbsoluteEntry(i: Int, header: Tfhd?): TrunEntry {
            val entry = getEntry(i)

            if (!hasFlag(bFlags, 0x0100) && hasFlag(header!!.bFlags, 0x20)) {
                entry.sampleFlags = header.defaultSampleFlags
            }

            if (!hasFlag(bFlags, 0x0200) && hasFlag(header!!.bFlags, 0x10)) {
                entry.sampleSize = header.defaultSampleSize
            }

            if (!hasFlag(bFlags, 0x0100) && hasFlag(header!!.bFlags, 0x08)) {
                entry.sampleDuration = header.defaultSampleDuration
            }

            if (i == 0 && hasFlag(bFlags, 0x0004)) {
                entry.sampleFlags = bFirstSampleFlags
            }

            return entry
        }
    }

    class Tkhd {
        var trackId: Int = 0
        @JvmField
        var duration: Long = 0
        @JvmField
        var bVolume: Short = 0
        @JvmField
        var bWidth: Int = 0
        @JvmField
        var bHeight: Int = 0
        @JvmField
        var matrix: ByteArray = byteArrayOf()
        @JvmField
        var bLayer: Short = 0
        @JvmField
        var bAlternateGroup: Short = 0
    }

    class Trak {
        @JvmField
        var tkhd: Tkhd? = null
        @JvmField
        var edstElst: Elst? = null
        @JvmField
        var mdia: Mdia? = null
    }

    internal class Mvhd {
        var timeScale: Long = 0
        var nextTrackId: Long = 0
    }

    internal class Moov {
        var mvhd: Mvhd? = null
        var trak: Array<Trak> = arrayOf()
        var mvexTrex: Array<Trex>? = null
    }

    class Trex {
        var trackId: Int = 0
        var defaultSampleDescriptionIndex: Int = 0
        var defaultSampleDuration: Int = 0
        var defaultSampleSize: Int = 0
        var defaultSampleFlags: Int = 0
    }

    class Elst {
        @JvmField
        var mediaTime: Long = 0
        @JvmField
        var bMediaRate: Int = 0
    }

    class Mdia {
        @JvmField
        var mdhdTimeScale: Int = 0
        @JvmField
        var mdhd: ByteArray = byteArrayOf()
        @JvmField
        var hdlr: Hdlr? = null
        @JvmField
        var minf: Minf? = null
    }

    class Hdlr {
        @JvmField
        var type: Int = 0
        @JvmField
        var subType: Int = 0
        @JvmField
        var bReserved: ByteArray = byteArrayOf()
    }

    class Minf {
        @JvmField
        var dinf: ByteArray = byteArrayOf()
        @JvmField
        var stblStsd: ByteArray = byteArrayOf()
        @JvmField
        var mhd: ByteArray = byteArrayOf()
    }

    class Mp4Track {
        @JvmField
        var kind: TrackKind? = null
        @JvmField
        var trak: Trak? = null
        var trex: Trex? = null
    }

    class Mp4DashChunk {
        var data: InputStream? = null
        @JvmField
        var moof: Moof? = null
        private var i = 0

        val nextSampleInfo: TrunEntry?
            get() {
                if (i >= moof!!.traf!!.trun!!.entryCount) return null

                return moof!!.traf!!.trun!!.getAbsoluteEntry(i++, moof!!.traf!!.tfhd)
            }

        @get:Throws(IOException::class)
        val nextSample: Mp4DashSample?
            get() {
                checkNotNull(data) { "This chunk has info only" }
                if (i >= moof!!.traf!!.trun!!.entryCount) return null

                val sample = Mp4DashSample()
                sample.info = moof!!.traf!!.trun!!.getAbsoluteEntry(i++, moof!!.traf!!.tfhd)
                sample.data = ByteArray(sample.info!!.sampleSize)

                if (data!!.read(sample.data) != sample.info!!.sampleSize) {
                    throw EOFException("EOF reached while reading a sample")
                }

                return sample
            }
    }

    class Mp4DashSample {
        @JvmField
        var info: TrunEntry? = null
        @JvmField
        var data: ByteArray = byteArrayOf()
    }

    companion object {
        private const val ATOM_MOOF = 0x6D6F6F66
        private const val ATOM_MFHD = 0x6D666864
        private const val ATOM_TRAF = 0x74726166
        private const val ATOM_TFHD = 0x74666864
        private const val ATOM_TFDT = 0x74666474
        private const val ATOM_TRUN = 0x7472756E
        private const val ATOM_MDIA = 0x6D646961
        private const val ATOM_FTYP = 0x66747970
        private const val ATOM_SIDX = 0x73696478
        private const val ATOM_MOOV = 0x6D6F6F76
        private const val ATOM_MDAT = 0x6D646174
        private const val ATOM_MVHD = 0x6D766864
        private const val ATOM_TRAK = 0x7472616B
        private const val ATOM_MVEX = 0x6D766578
        private const val ATOM_TREX = 0x74726578
        private const val ATOM_TKHD = 0x746B6864
        private const val ATOM_MFRA = 0x6D667261
        private const val ATOM_MDHD = 0x6D646864
        private const val ATOM_EDTS = 0x65647473
        private const val ATOM_ELST = 0x656C7374
        private const val ATOM_HDLR = 0x68646C72
        private const val ATOM_MINF = 0x6D696E66
        private const val ATOM_DINF = 0x64696E66
        private const val ATOM_STBL = 0x7374626C
        private const val ATOM_STSD = 0x73747364
        private const val ATOM_VMHD = 0x766D6864
        private const val ATOM_SMHD = 0x736D6864

        private const val BRAND_DASH = 0x64617368
        private const val BRAND_ISO5 = 0x69736F35

        private const val HANDLER_VIDE = 0x76696465
        private const val HANDLER_SOUN = 0x736F756E
        private const val HANDLER_SUBT = 0x73756274

        fun hasFlag(flags: Int, mask: Int): Boolean {
            return (flags and mask) == mask
        }
    }
}
