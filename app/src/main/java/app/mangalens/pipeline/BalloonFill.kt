package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import app.mangalens.ocr.Balloon
import kotlin.math.max
import kotlin.math.min

/**
 * The fill a cleaned balloon should get when its paper is not one colour.
 *
 * Most balloons are flat white, and a flat fill in the sampled colour is
 * the right cleaning for them. Coloured manhwa balloons are not flat: they
 * carry gradients, soft vignettes, sometimes a texture, and a flat average
 * painted over one reads as a patch stuck onto the balloon rather than the
 * balloon with its lettering gone. For those the fill is inpainted: each
 * cell of the balloon's own mask takes the colour of the paper pixels it
 * holds, and cells the lettering touches are filled in from their
 * neighbours until the gradient runs through unbroken. A cell with any
 * lettering in it is never read for paper: the grey anti-aliased rim of
 * every glyph passes for paper, and a fill read from it keeps a faint copy
 * of the text, cell by cell, under the English.
 *
 * Returns null when the paper is flat, so the ordinary fill is used.
 */
object BalloonFill {

    /**
     * Spread of paper colour across the interior — the widest 15th-to-85th
     * percentile range of any channel over the cells well inside the mask —
     * below which the paper is one colour and a flat fill is right. Judged
     * per channel: a pink-to-blue gradient barely changes luminance at all.
     * Measured away from the mask's edge, where the outline's anti-aliasing
     * greys the cells of every balloon.
     */
    private const val FLAT_SPREAD = 12f

    /** Paper luminance floors, matching the pipeline's own sampling. */
    private const val PAPER_LIGHT = 150
    private const val PAPER_DARK = 120

    private const val MAX_DIFFUSION = 120

    /** A cell with more than one pixel in this many dark holds lettering (or the outline), not paper alone. */
    private const val INK_FREE = 50

