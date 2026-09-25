package app.mangalens.pipeline

import android.graphics.Rect
import app.mangalens.translate.PageItem

/** Which English a line keeps when it is read again at a later stop. */
internal object Wording {

    /**
     * Keeps the wording the reader already saw. An item recalled from an
     * earlier stop is re-read by the model along with everything else, and
     * a fresh answer for the same balloon may be phrased differently — a
     * line that changes words while it is being read is worse than either
     * phrasing. The model's new geometry is kept; the recalled English wins.
     *
     * Only where the two answers cut the lettering the same way: one fresh
     * item on one recalled item, reading the same characters. When the
     * model splits a balloon it read whole before, or joins two pieces it
     * read apart, the recalled English no longer lines up with any one
     * fresh item — copying it across would say a line twice or drop half
     * of it — so the fresh reading stands whole. A recalled item no fresh
     * item touches is lettering the model passed over this time, and is
     * kept as it was. A [partial] recalled item — one the screen's edge
     * cut when it was read — never keeps its wording over a fresh one.
     */
    fun keep(recalled: List<PageItem>, fresh: List<PageItem>, partial: Set<PageItem> = emptySet()): List<PageItem> {
        if (recalled.isEmpty()) return fresh
        val touches = recalled.map { r -> fresh.indices.filter { onSameLettering(r.box, fresh[it].box) } }
        val out = ArrayList<PageItem>(fresh.size + recalled.size)
        for ((k, f) in fresh.withIndex()) {
            val by = recalled.indices.filter { k in touches[it] }
            val r = by.singleOrNull()?.let { recalled[it] }
            if (r != null && r !in partial && touches[by[0]].size == 1 && sameLettering(r.box, f.box) && sameWords(r.src, f.src) && !sawMore(r, f)) {
                out.add(f.copy(en = r.en, who = r.who.ifBlank { f.who }))
            } else {
                out.add(f)
            }
        }
        for (i in recalled.indices) if (touches[i].isEmpty()) out.add(recalled[i])
        return out
    }

    /**
     * The fresh reading saw more of the line than the recalled one did:
     * more characters, over a box a tenth taller or more. The screen's edge cut
     * the line when it was first read, and its English says only the part
     * that showed ("a part-time job..." for 打工 of 打工？); kept, the line
     * would stay a fragment however much of it the reader can now see.
     */
    private fun sawMore(recalled: PageItem, fresh: PageItem): Boolean =
        fresh.src.count { !it.isWhitespace() } > recalled.src.count { !it.isWhitespace() } &&
            fresh.box.height() > recalled.box.height() * TALLER

    private fun sameLettering(a: Rect, b: Rect): Boolean =
        iou(a, b) > 0.45f || (a.contains(b.centerX(), b.centerY()) && b.contains(a.centerX(), a.centerY()))

    /** Enough of either box lies in the other that they hold some of the same lettering. */
    private fun onSameLettering(a: Rect, b: Rect): Boolean =
        Rect.intersects(a, b) && (containedShare(a, b) > 0.3f || containedShare(b, a) > 0.3f)

    /**
     * The same characters, near enough: the model's transcription of one
     * line wavers by a character or two between reads, but a different line
     * shares little with it. Blank on either side says nothing either way.
     */
    private fun sameWords(a: String, b: String): Boolean {
        val x = a.filter(Char::isLetterOrDigit)
        val y = b.filter(Char::isLetterOrDigit)
        if (x.isEmpty() || y.isEmpty()) return true
        val pool = HashMap<Char, Int>()
        for (c in x) pool[c] = (pool[c] ?: 0) + 1
        var common = 0
        for (c in y) {
            val n = pool[c] ?: 0
            if (n > 0) {
                common++
                pool[c] = n - 1
            }
        }
        return common >= maxOf(x.length, y.length) * SAME_WORDS
    }

    private fun iou(a: Rect, b: Rect): Float {
        val ix = maxOf(0, minOf(a.right, b.right) - maxOf(a.left, b.left))
        val iy = maxOf(0, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        val inter = ix.toLong() * iy
        if (inter == 0L) return 0f
        val union = a.width().toLong() * a.height() + b.width().toLong() * b.height() - inter
        return inter.toFloat() / union
    }

    /** Share of [box] that lies in [within]. */
    private fun containedShare(box: Rect, within: Rect): Float {
        val ix = minOf(box.right, within.right) - maxOf(box.left, within.left)
        val iy = minOf(box.bottom, within.bottom) - maxOf(box.top, within.top)
        if (ix <= 0 || iy <= 0) return 0f
        val area = box.width().toLong() * box.height()
        if (area <= 0L) return 0f
        return (ix.toLong() * iy).toFloat() / area
    }

    /** Share of the longer transcription two readings of one line have in common. */
    private const val SAME_WORDS = 0.6f

    /** How much taller a fresh box must be for its reading to be of more of the line. */
    private const val TALLER = 1.1f
}
