package app.mangalens.gaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A bench for the shade in motion: a matrix of scrolls, refresh rates, latencies and timing
 * noise, each played through [ShadeSimulation] and printed as one row of a table — the art it
 * covered, the white it covered, how much of the gutter stayed dark, how often the shade
 * dropped, and how steadily it rode the page.
 *
 * It is a measuring instrument, not a gate: it asserts only that the measures are sane. The
 * default matrix is the one to compare engines with; `BENCH_FULL=1` runs a much larger one,
 * `BENCH_ONLY=a,b` only the scenarios whose names contain one of those, and
 * `BENCH_TIMELINE=a` also prints, for the scenarios whose names contain it, one line per vsync.
 *
 * The columns, left to right:
 *  - `onset steady stop rest`: art pixels covered, worst frame, in each phase ([ShadeSimulation.Report]);
 *  - `white max/rst`: pixels of white that is the art's covered, worst frame and worst at rest;
 *  - `cov mean p10 min`: share of the gutter dark per moving vsync with plenty of gutter on screen;
 *    `blk`: those vsyncs with less than a quarter of it dark;
 *  - `drp`: times the engine took the whole shade down;
 *  - `flick mean max`: pixels whose shade changed over the page from one moving vsync to the next;
 *  - `wob mean p95 max`: rows a shade edge jumped over the page from one moving vsync to the next;
 *    `>2`: share of moving vsyncs with a jump of more than two rows; `pops`: edges that came or went;
 *  - `c95 c>2`: the wobble's p95 and jump share over the moving vsyncs that showed the same rectangles
 *    as the one before, only moved — the prediction's own wobble;
 *  - `new n95 n>2`: the share of moving vsyncs that showed new rectangles (a fresh detection, or the
 *    margin cut again), and the wobble's p95 and jump share over those;
 *  - `set w/p`: the largest jump and the pops in the moments after the page stops;
 *  - `cap`: frames the capture lost to a newer one, of those the screen changed in; `ms`: the run's own time.
 */
class ShadeBenchTest {

    private val w = 720
    private val h = 1400
    private val style = ShadeStyle(ShadeLevel.DARK)

    /** A finger goes down this long before the page first moves. */
    private val touch = 80.0

    /** A page to scroll: the strip, the white in it that is the art's, and what stays put over it. */
    private class Page(
        val strip: Strip,
        val white: BooleanArray?,
        val fixed: ((IntArray) -> Unit)? = null,
        val keepClear: BooleanArray? = null,
        val pageRows: IntRange,
    )

    /** How the page moves: the scroll, how long it lasts, and the touches after the first. */
    private class Motion(val name: String, val scroll: (Double) -> Double, val durationMs: Double, val touches: List<Double> = emptyList())

    /** How ragged the timing is. */
    private class Timing(
        val name: String,
        val captureJitterMs: Double = 0.0,
        val drawJitterMs: Double = 0.0,
        val dropShare: Double = 0.0,
        val jobJitterMs: Double = 0.0,
        val source: ShadeSimulation.TimeSource = ShadeSimulation.TimeSource.ARRIVAL,
    )

    private class Latency(val name: String, val captureLagMs: Double = 16.0, val displayFrames: Int = 2, val jobMs: Double = 25.0)

    private class Scenario(
        val name: String,
        val page: () -> Page,
        val motion: Motion,
        val vsyncMs: Double,
        val timing: Timing,
        val latency: Latency = Latency("std"),
    )

    // ---- pages, built once and only when a scenario needs them ----

    private val plain by lazy { scrollStrip(w).let { Page(it, protectedWhite(it), pageRows = 0 until h) } }
    private val grit by lazy { gritty(scrollStrip(w, seed = 31), paper = 245, grain = 3).let { Page(it, protectedWhite(it), pageRows = 0 until h) } }
    private val long by lazy { longGutterStrip(w).let { Page(it, protectedWhite(it), pageRows = 0 until h) } }
    private val whiteArt by lazy { whitePanelStrip(w).let { Page(it.strip, it.white, pageRows = 0 until h) } }

