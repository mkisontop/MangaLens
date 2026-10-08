package app.mangalens.gaps

import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * The gap finder on white art, frame by frame, judged against the truth the scenes carry.
 *
 * Each scene of [WhiteArtScenes] is looked at through a 720 × 1400 screen at a dozen places
 * down the strip, half of them an odd number of rows down (so the half-resolution pass sees
 * the other half of the rows), by the two passes the engine makes: the exact one at rest,
 * and the half-resolution one, pulled back by a margin, that runs while the page moves. Both
 * run as they do mid-read, with art seen a moment ago.
 */
internal object WhiteArtBench {
    const val SCREEN_W = 720
    const val SCREEN_H = 1400
    const val TOPS = 12

    /** Frames with less gutter than this in view say nothing about recall. */
    const val MIN_GUTTER_PX = 2000

    /** Art shaded further than this from any paper is counted: the anti-aliased ramp beside paper is at most this deep. */
    const val ART_REACH = 3

    /**
     * The exact pass takes in a ramp of up to three pixels and the dark pixel it ends on (see
     * GapFinder.growFringe), so art four pixels from paper may be shaded by design; past that it
     * is damage whatever the reason.
     */
    const val FRINGE_REACH = 4

    class Pass(val name: String, val step: Int, val margin: Int)

    val EXACT = Pass("exact", 1, 0)
    val MOVING = Pass("moving", 2, 8)

    private val style = ShadeStyle(ShadeLevel.DARK)

    /** Per scene and pass: the worst and the mean over its frames. */
    class Result(val scene: Scene, val pass: Pass) {
        var frames = 0
        var protMax = 0
        var protSum = 0L
        var openMax = 0
        var openSum = 0L
        var artMax = 0
        var artFarMax = 0
        var recallSum = 0.0
        var recallN = 0

        /** The frame that shaded the most protected white, and where in the strip that white was. */
        var worstTop = -1
        var worstBox: IntArray? = null
        var worstColumn = ""

        val protMean get() = if (frames == 0) 0.0 else protSum.toDouble() / frames
        val openMean get() = if (frames == 0) 0.0 else openSum.toDouble() / frames
        val recall get() = if (recallN == 0) Double.NaN else recallSum / recallN

        val failing get() = protMax > 0
        val lowRecall get() = scene.plainGutters && pass === EXACT && recall < 0.9

        fun status(): String {
            val s = ArrayList<String>()
            if (failing) s.add("currently failing")
            if (openMax > 0) s.add("open white shaded (known limit)")
            if (artFarMax > 0) s.add("ART DAMAGE") else if (artMax > 0) s.add("fringe anchor at 4 px")
            if (lowRecall) s.add("low recall")
            return if (s.isEmpty()) "ok" else s.joinToString(", ")
        }

        fun row(): String = "%-38s %-6s %9d %10.0f %9d %10.0f %8d %7s  %s".format(
            scene.name, pass.name, protMax, protMean, openMax, openMean, artMax,
            if (recall.isNaN()) "-" else "%.3f".format(recall), status(),
        )

        fun detail(): String? {
            val b = worstBox ?: return null
            return "    worst at top=$worstTop: $protMax protected px shaded in strip x ${b[0]}..${b[2]}, y ${b[1]}..${b[3]}; $worstColumn"
        }
    }

    const val HEADER = "scene                                  pass   prot.max  prot.mean  open.max  open.mean  art.max  recall  status"

    /** The rows of the strip at the top of the screen: spread evenly, every other one odd. */
    fun tops(scene: Scene): IntArray {
        val span = scene.h - SCREEN_H
        return IntArray(TOPS) { i ->
            val t = (span.toLong() * i / (TOPS - 1)).toInt()
            val odd = if (i % 2 == 1) t or 1 else t and 1.inv()
            odd.coerceIn(0, span)
        }
    }

