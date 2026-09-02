package app.mangalens.overlay

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The room a balloon actually has for text, row by row.
 *
 * A balloon is not its bounding box. An ellipse is narrow at the top and
 * bottom and widest in the middle; a tailed balloon has a body and a spike
 * the text must stay out of; a hand-drawn balloon is whatever shape the
 * artist made. Typesetting to a fraction of the box — 78% wide, 80% tall —
 * fits the middle of an ellipse and nothing else: the first and last lines
 * poke out of a round balloon, and a tall thin one holds two words a line.
 * A letterer shapes the block to the balloon; this measures the balloon so
 * the block can be shaped to it.
 *
 * The body centre is found by eroding the mask until tails and spikes have
 * gone, so a tail off to one side does not drag the text toward it. Each
 * row then reports the width available for a line centred on that column:
 * the run of interior the column falls in, cut to the nearer side, because
 * the lines are centred and a wide left half is no use to a line that
 * would overflow on the right.
 */
class BalloonShape private constructor(
    val rows: Int,
    /** Body centre column, in mask cells. */
    val centerX: Float,
    /** Body centre row, in mask cells. */
    val centerY: Float,
    /** Per row: interior width available to a line centred on [centerX], in cells; 0 where none. */
    val span: FloatArray,
) {

    /** The widest row. */
    val maxSpan: Float = span.maxOrNull() ?: 0f

    /**
     * The narrowest span over the rows a line covers, [fromRow] to [toRow]
     * in fractional cells, or 0 when any of those rows has no room.
     */
    fun capOver(fromRow: Float, toRow: Float): Float {
        val a = floor(fromRow).toInt().coerceIn(0, rows - 1)
        val b = (ceil(toRow).toInt() - 1).coerceIn(a, rows - 1)
        var cap = Float.MAX_VALUE
        for (y in a..b) {
            val s = span[y]
            if (s <= 0f) return 0f
            if (s < cap) cap = s
        }
        return cap
    }

    companion object {

        /** Share of the mask's narrow dimension eroded away to find the body. */
        private const val BODY_ERODE = 0.12f

        /** Null when the mask has no interior to speak of. */
        fun of(mask: BooleanArray, w: Int, h: Int): BalloonShape? {
            if (w < 2 || h < 2 || mask.size < w * h) return null
            val dist = distance(mask, w, h)
            var r = (min(w, h) * BODY_ERODE).toInt().coerceAtLeast(1)
            var cx = 0.0
            var cy = 0.0
            var n = 0
            // Erode until something survives; the whole mask is the body
            // when nothing does.
            while (r >= 0 && n == 0) {
                cx = 0.0
                cy = 0.0
                n = 0
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val i = y * w + x
                        if (mask[i] && dist[i] > r) {
                            cx += x + 0.5
                            cy += y + 0.5
                            n++
                        }
                    }
                }
                r--
            }
            if (n == 0) return null
            val centerX = (cx / n).toFloat()
            val centerY = (cy / n).toFloat()
            val col = centerX.toInt().coerceIn(0, w - 1)
            val span = FloatArray(h)
            for (y in 0 until h) {
                val row = y * w
                if (!mask[row + col]) continue
                var left = col
                while (left > 0 && mask[row + left - 1]) left--
                var right = col
                while (right < w - 1 && mask[row + right + 1]) right++
                // Symmetric about the centre column: the nearer edge bounds
                // a centred line on both sides.
                val half = min(centerX - left, right + 1 - centerX)
                span[y] = max(0f, 2f * half)
            }
            return BalloonShape(h, centerX, centerY, span)
        }

        /** 4-neighbour distance from each mask cell to the nearest cell outside it. */
        private fun distance(mask: BooleanArray, w: Int, h: Int): IntArray {
            val inf = w + h
            val d = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!mask[i]) continue
                    var best = min(x + 1, min(y + 1, min(w - x, h - y)))
                    if (x > 0) best = min(best, d[i - 1] + 1)
                    if (y > 0) best = min(best, d[i - w] + 1)
                    d[i] = min(best, inf)
                }
            }
            for (y in h - 1 downTo 0) {
                for (x in w - 1 downTo 0) {
                    val i = y * w + x
                    if (!mask[i]) continue
                    var best = d[i]
                    if (x < w - 1) best = min(best, d[i + 1] + 1)
                    if (y < h - 1) best = min(best, d[i + w] + 1)
                    d[i] = best
                }
            }
            return d
        }
    }
}
