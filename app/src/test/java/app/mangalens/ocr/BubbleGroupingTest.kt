package app.mangalens.ocr

import android.graphics.Rect
import app.mangalens.settings.SourceLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two balloons in adjacent panels, close enough that proximity clustering
 * reaches across the gutter between them.
 *
 * Clustering pads each line by a multiple of its own lettering, so the larger
 * the text the further it reaches — and on a page set in big vertical columns
 * that reach clears a panel border. The result is one region holding two
 * characters' lines from two different panels, rendered as a single card lying
 * across the border. Nothing downstream can undo it: the balloon reconciliation
 * that follows joins regions, it never separates them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleGroupingTest {

    /** A column of vertical lettering, 40px wide — the width drives padding. */
    private fun column(text: String, left: Int, top: Int = 140) =
        OcrLine(text, Rect(left, top, left + 40, top + 200), true)

    /** Right-hand balloon, then left, as manga reads them. */
    private val rightBalloon = Rect(400, 100, 700, 400)
    private val leftBalloon = Rect(60, 100, 360, 400)

    private val lines = listOf(
        // Right balloon: columns run right to left.
        column("夕が", 620),
        column("不満", 550),
        column("言わ", 430),
        // Left balloon, in the next panel over. Its rightmost column sits 90px
        // from the right balloon's leftmost — inside the 60px padding each
        // side gets, so the two reach each other.
        column("いい", 300),
        column("心配", 230),
        column("してる", 160),
    )

    private fun group(balloons: List<Rect>) = BubbleGrouper.group(
        lines,
        screenH = 800,
        ignoreTopPx = 0,
        ignoreBottomPx = 0,
        lang = SourceLang.JA,
        balloons = balloons,
    )

    @Test
    fun `balloons in adjacent panels are not welded together`() {
        val bubbles = group(listOf(rightBalloon, leftBalloon))

        assertEquals(
            "expected one region per balloon, got ${bubbles.map { it.text }}",
            2,
            bubbles.size,
        )
        assertTrue(
            "each balloon keeps its own line, got ${bubbles.map { it.text }}",
            bubbles.any { it.text == "夕が不満言わ" } && bubbles.any { it.text == "いい心配してる" },
        )
        // Manga order: the right-hand panel is read first.
        assertEquals("夕が不満言わ", bubbles.first().text)
    }

    @Test
    fun `columns within one balloon still join`() {
        val bubbles = group(listOf(rightBalloon, leftBalloon))
        // Three columns each, welded right-to-left into one line apiece — the
        // boundary must not cost us the merging that made it worth detecting
        // balloons in the first place.
        assertTrue(
            "columns should still weld inside a balloon, got ${bubbles.map { it.text }}",
            bubbles.all { it.text.length > 4 },
        )
    }

    @Test
    fun `without balloons the two panels do merge, which is the defect`() {
        // Pins the behaviour the boundary exists to prevent, so the fixture
        // cannot quietly stop reproducing it.
        val bubbles = group(emptyList())
        assertEquals(
            "with no balloons to bound them, proximity welds the panels: " +
                "${bubbles.map { it.text }}",
            1,
            bubbles.size,
        )
    }

    @Test
    fun `text outside every balloon still clusters by proximity`() {
        // Sound effects and captions live outside balloons and share the "no
        // balloon" group; they must keep clustering as they always did.
        val sfx = listOf(
            OcrLine("ゴゴ", Rect(800, 500, 850, 620), true),
            OcrLine("ゴゴ", Rect(860, 500, 910, 620), true),
        )
        val bubbles = BubbleGrouper.group(
            sfx, 800, 0, 0, SourceLang.JA, balloons = listOf(rightBalloon, leftBalloon),
        )
        assertEquals("free-floating text should still group, got $bubbles", 1, bubbles.size)
    }

    /** A horizontal Latin line, as OCR reads Spanish or English raws. */
    private fun latinRow(text: String, top: Int, left: Int = 120, width: Int = 260) =
        OcrLine(text, Rect(left, top, left + width, top + 34), false)

    @Test
    fun `latin raws form regions instead of being discarded`() {
        // Aggregator sites often serve raws already translated once. Those
        // lines are real dialogue with real geometry; dropping them for
        // carrying no CJK left such pages with nothing to anchor cards to.
        val lines = listOf(
            latinRow("PARECE COMO SI", 140),
            latinRow("TUVIERAS DOLOR", 184),
            latinRow("DE CUERPO...", 228),
        )
        val balloon = Rect(80, 100, 460, 300)
        val bubbles = BubbleGrouper.group(
            lines, 800, 0, 0, SourceLang.AUTO, balloons = listOf(balloon),
        )
        assertEquals("the three rows should form one region, got $bubbles", 1, bubbles.size)
        assertEquals(
            "rows join top-to-bottom with spaces between words",
            "PARECE COMO SI TUVIERAS DOLOR DE CUERPO...",
            bubbles[0].text,
        )
        assertEquals(
            "latin dialogue is dialogue, not sound effects",
            BubbleKind.DIALOGUE,
            bubbles[0].kind,
        )
    }

    @Test
    fun `single stray latin letters stay junk`() {
        val lines = listOf(OcrLine("W", Rect(700, 500, 740, 540), false))
        val bubbles = BubbleGrouper.group(lines, 800, 0, 0, SourceLang.AUTO)
        assertEquals("a lone letter is OCR noise, got $bubbles", 0, bubbles.size)
    }
}
