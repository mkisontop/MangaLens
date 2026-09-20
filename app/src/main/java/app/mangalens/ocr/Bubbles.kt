package app.mangalens.ocr

import android.graphics.Rect
import app.mangalens.settings.SourceLang
import kotlin.math.max
import kotlin.math.min

data class OcrLine(val text: String, val box: Rect, val vertical: Boolean)

enum class BubbleKind { DIALOGUE, SFX }

data class Bubble(
    val text: String,
    val box: Rect,
    val vertical: Boolean,
    val kind: BubbleKind = BubbleKind.DIALOGUE,
    /** Utterance this bubble is part of, or -1 when it stands alone. See [Utterance]. */
    val runId: Int = -1,
    /** 0-based position within [runId]. */
    val runPart: Int = 0,
    /**
     * The OCR line boxes this region was built from — one per column of
     * vertical lettering, one per row of horizontal — in page coordinates.
     * Empty for a balloon OCR read nothing in. Lettering that sits on open
     * art with no balloon around it is wiped line by line through these,
     * so the art between the columns is left alone.
     */
    val lines: List<Rect> = emptyList(),
)

object Script {
    fun isHangul(c: Char): Boolean {
        val code = c.code
        return code in 0xAC00..0xD7A3 || code in 0x1100..0x11FF || code in 0x3130..0x318F
    }

    fun isKana(c: Char): Boolean {
        val code = c.code
        return code in 0x3040..0x30FF || code in 0x31F0..0x31FF || code in 0xFF66..0xFF9D
    }

    fun isKatakana(c: Char): Boolean {
        val code = c.code
        return code in 0x30A0..0x30FF || code in 0x31F0..0x31FF || code in 0xFF66..0xFF9D
    }

    fun isHan(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF
    }

    fun isCjk(c: Char) = isHangul(c) || isKana(c) || isHan(c)

    fun cjkCount(s: String) = s.count { isCjk(it) }
    fun hangulCount(s: String) = s.count { isHangul(it) }
    fun kanaCount(s: String) = s.count { isKana(it) }
    fun hanCount(s: String) = s.count { isHan(it) }
    fun katakanaCount(s: String) = s.count { isKatakana(it) }

    // OCR noise: bubble borders and screentones come back as pipes, slashes,
    // box-drawing runs, etc. U+4E28 (丨) is the CJK stroke ML Kit favors for
    // vertical bubble edges and essentially never appears in real dialogue.
    private val NOISE = Regex("[|｜丨/\\\\_`~^=<>*#{}\\[\\]\\u2500-\\u257F\\u2028\\u2029]+")

