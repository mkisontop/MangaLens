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
    fun `without balloons the two panels are only told apart by their gaps`() {
        // Proximity welds the six columns into one group. The wide-gap cut
        // then separates them, but it can only see spacing, not which
        // columns share a balloon: here the right balloon's own columns are
        // spaced unevenly (30 px, then 80 px), and the 80 px gap is cut as
        // well. Balloons are what make the boundary exact; this pins what
        // the page looks like without them.
        val bubbles = group(emptyList())
        assertTrue(
            "the two balloons' lines must not share a region: ${bubbles.map { it.text }}",
            bubbles.none { it.text.contains("夕が") && it.text.contains("いい") },
        )
        assertEquals("the uneven right balloon is cut too", 3, bubbles.size)
    }

    @Test
    fun `two blocks of lettering on open art a column apart are two regions`() {
        // A six-column monologue on the art with a two-column aside beside
        // it, the gap between them one glyph wide: proximity reaches across
        // (the padding is a column and a half), the gap cut separates them.
        val lines = ArrayList<OcrLine>()
        for (k in 0 until 6) lines += column("這是文字", 700 - k * 55)
        lines += column("這還", 340)
        lines += column("完成", 285)
        val bubbles = BubbleGrouper.group(lines, 800, 0, 0, SourceLang.ZH)
        assertEquals("monologue and aside, got ${bubbles.map { it.text }}", 2, bubbles.size)
        assertEquals("the monologue keeps its six columns", 6, bubbles[0].lines.size)
        assertEquals("the aside keeps its two", 2, bubbles[1].lines.size)
    }

    @Test
    fun `a wide gap inside a balloon is not cut`() {
        // Columns spaced 15, 15 and 60 px apart inside one balloon: the
        // balloon says they are one utterance, whatever the spacing.
        val balloon = Rect(300, 80, 760, 420)
        val lines = listOf(column("一二", 700), column("三四", 645), column("五六", 590), column("七八", 490))
        val bubbles = BubbleGrouper.group(lines, 800, 0, 0, SourceLang.JA, balloons = listOf(balloon))
        assertEquals("one balloon, one region: ${bubbles.map { it.text }}", 1, bubbles.size)
        assertEquals("一二三四五六七八", bubbles[0].text)
    }

    @Test
    fun `a paragraph gap under one em holds a block together`() {
        val lines = listOf(column("一二", 700), column("三四", 650), column("五六", 600), column("七八", 530), column("九十", 480))
        // Gaps of 10, 10, 30, 10 px on 40 px columns: the 30 px gap is under
        // 0.9 em and under 2.5 times the typical gap only just — a paragraph
        // break, not a second block.
        val bubbles = BubbleGrouper.group(lines, 800, 0, 0, SourceLang.JA)
        assertEquals("one block, got ${bubbles.map { it.text }}", 1, bubbles.size)
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

    /** One square box per glyph, as ML Kit returns large vertical lettering. */
    private fun glyph(ch: String, left: Int, top: Int, em: Int = 34) =
        OcrLine(ch, Rect(left, top, left + em, top + em), false)

    @Test
    fun `per-glyph boxes stacked in columns are read as vertical text, right column first`() {
        // Two columns of four glyphs each. Every box is square, so no line
        // calls itself vertical; only the arrangement says so. Read as rows
        // the text comes out interleaved and left-to-right: かあきい…
        val lines = ArrayList<OcrLine>()
        val right = "あいうえ"
        val left = "かきくけ"
        for ((i, ch) in right.withIndex()) lines += glyph(ch.toString(), 300, 100 + i * 40)
        for ((i, ch) in left.withIndex()) lines += glyph(ch.toString(), 250, 100 + i * 40)
        val bubbles = BubbleGrouper.group(lines, 800, 0, 0, SourceLang.JA)
        assertEquals("the glyphs form one region, got $bubbles", 1, bubbles.size)
        assertTrue("a stack of glyph boxes is vertical text", bubbles[0].vertical)
        assertEquals("columns read right to left, top to bottom", "あいうえかきくけ", bubbles[0].text)
        assertEquals("every glyph box is kept for the wipe", 8, bubbles[0].lines.size)
    }

    @Test
    fun `per-glyph boxes in a row stay horizontal`() {
        val lines = "안녕하세요".mapIndexed { i, ch -> glyph(ch.toString(), 100 + i * 38, 200) }
        val bubbles = BubbleGrouper.group(lines, 800, 0, 0, SourceLang.KO)
        assertEquals(1, bubbles.size)
        assertTrue("a row of glyph boxes is horizontal text", !bubbles[0].vertical)
        assertEquals("안 녕 하 세 요", bubbles[0].text)
    }

    @Test
    fun `single stray latin letters stay junk`() {
        val lines = listOf(OcrLine("W", Rect(700, 500, 740, 540), false))
        val bubbles = BubbleGrouper.group(lines, 800, 0, 0, SourceLang.AUTO)
        assertEquals("a lone letter is OCR noise, got $bubbles", 0, bubbles.size)
    }
}
