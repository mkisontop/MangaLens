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
}