    fun clean(s: String): String = s
        .replace(NOISE, "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

/**
 * Groups raw OCR lines into speech bubbles by spatial proximity (union-find over
 * direction-aware padded boxes), reads vertical CJK columns right-to-left, drops
 * furigana and border-noise crumbs, and classifies oversized katakana bursts as
 * sound effects so they are never word-for-word translated.
 */
object BubbleGrouper {

    /**
     * @param balloons balloon regions detected from the page pixels. Where
     *   these disagree with the clustering below, they win — see [BalloonMerge].
     * @param includeEmptyBalloons report balloons holding no readable text.
     *   Only for engines that can read the image themselves.
     * @param panels the page's panel grid, when the pixels showed one; it
     *   decides reading order where balloon geometry alone cannot.
     */
    fun group(
        lines: List<OcrLine>,
        screenH: Int,
        ignoreTopPx: Int,
        ignoreBottomPx: Int,
        lang: SourceLang,
        exclusions: List<Rect> = emptyList(),
        balloons: List<Rect> = emptyList(),
        includeEmptyBalloons: Boolean = false,
        panels: List<Rect> = emptyList(),
    ): List<Bubble> {
        val usable = lines.mapNotNull { l ->
            val cleaned = Script.clean(l.text)
            if (cleaned.isEmpty()) return@mapNotNull null
            // CJK is the expected script, but raws that were already
            // translated once (Spanish, English) read as Latin — real
            // dialogue with real geometry, not noise to discard.
            if (Script.cjkCount(cleaned) == 0 && cleaned.count { it.isLetter() } < 2) {
                return@mapNotNull null
            }
            if (l.box.height() < 11) return@mapNotNull null
            if (l.box.bottom <= ignoreTopPx || l.box.top >= screenH - ignoreBottomPx) return@mapNotNull null
            if (exclusions.any { Rect.intersects(it, l.box) }) return@mapNotNull null
            OcrLine(cleaned, l.box, l.vertical)
        }
        if (usable.isEmpty()) {
            // OCR read nothing, but the page may still plainly show balloons —
            // vertical lettering it cannot resolve at all is the usual reason,
            // and that is precisely when handing them to a vision model helps.
            return if (includeEmptyBalloons && balloons.isNotEmpty()) {
                finish(BalloonMerge.apply(emptyList(), balloons, lang, includeEmpty = true), lang, panels)
            } else {
                emptyList()
            }
        }

        // Character size ("stroke") per line: width of a vertical column, height
        // of a horizontal line. Median across the page anchors SFX detection.
        fun stroke(l: OcrLine) = if (l.vertical) l.box.width() else l.box.height()
        val medianStroke = usable.map { stroke(it) }.sorted()[usable.size / 2].coerceAtLeast(8)

        val n = usable.size
        val parent = IntArray(n) { it }

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) {
                parent[r] = parent[parent[r]]
                r = parent[r]
            }
            return r
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        // Direction-aware padding: vertical columns sit side by side with a gap
        // close to a full character width; horizontal lines (webtoon bubbles)
        // stack with line spacing up to ~1.5x the glyph height. Pad more
        // across the reading axis than along it.
        val padded = usable.map { l ->
            val s = stroke(l).coerceAtLeast(8)
            val padX = if (l.vertical) (s * 1.5f).toInt() else (s * 0.85f).toInt()
            val padY = if (l.vertical) (s * 0.7f).toInt() else (s * 1.3f).toInt()
            Rect(l.box.left - padX, l.box.top - padY, l.box.right + padX, l.box.bottom + padY)
        }
        // Balloons are hard boundaries. Padding scales with the lettering, so
        // on large vertical text it reaches far enough to cross a panel gutter
        // and weld one balloon's columns to the next balloon's — two speakers,
        // two panels, one card straddling the border between them. Proximity
        // cannot tell that apart on its own, and [BalloonMerge] runs later and
        // can only join regions, never separate them.
        val balloonOf = IntArray(n) { balloonIndexOf(usable[it].box, balloons) }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (balloonOf[i] != balloonOf[j]) continue
                if (Rect.intersects(padded[i], padded[j])) union(i, j)
            }
        }

        val groups = HashMap<Int, MutableList<OcrLine>>()
        for (i in 0 until n) groups.getOrPut(find(i)) { mutableListOf() }.add(usable[i])

        // Outside every balloon, proximity is the only boundary there is,
        // and it reaches further than a block of lettering does: two
        // monologues set side by side on the art, a column's width apart,
        // weld into one region and come back as one paragraph. A block's
        // own lines are evenly spaced, so a gap far wider than the others
        // is the space between two blocks, and the group is cut there.
        val blocks = ArrayList<MutableList<OcrLine>>(groups.size)
        for ((root, members) in groups) {
            if (balloonOf[root] >= 0) blocks.add(members) else blocks.addAll(splitAtWideGaps(members))
        }