    fun measure(scene: Scene, pass: Pass): Result {
        val r = Result(scene, pass)
        val w = SCREEN_W
        val h = SCREEN_H
        require(scene.w == w) { "${scene.name}: scenes are ${SCREEN_W} wide" }
        for (top in tops(scene)) {
            val frame = scene.strip.frame(top, h)
            val planes = PlaneBuilder.build(ArrayPixels(w, h, frame), pass.step, style)
            val found = GapFinder.find(planes, pass.margin, GapParams(), artRecently = true)
            val covered = coverage(found.rects, found.rectCount, w, h)
            // paper within reach of each pixel, for telling the anti-aliased edge from the art
            val paper = BitPlane(w, h)
            for (y in 0 until h) for (x in 0 until w) if (Strip.isPaper(frame[y * w + x])) paper.set(x, y)
            val nearPaper = paper.copy().dilate(ART_REACH)
            val fringeReach = paper.copy().dilate(FRINGE_REACH)
            var prot = 0
            var open = 0
            var art = 0
            var artFar = 0
            var gutter = 0
            var gutterHit = 0
            var bx0 = Int.MAX_VALUE
            var by0 = Int.MAX_VALUE
            var bx1 = -1
            var by1 = -1
            val base = top * w
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                val t = scene.truth[base + i]
                val c = covered[i]
                when (t) {
                    Truth.GUTTER -> {
                        gutter++
                        if (c) gutterHit++
                    }
                    Truth.PROTECTED -> if (c) {
                        prot++
                        if (x < bx0) bx0 = x
                        if (x > bx1) bx1 = x
                        if (y < by0) by0 = y
                        if (y > by1) by1 = y
                    }
                    Truth.OPEN -> if (c) open++
                    Truth.ART -> if (c && !nearPaper.get(x, y)) {
                        art++
                        if (!fringeReach.get(x, y)) artFar++
                    }
                }
            }
            r.frames++
            r.protSum += prot
            r.openSum += open
            if (open > r.openMax) r.openMax = open
            if (art > r.artMax) r.artMax = art
            if (artFar > r.artFarMax) r.artFarMax = artFar
            if (gutter >= MIN_GUTTER_PX) {
                r.recallSum += gutterHit.toDouble() / gutter
                r.recallN++
            }
            if (prot > r.protMax) {
                r.protMax = prot
                r.worstTop = top
                r.worstBox = intArrayOf(bx0, by0 + top, bx1, by1 + top)
                val col = GapFinder.column(planes)
                r.worstColumn = "reading column (plane px, step ${pass.step}): left ${col.left}, right ${col.right}, bars ${col.barLeft}/${col.barRight}"
            }
        }
        return r
    }
}

class WhiteArtTest {

    @Test
    fun `white art and gutters, measured scene by scene`() {
        println("WHITEART " + WhiteArtBench.HEADER)
        var scenes = 0
        var failing = 0
        for (scene in WhiteArtScenes.all()) {
            scenes++
            // the instrument itself: a scene judges something
            var kept = 0L
            var gutter = 0L
            for (t in scene.truth) {
                if (t == Truth.PROTECTED || t == Truth.OPEN) kept++
                if (t == Truth.GUTTER) gutter++
            }
            assertTrue("${scene.name}: no white to judge", kept > 0 || gutter > 0)
            var sceneFails = false
            for (pass in listOf(WhiteArtBench.EXACT, WhiteArtBench.MOVING)) {
                val r = WhiteArtBench.measure(scene, pass)
                println("WHITEART " + r.row())
                r.detail()?.let { println("WHITEART $it") }
                if (r.failing) sceneFails = true
            }
            if (sceneFails) failing++
        }
        println("WHITEART $failing of $scenes scenes currently shade protected white")
        assertEquals(WhiteArtScenes.count, scenes)
    }

