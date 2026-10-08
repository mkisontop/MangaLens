package app.mangalens.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Choreographer
import android.view.View
import app.mangalens.gaps.ShadeSnapshot
import app.mangalens.gaps.ShadeStyle
import app.mangalens.gaps.ShadeWindow

/** The one clock the shade uses, everywhere: monotonic, in milliseconds with a fraction. */
object GapClock {
    fun nowMs(): Double = System.nanoTime() / 1_000_000.0

    /**
     * When a captured frame was put on the screen, from the [timestampNs] its producer stamped on
     * it — the same monotonic clock as [nowMs]. The capture thread gets to its frames when it can:
     * straight away, or after a few milliseconds of other work. The page's speed was read from
     * the times they came in, and wobbled by however late that was; the stamp does not. A stamp
     * that is missing, from some other clock, or in the future is not used.
     */
    fun frameMs(timestampNs: Long): Double {
        val now = nowMs()
        if (timestampNs <= 0L) return now
        val t = timestampNs / 1_000_000.0
        return if (t <= now + 1.0 && now - t <= MAX_FRAME_AGE_MS) t else now
    }

    /** A frame stamped longer ago than this was stamped on another clock, or waited too long to say when the page was there. */
    private const val MAX_FRAME_AGE_MS = 250.0
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
 * The prediction is made for the frame's vsync, not for whenever the draw happens to run. The
 * page under the shade moves once a vsync; a draw runs a millisecond or several after it,
 * however busy the main thread is, and a position worked out for that moment was ahead of the
 * page by that much — a few rows at reading speed, different every frame: the shade shivered
 * against the page it was riding. So frames are asked for through [Choreographer], whose
 * callback is told the vsync it belongs to.
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

    /** The view that draws this layer, for asking it to draw again. Set on the main thread. */
    var host: View? = null
        set(value) {
            field = value
            // the main thread's: the frames are the ones the host is drawn in
            if (value != null && choreographer == null) choreographer = Choreographer.getInstance()
        }

    private var choreographer: Choreographer? = null

    /** The vsync of the newest frame asked for, in [GapClock] milliseconds, and whether a draw has used it yet. */
    @Volatile private var vsyncMs = 0.0
    @Volatile private var vsyncFresh = false

    /** A frame has been asked for and its callback has not run yet. */
    private val asked = java.util.concurrent.atomic.AtomicBoolean(false)

    private val nextFrame = Choreographer.FrameCallback { frameTimeNanos ->
        vsyncMs = frameTimeNanos / 1_000_000.0
        vsyncFresh = true
        asked.set(false)
        // in this frame: the views are drawn right after the frame's callbacks
        host?.invalidate()
    }

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
        val c = choreographer
        if (c == null) {
            host?.postInvalidateOnAnimation()
            return
        }
        if (asked.compareAndSet(false, true)) c.postFrameCallback(nextFrame)
    }

    /**
     * When the frame being drawn is to show the page: its vsync, if this is the first draw since
     * the frame callback ran — the views are drawn straight after it, however long the main thread
     * took over the rest of the frame — and otherwise, for a draw that came about some other way,
     * now. A vsync more than a frame or two old is not this frame's, whatever happened.
     */
    private fun frameTimeMs(): Double {
        val now = GapClock.nowMs()
        val v = vsyncMs
        val fresh = vsyncFresh
        vsyncFresh = false
        return if (fresh && v <= now && now - v <= STALE_VSYNC_MS) v else now
    }

    override fun draw(canvas: Canvas, view: BubbleOverlayView): Boolean {
        if (!visible) return false
        val snap = source?.invoke() ?: return false
        val at = frameTimeMs()
        val shift = snap.shiftAt(at)
        onDrawn?.invoke(snap, shift, at)
        paint.alpha = ShadeWindow.paintAlpha(style.alpha, view.windowAlpha, view.screenLevel)
        // Cut to the rows the page scrolls in: the bars above and below it stay put.
        snap.forEachDrawn(shift, view.height) { x0, y0, x1, y1 ->
            canvas.drawRect(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), paint)
        }
        // The next frame is asked for here rather than by the host, so that it comes with its vsync.
        if (snap.moving) invalidate()
        return false
    }

    private companion object {
        /** A vsync older than this when the draw comes is not taken for the draw's own. */
        const val STALE_VSYNC_MS = 40.0
    }
}
