package app.mangalens.pipeline

import android.graphics.Rect
import app.mangalens.overlay.RenderBubble

/**
 * The AI polish replaces the draft it upgraded — and never shows less than
 * the draft already showed.
 *
 * The field failure this exists for: the fast draft paints, "✨ upgrading…"
 * runs, and the polished result comes back empty or partial — the model
 * skipped regions, or its answers failed anchoring against a frame that had
 * shifted a little. Painting that result verbatim erased cards the reader
 * was actively reading. A draft translation losing to *no* translation is
 * strictly a downgrade, so any draft bubble the polish did not answer is
 * kept alongside the polished ones.
 */
object UpgradeMerge {

    fun merge(draft: List<RenderBubble>, polished: List<RenderBubble>): List<RenderBubble> {
        if (draft.isEmpty()) return polished
        if (polished.isEmpty()) return draft
        val out = ArrayList<RenderBubble>(polished)
        for (d in draft) {
            if (polished.none { answers(it.box, d.box) }) out.add(d)
        }
        return out
    }

    /**
     * A polished box answers a draft box when it covers a meaningful share
     * of it (≥30%) — polish routinely re-measures geometry a few pixels off,
     * and exact equality would treat every re-measured bubble as unanswered
     * and double-paint the page.
     */
    private fun answers(polished: Rect, draft: Rect): Boolean {
        if (!Rect.intersects(polished, draft)) return false
        val ix = (minOf(polished.right, draft.right) - maxOf(polished.left, draft.left)).toLong()
        val iy = (minOf(polished.bottom, draft.bottom) - maxOf(polished.top, draft.top)).toLong()
        val draftArea = draft.width().toLong() * draft.height()
        return draftArea > 0 && ix * iy * 10 >= draftArea * 3
    }
}
