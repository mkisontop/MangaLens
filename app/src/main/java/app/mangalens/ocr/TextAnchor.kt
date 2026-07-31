package app.mangalens.ocr

import android.graphics.Rect
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Pins a piece of source text to where it physically sits on the page.
 *
 * The vision model's unanchored entries arrive with the model's own geometry,
 * and that geometry drifts — far enough to paint a shout balloon's line over
 * the chapter title art half a screen away. But an unanchored entry still
 * carries the source text it read, and OCR usually read the same text with
 * exact boxes (it was merely filtered out of the regions, or never grouped).
 * Matching the entry's text back to the OCR lines recovers the true position
 * without trusting the model's coordinates at all.
 *
 * Matching is conservative: folded case and accents, whole lines contained in
 * the target, spatial clustering so the same word appearing in two balloons
 * cannot stretch the anchor across both, and a coverage floor so a stray
 * "de" matching somewhere is never taken for the sentence it came from.
 */
object TextAnchor {

    /** Share of the target text the matched lines must actually carry. */
    private const val MIN_COVERAGE = 0.5f

    /**
     * The union box of the OCR lines that carry [src], or null when the page
     * does not show enough of it in one place.
     */
    fun locate(src: String, lines: List<OcrLine>): Rect? {
        val target = fold(src)
        if (target.length < 4) return null

        data class Hit(val box: Rect, val len: Int)
        val hits = ArrayList<Hit>()
        for (l in lines) {
            val t = fold(l.text)
            if (t.length < 3) continue
            // One line holding the whole target is the common single-line
            // balloon; anything longer is judged by accumulated coverage.
            if (t.contains(target)) return Rect(l.box)
            if (target.contains(t)) hits.add(Hit(l.box, t.length))
        }
        if (hits.isEmpty()) return null

        // Lines of one balloon stack tightly; the same word elsewhere on the
        // page does not. Cluster by adjacency and judge the best cluster
        // alone, so a coincidental match cannot stretch the anchor.
        hits.sortWith(compareBy({ it.box.top }, { it.box.left }))
        val clusters = ArrayList<MutableList<Hit>>()
        for (hit in hits) {
            val cluster = clusters.lastOrNull()
            val prev = cluster?.last()?.box
            if (prev != null && adjacent(prev, hit.box)) {
                cluster.add(hit)
            } else {
                clusters.add(mutableListOf(hit))
            }
        }
        val best = clusters.maxBy { c -> c.sumOf { it.len } }
        val covered = best.sumOf { it.len }
        if (covered < target.length * MIN_COVERAGE) return null

        val union = Rect(best[0].box)
        for (hit in best.drop(1)) union.union(hit.box)
        return union
    }

    /** Stacked lines of one balloon: vertically close, horizontally overlapping. */
    private fun adjacent(a: Rect, b: Rect): Boolean {
        val gap = b.top - a.bottom
        val height = max(a.height(), b.height())
        if (gap > height * 14 / 10) return false
        return min(a.right, b.right) - max(a.left, b.left) > 0
    }

    /** Case-, accent- and punctuation-blind form for containment tests. */
    fun fold(s: String): String {
        val norm = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(norm.length)
        for (c in norm) {
            if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }
}
