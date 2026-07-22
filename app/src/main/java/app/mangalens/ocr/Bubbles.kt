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

    fun group(
        lines: List<OcrLine>,
        screenH: Int,
        ignoreTopPx: Int,
        ignoreBottomPx: Int,
        lang: SourceLang,
        exclusions: List<Rect> = emptyList(),
    ): List<Bubble> {
        val usable = lines.mapNotNull { l ->
            val cleaned = Script.clean(l.text)
            if (cleaned.isEmpty() || Script.cjkCount(cleaned) == 0) return@mapNotNull null
            if (l.box.height() < 11) return@mapNotNull null
            if (l.box.bottom <= ignoreTopPx || l.box.top >= screenH - ignoreBottomPx) return@mapNotNull null
            if (exclusions.any { Rect.intersects(it, l.box) }) return@mapNotNull null
            OcrLine(cleaned, l.box, l.vertical)
        }
        if (usable.isEmpty()) return emptyList()

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
        // close to a full character width, horizontal lines stack with generous
        // line spacing. Pad more across the reading axis than along it.
        val padded = usable.map { l ->
            val s = stroke(l).coerceAtLeast(8)
            val padX = if (l.vertical) (s * 1.5f).toInt() else (s * 0.7f).toInt()
            val padY = if (l.vertical) (s * 0.7f).toInt() else (s * 1.1f).toInt()
            Rect(l.box.left - padX, l.box.top - padY, l.box.right + padX, l.box.bottom + padY)
        }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (Rect.intersects(padded[i], padded[j])) union(i, j)
            }
        }

        val groups = HashMap<Int, MutableList<OcrLine>>()
        for (i in 0 until n) groups.getOrPut(find(i)) { mutableListOf() }.add(usable[i])

        val bubbles = groups.values.mapNotNull { raw ->
            buildBubble(raw, lang, medianStroke)
        }
        return bubbles.sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }

    private fun buildBubble(raw: MutableList<OcrLine>, lang: SourceLang, pageStroke: Int): Bubble? {
        var members: List<OcrLine> = raw
        val vertical = members.count { it.vertical } * 2 > members.size

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
        val sep = if (lang == SourceLang.KO) " " else ""
        val text = sorted.joinToString(sep) { it.text.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
        val cjk = Script.cjkCount(text)
        if (cjk == 0) return null

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
        // whether to stylize (THUD) or leave the art alone.
        val katakanaRatio = if (cjk > 0) Script.katakanaCount(text).toFloat() / cjk else 0f
        val sfx = (groupStroke > pageStroke * 2.1f && cjk <= 8) ||
            (katakanaRatio >= 0.8f && cjk <= 4 && groupStroke > pageStroke * 1.45f)

        return Bubble(text, union, vertical, if (sfx) BubbleKind.SFX else BubbleKind.DIALOGUE)
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
