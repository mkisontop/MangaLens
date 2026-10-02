package app.mangalens.capture

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageBufferPixelsTest {

    /** A capture buffer: RGBA bytes, rows padded out to [stride] pixels, as a display surface hands them over. */
    private fun frame(w: Int, h: Int, stride: Int, colour: (Int, Int) -> Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(stride * h * 4)
        for (y in 0 until h) for (x in 0 until stride) {
            val c = if (x < w) colour(x, y) else 0x7F7F7F7F
            buf.put(((y * stride) + x) * 4, (c ushr 24).toByte())      // R first in memory
            buf.put(((y * stride) + x) * 4 + 1, (c ushr 16).toByte())  // G
            buf.put(((y * stride) + x) * 4 + 2, (c ushr 8).toByte())   // B
            buf.put(((y * stride) + x) * 4 + 3, c.toByte())            // A
        }
        return buf
    }

    @Test
    fun `rows and single pixels come out of a padded buffer`() {
        val w = 37
        val h = 11
        val stride = 48
        // red = x, green = y, blue = 200
        val buf = frame(w, h, stride) { x, y -> (x shl 24) or (y shl 16) or (200 shl 8) or 0xFF }
        val src = ImageBufferPixels(buf, stride, w, h)
        val row = IntArray(w)
        for (y in 0 until h) {
            src.readRow(y, row)
            for (x in 0 until w) {
                val p = row[x]
                // little-endian int of bytes R,G,B,A: red in the low byte
                assertEquals("red at ($x,$y)", x, p and 0xFF)
                assertEquals("green at ($x,$y)", y, (p ushr 8) and 0xFF)
                assertEquals("blue at ($x,$y)", 200, (p ushr 16) and 0xFF)
                assertEquals(p, src.pixel(x, y))
            }
        }
    }

    @Test
    fun `a buffer the frame loop has already read is read from the start`() {
        val w = 16
        val h = 4
        val buf = frame(w, h, w) { x, y -> (x shl 24) or (y shl 16) or 0xFF }
        // the frame loop's own copy leaves the original positioned at the end
        buf.position(buf.capacity())
        val src = ImageBufferPixels(buf, w, w, h)
        assertEquals(5, src.pixel(5, 0) and 0xFF)
        assertEquals(2, (src.pixel(0, 2) ushr 8) and 0xFF)
        // and reading it did not move the original
        assertEquals(buf.capacity(), buf.position())
    }
}
