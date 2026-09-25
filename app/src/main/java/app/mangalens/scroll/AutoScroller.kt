package app.mangalens.scroll

import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import app.mangalens.capture.FrameStability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Auto-scroll: moves the page for the reader, in any app, and slows down
 * for the balloons on it.
 *
 * With translation napping (or tap-to-translate) the page moves on without
 * stopping, at the reader's speed, eased down while balloons pass through
 * the reading rows — the more, the bigger they are — and up through empty
 * gutters ([ScrollPace]). The balloons come from the phone's own detector,
 * run on the newest frame every [DETECT_MS], and are carried along by the
 * distance dragged since, so the speed follows them between looks.
 *
 * With hands-free translation awake, a page that never stops would never be
 * translated: MangaLens translates the moment the screen goes still. So the
 * page then glides half a screen, holds while that stop is translated, and
 * holds on for as long as its new English takes to read, then glides on.
 * Tapping 文A to nap or wake switches between the two at the next stroke.
 *
 * A real touch cancels the drag; auto-scroll then rests until the reader
 * has let go and the page has come to rest, and carries on. It stops by
 * itself at the end of the page: the finger drags and nothing moves.
 * Main thread only.
 */
internal class AutoScroller(
    private val scope: CoroutineScope,
    private val handler: Handler,
    touchSlop: Int,
    /** Pixels per dp, for speeds set in dp. */
    private val density: Float,
    private val page: Page,
    private val listener: Listener,
    private val sinkOf: () -> GestureScroller.GestureSink? = { AutoScrollHost.sink() },
) {

    /** What auto-scroll needs to know about the screen and the translation. */
    interface Page {
        /** The screen's size, in pixels, width then height. */
        fun size(): Pair<Int, Int>

        /** MangaLens's own touchable windows: the finger must not come down on them. */
        fun obstacles(): List<Rect>

        /** A [FrameStability.SIZE]-square grey thumbnail of the newest frame, or null. */
        fun thumb(): IntArray?

        /** The balloons on the newest frame, found on the phone; null when there is no frame. */
        suspend fun balloons(): List<Rect>?

        /** Whether every stop is being translated: hands-free, awake, with a working setup. */
        fun translating(): Boolean

        /** How many translation passes have finished so far. */
        fun passes(): Int

        /** Characters of English on the cards lower than [y]: what the last stop brought. */
        fun englishBelow(y: Int): Int
    }

    interface Listener {
        fun onRunningChanged(running: Boolean)

        /** A word for the status pill. */
        fun say(text: String, ms: Long)
    }

    /** Speed level, [ScrollPace.MIN_LEVEL] to [ScrollPace.MAX_LEVEL]. */
    var level = ScrollPace.DEFAULT_LEVEL
        set(v) {
            field = ScrollPace.level(v)
        }

    /** Whether the page slows down for balloons and hurries through gaps. */
    var smart = true

    /** Whether auto-scroll is on: started by the reader and not stopped since. */
    var running = false
        private set

    private enum class Ending { NONE, GLIDED, INTERRUPTED, FAILED }

    private var ending = Ending.NONE

    /** Share of the chosen speed the page moves at now, eased toward [target]. */
    private var factor = 1f

    /** Distance dragged since auto-scroll started, in pixels. */
    private var travelled = 0f

    /** The balloons last found, and [travelled] when their frame was taken. */
    private var seen: List<Rect> = emptyList()
    private var seenAt = 0f

    private var loop: Job? = null
    private var detector: Job? = null

    /** Set when the touch that paused the drag was on MangaLens's own speed buttons. */
    private var resumeNow = false

    private val scroller = GestureScroller(
        sink = Sink(sinkOf),
        handler = handler,
        touchSlop = touchSlop,
        listener = object : GestureScroller.Listener {
            override fun speed(): Float = ScrollPace.baseDpPerSecond(level) * density * factor
            override fun lane(): ScrollLane? {
                val (w, h) = page.size()
                return ScrollLane.pick(w, h, page.obstacles())
            }
            override fun moved(px: Float) {
                travelled += px
            }
            override fun interrupted() {
                ending = Ending.INTERRUPTED
            }
            override fun glided() {
                ending = Ending.GLIDED
            }
            override fun failed() {
                ending = Ending.FAILED
            }
        },
    )

    /** Looks the service up at each gesture: it may be switched off mid-run. */
    private class Sink(private val of: () -> GestureScroller.GestureSink?) : GestureScroller.GestureSink {
        override fun dispatch(
            gesture: android.accessibilityservice.GestureDescription,
            done: (Boolean) -> Unit,
        ): Boolean = of()?.dispatch(gesture, done) ?: false
    }

    fun toggle(): Boolean {
        if (running) stop(null) else start()
        return running
    }

    /** Starts scrolling. False when the scroll service is not switched on, and nothing starts. */
    fun start(): Boolean {
        if (running) return true
        if (sinkOf() == null) return false
        running = true
        ending = Ending.NONE
        resumeNow = false
        factor = 1f
        travelled = 0f
        seen = emptyList()
        listener.onRunningChanged(true)
        loop = scope.launch { run() }
        detector = scope.launch { detect() }
        return true
    }

    /**
     * The reader tapped a speed button: that tap cancelled the drag like any
     * touch, but it was not a request to pause, so the drag carries straight on.
     */
    fun carryOn() {
        resumeNow = true
    }

    /** Stops scrolling; [why], when given, is said in the pill. */
    fun stop(why: String?) {
        if (!running) return
        running = false
        loop?.cancel()
        detector?.cancel()
        loop = null
        detector = null
        scroller.stop()
        listener.onRunningChanged(false)
        why?.let { listener.say(it, 3000) }
    }

    private suspend fun run() {
        while (running) {
            ending = Ending.NONE
            if (page.translating()) glideAndHold() else drag()
            when (ending) {
                Ending.INTERRUPTED -> rest()
                Ending.FAILED -> {
                    stop(if (sinkOf() != null) "⚠ couldn't scroll here" else "⚠ auto-scroll is switched off in Accessibility")
                    return
                }
                else -> Unit
            }
        }
    }

    /**
     * Drags on without stopping until something ends it: the reader's
     * touch, translation waking up, or the end of the page.
     */
    private suspend fun drag() {
        scroller.start()
        var last = SystemClock.uptimeMillis()
        var stillSince = last
        var stillThumb: IntArray? = page.thumb()
        var stillTravel = travelled
        while (running && scroller.running) {
            delay(TICK_MS)
            val now = SystemClock.uptimeMillis()
            pace(now - last)
            last = now
            if (page.translating()) {
                scroller.stop()
                awaitLifted()
                return
            }
            // The end of the page: the finger has been dragging a while
            // and the screen has not changed at all. An empty stretch shows
            // no change either, so on a screen with nothing on it only a
            // much longer stillness counts.
            val thumb = page.thumb()
            if (now - stillSince >= STUCK_MS) {
                val same = stillThumb != null && thumb != null && FrameStability.meanDiff(stillThumb, thumb) < STILL_DIFF
                val featureless = ScrollPace.blank(thumb, FrameStability.SIZE, 0f, 1f)
                val dragged = travelled - stillTravel
                if (same && dragged > 0f && (!featureless || now - stillSince >= BLANK_STUCK_MS)) {
                    scroller.stop()
                    awaitLifted()
                    stop("that's the end of the page · tap ▼ to scroll again")
                    return
                }
                if (!same || !featureless) {
                    stillSince = now
                    stillThumb = thumb
                    stillTravel = travelled
                }
            }
        }
    }

    /** Glides half a screen, waits for that stop's translation, and holds while it is read. */
    private suspend fun glideAndHold() {
        val (_, h) = page.size()
        val before = page.passes()
        scroller.start(distance = h * GLIDE_SHARE)
        var last = SystemClock.uptimeMillis()
        while (running && scroller.running) {
            delay(TICK_MS)
            val now = SystemClock.uptimeMillis()
            pace(now - last)
            last = now
        }
        if (!running || ending != Ending.GLIDED) return
        // The screen is still now: the stop is translated. Wait for it.
        val stoppedAt = SystemClock.uptimeMillis()
        while (running && page.translating() && page.passes() <= before &&
            SystemClock.uptimeMillis() - stoppedAt < TRANSLATE_WAIT_MS
        ) {
            delay(TICK_MS)
        }
        if (!running) return
        val chars = page.englishBelow((h * (1f - GLIDE_SHARE - 0.05f)).toInt())
        val until = SystemClock.uptimeMillis() + ScrollPace.holdMs(chars, level)
        while (running && page.translating() && SystemClock.uptimeMillis() < until) delay(TICK_MS)
    }

    /**
     * After the reader touched the screen: waits until they have let go a
     * while and the page has come to rest, so the drag neither fights a
     * fling nor starts under their finger.
     */
    private suspend fun rest() {
        // The speed button's own tap arrives a moment after the drag it
        // cancelled: give it that moment before saying anything.
        delay(TICK_MS * 3)
        if (resumeNow) {
            resumeNow = false
            return
        }
        listener.say("paused while you touch · I carry on when you let go", 2000)
        val from = SystemClock.uptimeMillis()
        var calm = page.thumb()
        var calmSince = from
        while (running) {
            delay(TICK_MS)
            val now = SystemClock.uptimeMillis()
            val thumb = page.thumb()
            if (calm == null || thumb == null || FrameStability.meanDiff(calm, thumb) >= STILL_DIFF) {
                calm = thumb
                calmSince = now
            }
            if (resumeNow || (now - from >= RESUME_AFTER_MS && now - calmSince >= CALM_MS)) {
                resumeNow = false
                return
            }
        }
    }

    private suspend fun awaitLifted() {
        val from = SystemClock.uptimeMillis()
        while (scroller.running && SystemClock.uptimeMillis() - from < 1_000) delay(20)
    }

    /** Eases the speed toward what the screen calls for now. */
    private fun pace(dtMs: Long) {
        val target = if (!smart) 1f else {
            val (w, h) = page.size()
            val shift = (travelled - seenAt).toInt()
            val now = seen.map { Rect(it).apply { offset(0, -shift) } }
            ScrollPace.factor(now, w, h, ScrollPace.blank(page.thumb(), FrameStability.SIZE))
        }
        factor = ScrollPace.ease(factor, target, dtMs)
    }

    /** Finds the balloons on the newest frame, over and over, while scrolling. */
    private suspend fun detect() {
        while (scope.isActive && running) {
            if (smart) {
                val at = travelled
                val found = runCatching { page.balloons() }.getOrNull()
                if (found != null) {
                    seen = found
                    seenAt = at
                }
            }
            delay(DETECT_MS)
        }
    }

    companion object {
        /** How often the speed is eased and the screen looked at. */
        const val TICK_MS = 100L

        /** How often the balloons are looked for again. */
        const val DETECT_MS = 600L

        /** Share of the screen one glide moves the page, when stops are translated. */
        const val GLIDE_SHARE = 0.5f

        /** Longest wait for a stop's translation before gliding on. */
        const val TRANSLATE_WAIT_MS = 12_000L

        /** Dragging this long with the screen unchanged is the end of the page. */
        const val STUCK_MS = 2_500L

        /** ... or this long, when the screen shows nothing to tell by. */
        const val BLANK_STUCK_MS = 15_000L

        /** Thumbnails this alike are the same screen. */
        const val STILL_DIFF = 0.35

        /** After a touch: at least this long before carrying on ... */
        const val RESUME_AFTER_MS = 2_500L

        /** ... and the page this long at rest. */
        const val CALM_MS = 700L
    }
}
