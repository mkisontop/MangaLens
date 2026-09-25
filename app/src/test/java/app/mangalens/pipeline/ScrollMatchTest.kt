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

    /** Whether the strip has anything drawn in rows [y0] until [y1]. */
    private fun inked(y0: Int, y1: Int): Boolean {
        val row = IntArray(w)
        for (y in y0 until y1) {
            strip.getPixels(row, 0, w, 0, y, w, 1)
            if (row.any { it != Color.WHITE }) return true
        }
        return false
    }

    @Test
    fun onlyRowsThatScrolledWithThePageAreKept() {
        val a = ScrollMatch.of(screen(600))
        val later = screen(933)
        // The reader's toolbar slid down over the top of the page since.
        Canvas(later).drawRect(0f, 90f, w.toFloat(), 300f, Paint().apply { color = Color.rgb(40, 40, 48) })
        val b = ScrollMatch.of(later)
        val d = b.scrolledFrom(a) ?: error("the scroll is still measured past the toolbar")
        assertEquals(333, d)
        assertFalse("under the toolbar", b.keeps(a, d, 120, 280))
        // Stretches of the page the scroll carried, wherever something is
        // drawn: below the toolbar, and above where the earlier frame's
        // bottom bar hid the page (row 1130 then, 797 now).
        val span = 320 until 797 - 60
        val kept = (span step 40).filter { y -> inked(933 + y, 933 + y + 60) }
        assertTrue(kept.size >= 4)
        for (y in kept) {
            assertTrue("rows $y+ moved with the page", b.keeps(a, d, y, y + 60))
            assertFalse("rows $y+ at the wrong scroll", b.keeps(a, d + 45, y, y + 60))
        }
        val blank = (span step 20).firstOrNull { y -> !inked(933 + y, 933 + y + 60) }
        if (blank != null) assertFalse("nothing drawn there, nothing to tell by", b.keeps(a, d, blank, blank + 60))
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
    fun aStripReadCountsOnMemoryForEverythingItsNewRowsDoNotReach() {
        val match = ScrollMatch.of(screen(900))
        val before = listOf(
            item(Rect(100, 400, 300, 500), "above"),
            item(Rect(100, 1000, 300, 1100), "margin"),
            item(Rect(100, 1150, 300, 1250), "cut"),
        )
        // Scrolled 300 down: the strip is the bottom 300 rows, the ones the
        // scroll revealed, plus a 300-row margin the model is told to leave.
        val read = StripRead(null, Rect(0, h - 600, w, h), 300, Seen(match, before), match, Rect(0, h - 300, w, h))
        val aboveNow = item(Rect(100, 100, 300, 200), "above")
        val marginNow = item(Rect(100, 700, 300, 800), "margin")
        assertTrue(
            "the upper line and the margin's were found again; the cut one reaches the new rows",
            read.covered(listOf(aboveNow, marginNow), h, 0, 0),
        )
        assertFalse("memory lost the line in the margin, which the model leaves", read.covered(listOf(aboveNow), h, 0, 0))
        assertFalse("memory lost the upper line", read.covered(listOf(marginNow), h, 0, 0))
        assertTrue("a line scrolled into the ignored top band is not expected", read.covered(listOf(marginNow), h, 250, 0))
        assertTrue("the lost margin line is on the strip: reading it whole will do", read.coveredByStrip(listOf(aboveNow), h, 0, 0))
        assertFalse("the lost upper line is not: only the whole screen will", read.coveredByStrip(listOf(marginNow), h, 0, 0))
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

    /**
     * A line the last stop's bottom edge cut was read only as far as it
     * showed. The next strip tells the model its rows are new as well, so
     * it is read whole rather than left to memory's fragment.
     */
    @Test
    fun aLineTheLastStopsEdgeCutIsReadAgainWhole() {
        val h = 2000
        val strip = Rect(0, 634, 1250, 2000)
        val revealed = Rect(0, 1134, 1250, 2000)
        val whole = PageItem(Rect(100, 1500, 200, 1700), ItemKind.SPEECH, "嗯", "Yeah.")
        val cut = PageItem(Rect(610, 1770, 700, 2000), ItemKind.SPEECH, "我們家的陽斗\n隨時可以過去接", "Haruto can pick you up anytime,")
        assertEquals("the revealed rows and a little slack", 1054 to 2000, StripRead.told(h, 866, strip, revealed, 80, listOf(whole), 0, 0))
        assertEquals("back over the cut line, now 866 rows higher", 904 to 2000, StripRead.told(h, 866, strip, revealed, 80, listOf(whole, cut), 0, 0))
        // Scrolled back up: the line the top edge cut, now 600 rows lower.
        val topCut = PageItem(Rect(300, 0, 380, 150), ItemKind.SPEECH, "なに", "What?")
        assertEquals(0 to 750, StripRead.told(h, -600, Rect(0, 0, 1250, 1100), Rect(0, 0, 1250, 600), 80, listOf(topCut), 0, 0))
    }

    @Test
    fun theLinesTheLastStopsEdgeCutAreKnownWhenRecalled() {
        val cutThen = PageItem(Rect(610, 1770, 700, 2000), ItemKind.SPEECH, "我們家的陽斗\n隨時可以過去接", "Haruto can pick you up anytime,")
        val wholeThen = PageItem(Rect(100, 1500, 200, 1700), ItemKind.SPEECH, "嗯", "Yeah.")
        val match = ScrollMatch.of(screen(900))
        val read = StripRead(null, Rect(0, 634, 1250, 2000), 866, Seen(match, listOf(cutThen, wholeThen)), match)
        // Recalled where the scroll put them.
        val cutNow = cutThen.copy(box = Rect(610, 904, 700, 1134))
        val wholeNow = wholeThen.copy(box = Rect(100, 634, 200, 834))
        assertEquals(setOf(cutNow), read.cutByEdge(listOf(cutNow, wholeNow), 2000, 0, 0))
    }

    @Test
    fun aNudgeThatRevealedNothingSendsNothing() = runBlocking {
        val match = ScrollMatch.of(screen(900))
        val read = StripRead(null, Rect(), 12, Seen(match, emptyList()), match)
        assertFalse(read.isActive)
        assertEquals(emptyList<PageItem>(), read.collect(null))
    }
}
