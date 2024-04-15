package us.shandian.giga.postprocessing

import org.schabi.newpipe.streams.OggFromWebMWriter
import org.schabi.newpipe.streams.io.SharpStream
import java.io.IOException
import java.nio.ByteBuffer

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
        val demuxer = OggFromWebMWriter((sources.filterNotNull().toTypedArray())[0], out!!)
        demuxer.parseSource()
        demuxer.selectTrack(0)
        demuxer.build()

        return OK_RESULT.toInt()
    }
}
