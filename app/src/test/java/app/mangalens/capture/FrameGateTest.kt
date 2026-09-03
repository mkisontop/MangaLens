package app.mangalens.capture

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last frame of a burst must always be looked at, however it lands
 * against the rate limit. A tap-to-turn swap is one frame followed by
 * nothing, and a rate limit that drops it leaves the previous page's cards
 * on the new page with no later frame to notice.
 */
class FrameGateTest {

    private val interval = 80L

    /**
     * The capture loop on a fake clock: frames arrive, rechecks fire when
     * due, and each frame is either looked at or superseded by a newer one.
     * Mirrors the service's use of the gate exactly, including the handler
     * ordering when a frame and a recheck are due at the same instant.
     */
    private class Loop(private val gate: FrameGate) {
        /** (arrival, looked at) of every frame looked at, in order. */
        val processed = ArrayList<Pair<Long, Long>>()
        private var held: Long? = null
        private var recheckAt: Long? = null

        fun frame(at: Long, recheckFirst: Boolean = true) {
            drain(if (recheckFirst) at else at - 1)
            when (gate.arrival(at)) {
                FrameGate.Action.PROCESS -> {
                    held = null
                    processed.add(at to at)
                }
                FrameGate.Action.HOLD_AND_SCHEDULE -> {
                    held = at
                    recheckAt = at + gate.delayFor(at)
                }
                FrameGate.Action.HOLD -> held = at
            }
        }

        /** Fires every recheck due at or before [until]. */
        fun drain(until: Long) {
            while (true) {
                val due = recheckAt ?: return
                if (due > until) return
                recheckAt = null
                val frame = held
                if (frame == null) {
                    gate.recheckIdle()
                    continue
                }
                when (gate.recheck(due)) {
                    FrameGate.Action.PROCESS -> {
                        held = null
                        processed.add(frame to due)
                    }
                    FrameGate.Action.HOLD_AND_SCHEDULE -> recheckAt = due + gate.delayFor(due)
                    FrameGate.Action.HOLD -> error("a recheck never answers HOLD")
                }
            }
        }
    }

    private fun loop() = Loop(FrameGate(interval))

    @Test
    fun `the one frame a tap produces is looked at even right after the tap's ripple`() {
        val loop = loop()
        loop.frame(0) // the ripple
        loop.frame(30) // the swap — and then nothing, ever
        loop.drain(10_000)
        assertEquals(listOf(0L to 0L, 30L to 80L), loop.processed)
    }

    @Test
    fun `isolated frames are looked at as they arrive`() {
        val loop = loop()
        for (t in listOf(0L, 200L, 400L, 1_000L)) loop.frame(t)
        loop.drain(10_000)
        assertEquals(listOf(0L to 0L, 200L to 200L, 400L to 400L, 1_000L to 1_000L), loop.processed)
    }

    @Test
    fun `a scroll burst is thinned to one frame per interval and ends on its last frame`() {
        val loop = loop()
        val frames = (0L..496L step 16).toList()
        for (t in frames) loop.frame(t)
        loop.drain(10_000)
        val looked = loop.processed
        assertEquals("the burst's last frame is looked at", 496L, looked.last().first)
        assertTrue("and within one interval of arriving", looked.last().second - 496L <= interval)
        for ((a, b) in looked.zipWithNext()) {
            assertTrue("no two frames closer than the interval ($a, $b)", b.second - a.second >= interval)
        }
        assertTrue("about one frame per interval, got ${looked.size}", looked.size <= frames.last() / interval + 2)
    }

    @Test
    fun `a frame due at the same instant as the recheck is looked at once, whichever runs first`() {
        for (recheckFirst in listOf(true, false)) {
            val loop = loop()
            loop.frame(0)
            loop.frame(30)
            loop.frame(80, recheckFirst = recheckFirst)
            loop.drain(10_000)
            val arrivals = loop.processed.map { it.first }
            assertEquals("recheckFirst=$recheckFirst: the newest frame is looked at", 80L, arrivals.last())
            assertEquals("recheckFirst=$recheckFirst: and only once", 1, arrivals.count { it == 80L })
            for ((a, b) in loop.processed.zipWithNext()) {
                assertTrue("recheckFirst=$recheckFirst: spacing kept ($a, $b)", b.second - a.second >= interval)
            }
        }
    }

    @Test
    fun `whatever the traffic, the last frame is always looked at and spacing is kept`() {
        val rnd = Random(7)
        repeat(300) { run ->
            val loop = loop()
            var t = 0L
            val arrivals = ArrayList<Long>()
            // Bursts of refresh-rate frames with quiet gaps between them,
            // and the occasional frame landing exactly on a recheck.
            repeat(rnd.nextInt(1, 6)) {
                repeat(rnd.nextInt(1, 12)) {
                    arrivals.add(t)
                    loop.frame(t, recheckFirst = rnd.nextBoolean())
                    t += if (rnd.nextInt(5) == 0) interval else rnd.nextLong(8, 40)
                }
                t += rnd.nextLong(interval, 600)
            }
            loop.drain(t + 10_000)
            val looked = loop.processed
            assertEquals("run $run: the last frame is looked at", arrivals.last(), looked.last().first)
            for ((arrival, at) in looked) {
                assertTrue("run $run: looked at no later than one interval after arriving", at - arrival <= interval)
            }
            for ((a, b) in looked.zipWithNext()) {
                assertTrue("run $run: spacing kept ($a, $b)", b.second - a.second >= interval)
                assertTrue("run $run: in arrival order", b.first > a.first)
            }
            // Every frame not looked at was superseded by a later one that was.
            val lookedAt = looked.map { it.first }.toSet()
            for (a in arrivals) {
                assertTrue("run $run: frame $a was neither looked at nor superseded", lookedAt.any { it >= a })
            }
        }
    }
}
