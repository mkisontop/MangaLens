package app.mangalens.overlay

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where a page is drawn and where it is empty, coarsely: which cells of a
 * grid hold line work (faces, hair, hands, panel borders) and which are
 * paper, flat colour or an even screentone. A note for a sound effect may
 * sit on empty ground beside the sound; drawn art is never covered for it.
 *
 * Measured on cell averages, not pixels: a cell averages a screentone's
 * dots into one even grey, while a drawn line changes the average from one
 * cell to the next. A cell is drawn when its average steps sharply against
 * a neighbour's.
 */
class ArtMap internal constructor(
    private val cell: Int,
    private val cols: Int,
    private val rows: Int,
    private val drawn: BooleanArray,
) {

    /** Share of the cells under [r] that hold line work; 1 for a rectangle off the page. */
    fun drawnShare(r: RectF): Float {
        val c0 = (r.left / cell).toInt().coerceAtLeast(0)
        val r0 = (r.top / cell).toInt().coerceAtLeast(0)
        val c1 = ((r.right - 1) / cell).toInt().coerceAtMost(cols - 1)
        val r1 = ((r.bottom - 1) / cell).toInt().coerceAtMost(rows - 1)
        if (c1 < c0 || r1 < r0) return 1f
        var n = 0
        var hit = 0
        for (y in r0..r1) for (x in c0..c1) {
            n++
            if (drawn[y * cols + x]) hit++
        }
        return hit.toFloat() / n
    }

    companion object {
        /** Cells across a page; about 8 px on a phone's capture. */
        private const val CELLS_ACROSS = 200

        /** Step in average grey, 0–255 summed over both axes, that marks a drawn line. */
        private const val EDGE = 24

        /** Pixels sampled per cell side; a screentone's dots average out well before this. */
        private const val SAMPLES = 4

        fun of(page: Bitmap): ArtMap {
            val w = page.width
            val h = page.height
            val cell = max(4, (w.toFloat() / CELLS_ACROSS).roundToInt())
            val cols = max(1, w / cell)
            val rows = max(1, h / cell)
            val grey = IntArray(cols * rows)
            val step = max(1, cell / SAMPLES)
            val row = IntArray(w)
            val sums = IntArray(cols)
            val counts = IntArray(cols)
            for (cy in 0 until rows) {
                sums.fill(0)
                counts.fill(0)
                var y = cy * cell
                while (y < min(h, (cy + 1) * cell)) {
                    page.getPixels(row, 0, w, 0, y, w, 1)
                    var x = 0
                    while (x < cols * cell) {
                        val c = row[x]
                        sums[x / cell] += (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                        counts[x / cell]++
                        x += step
                    }
                    y += step
                }
                for (cx in 0 until cols) grey[cy * cols + cx] = if (counts[cx] == 0) 0 else sums[cx] / counts[cx]
            }
            val drawn = BooleanArray(cols * rows)
            for (cy in 0 until rows) for (cx in 0 until cols) {
                val i = cy * cols + cx
                val g = grey[i]
                val right = if (cx + 1 < cols) abs(grey[i + 1] - g) else 0
                val down = if (cy + 1 < rows) abs(grey[i + cols] - g) else 0
                if (right + down > EDGE) {
                    // A step belongs to both cells it separates.
                    drawn[i] = true
                    if (right > EDGE / 2 && cx + 1 < cols) drawn[i + 1] = true
                    if (down > EDGE / 2 && cy + 1 < rows) drawn[i + cols] = true
                }
            }
            return ArtMap(cell, cols, rows, drawn)
        }
    }
}
