package app.mangalens.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

data class RenderBubble(
    val box: Rect,
    val translated: String,
    val original: String,
    val bgColor: Int,
    val textColor: Int,
    val vertical: Boolean,
)

/**
 * Full-screen, untouchable layer that paints the English text as clean rounded
 * cards directly over the original bubbles. Vertical source columns are widened
 * into horizontal cards; text auto-shrinks until it fits.
 */
class BubbleOverlayView(context: Context) : View(context) {

    private data class Placed(val rect: RectF, val layout: StaticLayout, val bg: Int)

    private var placed: List<Placed> = emptyList()

    @Volatile var textScale = 1f
    @Volatile var bgOpacity = 1f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0x2E000000
    }

    fun setBubbles(bubbles: List<RenderBubble>) {
        placed = bubbles.mapNotNull { place(it) }
        invalidate()
    }

    fun clear() {
        placed = emptyList()
        invalidate()
    }

    fun hasBubbles() = placed.isNotEmpty()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun place(b: RenderBubble): Placed? {
        if (b.translated.isBlank()) return null
        val screenW = (if (width > 0) width else resources.displayMetrics.widthPixels).toFloat()
        val screenH = (if (height > 0) height else resources.displayMetrics.heightPixels).toFloat()
        val pad = dp(7f)

        var boxW = b.box.width().toFloat()
        if (b.vertical) boxW = maxOf(boxW, b.box.height() * 0.85f)
        boxW = boxW.coerceAtLeast(dp(88f)).coerceAtMost(screenW * 0.92f)
        val maxH = maxOf(b.box.height() + dp(26f), dp(64f))

        var layout: StaticLayout? = null
        var size = 18f * textScale
        while (size >= 9f) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = b.textColor
                textSize = dp(size)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val candidate = StaticLayout.Builder
                .obtain(b.translated, 0, b.translated.length, tp, (boxW - pad * 2).toInt().coerceAtLeast(40))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.06f)
                .setIncludePad(false)
                .build()
            layout = candidate
            if (candidate.height <= maxH - pad * 2) break
            size -= 1.25f
        }
        val chosen = layout ?: return null

        var maxLine = 0f
        for (i in 0 until chosen.lineCount) maxLine = maxOf(maxLine, chosen.getLineWidth(i))
        val w = (maxLine + pad * 2).coerceAtLeast(dp(40f)).coerceAtMost(boxW + pad * 2)
        val h = chosen.height + pad * 2

        var left = b.box.centerX() - w / 2f
        var top = b.box.centerY() - h / 2f
        left = left.coerceAtMost(screenW - w - dp(2f)).coerceAtLeast(dp(2f))
        top = top.coerceAtMost(screenH - h - dp(2f)).coerceAtLeast(dp(2f))

        val alpha = (255 * bgOpacity).toInt().coerceIn(70, 255)
        val bg = Color.argb(alpha, Color.red(b.bgColor), Color.green(b.bgColor), Color.blue(b.bgColor))
        return Placed(RectF(left, top, left + w, top + h), chosen, bg)
    }

    override fun onDraw(canvas: Canvas) {
        val radius = dp(9f)
        for (p in placed) {
            bgPaint.color = p.bg
            canvas.drawRoundRect(p.rect, radius, radius, bgPaint)
            canvas.drawRoundRect(p.rect, radius, radius, strokePaint)
            canvas.save()
            canvas.translate(p.rect.centerX() - p.layout.width / 2f, p.rect.top + dp(7f))
            p.layout.draw(canvas)
            canvas.restore()
        }
    }
}
