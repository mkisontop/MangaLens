package app.mangalens.gaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiftedPixelsTest {

    private fun px(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `a veiled page is brought back to the page`() {
        val level = 0.6f
        // white paper under the veil, a shaded gap under the veil, and a mid grey
        val veiled = intArrayOf(px(153, 153, 153), px(15, 15, 15), px(60, 90, 120))
        val lifted = LiftedPixels(ArrayPixels(3, 1, veiled), 1f / level)
        val row = IntArray(3)
        lifted.readRow(0, row)
        assertEquals(px(255, 255, 255), row[0])
        assertEquals(px(25, 25, 25), row[1])
        assertEquals(px(100, 150, 200), row[2])
        assertEquals(row[2], lifted.pixel(2, 0))
    }

    @Test
    fun `brightening stops at white`() {
        val lifted = LiftedPixels(ArrayPixels(1, 1, intArrayOf(px(250, 200, 10))), 2f)
        assertEquals(px(255, 255, 20), lifted.pixel(0, 0))
    }

    @Test
    fun `the engine finds the same gutter through a veil as without one`() {
        val w = 720
        val h = 1400
        val s = Strip(w, h)
        s.art(0, 0, w, 400); s.rules(0, 0, w, 400)
        s.art(0, 900, w, h)
        val style = ShadeStyle(ShadeLevel.DARK)
        val plain = GapFinder.find(PlaneBuilder.build(ArrayPixels(w, h, s.px), 1, style), 0, GapParams(), false)
        // the same page, seen through a veil that lets 0.7 of the light through
        val veiled = IntArray(w * h) { i ->
            val p = s.px[i]
            px(Math.round(((p ushr 16) and 0xFF) * 0.7f), Math.round(((p ushr 8) and 0xFF) * 0.7f), Math.round((p and 0xFF) * 0.7f))
        }
        val raw = GapFinder.find(PlaneBuilder.build(ArrayPixels(w, h, veiled), 1, style), 0, GapParams(), false)
        val back = GapFinder.find(PlaneBuilder.build(LiftedPixels(ArrayPixels(w, h, veiled), 1f / 0.7f), 1, style), 0, GapParams(), false)
        assertTrue("without the lift the veil hides the gutter", raw.shadedPixels < plain.shadedPixels / 2)
        assertTrue("with it the gutter is found: ${back.shadedPixels} of ${plain.shadedPixels}", back.shadedPixels > 0.97 * plain.shadedPixels)
    }
}
