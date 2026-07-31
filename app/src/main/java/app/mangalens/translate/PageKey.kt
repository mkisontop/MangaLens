package app.mangalens.translate

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Identifies a page by what is actually on it.
 *
 * The vision cache used to key a page on the concatenated OCR text of its
 * regions. On manga that text is rich enough to tell pages apart — but the
 * balloons a manhwa reader scrolls past are exactly the ones stylized
 * lettering keeps on-device OCR from reading, so their regions carry no text
 * at all. Every such stop then produced the *same* key, the previous page's
 * answer "hit", and its translations were replayed onto whatever balloons now
 * sat at those positions. That is the stale-text-on-a-new-bubble bug: the
 * cache was remembering pages by position because content never made it into
 * the key.
 *
 * A region therefore contributes what it can always contribute — its pixels.
 * A region OCR read keeps its text (stable across scroll offsets, unlike its
 * screen position); a region OCR could not read contributes a perceptual hash
 * of its own crop instead. The crop travels with the balloon as the page
 * scrolls, so the identity is scroll-invariant, and two balloons holding
 * different lettering hash apart. Tokens are length-prefixed and joined with
 * a separator so neither region count nor token boundaries can collide.
 */
object PageKey {

    /** Joins tokens unambiguously; not whitespace, so cache keys keep it. */
    const val SEP = '\u0001'

    /** Hash resolution. Balloon lettering layout survives an 8x8 downscale. */
    const val HASH_DIM = 8

    /**
     * One region's contribution to the page identity: its text when OCR read
     * any, else the pixel fingerprint supplied by [hash].
     */
    fun token(text: String, hash: () -> Long): String =
        text.ifBlank { "px:" + java.lang.Long.toHexString(hash()) }

    /** The page identity: region count, then every token in reading order. */
    fun of(tokens: List<String>): String =
        tokens.size.toString() + SEP + tokens.joinToString(SEP.toString())

    /**
     * Average-hash of 64 grayscale cells: each bit records whether its cell is
     * brighter than the mean. Robust to compression noise and the few pixels
     * of jitter balloon detection adds between frames; different lettering
     * layouts differ in which cells the ink darkens.
     */
    fun hash64(cells: IntArray): Long {
        require(cells.size == HASH_DIM * HASH_DIM) { "expected ${HASH_DIM * HASH_DIM} cells" }
        var sum = 0L
        for (c in cells) sum += c
        val mean = sum / cells.size
        var bits = 0L
        for (i in cells.indices) {
            if (cells[i] > mean) bits = bits or (1L shl i)
        }
        return bits
    }

    /** Luminance below this counts as ink when weighing the lettering. */
    private const val INK_CEILING = 200

    /**
     * Fingerprint of one region's pixels.
     *
     * The sampling window is anchored on the lettering's own centroid before
     * hashing. Balloon detection re-finds the same balloon a few pixels off
     * between scroll stops, and a window fixed to the detection box would
     * sample the content slightly shifted — flipping near-threshold bits and
     * killing legitimate scroll-back hits. Re-centering on the ink makes the
     * window follow the content: the same lettering yields the same cells
     * wherever the box wobbled, exactly, for any pure shift of the box.
     */
    fun regionHash(bitmap: Bitmap, box: Rect): Long {
        val c = inkCentroid(bitmap, box)
        val anchored = if (c == null) box else Rect(box).apply {
            // floor, not truncation: floor(x - k) = floor(x) - k for whole k,
            // so a box that wobbled by k pixels lands on the same window.
            val dx = kotlin.math.floor(c.first - box.exactCenterX()).toInt()
                .coerceIn(-width() / 4, width() / 4)
            val dy = kotlin.math.floor(c.second - box.exactCenterY()).toInt()
                .coerceIn(-height() / 4, height() / 4)
            offset(dx, dy)
        }
        return hash64(regionCells(bitmap, anchored))
    }

    /**
     * Downscales [box]'s crop to [HASH_DIM]x[HASH_DIM] gray cells by exact
     * area averaging — every pixel contributes to its cell. Canvas bilinear
     * filtering point-samples on a downscale this steep, which turns the
     * cells into a handful of arbitrary pixels and the hash into a lottery.
     *
     * The crop is inset slightly: the balloon's inked border is the part whose
     * inclusion varies most with detection jitter, and shaving it keeps the
     * hash on the lettering rather than on how much outline the box caught.
     */
    fun regionCells(bitmap: Bitmap, box: Rect): IntArray {
        val cells = IntArray(HASH_DIM * HASH_DIM)
        val crop = insetCrop(bitmap, box) ?: return cells
        val w = crop.width()
        val h = crop.height()
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, crop.left, crop.top, w, h)

        val sums = LongArray(HASH_DIM * HASH_DIM)
        val counts = IntArray(HASH_DIM * HASH_DIM)
        for (y in 0 until h) {
            val cellRow = y * HASH_DIM / h * HASH_DIM
            val row = y * w
            for (x in 0 until w) {
                val i = cellRow + x * HASH_DIM / w
                sums[i] += luminance(px[row + x])
                counts[i]++
            }
        }
        for (i in cells.indices) {
            if (counts[i] > 0) cells[i] = (sums[i] / counts[i]).toInt()
        }
        return cells
    }

    /** The box inset past its own outline and clamped to the bitmap, or null. */
    private fun insetCrop(bitmap: Bitmap, box: Rect): Rect? {
        val insetX = box.width() / 16
        val insetY = box.height() / 16
        val left = (box.left + insetX).coerceIn(0, bitmap.width - 1)
        val top = (box.top + insetY).coerceIn(0, bitmap.height - 1)
        val right = (box.right - insetX).coerceIn(left + 1, bitmap.width)
        val bottom = (box.bottom - insetY).coerceIn(top + 1, bitmap.height)
        if (right - left < 2 || bottom - top < 2) return null
        return Rect(left, top, right, bottom)
    }

    /**
     * Ink centroid of the (inset) crop in page coordinates, or null for a
     * blank crop. Measured at full resolution so a shifted detection box over
     * the same lettering lands on the identical centroid.
     */
    private fun inkCentroid(bitmap: Bitmap, box: Rect): Pair<Double, Double>? {
        val crop = insetCrop(bitmap, box) ?: return null
        val w = crop.width()
        val h = crop.height()
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, crop.left, crop.top, w, h)
        var weight = 0L
        var xSum = 0L
        var ySum = 0L
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val darkness = INK_CEILING - luminance(px[row + x])
                if (darkness > 0) {
                    weight += darkness
                    xSum += darkness.toLong() * x
                    ySum += darkness.toLong() * y
                }
            }
        }
        if (weight == 0L) return null
        return (crop.left + xSum.toDouble() / weight) to (crop.top + ySum.toDouble() / weight)
    }

    private fun luminance(p: Int): Int =
        ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
}
