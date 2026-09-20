package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import app.mangalens.ocr.Balloon
import app.mangalens.ocr.BubbleKind
import app.mangalens.overlay.RenderBubble

/**
 * Turns a translated region into the card the overlay paints: the balloon
 * it belongs to, the colour of the paper it sits on, and — for lettering
 * on open art — the fills that take the original lines off the page.
 *
 * Kept apart from the pipeline so the rendering of a page can be exercised
 * without OCR or a translation engine behind it.
 */
internal object RenderPrep {

    fun bubble(
        bitmap: Bitmap,
        box: Rect,
        translated: String,
        original: String,
        vertical: Boolean,
        kind: BubbleKind,
        detected: List<Balloon>,
        lines: List<Rect> = emptyList(),
    ): RenderBubble {
        val balloon = balloonFor(box, detected)
        // Lettering with no balloon around it is wiped line by line, each
        // line filled from the art beside it; the English then sits on that
        // fill, so its colour is what decides the lettering's.
        val wipe = if (balloon == null && kind != BubbleKind.SFX && lines.isNotEmpty()) {
            ArtWipe.prepare(bitmap, lines)
        } else {
            null
        }
        val bg = when {
            balloon != null -> interiorColor(bitmap, balloon)
            wipe?.meanColor != null -> wipe.meanColor
            else -> sampleBackground(bitmap, box)
        }
        val textColor = when {
            balloon?.inverted == true -> 0xFFF2F3F7.toInt()
            luminance(bg) < 140 -> Color.WHITE
            else -> 0xFF17181C.toInt()
        }
        // A gradient or textured balloon is cleaned with its own paper
        // continued under the lettering, not with a flat patch of the average.
        val fill = balloon?.let { BalloonFill.build(bitmap, it) }
        return RenderBubble(
            box = Rect(box),
            translated = translated,
            original = original,
            bgColor = bg,
            textColor = textColor,
            vertical = vertical,
            kind = kind,
            balloon = balloon,
            fill = fill,
            lines = if (wipe != null) lines.map { Rect(it) } else emptyList(),
            lineFills = wipe?.fills ?: emptyList(),
        )
    }

    /**
     * The detected balloon this region belongs to, so its card can wipe the
     * whole balloon clean rather than float a patch over part of it. A welded
     * region carries the balloon's own box; an OCR-tight region sits inside
     * one; anything else — SFX on the art, captions, a drifted extra — has no
     * balloon and keeps the plain card.
     */
    fun balloonFor(box: Rect, detected: List<Balloon>): Balloon? {
        detected.firstOrNull { it.box == box }?.let { return it }
        return detected.firstOrNull { b ->
            b.box.contains(box.centerX(), box.centerY()) &&
                (iou(b.box, box) > 0.2f || containedShare(box, b.box) > 0.8f)
        }
    }

    /** Fraction of [box] inside [within]. */
    fun containedShare(box: Rect, within: Rect): Float {
        val ix = minOf(box.right, within.right) - maxOf(box.left, within.left)
        val iy = minOf(box.bottom, within.bottom) - maxOf(box.top, within.top)
        if (ix <= 0 || iy <= 0) return 0f
        val area = box.width().toLong() * box.height()
        if (area <= 0L) return 0f
        return (ix.toLong() * iy).toFloat() / area
    }

    fun iou(a: Rect, b: Rect): Float {
        val ix = maxOf(0, minOf(a.right, b.right) - maxOf(a.left, b.left))
        val iy = maxOf(0, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        val inter = ix.toLong() * iy
        if (inter == 0L) return 0f
        val union = a.width().toLong() * a.height() + b.width().toLong() * b.height() - inter
        return inter.toFloat() / union
    }

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
