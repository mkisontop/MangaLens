package app.mangalens.capture

/**
 * Rate limit for the capture loop that never drops the last frame of a burst.
 *
 * A screen capture delivers a frame only when the screen changes: at refresh
 * rate while anything moves, then nothing at all. The loop needs about a
 * dozen frames a second, so most are skipped — but the skipping has to be
 * done with care. A reader that turns the page on a tap changes the screen
 * exactly once: one frame with the new page on it, then stillness. Under a
 * plain leading-edge throttle ("ignore anything within 80 ms of the last
 * frame looked at") that frame was dropped whenever it followed the tap's
 * own ripple closely, and with no frame after it the swap was never seen:
 * the previous page's cards sat on the new page until the reader tapped
 * again.
 *
 * So a frame that arrives too soon is held rather than dropped, and looked
 * at again once the interval is up — by then a newer frame may have taken
 * its place, or it is still the newest and is looked at itself. Either way
 * the last frame of every burst is looked at, within one interval of its
 * arrival, and no two frames are looked at closer together than the
 * interval.
 *
 * Pure bookkeeping, so it can be tested; the caller owns the frames and the
 * timer. Not thread-safe: use from the capture thread only.
 */
class FrameGate(private val intervalMs: Long) {

    enum class Action {
        /** Look at this frame now. */
        PROCESS,

        /** Too soon; hold the frame. A recheck is already on its way. */
        HOLD,

        /** Too soon; hold the frame and call [recheck] after [delayFor] milliseconds. */
        HOLD_AND_SCHEDULE,
    }

    private var lastProcessedAt = Long.MIN_VALUE / 2
    private var scheduled = false

    /** What to do with a frame arriving at [now] (uptime milliseconds). */
    fun arrival(now: Long): Action {
        if (now - lastProcessedAt >= intervalMs) {
            lastProcessedAt = now
            return Action.PROCESS
        }
        if (scheduled) return Action.HOLD
        scheduled = true
        return Action.HOLD_AND_SCHEDULE
    }

    /**
     * The scheduled recheck has fired at [now] with a frame in hand — the
     * one held, or a newer one. Answers [Action.PROCESS], or, when another
     * frame was looked at in the meantime, [Action.HOLD_AND_SCHEDULE] to
     * wait one more interval; never [Action.HOLD].
     */
    fun recheck(now: Long): Action {
        scheduled = false
        return arrival(now)
    }

    /** The scheduled recheck fired and found no frame at all. */
    fun recheckIdle() {
        scheduled = false
    }

    /** Milliseconds from [now] until the interval since the last frame looked at is up; at least 1. */
    fun delayFor(now: Long): Long = (lastProcessedAt + intervalMs - now).coerceAtLeast(1)
}
