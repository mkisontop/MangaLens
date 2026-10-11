package app.mangalens.gaps

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeWatchTest {

    private val w = 8
    private val h = 40
    private val style = ShadeStyle(ShadeLevel.DARK)

    private val paper = 0xFFFFFFFF.toInt()
    private val ink = 0xFF202020.toInt()

    /** Paper under the shade, as the capture shows it. */
    private val shaded = (0xFF shl 24) or (style.whiteComposite * 0x010101)

    /**
     * Art as light as the faintest ringing, under the shade: it looks just like [shaded], and only
     * a row seen plain before can tell them apart.
     */
    private val faintArtShaded = (0xFF shl 24) or (style.sigLo * 0x010101)

    private fun frame(fill: Int = paper) = IntArray(w * h) { fill }

    private fun IntArray.rows(y0: Int, y1: Int, v: Int) = also { for (y in y0 until y1) for (x in 0 until w) this[y * w + x] = v }

    private fun walk(watch: EdgeWatch, f: IntArray, from: Int, edge: Int, offset: Int, keepOut: List<IntArray> = emptyList()) {
        watch.mark(0, w)
        watch.walk(ArrayPixels(w, h, f), from, edge, offset, style, keepOut)
    }

    @Test
    fun `plain paper is walked to the edge and the walk stops at ink`() {
        val watch = EdgeWatch(w, bottom = true)
        walk(watch, frame(), 20, h, 0)
        assertEquals("all paper: on to the edge", h, watch.stopIn(0, w, orElse = -1))
        walk(watch, frame().rows(31, 33, ink), 20, h, 0)
        assertEquals("ink at row 31", 31, watch.stopIn(0, w, orElse = -1))
    }

    @Test
    fun `paper under the shade counts only on rows seen plain as they came in`() {
        val watch = EdgeWatch(w, bottom = true)
        // Rows 20..39 come in plain: page rows 20..39 at offset 0.
        walk(watch, frame(), 20, h, 0)
        // The page moves up ten rows and the shade is drawn over what was seen: those page rows are
        // frame rows 10..29 now, under the shade, and rows 30..39 come in plain.
        walk(watch, frame().rows(10, 30, shaded), 10, h, -10)
        assertEquals("on to the edge: page row 50", 50, watch.stopIn(0, w, orElse = -1))
        // Ten rows more, and this time the shade has run on past what was seen, over faint art: page
        // rows 50..54, frame rows 30..34, were never seen plain, and what looks like paper there is
        // not believed.
        walk(watch, frame().rows(0, 30, shaded).rows(30, 35, faintArtShaded), 0, h, -20)
        assertEquals("stops at the first row never seen plain: page row 50", 50, watch.stopIn(0, w, orElse = -1))
    }

    @Test
    fun `art that looks like paper under the shade on a row never seen stops the walk`() {
        val watch = EdgeWatch(w, bottom = true)
        walk(watch, frame().rows(25, h, faintArtShaded), 20, h, 0)
        assertEquals(25, watch.stopIn(0, w, orElse = -1))
    }

    @Test
    fun `MangaLens's own controls do not stop the walk`() {
        val watch = EdgeWatch(w, bottom = true)
        val f = frame().rows(30, 34, ink)
        walk(watch, f, 20, h, 0, listOf(intArrayOf(0, 30, w, 34)))
        assertEquals(h, watch.stopIn(0, w, orElse = -1))
    }

    @Test
    fun `columns not asked for are not walked`() {
        val watch = EdgeWatch(w, bottom = true)
        watch.mark(2, 4)
        watch.walk(ArrayPixels(w, h, frame()), 20, h, 0, style, emptyList())
        assertEquals(h, watch.stopIn(2, 4, orElse = -1))
        assertEquals("a column not walked says what it is told to", -1, watch.stopIn(5, 6, orElse = -1))
        assertEquals("the nearest end of all the columns", -1, watch.stopIn(3, 6, orElse = -1))
    }

    @Test
    fun `at the top the walk goes up and ends where the gutter starts`() {
        val watch = EdgeWatch(w, bottom = false)
        walk(watch, frame(), 19, 0, 0)
        assertEquals("all paper: up to the edge", 0, watch.stopIn(0, w, orElse = -1))
        walk(watch, frame().rows(5, 7, ink), 19, 0, 0)
        assertEquals("ink on rows 5 and 6: the gutter starts at row 7", 7, watch.stopIn(0, w, orElse = -1))
        // the page moves down five rows; the rows seen are under the shade now, and believed
        val watch2 = EdgeWatch(w, bottom = false)
        walk(watch2, frame(), 19, 0, 0)
        walk(watch2, frame().rows(5, 25, shaded), 24, 0, 5)
        assertEquals("up to the edge: page row -5", -5, watch2.stopIn(0, w, orElse = 99))
    }
}
