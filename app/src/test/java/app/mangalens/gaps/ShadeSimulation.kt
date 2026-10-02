package app.mangalens.gaps

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max

/**
 * The whole loop, on a virtual clock: the page scrolls at the display's refresh rate, the
 * overlay is drawn on each vsync from the engine's newest snapshot and reaches the glass a
 * few frames later, the screen capture delivers each changed frame some milliseconds after
 * it was shown — with the overlay on it — and detections take time to come back.
 *
 * At every vsync it counts the pixels of *art* the visible overlay covers: the one number
 * that decides whether the feature does what it promises.
 */
internal class ShadeSimulation(
    private val strip: Strip,
    private val w: Int,
    private val h: Int,
    private val style: ShadeStyle,
    private val vsyncMs: Double = 1000.0 / 60,
    /** Milliseconds from a frame being on the glass to the engine having it. */
    private val captureLagMs: Double = 16.0,
    /** Vsyncs between the view drawing the overlay and it being on the glass. */
    private val displayFrames: Int = 2,
    /** Milliseconds a detection takes to come back. */
    private val jobMs: Double = 25.0,
    private val quietMs: Double = 130.0,
    tuning: ShadeTuning = ShadeTuning(),
    private val linearBlend: Boolean = false,
) {
    val engine = ShadeEngine(w, h, style, GapParams(), tuning)

    private class Event(val at: Double, val seq: Int, val run: () -> Unit) : Comparable<Event> {
        override fun compareTo(other: Event): Int = if (at != other.at) at.compareTo(other.at) else seq.compareTo(other.seq)
    }

    private companion object {
        const val ONSET_MS = 300.0
        const val STOP_MS = 260.0
    }

    private class Draw(val rects: IntArray, val count: Int, val shift: Int)

    private val queue = PriorityQueue<Event>()
    private var seq = 0
    var trace = false
    var traceFrom = 0.0
    var traceTo = 420.0
    private var firstTop = 0
    private var motionStartMs = 0.0
    private var motionEndMs = 0.0
    private var lastGlass: IntArray? = null
    private var lastDelivered = -1.0
    private var quietGen = 0

    class Report {
        var frames = 0
        var maxDamage = 0
        var damagedFrames = 0
        var totalDamage = 0L
        var coverageMovingSum = 0.0
        var coverageMovingN = 0
        var worstFrame = -1
        val damageLog = ArrayList<String>()
        var restDamage = 0
        var restCoverage = 1.0

        /** Worst damage in the first moments of motion, in steady motion, and just after the page stops. */
        var maxSpeed = 0f
        var onsetMax = 0
        var steadyMax = 0
        var stopMax = 0
        fun log() = damageLog.joinToString(" ")
        override fun toString() = "onset=${onsetMax}px steady=${steadyMax}px stop=${stopMax}px | frames=$frames maxDamage=${maxDamage}px damagedFrames=$damagedFrames " +
            "meanCoverageMoving=${"%.3f".format(if (coverageMovingN == 0) 1.0 else coverageMovingSum / coverageMovingN)} " +
            "restDamage=$restDamage restCoverage=${"%.4f".format(restCoverage)}"
    }

    private fun at(t: Double, run: () -> Unit) {
        queue.add(Event(t, seq++, run))
    }

    private fun drain(upTo: Double) {
        while (queue.isNotEmpty() && queue.peek().at <= upTo) {
            val e = queue.poll()
            e.run()
        }
    }

    private fun runJob(job: DetectJob?, t: Double) {
        if (job == null) return
        at(t + jobMs) {
            val r = job.run()
            engine.onDetected(job, r, t + jobMs)
        }
    }

    /**
     * Plays [scroll] — the strip row at the top of the screen, as a function of time — for
     * [durationMs], then keeps the clock running for [tailMs] with the page still.
     */
    fun run(
        scroll0: (Double) -> Double,
        durationMs: Double,
        tailMs: Double = 700.0,
        /** When set, a touch-down is reported this long before the page first moves. */
        touchLeadMs: Double? = null,
    ): Report {
        val lead = touchLeadMs ?: 0.0
        val scroll: (Double) -> Double = { t -> scroll0((t - lead).coerceAtLeast(0.0)) }
        if (touchLeadMs != null) at(0.0) { engine.arm(0.0) }
        val report = Report()
        val draws = ArrayList<Draw>()
        run {
            // when the page really starts and stops moving, from the profile itself
            var prevTop = Math.round(scroll(0.0))
            var first = -1.0
            var last = 0.0
            var t = 0.0
            while (t <= durationMs) {
                val top = Math.round(scroll(t))
                if (top != prevTop) {
                    if (first < 0) first = t
                    last = t
                }
                prevTop = top
                t += vsyncMs
            }
            motionStartMs = if (first < 0) 0.0 else first
            motionEndMs = last
        }
        val total = durationMs + tailMs
        val vsyncs = (total / vsyncMs).toInt()
        var prevGlass: IntArray? = null

        // The shade is switched on with the page at rest.
        firstTop = Math.round(scroll(0.0)).toInt()
        val first = strip.frame(scroll(0.0).toInt(), h)
        lastGlass = first
        runJob(engine.kick(ArrayPixels(w, h, first), 0.0), 0.0)

        for (k in 0..vsyncs) {
            val t = k * vsyncMs
            drain(t)
            engine.tick(t)
            val top = scroll(minOf(t, durationMs)).let { Math.round(it).toInt() }
            val page = strip.frame(top, h)

            // The view draws, now, from the newest snapshot.
            val snap = engine.snapshot
            val shift = snap.shiftAt(t)
            if (Math.abs(snap.velocity) > report.maxSpeed) report.maxSpeed = Math.abs(snap.velocity)
            if (trace && t in traceFrom..traceTo) println("TRACE t=${"%.0f".format(t)} top=$top truthShift=${firstTop - top} drawnShift=$shift base=${snap.baseOffset} off=${snap.offset} v=${"%.3f".format(snap.velocity)} a=${"%.4f".format(snap.accel)} lead=${"%.0f".format(snap.leadMs)} rects=${snap.rectCount} moving=${snap.moving}")
            draws.add(Draw(snap.rects, snap.rectCount, shift))
            engine.noteDraw(snap, shift, t)

            // What is on the glass at this vsync: the page, with the overlay drawn `displayFrames` ago.
            val shown = draws.getOrNull(k - displayFrames)
            val glass = if (shown == null) page else composite(page, w, h, shown.rects, shown.count, style, shown.shift, linearBlend)

            if (shown != null) score(report, page, shown, k, t > durationMs + 300, t <= durationMs)

            // The capture sees a frame only when the screen changed.
            if (prevGlass == null || !glass.contentEquals(prevGlass)) {
                val deliveredAt = t + captureLagMs
                val frame = glass
                at(deliveredAt) {
                    lastGlass = frame
                    lastDelivered = deliveredAt
                    runJob(engine.onFrame(ArrayPixels(w, h, frame), deliveredAt), deliveredAt)
                    val gen = ++quietGen
                    at(deliveredAt + quietMs) {
                        if (gen == quietGen) runJob(engine.onQuiet(ArrayPixels(w, h, lastGlass!!), deliveredAt + quietMs), deliveredAt + quietMs)
                    }
                }
            }
            prevGlass = glass
            report.frames++
        }
        return report
    }

    private fun score(report: Report, page: IntArray, shown: Draw, k: Int, atRest: Boolean, moving: Boolean) {
        var damage = 0
        for (i in 0 until shown.count) {
            val x0 = shown.rects[i * 4].coerceIn(0, w)
            val x1 = shown.rects[i * 4 + 2].coerceIn(0, w)
            val y0 = (shown.rects[i * 4 + 1] + shown.shift).coerceIn(0, h)
            val y1 = (shown.rects[i * 4 + 3] + shown.shift).coerceIn(0, h)
            for (y in y0 until y1) for (x in x0 until x1) if (!Strip.isPaper(page[y * w + x])) damage++
        }
        if (damage > 0) {
            report.damagedFrames++
            report.totalDamage += damage
            report.damageLog.add("t=${"%.0f".format(k * vsyncMs)}ms:${damage}px")
            if (damage > report.maxDamage) {
                report.maxDamage = damage
                report.worstFrame = k
            }
        }
        if (atRest) report.restDamage = max(report.restDamage, damage)
        val t = k * vsyncMs
        when {
            t < motionStartMs + ONSET_MS -> report.onsetMax = max(report.onsetMax, damage)
            t < motionEndMs -> report.steadyMax = max(report.steadyMax, damage)
            t < motionEndMs + STOP_MS -> report.stopMax = max(report.stopMax, damage)
        }
        if (k % 8 == 0) {
            // coverage against what an exact pass on the clean page would shade
            val ref = GapFinder.find(PlaneBuilder.build(ArrayPixels(w, h, page), 1, style), 0, GapParams(), true)
            if (ref.shadedPixels > 0) {
                val refMask = coverage(ref.rects, ref.rectCount, w, h)
                var hit = 0L
                for (i in 0 until shown.count) {
                    val x0 = shown.rects[i * 4].coerceIn(0, w)
                    val x1 = shown.rects[i * 4 + 2].coerceIn(0, w)
                    val y0 = (shown.rects[i * 4 + 1] + shown.shift).coerceIn(0, h)
                    val y1 = (shown.rects[i * 4 + 3] + shown.shift).coerceIn(0, h)
                    for (y in y0 until y1) for (x in x0 until x1) if (refMask[y * w + x]) hit++
                }
                val c = hit.toDouble() / ref.shadedPixels
                if (moving) {
                    report.coverageMovingSum += c
                    report.coverageMovingN++
                }
                if (atRest) report.restCoverage = c
            }
        }
    }
}

