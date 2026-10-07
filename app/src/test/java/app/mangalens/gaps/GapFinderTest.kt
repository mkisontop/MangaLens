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
        keepOut: List<IntArray> = emptyList(),
    ): Found {
        val planes = PlaneBuilder.build(ArrayPixels(w, h, frame), step, style, ignoreTop, ignoreBottom, keepOut)
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
    fun `a thin white line that stops short of the edge is not mistaken for a gutter`() {
        val s = Strip(w, h)
        s.art(0, 0, w, h)
        s.solid(40, 600, w - 40, 603, Strip.WHITE)
        val f = find(s.px)
        assertEquals(0, f.result.rectCount)
    }

    @Test
    fun `a thin white line right across the screen is a seam, and the art either side is left alone`() {
        // Indistinguishable from the hairline between two stacked images, and harmless to shade.
        val s = Strip(w, h)
        s.art(0, 0, w, h)
        s.solid(0, 600, w, 603, Strip.WHITE)
        val f = find(s.px)
        assertNoDamage(f, "seam")
        for (y in 600 until 603) for (x in 0 until w) assertTrue("seam pixel ($x,$y)", f.isCovered(x, y))
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

    /** A strip between black bars, with a browser's bars and a site's header and footer across the top and bottom. */
    private fun stripUnderChrome(): Strip {
        val s = Strip(w, h)
        val bar = Strip.rgb(0, 0, 0)
        s.solid(0, 0, 120, h, bar)
        s.solid(600, 0, w, h, bar)
        // the page
        s.art(120, 200, 600, 520); s.rules(120, 200, 600, 520)
        s.balloon(360, 690, 150, 70)
        s.art(120, 880, 600, 1290); s.rules(120, 880, 600, 1290)
        overlayBars(chromeBars(w, h, 200, 1290), 200, 1290)(s.px)
        return s
    }

    @Test
    fun `a gutter reaches black bars that the browser's and the site's bars cross`() {
        val s = stripUnderChrome()
        val f = find(s.px)
        assertNoDamage(f)
        assertTrue("gutter shaded, was ${coverageOfPaper(f, 520, 880)}", coverageOfPaper(f, 520, 880) { x, y ->
            val dx = (x - 360).toDouble() / 160
            val dy = (y - 690).toDouble() / 80
            x !in 120 until 600 || dx * dx + dy * dy <= 1.0
        } > 0.99)
        // the browser's grey passes for paper under the shade, and is still not the page's
        for (y in 0 until 200) for (x in 0 until w) assertFalse("chrome shaded at ($x,$y)", f.isCovered(x, y))
        for (y in 1290 until h) for (x in 0 until w) assertFalse("footer shaded at ($x,$y)", f.isCovered(x, y))
        // half resolution, as in motion, reads the bars the same way
        val half = find(s.px, step = 2)
        assertNoDamage(half, "half")
        assertTrue(coverageOfPaper(half, 560, 600) > 0.9)
        // and both know where the page scrolls: between the bars across the top and the bottom, give
        // or take the dark art against them, which wants no shade anyway
        for (r in listOf(f.result, half.result)) {
            assertTrue("page starts below the top bars: ${r.pageTop}", r.pageTop in 200..240)
            assertTrue("page ends above the bottom bars: ${r.pageBottom}", r.pageBottom in 1250..1290)
        }
    }

    @Test
    fun `a dark panel at the screen's edge is not taken for a browser's bar`() {
        // black bars, a gutter, and dark art running off the bottom of the screen: nothing crosses the bars
        val s = Strip(w, h)
        s.solid(0, 0, 120, h, Strip.BLACK)
        s.solid(600, 0, w, h, Strip.BLACK)
        s.art(120, 0, 600, 400); s.rules(120, 0, 600, 400)
        s.solid(120, 1100, 600, h, Strip.rgb(20, 18, 30))
        val f = find(s.px)
        assertEquals(0, f.result.pageTop)
        assertEquals(Int.MAX_VALUE, f.result.pageBottom)
        assertTrue("gutter shaded down to the panel: ${coverageOfPaper(f, 400, 1100)}", coverageOfPaper(f, 400, 1100) > 0.99)
    }

    @Test
    fun `the app's own controls over a bar and a gutter are left alone`() {
        val s = stripUnderChrome()
        // a pale button that sits half on the bar and half on the gutter
        s.solid(70, 560, 170, 620, Strip.rgb(245, 245, 245))
        val f = find(s.px, keepOut = listOf(intArrayOf(64, 554, 176, 626)))
        assertNoDamage(f)
        for (y in 554 until 626) for (x in 64 until 176) assertFalse("control shaded at ($x,$y)", f.isCovered(x, y))
        assertTrue(f.isCovered(360, 560))
        assertTrue(f.isCovered(190, 800))
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

    @Test
    fun `a slanted gutter is shaded from end to end`() {
        // A band of white that rises one row in ten, thin enough that no row of it is wide.
        val s = Strip(w, h)
        s.art(0, 0, w, h)
        val rise = 0.1
        val top = IntArray(w) { x -> 500 - (x * rise).toInt() }
        for (x in 0 until w) for (y in top[x] until top[x] + 70) s.px[y * w + x] = Strip.WHITE
        for (x in 0 until w) for (k in 0 until 4) {
            s.px[(top[x] - 1 - k) * w + x] = Strip.BLACK
            s.px[(top[x] + 70 + k) * w + x] = Strip.BLACK
        }
        val f = find(s.px)
        assertNoDamage(f, "slanted")
        var paper = 0
        var hit = 0
        for (x in 0 until w) for (y in top[x] + 4 until top[x] + 66) {
            paper++
            if (f.isCovered(x, y)) hit++
        }
        assertTrue("slanted gutter shaded, was ${hit.toDouble() / paper}", hit.toDouble() / paper > 0.97)
    }

    @Test
    fun `a balloon nearly as wide as a narrow strip is not mistaken for a gutter`() {
        // White margins either side, and a balloon 87% as wide as the strip between them.
        val s = Strip(w, h)
        s.art(120, 0, 600, 300); s.solid(120, 294, 600, 300, Strip.BLACK)
        s.balloon(360, 620, 210, 90)
        s.art(120, 900, 600, h)
        val f = find(s.px)
        assertNoDamage(f, "narrow strip")
        for (y in 540 until 700) for (x in 160 until 560) {
            val dx = (x - 360).toDouble() / 205
            val dy = (y - 620).toDouble() / 85
            if (dx * dx + dy * dy <= 1.0) assertFalse("balloon pixel ($x,$y) shaded", f.isCovered(x, y))
        }
        assertTrue("the white margins and the gutter are dark", coverageOfPaper(f, 300, 900) { x, y ->
            val dx = (x - 360).toDouble() / 250
            val dy = (y - 620).toDouble() / 140
            dx * dx + dy * dy <= 1.0
        } > 0.98)
    }

    @Test
    fun `a white panel boxed flush to the column is not a gutter`() {
        val s = Strip(w, h)
        s.art(0, 0, w, 300); s.rules(0, 0, w, 300)
        // a flashback panel: white, a 5 px border all the way round, a scribble in it
        s.solid(0, 420, w, 426, Strip.BLACK); s.solid(0, 900, w, 906, Strip.BLACK)
        s.solid(0, 420, 5, 906, Strip.BLACK); s.solid(w - 5, 420, w, 906, Strip.BLACK)
        s.text(200, 600, 520, 700)
        s.art(0, 1100, w, h)
        val f = find(s.px)
        assertNoDamage(f, "boxed")
        for (y in 440 until 890) for (x in 20 until w - 20) assertFalse("inside the white panel shaded at ($x,$y)", f.isCovered(x, y))
    }

    @Test
    fun `a round inset panel in the gutter is not given a margin of white`() {
        val s = simpleStrip()
        val cx = 360
        val cy = 650
        val r = 170
        for (y in cy - r - 5..cy + r + 5) for (x in cx - r - 5..cx + r + 5) {
            val d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            if (d2 <= (r + 5) * (r + 5)) s.px[y * w + x] = if (d2 <= r * r) Strip.rgb(60 + (x * 7 + y * 3) % 50, 40 + (x + y) % 60, 90 + (y * 5) % 40) else Strip.BLACK
        }
        val f = find(s.px)
        assertNoDamage(f, "round")
        // right up to the outline the gutter is dark: the panel is not lettering
        var near = 0
        var hit = 0
        for (y in cy - r - 20..cy + r + 20) for (x in cx - r - 20..cx + r + 20) {
            val d = Math.sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toDouble())
            if (d > r + 7 && d < r + 16 && Strip.isPaper(f.frame[y * w + x])) {
                near++
                if (f.isCovered(x, y)) hit++
            }
        }
        assertTrue("gutter beside the round panel shaded, was ${hit.toDouble() / near}", hit.toDouble() / near > 0.95)
    }

    @Test
    fun `a letter cut in half by the frame's edge keeps its margin`() {
        val s = simpleStrip()
        s.text(100, 500, 400, 620)
        s.solid(w - 16, 560, w, 600, Strip.BLACK)
        s.solid(w - 16, 560, w - 8, 600, Strip.BLACK)
        val f = find(s.px)
        assertNoDamage(f, "cut letter")
        assertLetteringStaysOnLight(f, w - 40, 540, w, 620, 6)
    }

    @Test
    fun `hairline seams between stacked images are shaded and no art goes with them`() {
        val s = Strip(w, h)
        var y = 0
        var k = 0
        val widths = intArrayOf(1, 2, 3, 4)
        val seams = ArrayList<IntRange>()
        while (y < h) {
            val ph = 200 + (k * 37) % 80
            s.art(0, y, w, minOf(h, y + ph))
            y += ph
            val t = widths[k % widths.size]
            if (y + t < h) {
                s.solid(0, y, w, y + t, Strip.WHITE)
                seams.add(y until y + t)
            }
            y += t
            k++
        }
        val f = find(s.px)
        assertNoDamage(f, "seams")
        for (r in seams) for (row in r) for (x in 0 until w) assertTrue("seam pixel ($x,$row) left lit", f.isCovered(x, row))
    }

    @Test
    fun `the gaps between close lines of lettering are not turned into dark stripes`() {
        val s = simpleStrip()
        // five lines of block lettering, 16 rows tall at a pitch of 20: a 4-row gap between lines
        for (line in 0 until 5) for (x in 80 until 640 step 18) s.solid(x, 450 + line * 20, x + 9, 466 + line * 20, Strip.BLACK)
        val f = find(s.px)
        assertNoDamage(f, "close lines")
        assertLetteringStaysOnLight(f, 70, 440, 660, 560, 6)
    }
}
