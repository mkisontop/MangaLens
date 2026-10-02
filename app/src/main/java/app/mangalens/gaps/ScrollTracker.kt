package app.mangalens.gaps

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Measures how far a vertical scroll moved the page between two frames, to the pixel.
 *
 * The shade has to ride the page, and the page's motion is only visible in the pixels:
 * there is no scroll event to listen to in somebody else's browser. A frame is boiled down
 * to its *row profile* — for each row, how much the brightness changes across a few dozen
 * sampled columns — and two profiles are slid against each other until they agree. A
 * profile is a one-dimensional picture of where the edges are, and the edges of a manhwa
 * strip are many and sharp: panel borders, balloon outlines, the cut between art and gutter.
 *
 * Two properties make this trustworthy where plain differencing is not:
 *
 *  - It looks at *gradients*, not brightness. The shade changes how bright a gutter is, but
 *    never where its edges are, so the profile of a shaded frame and of the same frame
 *    unshaded line up even though their pixels differ.
 *  - It says when it does not know. A frame with no edges (all gutter) gives a flat profile
 *    and any shift fits; the answer then is "coast": no measurement, keep the speed. A page
 *    swap fits no shift at all, and is reported as a jump.
 *
 * A positive result means the content moved down the screen.
 */
class ScrollTracker(private val width: Int, private val height: Int, private val cols: Int = 32) {

    class Match(
        /** Rows the content moved down; negative when it moved up. */
        val dy: Int,
        /** 0..1: how much better the best shift fits than any distinct alternative. */
        val confidence: Float,
        /** Best mismatch relative to the profile's own size: small when the frames are the same page. */
        val fit: Float,
        /** Mean edge strength per row: near zero on a frame with nothing to hold on to. */
        val activity: Float,
    ) {
        /** The frames show the same content, shifted by [dy]. */
        val measured: Boolean get() = confidence >= MIN_CONFIDENCE && fit <= MAX_FIT && activity >= MIN_ACTIVITY

        /**
         * The frames show different content: a page swap, or a jump past the search range.
         * Genuine shifts score 0.85 and up on [confidence]; a different page scores nothing
         * like it. Between the two is repetition — screentone, stripes — which is ambiguous,
         * not different, and is left to coast.
         */
        val jumped: Boolean get() = activity >= MIN_ACTIVITY && confidence < JUMP_CONFIDENCE && fit >= JUMP_FIT
    }

    private val xs = IntArray(cols) { ((it + 0.5f) * width / cols).toInt().coerceIn(0, width - 1) }

    /** Rows excluded per sampled column: the app's own controls, which sit still while the page moves. */
    @Volatile
    private var excluded: Array<BooleanArray?> = arrayOfNulls(cols)

    /** Declares screen rectangles ([x0, y0, x1, y1]) whose pixels are not the page's. */
    fun setExclusions(rects: List<IntArray>) {
        val out = arrayOfNulls<BooleanArray>(cols)
        for (r in rects) {
            for (j in 0 until cols) {
                val x = xs[j]
                if (x < r[0] || x >= r[2]) continue
                val rows = out[j] ?: BooleanArray(height).also { out[j] = it }
                for (y in max(0, r[1]) until min(height, r[3])) rows[y] = true
            }
        }
        excluded = out
    }

    /**
     * The row profile of [src] over rows [y0, y1).
     *
     * With a [style], an edge between plain paper and *shaded* paper is left out. Nothing of
     * the kind exists in the page — both sides are paper — so it can only be the overlay's own
     * boundary. Counted, it would feed the tracker its own output: on a screen of nothing but
     * gutter, the overlay's edges are the only edges there are, the tracker would measure the
     * overlay's motion rather than the page's, and the overlay's motion is set by that very
     * measurement. A loop like that runs away.
     */
    fun profile(src: PixelSource, y0: Int = 0, y1: Int = height, style: ShadeStyle? = null): IntArray {
        val p = IntArray(height)
        val lo = y0.coerceIn(0, height)
        val hi = y1.coerceIn(lo, height)
        if (hi - lo < 3) return p
        for (j in 0 until cols) {
            val x = xs[j]
            val ex = excluded[j]
            var pa = src.pixel(x, lo)
            var pc = src.pixel(x, lo + 1)
            for (y in lo + 1 until hi - 1) {
                val pb = src.pixel(x, y + 1)
                if (ex == null || (!ex[y - 1] && !ex[y] && !ex[y + 1])) {
                    if (style == null || !overlayEdge(pa, pb, style)) {
                        p[y] += abs(((pb ushr 8) and 0xFF) - ((pa ushr 8) and 0xFF))
                    }
                }
                pa = pc
                pc = pb
            }
        }
        return p
    }

