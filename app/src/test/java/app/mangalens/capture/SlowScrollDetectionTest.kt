package app.mangalens.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reproduces the manhwa scroll that both legacy detectors are blind to, and
 * checks that row-profile alignment catches it.
 *
 * The reported failure: a balloon is translated near the top of the screen,
 * the reader scrolls on, a *different* balloon arrives at that position — and
 * the card still floating there now shows the previous balloon's line over
 * the new balloon. For that to happen the scroll must go unnoticed, and a
 * slow scroll on a mostly-white strip does exactly that: consecutive frames
 * barely differ (no motion), and the arriving balloon changes only the cells
 * the card itself covers, which cumulative comparison must ignore (no page
 * change). Scrolled content is still self-similar under translation, though,
 * so aligning row brightness profiles of the unmasked cells sees the drift
 * the other two detectors cannot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SlowScrollDetectionTest {

    private val motionThreshold = 3.6
    private val pageChangeFraction = 0.015
    private val slowScrollMinRows = 2

    private val width = 1080
    private val height = 1920
    private val stripHeight = 3900

    /** Balloon translated at the top of the screen at offset 0. */
    private val balloonA = Rect(340, 300, 740, 530)

    /**
     * The balloon that scrolls in to take its place: same size, off-screen at
     * offset 0, exactly at [balloonA]'s screen position at [endOffset].
     */
    private val balloonB = Rect(340, 2220, 740, 2450)

    /** Scroll distance after which B sits precisely where A was. */
    private val endOffset = balloonB.top - balloonA.top

    /**
     * The overlay card, slightly larger than the balloon it covers — the
     * common case of a line that fills its balloon. Fixed to the screen, as a
     * real overlay is.
     */
    private val card = Rect(balloonA.left - 10, balloonA.top - 10, balloonA.right + 10, balloonA.bottom + 10)

    private val strip: Bitmap by lazy {
        val bmp = Bitmap.createBitmap(width, stripHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        drawBalloon(canvas, balloonA, seed = 1)
        drawBalloon(canvas, balloonB, seed = 2)
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
        // perfectly periodic pattern would let a scrolled page align with
        // itself and hide the very drift these tests measure.
        val rows = 3 + seed % 2
        val rowH = box.height() / (rows * 2 + 1)
        for (r in 0 until rows) {
            val top = box.top + rowH * (r * 2 + 1) + ((r * r * 19 + seed * 23) % rowH)
            val inset = 40 + ((r * 37 + seed * 131) % 90)
            val thickness = rowH / 2 + ((r * 13 + seed * 7) % (rowH / 3).coerceAtLeast(1))
            canvas.drawRect(Rect(box.left + inset, top, box.right - inset, top + thickness), fill)
        }
    }

    /** The screen as the capture sees it with the strip scrolled by [offset]. */
    private fun view(offset: Int, withCard: Boolean = false): IntArray {
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
        val thumb = FrameStability.grayThumb(bmp).copyOf()
        bmp.recycle()
        return thumb
    }

    private val mask: BooleanArray get() = FrameStability.mask(listOf(card), width, height)

    @Test
    fun `a slow scroll is invisible to frame differencing`() {
        // ~100 px/s at the capture loop's 80 ms sampling: 8 px per frame.
        val offsets = (1000..1080 step 8).toList()
        val thumbs = offsets.map { view(it, withCard = true) }
        val diffs = thumbs.zipWithNext { a, b -> FrameStability.meanDiff(a, b) }
        println(
            "slow scroll per-frame meanDiff: " + diffs.joinToString(" ") { "%.2f".format(it) } +
                "  (motion threshold %.1f)".format(motionThreshold)
        )
        assertTrue(
            "fixture must model a scroll too slow for frame differencing " +
                "(worst %.2f)".format(diffs.max()),
            diffs.max() < motionThreshold,
        )
    }

    @Test
    fun `a fast scroll is still caught by frame differencing`() {
        val diff = FrameStability.meanDiff(view(1000, withCard = true), view(1300, withCard = true))
        assertTrue("a fling must trip the motion threshold (was %.2f)".format(diff), diff > motionThreshold)
    }

    @Test
    fun `the balloon that slides under the card changes only masked cells`() {
        // The page as translated (clean, before cards paint) vs the screen
        // after the full scroll: A left the viewport, B parked exactly under
        // the card. Every changed cell is a masked cell, so the cumulative
        // detector sees nothing — this is the blindness the bug report
        // describes, with the old line still floating over the new balloon.
        val baseline = view(0)
        val end = view(endOffset, withCard = true)
        val changed = FrameStability.changedFraction(baseline, end, mask)
        println("balloon-under-card changedFraction: %.4f (threshold %.3f)".format(changed, pageChangeFraction))
        assertTrue(
            "fixture must model the swap cumulative comparison cannot see " +
                "(was %.4f)".format(changed),
            changed < pageChangeFraction,
        )
    }

    @Test
    fun `sampled row alignment sees the scroll both legacy detectors miss`() {
        // The capture loop samples drift roughly every 420 ms — about 42 px
        // at the slow-scroll speed the frame differ cannot see.
        val samplePx = 42
        for (start in intArrayOf(600, 1000, 1400)) {
            val drift = FrameStability.verticalShift(
                view(start, withCard = true),
                view(start + samplePx, withCard = true),
                mask,
            )
            println("sampled drift at offset $start: $drift rows")
            assertTrue(
                "drift at offset $start must be caught (was $drift)",
                abs(drift) >= slowScrollMinRows,
            )
        }
    }

    @Test
    fun `cumulative row alignment sees a drift that grew one row at a time`() {
        // Even when every 420 ms sample stays under the per-sample minimum,
        // the comparison against the translated page accumulates the drift.
        val translated = view(1000)
        val drifted = view(1064, withCard = true)
        val drift = FrameStability.verticalShift(translated, drifted, mask)
        println("cumulative drift: $drift rows")
        assertTrue("accumulated drift must be caught (was $drift)", abs(drift) >= slowScrollMinRows)
    }

    @Test
    fun `a still page with capture jitter reports no drift`() {
        val a = view(1000, withCard = true)
        val b = view(1000, withCard = true).also { thumb ->
            for (i in thumb.indices) thumb[i] = (thumb[i] + (i % 3) - 1).coerceIn(0, 255)
        }
        assertEquals(0, FrameStability.verticalShift(a, b, mask))
    }

    @Test
    fun `a dimming filter is not a scroll`() {
        val a = view(1000, withCard = true)
        val dimmed = a.map { (it - 14).coerceAtLeast(0) }.toIntArray()
        assertEquals(0, FrameStability.verticalShift(a, dimmed, mask))
    }

    @Test
    fun `an abrupt page swap finds no alignment to mistake for scrolling`() {
        // Two unrelated pages: balloons at new positions with new lettering.
        // The swap must be caught — but by the page-change detector, not by
        // inventing a scroll.
        val other = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(other)
        canvas.drawColor(Color.WHITE)
        drawBalloon(canvas, Rect(120, 180, 560, 400), seed = 4)
        drawBalloon(canvas, Rect(500, 1500, 940, 1720), seed = 5)
        val swapped = FrameStability.grayThumb(other).copyOf()
        other.recycle()

        val before = view(1000, withCard = true)
        assertEquals(0, FrameStability.verticalShift(before, swapped, mask))
        assertTrue(
            "the swap itself must still trip the page-change detector",
            FrameStability.changedFraction(before, swapped, mask) > pageChangeFraction,
        )
    }
}
