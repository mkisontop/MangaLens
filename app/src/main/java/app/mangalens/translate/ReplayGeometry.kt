package app.mangalens.translate

import android.graphics.Rect
import kotlin.math.abs

/**
 * Decides whether a cached vision answer may be replayed onto the frame in
 * front of us, and where its unanchored entries belong on it.
 *
 * A cache hit proves the same balloons are visible — not that they sit where
 * they sat. A manhwa reader stops at arbitrary scroll offsets, so the same
 * page content recurs shifted. Entries anchored to a region id already follow
 * the region's current box; the unanchored extras carry the screen coordinates
 * of the frame they were answered on, and replaying those verbatim paints text
 * where a balloon used to be. The stored region layout is therefore compared
 * against the current one: a single consistent translation is measured and
 * applied to the extras, and anything inconsistent — a zoom, a relayout, a
 * coincidental content match — refuses to replay at all. One extra network
 * round-trip is always a smaller cost than English on the wrong balloon.
 */
object ReplayGeometry {

    /** Displacement between two layouts, in the same units as the boxes. */
    data class Shift(val dx: Int, val dy: Int)

    /**
     * The single translation moving [stored] onto [current], or null when no
     * such translation exists: different region counts, or per-region moves
     * that disagree with the median by more than [tolerance]. Boxes correspond
     * by index and must share a coordinate space (normalized page coords here).
     */
    fun shift(stored: List<Rect>, current: List<Rect>, tolerance: Int = 25): Shift? {
        if (stored.size != current.size) return null
        if (stored.isEmpty()) return Shift(0, 0)
        val dxs = IntArray(stored.size) { current[it].centerX() - stored[it].centerX() }
        val dys = IntArray(stored.size) { current[it].centerY() - stored[it].centerY() }
        val mdx = median(dxs)
        val mdy = median(dys)
        for (i in stored.indices) {
            if (abs(dxs[i] - mdx) > tolerance || abs(dys[i] - mdy) > tolerance) return null
        }
        return Shift(mdx, mdy)
    }

    /**
     * Moves the unanchored entries (id < 0) by [shift] so they land on the
     * content they translate. Anchored entries take their geometry from the
     * current frame's regions and pass through untouched. Entries pushed
     * off-page are left to the renderer's own bounds checks to drop.
     */
    fun shiftExtras(
        entries: List<VisionLlmEngine.VisionBubble>,
        shift: Shift,
    ): List<VisionLlmEngine.VisionBubble> {
        if (shift.dx == 0 && shift.dy == 0) return entries
        return entries.map { v ->
            if (v.id >= 0) v else v.copy(nx = v.nx + shift.dx, ny = v.ny + shift.dy)
        }
    }

    private fun median(values: IntArray): Int {
        val sorted = values.sortedArray()
        return sorted[sorted.size / 2]
    }
}
