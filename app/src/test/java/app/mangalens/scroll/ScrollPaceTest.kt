package app.mangalens.scroll

import android.graphics.Rect
import app.mangalens.capture.FrameStability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How fast auto-scroll goes for what is on the screen: slower the bigger
 * the balloons passing through the reading rows, never to a standstill,
 * faster through an empty gutter, and eased rather than jerked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScrollPaceTest {

    private val w = 1080
    private val h = 2400

    /** A balloon [share] of the screen's area, centred at [cy]. */
    private fun balloon(share: Float, cy: Int): Rect {
        val bw = 900
        val bh = (share * w * h / bw).toInt()
        return Rect(90, cy - bh / 2, 90 + bw, cy + bh / 2)
    }

    @Test
    fun nothingOnScreenIsTheChosenSpeed() {
        assertEquals(1f, ScrollPace.factor(emptyList(), w, h, blank = false), 0f)
    }

    @Test
    fun anEmptyGutterGoesByFaster() {
        assertEquals(ScrollPace.HURRY, ScrollPace.factor(emptyList(), w, h, blank = true), 0f)
    }

    @Test
    fun theBiggerTheBalloonTheSlowerThePage() {
        val small = ScrollPace.factor(listOf(balloon(0.04f, h / 2)), w, h, blank = false)
        val big = ScrollPace.factor(listOf(balloon(0.18f, h / 2)), w, h, blank = false)
        assertTrue("a small balloon slows the page a little: $small", small in 0.6f..0.85f)
        assertTrue("a big one slows it right down: $big", big < 0.4f)
        assertTrue(big < small)
        // A balloon beside a big one slows it further, never to a standstill.
        val crowd = ScrollPace.factor(List(5) { balloon(0.18f, h / 2) }, w, h, blank = false)
        assertEquals(ScrollPace.SLOWEST, crowd, 0f)
    }

    @Test
    fun aBalloonCountsOnlyWhileItIsInTheReadingRows() {
        val below = balloon(0.1f, (h * 0.95f).toInt())
        assertEquals("not yet in view of the reader", 1f, ScrollPace.factor(listOf(below), w, h, blank = false), 0f)
        val above = balloon(0.1f, (h * 0.05f).toInt())
        assertEquals("already read", 1f, ScrollPace.factor(listOf(above), w, h, blank = false), 0f)
        // Arriving: half of it in the rows counts half.
        val whole = ScrollPace.factor(listOf(balloon(0.1f, h / 2)), w, h, blank = false)
        val b = balloon(0.1f, 0)
        val half = Rect(b).apply { offset(0, (h * ScrollPace.ZONE_BOTTOM).toInt() - b.centerY()) }
        val arriving = ScrollPace.factor(listOf(half), w, h, blank = false)
        assertTrue("$whole < $arriving < 1", arriving > whole && arriving < 1f)
    }

    @Test
    fun aStretchWithNothingDrawnIsBlankAndOneLineOfTextIsNot() {
        val size = FrameStability.SIZE
        val paper = IntArray(size * size) { 250 }
        assertTrue(ScrollPace.blank(paper, size))
        val black = IntArray(size * size) { 12 }
        assertTrue("a black gutter too", ScrollPace.blank(black, size))
        val line = paper.copyOf()
        val row = size / 2
        for (x in 30 until 66) line[row * size + x] = 150
        assertFalse(ScrollPace.blank(line, size))
        // Something drawn outside the reading rows leaves them blank.
        val edge = paper.copyOf()
        for (x in 0 until size) edge[2 * size + x] = 0
        assertTrue(ScrollPace.blank(edge, size))
        assertFalse("but not the whole screen", ScrollPace.blank(edge, size, 0f, 1f))
        assertFalse("no frame yet", ScrollPace.blank(null, size))
    }

    @Test
    fun theSpeedEasesDownQuicklyAndUpGently() {
        val down = ScrollPace.ease(1f, 0.2f, 200)
        val up = ScrollPace.ease(0.2f, 1f, 200)
        assertTrue(down < 1f && down > 0.2f)
        assertTrue(up > 0.2f && up < 1f)
        assertTrue("slowing is quicker than speeding up", (1f - down) > (up - 0.2f))
        assertEquals("it gets there", 0.2f, ScrollPace.ease(1f, 0.2f, 10_000), 0.001f)
        assertEquals(0.5f, ScrollPace.ease(0.5f, 1f, 0), 0f)
    }

    @Test
    fun levelsGoFromACrawlToASkim() {
        var last = 0f
        for (level in ScrollPace.MIN_LEVEL..ScrollPace.MAX_LEVEL) {
            val v = ScrollPace.baseDpPerSecond(level)
            assertTrue(v > last)
            last = v
        }
        assertEquals(ScrollPace.baseDpPerSecond(1), ScrollPace.baseDpPerSecond(-3), 0f)
        assertEquals(ScrollPace.baseDpPerSecond(ScrollPace.MAX_LEVEL), ScrollPace.baseDpPerSecond(40), 0f)
        // Level 10 is where 1.0.5 topped out, a skim; the fastest now goes about four times as fast.
        assertEquals(234f, ScrollPace.baseDpPerSecond(10), 5f)
        assertTrue(ScrollPace.baseDpPerSecond(ScrollPace.MAX_LEVEL) > 900f)
    }

    @Test
    fun aStopHoldsLongerForMoreEnglishAndLessAtAFasterSpeed() {
        val short = ScrollPace.holdMs(20, 4)
        val long = ScrollPace.holdMs(200, 4)
        assertTrue(long > short)
        assertTrue(ScrollPace.holdMs(200, 9) < long)
        assertTrue(ScrollPace.holdMs(0, 10) >= 1_200)
        assertTrue(ScrollPace.holdMs(5_000, 1) <= 15_000)
    }
}
