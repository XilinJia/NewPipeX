package org.schabi.newpipe.streams

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.schabi.newpipe.streams.io.SharpStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * @author kapodamy
 */
class SrtFromTtmlWriter(private val out: SharpStream, private val ignoreEmptyFrames: Boolean) {
    private val charset: Charset = StandardCharsets.UTF_8

    private var frameIndex = 0

    @Throws(IOException::class)
    private fun writeFrame(begin: String, end: String, text: StringBuilder) {
        writeString(frameIndex++.toString())
        writeString(NEW_LINE)
        writeString(begin)
        writeString(" --> ")
        writeString(end)
        writeString(NEW_LINE)
        writeString(text.toString())
        writeString(NEW_LINE)
        writeString(NEW_LINE)
    }

    @Throws(IOException::class)
    private fun writeString(text: String) {
        out.write(text.toByteArray(charset))
    }

    @Throws(IOException::class)
    fun build(ttml: SharpStream) {
        /*
         * TTML parser with BASIC support
         * multiple CUE is not supported
         * styling is not supported
         * tag timestamps (in auto-generated subtitles) are not supported, maybe in the future
         * also TimestampTagOption enum is not applicable
         * Language parsing is not supported
         */

        // parse XML

        val buffer = ByteArray(ttml.available().toInt())
        ttml.read(buffer)
        val doc = Jsoup.parse(ByteArrayInputStream(buffer), "UTF-8", "",
            Parser.xmlParser())

        val text = StringBuilder(128)
        val paragraphList = doc.select("body > div > p")

        // check if has frames
        if (paragraphList.size < 1) {
            return
        }

        for (paragraph in paragraphList) {
            text.setLength(0)

            for (children in paragraph.childNodes()) {
                if (children is TextNode) {
                    text.append(children.text())
                } else if (children is Element
                        && children.tagName().equals("br", ignoreCase = true)) {
                    text.append(NEW_LINE)
                }
            }

            if (ignoreEmptyFrames && text.length < 1) {
                continue
            }

            val begin = getTimestamp(paragraph, "begin")
            val end = getTimestamp(paragraph, "end")

            writeFrame(begin, end, text)
        }
    }

    companion object {
        private const val NEW_LINE = "\r\n"

        private fun getTimestamp(frame: Element, attr: String): String {
            return frame
                .attr(attr)
                .replace('.', ',') // SRT subtitles uses comma as decimal separator
        }
    }
}
