package app.mangalens.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import app.mangalens.capture.ScreenCaptureService.PageCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A reader who scrolls while the model is still streaming must see the
 * cards come down at once, however many times in a row they do it.
 *
 * Every item that lands repaints the cards, and to frame differencing a
 * repaint looks like motion. That check used to sit out a window around
 * every paint, so for as long as a stream lasted it never ran at all. The
 * check against the translated page was then the only one left, and it
 * counted each of those scrolls as a replaced page, which only two passes
 * in a row may be cancelled for. From the third scroll the cards stayed
 * painted over a page that had moved on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MidStreamMotionTest {

    private val pageChangeFraction = 0.015

    private val width = 1080
    private val height = 1920
    private val stripHeight = 4200

    /** Balloons down the strip, at irregular spacing, as a manhwa letters them. */
    private val balloons = listOf(
        Rect(120, 180, 560, 420),
        Rect(560, 700, 980, 930),
        Rect(160, 1180, 620, 1400),
        Rect(520, 1620, 960, 1840),
        Rect(140, 2150, 600, 2380),
        Rect(500, 2700, 940, 2920),
        Rect(180, 3240, 640, 3470),
        Rect(480, 3760, 920, 3990),
    )

    /** A strip with panels and figures drawn in. */
    private val strip: Bitmap by lazy { stripOf(withArt = true) }

    /** A mostly-white strip: balloons and nothing else. */
    private val sparse: Bitmap by lazy { stripOf(withArt = false) }

    private fun stripOf(withArt: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(width, stripHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        if (withArt) drawArt(canvas, seed = 1)
        for ((i, b) in balloons.withIndex()) drawBalloon(canvas, b, seed = i + 1)
        return bmp
    }

    /** Panels with a few figures in them. */
    private fun drawArt(canvas: Canvas, seed: Int) {
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.BLACK
        }
        val tone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 150, 155) }
        var top = 60
        var i = 0
        while (top < stripHeight - 200) {
            val h = 520 + ((i * 173 + seed * 61) % 380)
            canvas.drawRect(Rect(50, top, 1030, top + h), ink)
            for (f in 0 until 3) {
                val cx = 180 + ((f * 290 + i * 131 + seed * 47) % 720)
                val cy = top + 120 + ((f * 97 + i * 53) % (h - 240).coerceAtLeast(1))
                canvas.drawOval(RectF(cx - 70f, cy - 100f, cx + 70f, cy + 100f), tone)
                canvas.drawOval(RectF(cx - 70f, cy - 100f, cx + 70f, cy + 100f), ink)
            }
            top += h + 70
            i++
        }
    }

    private fun drawBalloon(canvas: Canvas, box: Rect, seed: Int) {
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.BLACK
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawOval(RectF(box), white)
        canvas.drawOval(RectF(box), ink)
        // Irregular lines, like real lettering: a periodic pattern would let
        // a scrolled page align with itself.
        val rows = 3 + seed % 2
        val rowH = box.height() / (rows * 2 + 1)
        for (r in 0 until rows) {
            val top = box.top + rowH * (r * 2 + 1) + ((r * r * 19 + seed * 23) % rowH)
            val inset = 40 + ((r * 37 + seed * 131) % 90)
            val thickness = rowH / 2 + ((r * 13 + seed * 7) % (rowH / 3).coerceAtLeast(1))
            canvas.drawRect(Rect(box.left + inset, top, box.right - inset, top + thickness), fill)
        }
    }

    /** Cards over the balloons on screen at offset 1000, fixed to the screen as real overlays are. */
    private val early = listOf(
        Rect(170, 200, 610, 380),
        Rect(530, 640, 970, 820),
    )

    /** The next streamed batch, painted a moment later. */
    private val batch = listOf(
        Rect(140, 1170, 600, 1360),
        Rect(470, 1690, 950, 1890),
    )

    /**
     * The screen as the capture sees it: [page] scrolled by [offset],
     * [cards] on top, their lettering at [textAlpha]. A card's background
     * is opaque from its first frame, and only the lettering fades in.
     */
    private fun view(
        offset: Int,
        cards: List<Rect> = emptyList(),
        textAlpha: Int = 255,
        page: Bitmap = strip,
    ): IntArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(page, 0f, -offset.toFloat(), null)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(250, 250, 250) }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(23, 24, 28)
            alpha = textAlpha
        }
        for (c in cards) {
            canvas.drawRoundRect(RectF(c), 18f, 18f, cardPaint)
            var y = c.top + 20
            while (y < c.bottom - 24) {
                canvas.drawRect(Rect(c.left + 18, y, c.right - 18, y + 14), text)
                y += 32
            }
        }
        val thumb = FrameStability.grayThumb(bmp).copyOf()
        bmp.recycle()
        return thumb
    }

    private fun mask(cards: List<Rect>) = FrameStability.mask(cards, width, height)

    @Test
    fun `a streamed batch fading in is not motion while it settles`() {
        val before = view(1000, early)
        val fading = view(1000, early + batch, textAlpha = 90)
        val settled = view(1000, early + batch)
        val mask = mask(early + batch)
        println(
            "batch fading in: meanDiff %.2f, past the mask %.2f"
                .format(FrameStability.meanDiff(before, fading), FrameStability.meanDiff(before, fading, mask))
        )

        // Why the window exists at all: counted over every cell, our own
        // repaint looks just like the reader scrolling.
        assertTrue(
            "fixture must model a repaint plain differencing mistakes for motion",
            ScreenCaptureService.frameMoved(before, fading, mask, ownPaintSettling = false),
        )
        assertFalse(ScreenCaptureService.frameMoved(before, fading, mask, ownPaintSettling = true))
        assertFalse(ScreenCaptureService.frameMoved(fading, settled, mask, ownPaintSettling = true))
    }

    @Test
    fun `a scroll under streamed cards is motion while they settle`() {
        val cards = early + batch
        val mask = mask(cards)
        // An ordinary scroll, about 1500 px/s at the capture loop's 80 ms,
        // on a strip with art and on a mostly-white one.
        for ((name, page) in listOf("art" to strip, "white" to sparse)) {
            for (start in intArrayOf(600, 1000, 1500)) {
                val a = view(start, cards, page = page)
                val b = view(start + 120, cards, page = page)
                val past = FrameStability.meanDiff(a, b, mask)
                println("$name strip, scroll under cards at $start: meanDiff past the mask %.2f".format(past))
                assertTrue(
                    "a scroll at $start on the $name strip must be caught while cards settle (was %.2f)".format(past),
                    ScreenCaptureService.frameMoved(a, b, mask, ownPaintSettling = true),
                )
            }
        }
    }

    @Test
    fun `a scroll the rows show is judged a scroll however many cells it changed`() {
        val cards = early + batch
        val mask = mask(cards)
        // The page as it was grabbed for translation, and the screen a
        // little way into a scroll with the cards still up.
        val translated = view(1000)
        for (distance in intArrayOf(42, 64, 100, 200, 300)) {
            val scrolled = view(1000 + distance, cards)
            val changed = FrameStability.changedFraction(translated, scrolled, mask)
            val check = ScreenCaptureService.judgeAgainstShown(translated, scrolled, mask)
            println("scrolled $distance px: changedFraction %.3f, judged $check".format(changed))
            // Enough cells changed to pass for a replaced page. Checked
            // first, that would have spent the budget of cancels meant for
            // a region that never stops changing.
            assertTrue(changed > pageChangeFraction)
            assertEquals("a $distance px scroll", PageCheck.SCROLLED, check)
        }
    }

    @Test
    fun `a replaced page is judged replaced and a still one the same`() {
        val cards = early + batch
        val mask = mask(cards)
        val translated = view(1000)

        val other = Bitmap.createBitmap(width, stripHeight, Bitmap.Config.ARGB_8888)
        Canvas(other).apply {
            drawColor(Color.WHITE)
            drawArt(this, seed = 7)
            drawBalloon(this, Rect(100, 1300, 520, 1520), seed = 9)
            drawBalloon(this, Rect(580, 2200, 1000, 2420), seed = 10)
        }
        val swapped = view(1000, cards, page = other)
        other.recycle()
        assertEquals(PageCheck.REPLACED, ScreenCaptureService.judgeAgainstShown(translated, swapped, mask))

        val still = view(1000, cards).also { thumb ->
            for (i in thumb.indices) thumb[i] = (thumb[i] + (i % 3) - 1).coerceIn(0, 255)
        }
        assertEquals(PageCheck.SAME, ScreenCaptureService.judgeAgainstShown(translated, still, mask))
    }
}
