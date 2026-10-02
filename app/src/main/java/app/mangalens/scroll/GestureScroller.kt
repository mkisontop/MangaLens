package app.mangalens.scroll

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler

/**
 * Drags the page with a finger that never lets go between beats.
 *
 * Auto-scroll is one long touch, sent to the system a beat ([BEAT_MS]) at
 * a time: each beat continues the same stroke
 * ([GestureDescription.StrokeDescription.continueStroke]) by as far as the
 * current speed says, so the page follows the finger exactly and the speed
 * can change from one beat to the next. A drag never flings and never taps,
 * which a series of swipes would: each lift at speed flings the page on by
 * a screen, and a short touch opens whatever link or menu it lands on.
 *
 * When the finger has come up the whole [ScrollLane] it holds still for a
 * moment, so it lifts with no speed left to fling with, and comes down at
 * the bottom of the lane again. A new stroke starts with a quick nudge past
 * the touch slop: the app then knows it is a drag, not the start of a long
 * press, and the slow drag that follows scrolls from the first beat.
 *
 * A real touch cancels whatever is being injected, so a stroke cancelled
 * without being asked to stop is the reader touching the screen
 * ([Listener.interrupted]). Main thread only.
 */
internal class GestureScroller(
    private val sink: GestureSink,
    private val handler: Handler,
    /** The system's touch slop, in pixels: a drag this short may still be a tap. */
    touchSlop: Int,
    private val listener: Listener,
) {

    /** Sends gestures to the system: [AutoScrollService], or a test's recorder. */
    interface GestureSink {
        /**
         * Sends [gesture]; [done] hears true once it has been performed,
         * false if it was cancelled. False when it could not be sent at all.
         */
        fun dispatch(gesture: GestureDescription, done: (Boolean) -> Unit): Boolean
    }

    interface Listener {
        /** How fast the finger should move now, in pixels per second. */
        fun speed(): Float

        /** Where to drag for the next stroke; null when nowhere on the screen is free. */
        fun lane(): ScrollLane?

        /** The finger dragged the page up by [px]. */
        fun moved(px: Float)

        /** A stroke was cancelled: the reader touched the screen. */
        fun interrupted()

        /** The distance [start] was asked for is covered, and the finger is up. */
        fun glided()

        /** The system refused a gesture, or no lane was free: scrolling cannot go on. */
        fun failed()
    }

    private val kick = (touchSlop * 1.5f).coerceAtLeast(8f)

    private var stroke: GestureDescription.StrokeDescription? = null
    private var lane: ScrollLane? = null
    private var y = 0f
    private var left = Float.POSITIVE_INFINITY

    /** Whether a stroke is down or being brought down. */
    var running = false
        private set

    /** Set when the current stroke is to be ended: the finger lifts at the next beat. */
    private var stopping = false

    /** Bumped on every start and stop, so a callback from an older run does nothing. */
    private var generation = 0

    /**
     * Starts dragging, and keeps going until [stop] — or, when [distance]
     * is finite, until the page has moved that far (then [Listener.glided]).
     */
    fun start(distance: Float = Float.POSITIVE_INFINITY) {
        if (running) return
        running = true
        stopping = false
        left = distance
        generation++
        beginStroke()
    }

    /** Lifts the finger at the next beat, still, so the page does not fling. */
    fun stop() {
        if (!running) return
        stopping = true
    }

    /**
     * Forgets the stroke in flight at once, without lifting: the service
     * sending it has gone, and its result may never come back to end it.
     */
    fun abandon() {
        if (running) reset()
    }

    /** Forgets the run at once, without lifting: for when the system has already ended it. */
    private fun reset() {
        running = false
        stopping = false
        stroke = null
        generation++
    }

    private fun beginStroke() {
        val l = listener.lane()
        if (l == null) {
            reset()
            listener.failed()
            return
        }
        lane = l
        // The nudge past the slop: the page reads a drag, and moves from the next beat.
        val from = l.bottom
        y = from - kick
        val s = GestureDescription.StrokeDescription(line(l.x, from, y), 0, KICK_MS, true)
        send(s, moved = 0f)
    }

    /** The beat after the one that just completed. */
    private fun beat() {
        val l = lane ?: return
        val s = stroke ?: return
        if (stopping || left <= 0f) {
            lift(s, glide = !stopping)
            return
        }
        var dy = listener.speed().coerceAtLeast(0f) * BEAT_MS / 1000f
        if (dy > left) dy = left
        val atTop = y - dy <= l.top
        if (atTop) dy = y - l.top
        val next = s.continueStroke(line(l.x, y, y - dy), 0, BEAT_MS, true)
        y -= dy
        left -= dy
        send(next, moved = dy, thenLift = atTop && left > 0f)
    }

    /**
     * Holds the finger still for a moment and lifts it: with no speed left
     * the page does not fling on. A [glide] that covered its distance says
     * so; a stroke at the top of its lane comes down at the bottom again.
     */
    private fun lift(s: GestureDescription.StrokeDescription, glide: Boolean, again: Boolean = false) {
        val l = lane ?: return
        val last = s.continueStroke(line(l.x, y, y), 0, HOLD_MS, false)
        val gen = generation
        val ok = sink.dispatch(GestureDescription.Builder().addStroke(last).build()) { completed ->
            handler.post {
                if (gen != generation) return@post
                if (!completed) {
                    reset()
                    listener.interrupted()
                    return@post
                }
                stroke = null
                when {
                    again && !stopping -> beginStroke()
                    else -> {
                        running = false
                        stopping = false
                        if (glide) listener.glided()
                    }
                }
            }
        }
        if (!ok) {
            reset()
            listener.failed()
        }
    }

    private fun send(s: GestureDescription.StrokeDescription, moved: Float, thenLift: Boolean = false) {
        stroke = s
        val gen = generation
        val ok = sink.dispatch(GestureDescription.Builder().addStroke(s).build()) { completed ->
            handler.post {
                if (gen != generation) return@post
                if (!completed) {
                    // A real touch took over the screen: the injected
                    // stroke is gone, and so is the finger.
                    reset()
                    listener.interrupted()
                    return@post
                }
                if (moved > 0f) listener.moved(moved)
                if (thenLift) lift(s, glide = false, again = true) else beat()
            }
        }
        if (!ok) {
            reset()
            listener.failed()
        }
    }

    private fun line(x: Float, fromY: Float, toY: Float): Path = Path().apply {
        moveTo(x, fromY)
        lineTo(x, toY)
    }

    companion object {
        /**
         * One beat of the drag: short enough for the speed to follow a
         * balloon smoothly, long enough that the moment between beats, while
         * the next one is sent, is rare.
         */
        const val BEAT_MS = 160L

        /** The nudge that tells the app a drag has begun. */
        const val KICK_MS = 40L

        /** Stillness before a lift, so the page is left with no speed to fling with. */
        const val HOLD_MS = 150L
    }
}