/** A strip with gutters, balloons and lettering to scroll through, longer than any scroll below. */
internal fun scrollStrip(w: Int, seed: Long = 11): Strip {
    val s = Strip(w, 14000, seed)
    var y = 0
    var n = 0
    while (y < 14000) {
        val art = 350 + (n * 97) % 300
        s.art(0, y, w, y + art); s.rules(0, y, w, y + art)
        y += art
        val gap = 250 + (n * 61) % 400
        if (n % 2 == 0) s.balloon(300 + (n * 53) % 200, y + gap / 2, 180, minOf(90, gap / 2 - 20))
        if (n % 3 == 1) s.text(80, y + 30, 640, y + gap - 30)
        y += gap
        n++
    }
    return s
}

/**
 * A strip with one very long gutter — three and a half thousand rows, two balloons far apart
 * — so that scrolling through it fills the whole screen with featureless white.
 */
internal fun longGutterStrip(w: Int): Strip {
    val s = Strip(w, 14000, 21)
    s.art(0, 0, w, 1500); s.rules(0, 0, w, 1500)
    s.balloon(360, 2400, 200, 90)
    s.balloon(300, 4300, 220, 100)
    s.art(0, 5000, w, 14000); s.rules(0, 5000, w, 5400)
    return s
}

/** Scroll profiles: the strip row at the top of the screen, in pixels, as a function of milliseconds. */
internal object Scrolls {
    fun still(startPx: Double): (Double) -> Double = { startPx }

