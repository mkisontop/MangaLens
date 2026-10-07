package app.mangalens.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import app.mangalens.gaps.ShadeSnapshot
import app.mangalens.gaps.ShadeStyle
import app.mangalens.gaps.ShadeWindow

/** The one clock the shade uses, everywhere: monotonic, in milliseconds with a fraction. */
object GapClock {
    fun nowMs(): Double = System.nanoTime() / 1_000_000.0
}

/**
 * The dark gaps, drawn as the bottom layer of the lettering's own window.
 *
 * It is not a window of its own, on purpose. Since Android 12 the windows of one app that let
 * touches through to the app below are added up, and once together they are more opaque than
 * the system's cap (0.8 unless set otherwise) touches are not passed through at all: the page
 * under them can no longer be scrolled, and Android says the app "isn't optimized". The
 * lettering's window already sits at that cap whenever it is an ordinary overlay, so the shade
 * shares it, and MangaLens stays one see-through window over the page.
 *
 * It draws one thing: a list of rectangles, each a slab of black. Which rectangles, and how far
 * down the screen, come from the engine's newest [ShadeSnapshot], read at the moment of
 * drawing; while the page moves it asks for every frame, because the position is a prediction
 * that advances with the clock. They are cut to the rows the page scrolls in, so that riding
 * the page never carries them onto the browser's or a site's bars.
 *
 * The black is painted so that what reaches the glass is [ShadeStyle.alpha] of black, whatever
 * the window is drawn at, and so that under the veil MangaLens lays over the page the capture,
 * once lifted by the veil's level, still reads the shade's own grey: the detector must see its
 * own output as paper.
 */
class GapShadeLayer : BubbleOverlayView.Underlay {

    private val paint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = false
        color = Color.BLACK
    }

    /** Where the newest snapshot comes from; set by the controller. */
    @Volatile var source: (() -> ShadeSnapshot)? = null

    /** Told, on the UI thread, of every draw: the snapshot, the shift it was drawn at, and when. */
    @Volatile var onDrawn: ((ShadeSnapshot, Int, Double) -> Unit)? = null

    /** The view that draws this layer, for asking it to draw again. Main thread. */
    var host: View? = null

    /** Whether the shade is shown at all. Main thread. */
    var visible = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** How dark, as it should reach the glass. Main thread. */
    var style: ShadeStyle = ShadeStyle()
        set(value) {
            field = value
            invalidate()
        }

    /** Any thread: draw again at the next frame. */
    fun invalidate() {
        host?.postInvalidateOnAnimation()
    }

    override fun draw(canvas: Canvas, view: BubbleOverlayView): Boolean {
        if (!visible) return false
        val snap = source?.invoke() ?: return false
        val now = GapClock.nowMs()
        val shift = snap.shiftAt(now)
        onDrawn?.invoke(snap, shift, now)
        paint.alpha = ShadeWindow.paintAlpha(style.alpha, view.windowAlpha, view.screenLevel)
        // Cut to the rows the page scrolls in: the bars above and below it stay put.
        snap.forEachDrawn(shift, view.height) { x0, y0, x1, y1 ->
            canvas.drawRect(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), paint)
        }
        return snap.moving
    }
}
