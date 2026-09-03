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

    @Test
    fun `the controls are masked out of the mean difference too`() {
        val a = IntArray(n) { 220 }
        val b = a.copyOf()
        // A dark pill lands on a white page: a few dozen cells, a long way —
        // enough to fail a frame read ahead as "no longer on screen".
        val pill = BooleanArray(n)
        for (y in 20 until 24) for (x in 10 until 50) {
            pill[y * FrameStability.SIZE + x] = true
            b[y * FrameStability.SIZE + x] = 70
        }
        assertTrue("unmasked, the pill reads as drift", FrameStability.meanDiff(a, b) > 1.2)
        assertEquals("masked, the page is still the page", 0.0, FrameStability.meanDiff(a, b, pill), 1e-9)
    }

    @Test
    fun `a union keeps every cell either mask covers`() {
        val cards = BooleanArray(n) { it < 100 }
        val pill = BooleanArray(n) { it in 200 until 260 }
        val both = FrameStability.union(cards, pill)!!
        assertEquals(160, both.count { it })
        assertTrue(FrameStability.union(null, null) == null)
        assertTrue(FrameStability.union(cards, null) === cards)
        assertTrue(FrameStability.union(null, pill) === pill)
    }
}
