package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * The fill that takes lettering off open art.
 *
 * Text drawn straight onto a panel — a monologue set in columns over a
 * night sky, a caption on a landscape, a shout across the top of a page —
 * has no balloon interior to sample. A card floated over it hides the art
 * and leaves the original showing around the edges; a flat patch in the
 * average colour reads as a sticker on any background with a gradient or
 * a texture. A cleaner does what a retoucher does: continues the art
 * through the lettering. This is the cheap version of that: each line of
 * lettering is filled row by row (column by column for a horizontal line)
 * by running the colour just outside one edge across to the colour just
 * outside the other, so a gradient sky passes through unbroken and the
 * seam is nowhere the eye lands. The samples are medians over a small
 * patch, so a star, a fleck of screentone or the neighbouring column's
 * stroke cannot streak across the fill.
 *
 * Only the OCR line boxes themselves are filled — the art between two
 * columns of a block stays untouched.
 */
object ArtWipe {

    /** Per-line fills, null where the line had no surroundings to sample; and the fills' mean colour. */
    class Result(val fills: List<Bitmap?>, val meanColor: Int?)

    /**
     * Pixels grown around each OCR box, so anti-aliased glyph edges — and
     * the thin halo artists draw around light lettering on dark art — go
     * with the glyphs.
     */
    const val PAD = 3

    /** Half the run of edge samples a median is taken over along the line, so one star cannot streak across the fill. */
    private const val SMOOTH = 4

    /** How far beyond the grown box the surroundings are sampled. */
    private const val REACH = 6

    /** Half the sample patch along the line, in pixels. */
    private const val WINDOW = 2

    /** A "line" this large is a mis-grouped block, not lettering; it takes a flat wipe. */
    private const val MAX_AREA = 1_000_000L

    /** "No sample": transparent black, which no opaque page pixel packs to. */
    private const val NONE = 0

    /** The wiped rectangle for an OCR line box, grown by [PAD] and clipped to the page. */
    fun wipeRect(line: Rect, w: Int, h: Int): Rect = Rect(
        (line.left - PAD).coerceIn(0, w),
        (line.top - PAD).coerceIn(0, h),
        (line.right + PAD).coerceIn(0, w),
        (line.bottom + PAD).coerceIn(0, h),
    )

    fun prepare(bitmap: Bitmap, lines: List<Rect>): Result {
        if (lines.isEmpty()) return Result(emptyList(), null)
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var n = 0L
        val fills = lines.map { line ->
            val fill = fill(bitmap, line) ?: return@map null
            val px = IntArray(fill.width * fill.height)
            fill.getPixels(px, 0, fill.width, 0, 0, fill.width, fill.height)
            // The mean is over a subsample; the fill's own colour is what
            // the lettering will sit on, and a few hundred pixels tell it.
            val step = max(1, px.size / 400)
            var i = 0
            while (i < px.size) {
                val p = px[i]
                sumR += p shr 16 and 0xFF
                sumG += p shr 8 and 0xFF
                sumB += p and 0xFF
                n++
                i += step
            }
            fill
        }
        val mean = if (n == 0L) null else Color.rgb((sumR / n).toInt(), (sumG / n).toInt(), (sumB / n).toInt())
        return Result(fills, mean)
    }

