package app.mangalens.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs

/**
 * Cheap frame-difference detector: frames are downscaled to a tiny grayscale
 * thumbnail and compared by mean absolute difference. Used to know when the
 * reader has stopped scrolling (stable page -> OCR) and when they start again
 * (motion -> clear overlays).
 *
 * Not thread-safe — call only from the capture thread. The 96x96 scratch
 * bitmap is reused across frames so steady-state motion detection allocates
 * nothing but the returned thumb array.
 */
object FrameStability {

    const val SIZE = 96

    private var thumbBmp: Bitmap? = null
    private var thumbCanvas: Canvas? = null
    private val srcRect = Rect()
    private val dstRect = Rect(0, 0, SIZE, SIZE)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * [srcW]/[srcH] bound the logical frame inside a possibly stride-padded
     * source bitmap, so padding columns never leak into the comparison.
     */
    fun grayThumb(src: Bitmap, srcW: Int = src.width, srcH: Int = src.height): IntArray {
        val bmp = thumbBmp ?: Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also {
            thumbBmp = it
            thumbCanvas = Canvas(it)
        }
        srcRect.set(0, 0, srcW.coerceAtMost(src.width), srcH.coerceAtMost(src.height))
        thumbCanvas?.drawBitmap(src, srcRect, dstRect, paint)
        val px = IntArray(SIZE * SIZE)
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        for (i in px.indices) {
            val p = px[i]
            px[i] = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        return px
    }

    /**
     * Like [grayThumb], but with no shared scratch state — safe to call from
     * any thread, at the cost of allocating its own 96x96 working bitmap.
     * Used once per translation pass to remember the page the overlays were
     * written for; the capture loop keeps the reusing variant.
     */
    fun grayThumbOf(src: Bitmap, srcW: Int = src.width, srcH: Int = src.height): IntArray {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawBitmap(
            src,
            Rect(0, 0, srcW.coerceAtMost(src.width), srcH.coerceAtMost(src.height)),
            Rect(0, 0, SIZE, SIZE),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        val px = IntArray(SIZE * SIZE)
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        bmp.recycle()
        for (i in px.indices) {
            val p = px[i]
            px[i] = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        return px
    }

    fun meanDiff(a: IntArray?, b: IntArray?): Double {
        if (a == null || b == null || a.size != b.size) return 255.0
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum.toDouble() / a.size
    }

    /**
     * Vertical displacement between two settled frames, in thumb rows
     * (negative when the reader scrolled down and the content moved up).
     * Returns 0 when the frames don't show the same content shifted — still
     * pages, page swaps, brightness changes, or too little unmasked content
     * to judge by.
     *
     * This is the detector for what [meanDiff] and [changedFraction] are both
     * blind to on a manhwa strip: a slow scroll moves mostly-white content
     * over mostly-white content, so consecutive frames barely differ — and
     * the balloon that slides in under a still-displayed card changes only
     * [mask]ed cells, which cumulative comparison must ignore. But scrolled
     * content is *self-similar under translation*, so matching the two
     * frames' row brightness profiles finds the scroll directly, using
     * exactly the cells the cards do not cover.
     */
    fun verticalShift(
        base: IntArray?,
        cur: IntArray?,
        mask: BooleanArray? = null,
        maxShift: Int = SIZE / 4,
    ): Int {
        if (base == null || cur == null || base.size != SIZE * SIZE || cur.size != SIZE * SIZE) return 0

        // Row brightness profiles over unmasked cells; a row mostly hidden by
        // cards contributes nothing rather than a noisy average.
        val baseProf = DoubleArray(SIZE)
        val curProf = DoubleArray(SIZE)
        val baseOk = BooleanArray(SIZE)
        val curOk = BooleanArray(SIZE)
        for (y in 0 until SIZE) {
            var sumB = 0
            var sumC = 0
            var n = 0
            val row = y * SIZE
            for (x in 0 until SIZE) {
                val i = row + x
                if (mask != null && i < mask.size && mask[i]) continue
                sumB += base[i]
                sumC += cur[i]
                n++
            }
            if (n >= SIZE / 4) {
                baseProf[y] = sumB.toDouble() / n
                curProf[y] = sumC.toDouble() / n
                baseOk[y] = true
                curOk[y] = true
            }
        }

        // Mean-centering makes a uniform brightness change (auto-dim, a
        // reader's night filter) look like the non-event it is.
        center(baseProf, baseOk)
        center(curProf, curOk)

        fun score(s: Int): Double {
            var sum = 0.0
            var n = 0
            for (y in 0 until SIZE) {
                val y2 = y + s
                if (y2 < 0 || y2 >= SIZE || !baseOk[y] || !curOk[y2]) continue
                sum += abs(baseProf[y] - curProf[y2])
                n++
            }
            return if (n >= SIZE / 4) sum / n else Double.MAX_VALUE
        }

        val zero = score(0)
        // Unchanged rows mean an unmoved page, whatever the masked cells did.
        if (zero == Double.MAX_VALUE || zero < MIN_SHIFT_ACTIVITY) return 0

        var best = 0
        var bestScore = zero
        for (s in -maxShift..maxShift) {
            if (s == 0) continue
            val sc = score(s)
            if (sc < bestScore) {
                bestScore = sc
                best = s
            }
        }
        // Only a decisive alignment counts; noise never beats "didn't move"
        // by this margin, and a page swap has no alignment to find.
        return if (bestScore < zero * DECISIVE_RATIO) best else 0
    }

    /** Row-profile activity below this is capture jitter, not content. */
    private const val MIN_SHIFT_ACTIVITY = 0.8

    /** How much better than no-shift an alignment must fit to be believed. */
    private const val DECISIVE_RATIO = 0.6

    private fun center(prof: DoubleArray, ok: BooleanArray) {
        var sum = 0.0
        var n = 0
        for (y in prof.indices) if (ok[y]) {
            sum += prof[y]
            n++
        }
        if (n == 0) return
        val mean = sum / n
        for (y in prof.indices) if (ok[y]) prof[y] -= mean
    }

    /**
     * Share of cells that changed materially, ignoring [mask]ed ones.
     *
     * [meanDiff] answers "is the screen moving", which scrolling makes obvious
     * and a tap-to-turn page swap does not: two comic pages are mostly white,
     * so averaging the difference over every cell buries a real page change
     * under the unchanged margins. Counting how *many* cells changed separates
     * the two cleanly — a new page moves a large minority of cells a long way,
     * while sensor noise and compression move nearly all of them barely at all.
     */
    fun changedFraction(
        a: IntArray?,
        b: IntArray?,
        mask: BooleanArray? = null,
        cellDelta: Int = 16,
    ): Double {
        if (a == null || b == null || a.size != b.size) return 0.0
        var considered = 0
        var changed = 0
        for (i in a.indices) {
            if (mask != null && i < mask.size && mask[i]) continue
            considered++
            if (abs(a[i] - b[i]) > cellDelta) changed++
        }
        // Too little of the page left to judge by.
        if (considered < a.size / 8) return 0.0
        return changed.toDouble() / considered
    }

    /**
     * Marks the thumbnail cells covered by [rects], measured against a frame of
     * [srcW] x [srcH].
     *
     * Overlay cards are captured along with the page and sit exactly over the
     * balloons — the parts of a page most likely to differ from the next one.
     * Left in, they hide the very change we are trying to notice.
     */
    fun mask(rects: List<Rect>, srcW: Int, srcH: Int): BooleanArray {
        val m = BooleanArray(SIZE * SIZE)
        if (rects.isEmpty() || srcW <= 0 || srcH <= 0) return m
        for (r in rects) {
            val l = (r.left.toLong() * SIZE / srcW).toInt().coerceIn(0, SIZE - 1)
            val t = (r.top.toLong() * SIZE / srcH).toInt().coerceIn(0, SIZE - 1)
            val right = (r.right.toLong() * SIZE / srcW).toInt().coerceIn(0, SIZE - 1)
            val bottom = (r.bottom.toLong() * SIZE / srcH).toInt().coerceIn(0, SIZE - 1)
            for (y in t..bottom) {
                val row = y * SIZE
                for (x in l..right) m[row + x] = true
            }
        }
        return m
    }
}
