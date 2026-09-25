package app.mangalens.scroll

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The finger never comes down on MangaLens's own controls, nor near the screen's edges. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScrollLaneTest {

    private val w = 1080
    private val h = 2400

    @Test
    fun aClearScreenIsDraggedDownTheMiddle() {
        val lane = ScrollLane.pick(w, h, emptyList())!!
        assertEquals(w * 0.5f, lane.x, 0f)
        assertEquals(h * ScrollLane.TOP, lane.top, 0f)
        assertEquals(h * ScrollLane.BOTTOM, lane.bottom, 0f)
    }

    @Test
    fun theLaneMovesAsideForTheControls() {
        // The 文A row with its pill, across the middle of the screen.
        val controls = Rect(20, 600, 700, 760)
        val lane = ScrollLane.pick(w, h, listOf(controls))!!
        assertTrue("clear of the controls: ${lane.x}", lane.x > controls.right)
        assertEquals("full height", h * (ScrollLane.BOTTOM - ScrollLane.TOP), lane.length, 0.5f)
    }

    @Test
    fun withNoFreeLineTheLongestFreeStretchIsUsed() {
        // A band right across the screen: only the stretch below it is free.
        val band = Rect(0, 900, w, 1000)
        val lane = ScrollLane.pick(w, h, listOf(band))
        assertNotNull(lane)
        assertTrue(lane!!.top >= 1000)
        assertEquals(h * ScrollLane.BOTTOM, lane.bottom, 0f)
    }

    @Test
    fun nowhereFreeIsNoLane() {
        val bands = (0 until 8).map { Rect(0, 400 + it * 200, w, 500 + it * 200) }
        assertNull(ScrollLane.pick(w, h, bands))
        assertNull(ScrollLane.pick(0, 0, emptyList()))
    }
}
