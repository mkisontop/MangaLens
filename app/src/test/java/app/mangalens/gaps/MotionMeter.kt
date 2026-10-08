package app.mangalens.gaps

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Measures how steadily the shade rides the page, the way the reader sees it.
 *
 * The ideal shade is painted on the page: it moves rigidly with it, row for row, and in the
 * page's own coordinates it does not move at all. So each vsync's shade on the glass — the
 * shown draw's rectangles, cut as they were drawn — is carried into *strip* coordinates (screen
 * row plus the page's true top row at that vsync) and laid over the previous vsync's, on the
 * strip rows both screens show. Whatever differs is motion of the shade over the page:
 *
 *  - *Flicker*: pixels whose shaded state changed from one vsync to the next.
 *  - *Wobble*: in a few sample columns, the rows where the shade starts and stops; each such
 *    edge is matched to the nearest edge of the same kind in the previous vsync, within
 *    [REACH] rows, and the distance between them is how far that edge jumped over the page.
 *  - *Pops*: edges with no partner within [REACH] rows — a rectangle that appeared or vanished
 *    — counted apart, since no displacement describes them. Near the screen's ends, where the
 *    partner could be off the other screen, an edge without one is not counted at all.
 *
 * A perfect shade riding a steady scroll scores zero on all three: its edges stay on the same
 * page rows from one vsync to the next, whatever the speed. A shade that lags or leads the page
 * by a constant amount scores zero as well — the reader sees a still pattern on a moving page —
 * but one whose lag varies from frame to frame scores the variation. A margin that grows or
 * shrinks, and a fresh detection that replaces the rectangles with slightly different ones,
 * count as wobble too, on purpose: an edge that moves over the page is what the reader sees as
 * the shade wavering, whatever the reason it moved.
 *
 * Frames are told apart by what the page was doing ([Phase]): the figures that matter are the
 * ones over [Phase.MOVING] vsyncs; [Phase.SETTLING] — the moments after the page stops, when the
 * margin comes down and the exact pass lands — is summarised on its own, since a shade that
 * grows to the edges once the page is still is expected to change there.
 */
