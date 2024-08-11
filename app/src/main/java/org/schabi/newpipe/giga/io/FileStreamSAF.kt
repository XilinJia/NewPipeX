package org.schabi.newpipe.giga.io

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import org.schabi.newpipe.giga.io.SharpStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel

class FileStreamSAF(contentResolver: ContentResolver, fileUri: Uri) : SharpStream() {
    private val `in`: FileInputStream
    private val out: FileOutputStream
    private val channel: FileChannel

    // Notes:
    // the file must exists first
    // ¡read-write mode must allow seek!
    // It is not guaranteed to work with files in the cloud (virtual files), tested in local storage devices
    private val file = contentResolver.openFileDescriptor(fileUri, "rw")

    override var isClosed: Boolean = false
        private set

    init {
        if (file == null) {
            throw IOException("Cannot get the ParcelFileDescriptor for $fileUri")
        }

        `in` = FileInputStream(file.fileDescriptor)
        out = FileOutputStream(file.fileDescriptor)
        channel = out.channel // or use in.getChannel()
    }

    @Throws(IOException::class)
    override fun read(): Int {
        return `in`.read()
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray?): Int {
        return `in`.read(buffer)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray?, offset: Int, count: Int): Int {
        return `in`.read(buffer, offset, count)
    }

    @Throws(IOException::class)
    override fun skip(amount: Long): Long {
        return `in`.skip(amount) // ¿or use channel.position(channel.position() + amount)?
    }

    override fun available(): Long {
        return try {
            `in`.available().toLong()
        } catch (e: IOException) {
            0 // ¡but not -1!
        }
    }

    @Throws(IOException::class)
    override fun rewind() {
        seek(0)
    }

    override fun close() {
        try {
            isClosed = true

            file!!.close()
            `in`.close()
            out.close()
            channel.close()
        } catch (e: IOException) {
            Log.e("FileStreamSAF", "close() error", e)
        }
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

    override fun canSetLength(): Boolean {
        return true
    }

    override fun canSeek(): Boolean {
        return true
    }

    @Throws(IOException::class)
    override fun write(value: Byte) {
        out.write(value.toInt())
    }

    @Throws(IOException::class)
    override fun write(buffer: ByteArray?) {
        out.write(buffer)
    }

    @Throws(IOException::class)
    override fun write(buffer: ByteArray?, offset: Int, count: Int) {
        out.write(buffer, offset, count)
    }

    @Throws(IOException::class)
    override fun setLength(length: Long) {
        channel.truncate(length)
    }

    @Throws(IOException::class)
    override fun seek(offset: Long) {
        channel.position(offset)
    }

    @Throws(IOException::class)
    override fun length(): Long {
        return channel.size()
    }
}
