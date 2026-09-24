package app.mangalens.pipeline

import android.graphics.Rect
import app.mangalens.translate.PageItem
import app.mangalens.translate.PendingRead

/** A frame whose lettering is all accounted for, and what it said. */
internal class Seen(val match: ScrollMatch, val items: List<PageItem>)

/**
 * The read of a stop that only nudged the page: the model is shown just the
 * strip the scroll revealed, with a margin above it for a balloon the last
 * stop cut in half, and everything else on screen is lettering already read
 * and repainted from memory. The new balloon is then the model's first
 * answer rather than its last, after every line the reader has already
 * seen, and the upload is a fraction of the page. A nudge that revealed
 * next to nothing sends no request at all ([inner] null).
 *
 * The model's boxes, measured on the strip, are moved back onto the screen.
 */
internal class StripRead(
    private val inner: PendingRead?,
    /** The strip read, in screen coordinates; empty when nothing was sent. */
    val strip: Rect,
    /** How far the page moved since [since]: row y now shows its row y + [scrolled]. */
    val scrolled: Int,
    val since: Seen,
    val match: ScrollMatch,
    /**
     * The rows the scroll revealed, in screen coordinates: the model reads
     * the lettering reaching into them, and the strip's margin is left to
     * memory. Empty when nothing was sent.
     */
    val revealed: Rect = Rect(),
) : PendingRead {

    override val isActive: Boolean get() = inner?.isActive ?: false
    override val firstItemMs: Long? get() = inner?.firstItemMs
    override val doneMs: Long? get() = inner?.doneMs ?: 0L
    override val cutOff: Boolean get() = inner?.cutOff ?: false
    override val summary: String
        get() = if (inner == null) "nothing new" else "strip ${strip.height()} px · ${inner.summary}"

    override suspend fun collect(onItem: (suspend (PageItem) -> Unit)?): List<PageItem> {
        val read = inner ?: return emptyList()
        if (onItem == null) return read.collect(null).map(::onScreen)
        return read.collect { item -> onItem(onScreen(item)) }.map(::onScreen)
    }

    override fun cancel() {
        inner?.cancel()
    }

    private fun onScreen(item: PageItem) = item.copy(box = Rect(item.box).apply { offset(strip.left, strip.top) })

    /**
     * Whether memory found again, on this frame of [height] rows, every
     * line [since] held that is still on screen and reaches no row the
     * scroll revealed — the lines this read counts on memory for, the
     * strip's margin included: the model is told to leave those. Lines in
     * the ignored bands at the top and bottom are not expected. When one is
     * missing, only a read of the whole screen is sure to letter it.
     */
    fun covered(recalled: List<PageItem>, height: Int, ignoreTop: Int, ignoreBottom: Int): Boolean =
        since.items.all { old ->
            val box = Rect(old.box).apply { offset(0, -scrolled) }
            when {
                box.top < ignoreTop || box.bottom > height - ignoreBottom -> true
                !revealed.isEmpty && Rect.intersects(box, revealed) -> true
                else -> recalled.any { sameSpot(it.box, box) }
            }
        }

    /**
     * The strip's answers, less any the strip's own edge cut in two: a
     * balloon straddling the edge towards what was read before is half on
     * the strip, and the model reads the half it was shown. Where memory
     * holds that balloon whole, the half-reading is dropped.
     */
    fun trim(fresh: List<PageItem>, recalled: List<PageItem>): List<PageItem> {
        if (strip.isEmpty) return fresh
        val edge = if (scrolled > 0) strip.top else strip.bottom
        if (edge <= 0 || edge >= match.h) return fresh
        return fresh.filterNot { f ->
            val cut = if (scrolled > 0) f.box.top <= edge + EDGE_PX else f.box.bottom >= edge - EDGE_PX
            cut && recalled.any { Rect.intersects(it.box, f.box) }
        }
    }

    private companion object {
        /** An answer's box this close to the strip's cut edge was cut by it. */
        const val EDGE_PX = 6

        fun sameSpot(a: Rect, b: Rect): Boolean {
            val r = Rect()
            if (!r.setIntersect(a, b)) return false
            val smaller = minOf(a.width().toLong() * a.height(), b.width().toLong() * b.height()).coerceAtLeast(1L)
            return r.width().toLong() * r.height() * 2 > smaller
        }
    }
}
