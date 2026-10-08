package app.mangalens.gaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The measuring instruments of the bench, checked against shades whose motion is known exactly. */
class MotionMeterTest {

    private val w = 120
    private val h = 300

    /**
     * A page with a gutter every 100 strip rows, rows [60, 100) of each hundred, from [x0] to [x1];
     * the shade on the screen with the page's row [top] at the top, drawn [lag] rows below where the
     * page has it, held back [margin] rows from each edge, and cut to screen rows [cutTop, cutBottom).
     */
    private fun shade(top: Int, lag: Int = 0, margin: Int = 0, x0: Int = 0, x1: Int = w, cutTop: Int = 0, cutBottom: Int = h, skip: Int = -1): BooleanArray {
        val m = BooleanArray(w * h)
        for (y in maxOf(0, cutTop) until minOf(h, cutBottom)) {
            val s = y + top - lag
            val r = Math.floorMod(s, 100)
            if (Math.floorDiv(s, 100) == skip) continue
            if (r in 60 + margin until 100 - margin) for (x in x0 until x1) m[y * w + x] = true
        }
        return m
    }

    private fun meter(pageTop: Int = 0, pageBottom: Int = h) = MotionMeter(w, h, pageTop, pageBottom, columns = 6)

    @Test
    fun `a shade painted on the page scores nothing however fast the page moves`() {
        for (speed in listOf(1, 7, 23, 61)) {
            val m = meter()
            for (k in 0 until 40) m.observe(shade(k * speed), k * speed, MotionMeter.Phase.MOVING)
            val s = m.summary
            assertEquals(39, s.movingFrames)
            assertEquals("flicker at $speed rows a frame", 0, s.flickerMax)
            assertEquals("wobble at $speed rows a frame", 0, s.moveMax)
            assertEquals("pops at $speed rows a frame", 0, s.pops)
            assertTrue("edges were seen", s.edges > 0)
        }
    }

    @Test
    fun `a shade that lags the page by a steady amount scores nothing`() {
        val m = meter()
        for (k in 0 until 30) m.observe(shade(k * 9, lag = 6, margin = 3), k * 9, MotionMeter.Phase.MOVING)
        assertEquals(0, m.summary.flickerMax)
        assertEquals(0, m.summary.moveMax)
        assertEquals(0, m.summary.pops)
    }

    @Test
    fun `a lag that changes from frame to frame is wobble, by the rows it changes`() {
        val m = meter()
        for (k in 0 until 30) m.observe(shade(k * 9, lag = if (k % 2 == 0) 0 else 3), k * 9, MotionMeter.Phase.MOVING)
        val s = m.summary
        assertEquals(3, s.moveMax)
        assertEquals(3.0, s.moveMean, 1e-9)
        assertEquals(3, s.movePercentile(0.95))
        assertEquals(1.0, s.jumpyShare, 1e-9)
        assertEquals(0, s.pops)
        // each gutter edge on screen moved three rows across the full width: start and stop of every gutter
        assertTrue("flicker counts the rows that changed: ${s.flickerMax}", s.flickerMax >= 3 * w * 2 * 2)
    }

    @Test
    fun `a margin that grows counts as the edges moving`() {
        val m = meter()
        m.observe(shade(0, margin = 4), 0, MotionMeter.Phase.MOVING)
        val f = m.observe(shade(5, margin = 6), 5, MotionMeter.Phase.MOVING)
        assertEquals(2, f.maxMove)
        assertEquals(0, f.pops)
        assertEquals(2, m.summary.moveMax)
        assertEquals(0.0, m.summary.jumpyShare, 1e-9)
    }

    @Test
    fun `a gutter whose shade vanishes is a pop, not a wobble`() {
        val m = meter()
        m.observe(shade(0), 0, MotionMeter.Phase.MOVING)
        // the gutter at strip rows [160, 200) loses its shade
        val f = m.observe(shade(4, skip = 1), 4, MotionMeter.Phase.MOVING)
        assertEquals(0, f.maxMove)
        assertEquals("a start and a stop in each of six columns", 12, f.pops)
        assertEquals(40 * w, f.flicker)
    }