    /** The page of ShadeEngineTest's bars case: a strip between black bars, under a browser's and a site's bars. */
    private val bars by lazy {
        val top = 200
        val bottom = 1290
        val strip = scrollStrip(w, seed = 17).also { s ->
            s.solid(0, 0, 120, s.h, Strip.BLACK)
            s.solid(600, 0, w, s.h, Strip.BLACK)
        }
        Page(
            strip, protectedWhite(strip, 120, 600),
            fixed = overlayBars(chromeBars(w, h, top, bottom), top, bottom),
            keepClear = BooleanArray(w * h) { it / w < top || it / w >= bottom },
            pageRows = top until bottom,
        )
    }

    // ---- motions ----

    private val slow = Motion("slow450", Scrolls.ramp(2000.0, 450.0, 150.0, 2300.0), 2500.0)
    private val drag = Motion("drag", Scrolls.drag(1500.0), 2200.0)
    private val fling = Motion("fling3000", Scrolls.fling(1000.0, 3000.0, 350.0, 0.5), 2000.0)
    private val reverse = Motion("reverse", Scrolls.reverse(3000.0, 700.0, 900.0), 2000.0)
    private val steady700 = Motion("steady700", Scrolls.ramp(2500.0, 700.0, 150.0, 1600.0), 1800.0)
    private val longGutter = Motion("longgutter", Scrolls.ramp(900.0, 900.0, 160.0, 4300.0), 4500.0)
    private val stopGo = Motion(
        "stopgo", Scrolls.stopAndGo(1800.0), 3800.0,
        // the first drag's touch is the run's own; each later one goes down as long before its drag
        Scrolls.stopAndGoStarts().drop(1).map { it - touch },
    )
    private val gritty600 = Motion("slow600", Scrolls.ramp(2000.0, 600.0, 150.0, 2000.0), 2300.0)

    // over the white wall and the line art on white of [whitePanelStrip], and on to the white panel in its box
    private val whiteSlow = Motion("slow450", Scrolls.ramp(200.0, 450.0, 150.0, 2300.0), 2500.0)
    private val whiteDrag = Motion("drag", Scrolls.drag(300.0), 2200.0)
    private val whiteStopGo = Motion("stopgo", Scrolls.stopAndGo(200.0), 3800.0, stopGo.touches)

    // ---- timings ----

    private val ideal = Timing("ideal")

    /** As ragged as a phone: frames up to 6 ms late, draws up to 5 ms after the vsync, one changed frame in ten lost, detections up to 10 ms slower. */
    private val real = Timing("real", captureJitterMs = 6.0, drawJitterMs = 5.0, dropShare = 0.10, jobJitterMs = 10.0)
    private val realGlass = Timing("realGlass", 6.0, 5.0, 0.10, 10.0, ShadeSimulation.TimeSource.GLASS)

    private val hz60 = 1000.0 / 60
    private val hz120 = 1000.0 / 120

    private fun hz(vs: Double) = if (vs < 10) "120" else "60"

    private fun s(page: () -> Page, pageName: String, m: Motion, vs: Double, t: Timing, lat: Latency = Latency("std")) =
        Scenario("$pageName ${m.name}@${hz(vs)} ${t.name}${if (lat.name == "std") "" else " " + lat.name}", page, m, vs, t, lat)

