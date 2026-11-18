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
        // A sharp horizontal texture can change completely between adjacent
        // scanlines. Vote for every pixel offset that actually matches a
        // sampled row, so an offset between four-pixel steps cannot be lost.
        val votes = IntArray(reach * 2 + 1)
        val prefix = IntArray(h + 1)
        for (y in 0 until h) {
            prefix[y + 1] = prefix[y] + if (y % COARSE_ROWS == 0 && drawn[y]) 1 else 0
        }
        if (prefix[h] < MIN_ROWS / COARSE_ROWS) return null
        fun evidence(delta: Int): Int =
            prefix[minOf(h, h - delta)] - prefix[maxOf(0, -delta)]
        val decisive = IntArray(votes.size) { i ->
            val n = evidence(i - reach)
            if (n < MIN_ROWS / COARSE_ROWS) Int.MAX_VALUE else (n * RIVAL).toInt() + 1
        }
        val index = earlier.rowIndex
        var firstStrong = reach
        var lastStrong = -reach
        for (y in 0 until h step COARSE_ROWS) {
            if (!drawn[y]) continue
            for (position in index.nearestBucket(bands, y)) {
                val otherY = index.rows[position]
                val delta = otherY - y
                if (delta !in -reach..reach || !sameRow(y, earlier, otherY)) continue
                val i = delta + reach
                if (++votes[i] == decisive[i]) {
                    firstStrong = minOf(firstStrong, delta)
                    lastStrong = maxOf(lastStrong, delta)
                    // Even a perfect offset cannot beat both of these rivals.
                    if (lastStrong - firstStrong > COARSE_STEP * 4) return null
                }
            }
        }
        fun score(delta: Int): Float {
            val n = evidence(delta)
            return if (n < MIN_ROWS / COARSE_ROWS) 0f else votes[delta + reach].toFloat() / n
        }
        var best = -reach
        var bestScore = 0f
        for (d in -reach..reach) {
            val share = score(d)
            if (share > bestScore) {
                best = d
                bestScore = share
            }
        }
        if (bestScore == 0f) return null
        // Recheck the best offset and its immediate neighbours with every
        // drawn row. A screen with repeated bands must still have a single,
        // decisive offset before any earlier lettering moves with it.
        var exact = best
        var exactShare = 0f
        for (d in maxOf(-reach, best - COARSE_STEP)..minOf(reach, best + COARSE_STEP)) {
            var count = 0
            var matches = 0
            for (y in maxOf(0, -d) until minOf(h, h - d)) {
                if (!drawn[y]) continue
                count++
                if (sameRow(y, earlier, y + d)) matches++
            }
            if (count < MIN_ROWS) continue
            val share = matches.toFloat() / count
            if (share > exactShare) {
                exact = d
                exactShare = share
            }
        }
        if (exactShare < MIN_MATCH) return null
        for (d in -reach..reach) {
            if (abs(d - exact) > COARSE_STEP * 2 && score(d) > exactShare * RIVAL) return null
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

    /**
     * Whether rows [top] until [bottom] of this frame still show what
     * [earlier] showed [d] rows further down, [d] being the scroll
     * [scrolledFrom] measured. What is drawn there moved with the page; a
     * bar that slid over those rows since, or art that changed under them,
     * does not line up. False when nothing is drawn there to tell by.
     */
    fun keeps(earlier: ScrollMatch, d: Int, top: Int, bottom: Int): Boolean {
        if (earlier.w != w || earlier.h != h) return false
        var n = 0
        var hit = 0
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(h)) {
            val y2 = y + d
            if (y2 !in 0 until h || !drawn[y]) continue
            n++
            if (sameRow(y, earlier, y2)) hit++
        }
        return n > 0 && hit >= n * KEPT
    }

    // Indexed only for a frame a later stop actually compares against.
    private val rowIndex by lazy { RowIndex(bands, h) }

    private class RowIndex(values: IntArray, height: Int) {
        private val boundaries = IntArray(BANDS * 257)
        val rows = IntArray(BANDS * height)

        init {
            for (b in 0 until BANDS) {
                val base = b * 257
                boundaries[base] = b * height
                for (y in 0 until height) boundaries[base + values[y * BANDS + b] + 1]++
                for (v in 1..256) boundaries[base + v] += boundaries[base + v - 1]
            }
            val next = boundaries.copyOf()
            for (y in 0 until height) for (b in 0 until BANDS) {
                rows[next[b * 257 + values[y * BANDS + b]]++] = y
            }
        }

        /** The least common of the six band values, including every value within tolerance. */
        fun nearestBucket(values: IntArray, y: Int): IntRange {
            var first = 0
            var size = Int.MAX_VALUE
            for (b in 0 until BANDS) {
                val base = b * 257
                val v = values[y * BANDS + b]
                val start = boundaries[base + (v - ROW_TOLERANCE).coerceAtLeast(0)]
                val end = boundaries[base + (v + ROW_TOLERANCE + 1).coerceAtMost(256)]
                if (end - start < size) {
                    first = start
                    size = end - start
                }
            }
            return first until first + size
        }
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

        /** Share of the drawn rows in a stretch that must line up for [keeps]. */
        private const val KEPT = 0.8f

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
            // Bitmap.getPixels crosses into Android. Copy a modest strip per
            // call and reuse the sample-to-band mapping for every scanline.
            val rowsPerRead = minOf(h, 32, (65_536 / w).coerceAtLeast(1))
            val pixels = IntArray(w * rowsPerRead)
            val sampleBands = IntArray((w - 1) / step + 1) { ((it * step).toLong() * BANDS / w).toInt() }
            val counts = IntArray(BANDS)
            for (band in sampleBands) counts[band]++
            val sums = IntArray(BANDS)
            for (top in 0 until h step rowsPerRead) {
                val rows = minOf(rowsPerRead, h - top)
                bitmap.getPixels(pixels, 0, w, 0, top, w, rows)
                for (dy in 0 until rows) {
                    sums.fill(0)
                    var pos = dy * w
                    for (band in sampleBands) {
                        val p = pixels[pos]
                        sums[band] += ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                        pos += step
                    }
                    val dest = (top + dy) * BANDS
                    for (b in 0 until BANDS) bands[dest + b] = if (counts[b] == 0) 0 else sums[b] / counts[b]
                }
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
