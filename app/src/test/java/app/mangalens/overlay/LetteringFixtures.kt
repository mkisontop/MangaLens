package app.mangalens.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import app.mangalens.ocr.Balloon
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

/**
 * Synthetic pages for the lettering tests: art to letter over, glyph-like
 * "original lettering" in known colours, and an exact erasure patch — the
 * art as it was before the lettering went on, opaque only where the
 * lettering changed it — standing in for the real eraser.
 */
internal object LetteringFixtures {

    val outputDir = File("build/render-preview").apply { mkdirs() }

    fun blank(w: Int, h: Int, color: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    /** Busy, colourful art: a gradient under scattered blocks and strokes of saturated colour. */
    fun noiseArt(w: Int, h: Int, seed: Long = 7L): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(60, 90, 170), Color.rgb(230, 120, 60), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        val rnd = Random(seed)
        repeat(900) {
            p.color = Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            val x = rnd.nextInt(w).toFloat()
            val y = rnd.nextInt(h).toFloat()
            val s = 6f + rnd.nextInt(28)
            if (rnd.nextBoolean()) c.drawRect(x, y, x + s, y + s * 0.6f, p) else c.drawCircle(x, y, s / 2f, p)
        }
        return bmp
    }

    /** A screentone: grey dots on paper, the ground side comments are lettered on. */
    fun screentone(w: Int, h: Int, paper: Int = Color.WHITE): Bitmap {
        val bmp = blank(w, h, paper)
        val c = Canvas(bmp)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 70, 70) }
        var y = 3f
        var row = 0
        while (y < h) {
            var x = if (row % 2 == 0) 3f else 7f
            while (x < w) {
                c.drawCircle(x, y, 2.1f, dot)
                x += 8f
            }
            y += 4f
            row++
        }
        return bmp
    }

    /**
     * Glyph-like marks filling [area] as [cols] x [rows] cells, each a box
     * with a cross through it — dense enough to read as CJK lettering to
     * anything measuring it, and made of rects so it renders identically
     * everywhere. An [outline] paint strokes every mark first.
     */
    fun glyphs(canvas: Canvas, area: Rect, cols: Int, rows: Int, fill: Paint, outline: Paint? = null) {
        val cw = area.width().toFloat() / cols
        val ch = area.height().toFloat() / rows
        val t = maxOf(2f, minOf(cw, ch) * 0.14f)
        for (r in 0 until rows) {
            for (k in 0 until cols) {
                val l = area.left + k * cw + cw * 0.12f
                val tp = area.top + r * ch + ch * 0.12f
                val rr = area.left + (k + 1) * cw - cw * 0.12f
                val b = area.top + (r + 1) * ch - ch * 0.12f
                val marks = listOf(
                    RectF(l, tp, rr, tp + t),
                    RectF(l, b - t, rr, b),
                    RectF(l, tp, l + t, b),
                    RectF((l + rr - t) / 2f, tp, (l + rr + t) / 2f, b),
                    RectF(l, (tp + b - t) / 2f, rr, (tp + b + t) / 2f),
                )
                if (outline != null) for (m in marks) canvas.drawRect(m, outline)
                for (m in marks) canvas.drawRect(m, fill)
            }
        }
    }

    fun fill(color: Int) = Paint().apply { this.color = color }

    fun outline(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeJoin = Paint.Join.ROUND
    }

    /** The exact erasure of whatever was drawn on [page] over [art] inside [rect]. */
    fun patchFrom(art: Bitmap, page: Bitmap, rect: Rect): Bitmap {
        val w = rect.width()
        val h = rect.height()
        val a = IntArray(w * h)
        val p = IntArray(w * h)
        art.getPixels(a, 0, w, rect.left, rect.top, w, h)
        page.getPixels(p, 0, w, rect.left, rect.top, w, h)
        val out = IntArray(w * h)
        for (i in out.indices) if (a[i] != p[i]) out[i] = a[i] or (0xFF shl 24)
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    fun ellipseBalloon(box: Rect, mw: Int = 100, mh: Int = 70): Balloon {
        val mask = BooleanArray(mw * mh)
        for (y in 0 until mh) for (x in 0 until mw) {
            val nx = (x + 0.5f) / mw * 2f - 1f
            val ny = (y + 0.5f) / mh * 2f - 1f
            mask[y * mw + x] = nx * nx + ny * ny <= 1f
        }
        return Balloon(box, mw, mh, mask, false)
    }

    fun rectBalloon(box: Rect, inverted: Boolean = false): Balloon {
        val mw = 80
        val mh = 50
        return Balloon(box, mw, mh, BooleanArray(mw * mh) { true }, inverted)
    }

    fun drawBalloon(canvas: Canvas, box: Rect, oval: Boolean, paper: Int = Color.WHITE, ink: Int = Color.BLACK) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paper }
        val stroke = outline(ink, 4f)
        val r = RectF(box)
        if (oval) {
            canvas.drawOval(r, fill)
            canvas.drawOval(r, stroke)
        } else {
            canvas.drawRect(r, fill)
            canvas.drawRect(r, stroke)
        }
    }

    /** [page] with the overlay drawn over it, as the reader sees the screen. */
    fun render(view: BubbleOverlayView, page: Bitmap): Bitmap {
        val out = page.copy(Bitmap.Config.ARGB_8888, true)
        view.draw(Canvas(out))
        return out
    }

    /** The overlay alone, on a transparent screen. */
    fun overlayOnly(view: BubbleOverlayView, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(out))
        return out
    }

    fun pixels(bmp: Bitmap): IntArray =
        IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }

    /** Painted overlay pixels no rect in [rects] covers. */
    fun uncovered(overlay: Bitmap, rects: List<Rect>): Int = uncoveredBounds(overlay, rects).first

    /** How many painted pixels [rects] miss, and the rectangle they span. */
    fun uncoveredBounds(overlay: Bitmap, rects: List<Rect>): Pair<Int, Rect> {
        val px = pixels(overlay)
        var n = 0
        val r = Rect()
        for (y in 0 until overlay.height) {
            for (x in 0 until overlay.width) {
                if (Color.alpha(px[y * overlay.width + x]) == 0) continue
                if (rects.none { it.contains(x, y) }) {
                    if (n == 0) r.set(x, y, x + 1, y + 1) else r.union(x, y)
                    n++
                }
            }
        }
        return n to r
    }

    fun count(bmp: Bitmap, area: Rect, test: (Int) -> Boolean): Int {
        var n = 0
        val r = Rect(area)
        if (!r.intersect(0, 0, bmp.width, bmp.height)) return 0
        for (y in r.top until r.bottom) for (x in r.left until r.right) if (test(bmp.getPixel(x, y))) n++
        return n
    }

    fun luminance(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    fun near(c: Int, target: Int, tol: Int = 40) =
        Math.abs(Color.red(c) - Color.red(target)) <= tol &&
            Math.abs(Color.green(c) - Color.green(target)) <= tol &&
            Math.abs(Color.blue(c) - Color.blue(target)) <= tol

    fun writePreview(name: String, bmp: Bitmap) {
        ByteArrayOutputStream().use { bos ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            File(outputDir, name).writeBytes(bos.toByteArray())
        }
        println("wrote ${File(outputDir, name).absolutePath}")
    }
}
