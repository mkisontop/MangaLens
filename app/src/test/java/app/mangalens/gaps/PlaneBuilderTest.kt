package app.mangalens.gaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaneBuilderTest {

    private val style = ShadeStyle(ShadeLevel.DARK)

    private fun frame(w: Int, h: Int, paint: (Strip) -> Unit): IntArray = Strip(w, h).also(paint).px

    @Test
    fun `a coarse block is paper only if all of it is, and ink if any of it is`() {
        val w = 64
        val h = 64
        for (line in 20..23) {
            // a one-pixel line across the frame, on an even row and on an odd one
            val across = frame(w, h) { it.solid(0, line, w, line + 1, Strip.BLACK) }
            val p = PlaneBuilder.build(ArrayPixels(w, h, across), 2, style)
            for (px in 0 until p.w) {
                assertTrue("row $line: the block holding it is ink", p.ink.get(px, line / 2))
                assertFalse("row $line: and not paper", p.paper.get(px, line / 2))
            }
            // and down the frame, on an even column and an odd one
            val down = frame(w, h) { it.solid(line, 0, line + 1, h, Strip.BLACK) }
            val q = PlaneBuilder.build(ArrayPixels(w, h, down), 2, style)
            for (py in 0 until q.h) {
                assertTrue("column $line: the block holding it is ink", q.ink.get(line / 2, py))
                assertFalse("column $line: and not paper", q.paper.get(line / 2, py))
            }
        }
        // a pale grey pixel in a block of paper: no longer paper, but not ink either
        val pale = frame(w, h) { it.solid(9, 9, 10, 10, Strip.rgb(200, 200, 200)) }
        val r = PlaneBuilder.build(ArrayPixels(w, h, pale), 2, style)
        assertFalse(r.paper.get(4, 4))
        assertFalse(r.ink.get(4, 4))
        assertTrue(r.paper.get(5, 5))
    }

    @Test
    fun `paper under the shade still reads as paper in a coarse block`() {
        val w = 64
        val h = 64
        val px = frame(w, h) {}
        val shaded = composite(px, w, h, intArrayOf(0, 0, 33, 64), 1, style)
        val p = PlaneBuilder.build(ArrayPixels(w, h, shaded), 2, style)
        // blocks wholly under the shade, the block the shade's edge cuts through, and plain paper
        for (x in 0 until p.w) assertTrue("block $x", p.paper.get(x, 10))
    }

    @Test
    fun `the moving pass does not run through a thin panel border into a white panel`() {
        // a panel with a one-pixel border and a white inside, flush with the frame's edges: at
        // half resolution, sampling every second row and column missed the border on odd rows
        val w = 720
        val h = 1400
        for (y0 in 300..303) for (x1 in 717..719) {
            val f = frame(w, h) { s ->
                s.solid(0, y0, x1 + 1, y0 + 700, Strip.BLACK)
                s.solid(1, y0 + 1, x1, y0 + 699, Strip.WHITE)
                s.art(1, y0 + 450, x1, y0 + 699)
            }
            val r = GapFinder.find(PlaneBuilder.build(ArrayPixels(w, h, f), 2, style), 8, GapParams(), true)
            val covered = coverage(r.rects, r.rectCount, w, h)
            var inside = 0
            for (y in y0 + 1 until y0 + 450) for (x in 1 until x1) if (covered[y * w + x]) inside++
            assertEquals("panel at row $y0, right border at column $x1: white inside shaded", 0, inside)
            assertTrue("the gutter above is still shaded", covered[100 * w + 360])
        }
    }

    @Test
    fun `a frame read from a later row gives the same gaps, a row further down`() {
        val w = 720
        val h = 1400
        val s = scrollStrip(w)
        val f = s.frame(2001, h)
        val shifted = GapFinder.find(PlaneBuilder.build(RowsFrom(ArrayPixels(w, h, f), 1), 2, style), 0, GapParams(), true).shiftedDown(1)
        val direct = GapFinder.find(PlaneBuilder.build(ArrayPixels(w, h - 1, f.copyOfRange(w, w * h)), 2, style), 0, GapParams(), true)
        assertEquals(direct.rectCount, shifted.rectCount)
        for (i in 0 until direct.rectCount) {
            assertEquals(direct.rects[i * 4], shifted.rects[i * 4])
            assertEquals(direct.rects[i * 4 + 1] + 1, shifted.rects[i * 4 + 1])
            assertEquals(direct.rects[i * 4 + 2], shifted.rects[i * 4 + 2])
            assertEquals(direct.rects[i * 4 + 3] + 1, shifted.rects[i * 4 + 3])
        }
    }
}
