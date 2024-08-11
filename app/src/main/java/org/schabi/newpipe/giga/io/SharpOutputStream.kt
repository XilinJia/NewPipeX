package org.schabi.newpipe.giga.io

import java.io.IOException
import java.io.OutputStream

/**
 * Simply wraps a writable [SharpStream] allowing it to be used with built-in Java stuff that
 * supports [OutputStream].
 */
class SharpOutputStream(stream: SharpStream) : OutputStream() {
    private val stream: SharpStream

    init {
        if (!stream.canWrite()) {
            throw IOException("SharpStream is not writable")
        }
        this.stream = stream
    }

    @Throws(IOException::class)
    override fun write(b: Int) {
        stream.write(b.toByte())
    }

    @Throws(IOException::class)
    override fun write(b: ByteArray) {
        stream.write(b)
    }

    @Throws(IOException::class)
    override fun write(b: ByteArray, off: Int, len: Int) {
        stream.write(b, off, len)
    }

    @Throws(IOException::class)
    override fun flush() {
        stream.flush()
    }

    override fun close() {
        stream.close()
    }
}