    private fun defaultMatrix(): List<Scenario> = listOf(
        s({ plain }, "plain", slow, hz60, ideal),
        s({ plain }, "plain", slow, hz120, ideal),
        s({ plain }, "plain", drag, hz60, ideal),
        s({ plain }, "plain", drag, hz120, ideal),
        s({ plain }, "plain", fling, hz60, ideal),
        s({ plain }, "plain", reverse, hz60, ideal),
        s({ long }, "long", longGutter, hz60, ideal),
        s({ plain }, "plain", stopGo, hz60, ideal),
        s({ grit }, "gritty", gritty600, hz60, ideal),
        s({ plain }, "plain", drag, hz60, real),
        s({ plain }, "plain", drag, hz60, realGlass),
        s({ plain }, "plain", slow, hz120, real),
        s({ plain }, "plain", slow, hz120, realGlass),
        s({ plain }, "plain", stopGo, hz60, real),
        s({ plain }, "plain", stopGo, hz60, realGlass),
        s({ bars }, "bars", drag, hz60, ideal),
        s({ whiteArt }, "white", whiteSlow, hz60, ideal),
        s({ whiteArt }, "white", whiteDrag, hz60, ideal),
    )

    private fun fullMatrix(): List<Scenario> {
        val out = ArrayList<Scenario>()
        val timings = listOf(ideal, real, realGlass)
        for (m in listOf(slow, drag, fling, reverse, stopGo, steady700)) for (vs in listOf(hz60, hz120)) for (t in timings) out += s({ plain }, "plain", m, vs, t)
        for (vs in listOf(hz60, hz120)) for (t in listOf(ideal, real)) out += s({ long }, "long", longGutter, vs, t)
        for (m in listOf(gritty600, drag, stopGo)) for (vs in listOf(hz60, hz120)) for (t in listOf(ideal, real)) out += s({ grit }, "gritty", m, vs, t)
        for (m in listOf(drag, fling, reverse, stopGo)) for (t in timings) out += s({ bars }, "bars", m, hz60, t)
        for (m in listOf(whiteSlow, whiteDrag, whiteStopGo)) for (vs in listOf(hz60, hz120)) out += s({ whiteArt }, "white", m, vs, ideal)
        for (lat in listOf(Latency("lag8/d1/j12", 8.0, 1, 12.0), Latency("lag25/d3/j45", 25.0, 3, 45.0))) {
            for (m in listOf(slow, drag)) for (t in listOf(ideal, real)) out += s({ plain }, "plain", m, hz60, t, lat)
        }
        // one source of timing noise at a time, to see which the wobble comes from
        val glass = ShadeSimulation.TimeSource.GLASS
        for (t in listOf(
            Timing("cap6", captureJitterMs = 6.0), Timing("cap6Glass", captureJitterMs = 6.0, source = glass),
            Timing("draw5", drawJitterMs = 5.0), Timing("drop10", dropShare = 0.10), Timing("drop10Glass", dropShare = 0.10, source = glass),
            Timing("job10", jobJitterMs = 10.0),
        )) for (m in listOf(slow, drag)) out += s({ plain }, "plain", m, hz60, t)
        return out
    }

