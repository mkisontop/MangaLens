package app.mangalens.capture

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pixel-level scroll odometry over a manhwa strip.
 *
 * [FrameStability.verticalShift] answers "has the page drifted" on a 96-row
 * thumbnail — enough to know overlays are stale, an order of magnitude too
 * coarse to move them. Sticky cards need the opposite trade: the scroll
 * measured in source pixels, frame after frame, cheap enough for the capture
 * loop. The idea carries over unchanged — scrolled content is self-similar
 * under vertical translation, so aligning row brightness profiles finds the
 * scroll — but the profile is taken at [BIN]-pixel resolution straight from
 * the frame, and the integer alignment is refined by parabolic interpolation,
 * so the answer is a displacement a card can actually be moved by.
 *
 * Profiles are opaque to callers: produce them with [profile], compare them
 * with [delta], and only compare profiles taken over the same geometry —
 * matching them across a rotation or resize would align coincidence.
 *
 * Pure computation over arrays with no shared scratch state, so any thread
 * may call it.
 */
object ScrollTracker {

    /** Source pixels per profile bin. */
    const val BIN = 4

    /** Column sampling stride; a profile needs each band's brightness, not its detail. */
    private const val COL_STRIDE = 8

    /** Fewest usable bands two profiles must overlap on to be judged at all. */
    private const val MIN_BANDS = 16

    /**
     * Centered-brightness change below which a band is treated as not having
     * moved between two frames — capture noise and compression shimmer, not
     * content. Bands under this floor are the static furniture (chrome, our
     * button, unmoving art) and are kept out of the shift scoring entirely.
     */
    private const val CHANGE_FLOOR = 4.0

    /**
     * Profile contrast (mean absolute deviation) below this is a blank gap,
     * motion blur, or capture jitter — nothing an alignment could hold on to.
     */
    private const val MIN_ACTIVITY = 2.0

    /** How much better than "didn't move" the winning shift must fit. */
    private const val DECISIVE_RATIO = 0.6

    /** Locks whose alignment explains less of the contrast than this are refused. */
    private const val MIN_CONFIDENCE = 0.4f

    /**
     * Column-mean luminance per [BIN]-pixel row band of the frame's logical
     * [srcW] x [srcH] area. The capture buffer is stride-padded on the right,
     * and the padding holds whatever the producer last left there — nothing
     * past [srcW] is ever read. A trailing partial band is dropped rather
     * than averaged short.
     *
     * Our own cards are captured into the frame at fixed screen positions and
     * would anchor any alignment to "didn't move", so the caller passes them
     * as [exclude]. A band more than half covered reports -1 — unusable,
     * skipped by [delta] on whichever side it appears — while a band less
     * covered simply drops the excluded columns, so a card narrower than half
     * the screen costs no band at all.
     *
     * Columns are subsampled every [COL_STRIDE] px and read as whole-column
     * strips, so only an eighth of the frame crosses the JNI boundary; a
     * 1080x1920 frame profiles in a couple of milliseconds, which is what
     * lets the capture loop afford this every sampled frame.
     */
    fun profile(
        bitmap: Bitmap,
        exclude: List<Rect> = emptyList(),
        srcW: Int = bitmap.width,
        srcH: Int = bitmap.height,
    ): IntArray {
        val w = srcW.coerceAtMost(bitmap.width)
        val h = srcH.coerceAtMost(bitmap.height)
        if (w <= 0 || h < BIN) return IntArray(0)
        val bands = h / BIN

        val cols = (w / COL_STRIDE).coerceAtLeast(1)
        val colX = IntArray(cols) { (it * COL_STRIDE + COL_STRIDE / 2).coerceAtMost(w - 1) }
        val px = IntArray(cols * h)
        for (c in 0 until cols) bitmap.getPixels(px, c * h, 1, colX[c], 0, 1, h)

        val out = IntArray(bands)
        val active = ArrayList<Rect>(exclude.size)
        for (b in 0 until bands) {
            val y0 = b * BIN
            val y1 = y0 + BIN
            active.clear()
            for (r in exclude) if (r.top < y1 && r.bottom > y0 && r.right > 0 && r.left < w) active.add(r)

            var sum = 0L
            var kept = 0
            for (c in 0 until cols) {
                val x = colX[c]
                var covered = false
                for (r in active) if (x >= r.left && x < r.right) {
                    covered = true
                    break
                }
                if (covered) continue
                kept++
                val base = c * h
                for (y in y0 until y1) {
                    val p = px[base + y]
                    sum += ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                }
            }
            out[b] = if (kept * 2 < cols) -1 else (sum / (kept.toLong() * BIN)).toInt()
        }
        return out
    }

    /**
     * One believed alignment. [deltaPx] is negative when the reader scrolled
     * down and the content moved up — [FrameStability.verticalShift]'s
     * convention, in source pixels. [confidence] is how much of the profiles'
     * own contrast the alignment explains: 1 when the shifted profiles agree
     * exactly, falling toward 0 as the residual left after aligning
     * approaches the signal itself. Locks under [MIN_CONFIDENCE] are never
     * returned, so a caller may weight by confidence but never needs to
     * re-reject.
     */
    data class Lock(val deltaPx: Int, val confidence: Float)