        val bubbles = blocks.mapNotNull { raw ->
            buildBubble(raw, lang, medianStroke)
        }
        // Reconcile against the balloons actually on the page before anything
        // downstream treats a region as an utterance.
        return finish(BalloonMerge.apply(bubbles, balloons, lang, includeEmptyBalloons), lang, panels)
    }

    /**
     * Index of the balloon holding most of [box], or -1 when it belongs to no
     * balloon — sound effects on the art, captions, narration boxes. Lines
     * outside every balloon share the -1 group and still cluster by proximity
     * as before.
     */
    private fun balloonIndexOf(box: Rect, balloons: List<Rect>): Int {
        if (balloons.isEmpty()) return -1
        val area = box.width().toLong() * box.height()
        if (area <= 0L) return -1
        var best = -1
        var bestShare = 0.5f
        for ((i, balloon) in balloons.withIndex()) {
            val ix = min(box.right, balloon.right) - max(box.left, balloon.left)
            val iy = min(box.bottom, balloon.bottom) - max(box.top, balloon.top)
            if (ix <= 0 || iy <= 0) continue
            val share = (ix.toLong() * iy).toFloat() / area
            if (share > bestShare) {
                bestShare = share
                best = i
            }
        }
        return best
    }

    /**
     * Panel-aware order, then link the bubbles that share one sentence. Both
     * feed the translator directly: order is what "reading order" means in the
     * prompt, and the links are what let it resolve a clause whose subject
     * lives in the previous balloon.
     */
    private fun finish(bubbles: List<Bubble>, lang: SourceLang, panels: List<Rect>): List<Bubble> {
        val ordered = ReadingOrder.order(bubbles, ReadingOrder.isRightToLeft(bubbles, lang), panels)
        return Utterance.link(ordered, lang)
    }

    private fun buildBubble(raw: MutableList<OcrLine>, lang: SourceLang, pageStroke: Int): Bubble? {
        var members: List<OcrLine> = raw
        // A line's own aspect says whether it is a column or a row — unless
        // the recognizer handed back one box per glyph, as it does on large
        // vertical lettering, when every box is square and says nothing.
        // The arrangement of the boxes still does.
        val vertical = arrangedVertically(members) ?: (members.count { it.vertical } * 2 > members.size)

        // Furigana: narrow ruby columns hugging the main columns of a vertical
        // bubble. They duplicate readings and wreck the joined text — drop any
        // vertical member far narrower than the bubble's typical column.
        if (vertical && members.size >= 3) {
            val widths = members.filter { it.vertical }.map { it.box.width() }.sorted()
            if (widths.size >= 2) {
                val medianW = widths[widths.size / 2]
                val filtered = members.filter { !it.vertical || it.box.width() >= medianW * 0.62f }
                if (filtered.isNotEmpty()) members = filtered
            }
        }

        val sorted = if (vertical) sortVertical(members) else sortHorizontal(members)
        val cjkTotal = sorted.sumOf { Script.cjkCount(it.text) }
        // Latin words need the spaces CJK does without; KO keeps them too.
        val sep = if (lang == SourceLang.KO || cjkTotal == 0) " " else ""
        val text = sorted.joinToString(sep) { it.text.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
        val cjk = Script.cjkCount(text)
        if (cjk == 0 && text.count { it.isLetter() } < 2) return null

        val union = Rect(sorted[0].box)
        for (m in sorted.drop(1)) union.union(m.box)
        val groupStroke = sorted.maxOf { if (it.vertical) it.box.width() else it.box.height() }

        // Single stray han characters are almost always OCR crumbs picked off
        // sound-effect art (死 → "death"). Lone kana/hangul interjections
        // (え!? / 아…) are real dialogue and stay.
        if (cjk == 1) {
            val ch = text.first { Script.isCjk(it) }
            if (Script.isHan(ch) && lang != SourceLang.ZH) return null
            if (Script.isHan(ch) && lang == SourceLang.ZH && groupStroke < pageStroke * 1.2f) return null
        }

        // SFX: dramatically oversized glyphs, or a short katakana burst drawn
        // well above dialogue size. Tag instead of dropping — engines decide
        // whether to stylize (THUD) or leave the art alone. CJK only: outsized
        // Latin lettering is a title or a shout, not onomatopoeia to caption.
        val katakanaRatio = if (cjk > 0) Script.katakanaCount(text).toFloat() / cjk else 0f
        val sfx = cjk > 0 && (
            (groupStroke > pageStroke * 2.1f && cjk <= 8) ||
                (katakanaRatio >= 0.8f && cjk <= 4 && groupStroke > pageStroke * 1.45f)
            )

        // Every member's box, furigana included: the ruby is lettering to
        // wipe even though its reading was dropped from the text.
        val lines = raw.map { Rect(it.box) }
        return Bubble(text, union, vertical, if (sfx) BubbleKind.SFX else BubbleKind.DIALOGUE, lines = lines)
    }

    /** A gap wider than this many em is never inside one block, whatever its other gaps. */
    private const val BLOCK_GAP_EM = 0.9f

    /** A gap this many times the block's typical gap separates two blocks. */
    private const val BLOCK_GAP_RATIO = 2.5f

    /**
     * Cuts a group of lettering that sits outside every balloon at any gap
     * far wider than the gaps between its other lines: wider than
     * [BLOCK_GAP_RATIO] times the typical gap, and at least [BLOCK_GAP_EM]
     * of the lettering's size. Columns are walked right to left and rows
     * top to bottom, the axis blocks separate along; a group of one or two
     * lines has nothing to compare and stays whole.
     */
    private fun splitAtWideGaps(members: MutableList<OcrLine>): List<MutableList<OcrLine>> {
        if (members.size < 3) return listOf(members)
        val vertical = arrangedVertically(members) ?: (members.count { it.vertical } * 2 > members.size)
        val em = members.map { if (vertical) it.box.width() else it.box.height() }.sorted()
            .let { it[it.size / 2] }.coerceAtLeast(8)
        val ordered = if (vertical) members.sortedByDescending { it.box.centerX() } else members.sortedBy { it.box.centerY() }
        // The gap before each line, measured from the reach of everything
        // before it, so a wide line never hides a gap behind a narrow one.
        val gaps = IntArray(ordered.size)
        var reach = if (vertical) ordered[0].box.left else ordered[0].box.bottom
        for (i in 1 until ordered.size) {
            val b = ordered[i].box
            gaps[i] = if (vertical) max(0, reach - b.right) else max(0, b.top - reach)
            reach = if (vertical) min(reach, b.left) else max(reach, b.bottom)
        }
        val typical = gaps.copyOfRange(1, gaps.size).sorted().let { it[(it.size - 1) / 2] }
        val threshold = max(em * BLOCK_GAP_EM, typical * BLOCK_GAP_RATIO)
        val out = ArrayList<MutableList<OcrLine>>()
        var current = mutableListOf(ordered[0])
        for (i in 1 until ordered.size) {
            if (gaps[i] > threshold) {
                out.add(current)
                current = mutableListOf()
            }
            current.add(ordered[i])
        }
        out.add(current)
        return out
    }

    /**
     * Whether a group of per-glyph boxes stacks into columns (true) or runs
     * in rows (false), or null when the boxes are ordinary lines that speak
     * for themselves. Square, one- or two-character boxes are glyphs; when
     * most of a group is glyphs, the group runs the way that needs fewer
     * lines: three glyphs stacked over one another are one column, three
     * side by side are one row. Read as rows, a column of glyphs would
     * come out one glyph per row and, with two columns, interleaved and
     * left-to-right — the mirror image of how the page reads.
     */
    private fun arrangedVertically(members: List<OcrLine>): Boolean? {
        val glyphs = members.filter { l ->
            val h = l.box.height().coerceAtLeast(1)
            val aspect = l.box.width().toFloat() / h
            aspect in 0.55f..1.8f && l.text.trim().length <= 2
        }
        if (glyphs.size < 3 || glyphs.size * 3 < members.size * 2) return null
        val columns = clusters(glyphs) { a, b -> min(a.right, b.right) - max(a.left, b.left) > 0.35f * min(a.width(), b.width()) }
        val rows = clusters(glyphs) { a, b -> min(a.bottom, b.bottom) - max(a.top, b.top) > 0.35f * min(a.height(), b.height()) }
        return when {
            columns < rows -> true
            rows < columns -> false
            else -> null
        }
    }

    /** Number of groups [boxes] fall into when [together] links two boxes (single-linkage). */
    private fun clusters(boxes: List<OcrLine>, together: (Rect, Rect) -> Boolean): Int {
        val n = boxes.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            return r
        }
        for (i in 0 until n) for (j in i + 1 until n) {
            if (together(boxes[i].box, boxes[j].box)) {
                val a = find(i)
                val b = find(j)
                if (a != b) parent[b] = a
            }
        }
        return (0 until n).count { find(it) == it }
    }

    /** Columns right-to-left, characters top-to-bottom within a column. */
    private fun sortVertical(members: List<OcrLine>): List<OcrLine> {
        data class Column(var left: Int, var right: Int, val lines: MutableList<OcrLine>)

        val columns = ArrayList<Column>()
        for (l in members.sortedByDescending { it.box.centerX() }) {
            val col = columns.firstOrNull { c ->
                val overlap = min(c.right, l.box.right) - max(c.left, l.box.left)
                overlap > 0.35f * min(c.right - c.left, l.box.width())
            }
            if (col != null) {
                col.left = min(col.left, l.box.left)
                col.right = max(col.right, l.box.right)
                col.lines.add(l)
            } else {
                columns.add(Column(l.box.left, l.box.right, mutableListOf(l)))
            }
        }
        columns.sortByDescending { (it.left + it.right) / 2 }
        return columns.flatMap { c -> c.lines.sortedBy { it.box.top } }
    }

    /** Rows top-to-bottom, text left-to-right within a row. */
    private fun sortHorizontal(members: List<OcrLine>): List<OcrLine> {
        data class Row(var top: Int, var bottom: Int, val lines: MutableList<OcrLine>)

        val rows = ArrayList<Row>()
        for (l in members.sortedBy { it.box.centerY() }) {
            val row = rows.firstOrNull { r ->
                val overlap = min(r.bottom, l.box.bottom) - max(r.top, l.box.top)
                overlap > 0.4f * min(r.bottom - r.top, l.box.height())
            }
            if (row != null) {
                row.top = min(row.top, l.box.top)
                row.bottom = max(row.bottom, l.box.bottom)
                row.lines.add(l)
            } else {
                rows.add(Row(l.box.top, l.box.bottom, mutableListOf(l)))
            }
        }
        rows.sortBy { (it.top + it.bottom) / 2 }
        return rows.flatMap { r -> r.lines.sortedBy { it.box.left } }
    }
}
