package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import app.mangalens.translate.ItemKind
import app.mangalens.translate.PageItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * How far a webtoon stop scrolled, to the pixel, and what a strip read of
 * it counts on memory for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrollMatchTest {

    private val w = 540
    private val h = 1200

    /** A long strip of panels and blank gutters, as a webtoon is drawn. */
    private val strip: Bitmap = Bitmap.createBitmap(w, h * 3, Bitmap.Config.ARGB_8888).apply {
        val c = Canvas(this)
        c.drawColor(Color.WHITE)
        val rnd = Random(5)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        var y = 80
        while (y < height - 200) {
            val panel = 300 + rnd.nextInt(300)
            p.color = Color.BLACK
            p.style = Paint.Style.STROKE
            p.strokeWidth = 4f
            c.drawRect(30f, y.toFloat(), w - 30f, (y + panel).toFloat(), p)
            p.style = Paint.Style.FILL
            repeat(40) {
                p.color = Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
                val x = 40f + rnd.nextInt(w - 120)
                val yy = y + 10f + rnd.nextInt(panel - 40)
                c.drawCircle(x, yy, 6f + rnd.nextInt(20), p)
            }
            y += panel + 120 + rnd.nextInt(200)
        }
    }

    /** The screen at scroll offset [top], with a fixed browser bar across its top and bottom. */
    private fun screen(top: Int, bars: Boolean = true): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(strip, 0f, -top.toFloat(), null)
        if (bars) {
            val bar = Paint().apply { color = Color.rgb(40, 40, 48) }
            c.drawRect(0f, 0f, w.toFloat(), 90f, bar)
            c.drawRect(0f, h - 70f, w.toFloat(), h.toFloat(), bar)
            val icon = Paint().apply { color = Color.WHITE }
            c.drawRect(20f, 30f, 200f, 60f, icon)
            c.drawRect(300f, h - 50f, 360f, h - 20f, icon)
        }
        return out
    }

    @Test
    fun aScrollIsMeasuredToThePixel() {
        val a = ScrollMatch.of(screen(600))
        assertEquals(0, ScrollMatch.of(screen(600)).scrolledFrom(a))
        assertEquals(333, ScrollMatch.of(screen(933)).scrolledFrom(a))
        assertEquals(-217, ScrollMatch.of(screen(383)).scrolledFrom(a))
        assertEquals("without browser bars too", 140, ScrollMatch.of(screen(740, bars = false)).scrolledFrom(ScrollMatch.of(screen(600, bars = false))))
    }

    @Test
    fun aDifferentPageIsNotAScroll() {
        val a = ScrollMatch.of(screen(600))
        val other = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(other)
        c.drawColor(Color.WHITE)
        val rnd = Random(99)
        val p = Paint()
        repeat(200) {
            p.color = Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            c.drawRect(rnd.nextInt(w).toFloat(), rnd.nextInt(h).toFloat(), rnd.nextInt(w).toFloat(), rnd.nextInt(h).toFloat(), p)
        }
        assertNull(ScrollMatch.of(other).scrolledFrom(a))
        val blank = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        assertNull("blank paper matches anywhere, so it says nothing", ScrollMatch.of(blank).scrolledFrom(ScrollMatch.of(blank)))
    }

    @Test
    fun aPageTurnedBackToIsRecognisedAsShownBefore() {
        val a = ScrollMatch.of(screen(600))
        assertTrue("the same frame again", ScrollMatch.of(screen(600)).unmovedFrom(a))
        assertFalse("a frame scrolled a little is not the same", ScrollMatch.of(screen(640)).unmovedFrom(a))
        assertFalse("another stretch of the strip is not", ScrollMatch.of(screen(2100)).unmovedFrom(a))
        val blank = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        assertFalse("blank paper says nothing", ScrollMatch.of(blank).unmovedFrom(ScrollMatch.of(blank)))
    }

    @Test
    fun measuringIsQuick() {
        val a = ScrollMatch.of(screen(600))
        val bmp = screen(900)
        ScrollMatch.of(bmp).scrolledFrom(a)
        val t = System.nanoTime()
        repeat(5) { ScrollMatch.of(bmp).scrolledFrom(a) }
        val ms = (System.nanoTime() - t) / 5_000_000
        assertTrue("$ms ms a frame", ms < 150)
    }

    private fun item(box: Rect, en: String) = PageItem(box, ItemKind.SPEECH, en, en)

    @Test
    fun aStripReadCountsOnMemoryForEverythingOutsideTheStrip() {
        val match = ScrollMatch.of(screen(900))
        val before = listOf(item(Rect(100, 400, 300, 500), "above"), item(Rect(100, 900, 300, 1000), "lower"))
        // Scrolled 300 down: the strip is the bottom 300 rows plus a 300-row margin.
        val read = StripRead(null, Rect(0, 600, w, h), 300, Seen(match, before), match)
        val aboveNow = item(Rect(100, 100, 300, 200), "above")
        assertTrue("the upper line was found again; the lower now lies in the strip", read.covered(listOf(aboveNow), h, 0, 0))
        assertFalse("memory lost the upper line", read.covered(emptyList(), h, 0, 0))
        assertTrue("a line scrolled into the ignored top band is not expected", read.covered(emptyList(), h, 250, 0))
    }

    @Test
    fun theHalfOfABalloonTheStripCutIsLeftToMemory() {
        val match = ScrollMatch.of(screen(900))
        val read = StripRead(null, Rect(0, 600, w, h), 300, Seen(match, emptyList()), match)
        val whole = item(Rect(100, 540, 300, 680), "Where were you? I looked everywhere.")
        val half = item(Rect(100, 602, 300, 680), "I looked everywhere.")
        val below = item(Rect(100, 900, 300, 980), "Over here.")
        assertEquals(listOf("Over here."), read.trim(listOf(half, below), listOf(whole)).map { it.en })
        assertEquals("without a memory of it, the half is all there is", 2, read.trim(listOf(half, below), emptyList()).size)
    }

    @Test
    fun aNudgeThatRevealedNothingSendsNothing() = runBlocking {
        val match = ScrollMatch.of(screen(900))
        val read = StripRead(null, Rect(), 12, Seen(match, emptyList()), match)
        assertFalse(read.isActive)
        assertEquals(emptyList<PageItem>(), read.collect(null))
    }
}
