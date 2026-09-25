package app.mangalens.scroll

import android.accessibilityservice.GestureDescription
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
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
 * The drag auto-scroll sends: one stroke continued beat after beat at the
 * speed of the moment, nudged past the touch slop as it comes down, and
 * lifted only after holding still, so the page neither long-presses nor
 * flings. A cancelled stroke is the reader's touch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GestureScrollerTest {

    private class Stroke(val fromY: Float, val toY: Float, val x: Float, val ms: Long, val continues: Boolean)

    /**
     * Performs every gesture in the time it takes, as the system does, or
     * cancels the next when told to.
     */
    private class Recorder(private val handler: Handler = Handler(Looper.getMainLooper())) : GestureScroller.GestureSink {
        val strokes = ArrayList<Stroke>()
        var cancelNext = false
        var refuse = false

        override fun dispatch(gesture: GestureDescription, done: (Boolean) -> Unit): Boolean {
            if (refuse) return false
            val s = gesture.getStroke(0)
            val m = PathMeasure(s.path, false)
            val a = FloatArray(2)
            val b = FloatArray(2)
            if (m.length > 0f) {
                m.getPosTan(0f, a, null)
                m.getPosTan(m.length, b, null)
            } else {
                // A finger held still: a line of no length, measured by its bounds.
                val r = RectF()
                s.path.computeBounds(r, true)
                a[0] = r.left; a[1] = r.top
                b[0] = r.left; b[1] = r.top
            }
            strokes += Stroke(a[1], b[1], a[0], s.duration, s.willContinue())
            val ok = !cancelNext
            cancelNext = false
            handler.postDelayed({ done(ok) }, s.duration)
            return true
        }
    }

    private class Events(var speed: Float = 300f, var lane: ScrollLane? = ScrollLane(500f, 400f, 1600f)) :
        GestureScroller.Listener {
        var moved = 0f
        var interrupted = 0
        var glided = 0
        var failed = 0
        override fun speed() = speed
        override fun lane() = lane
        override fun moved(px: Float) {
            moved += px
        }
        override fun interrupted() {
            interrupted++
        }
        override fun glided() {
            glided++
        }
        override fun failed() {
            failed++
        }
    }

    private val main = Looper.getMainLooper()

    /** Lets [ms] of the main thread's time go by. */
    private fun run(ms: Long = 30_000) {
        shadowOf(main).idleFor(Duration.ofMillis(ms))
    }

    @Test
    fun aDragStartsWithANudgeAndMovesAtTheSpeedOfEachBeat() {
        val sink = Recorder()
        val ev = Events(speed = 250f)
        val g = GestureScroller(sink, Handler(main), touchSlop = 24, listener = ev)
        g.start(distance = 400f)
        run()
        val first = sink.strokes.first()
        assertEquals("comes down at the bottom of the lane", 1600f, first.fromY, 0.5f)
        assertTrue("nudged past the slop at once", first.fromY - first.toY > 24f && first.ms <= 60)
        assertTrue(first.continues)
        // Every beat after it: the same line, moving up at 250 px/s.
        val beats = sink.strokes.drop(1).dropLast(1)
        assertTrue(beats.isNotEmpty())
        for (b in beats) {
            assertEquals(500f, b.x, 0f)
            assertTrue(b.continues)
            assertEquals(GestureScroller.BEAT_MS, b.ms)
        }
        assertEquals(250f * GestureScroller.BEAT_MS / 1000f, beats.first().fromY - beats.first().toY, 0.01f)
        // Each beat starts where the last one ended: one unbroken stroke.
        for (i in 1 until sink.strokes.size) assertEquals(sink.strokes[i - 1].toY, sink.strokes[i].fromY, 0.01f)
        // The glide covered its distance, then held still and let go.
        assertEquals(400f, ev.moved, 0.5f)
        val last = sink.strokes.last()
        assertEquals("still before the lift", last.fromY, last.toY, 0f)
        assertFalse("and lifted", last.continues)
        assertTrue(last.ms >= GestureScroller.HOLD_MS)
        assertEquals(1, ev.glided)
        assertFalse(g.running)
    }

    @Test
    fun atTheTopOfTheLaneTheFingerLiftsStillAndComesDownAgainAtTheBottom() {
        val sink = Recorder()
        val ev = Events(speed = 3000f, lane = ScrollLane(500f, 1000f, 1600f))
        val g = GestureScroller(sink, Handler(main), touchSlop = 24, listener = ev)
        g.start()
        run(3_000)
        val lifts = sink.strokes.withIndex().filter { !it.value.continues }
        assertTrue("lifted at the top at least once", lifts.isNotEmpty())
        for ((i, s) in lifts) {
            assertEquals(s.fromY, s.toY, 0f)
            assertEquals("never above the lane", 1000f, s.toY, 0.5f)
            if (i + 1 < sink.strokes.size) assertEquals("down again at the bottom", 1600f, sink.strokes[i + 1].fromY, 0.5f)
        }
        assertTrue(sink.strokes.all { it.toY >= 1000f - 0.5f && it.fromY <= 1600f + 0.5f })
        g.stop()
        run()
        assertFalse(g.running)
        assertFalse("a stop lifts too", sink.strokes.last().continues)
        assertEquals(0, ev.glided)
    }

    @Test
    fun theSpeedIsReadAgainEveryBeat() {
        val sink = Recorder()
        val ev = Events(speed = 100f)
        val g = GestureScroller(sink, Handler(main), touchSlop = 24, listener = ev)
        g.start()
        run(800)
        ev.speed = 400f
        run(800)
        g.stop()
        run()
        val steps = sink.strokes.drop(1).filter { it.continues }.map { it.fromY - it.toY }
        assertTrue(steps.any { kotlin.math.abs(it - 16f) < 0.01f })
        assertTrue(steps.any { kotlin.math.abs(it - 64f) < 0.01f })
    }

    @Test
    fun aCancelledStrokeIsTheReadersTouch() {
        val sink = Recorder()
        val ev = Events()
        val g = GestureScroller(sink, Handler(main), touchSlop = 24, listener = ev)
        g.start()
        run(500)
        sink.cancelNext = true
        run(500)
        assertEquals(1, ev.interrupted)
        assertFalse(g.running)
        val sent = sink.strokes.size
        run()
        assertEquals("nothing more is sent", sent, sink.strokes.size)
    }

    @Test
    fun aRefusedGestureOrNoLaneFails() {
        val refused = Recorder().apply { refuse = true }
        val ev = Events()
        GestureScroller(refused, Handler(main), touchSlop = 24, listener = ev).start()
        run()
        assertEquals(1, ev.failed)
        val nowhere = Events(lane = null)
        GestureScroller(Recorder(), Handler(main), touchSlop = 24, listener = nowhere).start()
        run()
        assertEquals(1, nowhere.failed)
    }
}
