package app.mangalens.gaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadeEngineTest {

    private val w = 720
    private val h = 1400
    private val style = ShadeStyle(ShadeLevel.DARK)
    private val strip by lazy { scrollStrip(w) }

    private fun sim(
        captureLag: Double = 16.0,
        displayFrames: Int = 2,
        jobMs: Double = 25.0,
        vsync: Double = 1000.0 / 60,
    ) = ShadeSimulation(strip, w, h, style, vsync, captureLag, displayFrames, jobMs)

    private fun report(name: String, r: ShadeSimulation.Report) = println("SIM $name: $r\nSIM    damage: ${r.log()}")

    @Test
    fun `at rest the shade is exact and covers everything`() {
        val r = sim().run(Scrolls.still(2000.0), 300.0, 700.0)
        report("rest", r)
        assertEquals("no art covered at rest", 0, r.restDamage)
        assertTrue("whole gutter shaded at rest: ${r.restCoverage}", r.restCoverage > 0.995)
    }

    /** A finger goes down 80 ms before the page first moves: the usual way a scroll begins. */
    private val touch = 80.0

    /**
     * What a page that stops dead, or turns round, may cost: a few rows of art dimmed for a
     * frame or two, summed over every gutter edge on screen. Prediction cannot foresee a
     * finger stopping; it can only keep the overshoot small.
     */
    private val stopTolerance = 14 * w

    /** A page stopping dead from twice the speed carries the overlay twice as far past it. */
    private fun assertClean(r: ShadeSimulation.Report, what: String, turn: Int = 0, speed: Double = 700.0) {
        assertEquals("$what: art covered at the start of the scroll", 0, r.onsetMax)
        assertTrue("$what: art covered in steady motion: ${r.steadyMax}px, ${r.log()}", r.steadyMax <= turn)
        assertTrue("$what: art covered as the page stopped: ${r.stopMax}px, ${r.log()}", r.stopMax <= stopTolerance * maxOf(1.0, speed / 700.0))
        assertEquals("$what: art covered at rest", 0, r.restDamage)
        assertTrue("$what: exact again at rest: ${r.restCoverage}", r.restCoverage > 0.995)
    }

    @Test
    fun `a slow reading scroll never covers art and leaves little white`() {
        val r = sim().run(Scrolls.ramp(2000.0, 450.0, 150.0, 2300.0), 2500.0, touchLeadMs = touch)
        report("slow 450px/s", r)
        assertClean(r, "slow")
        assertTrue("most of the gutter dark while moving: ${r.coverageMovingSum / r.coverageMovingN}", r.coverageMovingSum / r.coverageMovingN > 0.6)
    }

    @Test
    fun `a finger drag that jitters and stops dead never covers art`() {
        val r = sim().run(Scrolls.drag(1500.0), 2200.0, touchLeadMs = touch)
        report("drag", r)
        assertClean(r, "drag")
    }

    @Test
    fun `a fast fling never covers art`() {
        val r = sim().run(Scrolls.fling(1000.0, 3500.0, 350.0, 0.5), 2000.0, touchLeadMs = touch)
        report("fling 3500px/s", r)
        assertClean(r, "fling")
    }

    @Test
    fun `reversing direction never covers art`() {
        val r = sim().run(Scrolls.reverse(3000.0, 700.0, 900.0), 2000.0, touchLeadMs = touch)
        report("reverse", r)
        // the instant the page turns round is the one place steady motion may cost a few rows, for one frame
        assertClean(r, "reverse", turn = 8 * w)
    }

    @Test
    fun `a scroll with no touch to announce it costs a few rows at the start and nothing after`() {
        // A reader that scrolls on a key or by itself: nothing arms the margin first.
        val r = sim().run(Scrolls.ramp(2000.0, 450.0, 150.0, 2300.0), 2500.0)
        report("no touch", r)
        assertEquals("steady", 0, r.steadyMax)
        assertEquals(0, r.restDamage)
        assertTrue(r.restCoverage > 0.995)
        assertTrue("onset damage is limited to a few rows: ${r.onsetMax}px", r.onsetMax <= 16 * w)
    }

    @Test
    fun `a tap that is not a scroll puts the margin up and takes it down again`() {
        val s = sim()
        val r = s.run(Scrolls.still(2000.0), 600.0, 900.0, touchLeadMs = 0.0)
        report("tap", r)
        assertEquals(0, r.restDamage)
        assertTrue("exact again after the tap: ${r.restCoverage}", r.restCoverage > 0.995)
    }

    @Test
    fun `the result holds across display and capture latencies`() {
        for ((cap, disp, job, vs) in listOf(
            listOf(8.0, 1.0, 12.0, 1000.0 / 60),
            listOf(25.0, 3.0, 45.0, 1000.0 / 60),
            listOf(6.0, 2.0, 15.0, 1000.0 / 120),
            listOf(14.0, 3.0, 30.0, 1000.0 / 120),
        )) {
            val r = sim(cap, disp.toInt(), job, vs).run(Scrolls.ramp(2500.0, 600.0, 150.0, 1600.0), 1800.0, touchLeadMs = touch)
            report("latency cap=$cap disp=${disp.toInt()} job=$job vsync=${"%.1f".format(vs)}", r)
            assertClean(r, "cap=$cap disp=${disp.toInt()} job=$job vsync=${"%.1f".format(vs)}")
        }
    }

    @Test
    fun `randomised speeds and latencies never cover art in steady motion`() {
        val rnd = kotlin.random.Random(2024)
        repeat(6) { n ->
            val cap = 6.0 + rnd.nextDouble() * 24
            val disp = 1 + rnd.nextInt(3)
            val job = 10.0 + rnd.nextDouble() * 40
            val vs = if (rnd.nextBoolean()) 1000.0 / 60 else 1000.0 / 120
            val speed = 250.0 + rnd.nextDouble() * 1250
            val ramp = 90.0 + rnd.nextDouble() * 160
            val start = 1500.0 + rnd.nextDouble() * 4000
            val r = sim(cap, disp, job, vs).run(Scrolls.ramp(start, speed, ramp, 1700.0), 1900.0, touchLeadMs = touch)
            val tag = "#$n cap=${"%.0f".format(cap)} disp=$disp job=${"%.0f".format(job)} vs=${"%.1f".format(vs)} v=${"%.0f".format(speed)} ramp=${"%.0f".format(ramp)}"
            report(tag, r)
            assertClean(r, tag, speed = speed)
        }
    }

    @Test
    fun `flicks beyond any reading speed still leave nothing in steady motion and an exact rest`() {
        val rnd = kotlin.random.Random(77)
        repeat(3) { n ->
            val cap = 8.0 + rnd.nextDouble() * 20
            val disp = 1 + rnd.nextInt(3)
            val speed = 1800.0 + rnd.nextDouble() * 2400
            val ramp = 120.0 + rnd.nextDouble() * 160
            val vs = if (rnd.nextBoolean()) 1000.0 / 60 else 1000.0 / 120
            val r = sim(cap, disp, 25.0, vs).run(Scrolls.ramp(1500.0, speed, ramp, 1500.0), 1700.0, touchLeadMs = touch)
            report("fast #$n cap=${"%.0f".format(cap)} disp=$disp v=${"%.0f".format(speed)}", r)
            assertEquals("fast #$n: steady", 0, r.steadyMax)
            assertEquals("fast #$n: rest", 0, r.restDamage)
            assertTrue(r.restCoverage > 0.995)
        }
    }

    @Test
    fun `a detection landing or the clock ticking does not restart the prediction from a stale page`() {
        // Each publish used to stamp the page's offset with the moment of publishing. The offset
        // is the one read from the last frame; stamped later, the prediction started over from a
        // page that had in truth moved on, and the shade fell back by the speed times the delay.
        val eng = ShadeEngine(w, h, style)
        var top = 2000
        var t = 0.0
        var job: DetectJob? = eng.kick(ArrayPixels(w, h, strip.frame(top, h)), t)
        eng.onDetected(job!!, job.run(), t + 20)
        for (i in 0 until 30) {
            t += 16.7
            top += 8
            job = eng.onFrame(ArrayPixels(w, h, strip.frame(top, h)), t) ?: job
            assertEquals("frame $i: stamped with the frame's time", t, eng.snapshot.offsetAtMs, 1e-9)
            // where the page is predicted to be at a draw a little later, whatever rectangles ride it
            val before = eng.snapshot.shiftAt(t + 14) + eng.snapshot.baseOffset
            eng.tick(t + 6)
            if (i % 3 == 0) eng.onDetected(job!!, job.run(), t + 9)
            assertEquals("frame $i: still the frame's time", t, eng.snapshot.offsetAtMs, 1e-9)
            assertEquals("frame $i: the same page, predicted to the same place", before, eng.snapshot.shiftAt(t + 14) + eng.snapshot.baseOffset)
        }
    }

    @Test
    fun `a steady scroll at 120 Hz is followed smoothly`() {
        // The drawn shade, laid on the page, should not step back and forth from one vsync to the
        // next: every step it takes against the page is the shade shivering.
        val vs = 1000.0 / 120
        val disp = 3
        val lead = 80.0
        val sc0 = Scrolls.ramp(1500.0, 800.0, 150.0, 1900.0)
        val sc: (Double) -> Double = { t -> sc0((t - lead).coerceAtLeast(0.0)) }
        val firstTop = Math.round(sc(0.0)).toInt()
        val drawn = HashMap<Int, IntArray>()
        var sim: ShadeSimulation? = null
        val jumps = ArrayList<Int>()
        var prev: Int? = null
        sim = ShadeSimulation(strip, w, h, style, vs, 6.0, disp, 15.0, onGlass = { k, _ ->
            val t = k * vs
            val sn = sim!!.engine.snapshot
            drawn[k] = intArrayOf(sn.baseOffset, sn.shiftAt(t), sn.rectCount)
            val d = drawn[k - disp]
            val top = Math.round(sc(minOf(t, 2000.0))).toInt()
            if (d != null && d[2] > 0 && t in 400.0..1900.0) {
                val e = d[1] - ((firstTop - top) - d[0])
                prev?.let { jumps.add(kotlin.math.abs(e - it)) }
                prev = e
            }
        })
        sim.run(sc0, 2000.0, touchLeadMs = lead)
        val big = jumps.count { it > 2 }
        assertTrue("steps of more than two rows in ${big} of ${jumps.size} vsyncs", big * 10 <= jumps.size)
    }

    @Test
    fun `a page turn drops the shade within a few frames and restores it exactly`() {
        // the page is replaced by a different part of the strip in one frame, as a tap-to-turn reader does
        val jump: (Double) -> Double = { t -> if (t < 600) 2000.0 else 7300.0 }
        val sm = sim()
        val r = sm.run(jump, 1500.0, 800.0)
        report("page turn", r)
        assertEquals("exact again once the new page has settled", 0, r.restDamage)
        assertTrue("shade restored on the new page: ${r.restCoverage}", r.restCoverage > 0.995)
        // how long the old shade stood over the new page: a handful of frames, not a lingering stain
        val frames = r.damageLog.size
        assertTrue("old shade lingered for $frames frames: ${r.log()}", frames <= 9)
    }

    @Test
    fun `engine cost per frame at phone resolution`() {
        val bw = 1080
        val bh = 2400
        val big = Strip(bw, 9000, 5)
        var y = 0
        var n = 0
        while (y < 9000) {
            val art = 500 + (n * 97) % 400
            big.art(0, y, bw, y + art); big.rules(0, y, bw, y + art); y += art
            val gap = 300 + (n * 61) % 500
            big.balloon(500, y + gap / 2, 300, minOf(110, gap / 2 - 20))
            y += gap; n++
        }
        val eng = ShadeEngine(bw, bh, style)
        var total = 0.0
        var count = 0
        var t = 0.0
        var top = 1000
        // warm-up then measure the frame path only (detections are jobs, run elsewhere)
        for (i in 0 until 160) {
            val f = ArrayPixels(bw, bh, big.frame(top, bh))
            val t0 = System.nanoTime()
            val job = eng.onFrame(f, t)
            val ms = (System.nanoTime() - t0) / 1e6
            if (i >= 40) { total += ms; count++ }
            if (job != null) eng.onDetected(job, job.run(), t + 20)
            top += 9
            t += 16.7
        }
        println("SIM engine onFrame 1080x2400: %.2f ms/frame mean over %d frames (desktop JVM, includes plane build for detection frames)".format(total / count, count))
        assertTrue(total / count < 200)
    }

    @Test
    fun `scrolling through a gutter that fills the screen keeps it dark`() {
        val long = longGutterStrip(w)
        // from the art above, down through the whole gutter, to the art below
        val sm = ShadeSimulation(long, w, h, style, 1000.0 / 60, 16.0, 2, 25.0)
        val r = sm.run(Scrolls.ramp(900.0, 900.0, 160.0, 4300.0), 4500.0, touchLeadMs = touch)
        report("long gutter", r)
        assertEquals("art covered", 0, r.steadyMax)
        // The page moves at 0.9 rows a millisecond. A tracker that measured the overlay's own edges
        // on the featureless stretch ran away to five times that, and the wrong way round at times.
        assertTrue("speed estimate stayed honest: ${r.maxSpeed} rows/ms", r.maxSpeed < 1.6f)
        assertTrue("shade still there on the long gutter: ${r.coverageMovingSum / r.coverageMovingN}", r.coverageMovingSum / r.coverageMovingN > 0.6)
        assertEquals(0, r.restDamage)
        assertTrue(r.restCoverage > 0.995)
    }

    @Test
    fun `a compositor that blends in linear light is learned, not fought`() {
        // On such a device white under the shade reads about 89, not 25. A shade that did not
        // notice would see its own output as ink, take itself down, and flicker.
        val sm = ShadeSimulation(strip, w, h, style, 1000.0 / 60, 16.0, 2, 25.0, linearBlend = true)
        val r = sm.run(Scrolls.ramp(2000.0, 450.0, 150.0, 1800.0), 2000.0, touchLeadMs = touch)
        report("linear-light blending", r)
        assertEquals("art covered", 0, r.steadyMax)
        assertEquals(0, r.restDamage)
        assertTrue("shade restored in full at rest: ${r.restCoverage}", r.restCoverage > 0.99)
        assertTrue("the engine learned the grey: ${sm.engine.style.whiteComposite}", sm.engine.style.whiteComposite in 80..100)
    }

    @Test
    fun `the shade rides the page under a browser's and a site's bars and never onto them`() {
        // a strip between black bars, scrolling under bars that stay put at the top and bottom
        val page = scrollStrip(w, seed = 17).also { s ->
            s.solid(0, 0, 120, s.h, Strip.BLACK)
            s.solid(600, 0, w, s.h, Strip.BLACK)
        }
        val top = 200
        val bottom = 1290
        val bars = overlayBars(chromeBars(w, h, top, bottom), top, bottom)
        val onBars = BooleanArray(w * h) { it / w < top || it / w >= bottom }
        for ((name, run) in listOf<Pair<String, (ShadeSimulation) -> ShadeSimulation.Report>>(
            "drag" to { sm -> sm.run(Scrolls.drag(1500.0), 2200.0, touchLeadMs = touch) },
            "fling" to { sm -> sm.run(Scrolls.fling(1000.0, 3500.0, 350.0, 0.5), 2000.0, touchLeadMs = touch) },
            "reverse" to { sm -> sm.run(Scrolls.reverse(3000.0, 700.0, 900.0), 2000.0, touchLeadMs = touch) },
        )) {
            val r = run(ShadeSimulation(page, w, h, style, fixed = bars, keepClear = onBars))
            report("under bars: $name", r)
            assertEquals("$name: shade on the bars that stay put", 0, r.clearHits)
            assertEquals("$name: art covered at rest", 0, r.restDamage)
            assertTrue("$name: exact again at rest: ${r.restCoverage}", r.restCoverage > 0.995)
            assertTrue("$name: art covered in steady motion: ${r.steadyMax}px", r.steadyMax <= 8 * w)
        }
    }

    @Test
    fun `off-white grainy paper with soft edges is shaded in motion without covering art`() {
        // paper at 245 with grain down to 242, a ramp at every edge: nothing a site's own JPEGs would not do
        val grit = gritty(scrollStrip(w, seed = 31), paper = 245, grain = 3)
        val sm = ShadeSimulation(grit, w, h, style, 1000.0 / 60, 16.0, 2, 25.0)
        val r = sm.run(Scrolls.ramp(2000.0, 600.0, 150.0, 2000.0), 2300.0, touchLeadMs = touch)
        report("gritty paper", r)
        assertClean(r, "gritty", speed = 600.0)
        assertTrue("shade present while moving: ${r.coverageMovingSum / r.coverageMovingN}", r.coverageMovingSum / r.coverageMovingN > 0.5)
    }
}
