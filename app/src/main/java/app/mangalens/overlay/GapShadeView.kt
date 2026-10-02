package app.mangalens.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import app.mangalens.gaps.ShadeSnapshot
import app.mangalens.gaps.ShadeStyle

/** The one clock the shade uses, everywhere: monotonic, in milliseconds with a fraction. */
object GapClock {
    fun nowMs(): Double = System.nanoTime() / 1_000_000.0
}

/**
 * Full-screen, untouchable layer that paints the gaps dark.
 *
 * It draws one thing: a list of rectangles, each as an alpha-blended slab of black. It
 * decides nothing — which rectangles, and how far down the screen to draw them, come from
 * the engine's newest [ShadeSnapshot], read at the moment of drawing so that the position
 * is as fresh as the vsync allows. While the page is moving it keeps redrawing every frame,
 * because the position is a prediction that advances with the clock; at rest it draws once
 * and goes quiet.
 *
 * The slab is not opaque, by design: the capture sees the page through it, and so can read
 * back what the shade is covering (see [ShadeStyle]).
 */
class GapShadeView(context: Context) : View(context) {

    private val paint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = false
        color = Color.argb(ShadeStyle().alpha255, 0, 0, 0)
    }

    /** Where the newest snapshot comes from; set by the controller. */
    @Volatile var source: (() -> ShadeSnapshot)? = null

    /** Told, on the UI thread, of every draw: the snapshot, the shift it was drawn at, and when. */
    @Volatile var onDrawn: ((ShadeSnapshot, Int, Double) -> Unit)? = null

    fun setStyle(style: ShadeStyle) {
        paint.color = Color.argb(style.alpha255, 0, 0, 0)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val snap = source?.invoke() ?: return
        val now = GapClock.nowMs()
        val shift = snap.shiftAt(now)
        onDrawn?.invoke(snap, shift, now)
        val rects = snap.rects
        val h = height
        var i = 0
        while (i < snap.rectCount) {
            val j = i * 4
            val top = rects[j + 1] + shift
            val bottom = rects[j + 3] + shift
            if (bottom > 0 && top < h) {
                canvas.drawRect(rects[j].toFloat(), top.toFloat(), rects[j + 2].toFloat(), bottom.toFloat(), paint)
            }
            i++
        }
        // The position is a prediction that moves with the clock: keep drawing while the page moves.
        if (snap.moving) postInvalidateOnAnimation()
    }
}
