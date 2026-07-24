package app.mangalens.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reproduces the page swap a tap-to-turn reader performs, and checks that the
 * detector notices it.
 *
 * Scrolling is easy to see: content slides across the whole frame, so nearly
 * every cell of the thumbnail changes for many consecutive frames. A tap gives
 * one changed frame and then stillness, and comic pages are mostly white — so
 * the average difference between two entirely different pages can stay under
 * the motion threshold, and the reader sits on an untranslated page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageChangeDetectionTest {

    private val motionThreshold = 3.6
    private val pageChangeFraction = 0.015

    private val width = 1080
    private val height = 1920

    /** Balloon boxes, identical on both pages — a reader keeps its layout. */
    private val balloons = listOf(
        Rect(120, 200, 520, 430),
        Rect(600, 260, 960, 470),
        Rect(160, 1150, 620, 1380),
    )

    /**
     * A comic page: white, ruled into panels, with figures and lettering that
     * differ between [variant]s. Same layout, different content — what a page
     * turn in a fixed-layout reader actually looks like.
     */
    private fun page(variant: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.BLACK
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        // Panel borders are the same on every page.
        canvas.drawRect(Rect(60, 100, 1020, 900), ink)
        canvas.drawRect(Rect(60, 1000, 1020, 1820), ink)

        // Figures: same count, different placement and pose per variant.
        val rnd = variant * 137
        for (i in 0 until 6) {
            val cx = 150 + ((i * 190 + rnd) % 800)
            val cy = if (i < 3) 500 + ((i * 90 + rnd) % 320) else 1400 + ((i * 110 + rnd) % 340)
            canvas.drawOval(RectF(cx - 60f, cy - 90f, cx + 60f, cy + 90f), ink)
            canvas.drawOval(RectF(cx - 24f, cy - 40f, cx - 6f, cy - 18f), fill)
            canvas.drawOval(RectF(cx + 8f, cy - 40f, cx + 26f, cy - 18f), fill)
        }

        // Balloons hold different lettering on each page.
        for ((b, balloon) in balloons.withIndex()) {
            canvas.drawOval(RectF(balloon), white)
            canvas.drawOval(RectF(balloon), ink)
            val rows = 3 + (variant + b) % 2
            val rowH = balloon.height() / (rows * 2 + 1)
            for (r in 0 until rows) {
                val top = balloon.top + rowH * (r * 2 + 1)
                val inset = 40 + ((r * 37 + rnd) % 90)
                canvas.drawRect(
                    Rect(balloon.left + inset, top, balloon.right - inset, top + rowH / 2),
                    fill,
                )
            }
        }
        return bmp
    }

    /** Paints overlay cards on, as they sit while a translated page is shown. */
    private fun withCards(src: Bitmap, cards: List<Rect>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(250, 250, 250) }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(23, 24, 28) }
        for (c in cards) {
            canvas.drawRoundRect(RectF(c), 18f, 18f, card)
            var y = c.top + 18
            while (y < c.bottom - 22) {
                canvas.drawRect(Rect(c.left + 16, y, c.right - 16, y + 12), text)
                y += 30
            }
        }
        return out
    }

    /** Cards sit over the balloons, sized to the text rather than the balloon. */
    private val cards = balloons.map {
        Rect(it.left + 30, it.centerY() - 60, it.right - 30, it.centerY() + 60)
    }

    /** Blends [b] over [a], as a reader's page-turn animation does mid-flight. */
    private fun blend(a: Bitmap, b: Bitmap, t: Float): Bitmap {
        val out = a.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val p = Paint().apply { alpha = (255 * t).toInt().coerceIn(0, 255) }
        canvas.drawBitmap(b, 0f, 0f, p)
        return out
    }

    @Test
    fun `an abrupt page swap is caught by both detectors`() {
        val shown = withCards(page(1), cards)
        val turned = withCards(page(2), cards)
        val a = FrameStability.grayThumb(shown).copyOf()
        val b = FrameStability.grayThumb(turned).copyOf()

        val mask = FrameStability.mask(cards, width, height)
        val mean = FrameStability.meanDiff(a, b)
        val masked = FrameStability.changedFraction(a, b, mask)
        println(
            "\nabrupt swap: meanDiff %.2f (thr %.2f), changedFraction %.3f (thr %.3f)"
                .format(mean, motionThreshold, masked, pageChangeFraction)
        )

        assertTrue("frame differencing should catch an abrupt swap", mean > motionThreshold)
        assertTrue("cumulative comparison should catch it too", masked > pageChangeFraction)
    }

    /**
     * The case frame-to-frame differencing structurally cannot see: a reader
     * that cross-fades between pages. Each step is a small change, so no single
     * pair of consecutive frames clears the motion threshold — and because the
     * previous frame is the reference, it tracks the animation all the way to
     * the new page without ever registering motion. Comparing against the page
     * we translated instead accumulates the whole turn.
     */
    @Test
    fun `an animated page turn is missed frame-to-frame and caught cumulatively`() {
        val from = withCards(page(1), cards)
        val to = withCards(page(2), cards)
        val steps = (0..8).map { blend(from, to, it / 8f) }
        val thumbs = steps.map { FrameStability.grayThumb(it).copyOf() }
        val mask = FrameStability.mask(cards, width, height)

        val consecutive = thumbs.zipWithNext { x, y -> FrameStability.meanDiff(x, y) }
        val worstStep = consecutive.max()
        val cumulative = FrameStability.changedFraction(thumbs.first(), thumbs.last(), mask)

        println(
            "\nanimated page turn over ${steps.size} frames\n" +
                "  largest consecutive meanDiff  %.2f  (motion threshold %.2f)\n"
                    .format(worstStep, motionThreshold) +
                "  cumulative changedFraction    %.3f  (page-change threshold %.3f)\n"
                    .format(cumulative, pageChangeFraction) +
                "  per-step meanDiff: " + consecutive.joinToString(" ") { "%.2f".format(it) } + "\n"
        )

        assertTrue(
            "fixture should model a turn too gradual for frame differencing " +
                "(worst step %.2f vs threshold %.2f)".format(worstStep, motionThreshold),
            worstStep < motionThreshold,
        )
        assertTrue(
            "the turn must be caught against the translated page (was %.3f)".format(cumulative),
            cumulative > pageChangeFraction,
        )
    }

    @Test
    fun `a still page is not mistaken for a page turn`() {
        val shown = withCards(page(1), cards)
        val a = FrameStability.grayThumb(shown).copyOf()

        // The same page again, with the mild jitter a capture pipeline adds.
        val jittered = shown.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(jittered)
        val faint = Paint().apply { color = Color.argb(6, 0, 0, 0) }
        canvas.drawRect(Rect(0, 0, width, height), faint)
        val b = FrameStability.grayThumb(jittered).copyOf()

        val mask = FrameStability.mask(cards, width, height)
        val changed = FrameStability.changedFraction(a, b, mask)
        println("still page, with capture jitter: changedFraction %.4f".format(changed))

        assertTrue(
            "a still page must not trigger a retranslate (was %.4f)".format(changed),
            changed <= pageChangeFraction,
        )
    }

    @Test
    fun `an identical frame reports no change`() {
        val shown = withCards(page(3), cards)
        val a = FrameStability.grayThumb(shown).copyOf()
        val b = FrameStability.grayThumb(shown).copyOf()
        assertTrue(FrameStability.changedFraction(a, b) == 0.0)
    }

    @Test
    fun `a fully masked frame declines to judge rather than guessing`() {
        val a = FrameStability.grayThumb(withCards(page(1), cards)).copyOf()
        val b = FrameStability.grayThumb(withCards(page(2), cards)).copyOf()
        val everything = FrameStability.mask(listOf(Rect(0, 0, width, height)), width, height)
        assertTrue(FrameStability.changedFraction(a, b, everything) == 0.0)
    }
}
