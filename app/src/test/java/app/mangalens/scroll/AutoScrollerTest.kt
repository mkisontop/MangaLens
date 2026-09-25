package app.mangalens.scroll

import android.accessibilityservice.GestureDescription
import android.graphics.PathMeasure
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.mangalens.capture.FrameStability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * Auto-scroll over a make-believe webtoon: a long strip that moves exactly
 * as far as the finger drags it, with a big balloon on it, an empty gap,
 * and an end. The strip's thumbnail and balloons are what the capture
 * would show at each moment, MangaLens's own controls included, and its
 * translation passes come when the capture would run them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutoScrollerTest {

    private val w = 1080
    private val h = 2400
    private val main = Looper.getMainLooper()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Every auto-scroll made here, closed after each test: they listen to the one [AutoScrollHost]. */
    private val made = ArrayList<AutoScroller>()
    private val services = ArrayList<AutoScrollService>()

    /** How long the capture waits for a still page, and how long a pass takes. */
    private val quietMs = 350L
    private val passMs = 1_000L

    @After
    fun tearDown() {
        made.forEach { it.close() }
        services.forEach { AutoScrollHost.disconnect(it) }
        scope.cancel()
    }

    /** The strip, and the finger that drags it. */
    private inner class Strip(
        val length: Int = 20_000,
        val balloon: Rect = Rect(90, 4_600, 990, 5_500),
        val gap: IntRange = 8_000..11_000,
    ) : GestureScroller.GestureSink, AutoScroller.Page {
        /** Rows of the strip scrolled off the top of the screen. */
        var offset = 0f
        var cancelNext = false

        /** Every stroke from now on goes unanswered, as one sent to a service that has gone. */
        var hang = false
        var translating = false
        var holding = false
        var passes = 0
        var english = 0

        /** With it, one pass a glide finishes mid-glide, as one did before the capture felt the finger. */
        var strayPasses = false

        /** The auto-scroll dragging this strip: the capture feels its finger. */
        var auto: AutoScroller? = null

        /** Each beat: when it ended, how far the page moved in it. */
        val beats = ArrayList<Pair<Long, Float>>()
        var lifts = 0
        var sent = 0

        /** Beats that moved the page while auto-scroll said its finger was not moving it. */
        var movedUnfelt = 0
        private val handler = Handler(main)

        /** MangaLens's controls on the thumbnail, in the reading rows where they sit by default. */
        private val ours = BooleanArray(FrameStability.SIZE * FrameStability.SIZE).also {
            for (row in 18..23) for (col in 1..17) it[row * FrameStability.SIZE + col] = true
        }

        private var quietSince = 0L
        private var passDue = -1L
        private var read = false
        private var strayed = false

        /**
         * The capture, as the service runs it: a pass starts once the page
         * has been still a while and finishes a while later, unless the
         * page moves first, and each stop is read once. A glide is too slow
         * for its frames to show, so the page is moving exactly while
         * auto-scroll says its finger is.
         */
        private val capture = object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                if (auto?.moving == true) {
                    quietSince = now
                    passDue = -1L
                    read = false
                    if (strayPasses && translating && !strayed) {
                        strayed = true
                        passes++
                    }
                } else {
                    strayed = false
                    if (translating && !read) {
                        if (passDue < 0 && now - quietSince >= quietMs) passDue = now + passMs
                        if (passDue in 0..now) {
                            passDue = -1L
                            read = true
                            passes++
                        }
                    }
                }
                handler.postDelayed(this, 50)
            }
        }

        init {
            handler.post(capture)
        }

        override fun dispatch(gesture: GestureDescription, done: (Boolean) -> Unit): Boolean {
            sent++
            if (hang) return true
            val s = gesture.getStroke(0)
            val m = PathMeasure(s.path, false)
            val a = FloatArray(2)
            val b = FloatArray(2)
            var dy = 0f
            if (m.length > 0f) {
                m.getPosTan(0f, a, null)
                m.getPosTan(m.length, b, null)
                dy = a[1] - b[1]
            }
            val ok = !cancelNext
            cancelNext = false
            handler.postDelayed({
                if (ok) {
                    val before = offset
                    offset = (offset + dy).coerceAtMost((length - h).toFloat())
                    beats += SystemClock.uptimeMillis() to (offset - before)
                    if (offset != before && auto?.moving != true) movedUnfelt++
                    if (!s.willContinue()) lifts++
                }
                done(ok)
            }, s.duration)
            return true
        }

        override fun size() = w to h
        override fun obstacles() = emptyList<Rect>()

        override fun thumb(): IntArray {
            val size = FrameStability.SIZE
            return IntArray(size * size) { i ->
                val row = i / size
                val y = (offset + row * h / size).toInt()
                when {
                    ours[i] -> if (i % size <= 5) 199 else 60
                    y in gap -> 250
                    else -> ((y / 40) * 37 + (i % size) * 11) % 180 + 40
                }
            }
        }

        override fun mask() = ours
        override fun holding() = holding

        override suspend fun balloons(): List<Rect> {
            val screen = Rect(0, offset.toInt(), w, offset.toInt() + h)
            return if (Rect.intersects(screen, balloon)) listOf(Rect(balloon).apply { offset(0, -offset.toInt()) }) else emptyList()
        }

        override fun translating() = translating
        override fun passes() = passes
        override fun englishBelow(y: Int) = english
    }

    private class Said : AutoScroller.Listener {
        val lines = ArrayList<String>()
        var running = false
        override fun onRunningChanged(running: Boolean) {
            this.running = running
        }
        override fun say(text: String, ms: Long) {
            lines += text
        }
    }

    private fun scroller(
        strip: Strip,
        said: Said,
        sink: GestureScroller.GestureSink? = strip,
        sinkOf: () -> GestureScroller.GestureSink? = { sink },
    ) = AutoScroller(scope, Handler(main), touchSlop = 24, density = 3f, page = strip, listener = said, sinkOf = sinkOf)
        .also {
            strip.auto = it
            made += it
        }

    private fun run(ms: Long) {
        shadowOf(main).idleFor(Duration.ofMillis(ms))
    }

    /** Runs until [done], or [limitMs] has gone by. */
    private fun runUntil(limitMs: Long, done: () -> Boolean) {
        var left = limitMs
        while (!done() && left > 0) {
            run(100)
            left -= 100
        }
    }

    /** The pauses between the glides: from each glide's last moving beat to the next one's first. */
    private fun holds(strip: Strip): List<Long> {
        val out = ArrayList<Long>()
        var lastEnd = -1L
        for ((at, d) in strip.beats) {
            if (d <= 0f) continue
            val pause = at - lastEnd - GestureScroller.BEAT_MS
            if (lastEnd >= 0 && pause > 500) out += pause
            lastEnd = at
        }
        return out
    }

    /** The page's speed, in px/s, over the beats that ended while the strip's offset was in [rows]. */
    private fun speedWhile(strip: Strip, rows: ClosedFloatingPointRange<Float>, track: List<Pair<Float, Float>>): Float {
        val inside = track.filter { it.first in rows }
        return inside.map { it.second }.average().toFloat() / GestureScroller.BEAT_MS * 1000f
    }

    @Test
    fun aBigBalloonSlowsThePageRightDownAndAnEmptyGapHurriesIt() {
        val strip = Strip()
        val said = Said()
        val auto = scroller(strip, said)
        auto.level = 4
        assertTrue(auto.start())
        assertTrue(said.running)
        // Where the strip was at each beat, and how far that beat moved it.
        val track = ArrayList<Pair<Float, Float>>()
        var seen = 0
        repeat(1_200) {
            run(100)
            while (seen < strip.beats.size) {
                val (_, d) = strip.beats[seen++]
                if (d > 1f) track += (strip.offset - d) to d
            }
        }
        val base = ScrollPace.baseDpPerSecond(4) * 3f
        // The balloon sits in the reading rows (15%-75% of the screen) while
        // the offset runs from about 3,200 to 4,700.
        val atBalloon = speedWhile(strip, 3_900f..4_300f, track)
        val plain = speedWhile(strip, 1_000f..2_000f, track)
        val inGap = speedWhile(strip, 8_200f..8_500f, track)
        assertEquals("the chosen speed on a plain stretch", base, plain, base * 0.15f)
        assertTrue("slowed right down at the big balloon: $atBalloon vs $base", atBalloon < base * 0.45f)
        assertTrue("hurried through the gap: $inGap vs $base", inGap > base * 1.4f)
        auto.stop(null)
        run(1_000)
        assertFalse(said.running)
    }

    @Test
    fun theTopSpeedRacesWithTheLiftsBetweenStrokesIncluded() {
        // A plain strip, nothing to slow down for.
        val strip = Strip(length = 60_000, balloon = Rect(0, 0, 0, 0), gap = -1..-1)
        val said = Said()
        val auto = scroller(strip, said)
        auto.level = ScrollPace.MAX_LEVEL
        auto.start()
        run(1_000)
        val from = strip.offset
        run(10_000)
        val perSecond = (strip.offset - from) / 10f
        val chosen = ScrollPace.baseDpPerSecond(ScrollPace.MAX_LEVEL) * 3f
        println("[speed] top level: $perSecond px/s of $chosen chosen")
        assertTrue("most of the chosen speed, lifts included: $perSecond px/s of $chosen", perSecond > chosen * 0.65f)
        auto.stop(null)
    }

    @Test
    fun itStopsByItselfAtTheEndOfThePage() {
        val strip = Strip(length = 4_000)
        val said = Said()
        val auto = scroller(strip, said)
        auto.level = 10
        auto.start()
        run(60_000)
        assertEquals((4_000 - h).toFloat(), strip.offset, 1f)
        assertFalse("stopped", auto.running)
        assertFalse(said.running)
        assertTrue(said.lines.any { it.contains("end of the page") })
        val sent = strip.beats.size
        run(10_000)
        assertEquals("and sends nothing more", sent, strip.beats.size)
    }

    @Test
    fun anEmptyStretchWithOnlyItsOwnControlsOnItIsNotTheEndOfThePage() {
        // White for well over a screen, with nothing on it but MangaLens's controls.
        val strip = Strip(gap = 8_000..12_200)
        val said = Said()
        val auto = scroller(strip, said)
        auto.level = 6
        auto.start()
        runUntil(150_000) { strip.offset > 12_500f || !auto.running }
        assertTrue("still going: ${said.lines}", auto.running)
        assertTrue("and across it: ${strip.offset}", strip.offset > 12_500f)
        auto.stop(null)
    }

    @Test
    fun aTouchPausesItUntilTheReaderLetsGo() {
        val strip = Strip()
        val said = Said()
        val auto = scroller(strip, said)
        auto.start()
        run(2_000)
        val before = strip.offset
        assertTrue(before > 0f)
        strip.cancelNext = true
        run(1_000)
        val paused = strip.offset
        run(1_000)
        assertEquals("resting while the reader touches", paused, strip.offset, 0f)
        assertTrue(said.lines.any { it.startsWith("paused while you touch") })
        assertTrue("still on", auto.running)
        run(5_000)
        assertTrue("carried on", strip.offset > paused)
        auto.stop(null)
    }

    @Test
    fun whileStopsAreTranslatedItGlidesHalfAScreenAndWaitsToBeRead() {
        val strip = Strip()
        strip.translating = true
        strip.english = 40
        val said = Said()
        val auto = scroller(strip, said)
        auto.level = 6
        auto.start()
        run(45_000)
        assertTrue("glided and stopped more than once: ${strip.lifts}", strip.lifts >= 2)
        assertEquals("the capture felt every beat that moved the page", 0, strip.movedUnfelt)
        // Between glides the page holds for the stop's translation and then the reading.
        val hold = ScrollPace.holdMs(40, 6)
        val holds = holds(strip)
        assertTrue(holds.isNotEmpty())
        assertTrue("held for the translation and the reading: $holds vs ${quietMs + passMs + hold}", holds.min() >= quietMs + passMs + hold)
        // Each glide moves half a screen (the last may still be under way).
        val moved = strip.beats.sumOf { it.second.toDouble() }.toFloat()
        val glides = strip.lifts.toFloat()
        assertTrue("half a screen a glide: $moved over $glides", moved >= h * AutoScroller.GLIDE_SHARE * glides * 0.95f)
        assertTrue(moved <= h * AutoScroller.GLIDE_SHARE * (glides + 1) * 1.05f)
        auto.stop(null)
    }

    @Test
    fun aPassThatFinishesMidGlideIsNotTheStopsTranslation() {
        val strip = Strip()
        strip.translating = true
        strip.english = 40
        strip.strayPasses = true
        val said = Said()
        val auto = scroller(strip, said)
        auto.level = 6
        auto.start()
        run(45_000)
        // Every stop still waits for a pass of its own before the reading hold.
        val hold = ScrollPace.holdMs(40, 6)
        val holds = holds(strip)
        assertTrue("glided more than once: $holds", holds.size >= 2)
        assertTrue("waited for the stop's own pass: $holds vs ${quietMs + passMs + hold}", holds.min() >= quietMs + passMs + hold)
        auto.stop(null)
    }

    @Test
    fun whileStopsAreTranslatedItStillStopsAtTheEndOfThePage() {
        val strip = Strip(length = 4_000)
        strip.translating = true
        strip.english = 40
        val said = Said()
        val auto = scroller(strip, said)
        auto.level = 10
        auto.start()
        run(60_000)
        assertEquals((4_000 - h).toFloat(), strip.offset, 1f)
        assertFalse("stopped", auto.running)
        assertFalse(said.running)
        assertTrue(said.lines.any { it.contains("end of the page") })
        val sent = strip.sent
        run(30_000)
        assertEquals("and sends nothing more", sent, strip.sent)
    }

    @Test
    fun whileStopsAreTranslatedAnEmptyStretchIsNotTheEndOfThePage() {
        // White for three screens: three glides in a row start and end on nothing at all.
        val strip = Strip(balloon = Rect(0, 0, 0, 0), gap = 3_000..10_200)
        strip.translating = true
        val said = Said()
        val auto = scroller(strip, said)
        auto.level = 8
        auto.start()
        runUntil(120_000) { strip.offset > 10_500f || !auto.running }
        assertTrue("still going: ${said.lines}", auto.running)
        assertTrue("and across it: ${strip.offset}", strip.offset > 10_500f)
        auto.stop(null)
    }

    @Test
    fun aTapOnItsSpeedButtonsDoesNotPauseIt() {
        val strip = Strip()
        val said = Said()
        val auto = scroller(strip, said)
        auto.start()
        run(2_000)
        // The tap on + cancels the drag like any touch, then says what it was.
        strip.cancelNext = true
        run(200)
        auto.level = 6
        auto.carryOn()
        val at = strip.offset
        run(1_200)
        assertTrue("carried straight on", strip.offset > at)
        assertFalse(said.lines.any { it.startsWith("paused while you touch") })
        auto.stop(null)
    }

    @Test
    fun aSpeedTapWhileTheFingerIsUpDoesNotExcuseTheNextTouch() {
        val strip = Strip()
        strip.translating = true
        val said = Said()
        val auto = scroller(strip, said)
        auto.level = 6
        auto.start()
        // A tap on + during the hold after a glide: there is no stroke for it to cancel.
        runUntil(20_000) { strip.lifts >= 1 }
        run(500)
        auto.carryOn()
        // On the next glide the reader touches the page.
        val before = strip.beats.size
        runUntil(20_000) { strip.beats.size >= before + 3 }
        strip.cancelNext = true
        run(1_000)
        assertTrue("paused for the touch", said.lines.any { it.startsWith("paused while you touch") })
        val paused = strip.offset
        run(1_500)
        assertEquals("and resting while the reader touches", paused, strip.offset, 0f)
        auto.stop(null)
    }

    @Test
    fun itWaitsWhileItsMenuIsOpen() {
        val strip = Strip()
        val said = Said()
        val auto = scroller(strip, said)
        auto.start()
        run(2_000)
        assertTrue(strip.offset > 0f)
        // Opened by its accessibility action, which cancels no stroke.
        strip.holding = true
        run(1_000)
        val held = strip.offset
        val sent = strip.sent
        run(5_000)
        assertEquals("no stroke comes down outside the menu", sent, strip.sent)
        assertEquals(held, strip.offset, 0f)
        assertTrue("still on", auto.running)
        strip.holding = false
        run(2_000)
        assertTrue("carried on once it closed", strip.offset > held)
        auto.stop(null)
    }

    /** The scroll service, connected as the system connects it. */
    private fun connect(): AutoScrollService = AutoScrollService().also {
        services += it
        AutoScrollHost.connect(it)
    }

    @Test
    fun switchedOffMidStrokeItStopsAndSaysSoAndStartsAgainOnceBack() {
        val strip = Strip()
        val said = Said()
        val service = connect()
        val auto = scroller(strip, said, sinkOf = { if (AutoScrollHost.connected) strip else null })
        assertTrue(auto.start())
        run(2_000)
        // The service goes with a stroke in flight whose result never comes.
        strip.hang = true
        run(500)
        AutoScrollHost.disconnect(service)
        run(100)
        assertFalse("stopped", auto.running)
        assertFalse(said.running)
        assertTrue(said.lines.any { it.contains("switched off in Accessibility") })
        // Switched on again: the page moves again.
        strip.hang = false
        connect()
        val at = strip.offset
        assertTrue(auto.start())
        run(2_000)
        assertTrue("dragging again", strip.offset > at)
        auto.stop(null)
    }

    @Test
    fun withoutItsAccessibilitySwitchItDoesNotStart() {
        val strip = Strip()
        val said = Said()
        val auto = scroller(strip, said, sink = null)
        assertFalse(auto.start())
        assertFalse(auto.running)
        assertFalse(said.running)
    }
}
