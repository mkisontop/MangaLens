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
 * has let go and the page has come to rest, and carries on. It waits while
 * MangaLens's own menu is open, and stops by itself at the end of the
 * page, where the finger drags and nothing moves, and when the scroll
 * service is switched off. Main thread only.
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

        /**
         * The cells of [thumb] MangaLens's own windows cover, its controls,
         * pill, menu and cards, which are captured along with the page; null
         * when none are up.
         */
        fun mask(): BooleanArray?

        /** Whether the reader has MangaLens's menu open: the page waits until it closes. */
        fun holding(): Boolean

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

    /**
     * Whether the finger is on the page now, dragging it or holding still
     * to lift. The capture takes this for motion: a glide slow enough to
     * read along with moves the page too little between frames for frame
     * differencing to see, and a stop read in the middle of one is not a
     * stop at all.
     */
    val moving: Boolean get() = running && scroller.running

    private enum class Ending { NONE, GLIDED, INTERRUPTED, FAILED }

    private var ending = Ending.NONE

    /** Share of the chosen speed the page moves at now, eased toward [target]. */
    private var factor = 1f

    /** Distance dragged since auto-scroll started, in pixels. */
    private var travelled = 0f

    /** Time spent on the glides in a row that moved nothing on screen; see [glideAndHold]. */
    private var unmovedMs = 0L

    /** The balloons last found, and [travelled] when their frame was taken. */
    private var seen: List<Rect> = emptyList()
    private var seenAt = 0f

    private var loop: Job? = null
    private var detector: Job? = null

    /**
     * Set when the touch that paused the drag was on MangaLens's own speed
     * buttons. It lasts for the stroke it was set in: a tap made while the
     * finger was up cancelled nothing, and must not excuse a later touch
     * from pausing the page.
     */
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

    /**
     * The scroll service connected or went. A stroke in flight was sent
     * through the connection that changed, and its result may never come:
     * without one the scroller would wait for it for good, and never drag
     * again. So it is forgotten, and a drag still wanted starts afresh on
     * the service there is now. With none, auto-scroll stops and says why.
     */
    private val serviceChanged: () -> Unit = {
        scroller.abandon()
        if (running && sinkOf() == null) stop("⚠ auto-scroll is switched off in Accessibility")
    }

    init {
        AutoScrollHost.addListener(serviceChanged)
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
        unmovedMs = 0L
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
        // With the service gone there is no lifting the finger, and no
        // result coming for the stroke it was on.
        if (sinkOf() == null) scroller.abandon() else scroller.stop()
        listener.onRunningChanged(false)
        why?.let { listener.say(it, 3000) }
    }

    /** Stops for good, and stops listening for the scroll service: the capture is ending. */
    fun close() {
        stop(null)
        AutoScrollHost.removeListener(serviceChanged)
    }

    private suspend fun run() {
        while (running) {
            // The reader has MangaLens's menu open. A stroke coming down
            // outside it would close it, maybe under their finger.
            if (page.holding()) {
                delay(TICK_MS)
                continue
            }
            ending = Ending.NONE
            resumeNow = false
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
     * touch, translation waking up, the menu opening, or the end of the
     * page.
     */
    private suspend fun drag() {
        scroller.start()
        var last = SystemClock.uptimeMillis()
        var stillSince = last
        var stillThumb: IntArray? = page.thumb()
        var stillMask = page.mask()
        var stillTravel = travelled
        while (running && scroller.running) {
            delay(TICK_MS)
            val now = SystemClock.uptimeMillis()
            pace(now - last)
            last = now
            if (page.translating() || page.holding()) {
                scroller.stop()
                awaitLifted()
                return
            }
            // The end of the page: the finger has been dragging a while
            // and the screen has not changed at all. An empty stretch shows
            // no change either, so on a screen with nothing on it only a
            // much longer stillness counts. Our own controls and cards are
            // left out of both looks: on a gutter they alone would be
            // something on the screen. An app's own bars are not, and
            // outside a full-screen reader they still can be.
            val thumb = page.thumb()
            if (now - stillSince >= STUCK_MS) {
                val same = unchanged(stillThumb, stillMask, thumb)
                val featureless = ScrollPace.blank(thumb, FrameStability.SIZE, 0f, 1f, page.mask())
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
                    stillMask = page.mask()
                    stillTravel = travelled
                }
            }
        }
    }

    /**
     * Glides half a screen, waits for that stop's translation, and holds
     * while it is read. A glide that moved nothing on screen is the end of
     * the page, as in [drag]. A glide across an empty stretch shows no
     * change either, so on a screen with nothing on it the unmoved glides
     * in a row must add up to [BLANK_STUCK_MS] of dragging.
     */
    private suspend fun glideAndHold() {
        val (_, h) = page.size()
        val from = page.thumb()
        val fromMask = page.mask()
        val startedAt = SystemClock.uptimeMillis()
        scroller.start(distance = h * GLIDE_SHARE)
        var last = startedAt
        while (running && scroller.running) {
            delay(TICK_MS)
            val now = SystemClock.uptimeMillis()
            pace(now - last)
            last = now
            if (page.holding()) {
                scroller.stop()
                awaitLifted()
                return
            }
        }
        if (!running || ending != Ending.GLIDED) return
        // Counted from here, not from when the glide began: a pass the
        // capture finished mid-glide read a page still on the move, and is
        // not this stop's translation.
        val before = page.passes()
        val stoppedAt = SystemClock.uptimeMillis()
        // Let the lift and any overscroll stretch settle, then look.
        delay(SETTLE_MS)
        if (!running) return
        val thumb = page.thumb()
        if (unchanged(from, fromMask, thumb)) {
            unmovedMs += stoppedAt - startedAt
            if (!ScrollPace.blank(thumb, FrameStability.SIZE, 0f, 1f, page.mask()) || unmovedMs >= BLANK_STUCK_MS) {
                stop("that's the end of the page · tap ▼ to scroll again")
                return
            }
        } else {
            unmovedMs = 0L
        }
        // The screen is still now: the stop is translated. Wait for it.
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
        var calmMask = page.mask()
        var calmSince = from
        while (running) {
            delay(TICK_MS)
            val now = SystemClock.uptimeMillis()
            val thumb = page.thumb()
            if (!unchanged(calm, calmMask, thumb)) {
                calm = thumb
                calmMask = page.mask()
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

    /**
     * Whether [thumb] shows the page as [was] did, taken while [wasMask]
     * covered our own windows: compared only where none of ours was on top,
     * then or now, so a card or the pill coming or going is not the page
     * moving.
     */
    private fun unchanged(was: IntArray?, wasMask: BooleanArray?, thumb: IntArray?): Boolean =
        was != null && thumb != null &&
            FrameStability.meanDiff(was, thumb, FrameStability.union(wasMask, page.mask())) < STILL_DIFF

    /** Eases the speed toward what the screen calls for now. */
    private fun pace(dtMs: Long) {
        val target = if (!smart) 1f else {
            val (w, h) = page.size()
            val shift = (travelled - seenAt).toInt()
            val now = seen.map { Rect(it).apply { offset(0, -shift) } }
            ScrollPace.factor(now, w, h, ScrollPace.blank(page.thumb(), FrameStability.SIZE, mask = page.mask()))
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

        /** After a glide, how long the page is given to come to rest before it is looked at. */
        const val SETTLE_MS = 400L

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
