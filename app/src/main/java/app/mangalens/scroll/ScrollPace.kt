package app.mangalens.scroll

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * How fast auto-scroll moves, from what is on the screen.
 *
 * A reader takes longer over a big balloon than a small one, so the page
 * slows down while balloons pass through the part of the screen they are
 * read in, the more the bigger they are, and hurries through the empty
 * stretches between panels. The balloons are the ones found on the phone
 * itself ([app.mangalens.ocr.BalloonFinder]): no AI, no connection, and the
 * same whether translation is on or napping.
 *
 * Pure arithmetic over rectangles and a thumbnail, so it is tested as such.
 */
internal object ScrollPace {

    const val MIN_LEVEL = 1
    const val MAX_LEVEL = 15
    const val DEFAULT_LEVEL = 4

    /** Speed at level 1, in dp per second: a slow crawl. */
    private const val SLOWEST_DP = 18f

    /**
     * Each level is this much faster than the one below it: level 10 skims
     * at about 230 dp a second, and level 15 races at nearly a thousand.
     */
    private const val LEVEL_STEP = 1.33f

    /** The rows a balloon is read in, as shares of the screen's height. */
    const val ZONE_TOP = 0.15f
    const val ZONE_BOTTOM = 0.75f

    /**
     * How strongly balloons slow the page: the speed is divided by one plus
     * this times the share of the screen they cover. A balloon covering 6%
     * of the screen scrolls by at about 60%, one covering 17% at about a
     * third.
     */
    private const val SLOWDOWN = 10f

    /** The slowest the page goes for balloons, as a share of the chosen speed. */
    const val SLOWEST = 0.15f

    /** How much faster an empty stretch between panels goes by. */
    const val HURRY = 1.8f

    /** A thumbnail cell further than this from the stretch's middle grey is something drawn there. */
    private const val BLANK_TOLERANCE = 8

    /** Share of the stretch's cells that may differ before it is not empty. */
    private const val BLANK_SHARE = 0.005f

    /** How quickly the speed follows its target: fast into a slow-down, gently out of one. */
    private const val EASE_DOWN_MS = 350f
    private const val EASE_UP_MS = 900f

    fun level(v: Int): Int = v.coerceIn(MIN_LEVEL, MAX_LEVEL)

    /** The chosen speed at [level], in dp per second. */
    fun baseDpPerSecond(level: Int): Float = SLOWEST_DP * LEVEL_STEP.pow(level(level) - 1)

    /**
     * The share of the chosen speed to move at: under 1 while [balloons]
     * (on a [width] x [height] screen, where they are now) pass through the
     * reading rows, [HURRY] when those rows are [blank], and 1 otherwise.
     * A balloon counts by its whole size, in proportion to how much of it
     * is in those rows: a big balloon needs long reading from the moment
     * it arrives.
     */
    fun factor(balloons: List<Rect>, width: Int, height: Int, blank: Boolean): Float {
        if (width <= 0 || height <= 0) return 1f
        val top = height * ZONE_TOP
        val bottom = height * ZONE_BOTTOM
        val screen = width.toFloat() * height
        var load = 0f
        for (b in balloons) {
            if (b.height() <= 0 || b.width() <= 0) continue
            val inZone = minOf(b.bottom.toFloat(), bottom) - maxOf(b.top.toFloat(), top)
            if (inZone <= 0f) continue
            load += b.width().toFloat() * b.height() / screen * (inZone / b.height())
        }
        if (load <= 0f) return if (blank) HURRY else 1f
        return (1f / (1f + SLOWDOWN * load)).coerceAtLeast(SLOWEST)
    }

    /**
     * Whether the reading rows (or the rows [from] to [to], as shares of the
     * height) of a [size] x [size] grey [thumb] of the screen hold nothing
     * at all: the gutter between two panels, a white or black stretch of a
     * webtoon. Nearly every cell must be within a few levels of the
     * stretch's middle grey; one line of small text is not.
     */
    fun blank(thumb: IntArray?, size: Int, from: Float = ZONE_TOP, to: Float = ZONE_BOTTOM): Boolean {
        if (thumb == null || size <= 0 || thumb.size < size * size) return false
        val y0 = (size * from).toInt()
        val y1 = (size * to).toInt().coerceAtMost(size)
        if (y1 <= y0) return false
        val hist = IntArray(256)
        for (y in y0 until y1) for (x in 0 until size) hist[thumb[y * size + x].coerceIn(0, 255)]++
        val cells = (y1 - y0) * size
        var acc = 0
        var median = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc * 2 >= cells) {
                median = v
                break
            }
        }
        var off = 0
        for (y in y0 until y1) for (x in 0 until size) if (abs(thumb[y * size + x] - median) > BLANK_TOLERANCE) off++
        return off <= cells * BLANK_SHARE
    }

    /** [current] moved toward [target] over [dtMs]: quickly when slowing, gently when speeding up. */
    fun ease(current: Float, target: Float, dtMs: Long): Float {
        if (dtMs <= 0) return current
        val tau = if (target < current) EASE_DOWN_MS else EASE_UP_MS
        return current + (target - current) * (1f - exp(-dtMs / tau))
    }

    /**
     * How long to hold still on a stop whose new English is [chars]
     * characters long, when auto-scroll moves stop by stop because the
     * pages are being translated: time to read it at the pace [level]
     * reads at, level 4 taking about a second plus a twentieth of a second
     * a character.
     */
    fun holdMs(chars: Int, level: Int): Long {
        val pace = (baseDpPerSecond(DEFAULT_LEVEL) / baseDpPerSecond(level)).pow(0.6f)
        return ((900f + chars.coerceAtLeast(0) * 55f) * pace).toLong().coerceIn(1_200L, 15_000L)
    }
}
