package app.mangalens.ui

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The art's geometry, checked as plain numbers: no canvas needed. */
class PopArtTest {

    @Test
    fun `halftone dots stay within their size range and shrink away from the corner`() {
        val maxDot = 13.5f
        val minDot = 1.8f
        val dots = halftoneDots(
            width = 1200f, height = 2600f, originX = 1200f, originY = 0f,
            reach = 780f, spacing = 42f, maxDot = maxDot, minDot = minDot,
        )
        assertTrue(dots.isNotEmpty())
        for (d in dots) {
            assertTrue("radius ${d.r}", d.r in minDot..maxDot)
            assertTrue(hypot(d.x - 1200f, d.y) < 780f)
        }
        val byDistance = dots.sortedBy { hypot(it.x - 1200f, it.y) }
        for (i in 1 until byDistance.size) {
            assertTrue(byDistance[i].r <= byDistance[i - 1].r + 1e-4f)
        }
    }

    @Test
    fun `halftone rows are staggered by half a step`() {
        val dots = halftoneDots(400f, 400f, 0f, 0f, reach = 1000f, spacing = 20f, maxDot = 4f, minDot = 0f)
        val row0 = dots.filter { it.y == 0f }.map { it.x }
        val row1 = dots.filter { it.y == 10f }.map { it.x }
        assertTrue(0f in row0)
        assertTrue(10f in row1)
        assertTrue(0f !in row1)
    }

    @Test
    fun `nothing to draw on an empty area`() {
        assertTrue(halftoneDots(0f, 100f, 0f, 0f, 100f, 10f, 4f, 0.5f).isEmpty())
    }

    @Test
    fun `the burst alternates jittered tips and notches on the inner radius`() {
        val inner = 106f
        val pts = burstPoints(0f, 0f, inner = inner, outer = 134f)
        assertEquals(28, pts.size)
        pts.forEachIndexed { i, p ->
            val r = hypot(p.x, p.y)
            if (i % 2 == 0) assertTrue("tip $i at $r", r > inner) else assertEquals(inner, r, 0.01f)
        }
        val tips = pts.filterIndexed { i, _ -> i % 2 == 0 }.map { hypot(it.x, it.y) }.toSet()
        assertTrue("the jitter gives the tips different lengths", tips.size > 3)
    }

    @Test
    fun `speed lines skip the tail's sector and grow with progress`() {
        val r = 100f
        val full = speedLines(0f, 0f, r, 1f)
        assertTrue(full.size in 24..31)
        for (l in full) {
            var deg = (atan2(l.start.y, l.start.x) * 180f / PI.toFloat())
            if (deg < 0f) deg += 360f
            assertTrue("line at $deg°", deg < 105f || deg > 155f)
            assertEquals(112f, hypot(l.start.x, l.start.y), 0.01f)
            assertTrue(hypot(l.end.x, l.end.y) > 112f)
        }
        for (l in speedLines(0f, 0f, r, 0f)) {
            assertEquals(0f, hypot(l.end.x - l.start.x, l.end.y - l.start.y), 1e-4f)
        }
    }

    @Test
    fun `every stage has a mood, and a finger on GO squints`() {
        assertEquals(FukiMood.SLEEPY, moodFor(Stage.SETUP, pressed = false))
        assertEquals(FukiMood.AWAKE, moodFor(Stage.READY, pressed = false))
        assertEquals(FukiMood.SQUINT, moodFor(Stage.READY, pressed = true))
        assertEquals(FukiMood.HAPPY, moodFor(Stage.RUNNING, pressed = true))
        assertEquals(FukiMood.NAPPING, moodFor(Stage.PAUSED, pressed = false))
    }
}
