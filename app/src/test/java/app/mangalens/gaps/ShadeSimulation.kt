package app.mangalens.gaps

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * The whole loop, on a virtual clock: the page scrolls at the display's refresh rate, the
 * overlay is drawn on each vsync from the engine's newest snapshot and reaches the glass a
 * few frames later, the screen capture delivers each changed frame some milliseconds after
 * it was shown — with the overlay on it — and detections take time to come back.
 *
 * At every vsync it counts the pixels of *art* the visible overlay covers: the one number
 * that decides whether the feature does what it promises. Alongside, it measures what the
 * reader sees of the shade in motion — how steadily it rides the page ([Report.motion], see
 * [MotionMeter]), how much of the gutter stays dark ([Report.moving]), and whether white that
 * belongs to the art was darkened ([Report.whiteMax]).
 *
 * The timing is exact by default: every frame takes [captureLagMs] to arrive, every draw is
 * made on its vsync, every detection takes [jobMs]. The jitter knobs make it as ragged as a
 * phone is; all of them are seeded, so a run is repeatable.
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
    /**
     * What else is on the screen under the shade and does not scroll — the browser's bars, a
     * site's own header and footer — painted over each frame of the page.
     */
    private val fixed: ((IntArray) -> Unit)? = null,
    /** What is drawn over the shade — MangaLens's own controls — and declared to the engine as [exclusions]. */
    private val onTop: ((IntArray) -> Unit)? = null,
    private val exclusions: List<IntArray> = emptyList(),
    private val ignoreTopRows: Int = 0,
    private val ignoreBottomRows: Int = 0,
    /** Screen pixels the shade must never reach, whatever is under them: the fixed bars, the controls. */
    private val keepClear: BooleanArray? = null,
    /** Shown each vsync's glass, for a look at it. */
    private val onGlass: ((Int, IntArray) -> Unit)? = null,
    /**
     * Each frame the capture delivers arrives up to this many milliseconds later than
     * [captureLagMs], uniformly at random. Frames still arrive in the order they were shown, as
     * a capture queue hands them over.
     */
    private val captureJitterMs: Double = 0.0,
    /**
     * Share of the changed frames the capture never delivers, chosen at random. A frame is only
     * lost to a newer one: when it turns out to be the last change, it is delivered a vsync late
     * instead, as a reader that always takes the newest image would still find it.
     */
    private val captureDropShare: Double = 0.0,
    /**
     * The view does not draw exactly on the vsync: the time it reads the snapshot's shift for,
     * and tells the engine it drew at, is the vsync plus up to this many milliseconds, uniformly
     * at random. The draw still reaches the glass [displayFrames] vsyncs on, at the vsync.
     */
    private val drawJitterMs: Double = 0.0,
    /** A detection takes up to this many milliseconds longer than [jobMs], uniformly at random. */
    private val jobJitterMs: Double = 0.0,
    /** Which time the engine is told a frame was taken at. */
    private val timeSource: TimeSource = TimeSource.ARRIVAL,
    /** Seeds the random timing: the same seed plays the same run. */
    seed: Long = 1L,
    /**
     * White pixels of the strip the shade must never cover — the white inside panels, the
     * inside of balloons — as a mask `strip.w * strip.h`, in strip coordinates. Measured as
     * [Report.whiteMax], apart from the art damage, which only counts pixels that are not paper.
     */
    private val protectedWhite: BooleanArray? = null,
    /**
     * The screen rows the page is truly seen in: above and below are the bars that stay put.
     * The motion and the white are measured on these rows only.
     */
    private val pageRows: IntRange = 0 until h,
    /**
     * Coverage while moving is measured on every this-many-th moving vsync; 0 turns it off. Each
     * new position of the page costs an exact pass, so by default every other vsync is enough.
     */
    private val coverageEvery: Int = 2,
    /** Keeps one line of the motion measures per vsync in [Report.timeline]. */
    private val keepTimeline: Boolean = false,
) {
    /** Which clock the timestamps handed to the engine with each frame come from. */
    enum class TimeSource {
        /**
         * When the frame reached the engine: what the capture thread reads off the clock as the
         * image comes in. Capture jitter lands in the measurements.
         */
        ARRIVAL,

        /**
         * When the frame was on the glass, plus the constant [captureLagMs]: a stamp with the
         * capture's delay taken out of the jitter but not out of the time. Capture jitter does not
         * land in the measurements.
         */
        GLASS,

        /**
         * What the app does on a phone: the frame's own stamp, the time it was on the glass, as the
         * time the page was where the frame shows it; and the time it reached the engine besides,
         * for everything else.
         */
        STAMP,
    }

    val engine = ShadeEngine(w, h, style, GapParams(), tuning).also { e ->
        e.ignoreTopRows = ignoreTopRows
        e.ignoreBottomRows = ignoreBottomRows
        e.setExclusions(exclusions)
    }

    init {
        require(protectedWhite == null || (strip.w == w && protectedWhite.size == strip.w * strip.h)) { "the white mask is the strip's" }
        require(pageRows.first >= 0 && pageRows.last < h) { "page rows are screen rows" }
    }

    /** The screen with the page scrolled to [top], before the shade: the page and whatever is fixed over it. */
    private fun screen(top: Int): IntArray = strip.frame(top, h).also { fixed?.invoke(it) }

    private class Event(val at: Double, val seq: Int, val run: () -> Unit) : Comparable<Event> {
        override fun compareTo(other: Event): Int = if (at != other.at) at.compareTo(other.at) else seq.compareTo(other.seq)
    }

    private companion object {
        const val ONSET_MS = 300.0
        const val STOP_MS = 260.0

        /** How long after the page stops its vsyncs count as settling rather than still. */
        const val SETTLE_MS = 600.0

        /** Exact passes kept, by the page's top row, for the coverage reference. */
        const val REFERENCE_CACHE = 512
    }

    /** A draw as the view makes it: the rectangles, how far down, and the rows of the page they are cut to. */
    private class Draw(val rects: IntArray, val count: Int, val shift: Int, val top: Int, val bottom: Int)

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
    private var shownRects = 0

    private val captureRandom = Random(seed)
    private val dropRandom = Random(seed + 1)
    private val drawRandom = Random(seed + 2)
    private val jobRandom = Random(seed + 3)

    /** Changed frames shown so far: a dropped frame is lost only once a newer one exists. */
    private var glassSeq = 0
    private var lastArrivalMs = Double.NEGATIVE_INFINITY

    /** The exact pass on the clean page, by the page's top row: the page repeats a row when it moves slowly. */
    private val references = HashMap<Int, GapResult>()

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

        /** Times the shade was up and the engine took all of it down. */
        var drops = 0

        /** Most pixels of [keepClear] the shade on the glass covered in any one frame. */
        var clearHits = 0

        /** Worst damage in the first moments of motion, in steady motion, and just after the page stops. */
        var maxSpeed = 0f
        var onsetMax = 0
        var steadyMax = 0
        var stopMax = 0

        /**
         * How steadily the shade rode the page, in the page's own coordinates: flicker, edge wobble
         * and pops over the vsyncs in which the page moved, and apart from them the moments after
         * it stopped. A perfect shade scores zero while it rides a steady scroll. See [MotionMeter].
         */
        val motion = MotionMeter.Summary()

        /**
         * [motion], split by what changed on the glass: vsyncs that show the same rectangles as the
         * one before, carried to a new place — where any wobble is the prediction's — and vsyncs
         * that show new ones: a fresh detection, or the margin cut again.
         */
        val motionCarried = MotionMeter.Summary()
        val motionReplaced = MotionMeter.Summary()

        /**
         * How much of the gutter the shade covered while the page moved, on every `coverageEvery`-th
         * moving vsync, against the exact pass on the clean page. See [MovingCoverage].
         */
        val moving = MovingCoverage()

        /**
         * Most pixels of the protected white the shade on the glass covered in any one frame, the
         * most in any frame at rest, and the frames with any.
         */
        var whiteMax = 0
        var whiteRest = 0
        var whiteFrames = 0

        /** Frames the screen changed in, the ones the capture delivered, and the ones it lost to a newer one. */
        var framesChanged = 0
        var framesDelivered = 0
        var framesDropped = 0

        /** One line per vsync of the motion measures, when the simulation was asked to keep them. */
        val timeline = ArrayList<String>()

        fun log() = damageLog.joinToString(" ")
        override fun toString() = "onset=${onsetMax}px steady=${steadyMax}px stop=${stopMax}px | frames=$frames maxDamage=${maxDamage}px damagedFrames=$damagedFrames " +
            "meanCoverageMoving=${"%.3f".format(if (coverageMovingN == 0) 1.0 else coverageMovingSum / coverageMovingN)} " +
            "restDamage=$restDamage restCoverage=${"%.4f".format(restCoverage)} drops=$drops clearHits=$clearHits"

        /** The motion measures, on one line. */
        fun motionLine() = "$motion | carried ${motionCarried.brief()} | replaced ${motionReplaced.brief()} | $moving | " +
            "white max=${whiteMax}px rest=${whiteRest}px frames=$whiteFrames | " +
            "capture changed=$framesChanged delivered=$framesDelivered dropped=$framesDropped"
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
        val done = t + jobMs + (if (jobJitterMs > 0) jobRandom.nextDouble() * jobJitterMs else 0.0)
        at(done) {
            val r = job.run()
            engine.onDetected(job, r, done)
        }
    }

    /** The capture hands over [frame], on the glass at [glassMs], at [arrivedMs]. */
    private fun deliver(report: Report, frame: IntArray, glassMs: Double, arrivedMs: Double) {
        report.framesDelivered++
        lastGlass = frame
        lastDelivered = arrivedMs
        val src = ArrayPixels(w, h, frame)
        val job = when (timeSource) {
            TimeSource.ARRIVAL -> engine.onFrame(src, arrivedMs)
            TimeSource.GLASS -> engine.onFrame(src, arrivedMs, glassMs + captureLagMs)
            TimeSource.STAMP -> engine.onFrame(src, arrivedMs, glassMs)
        }
        runJob(job, arrivedMs)
        val gen = ++quietGen
        at(arrivedMs + quietMs) {
            if (gen == quietGen) runJob(engine.onQuiet(ArrayPixels(w, h, lastGlass!!), arrivedMs + quietMs), arrivedMs + quietMs)
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
        /**
         * Further touch-downs, in the scroll's own milliseconds: a finger going down again for
         * each drag of a stop-and-go scroll.
         */
        touchesMs: List<Double> = emptyList(),
    ): Report {
        val lead = touchLeadMs ?: 0.0
        val scroll: (Double) -> Double = { t -> scroll0((t - lead).coerceAtLeast(0.0)) }
        if (touchLeadMs != null) at(0.0) { engine.arm(0.0) }
        for (touch in touchesMs) at(touch + lead) { engine.arm(touch + lead) }
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

        // Where the page truly is at every vsync, and so whether it is moving there.
        val tops = IntArray(vsyncs + 4) { k -> scroll(minOf(k * vsyncMs, durationMs)).let { Math.round(it).toInt() } }
        fun changed(k: Int) = k in 1 until tops.size && tops[k] != tops[k - 1]
        val meter = MotionMeter(w, h, pageRows.first, pageRows.last + 1, summary = report.motion)
        val mask = BooleanArray(w * h)
        var lastMovingK = -1
        var movingSeen = 0
        var prevShown: Draw? = null
        var prevShownTop = 0
        var shadeSeen = false

        // The shade is switched on with the page at rest.
        firstTop = Math.round(scroll(0.0)).toInt()
        val first = screen(scroll(0.0).toInt()).also { onTop?.invoke(it) }
        lastGlass = first
        runJob(engine.kick(ArrayPixels(w, h, first), 0.0), 0.0)

        for (k in 0..vsyncs) {
            val t = k * vsyncMs
            drain(t)
            engine.tick(t)
            val top = scroll(minOf(t, durationMs)).let { Math.round(it).toInt() }
            val page = screen(top)

            // The view draws, now — or a moment late — from the newest snapshot.
            val snap = engine.snapshot
            if (snap.rectCount == 0 && shownRects > 0) report.drops++
            shownRects = snap.rectCount
            val drawAt = t + (if (drawJitterMs > 0) drawRandom.nextDouble() * drawJitterMs else 0.0)
            val shift = snap.shiftAt(drawAt)
            if (Math.abs(snap.velocity) > report.maxSpeed) report.maxSpeed = Math.abs(snap.velocity)
            if (trace && t in traceFrom..traceTo) println("TRACE t=${"%.0f".format(t)} top=$top truthShift=${firstTop - top} drawnShift=$shift base=${snap.baseOffset} off=${snap.offset} v=${"%.3f".format(snap.velocity)} a=${"%.4f".format(snap.accel)} lead=${"%.0f".format(snap.leadMs)} rects=${snap.rectCount} moving=${snap.moving}")
            draws.add(Draw(snap.rects, snap.rectCount, shift, snap.pageTop, snap.pageBottom))
            engine.noteDraw(snap, shift, drawAt)

            // What is on the glass at this vsync: the page, with the overlay drawn `displayFrames` ago.
            val shown = draws.getOrNull(k - displayFrames)
            val glass = if (shown == null) page.copyOf() else composite(page, w, h, shown.rects, shown.count, style, shown.shift, linearBlend, shown.top, shown.bottom)
            onTop?.invoke(glass)
            onGlass?.invoke(k, glass)

            if (shown != null) score(report, page, top, shown, k, t > durationMs + 300, t <= durationMs)

            // What the reader sees of the shade: a vsync is moving if the page moved into it or
            // moves out of it, or holds for a vsync or two in the middle of a slow scroll.
            val moving = changed(k) || changed(k + 1) || ((changed(k - 1) || changed(k - 2)) && (changed(k + 2) || changed(k + 3)))
            if (moving) lastMovingK = k
            val phase = when {
                moving -> MotionMeter.Phase.MOVING
                lastMovingK >= 0 && (k - lastMovingK) * vsyncMs <= SETTLE_MS -> MotionMeter.Phase.SETTLING
                else -> MotionMeter.Phase.STILL
            }
            if (shown != null && shown.count > 0) shadeSeen = true
            if (shown == null || !shadeSeen) {
                // Nothing on the glass yet: the shade coming up when it is switched on is not motion.
                meter.forget()
                prevShown = null
            } else {
                paint(mask, shown)
                val f = meter.observe(mask, top, phase)
                val before = prevShown
                val carried = before != null && before.rects === shown.rects
                // the same rectangles, moved: how far off the page's own motion they were moved
                val slip = if (before != null && carried) (shown.shift - before.shift) + (top - prevShownTop) else 0
                (if (carried) report.motionCarried else report.motionReplaced).take(f, phase)
                prevShown = shown
                prevShownTop = top
                val white = whiteUnder(mask, top)
                if (white > 0) report.whiteFrames++
                report.whiteMax = max(report.whiteMax, white)
                if (t > durationMs + 300) report.whiteRest = max(report.whiteRest, white)
                var cover = Double.NaN
                if (moving && coverageEvery > 0 && movingSeen++ % coverageEvery == 0) {
                    val ref = reference(top, page)
                    val hit = covered(mask, ref)
                    report.moving.add(hit, ref.shadedPixels, w.toLong() * h, t)
                    if (ref.shadedPixels > 0) cover = hit.toDouble() / ref.shadedPixels
                }
                if (keepTimeline) {
                    report.timeline.add(
                        "k=$k t=${"%.0f".format(t)} top=$top ${phase.name.lowercase()} rects=${shown.count} shift=${shown.shift} " +
                            "${if (carried) "slip=$slip" else "new"} " +
                            "flicker=${f.flicker} edges=${f.matched} maxMove=${f.maxMove} pops=${f.pops} white=$white " +
                            "cover=${if (cover.isNaN()) "-" else "%.2f".format(cover)}",
                    )
                }
            }

            // The capture sees a frame only when the screen changed.
            if (prevGlass == null || !glass.contentEquals(prevGlass)) {
                report.framesChanged++
                val mine = ++glassSeq
                val frame = glass
                val jitter = if (captureJitterMs > 0) captureRandom.nextDouble() * captureJitterMs else 0.0
                val arrive = max(t + captureLagMs + jitter, lastArrivalMs)
                lastArrivalMs = arrive
                val drop = captureDropShare > 0 && dropRandom.nextDouble() < captureDropShare
                if (!drop) {
                    at(arrive) { deliver(report, frame, t, arrive) }
                } else {
                    // Lost to the next changed frame — unless there is none, and the capture still hands this one over.
                    at(arrive + vsyncMs) {
                        if (glassSeq != mine) report.framesDropped++ else deliver(report, frame, t, arrive + vsyncMs)
                    }
                }
            }
            prevGlass = glass
            report.frames++
        }
        return report
    }

    /** Marks in [mask] the screen pixels [shown] shades, cut as it was drawn. */
    private fun paint(mask: BooleanArray, shown: Draw) {
        java.util.Arrays.fill(mask, false)
        for (i in 0 until shown.count) {
            val x0 = shown.rects[i * 4].coerceIn(0, w)
            val x1 = shown.rects[i * 4 + 2].coerceIn(0, w)
            val y0 = (shown.rects[i * 4 + 1] + shown.shift).coerceIn(max(0, shown.top), h)
            val y1 = (shown.rects[i * 4 + 3] + shown.shift).coerceIn(0, minOf(h, shown.bottom))
            if (x1 <= x0) continue
            for (y in y0 until y1) java.util.Arrays.fill(mask, y * w + x0, y * w + x1, true)
        }
    }

    /** Shaded pixels, on the page's rows, over white the art owns: the strip's protected white with its row [top] at the top. */
    private fun whiteUnder(mask: BooleanArray, top: Int): Int {
        val white = protectedWhite ?: return 0
        var n = 0
        for (y in pageRows) {
            val sy = y + top
            if (sy !in 0 until strip.h) continue
            val m = y * w
            val s = sy * strip.w
            for (x in 0 until w) if (mask[m + x] && white[s + x]) n++
        }
        return n
    }

    /** The exact pass on the clean page with its row [top] at the top of the screen, kept for when the page comes back to it. */
    private fun reference(top: Int, page: IntArray): GapResult = references.getOrPut(top) {
        if (references.size >= REFERENCE_CACHE) references.clear()
        GapFinder.find(PlaneBuilder.build(ArrayPixels(w, h, page), 1, style, ignoreTopRows, ignoreBottomRows, exclusions), 0, GapParams(), true)
    }

    /** Pixels of [ref]'s rectangles that [mask] shades. */
    private fun covered(mask: BooleanArray, ref: GapResult): Long {
        var hit = 0L
        for (i in 0 until ref.rectCount) {
            val x0 = ref.rects[i * 4].coerceIn(0, w)
            val x1 = ref.rects[i * 4 + 2].coerceIn(0, w)
            val y0 = ref.rects[i * 4 + 1].coerceIn(0, h)
            val y1 = ref.rects[i * 4 + 3].coerceIn(0, h)
            for (y in y0 until y1) for (x in x0 until x1) if (mask[y * w + x]) hit++
        }
        return hit
    }

    private fun nearPaper(page: IntArray, x: Int, y: Int, reach: Int): Boolean {
        for (dy in -reach..reach) for (dx in -reach..reach) {
            val xx = x + dx
            val yy = y + dy
            if (xx in 0 until w && yy in 0 until h && Strip.isPaper(page[yy * w + xx])) return true
        }
        return false
    }

    private fun score(report: Report, page: IntArray, top: Int, shown: Draw, k: Int, atRest: Boolean, moving: Boolean) {
        var damage = 0
        var clear = 0
        for (i in 0 until shown.count) {
            val x0 = shown.rects[i * 4].coerceIn(0, w)
            val x1 = shown.rects[i * 4 + 2].coerceIn(0, w)
            val y0 = (shown.rects[i * 4 + 1] + shown.shift).coerceIn(max(0, shown.top), h)
            val y1 = (shown.rects[i * 4 + 3] + shown.shift).coerceIn(0, minOf(h, shown.bottom))
            for (y in y0 until y1) for (x in x0 until x1) {
                if (keepClear != null && keepClear[y * w + x]) clear++
                if (Strip.isPaper(page[y * w + x])) continue
                // At rest the exact pass takes in the anti-aliased edge of the art on purpose: a pixel
                // of ramp within three of paper is the edge, not damage. In motion nothing is tolerated.
                if (atRest && nearPaper(page, x, y, 3)) continue
                damage++
            }
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
        report.clearHits = max(report.clearHits, clear)
        val t = k * vsyncMs
        when {
            t < motionStartMs + ONSET_MS -> report.onsetMax = max(report.onsetMax, damage)
            t < motionEndMs -> report.steadyMax = max(report.steadyMax, damage)
            t < motionEndMs + STOP_MS -> report.stopMax = max(report.stopMax, damage)
        }
        if (k % 8 == 0) {
            // coverage against what an exact pass on the clean page would shade
            val ref = reference(top, page)
            if (ref.shadedPixels > 0) {
                val refMask = coverage(ref.rects, ref.rectCount, w, h)
                var hit = 0L
                for (i in 0 until shown.count) {
                    val x0 = shown.rects[i * 4].coerceIn(0, w)
                    val x1 = shown.rects[i * 4 + 2].coerceIn(0, w)
                    val y0 = (shown.rects[i * 4 + 1] + shown.shift).coerceIn(max(0, shown.top), h)
                    val y1 = (shown.rects[i * 4 + 3] + shown.shift).coerceIn(0, minOf(h, shown.bottom))
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
 * [scrollStrip], made harder the way real pages are: the paper is off-white and grainy, the
 * edges of the art are soft (a ramp of two or three greys rather than a cliff), and the art
 * has dark ink in it right up to its border. Nothing here is stricter than a real site's images.
 */
internal fun gritty(strip: Strip, paper: Int = 245, grain: Int = 3, soft: Boolean = true, seed: Long = 5): Strip {
    val rnd = kotlin.random.Random(seed)
    val w = strip.w
    val h = strip.h
    val px = strip.px
    for (y in 0 until h) for (x in 0 until w) {
        val p = px[y * w + x]
        if ((p and 0xFFFFFF) == 0xFFFFFF) {
            // grain only ever dims the paper; it stays inside the paper band
            val v = (paper - rnd.nextInt(grain + 1)).coerceIn(240, 255)
            px[y * w + x] = Strip.rgb(v, v, v)
        }
    }
    if (soft) {
        // one pixel of ramp either side of every vertical edge between paper and anything darker
        for (y in 1 until h - 1) for (x in 0 until w) {
            val a = px[(y - 1) * w + x]
            val b = px[y * w + x]
            val c = px[(y + 1) * w + x]
            fun lum(p: Int) = minOf((p ushr 16) and 0xFF, (p ushr 8) and 0xFF, p and 0xFF)
            if (lum(b) >= 240 && lum(c) < 120 && lum(a) >= 240) {
                val m = (lum(b) + lum(c)) / 2
                px[y * w + x] = Strip.rgb(m + 30, m + 30, m + 30)
            }
        }
    }
    return strip
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

    /**
     * Reading in short pushes: one drag after another, each to its own speed in [speeds], with
     * the page still for [pauseMs] in between. Every other drag ends with the finger stopping
     * dead; the others are let go into a short glide of [glideTauSec]. Each drag ramps up over
     * [rampMs] and is held for [dragMs] in all.
     */
    fun stopAndGo(
        startPx: Double,
        speeds: List<Double> = listOf(520.0, 900.0, 420.0, 760.0),
        dragMs: Double = 420.0,
        pauseMs: Double = 520.0,
        rampMs: Double = 110.0,
        glideTauSec: Double = 0.07,
    ): (Double) -> Double = { t ->
        val period = dragMs + pauseMs
        var y = startPx
        for ((i, v) in speeds.withIndex()) {
            val local = t - i * period
            if (local <= 0) break
            val drag = ramp(0.0, v, rampMs, 1e9)
            val glide = i % 2 == 1
            y += if (local < dragMs) {
                drag(local)
            } else if (!glide) {
                drag(dragMs)
            } else {
                drag(dragMs) + v * glideTauSec * (1 - Math.exp(-(local - dragMs) / 1000.0 / glideTauSec))
            }
        }
        y
    }

    /** When each drag of [stopAndGo] begins, in the scroll's milliseconds: the finger is down this long before. */
    fun stopAndGoStarts(count: Int = 4, dragMs: Double = 420.0, pauseMs: Double = 520.0): List<Double> =
        List(count) { it * (dragMs + pauseMs) }
}

internal fun absI(a: Int) = abs(a)
