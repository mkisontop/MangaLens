package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import app.mangalens.ocr.Balloon

/** Colour sampling shared by every path that paints over the page. */
internal object PageColors {

    fun luminance(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    /**
     * The color a scanlator's cleaning fill should be: the average of the
     * paper pixels inside the balloon (or, for an inverted balloon, of its
     * ink), sampled through the interior mask so the lettering itself never
     * tints the fill.
     */
    fun interiorColor(bitmap: Bitmap, balloon: Balloon): Int {
        val box = balloon.box
        val stepX = (balloon.maskW / 48).coerceAtLeast(1)
        val stepY = (balloon.maskH / 48).coerceAtLeast(1)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        var cy = 0
        while (cy < balloon.maskH) {
            var cx = 0
            while (cx < balloon.maskW) {
                if (balloon.mask[cy * balloon.maskW + cx]) {
                    val x = (box.left + ((cx * 2 + 1) * box.width()) / (2 * balloon.maskW))
                        .coerceIn(0, bitmap.width - 1)
                    val y = (box.top + ((cy * 2 + 1) * box.height()) / (2 * balloon.maskH))
                        .coerceIn(0, bitmap.height - 1)
                    val p = bitmap.getPixel(x, y)
                    val lum = luminance(p)
                    val keep = if (balloon.inverted) lum <= 120 else lum >= 150
                    if (keep) {
                        r += Color.red(p)
                        g += Color.green(p)
                        b += Color.blue(p)
                        n++
                    }
                }
                cx += stepX
            }
            cy += stepY
        }
        if (n == 0) return if (balloon.inverted) 0xFF17181C.toInt() else Color.WHITE
        val avg = Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        // Most bubbles are white; snap near-white fills to pure white.
        return if (!balloon.inverted && luminance(avg) > 190) Color.WHITE else avg
    }

    /** Averages the pixels in a thin ring just outside the text box. */
    fun sampleBackground(bmp: Bitmap, box: Rect): Int {
        val left = (box.left - 8).coerceIn(0, bmp.width - 1)
        val top = (box.top - 8).coerceIn(0, bmp.height - 1)
        val right = (box.right + 8).coerceIn(0, bmp.width - 1)
        val bottom = (box.bottom + 8).coerceIn(0, bmp.height - 1)
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0

        fun sample(x: Int, y: Int) {
            val p = bmp.getPixel(x, y)
            r += Color.red(p)
            g += Color.green(p)
            b += Color.blue(p)
            count++
        }

        var x = left
        while (x <= right) {
            sample(x, top)
            sample(x, bottom)
            x += 4
        }
        var y = top
        while (y <= bottom) {
            sample(left, y)
            sample(right, y)
            y += 4
        }
        if (count == 0) return Color.WHITE
        val avg = Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
        // Most bubbles are white; snap near-white samples to pure white for a clean look.
        return if (luminance(avg) > 190) Color.WHITE else avg
    }
}
