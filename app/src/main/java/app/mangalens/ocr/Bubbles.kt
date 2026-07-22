package app.mangalens.ocr

import android.graphics.Rect
import app.mangalens.settings.SourceLang

data class OcrLine(val text: String, val box: Rect, val vertical: Boolean)

data class Bubble(
    val text: String,
    val box: Rect,
    val vertical: Boolean,
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

    fun isHan(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF
    }

    fun isCjk(c: Char) = isHangul(c) || isKana(c) || isHan(c)

    fun cjkCount(s: String) = s.count { isCjk(it) }
    fun hangulCount(s: String) = s.count { isHangul(it) }
    fun kanaCount(s: String) = s.count { isKana(it) }
    fun hanCount(s: String) = s.count { isHan(it) }
}

/**
 * Groups raw OCR lines into speech bubbles by spatial proximity (union-find over
 * padded bounding boxes), then joins each group's lines in natural reading order.
 */
object BubbleGrouper {

    fun group(
        lines: List<OcrLine>,
        screenH: Int,
        ignoreTopPx: Int,
        ignoreBottomPx: Int,
        lang: SourceLang,
    ): List<Bubble> {
        val usable = lines.filter { l ->
            Script.cjkCount(l.text) > 0 &&
                l.box.height() >= 9 &&
                l.box.bottom > ignoreTopPx &&
                l.box.top < screenH - ignoreBottomPx
        }
        if (usable.isEmpty()) return emptyList()

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

        val padded = usable.map { l ->
            val stroke = minOf(l.box.width(), l.box.height()).coerceAtLeast(8)
            val pad = (stroke * 0.75f).toInt()
            Rect(l.box.left - pad, l.box.top - pad, l.box.right + pad, l.box.bottom + pad)
        }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (Rect.intersects(padded[i], padded[j])) union(i, j)
            }
        }

        val groups = HashMap<Int, MutableList<OcrLine>>()
        for (i in 0 until n) groups.getOrPut(find(i)) { mutableListOf() }.add(usable[i])

        val bubbles = groups.values.map { members ->
            val vertical = members.count { it.vertical } * 2 > members.size
            val sorted = if (vertical) {
                // vertical CJK text reads column by column, right to left
                members.sortedWith(compareByDescending<OcrLine> { it.box.centerX() }.thenBy { it.box.top })
            } else {
                members.sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left })
            }
            val sep = if (lang == SourceLang.KO) " " else ""
            val text = sorted.joinToString(sep) { it.text.trim() }
                .replace(Regex("\\s+"), " ")
                .trim()
            val union = Rect(sorted[0].box)
            for (m in sorted.drop(1)) union.union(m.box)
            Bubble(text, union, vertical)
        }
        return bubbles.sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }
}