    @Test
    fun `the cut at a bar that stays put is not counted when the page is known to stop there`() {
        // the shade is cut at screen row 50, where the page's rows begin: the cut stays put while the page moves
        val m = meter(pageTop = 50)
        for (k in 0 until 20) m.observe(shade(k * 7, cutTop = 50), k * 7, MotionMeter.Phase.MOVING)
        assertEquals(0, m.summary.flickerMax)
        assertEquals(0, m.summary.moveMax)
        assertEquals(0, m.summary.pops)
        // measured over the whole screen, the same cut sweeps over the page
        val all = meter()
        for (k in 0 until 20) all.observe(shade(k * 7, cutTop = 50), k * 7, MotionMeter.Phase.MOVING)
        assertTrue(all.summary.flickerMax > 0)
    }

    @Test
    fun `settling and still vsyncs are kept apart from moving ones`() {
        val m = meter()
        m.observe(shade(0, margin = 8), 0, MotionMeter.Phase.MOVING)
        m.observe(shade(0, margin = 0), 0, MotionMeter.Phase.SETTLING)
        m.observe(shade(0, margin = 0), 0, MotionMeter.Phase.STILL)
        val s = m.summary
        assertEquals(0, s.movingFrames)
        assertEquals("settling edges stay out of the moving figures", 0L, s.edges)
        assertEquals(0, s.moveMax)
        assertEquals(1, s.settleFrames)
        assertEquals(8, s.settleMoveMax)
        assertEquals(0, s.stillFlickerMax)
    }

    @Test
    fun `nothing on the glass in between means nothing to compare with`() {
        val m = meter()
        m.observe(shade(0), 0, MotionMeter.Phase.MOVING)
        m.forget()
        val f = m.observe(shade(5, lag = 9), 5, MotionMeter.Phase.MOVING)
        assertTrue(!f.compared)
        assertEquals(0, m.summary.movingFrames)
    }

    @Test
    fun `moving coverage keeps slivers out of the per-frame figures and counts blackouts`() {
        val c = MovingCoverage()
        val screen = 1000L * 1000
        c.add(900, 1000, screen, 0.0)            // a sliver: weighted only
        c.add(18_000, 20_000, screen, 1.0)        // 0.9
        c.add(5_000, 40_000, screen, 2.0)         // 0.125: a blackout
        c.add(30_000, 40_000, screen, 3.0)        // 0.75
        assertEquals(3, c.count)
        assertEquals(1, c.blackouts)
        assertEquals((0.9 + 0.125 + 0.75) / 3, c.mean, 1e-9)
        assertEquals(0.125, c.min, 1e-9)
        assertEquals(0.125, c.percentile(0.10), 1e-9)
        assertEquals((900 + 18_000 + 5_000 + 30_000).toDouble() / (1000 + 20_000 + 40_000 + 40_000), c.weighted, 1e-9)
    }

    @Test
    fun `the white truth is the inside of balloons and lettering, never the gutter`() {
        val s = Strip(200, 400, 3)
        s.art(0, 0, 200, 100)
        s.balloon(100, 250, 60, 40, lettering = false)
        s.art(0, 330, 200, 400)
        val white = protectedWhite(s)
        // the balloon's inside is protected, the gutter around it is not
        assertTrue(white[250 * 200 + 100])
        assertTrue(!white[150 * 200 + 10])
        assertTrue(!white[310 * 200 + 190])
        // and art is never in it
        assertTrue(!white[50 * 200 + 50])
    }

    @Test
    fun `capture noise is seeded, and a lost frame is only ever lost to a newer one`() {
        val strip = scrollStrip(360)
        val style = ShadeStyle(ShadeLevel.DARK)
        fun once() = ShadeSimulation(
            strip, 360, 700, style, captureJitterMs = 8.0, captureDropShare = 0.3, drawJitterMs = 5.0, jobJitterMs = 10.0, seed = 9,
        ).run(Scrolls.ramp(2000.0, 500.0, 120.0, 900.0), 1000.0, touchLeadMs = 80.0)
        val a = once()
        val b = once()
        assertEquals("the same seed plays the same run", a.toString() + a.motionLine(), b.toString() + b.motionLine())
        assertTrue("frames were lost: ${a.framesDropped}", a.framesDropped > 0)
        // the page ends still; every changed frame has been delivered or lost by the end of the tail
        assertEquals(a.framesChanged, a.framesDelivered + a.framesDropped)
    }
}
