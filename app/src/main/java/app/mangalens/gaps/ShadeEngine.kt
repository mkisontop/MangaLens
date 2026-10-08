package app.mangalens.gaps

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The numbers that govern how the shade rides a scroll. All times are milliseconds. */
class ShadeTuning(
    /** How far ahead of the newest measurement the overlay is drawn, in frame intervals: capture delay plus the time to reach the glass. */
    val leadFrames: Float = 3.2f,
    val leadMinMs: Float = 14f,
    val leadMaxMs: Float = 80f,

    /** Pixels the shade is always held back from an edge while moving. */
    val marginBasePx: Int = 3,

    /** Margin per pixel of travel in the timing's uncertainty. */
    val marginPerLeadPx: Float = 0.85f,

    /**
     * Extra margin for a moment after the page starts to move. A scroll's first frames
     * show almost no speed, and a moment later there is plenty; nothing measured can
     * foresee it, so for [onsetMs] the margin is padded, tapering away.
     */
    val onsetBoostPx: Int = 22,
    val onsetMs: Double = 240.0,

    /** Share of a lead's travel kept as margin against the page stopping dead. */
    val stopRisk: Float = 0.3f,

    /** Fewest milliseconds between detections while the page moves. */
    val detectIntervalMs: Double = 40.0,

    /** A page that has not moved for this long is at rest, and gets the exact pass. */
    val settleMs: Double = 110.0,

    /** A detection that has not come back after this long is given up on. */
    val jobTimeoutMs: Double = 500.0,

    /** Longest the overlay is extrapolated past the newest measurement. */
    val maxExtrapolateMs: Float = 100f,

    /** How long unmeasurable motion is tolerated, coasting on the last speed, before the shade is dropped. */
    val maxCoastMs: Double = 135.0,

    /** How long a featureless screen — a very long gutter — may scroll by before the engine stops trusting its own dead reckoning. */
    val maxFlatMs: Double = 20_000.0,

    /** How long a page that once showed art stays a manhwa when a screen of pure gutter scrolls by. */
    val artMemoryMs: Double = 120_000.0,

    /** Frames closer together than this are not all looked at. */
    val minTrackGapMs: Double = 6.0,

    /** Slowest speed, rows per millisecond, at which the overlay's lead is calibrated. */
    val calibrateMinSpeed: Float = 0.12f,

    /** Share of each new lead measurement taken into the running estimate, once it has settled. */
    val calibrateGain: Float = 0.2f,

    /** The first few measurements count for more: the starting lead is only a guess. */
    val calibrateEarlyGain: Float = 0.5f,
    val calibrateEarlyCount: Int = 6,

    /** Longest lead the calibration may settle on. */
    val leadCeilingMs: Float = 160f,

    /**
     * Margin put up the instant a finger touches the screen, before the page has moved: a
     * touch is how almost every scroll begins, and the overlay cannot react to the first
     * rows of motion until a frame has been captured, measured and drawn.
     */
    val armMarginPx: Int = 32,

    /** How long that margin stays up if no scroll follows: a tap, not a drag. */
    val armMs: Double = 320.0,

    /**
     * How long the margin waits, once the speed no longer asks for all of it, before it starts
     * to give it back; and then how fast: the excess decays with [marginReleaseMs] as its time
     * constant, and never slower than [marginReleasePxPerSec]. A margin that followed the speed
     * frame by frame moved every edge of the shade in and out with each waver of the finger;
     * one that let go too slowly kept a slowing fling's gutters pale long after it needed to.
     */
    val marginHoldMs: Double = 100.0,
    val marginReleaseMs: Double = 220.0,
    val marginReleasePxPerSec: Double = 90.0,

    /**
     * How the speed and the acceleration are read from the frames. Each new frame's speed is
     * measured over at least [speedBaseMs] — the page moves a whole number of rows from one frame
     * to the next, and over a single frame at 120 Hz a row either way is a large part of the
     * speed — and taken into the running speed with [speedTauMs] as the time constant; the
     * acceleration likewise, over [accelBaseMs] and with [accelTauMs]. Time constants, not shares
     * per frame, so that the shade rides a 120 Hz screen as steadily as a 60 Hz one.
     */
    val speedBaseMs: Double = 14.0,
    val speedTauMs: Double = 21.0,
    val accelBaseMs: Double = 30.0,
    val accelTauMs: Double = 47.0,

    /** How much of the measured acceleration the prediction believes: it is noisy. */
    val accelTrust: Float = 0.65f,
)

/**
 * What the view needs to draw: the rectangles, where the page was when they were found
 * and where it is now, and how fast it is moving.
 *
 * The rectangles are in the coordinates of the frame they were found on; [baseOffset] is the
 * page's offset then and [offset] its offset at [offsetAtMs]. The overlay is drawn at
 * `offset - baseOffset` rows from where it was found, plus a prediction of where the page
 * will be by the time the pixels reach the glass — see [shiftAt].
 */
