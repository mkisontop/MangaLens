package app.mangalens.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** Original fixture pages. The Korean ink has a distinct hue so the screenshot can measure erasure. */
internal class ReaderPage(context: Context) : View(context) {
    var startupProbe = true
    val startupSource = "오른쪽이에요"
    val startupEnglish = "It is on the right."
    var page = 0
        set(value) {
            field = value
            invalidate()
        }

    val source = listOf("안녕하세요", "기다려요", "돌아와요", "조심해요", "열어봐요", "도와줘요")
    val english = listOf("Hello there!", "Wait for me.", "Come back.", "Be careful.", "Open it.", "Help me.")
    private val paper = Color.WHITE
    private val sourceInk = Color.rgb(99, 44, 128)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    fun ground() = when {
        startupProbe -> Color.rgb(104, 92, 160)
        page % 2 == 0 -> Color.rgb(49, 126, 143)
        else -> Color.rgb(171, 95, 58)
    }

    fun region(lower: Boolean): Rect {
        val x = width * (if (page % 2 == 0) .44f else .58f)
        val y = when {
            startupProbe -> 196f * resources.displayMetrics.density
            lower -> height * .72f
            page == 0 -> height * .53f
            page % 2 == 0 -> height * .28f
            else -> height * .42f
        }
        return Rect((x - width * .21f).toInt(), (y - height * .048f).toInt(),
            (x + width * .21f).toInt(), (y + height * .048f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(ground())
        // Permanent panel lines outside the balloons make page turns visible to capture.
        paint.color = Color.rgb(30, 48, 55)
        paint.strokeWidth = width * .008f
        for (i in 0..4) {
            val x = width * (.05f + i * .20f)
            canvas.drawLine(x, height * .08f, x + (if (page % 2 == 0) 80 else -80), height * .19f, paint)
        }
        balloon(canvas, false, if (startupProbe) startupSource else source.first())
        if (!startupProbe && page > 0) balloon(canvas, true, source[page])
    }

    private fun balloon(canvas: Canvas, lower: Boolean, label: String) {
        val region = region(lower)
        val oval = RectF(region).apply { inset(-width * .09f, -height * .04f) }
        paint.color = Color.rgb(29, 28, 32)
        canvas.drawOval(oval, paint)
        oval.inset(4f, 4f)
        paint.color = paper
        canvas.drawOval(oval, paint)
        paint.textSize = width * .061f
        paint.color = sourceInk
        canvas.drawText(label, region.exactCenterX(),
            region.exactCenterY() - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    data class Ink(val source: Int, val english: Int)

    fun ink(bitmap: Bitmap, region: Rect): Ink {
        val pixels = IntArray(region.width() * region.height())
        bitmap.getPixels(pixels, 0, region.width(), region.left, region.top, region.width(), region.height())
        var original = 0
        var translated = 0
        for (c in pixels) {
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            if (b - g > 27 && r - g > 15 && b > r) original++
            if (maxOf(r, g, b) < 127 && maxOf(r, g, b) - minOf(r, g, b) < 18) translated++
        }
        return Ink(original, translated)
    }
}
