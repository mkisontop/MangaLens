package app.mangalens.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tap-to-turn failure under blanket coverage: with cards over nearly the
 * whole page, the change detector used to return a hard 0.0 — formally blind
 * — and a page swap under the cards could never be noticed. A thin remainder
 * of gutters must still be readable, and the coverage measure the service
 * uses to lower its threshold must tell the truth.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoverageBlindnessTest {

    private val n = FrameStability.SIZE * FrameStability.SIZE

    @Test
    fun `a page swap is visible through a 90 percent mask`() {
        val a = IntArray(n) { 200 }
        val b = IntArray(n) { 200 }
        val mask = BooleanArray(n) { it < n * 9 / 10 }
        // The unmasked tail is the last 10% of cells; flip half of them hard,
        // as a new page's gutters and panel edges do.
        for (i in n * 9 / 10 until n) if (i % 2 == 0) b[i] = 40
        val fraction = FrameStability.changedFraction(a, b, mask)
        assertTrue("expected a visible change, got $fraction", fraction > 0.4)
    }

    @Test
    fun `a still page through the same mask stays quiet`() {
        val a = IntArray(n) { 200 }
        val b = IntArray(n) { 200 }
        val mask = BooleanArray(n) { it < n * 9 / 10 }
        assertEquals(0.0, FrameStability.changedFraction(a, b, mask), 1e-9)
    }

    @Test
    fun `coverage reports the masked share`() {
        assertEquals(0.0, FrameStability.coverage(null), 1e-9)
        assertEquals(0.0, FrameStability.coverage(BooleanArray(n)), 1e-9)
        val mask = BooleanArray(n) { it < n / 2 }
        assertEquals(0.5, FrameStability.coverage(mask), 1e-3)
    }

    @Test
    fun `a truly empty remainder still refuses to judge`() {
        val a = IntArray(n) { 200 }
        val b = IntArray(n) { 40 }
        val mask = BooleanArray(n) { true }
        assertEquals(0.0, FrameStability.changedFraction(a, b, mask), 1e-9)
    }
}