    /** Constant speed from the first instant: the harshest start there is. */
    fun steady(startPx: Double, pxPerSec: Double): (Double) -> Double = { t -> startPx + pxPerSec * t / 1000.0 }

    /** A finger drag: speed ramps from nothing over [rampMs], holds, and the finger stops dead at [stopMs]. */
    fun ramp(startPx: Double, pxPerSec: Double, rampMs: Double, stopMs: Double): (Double) -> Double = { t0 ->
        val t = minOf(t0, stopMs)
        val a = pxPerSec / rampMs            // px/s per ms of ramp
        val s = if (t < rampMs) 0.5 * a * t * t / 1000.0 else 0.5 * a * rampMs * rampMs / 1000.0 + pxPerSec * (t - rampMs) / 1000.0
        startPx + s
    }

    /** A drag that speeds up and slows down, jittering as a finger does, then stops dead. */
    fun drag(startPx: Double): (Double) -> Double = { t ->
        val s = minOf(t, 1800.0) / 1000.0
        val base = ramp(0.0, 520.0, 160.0, 1e9)(t.coerceAtMost(1800.0))
        startPx + base + 52 * Math.sin(s * 5.0) + 6 * Math.sin(s * 17.0)
    }

    /** A drag up to [v0] px/s over [dragMs], released into a fling that decays with time constant [tauSec]. */
    fun fling(startPx: Double, v0: Double, dragMs: Double, tauSec: Double): (Double) -> Double = { t ->
        if (t < dragMs) ramp(startPx, v0, dragMs, 1e9)(t)
        else {
            val atRelease = ramp(startPx, v0, dragMs, 1e9)(dragMs)
            atRelease + v0 * tauSec * (1 - Math.exp(-(t - dragMs) / 1000.0 / tauSec))
        }
    }

    /** Down, then the finger turns round — slowing through zero as a finger must — and back up. */
    fun reverse(startPx: Double, pxPerSec: Double, turnMs: Double): (Double) -> Double = { t ->
        val down = ramp(startPx, pxPerSec, 150.0, 1e9)(minOf(t, turnMs))
        if (t < turnMs) down
        else {
            // velocity goes from +v to -0.8v linearly over 220 ms, then holds
            val dt = (t - turnMs) / 1000.0
            val span = 0.22
            val a = (pxPerSec * 1.8) / span
            val s = if (dt < span) pxPerSec * dt - 0.5 * a * dt * dt else pxPerSec * span - 0.5 * a * span * span - 0.8 * pxPerSec * (dt - span)
            down + s
        }
    }
}

internal fun absI(a: Int) = abs(a)
