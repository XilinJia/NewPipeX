package us.shandian.giga.postprocessing

import org.schabi.newpipe.streams.WebMReader
import org.schabi.newpipe.streams.WebMWriter
import org.schabi.newpipe.streams.io.SharpStream
import java.io.IOException

/**
 * @author kapodamy
 */
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
}
