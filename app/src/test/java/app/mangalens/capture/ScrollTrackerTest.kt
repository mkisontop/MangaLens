package app.mangalens.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Exercises the scroll odometer on a synthetic strip and viewport crops, the
 * way the slow-scroll detector is tested: the strip is ground truth, view(o)
 * is the frame capture would deliver with the strip scrolled by o, and the
 * tracker must recover the offset difference from the two frames alone.
 * Recovery is held to ±3 px — the tolerance under which a repositioned card
 * still visually owns its balloon — and, just as important, the tracker must
 * return null rather than a number whenever two frames do not actually show
 * the same content shifted: one invented displacement walks every card off
 * its balloon at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrollTrackerTest {

    private val width = 1080
    private val height = 1920
    private val stripHeight = 4200

    /** The overlay card, fixed to the screen while content scrolls beneath it. */
    private val card = Rect(310, 360, 780, 640)

    private val strip: Bitmap by lazy {
        val bmp = Bitmap.createBitmap(width, stripHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        // Panel rules at deliberately uneven spacing — a periodic layout would
        // let a scrolled page align with itself one panel over.
        val rule = Paint().apply { color = Color.rgb(30, 30, 34) }
        for (y in intArrayOf(760, 1495, 2160, 2890, 3495)) {
            canvas.drawRect(Rect(0, y, width, y + 10), rule)
        }
        drawBalloon(canvas, Rect(140, 320, 620, 585), seed = 1)
        drawBalloon(canvas, Rect(480, 980, 950, 1230), seed = 2)
        drawBalloon(canvas, Rect(90, 1690, 520, 1930), seed = 3)
        drawBalloon(canvas, Rect(400, 2380, 880, 2650), seed = 4)
        drawBalloon(canvas, Rect(170, 3060, 640, 3320), seed = 5)
        drawBalloon(canvas, Rect(520, 3660, 990, 3900), seed = 6)
        bmp
    }

    private fun drawBalloon(canvas: Canvas, box: Rect, seed: Int) {
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.BLACK
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        canvas.drawOval(RectF(box), ink)
        // Irregular line positions and thicknesses, like real lettering — a
        // repeating pattern would hand the matcher false alignments.
        val rows = 3 + seed % 2
        val rowH = box.height() / (rows * 2 + 1)
        for (r in 0 until rows) {
            val top = box.top + rowH * (r * 2 + 1) + ((r * r * 19 + seed * 23) % rowH)
            val inset = 40 + ((r * 37 + seed * 131) % 90)
            val thickness = rowH / 2 + ((r * 13 + seed * 7) % (rowH / 3).coerceAtLeast(1))
            canvas.drawRect(Rect(box.left + inset, top, box.right - inset, top + thickness), fill)
        }
    }

    /** The screen as capture sees it with the strip scrolled by [offset]. */
    private fun view(offset: Int, withCard: Boolean = false, dimmed: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(strip, 0f, -offset.toFloat(), null)
        if (withCard) {
            val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(250, 250, 250) }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(23, 24, 28) }
            canvas.drawRoundRect(RectF(card), 18f, 18f, cardPaint)
            var y = card.top + 20
            while (y < card.bottom - 24) {
                canvas.drawRect(Rect(card.left + 18, y, card.right - 18, y + 12), text)
                y += 32
            }
        }
        if (dimmed) canvas.drawColor(Color.argb(46, 0, 0, 0))
        return bmp
    }

    private fun profileAt(
        offset: Int,
        withCard: Boolean = false,
        dimmed: Boolean = false,
        exclude: List<Rect> = emptyList(),
    ): IntArray {
        val bmp = view(offset, withCard, dimmed)
        val prof = ScrollTracker.profile(bmp, exclude)
        bmp.recycle()
        return prof
    }

    @Test
    fun `scroll deltas are recovered within three pixels`() {
        val prev = profileAt(1000)
        assertEquals(height / ScrollTracker.BIN, prev.size)
        for (d in intArrayOf(8, 60, 240)) {
            val lock = ScrollTracker.delta(prev, profileAt(1000 + d), maxShiftPx = 300)
            assertNotNull("a scroll of $d px must lock", lock)
            println("scroll $d px -> delta ${lock!!.deltaPx} px, confidence %.2f".format(lock.confidence))
            // Scrolling down moves the content up: the displacement is negative.
            assertTrue("scroll of $d px read as ${lock.deltaPx} px", abs(lock.deltaPx + d) <= 3)
            assertTrue("confidence for $d px was ${lock.confidence}", lock.confidence > 0.4f)
        }
    }

    /**
     * The frame capture delivers is never all strip: the status bar, the
     * browser's toolbar and tab row, the system navigation bar, and our own
     * floating button all sit at fixed screen positions with high-contrast
     * detail while the content moves beneath them. Scored at full weight,
     * those bands vote "no movement" hard enough to out-argue the strip —
     * the field failure where the tracker lost lock mid-scroll, coasted,
     * and reset the session, leaving revisited balloons bare.
     */
    private fun withChrome(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val bar = Paint().apply { color = Color.rgb(32, 33, 36) }
        val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(214, 216, 220) }
        // Status bar and browser toolbar: dark bands full of light detail.
        canvas.drawRect(Rect(0, 0, width, 96), bar)
        canvas.drawRect(Rect(0, 96, width, 210), bar)
        for (x in 40 until width - 40 step 90) {
            canvas.drawRect(Rect(x, 24, x + 46, 72), icon)
            canvas.drawRect(Rect(x, 120, x + 60, 186), icon)
        }
        // System navigation, and the floating toggle over the content.
        canvas.drawRect(Rect(0, height - 110, width, height), bar)
        for (x in intArrayOf(width / 4, width / 2, 3 * width / 4)) {
            canvas.drawRect(Rect(x - 30, height - 84, x + 30, height - 30), icon)
        }
        canvas.drawOval(RectF(18f, 300f, 122f, 404f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(59, 63, 140)
        })
        return out
    }

    private fun chromedProfile(offset: Int): IntArray {
        val plain = view(offset)
        val framed = withChrome(plain)
        plain.recycle()
        val prof = ScrollTracker.profile(framed)
        framed.recycle()
        return prof
    }

    @Test
    fun `static chrome cannot anchor the lock to zero`() {
        val prev = chromedProfile(1000)
        for (d in intArrayOf(24, 90, 260)) {
            val lock = ScrollTracker.delta(prev, chromedProfile(1000 + d), maxShiftPx = 320)
            assertNotNull("a scroll of $d px under chrome must lock", lock)
            println("chromed scroll $d px -> delta ${lock!!.deltaPx} px, confidence %.2f".format(lock.confidence))
            assertTrue("chromed scroll of $d px read as ${lock.deltaPx} px", abs(lock.deltaPx + d) <= 3)
        }
    }

    @Test
    fun `a chromed still pair is still, not lost`() {
        val a = chromedProfile(1400)
        val b = chromedProfile(1400)
        val lock = ScrollTracker.delta(a, b, maxShiftPx = 320)
        assertNotNull(lock)
        assertEquals(0, lock!!.deltaPx)
    }

    @Test
    fun `a velocity guess recenters the search window`() {
        // The window alone is far too narrow for a 240 px jump; the guess
        // carries it there.
        val lock = ScrollTracker.delta(profileAt(1000), profileAt(1240), maxShiftPx = 48, guessPx = -230)
        assertNotNull(lock)
        assertTrue("read ${lock!!.deltaPx} px", abs(lock.deltaPx + 240) <= 3)
    }

    @Test
    fun `a still pair locks at zero rather than losing the page`() {
        val lock = ScrollTracker.delta(profileAt(1000), profileAt(1000), maxShiftPx = 300)
        assertNotNull(lock)
        assertEquals(0, lock!!.deltaPx)
        assertTrue("confidence was ${lock.confidence}", lock.confidence > 0.9f)
    }

    @Test
    fun `a uniformly dimmed frame tracks identically`() {
        val prev = profileAt(900)
        val plain = ScrollTracker.delta(prev, profileAt(960), maxShiftPx = 300)
        val dimmed = ScrollTracker.delta(prev, profileAt(960, dimmed = true), maxShiftPx = 300)
        assertNotNull(plain)
        assertNotNull(dimmed)
        assertTrue("plain read ${plain!!.deltaPx} px", abs(plain.deltaPx + 60) <= 3)
        assertTrue(
            "dimmed read ${dimmed!!.deltaPx} px vs plain ${plain.deltaPx} px",
            abs(dimmed.deltaPx - plain.deltaPx) <= 2,
        )
    }

    @Test
    fun `a swapped page returns null`() {
        // An unrelated page: balloons at new positions and a dark flashback
        // panel nothing on the strip resembles. The swap must be caught by
        // the page-change detector — never explained away as a scroll.
        val other = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(other)
        canvas.drawColor(Color.WHITE)
        canvas.drawRect(Rect(0, 620, width, 1040), Paint().apply { color = Color.rgb(38, 38, 44) })
        drawBalloon(canvas, Rect(120, 160, 640, 430), seed = 7)
        drawBalloon(canvas, Rect(430, 1210, 930, 1480), seed = 8)
        drawBalloon(canvas, Rect(90, 1580, 540, 1840), seed = 9)
        val swapped = ScrollTracker.profile(other)
        other.recycle()

        assertNull(ScrollTracker.delta(profileAt(1000), swapped, maxShiftPx = 300))
    }

    @Test
    fun `an excluded card over changing content does not break the lock`() {
        // The card is identical in both frames while the page under it moves:
        // left in, it is a fixed feature arguing for "didn't move".
        val exclude = listOf(card)
        val lock = ScrollTracker.delta(
            profileAt(1000, withCard = true, exclude = exclude),
            profileAt(1060, withCard = true, exclude = exclude),
            maxShiftPx = 300,
        )
        assertNotNull(lock)
        assertTrue("read ${lock!!.deltaPx} px", abs(lock.deltaPx + 60) <= 3)
    }

    @Test
    fun `a featureless white pair returns null`() {
        val a = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val pa = ScrollTracker.profile(a)
        val pb = ScrollTracker.profile(b)
        a.recycle()
        b.recycle()
        assertNull(ScrollTracker.delta(pa, pb, maxShiftPx = 300))
    }

    @Test
    fun `bands mostly covered by an exclusion become unusable`() {
        val wide = Rect(0, 400, 700, 600)
        val prof = profileAt(1000, exclude = listOf(wide))
        for (b in 100 until 150) assertEquals("band $b", -1, prof[b])
        assertTrue(prof[99] >= 0)
        assertTrue(prof[150] >= 0)
    }

    @Test
    fun `stride padding never reaches the profile`() {
        val logical = view(1000)
        val padded = Bitmap.createBitmap(width + 120, height, Bitmap.Config.ARGB_8888)
        Canvas(padded).apply {
            drawColor(Color.BLACK)
            drawBitmap(logical, 0f, 0f, null)
        }
        val a = ScrollTracker.profile(logical)
        val b = ScrollTracker.profile(padded, srcW = width, srcH = height)
        logical.recycle()
        padded.recycle()
        assertArrayEquals(a, b)
    }
}
