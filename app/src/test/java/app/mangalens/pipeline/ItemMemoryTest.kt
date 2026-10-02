package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import app.mangalens.translate.ItemKind
import app.mangalens.translate.PageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.random.Random

/**
 * Lettering recalled from an earlier stop must be the same lettering, never
 * merely something at the same place. The earlier, position-keyed memory
 * painted one balloon's English onto a neighbour drawn close to it; these
 * pages put balloons side by side, give neighbours lines that differ by a
 * single character, and scroll a new balloon into the exact spot an old one
 * held.
 *
 * Glyphs are drawn as seeded stroke patterns rather than font text, so
 * every character is distinct on any JVM, whatever fonts it has.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ItemMemoryTest {

    private val w = 720
    private val h = 1600

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** One seeded glyph in a [size]-pixel cell at (x, y). */
    private fun glyph(c: Canvas, ch: Char, x: Float, y: Float, size: Float) {
        val rnd = Random(ch.code * 7919)
        repeat(4) {
            c.drawLine(
                x + rnd.nextFloat() * size, y + rnd.nextFloat() * size,
                x + rnd.nextFloat() * size, y + rnd.nextFloat() * size, ink,
            )
        }
    }

    /**
     * A balloon at ([cx], [cy]) holding [text] as one horizontal line, and
     * the item a model would report for it — box tight on the lettering.
     */
    private fun balloon(c: Canvas, text: String, cx: Float, cy: Float, en: String): PageItem {
        val size = 26f
        val lineW = text.length * size
        val oval = RectF(cx - lineW / 2 - 40, cy - 55, cx + lineW / 2 + 40, cy + 55)
        c.drawOval(oval, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        c.drawOval(oval, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.BLACK })
        text.forEachIndexed { i, ch -> glyph(c, ch, cx - lineW / 2 + i * size, cy - size / 2, size - 4) }
        val box = Rect(
            (cx - lineW / 2 - 4).toInt(), (cy - size / 2 - 4).toInt(),
            (cx + lineW / 2 + 4).toInt(), (cy + size / 2 + 4).toInt(),
        )
        return PageItem(box, ItemKind.SPEECH, text, en)
    }

    private fun blank(): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // A light screentone-ish page, so the paper is not perfectly flat.
        c.drawColor(Color.rgb(236, 236, 232))
        return bmp to c
    }

    /** [src] scrolled up by [dy], with the revealed strip left blank for new content. */
    private fun scrolled(src: Bitmap, dy: Int): Pair<Bitmap, Canvas> {
        val (bmp, c) = blank()
        c.drawBitmap(src, 0f, -dy.toFloat(), null)
        return bmp to c
    }

    @Test
    fun scrolledLetteringIsFoundWhereItMoved() {
        val (page, c) = blank()
        val a = balloon(c, "ABCDEFG", 360f, 700f, "Where were you?")
        val b = balloon(c, "HIJKL", 300f, 1100f, "Nowhere.")
        val memory = ItemMemory()
        memory.remember(page, listOf(a, b))

        val (next, _) = scrolled(page, 400)
        val found = memory.recall(next)
        assertEquals(2, found.size)
        val fa = found.single { it.en == a.en }
        val fb = found.single { it.en == b.en }
        assertTrue("moved up by the scroll", abs(fa.box.top - (a.box.top - 400)) <= 4)
        assertTrue("moved up by the scroll", abs(fb.box.top - (b.box.top - 400)) <= 4)
    }

    @Test
    fun letteringIsFoundAfterAScrollOfAnyNumberOfRows() {
        // Scrolls by a multiple of the fingerprint's four-row cells were the
        // only ones recalled: three stops in four read everything again.
        val (page, c) = blank()
        val a = balloon(c, "ABCDEFG", 360f, 700f, "Where were you?")
        val b = balloon(c, "HIJKL", 300f, 1100f, "Nowhere.")
        for (dy in listOf(401, 402, 403, 297, 118)) {
            val memory = ItemMemory()
            memory.remember(page, listOf(a, b))
            val found = memory.recall(scrolled(page, dy).first)
            assertEquals("both lines after a $dy-row scroll", 2, found.size)
            for (item in listOf(a, b)) {
                val f = found.single { it.en == item.en }
                assertTrue("${item.en} moved up by $dy: ${f.box.top}", abs(f.box.top - (item.box.top - dy)) <= 2)
            }
        }
    }

    @Test
    fun aLineReachingIntoTheTopBandIsStillRepainted() {
        // The band at the top is left to the status bar; a line reaching into
        // it but mostly below is lettered, as a fresh read would letter it.
        val (page, c) = blank()
        val a = balloon(c, "ABCDEFG", 360f, 700f, "Where were you?")
        val memory = ItemMemory()
        memory.remember(page, listOf(a))
        val found = memory.recall(scrolled(page, a.box.top - 20).first, ignoreTop = 40)
        assertEquals(listOf(a.en), found.map { it.en })
    }

    @Test
    fun aLineTheScreenEdgeCutsComesBackWhereTheScrollPutIt() {
        val (page, c) = blank()
        val a = balloon(c, "ABCDEFG", 360f, 700f, "Where were you?")
        val b = balloon(c, "HIJKL", 300f, 1100f, "Nowhere.")
        val memory = ItemMemory()
        memory.remember(page, listOf(a, b))
        // Scrolled until a quarter of the first line is above the screen.
        val dy = a.box.top + a.box.height() / 4
        val next = scrolled(page, dy).first
        assertEquals("a line partly off the screen cannot be searched for", listOf(b.en), memory.recall(next).map { it.en })
        val cut = memory.recallCut(next, listOf(a, b), -dy)
        assertEquals(listOf(a.en), cut.map { it.en })
        val box = cut.single().box
        assertEquals("the part still on screen", 0, box.top)
        assertTrue("$box", abs(box.bottom - (a.box.bottom - dy)) <= 2 && abs(box.left - a.box.left) <= 4)
    }

    @Test
    fun aCutLineSomethingNowCoversIsNotRecalled() {
        val (page, c) = blank()
        val a = balloon(c, "ABCDEFG", 360f, 700f, "Where were you?")
        val memory = ItemMemory()
        memory.remember(page, listOf(a))
        val dy = a.box.top + a.box.height() / 4
        val (next, nc) = scrolled(page, dy)
        // A toolbar slid in over the top of the screen.
        nc.drawRect(0f, 0f, w.toFloat(), 60f, Paint().apply { color = Color.rgb(40, 40, 48) })
        assertTrue(memory.recallCut(next, listOf(a), -dy).isEmpty())
    }

    @Test
    fun onlyLinesOfTheFrameReadLastAreCarriedByItsScroll() {
        // A memory from some earlier frame has its own coordinates: the
        // scroll measured from the last frame says nothing about where it is.
        val (page, c) = blank()
        val a = balloon(c, "ABCDEFG", 360f, 700f, "Where were you?")
        val memory = ItemMemory()
        memory.remember(page, listOf(a))
        val dy = a.box.top + a.box.height() / 4
        assertTrue(memory.recallCut(scrolled(page, dy).first, emptyList(), -dy).isEmpty())
    }

    @Test
    fun aBalloonReadAsTwoPiecesComesBackWholeAndInOrder() {
        val (page, c) = blank()
        // One balloon, its two lines reported as two items whose boxes graze.
        val size = 26f
        val oval = RectF(160f, 640f, 560f, 780f)
        c.drawOval(oval, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        "ABCDEFGHIJ".forEachIndexed { i, ch -> glyph(c, ch, 230f + i * size, 672f, size - 4) }
        "KLMNOPQRST".forEachIndexed { i, ch -> glyph(c, ch, 230f + i * size, 700f, size - 4) }
        val top = PageItem(Rect(226, 668, 494, 700), ItemKind.SPEECH, "ABCDEFGHIJ", "Where were you?")
        val bottom = PageItem(Rect(226, 698, 494, 730), ItemKind.SPEECH, "KLMNOPQRST", "I looked everywhere.")
        val memory = ItemMemory()
        memory.remember(page, listOf(top, bottom))

        val (next, _) = scrolled(page, 300)
        val found = memory.recall(next)
        assertEquals("both pieces, top first", listOf(top.en, bottom.en), found.map { it.en })
    }

    @Test
    fun lineReadAgainAtTheNextStopIsRememberedOnceNotOncePerStop() {
        val (page, c) = blank()
        val a = balloon(c, "ABCDEFG", 360f, 900f, "Where were you?")
        val memory = ItemMemory()
        memory.remember(page, listOf(a))
        var frame = page
        for (stop in 1..5) {
            val (next, _) = scrolled(frame, 120)
            val found = memory.recall(next)
            assertEquals("found at stop $stop", 1, found.size)
            memory.remember(next, found)
            frame = next
        }
        assertEquals("one memory for one line", 1, memory.size)
    }

    @Test
    fun aCaptionRunningToTheEdgeOfTheScreenIsFoundAgain() {
        val (page, c) = blank()
        // A full-width narration line: its box starts at the screen's left edge.
        val size = 26f
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ".forEachIndexed { i, ch -> glyph(c, ch, 2f + i * size, 800f, size - 4) }
        val caption = PageItem(Rect(0, 796, w, 826), ItemKind.NARRATION, "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "At that moment...")
        val memory = ItemMemory()
        memory.remember(page, listOf(caption))
        val found = memory.recall(scrolled(page, 300).first)
        assertEquals(1, found.size)
        assertTrue("moved up by the scroll (${found[0].box})", abs(found[0].box.top - 496) <= 4)
    }

    @Test
    fun letteringLongScrolledAwayIsForgotten() {
        val (page, c) = blank()
        val a = balloon(c, "ABCDEFG", 360f, 300f, "Where were you?")
        val memory = ItemMemory()
        memory.remember(page, listOf(a))
        // Scrolled clean off the screen, stop after stop.
        repeat(6) { memory.recall(scrolled(page, 1400).first) }
        assertEquals(0, memory.size)
    }

    @Test
    fun aNewBalloonInAnOldBalloonsPlaceIsNotTheOldBalloon() {
        val (page, c) = blank()
        val a = balloon(c, "ABCDEFG", 360f, 700f, "Where were you?")
        val memory = ItemMemory()
        memory.remember(page, listOf(a))

        // The old balloon has scrolled off; a different one now sits exactly where it was.
        val (next, nc) = scrolled(page, 1200)
        balloon(nc, "MNOPQRS", 360f, 700f, "I was waiting.")
        assertTrue(memory.recall(next).isEmpty())
    }

    @Test
    fun oneCharacterApartIsADifferentLine() {
        val (page, c) = blank()
        val a = balloon(c, "ABCDEFG", 360f, 700f, "Where were you?")
        val memory = ItemMemory()
        memory.remember(page, listOf(a))

        // Scrolled, and the same balloon now reads with one character changed.
        val (next, nc) = scrolled(page, 1200)
        balloon(nc, "ABCXEFG", 360f, 500f, "Something else.")
        assertTrue(memory.recall(next).isEmpty())
    }

    @Test
    fun closeNeighboursKeepTheirOwnLines() {
        val (page, c) = blank()
        // Two balloons drawn nearly touching, their lines one character apart.
        val a = balloon(c, "ABCDEFG", 250f, 800f, "Line for the left balloon.")
        val b = balloon(c, "ABCDEFH", 470f, 930f, "Line for the right balloon.")
        val memory = ItemMemory()
        memory.remember(page, listOf(a, b))

        val (next, _) = scrolled(page, 260)
        val found = memory.recall(next)
        assertEquals(2, found.size)
        val fa = found.single { it.en == a.en }
        val fb = found.single { it.en == b.en }
        assertTrue(abs(fa.box.top - (a.box.top - 260)) <= 4 && abs(fa.box.left - a.box.left) <= 4)
        assertTrue(abs(fb.box.top - (b.box.top - 260)) <= 4 && abs(fb.box.left - b.box.left) <= 4)
    }

    @Test
    fun aLineShownTwiceIsNeverGuessedAt() {
        val (page, c) = blank()
        val a = balloon(c, "ABCD", 360f, 500f, "Huh?")
        val memory = ItemMemory()
        memory.remember(page, listOf(a))

        // Two identical balloons in the same column: which one it was is
        // unknowable, so neither is repainted from memory.
        val (next, nc) = blank()
        balloon(nc, "ABCD", 360f, 400f, "")
        balloon(nc, "ABCD", 360f, 1000f, "")
        assertTrue(memory.recall(next).isEmpty())
    }

    @Test
    fun recallIsQuick() {
        val (page, c) = blank()
        val items = (0 until 12).map { i ->
            balloon(c, "ABCDEFGHIJKLMNOPQRSTUVWXYZ".substring(i, i + 6), 200f + (i % 2) * 300f, 120f + i * 115f, "line $i")
        }
        val memory = ItemMemory()
        memory.remember(page, items)
        val (next, _) = scrolled(page, 300)
        memory.recall(next)
        val t0 = System.nanoTime()
        val found = memory.recall(next)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("recall of ${items.size} remembered items: ${found.size} found in $ms ms")
        assertTrue(found.isNotEmpty())
        assertTrue("recall took $ms ms", ms < 400)
    }

    @Test
    fun aColumnIsFoundAgainWhateverSliverItsBoxLeavesAtTheEdge() {
        // A narrow column's box rarely spans whole glyph-sized tiles: the
        // last tile can be a column of cells or two holding only a stroke's
        // anti-aliased rim, which the recall's threshold tips either way on
        // pixels that did not change. Such a sliver lost the line, and the
        // stop fell back to reading the whole screen.
        val thin = Paint(ink).apply { strokeWidth = 2.5f }
        val (page, c) = blank()
        val items = ArrayList<PageItem>()
        var n = 0
        for (row in 0 until 4) {
            for (col in 0 until 6) {
                val x = 40f + col * 110f
                val y = 400f + row * 300f
                val size = 22f
                val glyphs = 3 + n % 3
                for (i in 0 until glyphs) {
                    val rnd = Random((n * 31 + i) * 7919)
                    val top = y + i * (size + 4)
                    repeat(4) {
                        c.drawLine(
                            x + rnd.nextFloat() * size, top + rnd.nextFloat() * size,
                            x + rnd.nextFloat() * size, top + rnd.nextFloat() * size, thin,
                        )
                    }
                }
                // Tight on the strokes, give or take a pixel or three.
                val box = Rect(x.toInt() - 2, y.toInt() - 2, (x + size).toInt() + 1 + n % 4, (y + glyphs * (size + 4)).toInt())
                items += PageItem(box, ItemKind.SPEECH, "column $n", "line $n", vertical = true)
                n++
            }
        }
        val memory = ItemMemory()
        memory.remember(page, items)
        val found = memory.recall(scrolled(page, 302).first)
        val lost = items.filter { item -> found.none { it.en == item.en } }
        assertEquals("columns lost: ${lost.map { it.box.toShortString() }}", emptyList<PageItem>(), lost)
        for (item in items) {
            val f = found.single { it.en == item.en }
            assertTrue("${item.en} moved up by the scroll", abs(f.box.top - (item.box.top - 302)) <= 2)
        }
    }

    @Test
    fun aLoneEllipsisIsFoundAgain() {
        // A balloon holding only "……": a column of dots a few pixels wide,
        // too thin to fingerprint on its own. It was never remembered, and
        // a strip stop below it read the whole screen again for it.
        val (page, c) = blank()
        val oval = RectF(300f, 600f, 400f, 800f)
        c.drawOval(oval, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        c.drawOval(oval, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.BLACK })
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        for (i in 0 until 6) c.drawCircle(350f, 650f + i * 18f, 3f, dot)
        val item = PageItem(Rect(346, 646, 354, 744), ItemKind.SPEECH, "……", "...", vertical = true)
        val memory = ItemMemory()
        memory.remember(page, listOf(item))
        assertEquals(1, memory.size)
        val found = memory.recall(scrolled(page, 331).first)
        assertEquals(listOf(item.en), found.map { it.en })
        val f = found.single()
        assertTrue("moved up by the scroll: ${f.box}", abs(f.box.top - (item.box.top - 331)) <= 2)
        assertEquals("the line's own box, not the paper round it", item.box.width(), f.box.width())
    }
}