internal class MotionMeter(
    private val w: Int,
    private val h: Int,
    /** The screen rows [pageTop, pageBottom) the page is seen in; above and below are bars that stay put. */
    private val pageTop: Int = 0,
    private val pageBottom: Int = h,
    columns: Int = 24,
    /** Where the measures accumulate. */
    val summary: Summary = Summary(),
) {
    enum class Phase {
        /** The page moved into or out of this vsync. */
        MOVING,

        /** The page is still, but moved a moment ago. */
        SETTLING,

        /** The page has been still for a while, or has not moved yet. */
        STILL,
    }

    /**
     * One vsync's measures against the one before: the pixels that changed, how far each matched
     * edge moved ([moves], in rows), and the edges that came or went. [compared] is false when there
     * was nothing to compare with.
     */
    class Frame(val compared: Boolean, val flicker: Int, val moves: IntArray, val pops: Int) {
        val matched: Int get() = moves.size
        val maxMove: Int = moves.maxOrNull() ?: 0

        companion object {
            val NONE = Frame(false, 0, IntArray(0), 0)
        }
    }

    /** What a run of vsyncs added up to. */
    class Summary {
        /** Moving vsyncs compared with the one before. */
        var movingFrames = 0
        var flickerSum = 0L
        var flickerMax = 0

        /** Matched edges in moving vsyncs, the sum of how far they moved, and how many moved each distance. */
        var edges = 0L
        var moveSum = 0L
        var moveMax = 0
        val moveHist = LongArray(REACH + 1)

        /** Moving vsyncs in which some matched edge moved more than [JUMP] rows. */
        var jumpyFrames = 0
        var pops = 0

        var settleFrames = 0
        var settleFlickerMax = 0
        var settleMoveMax = 0
        var settlePops = 0
        var stillFlickerMax = 0

        /** Mean pixels changed per moving vsync. */
        val flickerMean: Double get() = if (movingFrames == 0) 0.0 else flickerSum.toDouble() / movingFrames

        /** Mean rows a matched edge moved per moving vsync. */
        val moveMean: Double get() = if (edges == 0L) 0.0 else moveSum.toDouble() / edges

        /** The distance, in rows, that [p] of the matched edges moved no further than. */
        fun movePercentile(p: Double): Int {
            if (edges == 0L) return 0
            val want = ceil(p * edges).toLong().coerceAtLeast(1)
            var seen = 0L
            for (d in 0..REACH) {
                seen += moveHist[d]
                if (seen >= want) return d
            }
            return REACH
        }

        /** Share of moving vsyncs in which an edge jumped more than [JUMP] rows. */
        val jumpyShare: Double get() = if (movingFrames == 0) 0.0 else jumpyFrames.toDouble() / movingFrames

        /** Adds one vsync's measures, made while the page was doing [phase]. */
        fun take(f: Frame, phase: Phase) {
            if (!f.compared) return
            when (phase) {
                Phase.MOVING -> {
                    movingFrames++
                    flickerSum += f.flicker
                    flickerMax = max(flickerMax, f.flicker)
                    for (d in f.moves) {
                        moveHist[d]++
                        moveSum += d
                    }
                    edges += f.moves.size
                    moveMax = max(moveMax, f.maxMove)
                    if (f.maxMove > JUMP) jumpyFrames++
                    pops += f.pops
                }
                Phase.SETTLING -> {
                    settleFrames++
                    settleFlickerMax = max(settleFlickerMax, f.flicker)
                    settleMoveMax = max(settleMoveMax, f.maxMove)
                    settlePops += f.pops
                }
                Phase.STILL -> stillFlickerMax = max(stillFlickerMax, f.flicker)
            }
        }

        /** The moving figures alone, short. */
        fun brief() = "$movingFrames frames: wobble mean=${"%.2f".format(moveMean)} p95=${movePercentile(0.95)} max=$moveMax, " +
            ">$JUMP rows in ${"%.1f".format(100 * jumpyShare)}%, flicker mean=${"%.0f".format(flickerMean)}px, pops=$pops"

        override fun toString() =
            "flicker mean=${"%.0f".format(flickerMean)}px max=${flickerMax}px | wobble mean=${"%.2f".format(moveMean)} " +
                "p95=${movePercentile(0.95)} max=$moveMax rows, >$JUMP rows in ${"%.1f".format(100 * jumpyShare)}% of " +
                "$movingFrames moving frames, pops=$pops | settling: flicker max=${settleFlickerMax}px wobble max=$settleMoveMax " +
                "pops=$settlePops | still flicker max=${stillFlickerMax}px"
    }

    private val cols = IntArray(columns) { ((it + 0.5) * w / columns).toInt().coerceIn(0, w - 1) }
    private val prev = BooleanArray(w * h)
    private var prevTop = 0
    private var havePrev = false

    // scratch for the edges of one column: rows and kinds, previous vsync and this one
    private val aRow = IntArray(MAX_COL_EDGES)
    private val aKind = IntArray(MAX_COL_EDGES)
    private val bRow = IntArray(MAX_COL_EDGES)
    private val bKind = IntArray(MAX_COL_EDGES)
    private val aUsed = BooleanArray(MAX_COL_EDGES)
    private val bUsed = BooleanArray(MAX_COL_EDGES)
    private val moves = IntList(256)

    /** No shade was on the glass at this vsync: the next one has nothing to be compared with. */
    fun forget() {
        havePrev = false
    }

    /**
     * Takes in the shade on the glass at one vsync: [mask] marks the shaded screen pixels, row-major,
     * [w] by [h]; [top] is the strip row at the top of the screen. Returns this vsync's measures
     * against the previous one, and adds them to [summary] under [phase].
     */
    fun observe(mask: BooleanArray, top: Int, phase: Phase): Frame {
        val frame = if (havePrev) compare(mask, top) else Frame.NONE
        System.arraycopy(mask, 0, prev, 0, w * h)
        prevTop = top
        havePrev = true
        summary.take(frame, phase)
        return frame
    }

    private fun compare(mask: BooleanArray, top: Int): Frame {
        // strip rows that both screens show the page in
        val lo = max(top, prevTop) + pageTop
        val hi = min(top, prevTop) + pageBottom
        if (hi - lo < 2) return Frame.NONE
        var flicker = 0
        for (s in lo until hi) {
            val a = (s - prevTop) * w
            val b = (s - top) * w
            for (x in 0 until w) if (prev[a + x] != mask[b + x]) flicker++
        }
        // Each screen's edges are read over all the page it shows, so that an edge that moved out
        // of the rows both show is still found; an edge counts once it is in those common rows.
        val aLo = prevTop + pageTop
        val aHi = prevTop + pageBottom
        val bLo = top + pageTop
        val bHi = top + pageBottom
        moves.n = 0
        var pops = 0
        for (x in cols) {
            val na = edges(prev, prevTop, x, aLo, aHi, aRow, aKind)
            val nb = edges(mask, top, x, bLo, bHi, bRow, bKind)
            java.util.Arrays.fill(aUsed, 0, na, false)
            java.util.Arrays.fill(bUsed, 0, nb, false)
            // Nearest pairs first: an edge is matched to the closest free edge of its kind.
            for (d in 0..REACH) {
                for (j in 0 until nb) {
                    if (bUsed[j]) continue
                    for (i in 0 until na) {
                        if (aUsed[i] || aKind[i] != bKind[j] || abs(aRow[i] - bRow[j]) != d) continue
                        if (aRow[i] !in lo until hi && bRow[j] !in lo until hi) continue
                        aUsed[i] = true
                        bUsed[j] = true
                        moves.add(d)
                        break
                    }
                }
            }
            // An edge with no partner is a pop only where the other screen showed all the rows its partner could be in.
            for (i in 0 until na) if (!aUsed[i] && aRow[i] in lo until hi && aRow[i] - REACH > bLo && aRow[i] + REACH < bHi) pops++
            for (j in 0 until nb) if (!bUsed[j] && bRow[j] in lo until hi && bRow[j] - REACH > aLo && bRow[j] + REACH < aHi) pops++
        }
        return Frame(true, flicker, moves.a.copyOf(moves.n), pops)
    }

    /**
     * The rows, in strip coordinates, where the shade starts (kind 0) and stops (kind 1) in column
     * [x], strictly inside the strip rows [lo, hi) the screen shows the page in: an edge lies
     * between two rows of the page, never at the screen's edge or at a bar.
     */
    private fun edges(m: BooleanArray, top: Int, x: Int, lo: Int, hi: Int, rows: IntArray, kinds: IntArray): Int {
        var n = 0
        var was = m[(lo - top) * w + x]
        for (s in lo + 1 until hi) {
            val on = m[(s - top) * w + x]
            if (on != was && n < MAX_COL_EDGES) {
                rows[n] = s
                kinds[n] = if (on) 0 else 1
                n++
            }
            was = on
        }
        return n
    }

    companion object {
        /** Farthest, in rows, an edge is looked for in the previous vsync before it counts as a pop. */
        const val REACH = 40

        /** An edge that moves further than this over the page in one vsync is a visible jump. */
        const val JUMP = 2

        private const val MAX_COL_EDGES = 256
    }
}

