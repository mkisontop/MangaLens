package app.mangalens.pipeline

import android.graphics.Rect
import app.mangalens.overlay.RenderBubble

/**
 * A later answer replaces the cards it covers — and never shows less than
 * the page already showed.
 *
 * Lines stream onto the page as the model writes them, and the answer a
 * pass ends with does not always hold all of them: a page-image reply that
 * covered too little is finished by the text path, which can only answer
 * what on-device OCR read, and a read that broke off is finished the same
 * way. Painting that answer verbatim erased lines the reader was actively
 * reading. A line losing to *no* line is strictly a downgrade, so any card
 * the later answer did not cover is kept alongside it.
 */
object UpgradeMerge {

    /** [over]'s cards, plus every card of [under] none of them covers. */
    fun merge(under: List<RenderBubble>, over: List<RenderBubble>): List<RenderBubble> {
        if (under.isEmpty()) return over
        if (over.isEmpty()) return under
        val out = ArrayList<RenderBubble>(over)
        for (u in under) {
            if (over.none { covers(it.box, u.box) }) out.add(u)
        }
        return out
    }

    /**
     * A box covers an earlier one when it takes in a meaningful share of it
     * (≥30%) — a later answer routinely measures the same balloon a few
     * pixels off, and exact equality would treat every re-measured card as
     * uncovered and paint the balloon twice.
     */
    private fun covers(later: Rect, earlier: Rect): Boolean {
        if (!Rect.intersects(later, earlier)) return false
        val ix = (minOf(later.right, earlier.right) - maxOf(later.left, earlier.left)).toLong()
        val iy = (minOf(later.bottom, earlier.bottom) - maxOf(later.top, earlier.top)).toLong()
        val earlierArea = earlier.width().toLong() * earlier.height()
        return earlierArea > 0 && ix * iy * 10 >= earlierArea * 3
    }
}
