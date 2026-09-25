package app.mangalens.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * The auto-scroll buttons beside the 文A button, drawn in its style: a disc
 * with an ink rim and an ink glyph. [Glyph.SCROLL] (two chevrons pointing
 * down) starts auto-scroll and [Glyph.PAUSE] stops it, on the yellow of the
 * live 文A button; [Glyph.SLOWER] and [Glyph.FASTER] are smaller cream
 * discs, shown only while the page is moving. The glyphs are drawn, not
 * typed, so they look the same on every phone.
 */
internal class ScrollButtonView(context: Context, glyph: Glyph) : View(context) {

    enum class Glyph { SCROLL, PAUSE, SLOWER, FASTER }

    var glyph: Glyph = glyph
        set(v) {
            if (field == v) return
            field = v
            describe()
            invalidate()
        }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SHADOW }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = INK
    }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = INK
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
    private val path = Path()

    init {
        describe()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun describe() {
        contentDescription = when (glyph) {
            Glyph.SCROLL -> "Start auto-scroll"
            Glyph.PAUSE -> "Stop auto-scroll"
            Glyph.SLOWER -> "Scroll slower"
            Glyph.FASTER -> "Scroll faster"
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - dp(3f)
        val small = glyph == Glyph.SLOWER || glyph == Glyph.FASTER
        canvas.drawCircle(cx, cy + dp(2f), r, shadowPaint)
        fillPaint.color = if (small) CREAM else YELLOW
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, rimPaint)
        val u = r * 0.42f
        when (glyph) {
            Glyph.SCROLL -> {
                // Two chevrons pointing down: the page moving on.
                path.reset()
                path.moveTo(cx - u, cy - u * 0.85f)
                path.lineTo(cx, cy - u * 0.15f)
                path.lineTo(cx + u, cy - u * 0.85f)
                path.moveTo(cx - u, cy + u * 0.05f)
                path.lineTo(cx, cy + u * 0.75f)
                path.lineTo(cx + u, cy + u * 0.05f)
                canvas.drawPath(path, inkPaint)
            }
            Glyph.PAUSE -> {
                val w = u * 0.42f
                canvas.drawRoundRect(cx - u * 0.75f, cy - u, cx - u * 0.75f + w, cy + u, w / 3, w / 3, barPaint)
                canvas.drawRoundRect(cx + u * 0.75f - w, cy - u, cx + u * 0.75f, cy + u, w / 3, w / 3, barPaint)
            }
            Glyph.SLOWER -> canvas.drawLine(cx - u, cy, cx + u, cy, inkPaint)
            Glyph.FASTER -> {
                canvas.drawLine(cx - u, cy, cx + u, cy, inkPaint)
                canvas.drawLine(cx, cy - u, cx, cy + u, inkPaint)
            }
        }
    }

    companion object {
        private const val YELLOW = 0xF2FFCC1A.toInt()
        private const val CREAM = 0xF2FFF4DC.toInt()
        private const val INK = 0xFF1C1424.toInt()
        private const val SHADOW = 0x801C1424.toInt()
    }
}