class ShadeSnapshot(
    val rects: IntArray,
    val rectCount: Int,
    val baseOffset: Int,
    val offset: Int,
    val offsetAtMs: Double,
    /** Rows per millisecond; positive when the page moves down the screen. */
    val velocity: Float,
    /** Rows per millisecond per millisecond. */
    val accel: Float,
    val frameMs: Float,
    val leadMs: Float,
    val maxExtrapolateMs: Float,
    val moving: Boolean,
    /**
     * The screen rows [pageTop, pageBottom) the page scrolls in. The bars above and below — the
     * browser's, a site's header and footer — stay put while the rectangles ride the page, and
     * nothing is drawn on them.
     */
    val pageTop: Int = 0,
    val pageBottom: Int = Int.MAX_VALUE,
    /** How much of [accel] the prediction believes. */
    val accelTrust: Float = 0.65f,
) {
    /**
     * Calls [f] with each rectangle as it is drawn [shift] rows down, cut to the page's rows and
     * to a screen [height] rows tall; rectangles left with nothing are skipped.
     */
    inline fun forEachDrawn(shift: Int, height: Int, f: (x0: Int, y0: Int, x1: Int, y1: Int) -> Unit) {
        val lo = max(pageTop, 0)
        val hi = min(pageBottom, height)
        for (i in 0 until rectCount) {
            val y0 = max(rects[i * 4 + 1] + shift, lo)
            val y1 = min(rects[i * 4 + 3] + shift, hi)
            if (y1 > y0) f(rects[i * 4], y0, rects[i * 4 + 2], y1)
        }
    }

    /** Rows to draw the rectangles below where they were found, for a frame drawn at [nowMs]. */
    fun shiftAt(nowMs: Double): Int {
        var shift = (offset - baseOffset).toFloat()
        if (moving) {
            // Never extrapolate far: if frames stop, the page has stopped.
            val cap = min(maxExtrapolateMs, leadMs + 1.0f * frameMs)
            var dt = ((nowMs - offsetAtMs).toFloat() + leadMs).coerceIn(0f, cap)
            // A page that is slowing down does not turn round: stop the prediction where it would stop.
            val a = accelTrust * accel
            if (a * velocity < 0f) dt = min(dt, -velocity / a)
            shift += velocity * dt + 0.5f * a * dt * dt
        }
        return shift.roundToInt()
    }

    companion object {
        val EMPTY = ShadeSnapshot(IntArray(0), 0, 0, 0, 0.0, 0f, 0f, 16f, 30f, 100f, false)
    }
}

/** A detection to run — anywhere, on any thread — and hand back to [ShadeEngine.onDetected]. */
class DetectJob internal constructor(
    private val planes: Planes,
    internal val marginPx: Int,
    private val artRecently: Boolean,
    internal val full: Boolean,
    internal val hardEpoch: Int,
    internal val moveEpoch: Int,
    internal val baseOffset: Int,
    internal val startedMs: Double,
    /** The frame row the planes start at: their row 0 is this row of the frame. */
    private val rowOrigin: Int = 0,
) {
    fun run(params: GapParams = GapParams()): GapResult =
        GapFinder.find(planes, marginPx, params, artRecently).let { if (rowOrigin == 0) it else it.shiftedDown(rowOrigin) }
}

/**
 * Drives the shade: watches frames, follows the page, schedules detections, and publishes
 * what the overlay should show. It owns no thread and no clock — a caller feeds it frames
 * with timestamps and runs the jobs it asks for — so one implementation serves the capture
 * thread on a phone and a latency simulation in a test.
 *
 * Not thread-safe: every method but [snapshot] belongs to one thread, the capture thread.
 *
 * The page is always in one of two situations, and the engine plays them differently.
 *
 *  - *Moving.* A fast, half-resolution pass runs every few dozen milliseconds. Between
 *    passes the tracker measures how far the page moved, and the overlay is carried by that
 *    measurement and a prediction of where the page will be when the next frame reaches the
 *    glass. The result is pulled back from every edge by a margin that grows with speed:
 *    whatever the prediction gets wrong is paid for in a strip of white at a gutter's edge
 *    for a few frames, never in darkened art.
 *  - *At rest.* When the page has stopped, one exact full-resolution pass replaces
 *    everything, with no margin: the edges land on the pixel.
 *
 * Two safety valves drop the shade rather than let it stand over something it was not
 * made for: a frame that is not the same page as the last one (a page turn), and a probe of
 * the deep inside of every gap, which must still be paper.
 */