    /** One pixel plain paper, the other paper under the shade. */
    private fun overlayEdge(a: Int, b: Int, style: ShadeStyle): Boolean {
        val aPaper = (a and 0xF0F0F0) == 0xF0F0F0
        val bPaper = (b and 0xF0F0F0) == 0xF0F0F0
        if (aPaper == bPaper) return false
        return if (aPaper) style.isShadedPaper(b) else style.isShadedPaper(a)
    }

    /**
     * Finds the shift that carries [prev] onto [cur]. The whole range is searched, coarsely —
     * every second row, every second shift — with a faint pull toward [centre], the shift the
     * caller expects: on periodic art (screentone, stripes) several shifts fit equally, and the
     * expected one should win the tie. The winner is then refined to the exact row.
     *
     * The confidence is read from the coarse landscape: the best fit against the best fit that
     * is not a neighbour of it. A true shift stands out from everything else by a wide margin;
     * a page swap has no standout at all.
     */
    fun match(prev: IntArray, cur: IntArray, y0: Int, y1: Int, centre: Int): Match {
        val n = y1 - y0
        val maxShift = n / 3
        var activity = 0.0
        for (y in y0 until y1) activity += prev[y]
        activity /= max(1, n)
        if (activity < MIN_ACTIVITY) return Match(centre.coerceIn(-maxShift, maxShift), 0f, Float.MAX_VALUE, activity.toFloat())

        val count = (2 * maxShift) / COARSE + 1
        val costs = DoubleArray(count) { Double.MAX_VALUE }
        for (idx in 0 until count) {
            val s = -maxShift + idx * COARSE
            val from = max(y0, y0 - s)
            val to = min(y1, y1 - s)
            if (to - from < n / 4) continue
            var sum = 0L
            var cnt = 0
            var y = from
            while (y < to) {
                sum += abs(prev[y] - cur[y + s])
                cnt++
                y += COARSE
            }
            costs[idx] = sum.toDouble() / cnt
        }
        // The pull toward the expectation: faint, so that it only ever breaks ties.
        val pull = PRIOR_PER_ROW * activity
        var bestIdx = 0
        var bestBiased = Double.MAX_VALUE
        for (idx in 0 until count) {
            if (costs[idx] == Double.MAX_VALUE) continue
            val s = -maxShift + idx * COARSE
            val biased = costs[idx] + pull * abs(s - centre)
            if (biased < bestBiased) {
                bestBiased = biased
                bestIdx = idx
            }
        }
        if (bestBiased == Double.MAX_VALUE) return Match(centre, 0f, Float.MAX_VALUE, activity.toFloat())
        val coarseBest = -maxShift + bestIdx * COARSE

        // Exact refinement around the coarse winner.
        var best = coarseBest
        var bestCost = Double.MAX_VALUE
        for (s in coarseBest - COARSE..coarseBest + COARSE) {
            val from = max(y0, y0 - s)
            val to = min(y1, y1 - s)
            if (s < -maxShift || s > maxShift || to - from < n / 4) continue
            var sum = 0L
            for (y in from until to) sum += abs(prev[y] - cur[y + s])
            val cost = sum.toDouble() / (to - from)
            if (cost < bestCost - 1e-9 || (abs(cost - bestCost) <= 1e-9 && abs(s - centre) < abs(best - centre))) {
                bestCost = cost
                best = s
            }
        }

        // Standout: best coarse fit in the neighbourhood against the best one outside it.
        var near = Double.MAX_VALUE
        var far = Double.MAX_VALUE
        for (idx in 0 until count) {
            val c = costs[idx]
            if (c == Double.MAX_VALUE) continue
            val s = -maxShift + idx * COARSE
            if (abs(s - best) <= STANDOUT_RADIUS) {
                if (c < near) near = c
            } else if (c < far) {
                far = c
            }
        }
        val confidence = if (far == Double.MAX_VALUE || far <= 1e-9) 0.0 else (1.0 - min(near, bestCost) / far).coerceIn(0.0, 1.0)
        return Match(best, confidence.toFloat(), (bestCost / (activity + 1.0)).toFloat(), activity.toFloat())
    }

    companion object {
        const val MIN_CONFIDENCE = 0.25f

        /** Mismatch, relative to the profile's size, up to which two profiles are the same page. */
        const val MAX_FIT = 0.60f

        private const val COARSE = 2

        /** Shifts this close to the winner are the same alignment, not a rival. */
        private const val STANDOUT_RADIUS = 6

        /** Cost of an expectation missed by one row, relative to the profile's own size. */
        private const val PRIOR_PER_ROW = 0.0015

        /** Below this confidence, with a poor fit, the frames are not of the same content. */
        const val JUMP_CONFIDENCE = 0.10f
        const val JUMP_FIT = 0.45f

        /** Mean edge strength per row below which a frame has no structure to measure by. */
        const val MIN_ACTIVITY = 6f
    }
}
