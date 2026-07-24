package app.mangalens.ocr

import app.mangalens.settings.SourceLang

/**
 * Panel-aware reading order via recursive X-Y cut.
 *
 * Sorting bubbles by (top, left) reads a manga page backwards: Japanese pages
 * run right-to-left, so a two-panel tier comes out swapped, and any two panels
 * sitting side by side interleave their dialogue by whichever line happens to
 * sit higher. Order matters far beyond cosmetics — it is the order bubbles are
 * handed to the translator as "reading order", the order story context
 * accumulates in, and the adjacency that [Utterance] uses to stitch a sentence
 * back together across bubbles. Get it wrong and every downstream context
 * signal is scrambled.
 *
 * The page is instead split recursively on whitespace gutters no bubble spans:
 * a horizontal gutter splits tiers (top tier first), a vertical gutter splits
 * panels within a tier (right first when [rtl]). Whichever gutter is more
 * pronounced wins, with a mild bias toward tiers because that is the dominant
 * structure of a comics page. This handles the case a flat tier sweep cannot:
 * a full-height panel down the right side of the page, where no horizontal
 * gutter crosses the page at all, so the vertical cut correctly takes that
 * panel first and only then splits the stacked panels beside it.
 */
object ReadingOrder {

    /** Recursion cap; pathological pages fall back to a plain sort at the leaf. */
    private const val MAX_DEPTH = 14

    /**
     * A gutter must clear this fraction of the group's median bubble size to
     * count as structure rather than ordinary line spacing inside one balloon.
     */
    private const val GUTTER_RATIO = 0.55f

    /**
     * Tiers are the dominant page structure, so a horizontal gutter wins ties
     * and survives being slightly narrower than a competing vertical one. On a
     * regular grid both gutters measure the same, and splitting into columns
     * first would read every panel down the right edge before coming back for
     * the left — the whole page out of order.
     */
    private const val TIER_BIAS = 0.8f

    /**
     * Orders bubbles as a human reads them.
     *
     * @param rtl true for traditional Japanese pages (right-to-left tiers),
     *   false for vertically-scrolling webtoons and manhua.
     */
    fun order(bubbles: List<Bubble>, rtl: Boolean): List<Bubble> {
        if (bubbles.size <= 1) return bubbles
        val out = ArrayList<Bubble>(bubbles.size)
        cut(bubbles, rtl, 0, out)
        return out
    }

    /**
     * True when a page should be read right-to-left. Vertical CJK text is the
     * reliable tell: a Japanese page typeset in columns is traditional manga,
     * while a Japanese webtoon sets its dialogue horizontally and scrolls like
     * a Korean one. Korean and Chinese webtoons stay left-to-right.
     */
    fun isRightToLeft(bubbles: List<Bubble>, lang: SourceLang): Boolean {
        if (lang == SourceLang.KO) return false
        val dialogue = bubbles.filter { it.kind == BubbleKind.DIALOGUE }
        if (dialogue.isEmpty()) return false
        val vertical = dialogue.count { it.vertical }
        return vertical * 3 >= dialogue.size
    }

    private fun cut(items: List<Bubble>, rtl: Boolean, depth: Int, out: MutableList<Bubble>) {
        if (items.isEmpty()) return
        if (items.size == 1) {
            out.add(items[0])
            return
        }
        if (depth >= MAX_DEPTH) {
            out.addAll(bandSweep(items, rtl))
            return
        }

        // A gutter only counts if it is wider than the text it separates,
        // otherwise ordinary line spacing inside a balloon splits the page.
        val sizes = items.map { minOf(it.box.width(), it.box.height()) }.sorted()
        val minGutter = (sizes[sizes.size / 2] * GUTTER_RATIO).coerceAtLeast(4f)

        val h = widestGap(items, minGutter, horizontal = true)
        val v = widestGap(items, minGutter, horizontal = false)

        // Prefer whichever separator is more pronounced; tiers break ties.
        val useHorizontal = h != null && (v == null || h.gap >= v.gap * TIER_BIAS)
        val split = if (useHorizontal) h else v
        if (split == null) {
            out.addAll(bandSweep(items, rtl))
            return
        }

        // Tiers read top-first; panels within a tier read right-first on a
        // manga page and left-first in a webtoon.
        val readNearSideFirst = useHorizontal || !rtl
        val first = if (readNearSideFirst) split.low else split.high
        val second = if (readNearSideFirst) split.high else split.low
        cut(first, rtl, depth + 1, out)
        cut(second, rtl, depth + 1, out)
    }

    private class Split(val low: List<Bubble>, val high: List<Bubble>, val gap: Float)

    /**
     * Finds the widest axis-aligned gutter that no bubble spans, splitting the
     * group cleanly in two. Returns null when the boxes overlap continuously
     * along that axis, which is the signal to try the other axis.
     */
    private fun widestGap(items: List<Bubble>, minGutter: Float, horizontal: Boolean): Split? {
        fun start(b: Bubble) = if (horizontal) b.box.top else b.box.left
        fun end(b: Bubble) = if (horizontal) b.box.bottom else b.box.right

        val sorted = items.sortedBy { start(it) }
        var reach = end(sorted[0])
        var bestGap = 0f
        var bestIndex = -1
        for (i in 1 until sorted.size) {
            val gap = (start(sorted[i]) - reach).toFloat()
            if (gap >= minGutter && gap > bestGap) {
                bestGap = gap
                bestIndex = i
            }
            reach = maxOf(reach, end(sorted[i]))
        }
        if (bestIndex < 0) return null
        return Split(sorted.subList(0, bestIndex), sorted.subList(bestIndex, sorted.size), bestGap)
    }

    /**
     * Ordering for a group no gutter cleanly divides — overlapping balloons,
     * art-shaped panels, boxes the grouper left tangled.
     *
     * Bubbles that overlap vertically are swept into one band and read across
     * it before the next band starts. Sorting by top alone would be wrong here
     * in exactly the way this class exists to fix: two balloons side by side
     * with slightly different tops are read *across*, not down, and on a manga
     * page that means the right one first regardless of which sits higher.
     */
    private fun bandSweep(items: List<Bubble>, rtl: Boolean): List<Bubble> {
        val across: Comparator<Bubble> =
            if (rtl) compareByDescending { it.box.right } else compareBy { it.box.left }

        val bands = ArrayList<MutableList<Bubble>>()
        var bandBottom = Int.MIN_VALUE
        for (b in items.sortedBy { it.box.top }) {
            if (bands.isEmpty() || b.box.top >= bandBottom) {
                bands.add(mutableListOf(b))
                bandBottom = b.box.bottom
            } else {
                bands.last().add(b)
                bandBottom = maxOf(bandBottom, b.box.bottom)
            }
        }
        return bands.flatMap { it.sortedWith(across) }
    }
}