    /**
     * The vertical displacement, in source pixels, moving [prev] onto [cur],
     * searched within guessPx ± maxShiftPx — a velocity guess from recent
     * locks buys reach without widening the window noise can win in.
     *
     * Null means no confident lock, and null is the honest answer far more
     * often than 0 would be: a swapped page has no alignment to find, motion
     * blur flattens the profiles, a featureless white gap aligns anywhere
     * equally well. The caller coasts or re-anchors on null; a guessed
     * displacement here walks every card off its balloon at once.
     *
     * Robustness follows [FrameStability.verticalShift]: both profiles are
     * mean-centered over their usable bands, so auto-dim and a reader's night
     * filter cancel; each must carry a minimum of contrast; every candidate
     * shift is scored only on bands usable on both sides, and only when
     * enough of them overlap; and the winner must beat "didn't move" by a
     * decisive margin — noise never clears it, and a page swap has nothing to
     * clear it with. A winner *at* zero displacement has nothing to beat and
     * is accepted on fit alone, so a still page stays a confident Lock(0)
     * instead of a lost one. The surviving integer-bin argmin is then
     * refined across (best-1, best, best+1) by parabolic interpolation and
     * converted to pixels: sub-bin truth lands between two integer bins, and
     * the two neighbors' scores say where.
     */
    fun delta(prev: IntArray, cur: IntArray, maxShiftPx: Int, guessPx: Int = 0): Lock? {
        val n = prev.size
        if (n == 0 || cur.size != n) return null
        val minBands = maxOf(MIN_BANDS, n / 4)

        val pv = DoubleArray(n)
        val cv = DoubleArray(n)
        val pOk = BooleanArray(n)
        val cOk = BooleanArray(n)
        val actP = center(prev, pv, pOk, minBands)
        val actC = center(cur, cv, cOk, minBands)
        if (actP < MIN_ACTIVITY || actC < MIN_ACTIVITY) return null
        val act = (actP + actC) / 2

        // A captured frame is never all strip: the status bar, the reader's
        // toolbar and our own floating button ride every frame without
        // moving, and at full weight those bands vote "no movement" hard
        // enough to out-argue the content and drop the lock mid-scroll. Only
        // the bands that actually changed between the two frames belong to
        // the moving content, so only they get to score — and if next to
        // nothing changed, the screen simply is still.
        val moved = BooleanArray(n)
        var movedCount = 0
        for (y in 0 until n) {
            if (pOk[y] && cOk[y] && abs(pv[y] - cv[y]) > CHANGE_FLOOR) {
                moved[y] = true
                movedCount++
            }
        }
        if (movedCount < maxOf(MIN_BANDS / 2, n / 24)) return Lock(0, 1f)
        val minMoved = maxOf(MIN_BANDS / 2, movedCount / 3)

        fun score(s: Int): Double {
            var sum = 0.0
            var k = 0
            val yFrom = maxOf(0, -s)
            val yTo = minOf(n, n - s)
            for (y in yFrom until yTo) {
                if (!moved[y] || !pOk[y] || !cOk[y + s]) continue
                sum += abs(pv[y] - cv[y + s])
                k++
            }
            return if (k >= minMoved) sum / k else Double.MAX_VALUE
        }

        val lo = Math.floorDiv(guessPx - maxShiftPx, BIN).coerceAtLeast(-(n - 1))
        val hi = Math.floorDiv(guessPx + maxShiftPx + BIN - 1, BIN).coerceAtMost(n - 1)
        if (lo > hi) return null

        var best = lo
        var bestScore = Double.MAX_VALUE
        for (s in lo..hi) {
            val sc = score(s)
            if (sc < bestScore) {
                bestScore = sc
                best = s
            }
        }
        if (bestScore == Double.MAX_VALUE) return null

        if (best != 0) {
            val zero = score(0)
            if (zero == Double.MAX_VALUE || bestScore >= zero * DECISIVE_RATIO) return null
        }
        val confidence = (1.0 - bestScore / act).toFloat().coerceIn(0f, 1f)
        if (confidence < MIN_CONFIDENCE) return null

        val fm = score(best - 1)
        val fp = score(best + 1)
        var frac = 0.0
        if (fm != Double.MAX_VALUE && fp != Double.MAX_VALUE) {
            val denom = fm - 2 * bestScore + fp
            if (denom > 1e-9) frac = (0.5 * (fm - fp) / denom).coerceIn(-0.5, 0.5)
        }
        return Lock(((best + frac) * BIN).roundToInt(), confidence)
    }

    /**
     * Mean-centers the usable bands of [src] into [out], marking them in
     * [ok], and reports their mean absolute deviation — the contrast the
     * matcher has to hold on to. Negative when fewer than [minBands] bands
     * are usable, which no activity threshold accepts.
     */
    private fun center(src: IntArray, out: DoubleArray, ok: BooleanArray, minBands: Int): Double {
        var sum = 0L
        var k = 0
        for (i in src.indices) {
            if (src[i] >= 0) {
                ok[i] = true
                sum += src[i]
                k++
            }
        }
        if (k < minBands) return -1.0
        val mean = sum.toDouble() / k
        var dev = 0.0
        for (i in src.indices) {
            if (ok[i]) {
                out[i] = src[i] - mean
                dev += abs(out[i])
            }
        }
        return dev / k
    }
}
