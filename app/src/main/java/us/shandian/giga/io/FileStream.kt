package us.shandian.giga.io

import org.schabi.newpipe.streams.io.SharpStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

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
        return source!!.read(b, off, len)
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
        if (source == null) return
        try {
            source!!.close()
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
        source!!.write(buffer, offset, count)
    }

    @Throws(IOException::class)
    override fun setLength(length: Long) {
        source!!.setLength(length)
    }

    @Throws(IOException::class)
    override fun seek(offset: Long) {
        source!!.seek(offset)
    }

    @Throws(IOException::class)
    override fun length(): Long {
        return source!!.length()
    }
}
