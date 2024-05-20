package us.shandian.giga.io

import android.util.Log
import org.schabi.newpipe.streams.io.SharpStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.min
import kotlin.math.max

/**
 * @author kapodamy
 */
class FileStream : SharpStream {
    var source: RandomAccessFile?

    constructor(target: File) {
        this.source = RandomAccessFile(target, "rw")
    }

    constructor(path: String) {
        this.source = RandomAccessFile(path, "rw")
    }

    @Throws(IOException::class)
    override fun read(): Int {
        return source!!.read()
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray?): Int {
        return source!!.read(b)
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray?, off: Int, len: Int): Int {
        if (b == null || b.isEmpty() || len <= 0) return 0
        return source!!.read(b, off, min(len, b.size-off))
    }

    @Throws(IOException::class)
    override fun skip(pos: Long): Long {
        return source!!.skipBytes(pos.toInt()).toLong()
    }

    override fun available(): Long {
        return try {
            source!!.length() - source!!.filePointer
        } catch (e: IOException) {
            0
        }
    }

    override fun close() {
        try {
            source?.close()
        } catch (err: IOException) {
            // nothing to do
        }
        source = null
    }

    override val isClosed: Boolean
        get() = source == null

    @Throws(IOException::class)
    override fun rewind() {
        source!!.seek(0)
    }

    override fun canRewind(): Boolean {
        return true
    }

    override fun canRead(): Boolean {
        return true
    }

    override fun canWrite(): Boolean {
        return true
    }

    override fun canSeek(): Boolean {
        return true
    }

    override fun canSetLength(): Boolean {
        return true
    }

    @Throws(IOException::class)
    override fun write(value: Byte) {
        source!!.write(value.toInt())
    }

    @Throws(IOException::class)
    override fun write(buffer: ByteArray?) {
        source!!.write(buffer)
    }

    @Throws(IOException::class)
    override fun write(buffer: ByteArray?, offset: Int, count: Int) {
        if (buffer == null || buffer.isEmpty() || count <= 0) return
//        Logd("FileStream", "${buffer.size} $offset $count ${buffer.size-offset}")
        source!!.write(buffer, offset, min(count, buffer.size-offset))
    }

    @Throws(IOException::class)
    override fun setLength(length: Long) {
        source!!.setLength(length)
    }

    @Throws(IOException::class)
    override fun seek(offset: Long) {
        source!!.seek(offset)
//        source!!.seek(max(0, offset))
    }

    @Throws(IOException::class)
    override fun length(): Long {
        return source!!.length()
    }
}
