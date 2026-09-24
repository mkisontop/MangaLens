package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import app.mangalens.ocr.Balloon

/**
 * Whether a detected balloon may be wiped clean.
 *
 * Balloon detection works on the page alone, and a lot of manga art is
 * exactly what it looks for: an enclosed patch of white paper bounded by
 * ink. A face drawn in line art, a highlight on a cape, the counter of a
 * big sound-effect glyph — each passes as a balloon, and cleaning one
 * paints flat paper over a face. What tells them apart is what is inside:
 * a balloon holds lettering and nothing else. The model has already said
 * where the lettering is, so the interior is checked for ink anywhere else
 * — eyes, mouths, hatching, screentone — and a detection with any real
 * amount of it is not trusted as a balloon. Its lettering is then erased
 * stroke by stroke instead, which leaves the art around it untouched.
 */
internal object BalloonTrust {

    /** Share of the interior, away from the lettering, that may be ink before the balloon is art. */
    private const val MAX_STRAY_INK = 0.012f

    /**
     * Mean grey-level change between neighbouring samples above which the
     * interior is textured — screentone, hatching — rather than paper.
     * Flat and gradient balloons stay within a few levels.
     */
    private const val MAX_TEXTURE = 9f

    /** Length to thickness past which a text box is a line, with a direction to run on in. */
    private const val LINE_ASPECT = 1.5f

    /** Mask cells next to the outside skipped, so the balloon's own outline never counts. */
    private const val EDGE_CELLS = 2

    /**
     * True when [balloon]'s interior holds nothing but the lettering in
     * [text] — boxes in page pixels, grown a little here for their margin
     * of error.
     */
    fun holdsOnly(bitmap: Bitmap, balloon: Balloon, text: List<Rect>): Boolean {
        val mw = balloon.maskW
        val mh = balloon.maskH
        val box = balloon.box
        if (mw < 3 || mh < 3 || box.width() < 8 || box.height() < 8) return true
        val interior = interiorWithHoles(balloon.mask, mw, mh)
        val deep = erode(interior, mw, mh, EDGE_CELLS)
        val grown = text.map { t ->
            val m = (minOf(t.width(), t.height()) * 0.2f).toInt() + 4
            // And a glyph further along the line at either end: the model's
            // box often stops one character short of a column, and that
            // character is lettering, not art in the balloon.
            val glyph = minOf(t.width(), t.height())
            when {
                t.height() > t.width() * LINE_ASPECT -> Rect(t.left - m, t.top - m - glyph, t.right + m, t.bottom + m + glyph)
                t.width() > t.height() * LINE_ASPECT -> Rect(t.left - m - glyph, t.top - m, t.right + m + glyph, t.bottom + m)
                else -> Rect(t.left - m, t.top - m, t.right + m, t.bottom + m)
            }
        }
        val left = box.left.coerceAtLeast(0)
        val right = box.right.coerceAtMost(bitmap.width)
        if (right - left < 2) return true
        val row = IntArray(right - left)
        var samples = 0
        var ink = 0
        var steps = 0
        var change = 0L
        var y = box.top.coerceAtLeast(0)
        val bottom = box.bottom.coerceAtMost(bitmap.height)
        while (y < bottom) {
            val cy = ((y - box.top).toLong() * mh / box.height()).toInt().coerceIn(0, mh - 1)
            bitmap.getPixels(row, 0, row.size, left, y, row.size, 1)
            var x = left
            var prev = -1
            while (x < right) {
                val cx = ((x - box.left).toLong() * mw / box.width()).toInt().coerceIn(0, mw - 1)
                if (deep[cy * mw + cx] && grown.none { it.contains(x, y) }) {
                    val p = row[x - left]
                    val lum = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    samples++
                    if (if (balloon.inverted) lum > 150 else lum < 110) ink++
                    if (prev >= 0) {
                        change += kotlin.math.abs(lum - prev)
                        steps++
                    }
                    prev = lum
                } else {
                    prev = -1
                }
                x += 2
            }
            y += 2
        }
        // A balloon so full of lettering that nothing else is left to look at.
        if (samples < 40) return true
        if (steps > 20 && change.toFloat() / steps > MAX_TEXTURE) return false
        return ink.toFloat() / samples <= MAX_STRAY_INK
    }

    /**
     * The mask with its holes filled: everything not reachable from the
     * mask's border through non-mask cells. The flood stops at lettering,
     * so lettering — and anything else drawn inside — shows up as holes.
     */
    private fun interiorWithHoles(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val outside = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        fun push(i: Int) {
            if (!mask[i] && !outside[i]) {
                outside[i] = true
                stack[sp++] = i
            }
        }
        for (x in 0 until w) {
            push(x)
            push((h - 1) * w + x)
        }
        for (y in 0 until h) {
            push(y * w)
            push(y * w + w - 1)
        }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        return BooleanArray(w * h) { !outside[it] }
    }

    private fun erode(mask: BooleanArray, w: Int, h: Int, times: Int): BooleanArray {
        var cur = mask
        repeat(times) {
            val next = BooleanArray(w * h)
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    next[i] = cur[i] && cur[i - 1] && cur[i + 1] && cur[i - w] && cur[i + w]
                }
            }
            cur = next
        }
        return cur
    }
}