    private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }

    @Test
    fun `shade bench`() {
        val full = env("BENCH_FULL") == "1"
        val only = env("BENCH_ONLY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val timeline = env("BENCH_TIMELINE")
        val matrix = (if (full) fullMatrix() else defaultMatrix()).filter { sc -> only == null || only.any { sc.name.contains(it) } }
        println("BENCH ${if (full) "full" else "default"} matrix, ${matrix.size} scenarios, ${w}x$h, ${style.level}")
        println("BENCH " + HEADER)
        val started = System.nanoTime()
        for (sc in matrix) {
            val t0 = System.nanoTime()
            val page = sc.page()
            val keep = timeline != null && sc.name.contains(timeline)
            val sim = ShadeSimulation(
                page.strip, w, h, style, sc.vsyncMs, sc.latency.captureLagMs, sc.latency.displayFrames, sc.latency.jobMs,
                fixed = page.fixed,
                keepClear = page.keepClear,
                captureJitterMs = sc.timing.captureJitterMs,
                captureDropShare = sc.timing.dropShare,
                drawJitterMs = sc.timing.drawJitterMs,
                jobJitterMs = sc.timing.jobJitterMs,
                timeSource = sc.timing.source,
                protectedWhite = page.white,
                pageRows = page.pageRows,
                coverageEvery = 1,
                keepTimeline = keep,
            )
            val r = sim.run(sc.motion.scroll, sc.motion.durationMs, touchLeadMs = touch, touchesMs = sc.motion.touches)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("BENCH " + row(sc.name, r, ms))
            if (keep) {
                println("BENCH   $r")
                println("BENCH   ${r.motionLine()}")
                println("BENCH   damage: ${r.log()}")
                println("BENCH   blackouts: ${r.moving.blackoutLog.joinToString(" ")}")
                for (line in r.timeline) println("BENCH   $line")
            }
            sane(sc, r)
        }
        println("BENCH done in ${(System.nanoTime() - started) / 1_000_000_000} s")
    }

    /** The measures make sense, whatever the engine does. */
    private fun sane(sc: Scenario, r: ShadeSimulation.Report) {
        val n = sc.name
        assertTrue("$n: frames", r.frames > 0)
        assertTrue("$n: the page moved", r.motion.movingFrames > 0)
        assertTrue("$n: some coverage measured", r.moving.count > 0)
        for (c in listOf(r.moving.mean, r.moving.percentile(0.10), r.moving.min, r.moving.weighted)) assertTrue("$n: coverage $c", c in 0.0..1.0)
        assertTrue("$n: p10 under the mean", r.moving.percentile(0.10) <= r.moving.mean + 1e-9)
        assertTrue("$n: min under p10", r.moving.min <= r.moving.percentile(0.10))
        assertTrue("$n: p95 within the max", r.motion.movePercentile(0.95) <= r.motion.moveMax)
        assertTrue("$n: wobble within reach", r.motion.moveMax <= MotionMeter.REACH)
        assertTrue("$n: white at rest within the worst", r.whiteRest <= r.whiteMax)
        assertTrue("$n: delivered and lost within changed", r.framesDelivered + r.framesDropped <= r.framesChanged)
        if (sc.timing.dropShare == 0.0) assertEquals("$n: nothing lost without drops", 0, r.framesDropped)
    }

    private fun row(name: String, r: ShadeSimulation.Report, ms: Long): String {
        val m = r.motion
        val c = r.moving
        val car = r.motionCarried
        val new = r.motionReplaced
        val newShare = if (m.movingFrames == 0) 0.0 else 100.0 * new.movingFrames / m.movingFrames
        return ("%-34s %6d %6d %6d %5d | %6d/%-5d | %5.2f %5.2f %5.2f %3d | %3d | %6.0f %6d | %5.2f %3d %3d %5.1f%% %4d | " +
            "%3d %5.1f%% | %3.0f%% %3d %5.1f%% | %3d/%-3d | %3d/%-3d | %5d").format(
            name, r.onsetMax, r.steadyMax, r.stopMax, r.restDamage, r.whiteMax, r.whiteRest,
            c.mean, c.percentile(0.10), c.min, c.blackouts, r.drops,
            m.flickerMean, m.flickerMax, m.moveMean, m.movePercentile(0.95), m.moveMax, 100 * m.jumpyShare, m.pops,
            car.movePercentile(0.95), 100 * car.jumpyShare, newShare, new.movePercentile(0.95), 100 * new.jumpyShare,
            m.settleMoveMax, m.settlePops, r.framesDropped, r.framesChanged, ms,
        )
    }

    private companion object {
        val HEADER = ("%-34s %6s %6s %6s %5s | %12s | %5s %5s %5s %3s | %3s | %6s %6s | %5s %3s %3s %6s %4s | " +
            "%3s %6s | %4s %3s %6s | %7s | %7s | %5s").format(
            "scenario", "onset", "steady", "stop", "rest", "white max/rst", "cov", "p10", "min", "blk", "drp",
            "flick", "fmax", "wob", "p95", "max", ">2", "pops", "c95", "c>2", "new", "n95", "n>2", "set w/p", "cap", "ms",
        )
    }
}
