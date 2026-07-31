package app.mangalens.translate

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A cached vision answer may only be replayed when the current frame shows
 * the stored layout moved as one piece — the manhwa scroll case. Anything
 * else (zoom, relayout, coincidence) must refuse to replay, and unanchored
 * extras must move with the scroll rather than stay at their old screen
 * position.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayGeometryTest {

    private fun box(l: Int, t: Int, r: Int, b: Int) = Rect(l, t, r, b)

    private val stored = listOf(
        box(100, 100, 400, 250),
        box(550, 400, 900, 560),
        box(120, 700, 480, 860),
    )

    @Test
    fun `a scrolled layout yields its one displacement`() {
        val current = stored.map { Rect(it.left, it.top - 180, it.right, it.bottom - 180) }
        val shift = ReplayGeometry.shift(stored, current)
        assertEquals(ReplayGeometry.Shift(0, -180), shift)
    }

    @Test
    fun `detection jitter within tolerance still aligns`() {
        val current = listOf(
            box(104, -77, 404, 73),      // +4, -177
            box(548, 218, 898, 378),     // -2, -182
            box(120, 521, 480, 681),     // 0, -179
        )
        val shift = ReplayGeometry.shift(stored, current)
        assertEquals(-179, shift!!.dy)
    }

    @Test
    fun `a different region count refuses to replay`() {
        assertNull(ReplayGeometry.shift(stored, stored.take(2)))
    }

    @Test
    fun `a zoomed layout refuses to replay`() {
        val current = stored.map {
            Rect(it.left * 13 / 10, it.top * 13 / 10, it.right * 13 / 10, it.bottom * 13 / 10)
        }
        assertNull(ReplayGeometry.shift(stored, current))
    }

    @Test
    fun `one region moving on its own refuses to replay`() {
        val current = listOf(
            Rect(stored[0]),
            Rect(stored[1]),
            box(120, 400, 480, 560),   // third region jumped independently
        )
        assertNull(ReplayGeometry.shift(stored, current))
    }

    @Test
    fun `an unmoved layout is the zero shift`() {
        assertEquals(ReplayGeometry.Shift(0, 0), ReplayGeometry.shift(stored, stored.map { Rect(it) }))
    }

    @Test
    fun `extras follow the scroll and anchored entries stay untouched`() {
        val anchored = VisionLlmEngine.VisionBubble(2, 0, 0, 0, 0, "src", "en", false)
        val extra = VisionLlmEngine.VisionBubble(-1, 300, 500, 200, 80, "src2", "en2", false)
        val out = ReplayGeometry.shiftExtras(listOf(anchored, extra), ReplayGeometry.Shift(0, -180))

        assertEquals(anchored, out[0])
        assertEquals(300, out[1].nx)
        assertEquals(320, out[1].ny)
        assertEquals(200, out[1].nw)
        assertEquals(80, out[1].nh)
    }
}