class ShadeEngine(
    private val width: Int,
    private val height: Int,
    style: ShadeStyle = ShadeStyle(),
    private val params: GapParams = GapParams(),
    private val tuning: ShadeTuning = ShadeTuning(),
) {

    var style: ShadeStyle = style
        private set

    @Volatile
    var snapshot: ShadeSnapshot = ShadeSnapshot.EMPTY
        private set

    private val tracker = ScrollTracker(width, height)

    var ignoreTopRows = 0
    var ignoreBottomRows = 0

    // ---- state of the page ----
    private var offset = 0
    private var velocity = 0f
    private var accel = 0f

    /** How much the speed has been changing lately, whichever way: a page that has just stopped may be about to reverse. */
    private var accelUnc = 0f
    private var frameMs = 16f

    /** The lead in use: starts from the tuning's guess, and is corrected by what the frames show. */
    private var leadEstMs = -1f

    /** How wrong, in milliseconds of travel, the overlay has been lately. Sets the margin. */
    private var lagErrMs = -1f
    private var calibrations = 0
    private var prevProfile: IntArray? = null
    private var prevAtMs = -1.0
    private var lastFrameAtMs = -1.0
    private var lastMoveAtMs = -1e9
    private var motionStartMs = -1e9
    /** Since when the motion has not been measurable, coasting on the last speed, or on a featureless screen; -1 while it has been. */
    private var coastingSinceMs = -1.0
    private var flatSinceMs = -1.0

    /** The page's offset, and the running speed, at the last frames measured — newest last — for speeds read over more than one frame. */
    private val histAt = DoubleArray(HISTORY)
    private val histOffset = IntArray(HISTORY)
    private val histSpeed = FloatArray(HISTORY)
    private var histCount = 0

    // ---- the shade ----
    /** What is published: the newest detection, pulled further back from the edges if speed or a touch asks for it. */
    private var rects = IntArray(0)
    private var rectCount = 0
    private var srcRects = IntArray(0)
    private var srcCount = 0
    private var srcMargin = 0
    private var appliedMargin = 0
    private var armedUntilMs = -1e9

    /** Since when the margin wanted has been below the one applied, and when it was last given back; -1 while it is not. */
    private var marginLowSinceMs = -1.0
    private var marginGivenAtMs = -1.0
    private var baseOffset = 0
    private var artSeenAtMs = -1e12
    private var lastInstallMs = -1e9
    private var signatureChecked = false

    /** The screen rows the page scrolls in, as the newest detection found them. */
    private var pageTop = 0
    private var pageBottom = Int.MAX_VALUE

    // ---- detections ----
    private var hardEpoch = 0
    private var moveEpoch = 0
    private var needFull = true
    private var pending: DetectJob? = null
    private var lastDetectAtMs = -1e9

    private class DrawRecord(val snap: ShadeSnapshot, val shift: Int, val atMs: Double) {
        /**
         * For each calibration column, the rows of the rectangle edges that cross it — coded
         * `(row shl 1) or kind`, kind 0 for a top edge and 1 for a bottom edge — sorted, in
         * the rectangles' own coordinates. A record never changes, so this is built once.
         */
        var edges: Array<IntArray>? = null

        /** The record as it reached the glass, cut to the page's rows; built once, when first asked for. */
        var cut: Displayed? = null
    }

    private val calibrationCols = IntArray(CAL_COLS) { ((it + 0.5f) * width / CAL_COLS).toInt().coerceIn(0, width - 1) }

    private fun edgesOf(rec: DrawRecord): Array<IntArray> {
        rec.edges?.let { return it }
        val sn = rec.snap
        val built = Array(CAL_COLS) { c ->
            val x = calibrationCols[c]
            val list = IntList(64)
            for (r in 0 until sn.rectCount) {
                if (x >= sn.rects[r * 4] && x < sn.rects[r * 4 + 2]) {
                    list.add(sn.rects[r * 4 + 1] shl 1)
                    list.add((sn.rects[r * 4 + 3] shl 1) or 1)
                }
            }
            list.a.copyOf(list.n).also { it.sort() }
        }
        rec.edges = built
        return built
    }

    private val drawLog = arrayOfNulls<DrawRecord>(DRAW_LOG)
    private var drawHead = 0

    /**
     * The view drew [snap], [shift] rows below where its rectangles were found. Called from
     * the UI thread. What is on screen is later read back out of the frames, and this is
     * what it is compared with.
     */
    fun noteDraw(snap: ShadeSnapshot, shift: Int, nowMs: Double) {
        synchronized(drawLog) {
            drawLog[drawHead % DRAW_LOG] = DrawRecord(snap, shift, nowMs)
            drawHead++
        }
    }

    /** A shade as it was drawn, and where: [rects] are cut to what reached the glass. */
    class Displayed(val rects: IntArray, val rectCount: Int, val shift: Int)

    /** What [rec] put on the glass: its rectangles cut to the page's rows, in their own coordinates. */
    private fun displayed(rec: DrawRecord): Displayed {
        val sn = rec.snap
        if (sn.pageTop <= 0 && sn.pageBottom >= height) return Displayed(sn.rects, sn.rectCount, rec.shift)
        rec.cut?.let { return it }
        val out = IntArray(sn.rectCount * 4)
        var n = 0
        sn.forEachDrawn(rec.shift, height) { x0, y0, x1, y1 ->
            out[n * 4] = x0
            out[n * 4 + 1] = y0 - rec.shift
            out[n * 4 + 2] = x1
            out[n * 4 + 3] = y1 - rec.shift
            n++
        }
        return Displayed(out, n, rec.shift).also { rec.cut = it }
    }

    /**
     * The shade that is on the glass in a frame that arrived at [nowMs]: the newest draw old
     * enough to have got there. Null when nothing was drawn. Used to tell the rest of the app
     * what the screen would look like without the shade.
     */
    fun displayedAt(nowMs: Double): Displayed? {
        val cutoff = nowMs - leadForRead()
        synchronized(drawLog) {
            val n = min(drawHead, DRAW_LOG)
            var fallback: DrawRecord? = null
            for (i in 0 until n) {
                val rec = drawLog[(drawHead - 1 - i) % DRAW_LOG] ?: continue
                if (fallback == null) fallback = rec
                if (rec.atMs <= cutoff) return displayed(rec)
            }
            // Every draw is newer than the cutoff: the shade came up just now; the oldest is the best guess.
            val oldest = drawLog[(drawHead - n) % DRAW_LOG] ?: return null
            return displayed(oldest)
        }
    }

    /** The app's own controls, as the last [setExclusions] gave them. */
    private var keepOut: List<IntArray> = emptyList()

    /** Rectangles to leave unmeasured and unshaded: the app's own controls. */
    fun setExclusions(rects: List<IntArray>) {
        keepOut = rects
        tracker.setExclusions(rects)
    }

    /** The shade is switched off, or the screen changed shape. */
    fun reset(nowMs: Double) {
        prevProfile = null
        prevAtMs = -1.0
        velocity = 0f
        accel = 0f
        accelUnc = 0f
        coastingSinceMs = -1.0
        flatSinceMs = -1.0
        histCount = 0
        pending = null
        dropShade()
        publish(nowMs, false)
    }

    // ---------------------------------------------------------------------------------------
    // Frames
    // ---------------------------------------------------------------------------------------

    /**
     * A frame arrived at [nowMs]. Returns the detection it calls for, if any; the caller
     * runs it and returns the result through [onDetected].
     */
    fun onFrame(src: PixelSource, nowMs: Double): DetectJob? {
        if (src.width != width || src.height != height) return null
        val sinceLast = if (lastFrameAtMs < 0) Double.MAX_VALUE else nowMs - lastFrameAtMs
        if (sinceLast < tuning.minTrackGapMs) return null
        if (sinceLast in 2.0..60.0) frameMs = 0.85f * frameMs + 0.15f * sinceLast.toFloat()
        lastFrameAtMs = nowMs

        // The page's motion is read from the page: the bars that stay put above and below it
        // would only argue that nothing moved.
        val y0 = max(ignoreTopRows, pageTop).coerceIn(0, height)
        val y1 = min(height - ignoreBottomRows, pageBottom).coerceIn(y0, height)
        val profile = tracker.profile(src, y0, y1, style)
        val prev = prevProfile
        var dy = 0
        var measured = false
        var jumped = false
        var flat = false
        if (prev != null) {
            val dt = (nowMs - prevAtMs).coerceAtLeast(1.0)
            val centre = (velocity * dt).roundToInt()
            val m = tracker.match(prev, profile, y0, y1, centre)
            when {
                m.measured -> {
                    dy = m.dy
                    measured = true
                }
                m.jumped -> jumped = true
                else -> {
                    dy = if (isMoving(nowMs)) centre else 0
                    // A frame with no edges at all — a long gutter filling the screen — says
                    // nothing about the page's motion; it is not evidence that tracking is lost.
                    flat = m.activity < ScrollTracker.MIN_ACTIVITY
                }
            }
            val frames = (dt / 16.7).toFloat()
            if (measured) {
                measureMotion(offset + dy, dy, nowMs, dt)
                coastingSinceMs = -1.0
                flatSinceMs = -1.0
            } else if (!jumped) {
                if (flat) {
                    // Coast on the last speed, easing off: a gutter this long can end at any moment.
                    if (flatSinceMs < 0) flatSinceMs = nowMs
                    velocity *= Math.pow(0.985, frames.toDouble()).toFloat()
                    accel *= Math.pow(0.9, frames.toDouble()).toFloat()
                } else {
                    if (coastingSinceMs < 0) coastingSinceMs = prevAtMs
                    velocity *= Math.pow(0.92, frames.toDouble()).toFloat()
                    accel *= Math.pow(0.8, frames.toDouble()).toFloat()
                }
                histCount = 0
            }
        }
        prevProfile = profile
        prevAtMs = nowMs

        val lost = (coastingSinceMs >= 0 && nowMs - coastingSinceMs > tuning.maxCoastMs) ||
            (flatSinceMs >= 0 && nowMs - flatSinceMs > tuning.maxFlatMs)
        if (jumped || lost) {
            // Not the page we were following: a turn, a jump, or a screen with nothing to hold on to.
            velocity = 0f
            accel = 0f
            coastingSinceMs = -1.0
            flatSinceMs = -1.0
            histCount = 0
            dropShade()
            publish(nowMs, false)
            return maybeSettle(src, nowMs)
        }

        if (dy != 0) {
            if (!isMoving(nowMs)) motionStartMs = nowMs
            offset += dy
            lastMoveAtMs = nowMs
            moveEpoch++
            needFull = true
        }
        if (measured && dy != 0) calibrate(src, y0, y1)

        if (rectCount > 0 && !probesHold(src)) {
            dropShade()
            publish(nowMs, false)
            return maybeSettle(src, nowMs)
        }

        if (!signatureChecked && !isMoving(nowMs) && rectCount > 0 &&
            nowMs - lastInstallMs >= leadMs() + 3f * frameMs
        ) {
            if (checkSignature(src)) {
                // The style changed: what was found under the wrong window is to be found again.
                dropShade()
                publish(nowMs, false)
                return maybeSettle(src, nowMs)
            }
        }

        updateMargin(nowMs)
        publish(nowMs, isMoving(nowMs))
        return if (isMoving(nowMs)) maybeFast(src, nowMs) else maybeSettle(src, nowMs)
    }

    /**
     * Checks, once the shade has been on the glass long enough to be in the capture, that
     * paper under it looks the way the style says it should.
     *
     * Everything the finder knows about its own output rests on that: paper under the shade
     * is read as paper, so the shade never mistakes itself for ink and takes itself down. The
     * arithmetic (a black slab at 90% over white leaves 25) holds for the gamma-space blending
     * every Android compositor does — but if a device blended differently, the grey would
     * be another, and the shade would flicker. So the glass is asked: if the middle of the
     * rectangles, which is paper, shows a steady neutral grey, that grey is learned.
     *
     * Returns true when the style was changed and the shade has to be found again.
     */
    private fun checkSignature(src: PixelSource): Boolean {
        val shift = offset - baseOffset
        var probes = 0
        var shaded = 0
        val grey = IntArray(32)
        var greys = 0
        for (i in 0 until rectCount) {
            val x0 = rects[i * 4]
            val y0 = rects[i * 4 + 1] + shift
            val x1 = rects[i * 4 + 2]
            val y1 = rects[i * 4 + 3] + shift
            if (y1 - y0 < 48 || x1 - x0 < 48) continue
            val y = (y0 + y1) / 2
            if (y !in 0 until height || y < pageTop || y >= pageBottom) continue
            for (k in 1..3) {
                val x = x0 + (x1 - x0) * k / 4
                if (x !in 0 until width) continue
                val p = src.pixel(x, y)
                // Plain white says only that the shade is not on the glass here yet: no evidence either way.
                if (isPaper(p)) continue
                probes++
                if (style.isShadedPaper(p)) {
                    shaded++
                } else {
                    val r = (p ushr 16) and 0xFF
                    val g = (p ushr 8) and 0xFF
                    val b = p and 0xFF
                    if (max(r, max(g, b)) - min(r, min(g, b)) <= 8 && g in 8..170 && greys < grey.size) grey[greys++] = g
                }
            }
            if (probes >= 24) break
        }
        if (probes < 3) return false                          // the shade is not visible yet
        if (shaded * 100 >= 75 * probes) {                     // as the style says: nothing to learn
            signatureChecked = true
            return false
        }
        if (greys * 100 >= 70 * probes) {
            java.util.Arrays.sort(grey, 0, greys)
            val median = grey[greys / 2]
            if (grey[greys - 1] - grey[0] <= 10) {
                style = style.observed(median)
                signatureChecked = true
                return true
            }
        }
        return false                                           // inconclusive: look again
    }

    /**
     * A finger is on the screen. Almost every scroll starts with a touch, and the touch
     * comes before the page moves; the margin goes up now, so that the first rows of
     * motion — which the overlay cannot answer for a few frames — fall on white, not on art.
     */
    fun arm(nowMs: Double) {
        armedUntilMs = nowMs + tuning.armMs
        updateMargin(nowMs)
        publish(nowMs, isMoving(nowMs))
    }

    /** The clock moved on with no frame to say so: lets an unanswered touch lapse. */
    fun tick(nowMs: Double) {
        updateMargin(nowMs)
        publish(nowMs, isMoving(nowMs))
    }

    private fun wantedMargin(nowMs: Double): Int {
        val speed = if (isMoving(nowMs)) marginPx(nowMs) else 0
        val touch = if (nowMs < armedUntilMs) tuning.armMarginPx else 0
        return max(speed, touch)
    }

    /**
     * Brings the margin the shade is pulled back by toward what [wantedMargin] asks for now.
     *
     * The margin belongs to the engine, not to a detection: every detection comes back with
     * none, and the one margin in force is laid on whatever is newest. It grows at once — it is
     * what keeps the shade off the art — and in even steps, so that a margin creeping up with
     * the speed is not rebuilt every frame. It gives way slowly: only once the speed has asked
     * for less for [ShadeTuning.marginHoldMs], and then at [ShadeTuning.marginReleasePxPerSec].
     * A finger's speed wavers from frame to frame, and a margin that followed it moved every
     * edge of the shade in and out with it. With the page at rest and no finger down, it goes
     * at once: the exact pass is coming, and replaces everything.
     */
    private fun updateMargin(nowMs: Double) {
        val want = (wantedMargin(nowMs) + 1) and 1.inv()
        val settled = !isMoving(nowMs) && nowMs >= armedUntilMs
        if (want >= appliedMargin || settled) {
            marginLowSinceMs = -1.0
            setMargin(want)
            return
        }
        if (marginLowSinceMs < 0) {
            marginLowSinceMs = nowMs
            marginGivenAtMs = nowMs
            return
        }
        if (nowMs - marginLowSinceMs < tuning.marginHoldMs) {
            marginGivenAtMs = nowMs
            return
        }
        val dt = nowMs - marginGivenAtMs
        val decay = (appliedMargin - want) * (1.0 - kotlin.math.exp(-dt / tuning.marginReleaseMs))
        val give = max(decay, dt * tuning.marginReleasePxPerSec / 1000.0).toInt() and 1.inv()
        if (give >= 2) {
            setMargin(max(want, appliedMargin - give))
            marginGivenAtMs = nowMs
        }
    }

    /** Lays [margin] pixels of margin on the newest detection. */
    private fun setMargin(margin: Int) {
        val m = max(margin, srcMargin)
        if (m == appliedMargin) return
        appliedMargin = m
        applyMargin()
    }

    /** Rebuilds the published rectangles: the newest detection, pulled back by [appliedMargin]. */
    private fun applyMargin() {
        val extra = appliedMargin - srcMargin
        if (srcCount == 0 || extra <= 0) {
            rects = srcRects
            rectCount = srcCount
        } else {
            val r = GapFinder.erodeRects(srcRects, srcCount, width, height, extra)
            rects = r.rects
            rectCount = r.rectCount
        }
    }

    /**
     * No frame has arrived for a while: the screen is still. [rest] is the last frame, if the
     * caller kept one. The page has stopped, and gets the exact pass.
     */
    fun onQuiet(rest: PixelSource?, nowMs: Double): DetectJob? {
        velocity = 0f
        accel = 0f
        coastingSinceMs = -1.0
        flatSinceMs = -1.0
        histCount = 0
        publish(nowMs, false)
        if (rest == null || rest.width != width || rest.height != height) return null
        // A still screen delivers no frames once the shade is up, so this is where the glass
        // is first read back: is paper under the shade what the style says it is?
        if (!signatureChecked && rectCount > 0 && checkSignature(rest)) {
            dropShade()
            publish(nowMs, false)
        }
        if (!needFull) return null
        return startFull(rest, nowMs)
    }

    /** Asks for the exact pass now, from [src]: the shade has just been switched on. */
    fun kick(src: PixelSource, nowMs: Double): DetectJob? {
        needFull = true
        pending = null
        return startFull(src, nowMs)
    }

    // ---------------------------------------------------------------------------------------
    // Detections
    // ---------------------------------------------------------------------------------------

    fun onDetected(job: DetectJob, result: GapResult, nowMs: Double) {
        if (pending === job) pending = null
        if (job.hardEpoch != hardEpoch) return
        // An exact pass describes a page at rest; if the page has moved since, it is stale.
        if (job.full && job.moveEpoch != moveEpoch) return
        if (result.sawArt) artSeenAtMs = nowMs
        pageTop = result.pageTop
        pageBottom = result.pageBottom
        srcRects = result.rects
        srcCount = result.rectCount
        srcMargin = job.marginPx
        baseOffset = job.baseOffset
        lastInstallMs = nowMs
        if (job.full) needFull = false
        // the margin in force, on the new rectangles; then brought up to date
        appliedMargin = max(appliedMargin, srcMargin)
        applyMargin()
        updateMargin(nowMs)
        publish(nowMs, isMoving(nowMs))
    }

    /**
     * Takes in a frame at [nowMs] that shows the page at offset [pos], [dy] rows from the frame
     * [dt] milliseconds before it.
     *
     * The speed is measured from the newest earlier frame at least [ShadeTuning.speedBaseMs] back:
     * at 120 Hz that is two frames, and a row too many or too few between them is half the error
     * it would be over one. A page that has not moved over that stretch has stopped, and the
     * speed goes to nothing at once: the prediction must not carry the shade on past a page that
     * a finger has stopped dead.
     */
    private fun measureMotion(pos: Int, dy: Int, nowMs: Double, dt: Double) {
        // the previous frame, unless the frames are close enough together to look further back
        var baseAt = prevAtMs
        var baseOffset = offset
        var accelAt = prevAtMs
        var accelSpeed = velocity
        var based = false
        for (i in histCount - 1 downTo 0) {
            val age = nowMs - histAt[i]
            if (!based && age >= tuning.speedBaseMs) {
                baseAt = histAt[i]
                baseOffset = histOffset[i]
                based = true
            }
            if (age >= tuning.accelBaseMs) {
                accelAt = histAt[i]
                accelSpeed = histSpeed[i]
                break
            }
        }
        val span = (nowMs - baseAt).coerceAtLeast(1.0)
        if (dy == 0 && pos == baseOffset) {
            velocity = 0f
            accel = 0f
        } else {
            val inst = ((pos - baseOffset) / span).toFloat()
            val kv = (1.0 - Math.exp(-dt / tuning.speedTauMs)).toFloat()
            val newV = kv * inst + (1 - kv) * velocity
            val aSpan = (nowMs - accelAt).coerceAtLeast(1.0)
            val aInst = ((newV - accelSpeed) / aSpan).toFloat()
            val ka = (1.0 - Math.exp(-dt / tuning.accelTauMs)).toFloat()
            accel = ((1 - ka) * accel + ka * aInst).coerceIn(-MAX_ACCEL, MAX_ACCEL)
            velocity = newV
        }
        accelUnc = max(abs(accel), Math.pow(0.93, dt / 16.7).toFloat() * accelUnc)
        // remember this frame
        if (histCount == HISTORY) {
            System.arraycopy(histAt, 1, histAt, 0, HISTORY - 1)
            System.arraycopy(histOffset, 1, histOffset, 0, HISTORY - 1)
            System.arraycopy(histSpeed, 1, histSpeed, 0, HISTORY - 1)
            histCount--
        }
        histAt[histCount] = nowMs
        histOffset[histCount] = pos
        histSpeed[histCount] = velocity
        histCount++
    }

    private fun isMoving(nowMs: Double) = nowMs - lastMoveAtMs < tuning.settleMs

    /** The lead as other threads may read it: never writes. */
    private fun leadForRead(): Float =
        if (leadEstMs >= 0) leadEstMs else (tuning.leadFrames * frameMs).coerceIn(tuning.leadMinMs, tuning.leadMaxMs)

    private fun leadMs(): Float {
        if (leadEstMs < 0) leadEstMs = (tuning.leadFrames * frameMs).coerceIn(tuning.leadMinMs, tuning.leadMaxMs)
        return leadEstMs
    }

    /**
     * How far a wrong prediction can carry the overlay onto what it was not made for: the
     * speed times how far out the timing may be. The timing's uncertainty is a frame's worth,
     * or what calibration has seen the overlay miss by lately, whichever is more.
     */
    private fun marginPx(nowMs: Double): Int {
        val lead = leadMs()
        val unsure = max(1.2f * frameMs, 1.6f * (if (lagErrMs < 0) 0.6f * lead else lagErrMs))
        val speed = abs(velocity)
        val timing = tuning.marginPerLeadPx * speed * unsure
        // A page can stop dead, and the overlay will have been carried a lead's worth past it.
        val stop = tuning.stopRisk * speed * lead
        // And speed itself is only known as it was a moment ago.
        val accelErr = 0.4f * accelUnc * lead * lead
        val onset = tuning.onsetBoostPx * (1.0 - (nowMs - motionStartMs) / tuning.onsetMs).coerceIn(0.0, 1.0)
        return tuning.marginBasePx + ceil(timing + stop + accelErr + onset).toInt()
    }

    private fun artRecently(nowMs: Double) = nowMs - artSeenAtMs <= tuning.artMemoryMs

    private fun pendingBusy(nowMs: Double): Boolean {
        val p = pending ?: return false
        if (nowMs - p.startedMs > tuning.jobTimeoutMs) {
            pending = null
            return false
        }
        return true
    }

    /**
     * The fast pass, at half resolution, with no margin: the engine lays its own on the result.
     *
     * Its blocks of two rows are laid on the page, not on the screen: they start on the same rows
     * of the page whichever way it has moved. Laid on the screen, a block took in one row of a
     * gutter's edge and one of the art when the page stood one way and two of the gutter when it
     * stood the other, and each pass found the gutter a row longer or shorter than the last.
     */
    private fun maybeFast(src: PixelSource, nowMs: Double): DetectJob? {
        if (pendingBusy(nowMs) || nowMs - lastDetectAtMs < tuning.detectIntervalMs) return null
        lastDetectAtMs = nowMs
        val origin = Math.floorMod(offset, FAST_STEP)
        val planes = if (origin == 0) {
            PlaneBuilder.build(src, FAST_STEP, style, ignoreTopRows, ignoreBottomRows, keepOut)
        } else {
            val shifted = keepOut.map { intArrayOf(it[0], it[1] - origin, it[2], it[3] - origin) }
            PlaneBuilder.build(RowsFrom(src, origin), FAST_STEP, style, max(0, ignoreTopRows - origin), ignoreBottomRows, shifted)
        }
        val job = DetectJob(planes, 0, artRecently(nowMs), false, hardEpoch, moveEpoch, offset, nowMs, origin)
        pending = job
        return job
    }

    private fun maybeSettle(src: PixelSource, nowMs: Double): DetectJob? {
        if (isMoving(nowMs) || !needFull) return null
        return startFull(src, nowMs)
    }

    private fun startFull(src: PixelSource, nowMs: Double): DetectJob? {
        // One exact pass at a time for a given state of the page.
        val p = pending
        if (p != null && p.full && p.moveEpoch == moveEpoch && p.hardEpoch == hardEpoch && pendingBusy(nowMs)) return null
        lastDetectAtMs = nowMs
        val planes = PlaneBuilder.build(src, 1, style, ignoreTopRows, ignoreBottomRows, keepOut)
        val job = DetectJob(planes, 0, artRecently(nowMs), true, hardEpoch, moveEpoch, offset, nowMs)
        pending = job
        return job
    }

    // ---------------------------------------------------------------------------------------
    // Calibration
    // ---------------------------------------------------------------------------------------

    /**
     * Reads, from the frame, how far ahead of or behind the page the overlay on screen was.
     *
     * The shade's own edges are in the capture: wherever a gutter has white margin, the
     * boundary between shaded paper and plain paper is an edge of one of our rectangles. Those
     * edges are looked for in a few columns, and the draw whose rectangles, laid where that
     * draw put them, have edges exactly there is the one that is on the glass in this frame.
     * That draw was positioned with a certain lead; the page has since turned out to be
     * somewhere else by a measurable amount; the amount, divided by the speed, is how much
     * too long or too short the lead was. A running estimate of it is the lead.
     *
     * Nothing about the device is assumed: capture delay, display delay and refresh rate all
     * land in the one number, and it follows them.
     */
    private fun calibrate(src: PixelSource, y0: Int, y1: Int) {
        val v = velocity
        if (abs(v) < tuning.calibrateMinSpeed || y1 - y0 < 64) return
        val log = ArrayList<DrawRecord>(DRAW_LOG)
        synchronized(drawLog) {
            val n = min(drawHead, DRAW_LOG)
            for (i in 0 until n) drawLog[(drawHead - 1 - i) % DRAW_LOG]?.let { log.add(it) }
        }
        if (log.size < 2) return

        // Edges of shaded paper meeting plain paper, in a few columns.
        val ec = IntArray(MAX_EDGES)
        val ey = IntArray(MAX_EDGES)
        val ek = IntArray(MAX_EDGES)   // 0: a rectangle's top edge, 1: its bottom edge
        var n = 0
        for (c in 0 until CAL_COLS) {
            val x = calibrationCols[c]
            var prev = pixelClass(src.pixel(x, y0))
            for (y in y0 + 1 until y1) {
                val cls = pixelClass(src.pixel(x, y))
                if (n < MAX_EDGES) {
                    if (prev == CLASS_SHADED && cls == CLASS_PAPER) { ec[n] = c; ey[n] = y; ek[n] = 1; n++ }
                    else if (prev == CLASS_PAPER && cls == CLASS_SHADED) { ec[n] = c; ey[n] = y; ek[n] = 0; n++ }
                }
                prev = cls
            }
        }
        if (n < 3) return

        // The draw whose rectangles, laid where it put them, have edges exactly there.
        var best: DrawRecord? = null
        var bestScore = 0
        var second = 0
        for (rec in log) {
            val edges = edgesOf(rec)
            var score = 0
            for (i in 0 until n) {
                val col = edges[ec[i]]
                if (col.isEmpty()) continue
                val row = ey[i] - rec.shift
                var hit = false
                for (d in -1..1) {
                    if (java.util.Arrays.binarySearch(col, ((row + d) shl 1) or ek[i]) >= 0) { hit = true; break }
                }
                if (hit) score++
            }
            if (score > bestScore) { second = bestScore; bestScore = score; best = rec }
            else if (score > second) second = score
        }
        val rec = best ?: return
        if (bestScore < 3 || bestScore * 2 < n || second > bestScore - 2) return

        // Where the page is now, against where this draw put the rectangles.
        val ideal = offset - rec.snap.baseOffset
        val e = (rec.shift - ideal).toFloat()
        val ms = (e / v).coerceIn(-90f, 90f)
        val used = rec.snap.leadMs
        val measured = (used - ms).coerceIn(tuning.leadMinMs * 0.5f, tuning.leadCeilingMs)
        // A slow page makes a poor yardstick: the same error in rows is a much larger one in milliseconds.
        val weight = (abs(v) / 0.35f).coerceIn(0.15f, 1f)
        val g = (if (calibrations < tuning.calibrateEarlyCount) tuning.calibrateEarlyGain else tuning.calibrateGain) * weight
        calibrations++
        val cur = leadMs()
        leadEstMs = (1 - g) * cur + g * measured.coerceIn(0.6f * cur, 1.6f * cur)
        lagErrMs = if (lagErrMs < 0) abs(ms) else (1 - g) * lagErrMs + g * abs(ms)
    }

    private fun pixelClass(p: Int): Int = when {
        (p and 0xF0F0F0) == 0xF0F0F0 -> CLASS_PAPER
        style.isShadedPaper(p) -> CLASS_SHADED
        else -> CLASS_OTHER
    }

    // ---------------------------------------------------------------------------------------
    // Safety
    // ---------------------------------------------------------------------------------------

    private fun dropShade() {
        rects = IntArray(0)
        rectCount = 0
        srcRects = IntArray(0)
        srcCount = 0
        srcMargin = 0
        hardEpoch++
        needFull = true
        pending = null
    }

    /**
     * Whether the deep inside of the gaps is still paper. A probe sits well inside a rectangle,
     * further from its edges than the overlay can be out by, so it must find paper — seen
     * plain, or through the shade. Art there means the shade is over something it was not
     * made for, and the tracker has not noticed.
     */
    private fun probesHold(src: PixelSource): Boolean {
        val shift = offset - baseOffset
        val depth = 18 + ceil(abs(velocity) * leadMs() * 2f).toInt()
        var probes = 0
        var bad = 0
        for (i in 0 until rectCount) {
            val x0 = rects[i * 4]
            val y0 = rects[i * 4 + 1] + shift
            val x1 = rects[i * 4 + 2]
            val y1 = rects[i * 4 + 3] + shift
            if (y1 - y0 < 2 * depth + 6 || x1 - x0 < 24) continue
            val cy = (y0 + y1) / 2
            if (cy !in 0 until height || cy < pageTop || cy >= pageBottom) continue
            for (k in 1..3) {
                val x = x0 + (x1 - x0) * k / 4
                if (x !in 0 until width) continue
                probes++
                val p = src.pixel(x, cy)
                if (!(isPaper(p) || style.isShadedPaper(p) || (!signatureChecked && isNeutralDark(p)))) bad++
            }
            if (probes >= 96) break
        }
        return probes < 3 || bad * 100 < 30 * probes || bad < 3
    }

    // ---------------------------------------------------------------------------------------

    /**
     * Publishes what the view is to draw. The page's offset is stamped with the time of the frame
     * it was read from — not with [nowMs], the moment of publishing, which for a detection coming
     * back or a touch is later. Stamped with that, the prediction started over from a page that
     * had in truth moved on, and the shade fell back by the speed times the difference: up to a
     * dozen rows, every time a detection landed, a score of times a second.
     */
    private fun publish(nowMs: Double, moving: Boolean) {
        val at = if (prevAtMs >= 0 && prevAtMs <= nowMs) prevAtMs else nowMs
        snapshot = ShadeSnapshot(
            rects, rectCount, baseOffset, offset, at, velocity, accel, frameMs, leadMs(),
            tuning.maxExtrapolateMs, moving, pageTop, pageBottom, tuning.accelTrust,
        )
    }

    private fun isPaper(p: Int) = (p and 0xF0F0F0) == 0xF0F0F0

    /**
     * A steady dark grey. Until the shade's own grey has been checked against the glass, this
     * is what paper under it might look like on a device that blends differently, and a probe
     * must not take it for art.
     */
    private fun isNeutralDark(p: Int): Boolean {
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        return max(r, max(g, b)) - min(r, min(g, b)) <= 8 && g in 8..170
    }

    private companion object {
        const val DRAW_LOG = 24

        /** Frame pixels to a plane pixel, each way, in the fast pass. */
        const val FAST_STEP = 2

        /** Frames remembered for reading speed and acceleration over more than one. */
        const val HISTORY = 12
        const val CAL_COLS = 10
        const val MAX_EDGES = 48
        const val MAX_ACCEL = 0.03f
        const val CLASS_OTHER = 0
        const val CLASS_PAPER = 1
        const val CLASS_SHADED = 2
    }
}
