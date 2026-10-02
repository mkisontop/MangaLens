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
 * A still page with an animated banner on it must end up translated and
 * stay that way.
 *
 * The banner changes more of the page than a page turn needs to, so it
 * cancels passes as a turn does, until the budget for that is spent and a
 * pass is left to finish. The cards it paints used to come down on the
 * very next frame, since a finished page was judged against the banner as
 * before, and the loop ran forever: a pass, cards for a frame, a pass. The
 * cells that keep changing while that pass runs are now learned and left
 * out, so the banner no longer counts, while a page turned under the cards
 * still does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RestlessRegionTest {

    private val width = 1080
    private val height = 1920
    private val frameMs = 80L

    private val balloons = listOf(
        Rect(120, 200, 520, 430),
        Rect(600, 260, 960, 470),
        Rect(160, 1150, 620, 1380),
    )

    /** Cards over the balloons, as they sit while the pass streams and once it is done. */
    private val cards = balloons.map {
        Rect(it.left + 30, it.centerY() - 60, it.right - 30, it.centerY() + 60)
    }

    /** An ad strip along the bottom, below the panels: four thumb rows. */
    private val banner = Rect(0, 1830, width, 1910)

    private val pages = mapOf(1 to page(1), 2 to page(2))

    /** A comic page, as PageChangeDetectionTest draws one: same layout, different content per [variant]. */
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
        canvas.drawRect(Rect(60, 100, 1020, 900), ink)
        canvas.drawRect(Rect(60, 1000, 1020, 1780), ink)
        val rnd = variant * 137
        for (i in 0 until 6) {
            val cx = 150 + ((i * 190 + rnd) % 800)
            val cy = if (i < 3) 500 + ((i * 90 + rnd) % 320) else 1400 + ((i * 110 + rnd) % 300)
            canvas.drawOval(RectF(cx - 60f, cy - 90f, cx + 60f, cy + 90f), ink)
            canvas.drawOval(RectF(cx - 24f, cy - 40f, cx - 6f, cy - 18f), fill)
            canvas.drawOval(RectF(cx + 8f, cy - 40f, cx + 26f, cy - 18f), fill)
        }
        for ((b, balloon) in balloons.withIndex()) {
            canvas.drawOval(RectF(balloon), white)
            canvas.drawOval(RectF(balloon), ink)
            val rows = 3 + (variant + b) % 2
            val rowH = balloon.height() / (rows * 2 + 1)
            for (r in 0 until rows) {
                val top = balloon.top + rowH * (r * 2 + 1)
                val inset = 40 + ((r * 37 + rnd) % 90)
                canvas.drawRect(Rect(balloon.left + inset, top, balloon.right - inset, top + rowH / 2), fill)
            }
        }
        return bmp
    }

    /**
     * The screen as the capture sees it: [page] under the banner at [phase]
     * of its marquee, with the cards on top when [withCards]. With [into],
     * the page is fading into that one, [fade] of the way.
     */
    private fun frame(page: Int, phase: Int, withCards: Boolean = true, into: Int? = null, fade: Float = 0f): IntArray {
        val bmp = pages.getValue(page).copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)
        if (into != null) canvas.drawBitmap(pages.getValue(into), 0f, 0f, Paint().apply { alpha = (255 * fade).toInt() })
        // A marquee of soft stripes, 45 px along per step: a gentle change,
        // well under what frame differencing takes for a scroll.
        canvas.save()
        canvas.clipRect(banner)
        canvas.drawColor(Color.rgb(220, 220, 220))
        val stripe = Paint().apply { color = Color.rgb(150, 150, 150) }
        var x = -180 + (phase % 4) * 45
        while (x < width) {
            canvas.drawRect(Rect(x, banner.top, x + 90, banner.bottom), stripe)
            x += 180
        }
        canvas.restore()
        if (withCards) {
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
        }
        val thumb = FrameStability.grayThumb(bmp).copyOf()
        bmp.recycle()
        return thumb
    }

    private val mask = FrameStability.mask(cards, width, height)

    /** Runs [frames] through the learning a pass left to finish does, one [frameMs] apart. */
    private fun learn(frames: List<IntArray>): BooleanArray? {
        val stirredAt = LongArray(frames.first().size)
        var restless: BooleanArray? = null
        for (k in 1 until frames.size) {
            ScreenCaptureService.learnRestless(frames[k - 1], frames[k], mask, stirredAt, 1000L + k * frameMs)
                ?.let { restless = FrameStability.union(restless, it) }
        }
        return restless
    }

    private fun rowsOf(cells: BooleanArray?): Set<Int> =
        cells?.indices?.filter { cells[it] }?.map { it / FrameStability.SIZE }?.toSet().orEmpty()

    @Test
    fun `a banner that never stops changing stops taking the finished cards down`() {
        // The page as the pass grabbed it, cards cleared.
        val base = frame(1, phase = 0, withCards = false)
        // Two seconds of a pass left to finish, the banner stepping on.
        val frames = (0..25).map { frame(1, phase = it) }

        for (k in 1 until frames.size) {
            assertFalse(
                "fixture: the banner must be too gentle for frame differencing",
                ScreenCaptureService.frameMoved(frames[k - 1], frames[k], mask, ownPaintSettling = false),
            )
        }
        // The loop: every phase but the grabbed one reads as a replaced page.
        for (phase in 1..3) {
            assertEquals("fixture, phase $phase", PageCheck.REPLACED, ScreenCaptureService.judgeAgainstShown(base, frame(1, phase), mask))
        }

        val restless = learn(frames)
        println("learned restless rows: ${rowsOf(restless).sorted()}")
        val judged = FrameStability.union(mask, restless)
        for (phase in 0..3) {
            assertEquals(
                "the finished page with the banner at phase $phase",
                PageCheck.SAME,
                ScreenCaptureService.judgeAgainstShown(base, frame(1, phase), judged),
            )
        }
        assertTrue("only the banner is learned", rowsOf(restless).all { it * height / FrameStability.SIZE >= banner.top - 40 })

        // A page turned under the cards later is still caught.
        for (phase in 0..3) {
            assertEquals(
                "a turned page, banner at phase $phase",
                PageCheck.REPLACED,
                ScreenCaptureService.judgeAgainstShown(base, frame(2, phase), judged),
            )
        }
    }

    @Test
    fun `a page turned while the pass runs is not learned, and is caught once it ends`() {
        val base = frame(1, phase = 0, withCards = false)
        // An abrupt turn, and one that fades over a few frames, a second into the pass.
        for (steps in intArrayOf(1, 3)) {
            val frames = (0..30).map { k ->
                when {
                    k < 12 -> frame(1, phase = k)
                    k < 12 + steps -> frame(1, phase = k, into = 2, fade = (k - 11).toFloat() / steps)
                    else -> frame(2, phase = k)
                }
            }
            val restless = learn(frames)
            println("turn over $steps frame(s): learned restless rows ${rowsOf(restless).sorted()}")
            assertTrue(
                "the turn is not taken for a restless region",
                rowsOf(restless).all { it * height / FrameStability.SIZE >= banner.top - 40 },
            )
            assertEquals(
                "turn over $steps frame(s)",
                PageCheck.REPLACED,
                ScreenCaptureService.judgeAgainstShown(base, frames.last(), FrameStability.union(mask, restless)),
            )
        }
    }
}
