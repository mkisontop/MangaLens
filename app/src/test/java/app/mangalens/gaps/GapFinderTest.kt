package app.mangalens.gaps

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GapFinderTest {

    private val style = ShadeStyle(ShadeLevel.DARK)
    private val w = 720
    private val h = 1400

    private class Found(val frame: IntArray, val result: GapResult, val covered: BooleanArray, val w: Int, val h: Int) {
        fun isCovered(x: Int, y: Int) = x in 0 until w && y in 0 until h && covered[y * w + x]
    }

    private fun find(
        frame: IntArray,
        w: Int = this.w,
        h: Int = this.h,
        step: Int = 1,
        margin: Int = 0,
        artRecently: Boolean = false,
        ignoreTop: Int = 0,
        ignoreBottom: Int = 0,
    ): Found {
        val planes = PlaneBuilder.build(ArrayPixels(w, h, frame), step, style, ignoreTop, ignoreBottom)
        val result = GapFinder.find(planes, margin, GapParams(), artRecently)
        return Found(frame, result, coverage(result.rects, result.rectCount, w, h), w, h)
    }

    /**
     * The one invariant: the shade lands on paper — or on the anti-aliased ramp within three
     * pixels of it — and on nothing else.
     */
    private fun assertNoDamage(f: Found, what: String = "") {
        val bad = artDamage(f.frame, f.covered, f.w, f.h)
        assertEquals("$what: shade covers art", 0, bad)
    }

    private fun coverageOfPaper(f: Found, y0: Int, y1: Int, skip: (Int, Int) -> Boolean = { _, _ -> false }): Double {
        var paper = 0
        var hit = 0
        for (y in y0 until y1) for (x in 0 until f.w) {
            if (skip(x, y) || !Strip.isPaper(f.frame[y * f.w + x])) continue
            paper++
            if (f.covered[y * f.w + x]) hit++
        }
        return if (paper == 0) 1.0 else hit.toDouble() / paper
    }

    private fun simpleStrip(): Strip {
        val s = Strip(w, h)
        s.art(0, 0, w, 400); s.rules(0, 0, w, 400)
        s.art(0, 900, w, h)
        return s
    }

    @Test
    fun `a plain gutter is shaded edge to edge and the art is never touched`() {
        val s = simpleStrip()
        val f = find(s.px)
        assertNoDamage(f)
        assertTrue("gutter must be found", f.result.rectCount > 0)
        assertTrue("whole gutter shaded, was ${coverageOfPaper(f, 400, 900)}", coverageOfPaper(f, 400, 900) > 0.999)
        // a flat gutter is a single rectangle
        assertEquals(1, f.result.rectCount)
    }

    @Test
    fun `a balloon in the gutter keeps its outline and its inside`() {
        val s = simpleStrip()
        s.balloon(360, 650, 240, 100)
        val f = find(s.px)
        assertNoDamage(f)
        // nothing inside the balloon's outer edge may be shaded
        for (y in 520 until 780) for (x in 100 until 620) {
            val dx = (x - 360).toDouble() / 245
            val dy = (y - 650).toDouble() / 105
            if (dx * dx + dy * dy <= 1.0) assertFalse("balloon pixel ($x,$y) shaded", f.isCovered(x, y))
        }
        // and the gutter around it is, right up to the outline
        val around = coverageOfPaper(f, 400, 900) { x, y ->
            val dx = (x - 360).toDouble() / 245
            val dy = (y - 650).toDouble() / 105
            dx * dx + dy * dy <= 1.0
        }
        assertTrue("gutter around the balloon shaded, was $around", around > 0.995)
    }

    @Test
    fun `a balloon whose outline has a break still keeps its lettering readable`() {
        val s = simpleStrip()
        s.balloon(360, 650, 240, 100, leak = 8)
        val f = find(s.px)
        assertNoDamage(f)
        assertLetteringStaysOnLight(f, 150, 540, 570, 760, 6)
    }

    @Test
    fun `a break of a few pixels is sealed and the balloon stays whole`() {
        val s = simpleStrip()
        s.balloon(360, 650, 240, 100, leak = 3)
        val f = find(s.px)
        assertNoDamage(f)
        // sealed: the inside is still entirely unshaded, margin and all
        for (y in 595 until 705) for (x in 250 until 470) {
            assertFalse("inside of a sealed balloon shaded at ($x,$y)", f.isCovered(x, y))
        }
    }

    /** No shaded pixel within [pad] pixels of any dark pixel in the box: black ink never meets the dark. */
    private fun assertLetteringStaysOnLight(f: Found, x0: Int, y0: Int, x1: Int, y1: Int, pad: Int) {
        var violations = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            if (f.frame[y * f.w + x] != Strip.BLACK) continue
            for (dy in -pad..pad) for (dx in -pad..pad) if (f.isCovered(x + dx, y + dy)) violations++
        }
        assertEquals("shaded pixels within $pad px of black lettering", 0, violations)
    }

    @Test
    fun `narration set on the white gets a margin of white and everything else goes dark`() {
        val s = simpleStrip()
        s.text(100, 500, 600, 620)
        val f = find(s.px)
        assertNoDamage(f)
        assertLetteringStaysOnLight(f, 90, 495, 610, 625, 7)
        // away from the text the gutter is dark
        val rest = coverageOfPaper(f, 640, 900)
        assertTrue("gutter away from the text shaded, was $rest", rest > 0.999)
        assertTrue("some of the gutter near the text is dark too", coverageOfPaper(f, 400, 900) > 0.6)
    }

    @Test
    fun `white beside an inset panel is shaded and the panel is not`() {
        val s = Strip(w, h)
        s.art(0, 0, w, 300); s.rules(0, 0, w, 300)
        s.art(120, 520, w, 820)
        s.solid(120, 520, w, 526, Strip.BLACK)
        s.art(0, 1000, w, h)
        val f = find(s.px)
        assertNoDamage(f)
        assertTrue("margin beside the panel is dark", coverageOfPaper(f, 520, 820) > 0.99)
        assertTrue("gutters above and below are dark", coverageOfPaper(f, 300, 520) > 0.999)
    }

    @Test
    fun `a white page with only text is left alone`() {
        val s = Strip(w, h)
        s.text(60, 100, 660, 700)
        val f = find(s.px)
        assertEquals(0, f.result.rectCount)
        assertFalse(f.result.sawArt)
    }

    @Test
    fun `a screen of nothing but gutter waits for art, then follows it`() {
        val s = Strip(w, h)
        s.balloon(360, 700, 220, 90)
        val cold = find(s.px)
        assertEquals("no art seen yet: nothing is shaded", 0, cold.result.rectCount)
        val warm = find(s.px, artRecently = true)
        assertTrue("art was seen a moment ago: the gutter is shaded", warm.result.rectCount > 0)
        assertNoDamage(warm)
        for (y in 600 until 800) for (x in 140 until 580) {
            val dx = (x - 360).toDouble() / 225
            val dy = (y - 700).toDouble() / 95
            if (dx * dx + dy * dy <= 1.0) assertFalse("balloon pixel shaded", warm.isCovered(x, y))
        }
    }

    @Test
    fun `pale art next to the gutter is left alone`() {
        val s = simpleStrip()
        // a pale band the gutter's white runs straight into: 236 is not paper
        s.solid(0, 880, w, 900, Strip.rgb(236, 236, 236))
        val f = find(s.px)
        assertNoDamage(f)
        for (y in 880 until 900) for (x in 0 until w) assertFalse(f.isCovered(x, y))
        assertTrue(coverageOfPaper(f, 400, 880) > 0.999)
    }

    @Test
    fun `a thin white line of art inside a panel is not mistaken for a gutter`() {
        val s = Strip(w, h)
        s.art(0, 0, w, h)
        s.solid(0, 600, w, 603, Strip.WHITE)
        val f = find(s.px)
        assertEquals(0, f.result.rectCount)
    }

    @Test
    fun `linework on a white ground never ends up on a dark one`() {
        val s = Strip(w, h)
        s.art(0, 0, w, 300)
        s.art(0, 1100, w, h)
        // a drawing on white, with no frame, so its ground runs straight into the gutters
        for (i in 0 until 60) s.text(40 + (i * 37) % 300, 330 + (i * 53) % 700, 400 + (i * 37) % 300, 360 + (i * 53) % 700)
        val f = find(s.px)
        assertNoDamage(f)
        // every dark stroke keeps a margin of light round it
        assertLetteringStaysOnLight(f, 0, 320, w, 1040, 6)
        // the gutter beneath it, which its margins do not reach, is still dark
        assertTrue(f.isCovered(360, 1090))
    }

    @Test
    fun `black bars either side of the strip are not part of it`() {
        val s = Strip(w, h)
        val barL = 40
        val barR = 40
        s.art(0, 0, w, 400); s.rules(0, 0, w, 400)
        s.art(0, 900, w, h)
        s.solid(0, 0, barL, h, Strip.rgb(10, 10, 10))
        s.solid(w - barR, 0, w, h, Strip.rgb(10, 10, 10))
        s.balloon(360, 650, 230, 95)
        val f = find(s.px)
        assertNoDamage(f)
        assertTrue(coverageOfPaper(f, 400, 900) { x, y ->
            val dx = (x - 360).toDouble() / 235
            val dy = (y - 650).toDouble() / 100
            dx * dx + dy * dy <= 1.0
        } > 0.995)
    }

    @Test
    fun `the browser's own bars are not shaded`() {
        val s = Strip(w, h)
        s.art(0, 100, w, 500)
        s.art(0, 800, w, h)
        val f = find(s.px, ignoreTop = 100)
        assertNoDamage(f)
        for (y in 0 until 100) for (x in 0 until w) assertFalse(f.isCovered(x, y))
    }

    @Test
    fun `a margin pulls the shade back from every edge it could run into`() {
        val s = simpleStrip()
        val f = find(s.px, margin = 20)
        assertNoDamage(f)
        for (y in 400 until 420) for (x in 0 until w) assertFalse("margin at the top edge, y=$y", f.isCovered(x, y))
        for (y in 880 until 900) for (x in 0 until w) assertFalse("margin at the bottom edge, y=$y", f.isCovered(x, y))
        assertTrue(f.isCovered(360, 421))
        assertTrue(f.isCovered(360, 879))
    }

    @Test
    fun `the margin also holds the shade off the screen's own edge`() {
        val s = Strip(w, h)
        s.art(0, 600, w, 900)
        val f = find(s.px, margin = 16)
        assertNoDamage(f)
        for (y in 0 until 16) assertFalse("frame edge at y=$y", f.isCovered(360, y))
        for (y in h - 16 until h) assertFalse("frame edge at y=$y", f.isCovered(360, y))
        assertTrue(f.isCovered(360, 17))
    }

    @Test
    fun `a frame that already carries the shade reads the same as the page under it`() {
        val s = simpleStrip()
        s.balloon(360, 650, 240, 100)
        s.text(60, 440, 300, 480)
        val first = find(s.px)
        val shaded = composite(s.px, w, h, first.result.rects, first.result.rectCount, style)
        val second = find(shaded)
        var differ = 0
        for (i in first.covered.indices) if (first.covered[i] != second.covered[i]) differ++
        assertTrue("shaded frame read differently from the clean one: $differ pixels", differ < 40)
    }

    @Test
    fun `a shade strayed onto art is seen for what it covers and let go`() {
        val s = simpleStrip()
        val first = find(s.px)
        // the overlay lags by 40 rows: it now covers the top of the art below the gutter
        val shaded = composite(s.px, w, h, first.result.rects, first.result.rectCount, style, dy = 40)
        val second = find(shaded)
        // the art band 900..940 must not be claimed, now or ever — bar the few pixels of anti-aliased
        // edge the shade is allowed to take in beside paper
        for (y in 905 until 940) for (x in 0 until w) assertFalse("art claimed at ($x,$y)", second.isCovered(x, y))
        // and the gutter that the shade left behind is found again
        assertTrue(second.isCovered(360, 500))
    }

    @Test
    fun `half resolution finds the same gutter and still never touches art`() {
        val s = simpleStrip()
        s.balloon(360, 650, 240, 100)
        val full = find(s.px)
        val fast = find(s.px, step = 2, margin = 4)
        assertNoDamage(fast, "step 2")
        val a = coverageOfPaper(full, 400, 900)
        val b = coverageOfPaper(fast, 400, 900)
        assertTrue("fast pass covers nearly as much: $b vs $a", b > a - 0.04)
    }

    @Test
    fun `a gutter with tinted shade levels is read the same at every level`() {
        for (level in ShadeLevel.values()) {
            val st = ShadeStyle(level)
            val s = simpleStrip()
            s.balloon(360, 650, 240, 100)
            val planes = PlaneBuilder.build(ArrayPixels(w, h, s.px), 1, st)
            val first = GapFinder.find(planes)
            val shaded = composite(s.px, w, h, first.rects, first.rectCount, st)
            val second = GapFinder.find(PlaneBuilder.build(ArrayPixels(w, h, shaded), 1, st))
            val a = coverage(first.rects, first.rectCount, w, h)
            val b = coverage(second.rects, second.rectCount, w, h)
            var differ = 0
            for (i in a.indices) if (a[i] != b[i]) differ++
            // The lightest shade leaves paper at 45..53, a band a few of the darkest greys of the art
            // also fall in: tens of near-black pixels at a panel edge are read differently, harmlessly.
            val allowed = if (level == ShadeLevel.DIM) 120 else 40
            assertTrue("level $level: $differ pixels differ", differ < allowed)
        }
    }

    @Test
    fun `speed on a phone-sized frame`() {
        val big = Strip(1080, 2400)
        big.art(0, 0, 1080, 500); big.rules(0, 0, 1080, 500)
        big.art(0, 1400, 1080, 2000)
        big.balloon(540, 950, 360, 150)
        big.text(100, 1250, 900, 1330)
        big.art(0, 2000, 1080, 2400)
        val src = ArrayPixels(1080, 2400, big.px)
        // warm up the JIT, then time
        repeat(3) { GapFinder.find(PlaneBuilder.build(src, 1, style)) }
        val t0 = System.nanoTime()
        val n = 5
        repeat(n) { GapFinder.find(PlaneBuilder.build(src, 1, style)) }
        val full = (System.nanoTime() - t0) / 1e6 / n
        repeat(3) { GapFinder.find(PlaneBuilder.build(src, 2, style), 8) }
        val t1 = System.nanoTime()
        repeat(n) { GapFinder.find(PlaneBuilder.build(src, 2, style), 8) }
        val fast = (System.nanoTime() - t1) / 1e6 / n
        println("GAPFINDER 1080x2400: full-resolution %.1f ms, half-resolution %.1f ms (desktop JVM)".format(full, fast))
        assertTrue(abs(full) < 5000)
    }

    private fun rampStrip(): Strip {
        val s = Strip(w, h)
        s.art(0, 0, w, 394)
        s.solid(0, 394, w, 400, Strip.BLACK)
        // black to white over five rows: the anti-aliased edge of a black panel border
        val ramp = intArrayOf(30, 70, 140, 200, 235)
        for ((i, v) in ramp.withIndex()) s.solid(0, 400 + i, w, 401 + i, Strip.rgb(v, v, v))
        s.art(0, 900, w, h)
        return s
    }

    @Test
    fun `the anti-aliased edge of a border is shaded, not left as a pale line`() {
        val s = rampStrip()
        val f = find(s.px)
        assertNoDamage(f)
        // paper begins at row 405; the ramp rows 402..404 (140, 200, 235) fall away from it step by step
        for (y in 402..404) assertTrue("ramp row $y shaded", f.isCovered(360, y))
        // the border itself, and the art, are not
        for (y in 380..399) assertFalse("border/art row $y untouched", f.isCovered(360, y))
        // and the ramp reads as a smooth dark edge once shaded: no pixel of it is lighter than the gap
        val shaded = composite(s.px, w, h, f.result.rects, f.result.rectCount, style)
        val gap = shaded[600 * w + 360] and 0xFF
        for (y in 402..420) assertTrue("row $y no lighter than the gap", (shaded[y * w + 360] and 0xFF) <= gap + 1)
    }

    @Test
    fun `flat pale art against the gutter is not eaten by the fringe`() {
        val s = simpleStrip()
        s.solid(0, 880, w, 900, Strip.rgb(225, 225, 225))   // pale, flat, and touching the white
        val f = find(s.px)
        assertNoDamage(f)
        for (y in 880 until 900) assertFalse("flat pale art row $y untouched", f.isCovered(360, y))
    }

    @Test
    fun `a slow fade into the white is not eaten either`() {
        val s = simpleStrip()
        // 40 rows rising one level at a time, 200 up to 239: a vignette, not an edge
        for (i in 0 until 40) s.solid(0, 860 + i, w, 861 + i, Strip.rgb(200 + i, 200 + i, 200 + i))
        val f = find(s.px)
        assertNoDamage(f)
        for (y in 860 until 900) assertFalse("fade row $y untouched", f.isCovered(360, y))
    }

    @Test
    fun `an edge shaded with its fringe is read back as the same edge`() {
        val s = rampStrip()
        val first = find(s.px)
        val shaded = composite(s.px, w, h, first.result.rects, first.result.rectCount, style)
        val second = find(shaded)
        var differ = 0
        for (i in first.covered.indices) if (first.covered[i] != second.covered[i]) differ++
        assertTrue("fringe read back differently: $differ pixels", differ < 40)
    }
    @Test
    fun `a plane too ragged to describe yields no rectangles rather than half a page`() {
        // 500 separate runs a row, each differing from the last every 16 rows: over the limit at every coarseness
        val plane = BitPlane(2000, 400)
        for (y in 0 until 400) {
            val shift = (y / 16) % 2
            var x = shift
            while (x + 2 <= 2000) { plane.setRun(y, x, x + 2); x += 4 }
        }
        val r = GapFinder.extractRects(plane, 1, 2000, 400, 0)
        assertEquals("nothing is better than a shade that stops halfway down the page", 0, r.rectCount)
    }

    @Test
    fun `a busy but describable plane is coarsened, never truncated`() {
        // Many short runs, but rows agree in groups of 4: describable once the rows are merged
        val plane = BitPlane(2000, 64)
        for (y in 0 until 64) {
            val shift = (y / 4) % 2
            var x = shift
            while (x + 2 <= 2000) { plane.setRun(y, x, x + 2); x += 4 }
        }
        val r = GapFinder.extractRects(plane, 1, 2000, 64, 0)
        assertTrue("rects present: ${r.rectCount}", r.rectCount in 1..6000)
    }
}