    /** An opaque fill the size of [wipeRect] for [line], or null when nothing around it can be sampled. */
    fun fill(bitmap: Bitmap, line: Rect): Bitmap? {
        val box = wipeRect(line, bitmap.width, bitmap.height)
        val w = box.width()
        val h = box.height()
        if (w < 1 || h < 1) return null
        if (w.toLong() * h > MAX_AREA) return null
        val vertical = h > w

        // Grab the box and its sampling margin in one read.
        val margin = REACH + WINDOW
        val region = Rect(
            max(0, box.left - margin), max(0, box.top - margin),
            min(bitmap.width, box.right + margin), min(bitmap.height, box.bottom + margin),
        )
        val rw = region.width()
        val rh = region.height()
        val px = IntArray(rw * rh)
        bitmap.getPixels(px, 0, rw, region.left, region.top, rw, rh)

        val out = IntArray(w * h)
        val rs = IntArray((2 * WINDOW + 1) * REACH)
        val gs = IntArray(rs.size)
        val bs = IntArray(rs.size)

        /**
         * Median colour of the patch [x0,x1) x [y0,y1) in region coordinates,
         * or [NONE] when the patch lies off the page. Packed colours are
         * negative ints, so the sentinel is zero — transparent black, which
         * no opaque sample can produce.
         */
        fun median(x0: Int, y0: Int, x1: Int, y1: Int): Int {
            var k = 0
            for (y in max(0, y0) until min(rh, y1)) {
                val row = y * rw
                for (x in max(0, x0) until min(rw, x1)) {
                    val p = px[row + x]
                    rs[k] = p shr 16 and 0xFF
                    gs[k] = p shr 8 and 0xFF
                    bs[k] = p and 0xFF
                    k++
                }
            }
            if (k == 0) return NONE
            java.util.Arrays.sort(rs, 0, k)
            java.util.Arrays.sort(gs, 0, k)
            java.util.Arrays.sort(bs, 0, k)
            return Color.rgb(rs[k / 2], gs[k / 2], bs[k / 2])
        }

        val ox = box.left - region.left
        val oy = box.top - region.top
        // The colour just outside each edge, at every step along the line.
        val n = if (vertical) h else w
        val near = IntArray(n)
        val far = IntArray(n)
        for (k in 0 until n) {
            if (vertical) {
                val ry = oy + k
                near[k] = median(ox - REACH, ry - WINDOW, ox, ry + WINDOW + 1)
                far[k] = median(ox + w, ry - WINDOW, ox + w + REACH, ry + WINDOW + 1)
            } else {
                val rx = ox + k
                near[k] = median(rx - WINDOW, oy - REACH, rx + WINDOW + 1, oy)
                far[k] = median(rx - WINDOW, oy + h, rx + WINDOW + 1, oy + h + REACH)
            }
        }
        if (near.all { it == NONE } && far.all { it == NONE }) return null
        // A star, a sparkle or a stray stroke that fills one sample patch
        // would run as a streak across the fill; a median along the line
        // takes it out while a gradient passes through.
        val nearS = smooth(near)
        val farS = smooth(far)
        for (k in 0 until n) {
            val a0 = nearS[k]
            val b0 = farS[k]
            if (a0 == NONE && b0 == NONE) return null
            val a = if (a0 == NONE) b0 else a0
            val b = if (b0 == NONE) a0 else b0
            if (vertical) lerpRow(out, k * w, w, 1, a, b) else lerpRow(out, k, h, w, a, b)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Per-channel running median over ±[SMOOTH] entries; [NONE] entries stay [NONE]. */
    private fun smooth(c: IntArray): IntArray {
        val n = c.size
        if (n <= 2) return c
        val out = IntArray(n)
        val rs = IntArray(2 * SMOOTH + 1)
        val gs = IntArray(rs.size)
        val bs = IntArray(rs.size)
        for (i in 0 until n) {
            if (c[i] == NONE) {
                out[i] = NONE
                continue
            }
            var k = 0
            for (j in max(0, i - SMOOTH)..min(n - 1, i + SMOOTH)) {
                val p = c[j]
                if (p == NONE) continue
                rs[k] = p shr 16 and 0xFF
                gs[k] = p shr 8 and 0xFF
                bs[k] = p and 0xFF
                k++
            }
            java.util.Arrays.sort(rs, 0, k)
            java.util.Arrays.sort(gs, 0, k)
            java.util.Arrays.sort(bs, 0, k)
            out[i] = Color.rgb(rs[k / 2], gs[k / 2], bs[k / 2])
        }
        return out
    }

    /** Writes [n] pixels from [start] every [stride], running from colour [a] to colour [b]. */
    private fun lerpRow(out: IntArray, start: Int, n: Int, stride: Int, a: Int, b: Int) {
        val ar = a shr 16 and 0xFF
        val ag = a shr 8 and 0xFF
        val ab = a and 0xFF
        val br = b shr 16 and 0xFF
        val bg = b shr 8 and 0xFF
        val bb = b and 0xFF
        var i = start
        for (k in 0 until n) {
            val t = (k + 0.5f) / n
            val r = (ar + (br - ar) * t).toInt()
            val g = (ag + (bg - ag) * t).toInt()
            val bl = (ab + (bb - ab) * t).toInt()
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            i += stride
        }
    }
}
