package us.shandian.giga.postprocessing

import org.schabi.newpipe.streams.Mp4DashReader
import org.schabi.newpipe.streams.Mp4FromDashWriter
import org.schabi.newpipe.streams.io.SharpStream
import java.io.IOException

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
