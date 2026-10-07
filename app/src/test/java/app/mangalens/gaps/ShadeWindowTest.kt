package app.mangalens.gaps

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shade is painted inside the lettering's window, which Android draws at most at its
 * obscuring cap so that touches still reach the page. What is painted must show the shade's
 * own grey on the glass, and read back as that grey through MangaLens's veil.
 */
class ShadeWindowTest {

    /** The veil the lettering lays for a window at [w]: what BubbleOverlayView computes. */
    private fun veilAlpha(w: Float): Int = if (w >= 0.5f && w < 1f) (((1f - w) / w) * 255f + 0.5f).toInt().coerceIn(0, 255) else 0

    private fun screenLevel(w: Float): Float = 1f - w * veilAlpha(w) / 255f

    /** White paper under the shade, as the glass shows it, divided by the veil's level as the capture is. */
    private fun readBack(effective: Float, w: Float, veiled: Boolean): Float {
        val va = if (veiled) veilAlpha(w) / 255f else 0f
        val level = if (veiled) screenLevel(w) else 1f
        val a = ShadeWindow.paintAlpha(effective, w, level) / 255f
        val held = 1f - (1f - va) * (1f - a)
        val glass = 255f * (1f - w * held)
        return glass / level
    }

    @Test
    fun `a trusted window shows any level as painted`() {
        assertEquals(1f, ShadeWindow.cap(1f), 0f)
        assertEquals(Math.round(0.9f * 255f), ShadeWindow.paintAlpha(0.9f, 1f, 1f))
    }

    @Test
    fun `an ordinary overlay at Android's cap leaves room for the veil`() {
        assertEquals(0.75f, ShadeWindow.cap(0.8f), 1e-4f)
        for (w in listOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 0.95f)) {
            val cap = ShadeWindow.cap(w)
            assertTrue("cap $cap at window $w", cap > 0f && cap <= w)
        }
    }

    @Test
    fun `without a veil the glass shows the shade's alpha`() {
        val w = 0.8f
        val e = ShadeWindow.cap(w)
        val read = readBack(e, w, veiled = false)
        assertEquals("grey on the glass", 255f * (1f - e), read, 1.5f)
    }

    @Test
    fun `under the veil the lifted capture reads the same grey`() {
        for (w in listOf(0.6f, 0.7f, 0.8f, 0.9f)) {
            val e = ShadeWindow.cap(w)
            for (level in ShadeLevel.values()) {
                val effective = minOf(level.alpha, e)
                val plain = readBack(effective, w, veiled = false)
                val veiled = readBack(effective, w, veiled = true)
                assertTrue("window $w, $level: $plain without the veil, $veiled under it", abs(plain - veiled) <= 2.5f)
                assertTrue("window $w, $level reads ${255f * (1f - effective)}: $veiled", abs(veiled - 255f * (1f - effective)) <= 2.5f)
            }
        }
    }

    @Test
    fun `the read-back grey is what the finder calls shaded paper`() {
        val w = 0.8f
        val style = ShadeStyle(ShadeLevel.BLACK, cap = ShadeWindow.cap(w))
        val grey = Math.round(readBack(style.alpha, w, veiled = true))
        assertTrue("$grey in ${style.sigLo}..${style.sigHi}", grey in style.sigLo..style.sigHi)
    }
}