    /**
     * What the finder should achieve, to be switched on once it does: protected white is never
     * shaded by the exact pass (past the two-pixel band at a boundary, which the truth already
     * leaves out) and hardly ever by the moving one; art is never shaded beyond its fringe;
     * and plain gutters are still nearly all shaded at rest.
     */
    @Ignore("enable once white art is fixed")
    @Test
    fun `white art stays white and gutters still go dark`() {
        val problems = ArrayList<String>()
        for (scene in WhiteArtScenes.all()) {
            val exact = WhiteArtBench.measure(scene, WhiteArtBench.EXACT)
            val moving = WhiteArtBench.measure(scene, WhiteArtBench.MOVING)
            if (exact.protMax > 0) problems.add("${scene.name}: exact pass shades ${exact.protMax} protected px (top ${exact.worstTop})")
            if (moving.protMax > MOVING_PROTECTED_MAX) problems.add("${scene.name}: moving pass shades ${moving.protMax} protected px (top ${moving.worstTop})")
            if (exact.artFarMax > 0 || moving.artFarMax > 0) problems.add("${scene.name}: art shaded beyond its fringe: ${exact.artFarMax} / ${moving.artFarMax} px")
            if (scene.plainGutters && !(exact.recall >= MIN_RECALL)) problems.add("${scene.name}: gutter recall at rest ${"%.3f".format(exact.recall)}")
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * The reader's own strip, which has no truth: what each pass shades at a few places down it,
     * written out as previews to look at, with the shaded regions listed in strip coordinates.
     * Only with WHITEART_REAL=1, the strip's path in WHITEART_STRIP and somewhere to write in
     * WHITEART_OUT.
     */
    @Test
    fun `the reader's own strip, previewed`() {
        if (System.getenv("WHITEART_REAL") != "1") return
        val path = System.getenv("WHITEART_STRIP")?.takeIf { it.isNotEmpty() } ?: return
        val src = File(path)
        if (!src.exists()) return
        val out = File(System.getenv("WHITEART_OUT")?.takeIf { it.isNotEmpty() } ?: "build/whiteart-out").also { it.mkdirs() }
        val img = ImageIO.read(src)
        val w = img.width
        val sh = img.height
        val strip = IntArray(w * sh)
        img.getRGB(0, 0, w, sh, strip, 0, w)
        for (i in strip.indices) strip[i] = strip[i] or (0xFF shl 24)
        val h = REAL_SCREEN_H
        val style = ShadeStyle(ShadeLevel.DARK)
        val step = (sh - h) / (REAL_TOPS - 1)
        for (k in 0 until REAL_TOPS) {
            val top = minOf(sh - h, k * step + (k % 2))
            val frame = IntArray(w * h)
            System.arraycopy(strip, top * w, frame, 0, w * h)
            val shown = ArrayList<IntArray>()
            for (pass in listOf(WhiteArtBench.EXACT, WhiteArtBench.MOVING)) {
                val planes = PlaneBuilder.build(ArrayPixels(w, h, frame), pass.step, style)
                val r = GapFinder.find(planes, pass.margin, GapParams(), artRecently = true)
                val cov = coverage(r.rects, r.rectCount, w, h)
                val col = GapFinder.column(planes)
                println("WHITEART-REAL top=$top ${pass.name}: ${r.rectCount} rects, ${r.shadedPixels} px shaded, column ${col.left}/${col.right} (plane px), page rows ${r.pageTop}..${r.pageBottom}")
                for (blob in blobs(cov, w, h)) {
                    println("WHITEART-REAL     shaded region x ${blob[0]}..${blob[2]}, y ${blob[1] + top}..${blob[3] + top} (strip), ${blob[4]} px")
                }
                shown.add(tinted(frame, cov))
            }
            writePreview(File(out, "real_top%05d.png".format(top)), w, h, shown)
        }
    }

    /** The frame with shaded pixels darkened to a tenth and pushed to red, so nothing shaded can pass for art. */
    private fun tinted(frame: IntArray, cov: BooleanArray): IntArray = IntArray(frame.size) { i ->
        val p = frame[i]
        if (!cov[i]) p else {
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            Strip.rgb(minOf(255, r / 10 + 90), g / 10, b / 10)
        }
    }

    /** The frames side by side, scaled to a third. */
    private fun writePreview(file: File, w: Int, h: Int, frames: List<IntArray>) {
        val sw = w / 3
        val shh = h / 3
        val gap = 12
        val sheet = BufferedImage(frames.size * sw + (frames.size - 1) * gap, shh, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.color = java.awt.Color(0, 160, 255)
        g.fillRect(0, 0, sheet.width, sheet.height)
        for ((i, f) in frames.withIndex()) {
            val full = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            full.setRGB(0, 0, w, h, f, 0, w)
            g.drawImage(full.getScaledInstance(sw, shh, Image.SCALE_AREA_AVERAGING), i * (sw + gap), 0, null)
        }
        g.dispose()
        ImageIO.write(sheet, "png", file)
    }

    /**
     * The shaded regions of [cov], as `x0, y0, x1, y1, pixels`: connected at an eighth of the
     * resolution, so a gutter cut into rows of rectangles round a balloon is still one region.
     */
    private fun blobs(cov: BooleanArray, w: Int, h: Int): List<IntArray> {
        val s = 8
        val bw = (w + s - 1) / s
        val bh = (h + s - 1) / s
        val count = IntArray(bw * bh)
        for (y in 0 until h) for (x in 0 until w) if (cov[y * w + x]) count[(y / s) * bw + x / s]++
        val seen = BooleanArray(bw * bh)
        val out = ArrayList<IntArray>()
        val stack = IntArray(bw * bh)
        for (start in 0 until bw * bh) {
            if (seen[start] || count[start] == 0) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var x0 = Int.MAX_VALUE
            var y0 = Int.MAX_VALUE
            var x1 = 0
            var y1 = 0
            var px = 0
            while (sp > 0) {
                val c = stack[--sp]
                val cx = c % bw
                val cy = c / bw
                px += count[c]
                x0 = minOf(x0, cx * s); y0 = minOf(y0, cy * s); x1 = maxOf(x1, minOf(w, cx * s + s)); y1 = maxOf(y1, minOf(h, cy * s + s))
                for ((dx, dy) in NEIGHBOURS) {
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx !in 0 until bw || ny !in 0 until bh) continue
                    val n = ny * bw + nx
                    if (!seen[n] && count[n] > 0) {
                        seen[n] = true
                        stack[sp++] = n
                    }
                }
            }
            if (px >= MIN_BLOB_PX) out.add(intArrayOf(x0, y0, x1, y1, px))
        }
        return out
    }

    private companion object {
        /** Most protected pixels the moving pass may shade in a frame: a sliver at a boundary, never a region. */
        const val MOVING_PROTECTED_MAX = 100
        const val MIN_RECALL = 0.9

        /** The phone the reader's screenshots came from. */
        const val REAL_SCREEN_H = 2960
        const val REAL_TOPS = 7
        const val MIN_BLOB_PX = 400
        val NEIGHBOURS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    }
}
