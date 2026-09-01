package app.mangalens.ocr

import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Reads the panel grid off the gutters between panels.
 *
 * [ReadingOrder] cuts the page on the whitespace between balloons, and for
 * one family of layouts that is not enough: a full-height panel down one
 * side of the page, with a balloon near its top, produces balloon geometry
 * identical to a two-panel tier above a single panel — and the two read in
 * different orders. Only the panel borders tell them apart. This finds the
 * panels the same way a reader's eye does, from the gutters: a run of rows
 * (or columns) across a region that holds nothing but paper, or nothing but
 * black, separates the panels on either side of it. Cutting recursively on
 * those runs yields the panel rectangles; a region no gutter crosses is a
 * panel.
 *
 * The gutter test is strict — every cell of the row must be blank — because
 * the case that matters is exactly a row that is blank except where a panel
 * border crosses it. Tolerate a couple of cells and the border of a tall
 * side panel is forgiven, the page is cut into tiers it does not have, and
 * the tall panel's line is read in the wrong place. Blankness is judged from
 * each cell's darkest pixel, so a hairline border is never averaged away.
 *
 * A leaf only counts as a panel when it has a drawn border: a run of ink
 * along each of its four sides. Balloons and figures floating on a blank
 * background cut into leaves just as panels do, but an ellipse touches its
 * bounding box at four points and a figure at a few — neither has a border,
 * and a page made only of such leaves has no grid. Pages drawn without
 * gutters at all — borderless art, bleeds, a webtoon strip — likewise yield
 * no grid, and reading order falls back to the balloons alone.
 */
object PageLayout {

    /** Rows or columns of blank this thick (cells) count as a gutter. */
    private const val MIN_GUTTER = 2

    private const val MAX_DEPTH = 8

    /** Leaves smaller than this share of the page are titles, numbers, crumbs. */
    private const val MIN_PANEL_AREA = 0.012f

    /** Leaves narrower than this share of the page's short side likewise. */
    private const val MIN_PANEL_DIM = 0.05f

    /**
     * How far inside a leaf its border may sit, as a share of the leaf's
     * size. A balloon breaking a panel's edge stretches the leaf past the
     * border; the border is then found a little way in.
     */
    private const val BORDER_REACH = 0.25f

    /** Share of a row or column that must be drawn line for it to be a border. */
    private const val BORDER_SHARE = 0.6f

    /** A grid covers the page; a couple of accidental borders on a blank one do not. */
    private const val MIN_GRID_COVER = 0.3f

    /**
     * Panel rectangles at the grid's own resolution, or an empty list when
     * the page shows no grid.
     *
     * @param paper cells holding no ink at all.
     * @param dark cells holding nothing but black.
     * @param edge cells holding a drawn line: solid ink, or light and dark together.
     */
    fun panels(paper: BooleanArray, dark: BooleanArray, edge: BooleanArray, w: Int, h: Int): List<Rect> {
        if (w < 8 || h < 8 || paper.size < w * h || dark.size < w * h || edge.size < w * h) return emptyList()
        val leaves = ArrayList<Rect>()
        cut(paper, dark, w, Rect(0, 0, w, h), 0, leaves)
        val minArea = MIN_PANEL_AREA * w * h
        val minDim = (MIN_PANEL_DIM * min(w, h)).toInt().coerceAtLeast(2)
        val panels = leaves.mapNotNull { bordered(it, dark, edge, w, h) }.filter {
            it.width() * it.height() >= minArea && it.width() >= minDim && it.height() >= minDim
        }
        // One leaf is the page itself, not a grid.
        if (panels.size < 2) return emptyList()
        val covered = panels.sumOf { it.width().toLong() * it.height() }
        return if (covered < MIN_GRID_COVER * w * h) emptyList() else panels
    }

    /**
     * The drawn border of a leaf, or null when any side has none. Each side
     * is searched a little way inward for a row or column that is mostly
     * line; the panel is the rectangle those four lines make. A side with
     * no line of its own still counts when a black gutter runs along it —
     * the line is there, merely black on black — provided the leaf's own
     * edge row is mostly content rather than the few cells a figure's
     * outline touches its bounding box with.
     */
    private fun bordered(leaf: Rect, dark: BooleanArray, edge: BooleanArray, w: Int, h: Int): Rect? {
        val reachY = (leaf.height() * BORDER_REACH).toInt().coerceAtLeast(1)
        val reachX = (leaf.width() * BORDER_REACH).toInt().coerceAtLeast(1)
        fun rowShare(y: Int, plane: BooleanArray, want: Boolean): Float {
            var n = 0
            for (x in leaf.left until leaf.right) if (plane[y * w + x] == want) n++
            return n.toFloat() / leaf.width()
        }
        fun colShare(x: Int, plane: BooleanArray, want: Boolean): Float {
            var n = 0
            for (y in leaf.top until leaf.bottom) if (plane[y * w + x] == want) n++
            return n.toFloat() / leaf.height()
        }
        fun rowLine(y: Int) = rowShare(y, edge, true) >= BORDER_SHARE
        fun colLine(x: Int) = colShare(x, edge, true) >= BORDER_SHARE
        // Bounded by a dark gutter: the row just outside is black through,
        // and the edge row itself is content across most of its width.
        fun rowInGutter(y: Int, outside: Int) =
            outside in 0 until h && rowShare(outside, dark, true) >= 0.98f && rowShare(y, dark, false) >= BORDER_SHARE
        fun colInGutter(x: Int, outside: Int) =
            outside in 0 until w && colShare(outside, dark, true) >= 0.98f && colShare(x, dark, false) >= BORDER_SHARE

        val top = (leaf.top until min(leaf.bottom, leaf.top + reachY)).firstOrNull { rowLine(it) }
            ?: leaf.top.takeIf { rowInGutter(it, it - 1) }
            ?: return null
        val bottom = (leaf.bottom - 1 downTo max(leaf.top, leaf.bottom - reachY)).firstOrNull { rowLine(it) }
            ?: (leaf.bottom - 1).takeIf { rowInGutter(it, it + 1) }
            ?: return null
        val left = (leaf.left until min(leaf.right, leaf.left + reachX)).firstOrNull { colLine(it) }
            ?: leaf.left.takeIf { colInGutter(it, it - 1) }
            ?: return null
        val right = (leaf.right - 1 downTo max(leaf.left, leaf.right - reachX)).firstOrNull { colLine(it) }
            ?: (leaf.right - 1).takeIf { colInGutter(it, it + 1) }
            ?: return null
        if (right - left < 2 || bottom - top < 2) return null
        return Rect(left, top, right + 1, bottom + 1)
    }

