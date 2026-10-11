package app.mangalens.gaps

import kotlin.math.max
import kotlin.math.min

/**
 * One edge of the page on screen — the bottom or the top — and how far the gutters that run out
 * of it go, column by column, as the frames have shown them coming in.
 *
 * A gutter that reaches the edge goes on past it, and the page brings that part in as it moves.
 * Each frame, the rows from the detection's last ones at the edge to the edge itself are walked,
 * column by column, from the gutter outward, for as long as they are paper. The walk stops where
 * they are not: there the gutter ends, as far as anyone knows yet.
 *
 * The frames carry the shade, and under it paper and art as light as the faintest ringing look
 * alike. So the walk believes only what cannot be the shade's doing: paper seen plain — the shade
 * only ever darkens — or a pixel that looks like paper under the shade on a row of the page that
 * was seen plain before, as it came in. Rows come in plain: the shade's end is held a margin short
 * of the last row seen. Whatever the frames cannot vouch for stops the walk, and costs a strip of
 * white, never a row of darkened art.
 *
 * Rows are kept as rows of the page: a frame row minus the page's offset in it.
 */
internal class EdgeWatch(private val width: Int, private val bottom: Boolean) {

    /** Per column, the page rows [runFrom, runTo) last seen to be paper, one unbroken run; empty when runTo <= runFrom. */
    private val runFrom = IntArray(width)
    private val runTo = IntArray(width)

    /** Per column, where the newest walk stopped: the first page row past the gutter not known to be paper; [NONE] where it did not go. */
    private val stop = IntArray(width) { NONE }

    /** Columns to walk in the next frame. */
    private val marked = BooleanArray(width)

    /** Forgets everything: the page is another, or was read whole at rest. */
    fun clear() {
        java.util.Arrays.fill(runFrom, 0)
        java.util.Arrays.fill(runTo, 0)
        java.util.Arrays.fill(stop, NONE)
        java.util.Arrays.fill(marked, false)
    }

    /**
     * Takes page rows [a, b) of columns [x0, x1) for seen: the exact pass at rest read them as
     * paper, and the shade over them is its own. Joined to what is known where it touches it; give
     * the rows of one column in order, from the gutter's far end to the edge.
     */
    fun seed(x0: Int, x1: Int, a: Int, b: Int) {
        if (b <= a) return
        for (x in max(0, x0) until min(width, x1)) {
            val f = runFrom[x]
            val t = runTo[x]
            if (t > f && f <= b && a <= t) {
                runFrom[x] = min(f, a)
                runTo[x] = max(t, b)
            } else {
                runFrom[x] = a
                runTo[x] = b
            }
        }
    }

    /** Asks for columns [x0, x1) to be walked in the next frame. */
    fun mark(x0: Int, x1: Int) {
        for (x in max(0, x0) until min(width, x1)) marked[x] = true
    }

    /**
     * Walks the marked columns of [src] — and forgets the walks of the rest — from frame row [from]
     * toward the edge at frame row [edge]: down to the row before it at the bottom, up to it at the
     * top. [offset] is the page's offset in this frame, [style] says what paper looks like under the
     * shade, and [keepOut] are rectangles, `[x0, y0, x1, y1]`, that are not the page at all:
     * MangaLens's own controls.
     *
     * A row at a time, every column still going: a frame is laid out in rows.
     */
    fun walk(src: PixelSource, from: Int, edge: Int, offset: Int, style: ShadeStyle, keepOut: List<IntArray>) {
        var going = 0
        for (x in 0 until width) {
            if (marked[x]) {
                going++
                // where it stops if nothing stops it: at the edge
                stop[x] = edge - offset
            } else {
                stop[x] = NONE
            }
        }
        val step = if (bottom) 1 else -1
        var y = from
        while (going > 0 && (if (bottom) y < edge else y >= edge)) {
            val row = y - offset
            for (x in 0 until width) {
                if (!marked[x]) continue
                val p = src.pixel(x, y)
                if ((p and 0xF0F0F0) == 0xF0F0F0) continue
                if (style.isShadedPaper(p) && row >= runFrom[x] && row < runTo[x]) continue
                if (keptOut(keepOut, x, y)) continue
                marked[x] = false
                going--
                stop[x] = if (bottom) row else row + 1
            }
            y += step
        }
        for (x in 0 until width) {
            if (stop[x] == NONE) continue
            marked[x] = false
            // the rows walked are paper
            if (bottom) remember(x, from - offset, stop[x]) else remember(x, stop[x], from + 1 - offset)
        }
    }

    private fun keptOut(keepOut: List<IntArray>, x: Int, y: Int): Boolean {
        for (i in keepOut.indices) {
            val r = keepOut[i]
            if (x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) return true
        }
        return false
    }

    /**
     * Where the gutter in columns [x0, x1) stops being known paper, as a page row: at the bottom,
     * the first row past it, the nearest of all the columns; at the top, the row it starts at, the
     * nearest likewise. [orElse] where a column was not walked.
     */
    fun stopIn(x0: Int, x1: Int, orElse: Int): Int {
        var best = Int.MIN_VALUE
        for (x in max(0, x0) until min(width, x1)) {
            val s = stop[x].let { if (it == NONE) orElse else it }
            best = if (best == Int.MIN_VALUE) s else if (bottom) min(best, s) else max(best, s)
        }
        return if (best == Int.MIN_VALUE) orElse else best
    }

    /**
     * Rows [a, b) of column [x] are paper. Joined to the run already known when they touch it — the
     * walk's own end is the run's — and the run besides.
     */
    private fun remember(x: Int, a: Int, b: Int) {
        if (b <= a) return
        val f = runFrom[x]
        val t = runTo[x]
        val touches = t > f && f <= b && a <= t
        if (bottom) {
            runFrom[x] = if (touches) min(f, a) else a
            runTo[x] = b
        } else {
            runFrom[x] = a
            runTo[x] = if (touches) max(t, b) else b
        }
    }

    companion object {
        const val NONE = Int.MIN_VALUE
    }
}