    /** An opaque [Balloon.maskW] x [Balloon.maskH] fill, or null for a flat balloon. */
    fun build(bitmap: Bitmap, balloon: Balloon): Bitmap? {
        val mw = balloon.maskW
        val mh = balloon.maskH
        if (mw < 2 || mh < 2 || balloon.mask.size < mw * mh) return null
        val src = balloon.box
        if (src.width() <= 0 || src.height() <= 0) return null
        val clip = Rect(
            src.left.coerceIn(0, bitmap.width),
            src.top.coerceIn(0, bitmap.height),
            src.right.coerceIn(0, bitmap.width),
            src.bottom.coerceIn(0, bitmap.height),
        )
        if (clip.width() < 2 || clip.height() < 2) return null

        val n = mw * mh
        val sumR = LongArray(n)
        val sumG = LongArray(n)
        val sumB = LongArray(n)
        val cnt = IntArray(n)
        val ink = IntArray(n)
        val cw = clip.width()
        val cxOf = IntArray(cw) { x -> ((clip.left + x - src.left).toLong() * mw / src.width()).toInt().coerceIn(0, mw - 1) }
        val band = max(1, min(clip.height(), (1 shl 18) / cw))
        val px = IntArray(cw * band)
        var y = clip.top
        while (y < clip.bottom) {
            val rows = min(band, clip.bottom - y)
            bitmap.getPixels(px, 0, cw, clip.left, y, cw, rows)
            for (r in 0 until rows) {
                val cy = ((y + r - src.top).toLong() * mh / src.height()).toInt().coerceIn(0, mh - 1)
                val cellRow = cy * mw
                val srcRow = r * cw
                for (x in 0 until cw) {
                    val p = px[srcRow + x]
                    val red = p shr 16 and 0xFF
                    val green = p shr 8 and 0xFF
                    val blue = p and 0xFF
                    val lum = (red * 299 + green * 587 + blue * 114) / 1000
                    val paper = if (balloon.inverted) lum <= PAPER_DARK else lum >= PAPER_LIGHT
                    val i = cellRow + cxOf[x]
                    if (!paper) {
                        ink[i]++
                        continue
                    }
                    sumR[i] += red
                    sumG[i] += green
                    sumB[i] += blue
                    cnt[i]++
                }
            }
            y += rows
        }

        val red = FloatArray(n)
        val green = FloatArray(n)
        val blue = FloatArray(n)
        // Lettering and the cells beside it: the rim of a stroke can spill
        // into the next cell without any of its dark core.
        val lettered = BooleanArray(n)
        for (i in 0 until n) {
            if (ink[i] * INK_FREE <= ink[i] + cnt[i]) continue
            val x = i % mw
            val yy = i / mw
            lettered[i] = true
            if (x > 0) lettered[i - 1] = true
            if (x < mw - 1) lettered[i + 1] = true
            if (yy > 0) lettered[i - mw] = true
            if (yy < mh - 1) lettered[i + mw] = true
        }
        val known = BooleanArray(n)
        var knownCount = 0
        for (i in 0 until n) {
            if (cnt[i] == 0 || lettered[i]) continue
            red[i] = sumR[i].toFloat() / cnt[i]
            green[i] = sumG[i].toFloat() / cnt[i]
            blue[i] = sumB[i].toFloat() / cnt[i]
            known[i] = true
            knownCount++
        }
        if (knownCount < n / 8) return null
        if (!textured(balloon.mask, known, red, green, blue, mw, mh)) return null

        // Diffuse paper into the cells the lettering covered entirely — and
        // into the cells outside the balloon, so the stamp's filtered edge
        // never samples black.
        val nextR = FloatArray(n)
        val nextG = FloatArray(n)
        val nextB = FloatArray(n)
        val grew = BooleanArray(n)
        var unknown = n - knownCount
        var iter = 0
        while (unknown > 0 && iter < MAX_DIFFUSION) {
            var any = false
            for (i in 0 until n) {
                if (known[i]) continue
                val x = i % mw
                val yy = i / mw
                var sr = 0f
                var sg = 0f
                var sb = 0f
                var k = 0
                if (x > 0 && known[i - 1]) { sr += red[i - 1]; sg += green[i - 1]; sb += blue[i - 1]; k++ }
                if (x < mw - 1 && known[i + 1]) { sr += red[i + 1]; sg += green[i + 1]; sb += blue[i + 1]; k++ }
                if (yy > 0 && known[i - mw]) { sr += red[i - mw]; sg += green[i - mw]; sb += blue[i - mw]; k++ }
                if (yy < mh - 1 && known[i + mw]) { sr += red[i + mw]; sg += green[i + mw]; sb += blue[i + mw]; k++ }
                if (k == 0) continue
                nextR[i] = sr / k
                nextG[i] = sg / k
                nextB[i] = sb / k
                grew[i] = true
                any = true
            }
            if (!any) break
            for (i in 0 until n) {
                if (!grew[i]) continue
                red[i] = nextR[i]
                green[i] = nextG[i]
                blue[i] = nextB[i]
                known[i] = true
                grew[i] = false
                unknown--
            }
            iter++
        }

        val out = IntArray(n)
        for (i in 0 until n) {
            out[i] = Color.rgb(
                red[i].toInt().coerceIn(0, 255),
                green[i].toInt().coerceIn(0, 255),
                blue[i].toInt().coerceIn(0, 255),
            )
        }
        return Bitmap.createBitmap(out, mw, mh, Bitmap.Config.ARGB_8888)
    }

    /** True when the paper well inside the mask varies in colour beyond [FLAT_SPREAD]. */
    private fun textured(
        mask: BooleanArray,
        known: BooleanArray,
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        w: Int,
        h: Int,
    ): Boolean {
        val rs = ArrayList<Float>()
        val gs = ArrayList<Float>()
        val bs = ArrayList<Float>()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                if (!known[i] || !mask[i]) continue
                if (!mask[i - 1] || !mask[i + 1] || !mask[i - w] || !mask[i + w]) continue
                rs.add(red[i])
                gs.add(green[i])
                bs.add(blue[i])
            }
        }
        if (rs.size < 8) return false
        fun spread(values: ArrayList<Float>): Float {
            values.sort()
            val lo = values[(values.size * 15 / 100).coerceIn(0, values.size - 1)]
            val hi = values[(values.size * 85 / 100).coerceIn(0, values.size - 1)]
            return hi - lo
        }
        return max(spread(rs), max(spread(gs), spread(bs))) >= FLAT_SPREAD
    }
}