    private fun cut(paper: BooleanArray, dark: BooleanArray, w: Int, region: Rect, depth: Int, out: MutableList<Rect>) {
        val r = trim(paper, dark, w, region) ?: return
        if (depth >= MAX_DEPTH) {
            out.add(r)
            return
        }
        val rows = gutterRuns(r.top, r.bottom) { y -> blankRow(paper, dark, w, r.left, r.right, y) }
        if (rows.isNotEmpty()) {
            for ((from, to) in segments(r.top, r.bottom, rows)) {
                cut(paper, dark, w, Rect(r.left, from, r.right, to), depth + 1, out)
            }
            return
        }
        val cols = gutterRuns(r.left, r.right) { x -> blankCol(paper, dark, w, r.top, r.bottom, x) }
        if (cols.isNotEmpty()) {
            for ((from, to) in segments(r.left, r.right, cols)) {
                cut(paper, dark, w, Rect(from, r.top, to, r.bottom), depth + 1, out)
            }
            return
        }
        out.add(r)
    }

    /**
     * Shrinks [region] past its blank margins on every side, repeating until
     * nothing more comes off — trimming the columns can leave rows that
     * only ever held a corner of art blank in turn. Null when nothing is
     * left, which is a blank region.
     */
    private fun trim(paper: BooleanArray, dark: BooleanArray, w: Int, region: Rect): Rect? {
        val r = Rect(region)
        while (true) {
            var changed = false
            while (r.top < r.bottom && blankRow(paper, dark, w, r.left, r.right, r.top)) { r.top++; changed = true }
            while (r.bottom > r.top && blankRow(paper, dark, w, r.left, r.right, r.bottom - 1)) { r.bottom--; changed = true }
            if (r.top >= r.bottom) return null
            while (r.left < r.right && blankCol(paper, dark, w, r.top, r.bottom, r.left)) { r.left++; changed = true }
            while (r.right > r.left && blankCol(paper, dark, w, r.top, r.bottom, r.right - 1)) { r.right--; changed = true }
            if (r.left >= r.right) return null
            if (!changed) return r
        }
    }

    /** True when row [y] over [from, to) is entirely paper or entirely black. */
    private fun blankRow(paper: BooleanArray, dark: BooleanArray, w: Int, from: Int, to: Int, y: Int): Boolean {
        val row = y * w
        var allPaper = true
        var allDark = true
        for (x in from until to) {
            val i = row + x
            if (!paper[i]) allPaper = false
            if (!dark[i]) allDark = false
            if (!allPaper && !allDark) return false
        }
        return true
    }

    /** True when column [x] over [from, to) is entirely paper or entirely black. */
    private fun blankCol(paper: BooleanArray, dark: BooleanArray, w: Int, from: Int, to: Int, x: Int): Boolean {
        var allPaper = true
        var allDark = true
        for (y in from until to) {
            val i = y * w + x
            if (!paper[i]) allPaper = false
            if (!dark[i]) allDark = false
            if (!allPaper && !allDark) return false
        }
        return true
    }

    /**
     * Interior runs of blank lines at least [MIN_GUTTER] long, as
     * (start, end) pairs over [from, to). The ends were trimmed already, so
     * every run found here sits between two pieces of content.
     */
    private fun gutterRuns(from: Int, to: Int, blank: (Int) -> Boolean): List<Pair<Int, Int>> {
        val runs = ArrayList<Pair<Int, Int>>()
        var start = -1
        for (i in from until to) {
            if (blank(i)) {
                if (start < 0) start = i
            } else if (start >= 0) {
                if (i - start >= MIN_GUTTER) runs.add(start to i)
                start = -1
            }
        }
        return runs
    }

    /** The content stretches between consecutive gutter runs. */
    private fun segments(from: Int, to: Int, runs: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>(runs.size + 1)
        var cursor = from
        for ((start, end) in runs) {
            if (start > cursor) out.add(cursor to start)
            cursor = end
        }
        if (to > cursor) out.add(cursor to to)
        return out
    }
}
