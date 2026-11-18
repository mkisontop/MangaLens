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
import kotlin.math.abs

/** An authored Japanese page for the real Android screen-sharing test. */
internal class JapaneseDialoguePage(context: Context) : View(context) {
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val sourceInk = Color.rgb(114, 41, 147)

    fun letteringArea() = Rect((width * .13f).toInt(), (height * .44f).toInt(),
        (width * .87f).toInt(), (height * .68f).toInt())

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(41, 47, 54))
        brush.color = Color.rgb(79, 130, 147)
        canvas.drawRect(0f, height * .22f, width.toFloat(), height * .81f, brush)
        brush.color = Color.rgb(40, 82, 97)
        brush.strokeWidth = width * .014f
        for (i in 0..8) {
            canvas.drawLine(i * width / 8f, height * .26f, (i + 1) * width / 8f, height * .4f, brush)
        }
        val area = RectF(letteringArea()).apply { inset(-width * .052f, -height * .015f) }
        brush.color = Color.rgb(26, 30, 35)
        canvas.drawOval(area, brush)
        area.inset(4f, 4f)
        brush.color = Color.WHITE
        canvas.drawOval(area, brush)
        brush.color = sourceInk
        brush.typeface = Typeface.DEFAULT_BOLD
        brush.textSize = width * .052f
        canvas.drawText("まだ帰らないで。", width / 2f, height * .545f, brush)
        canvas.drawText("朝までここで待って。", width / 2f, height * .602f, brush)
    }

    fun sourcePixels(image: Bitmap, rect: Rect): Int {
        val pixels = IntArray(rect.width() * rect.height())
        image.getPixels(pixels, 0, rect.width(), rect.left, rect.top, rect.width(), rect.height())
        return pixels.count {
            Color.red(it) - Color.green(it) > 27 && Color.blue(it) - Color.green(it) > 45
        }
    }

    /** The reader's Android 12+ anti-ghosting veil scales untouched art to 80% brightness. */
    fun checkArt(before: Bitmap, after: Bitmap, origin: IntArray): Pair<Int, Int> {
        val band = Rect((width * .1f).toInt(), (height * .27f).toInt(),
            (width * .9f).toInt(), (height * .37f).toInt()).apply { offset(origin[0], origin[1]) }
        val original = IntArray(band.width() * band.height())
        val actual = IntArray(original.size)
        before.getPixels(original, 0, band.width(), band.left, band.top, band.width(), band.height())
        after.getPixels(actual, 0, band.width(), band.left, band.top, band.width(), band.height())
        val changed = original.indices.count { i ->
            (0..16 step 8).any { shift ->
                val expect = ((original[i] ushr shift and 255) * .8f).toInt()
                abs((actual[i] ushr shift and 255) - expect) > 5
            }
        }
        return original.size to changed
    }
}
