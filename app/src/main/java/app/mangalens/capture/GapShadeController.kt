package app.mangalens.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import android.os.Handler
import android.os.Looper
import app.mangalens.gaps.DetectJob
import app.mangalens.gaps.LiftedPixels
import app.mangalens.gaps.PixelSource
import app.mangalens.gaps.ShadeEngine
import app.mangalens.gaps.ShadeLevel
import app.mangalens.gaps.ShadeSnapshot
import app.mangalens.gaps.ShadeStyle
import app.mangalens.gaps.Unshade
import app.mangalens.overlay.GapClock
import app.mangalens.overlay.GapShadeLayer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Connects the shade engine to Android: the capture thread's frames go in, detections run
 * on a worker of their own, and the overlay view draws what comes out.
 *
 * Everything the engine owns is touched from the capture thread alone. The view reads the
 * engine's published snapshot, which is immutable; the UI thread's only other contact is
 * posting work to the capture thread.
 *
 * It must never take the app down, or slow the reader: every entry point is guarded, and a
 * shade that keeps failing switches itself off and says so.
 */
class GapShadeController(
    private val captureHandler: Handler,
    /** The newest whole frame, for the exact pass at rest. Called on the capture thread. */
    private val restFrame: () -> PixelSource?,
    /** Something worth telling the reader; called on the main thread. */
    private val onProblem: (String) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mangalens-shade").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    @Volatile private var engine: ShadeEngine? = null
    @Volatile private var view: GapShadeLayer? = null
    @Volatile private var enabled = false

    private var width = 0
    private var height = 0
    private var level = ShadeLevel.DARK
    private var cap = 1f
    private var failures = 0
    private var tripped = false
    @Volatile private var exclusions: List<IntArray> = emptyList()

    private val quiet = Runnable {
        if (enabled) guard { engine?.let { e -> after(e, e.onQuiet(restFrame(), GapClock.nowMs())) } }
    }
    private val tick = Runnable {
        if (enabled) guard { engine?.let { e -> e.tick(GapClock.nowMs()); invalidate() } }
    }

    /** Main thread. */
    fun attach(v: GapShadeLayer) {
        view = v
        v.source = { engine?.snapshot ?: ShadeSnapshot.EMPTY }
        v.onDrawn = { snap, shift, now -> engine?.noteDraw(snap, shift, now) }
        v.style = ShadeStyle(level, cap = cap)
        v.visible = enabled
    }

    /**
     * Main thread. Applies the settings: whether the shade is on, how dark, the darkest the
     * window it is drawn in can show ([newCap], see [app.mangalens.gaps.ShadeWindow.cap]), and the size of the
     * screen it will be reading. A change of any of these starts a fresh engine.
     */
    fun configure(on: Boolean, newLevel: ShadeLevel, newCap: Float, w: Int, h: Int, ignoreTopRows: Int, ignoreBottomRows: Int) {
        captureHandler.post {
            guard {
                val rebuild = engine == null || w != width || h != height || newLevel != level || newCap != cap
                width = w
                height = h
                level = newLevel
                cap = newCap
                val style = ShadeStyle(newLevel, cap = newCap)
                if (rebuild) {
                    engine = if (w > 0 && h > 0) ShadeEngine(w, h, style).also { e ->
                        e.setExclusions(exclusions)
                    } else null
                }
                val e = engine
                e?.ignoreTopRows = ignoreTopRows
                e?.ignoreBottomRows = ignoreBottomRows
                val wasOn = enabled
                enabled = on && e != null
                val shown = enabled
                main.post {
                    view?.style = style
                    view?.visible = shown
                }
                if (e != null) {
                    if (!enabled) {
                        e.reset(GapClock.nowMs())
                    } else if (!wasOn || rebuild) {
                        failures = 0
                        tripped = false
                        val src = restFrame()
                        if (src != null) after(e, e.kick(src, GapClock.nowMs()))
                    }
                }
            }
        }
    }

    /** Any thread. The screen the shade has to keep clear of: the app's own controls. */
    fun setExclusions(rects: List<Rect>) {
        val list = rects.map { intArrayOf(it.left, it.top, it.right, it.bottom) }
        exclusions = list
        // the engine belongs to the capture thread
        captureHandler.post { engine?.setExclusions(list) }
    }

    /**
     * Capture thread: a frame has arrived. Cheap when the frame is not one to look at. [level] is
     * the share of light MangaLens's own veil lets through, 1 when there is none: the page is
     * read as it is under it, or the veil's darkness would be taken for ink.
     */
    fun onFrame(image: Image, level: Float = 1f) {
        val e = engine ?: return
        if (!enabled) return
        // A frame of another size — a rotation in flight — is not one this engine can read.
        if (image.width != width || image.height != height) return
        guard {
            val plane = image.planes[0]
            val raw = ImageBufferPixels(plane.buffer, plane.rowStride / 4, width, height)
            val src: PixelSource = if (level < 0.999f) LiftedPixels(raw, 1f / level) else raw
            // The frame's own stamp, not the moment this thread got round to it: see [GapClock.frameMs].
            after(e, e.onFrame(src, GapClock.frameMs(image.timestamp)))
            captureHandler.removeCallbacks(quiet)
            captureHandler.postDelayed(quiet, QUIET_MS)
        }
    }

    /** Any thread: a finger has touched the screen, and a scroll may be about to start. */
    fun arm() {
        if (!enabled) return
        captureHandler.post {
            guard {
                engine?.let { e ->
                    e.arm(GapClock.nowMs())
                    invalidate()
                    captureHandler.removeCallbacks(tick)
                    captureHandler.postDelayed(tick, TICK_AFTER_ARM_MS)
                }
            }
        }
    }

    /**
     * Capture thread. Corrects a grey thumbnail of a captured frame for the shade that was on
     * the glass when it was captured, at [capturedAtMs], so that the change detectors see the
     * page, not our shade.
     */
    fun correctThumb(thumb: IntArray, srcW: Int, srcH: Int, capturedAtMs: Double) {
        if (!enabled) return
        val e = engine ?: return
        guard {
            val shown = e.displayedAt(capturedAtMs) ?: return@guard
            if (shown.rectCount == 0) return@guard
            val cov = Unshade.thumbCoverage(shown.rects, shown.rectCount, shown.shift, srcW, srcH, thumb.size.let { Math.sqrt(it.toDouble()).toInt() })
            Unshade.correctThumb(thumb, cov, e.style)
        }
    }

    /**
     * Any thread. Restores paper under the shade in [bitmap] — a private copy of a frame that
     * is about to be read — so that OCR and the balloon finder see the page as it is. The shade
     * to take off is the one that was on the glass when the frame was captured, at [capturedAtMs].
     *
     * Works a band of rows at a time, so a phone does not hold a second ten-megabyte copy of
     * the frame. Not a place for [guard]: this runs off the capture thread, which owns the engine
     * and must not be reset from here — a failure just leaves the frame as it was.
     */
    fun unshade(bitmap: Bitmap, capturedAtMs: Double) {
        if (!enabled) return
        val e = engine ?: return
        try {
            val shown = e.displayedAt(capturedAtMs) ?: return
            if (shown.rectCount == 0) return
            val w = bitmap.width
            val h = bitmap.height
            val style = e.style
            val band = IntArray(w * UNSHADE_BAND)
            var y = 0
            while (y < h) {
                val rows = minOf(UNSHADE_BAND, h - y)
                if (touches(shown, y + 0, y + rows)) {
                    bitmap.getPixels(band, 0, w, 0, y, w, rows)
                    Unshade.restore(band, w, w, rows, shown.rects, shown.rectCount, shown.shift - y, style)
                    bitmap.setPixels(band, 0, w, 0, y, w, rows)
                }
                y += rows
            }
        } catch (_: Throwable) {
            // leave the frame as it was
        }
    }

    /** Whether any rectangle of [shown] reaches rows [y0, y1). */
    private fun touches(shown: ShadeEngine.Displayed, y0: Int, y1: Int): Boolean {
        for (i in 0 until shown.rectCount) {
            val top = shown.rects[i * 4 + 1] + shown.shift
            val bottom = shown.rects[i * 4 + 3] + shown.shift
            if (bottom > y0 && top < y1) return true
        }
        return false
    }

    /** Main thread, on teardown. */
    fun shutdown() {
        enabled = false
        captureHandler.removeCallbacks(quiet)
        captureHandler.removeCallbacks(tick)
        worker.shutdownNow()
        view?.visible = false
        view?.source = null
        view?.onDrawn = null
        view = null
        engine = null
    }

    // ---------------------------------------------------------------------------------------

    /** Hands a detection to the worker and the result back to the capture thread. */
    private fun after(e: ShadeEngine, job: DetectJob?) {
        invalidate()
        if (job == null) return
        try {
            worker.execute {
                val result = try {
                    job.run()
                } catch (t: Throwable) {
                    null
                }
                if (result != null) {
                    captureHandler.post {
                        guard {
                            if (engine === e) {
                                e.onDetected(job, result, GapClock.nowMs())
                                invalidate()
                            }
                        }
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // shutting down
        }
    }

    private fun invalidate() {
        view?.invalidate()
    }

    /** Runs [block], and turns the shade off if it keeps failing: it must never hurt the reader. */
    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            failures++
            if (failures >= MAX_FAILURES && !tripped) {
                tripped = true
                enabled = false
                engine?.reset(GapClock.nowMs())
                main.post {
                    view?.visible = false
                    onProblem("Dark gaps stopped: ${t.javaClass.simpleName}")
                }
            }
        }
    }

    private companion object {
        /** No frame for this long: the screen is still, and the page has stopped. */
        const val QUIET_MS = 130L

        /** A touch that did not become a scroll is let go after the arm margin's lifetime. */
        const val TICK_AFTER_ARM_MS = 340L

        const val MAX_FAILURES = 3

        /** Rows of a frame worked on at a time when the shade is taken back off it. */
        const val UNSHADE_BAND = 256
    }
}

/** A captured frame held in a bitmap, for the exact pass at rest. */
class BitmapPixels(private val bitmap: Bitmap, override val width: Int, override val height: Int) : PixelSource {
    override fun readRow(y: Int, dst: IntArray) {
        bitmap.getPixels(dst, 0, width, 0, y, width, 1)
    }

    override fun pixel(x: Int, y: Int): Int = bitmap.getPixel(x, y)
}