/**
 * How much of the gutter is dark while the page moves: each sampled vsync's share of the
 * reference gutter — what the exact pass finds on the clean page at that position — that the
 * shade on the glass covers.
 *
 * A gutter just scrolling into view is a sliver the moving shade is held back from by its
 * margin, and its share says little; the per-frame figures ([mean], [percentile], [min]) are
 * taken over vsyncs whose reference is at least [floorShare] of the screen. [weighted] counts
 * every sampled vsync, by area.
 */
internal class MovingCoverage(private val floorShare: Double = 0.02) {
    private var samples = DoubleArray(256)
    var count = 0
        private set
    var hit = 0L
        private set
    var ref = 0L
        private set

    /** Vsyncs where the reference had at least [floorShare] of the screen as gutter and less than [BLACKOUT] of it was dark. */
    var blackouts = 0
        private set
    val blackoutLog = ArrayList<String>()

    /** Takes in one vsync: [covered] of the reference's [reference] pixels were shaded, on a screen of [screen] pixels. */
    fun add(covered: Long, reference: Long, screen: Long, atMs: Double) {
        if (reference <= 0) return
        hit += covered
        ref += reference
        if (reference < floorShare * screen) return
        val c = covered.toDouble() / reference
        if (count == samples.size) samples = samples.copyOf(count * 2)
        samples[count++] = c
        if (c < BLACKOUT) {
            blackouts++
            if (blackoutLog.size < 40) blackoutLog.add("t=${"%.0f".format(atMs)}ms:${"%.2f".format(c)}")
        }
    }

    val mean: Double get() = if (count == 0) Double.NaN else samples.copyOf(count).average()

    /** The share that a fraction [p] of the vsyncs fell at or below (nearest rank). */
    fun percentile(p: Double): Double {
        if (count == 0) return Double.NaN
        val s = samples.copyOf(count).also { it.sort() }
        return s[(ceil(p * count).toInt() - 1).coerceIn(0, count - 1)]
    }

    val min: Double get() = if (count == 0) Double.NaN else samples.copyOf(count).min()

    /** Shaded share of all the reference gutter seen while moving, by area. */
    val weighted: Double get() = if (ref == 0L) Double.NaN else hit.toDouble() / ref

    override fun toString() =
        "coverage moving mean=${"%.3f".format(mean)} p10=${"%.3f".format(percentile(0.10))} min=${"%.3f".format(min)} " +
            "weighted=${"%.3f".format(weighted)} over $count frames, blackouts=$blackouts"

    companion object {
        /** Less of the gutter dark than this, with plenty of gutter on screen, is a blackout: the reader sees the shade gone. */
        const val BLACKOUT = 0.25
    }
}
