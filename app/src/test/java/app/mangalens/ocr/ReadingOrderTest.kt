package app.mangalens.ocr

import android.graphics.Rect
import app.mangalens.settings.SourceLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reading order is invisible on screen — every overlay still lands on its own
 * balloon whichever order they were translated in — but it decides the order
 * the translator is told the page runs in, so a regression here degrades
 * meaning without leaving a visible trace. These cases pin the layouts that
 * distinguish a real page from a naive top-to-bottom sweep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadingOrderTest {

    private fun bubble(name: String, l: Int, t: Int, r: Int, b: Int, vertical: Boolean = true) =
        Bubble(name, Rect(l, t, r, b), vertical)

    private fun order(bubbles: List<Bubble>, rtl: Boolean) =
        ReadingOrder.order(bubbles, rtl).map { it.text }

    @Test
    fun `manga tier reads the right panel first even when the left sits higher`() {
        // The exact case a (top, left) sort gets backwards: the left balloon is
        // higher on the page, so a top-first sweep reads it first, but a manga
        // reader takes the right panel.
        val left = bubble("left", 100, 100, 400, 200)
        val right = bubble("right", 600, 150, 900, 250)

        assertEquals(listOf("right", "left"), order(listOf(left, right), rtl = true))
    }

    @Test
    fun `manga grid reads tier by tier, right to left within each tier`() {
        val topLeft = bubble("TL", 100, 100, 400, 200)
        val topRight = bubble("TR", 600, 100, 900, 200)
        val bottomLeft = bubble("BL", 100, 400, 400, 500)
        val bottomRight = bubble("BR", 600, 400, 900, 500)

        // Tiers must win over columns here. Splitting into columns first would
        // read TR, BR, TL, BL — the entire right side of the page before
        // coming back for the left.
        assertEquals(
            listOf("TR", "TL", "BR", "BL"),
            order(listOf(topLeft, topRight, bottomLeft, bottomRight), rtl = true),
        )
    }

    @Test
    fun `a full-height panel down the right edge is read before the stack beside it`() {
        // No horizontal gutter crosses this page, so a flat tier sweep cannot
        // order it at all; the vertical cut has to take the tall panel first.
        val tall = bubble("tall", 600, 100, 900, 500)
        val upper = bubble("upper", 100, 100, 400, 200)
        val lower = bubble("lower", 100, 300, 400, 400)

        assertEquals(
            listOf("tall", "upper", "lower"),
            order(listOf(upper, lower, tall), rtl = true),
        )
    }

    @Test
    fun `webtoon reads straight down, left to right within a row`() {
        val first = bubble("first", 100, 100, 400, 200, vertical = false)
        val second = bubble("second", 600, 120, 900, 220, vertical = false)
        val third = bubble("third", 100, 400, 400, 500, vertical = false)

        assertEquals(
            listOf("first", "second", "third"),
            order(listOf(third, second, first), rtl = false),
        )
    }

    @Test
    fun `bubbles stacked inside one panel keep their top-to-bottom order`() {
        val top = bubble("top", 100, 100, 400, 200)
        val middle = bubble("middle", 100, 260, 400, 360)
        val bottom = bubble("bottom", 100, 420, 400, 520)

        assertEquals(
            listOf("top", "middle", "bottom"),
            order(listOf(bottom, top, middle), rtl = true),
        )
    }

    @Test
    fun `a gap narrower than the text is not treated as a panel gutter`() {
        // Two balloons a few pixels apart, the left one higher. Were that
        // hairline gap taken for a gutter, the page would split into tiers and
        // read the left balloon first; it is line spacing, so the tier holds
        // together and manga order puts the right balloon first.
        val left = bubble("left", 100, 100, 400, 200)
        val right = bubble("right", 420, 106, 720, 206)

        assertEquals(listOf("right", "left"), order(listOf(left, right), rtl = true))
    }

    @Test
    fun `every bubble survives ordering`() {
        val bubbles = (0 until 12).map {
            bubble("b$it", (it % 3) * 300 + 50, (it / 3) * 250 + 50, (it % 3) * 300 + 250, (it / 3) * 250 + 180)
        }
        assertEquals(bubbles.size, ReadingOrder.order(bubbles, rtl = true).size)
        assertEquals(bubbles.map { it.text }.toSet(), ReadingOrder.order(bubbles, true).map { it.text }.toSet())
    }

    @Test
    fun `single and empty inputs are returned untouched`() {
        assertEquals(emptyList<Bubble>(), ReadingOrder.order(emptyList(), rtl = true))
        val one = listOf(bubble("only", 0, 0, 10, 10))
        assertEquals(listOf("only"), order(one, rtl = true))
    }

    @Test
    fun `vertical Japanese lettering marks a page as right-to-left`() {
        val bubbles = listOf(
            bubble("a", 100, 100, 200, 400, vertical = true),
            bubble("b", 300, 100, 400, 400, vertical = true),
        )
        assertTrue(ReadingOrder.isRightToLeft(bubbles, SourceLang.JA))
    }

    @Test
    fun `horizontal Japanese is a webtoon and stays left-to-right`() {
        val bubbles = listOf(
            bubble("a", 100, 100, 400, 200, vertical = false),
            bubble("b", 100, 300, 400, 400, vertical = false),
        )
        assertFalse(ReadingOrder.isRightToLeft(bubbles, SourceLang.JA))
    }

    @Test
    fun `Korean is never right-to-left, whatever the lettering looks like`() {
        val bubbles = listOf(bubble("a", 100, 100, 200, 400, vertical = true))
        assertFalse(ReadingOrder.isRightToLeft(bubbles, SourceLang.KO))
    }

    @Test
    fun `sound effects do not decide page direction`() {
        // A single vertical SFX burst on an otherwise horizontal webtoon page
        // must not flip the whole page into manga order.
        val bubbles = listOf(
            bubble("line", 100, 100, 400, 200, vertical = false),
            bubble("line2", 100, 300, 400, 400, vertical = false),
            Bubble("SFX", Rect(500, 100, 560, 400), true, BubbleKind.SFX),
        )
        assertFalse(ReadingOrder.isRightToLeft(bubbles, SourceLang.JA))
    }
}
