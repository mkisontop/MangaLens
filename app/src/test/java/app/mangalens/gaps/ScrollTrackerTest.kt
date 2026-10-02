package app.mangalens.gaps

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollTrackerTest {

    private val w = 720
    private val h = 1400
    private val style = ShadeStyle(ShadeLevel.DARK)

    /** A long strip with enough going on to scroll through: art, gutters, balloons, lettering. */
    private fun strip(): Strip {
        val s = Strip(w, 9000, seed = 11)
        var y = 0
        var n = 0
        while (y < 9000) {
            val art = 350 + (n * 97) % 300
            s.art(0, y, w, y + art); s.rules(0, y, w, y + art)
            y += art
            val gap = 250 + (n * 61) % 400
            if (n % 2 == 0) s.balloon(300 + (n * 53) % 200, y + gap / 2, 180, minOf(90, gap / 2 - 20))
            if (n % 3 == 1) s.text(80, y + 30, 640, y + gap - 30)
            y += gap
            n++
        }
        return s
    }

    private fun src(f: IntArray) = ArrayPixels(w, h, f)

    @Test
    fun `every shift of the page is measured exactly`() {
        val s = strip()
        val t = ScrollTracker(w, h)
        val rnd = Random(3)
        repeat(60) {
            val top = 500 + rnd.nextInt(7000)
            val dy = rnd.nextInt(-150, 151)
            val a = t.profile(src(s.frame(top, h)))
            // the page scrolls up by dy: what was at row r is now at r + dy
            val b = t.profile(src(s.frame(top - dy, h)))
            val m = t.match(a, b, 0, h, 0)
            assertTrue("measured at all, dy=$dy, conf=${m.confidence} fit=${m.fit} act=${m.activity}", m.measured)
            assertEquals("top=$top", dy, m.dy)
        }
    }

    @Test
    fun `an overlay that lags the page by a few rows does not move the measurement`() {
        val s = strip()
        val t = ScrollTracker(w, h)
        val rnd = Random(9)
        repeat(40) {
            val top = 500 + rnd.nextInt(7000)
            val dy = rnd.nextInt(-120, 121)
            val lag = rnd.nextInt(-25, 26)
            val f1 = s.frame(top, h)
            val r1 = GapFinder.find(PlaneBuilder.build(src(f1), 1, style))
            // The shade follows the page, but by `lag` rows too many or too few.
            val f2 = composite(s.frame(top - dy, h), w, h, r1.rects, r1.rectCount, style, dy = dy + lag)
            val m = t.match(t.profile(src(f1)), t.profile(src(f2)), 0, h, 0)
            assertTrue("measured: dy=$dy lag=$lag conf=${m.confidence} fit=${m.fit}", m.measured)
            assertEquals("dy=$dy lag=$lag", dy, m.dy)
        }
    }

    @Test
    fun `an overlay that did not move at all is never mistaken for a confident wrong answer`() {
        val s = strip()
        val t = ScrollTracker(w, h)
        val rnd = Random(10)
        repeat(40) {
            val top = 500 + rnd.nextInt(7000)
            val dy = rnd.nextInt(-90, 91)
            val f1 = s.frame(top, h)
            val r1 = GapFinder.find(PlaneBuilder.build(src(f1), 1, style))
            val f2 = composite(s.frame(top - dy, h), w, h, r1.rects, r1.rectCount, style, dy = 0)
            val m = t.match(t.profile(src(f1)), t.profile(src(f2)), 0, h, 0)
            // Two alignments compete — the page's and the stale overlay's — and the tracker may
            // decline to choose. What it must never do is choose wrongly and say it is sure.
            if (m.measured) assertEquals("dy=$dy conf=${m.confidence}", dy, m.dy)
        }
    }

    @Test
    fun `the app's own controls do not hold the measurement at zero`() {
        val s = strip()
        val t = ScrollTracker(w, h)
        t.setExclusions(listOf(intArrayOf(0, 300, 400, 460)))
        val top = 2000
        val dy = 37
        val f1 = s.frame(top, h)
        val f2 = s.frame(top - dy, h)
        // a bright pill with hard edges painted at the same screen spot in both frames
        for (f in listOf(f1, f2)) for (y in 320 until 440) for (x in 20 until 380) {
            f[y * w + x] = if ((y / 8 + x / 40) % 2 == 0) Strip.WHITE else Strip.BLACK
        }
        val m = t.match(t.profile(src(f1)), t.profile(src(f2)), 0, h, 0)
        assertEquals(dy, m.dy)
    }

    @Test
    fun `a frame with no edges is not measured, and says so`() {
        val t = ScrollTracker(w, h)
        val blank = IntArray(w * h) { Strip.WHITE }
        val a = t.profile(src(blank))
        val m = t.match(a, a, 0, h, 12)
        assertFalse(m.measured)
        assertFalse(m.jumped)
    }

    @Test
    fun `an unchanged page measures zero`() {
        val s = strip()
        val t = ScrollTracker(w, h)
        val f = s.frame(3000, h)
        val p = t.profile(src(f))
        val m = t.match(p, p, 0, h, 0)
        assertTrue(m.measured)
        assertEquals(0, m.dy)
    }

    @Test
    fun `a different page is a jump, not a scroll`() {
        val s = strip()
        val t = ScrollTracker(w, h)
        val a = t.profile(src(s.frame(1000, h)))
        // a page far away that shares no structure with the first
        val other = Strip(w, h, seed = 99)
        other.art(0, 0, w, h)
        val b = t.profile(src(other.px))
        val m = t.match(a, b, 0, h, 0)
        assertFalse("not a measured shift", m.measured)
        assertTrue("reported as a jump: fit=${m.fit}", m.jumped)
    }

    @Test
    fun `a fast fling is found by the wide search`() {
        val s = strip()
        val t = ScrollTracker(w, h)
        val a = t.profile(src(s.frame(2000, h)))
        val b = t.profile(src(s.frame(2000 - 380, h)))
        // the expectation is badly off; the whole range is searched all the same
        val m = t.match(a, b, 0, h, 20)
        assertEquals(380, m.dy)
    }

    @Test
    fun `an overlay moving over a blank page is not mistaken for the page moving`() {
        // The shade's own edges are the only edges on a screen of pure gutter. Measured, they
        // would feed the tracker its own output and the loop would run away.
        val t = ScrollTracker(w, h)
        fun blankWithShade(top: Int): IntArray {
            val f = IntArray(w * h) { Strip.WHITE }
            val g = style.whiteComposite
            for (y in top until top + 600) for (x in 0 until w) f[y * w + x] = Strip.rgb(g, g, g)
            return f
        }
        val a = blankWithShade(300)
        val b = blankWithShade(340)
        // read naively, the overlay's motion looks exactly like a scroll of 40 rows...
        val naive = t.match(t.profile(src(a)), t.profile(src(b)), 0, h, 0)
        assertTrue("the trap is real: ${naive.dy}", naive.measured && naive.dy == 40)
        // ...and with the style given, there is nothing to measure at all
        val aware = t.match(t.profile(src(a), 0, h, style), t.profile(src(b), 0, h, style), 0, h, 0)
        assertFalse("overlay edges ignored", aware.measured)
    }
}
