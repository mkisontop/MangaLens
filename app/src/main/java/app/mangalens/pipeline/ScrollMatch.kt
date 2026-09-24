package app.mangalens.pipeline

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * How far a frame has scrolled since an earlier one, to the pixel, from the
 * frames alone.
 *
 * A webtoon stop that moved the page a little shows mostly what the last
 * stop showed. Knowing exactly how far it moved says which strip of the
 * screen is new — and only that strip needs reading; everything above it
 * was read already and is repainted from memory.
 *
 * Each row is summarised by the mean brightness of a few vertical bands.
 * Scrolled content matches itself row for row at the true offset, and
 * almost nowhere else — except rows of blank paper, which match anywhere,
 * so only rows with something drawn on them are counted. A fixed browser
 * bar does not move with the page and never matches at the true offset;
 * a share of rows is allowed to disagree for it.
 */
internal class ScrollMatch private constructor(
    val w: Int,
    val h: Int,
    private val bands: IntArray,
    private val drawn: BooleanArray,
) {

    /**
     * The scroll from [earlier] to this frame: row y here shows row y + d of
     * [earlier], so d is positive when the reader scrolled down. 0 when the
     * page has not moved; null when the frames do not show one page shifted
     * — a new page, a zoom, a different app — or show too little to tell.
     */
    fun scrolledFrom(earlier: ScrollMatch): Int? {
        if (earlier.w != w || earlier.h != h) return null
        val reach = (h * MAX_SCROLL).toInt()
        fun share(d: Int, step: Int): Float {
            var n = 0
            var hit = 0
            var y = 0
            while (y < h) {
                val y2 = y + d
                if (y2 in 0 until h && drawn[y]) {
                    n++
                    if (sameRow(y, earlier, y2)) hit++
                }
                y += step
            }
            return if (n < MIN_ROWS / step.coerceAtLeast(1) || n == 0) 0f else hit.toFloat() / n
        }
        val offsets = (-reach..reach step COARSE_STEP).toList()
        val coarse = FloatArray(offsets.size) { share(offsets[it], COARSE_ROWS) }
        var best = 0
        var bestShare = -1f
        for (i in offsets.indices) {
            if (coarse[i] > bestShare) {
                bestShare = coarse[i]
                best = offsets[i]
            }
        }
        // To the pixel, around the coarse answer.
        var exact = best
        var exactShare = -1f
        for (e in best - COARSE_STEP..best + COARSE_STEP) {
            val s = share(e, 1)
            if (s > exactShare) {
                exactShare = s
                exact = e
            }
        }
        if (exactShare < MIN_MATCH) return null
        // Decisive: no other offset explains the frame nearly as well.
        for (i in offsets.indices) {
            if (abs(offsets[i] - exact) > COARSE_STEP * 2 && coarse[i] > exactShare * RIVAL) return null
        }
        return exact
    }

    /**
     * True when this frame shows exactly what [earlier] showed, unmoved: a
     * page turned back to, or a scroll back to the same spot. One offset is
     * checked, so it is cheap enough to try against several earlier frames.
     */
    fun unmovedFrom(earlier: ScrollMatch): Boolean {
        if (earlier.w != w || earlier.h != h) return false
        var n = 0
        var hit = 0
        for (y in 0 until h step 2) {
            if (!drawn[y]) continue
            n++
            if (sameRow(y, earlier, y)) hit++
        }
        return n * 2 >= MIN_ROWS && hit >= n * SAME_PAGE
    }

    private fun sameRow(y: Int, other: ScrollMatch, y2: Int): Boolean {
        for (b in 0 until BANDS) {
            if (abs(bands[y * BANDS + b] - other.bands[y2 * BANDS + b]) > ROW_TOLERANCE) return false
        }
        return true
    }

    companion object {
        private const val BANDS = 6

        /** Pixels sampled across a row. */
        private const val SAMPLES_ACROSS = 192

        /** Brightness difference, per band mean, within which two rows are the same row. */
        private const val ROW_TOLERANCE = 3

        /** A row whose bands differ by less than this, and match their neighbours, is blank paper. */
        private const val FLAT = 4

        /** Share of the drawn rows that must match, unmoved, for a frame to be one shown before. */
        private const val SAME_PAGE = 0.97f

        /** Share of the drawn rows that must line up at the offset found. */
        private const val MIN_MATCH = 0.7f

        /** Another offset scoring this close to the best one makes the answer a guess. */
        private const val RIVAL = 0.6f

        /** Drawn rows needed in the overlap to judge at all. */
        private const val MIN_ROWS = 60

        /** Furthest scroll searched, as a share of the screen. */
        private const val MAX_SCROLL = 0.85f

        private const val COARSE_STEP = 4
        private const val COARSE_ROWS = 3

        fun of(bitmap: Bitmap): ScrollMatch {
            val w = bitmap.width
            val h = bitmap.height
            val bands = IntArray(h * BANDS)
            val step = (w / SAMPLES_ACROSS).coerceAtLeast(1)
            val row = IntArray(w)
            val sums = IntArray(BANDS)
            val counts = IntArray(BANDS)
            for (y in 0 until h) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                sums.fill(0)
                counts.fill(0)
                var x = 0
                while (x < w) {
                    val p = row[x]
                    val b = x * BANDS / w
                    sums[b] += ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    counts[b]++
                    x += step
                }
                for (b in 0 until BANDS) bands[y * BANDS + b] = if (counts[b] == 0) 0 else sums[b] / counts[b]
            }
            val drawn = BooleanArray(h)
            for (y in 0 until h) {
                var lo = 255
                var hi = 0
                var change = 0
                for (b in 0 until BANDS) {
                    val v = bands[y * BANDS + b]
                    lo = minOf(lo, v)
                    hi = maxOf(hi, v)
                    if (y > 0) change = maxOf(change, abs(v - bands[(y - 1) * BANDS + b]))
                    if (y + 1 < h) change = maxOf(change, abs(v - bands[(y + 1) * BANDS + b]))
                }
                drawn[y] = hi - lo >= FLAT || change >= FLAT
            }
            return ScrollMatch(w, h, bands, drawn)
        }
    }
}
