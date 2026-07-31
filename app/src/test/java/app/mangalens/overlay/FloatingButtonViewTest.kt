package app.mangalens.overlay

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The button's state cross-fade is only as good as the channel math under it.
 * The state colors are translucent, so an interpolation that mishandles the
 * alpha channel snaps the disc opaque mid-fade; one that fails to clamp lets
 * an overshooting interpolator push channels into colors neither state owns;
 * and endpoint drift would leave the settled button a shade off its spec.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FloatingButtonViewTest {

    private val live = 0xEE3B3F8C.toInt()
    private val grey = 0xE6555A66.toInt()

    @Test
    fun `the endpoints reproduce their colors exactly`() {
        assertEquals(live, FloatingButtonView.blendArgb(live, grey, 0f))
        assertEquals(grey, FloatingButtonView.blendArgb(live, grey, 1f))
    }

    @Test
    fun `the midpoint moves every channel including alpha`() {
        assertEquals(
            0xFF808080.toInt(),
            FloatingButtonView.blendArgb(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f)
        )
        assertEquals(
            0x80102030.toInt(),
            FloatingButtonView.blendArgb(0x00102030, 0xFF102030.toInt(), 0.5f)
        )
    }

    @Test
    fun `progress outside the unit range clamps to the endpoints`() {
        assertEquals(live, FloatingButtonView.blendArgb(live, grey, -0.4f))
        assertEquals(grey, FloatingButtonView.blendArgb(live, grey, 1.7f))
    }

    @Test
    fun `a saturated channel never carries into its neighbour`() {
        assertEquals(0x00FF00FF, FloatingButtonView.blendArgb(0x00FF00FF, 0x00FF00FF, 0.5f))
    }

    @Test
    fun `content description tracks the state for screen readers`() {
        val v = FloatingButtonView(RuntimeEnvironment.getApplication())
        assertEquals("Translation on: tap to pause", v.contentDescription)
        v.setPaused(true)
        assertEquals("Translation paused: tap to resume", v.contentDescription)
        v.setPaused(false)
        assertEquals("Translation on: tap to pause", v.contentDescription)
    }
}
