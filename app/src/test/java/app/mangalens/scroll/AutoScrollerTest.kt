package app.mangalens.scroll

import android.accessibilityservice.GestureDescription
import android.graphics.PathMeasure
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
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
 * would show at each moment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutoScrollerTest {

    private val w = 1080
    private val h = 2400
    private val main = Looper.getMainLooper()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() {
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
        var translating = false
        var passes = 0
        var english = 0

        /** Each beat: when it ended, how far the page moved in it. */
        val beats = ArrayList<Pair<Long, Float>>()
        var lifts = 0
        private val handler = Handler(main)

        override fun dispatch(gesture: GestureDescription, done: (Boolean) -> Unit): Boolean {
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
                    beats += android.os.SystemClock.uptimeMillis() to (offset - before)
                    if (!s.willContinue()) {
                        lifts++
                        // The page is still: the stop is translated a second later.
                        if (translating) handler.postDelayed({ passes++ }, 1_000)
                    }
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
                if (y in gap) 250 else ((y / 40) * 37 + (i % size) * 11) % 180 + 40
            }
        }

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

    private fun scroller(strip: Strip, said: Said, sink: GestureScroller.GestureSink? = strip) =
        AutoScroller(scope, Handler(main), touchSlop = 24, density = 3f, page = strip, listener = said, sinkOf = { sink })

    private fun run(ms: Long) {
        shadowOf(main).idleFor(Duration.ofMillis(ms))
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
        // Between glides the page holds for the translation and then the reading.
        var moved = 0f
        var lastEnd = -1L
        var longestPause = 0L
        for ((at, d) in strip.beats) {
            if (d <= 0f) continue
            if (lastEnd >= 0) longestPause = maxOf(longestPause, at - lastEnd - GestureScroller.BEAT_MS)
            moved += d
            lastEnd = at
        }
        val hold = ScrollPace.holdMs(40, 6)
        assertTrue("held for the translation and the reading: $longestPause vs ${1_000 + hold}", longestPause >= 1_000 + hold)
        // Each glide moves half a screen (the last may still be under way).
        val glides = strip.lifts.toFloat()
        assertTrue("half a screen a glide: $moved over $glides", moved >= h * AutoScroller.GLIDE_SHARE * glides * 0.95f)
        assertTrue(moved <= h * AutoScroller.GLIDE_SHARE * (glides + 1) * 1.05f)
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
    fun withoutItsAccessibilitySwitchItDoesNotStart() {
        val strip = Strip()
        val said = Said()
        val auto = scroller(strip, said, sink = null)
        assertFalse(auto.start())
        assertFalse(auto.running)
        assertFalse(said.running)
    }
}
