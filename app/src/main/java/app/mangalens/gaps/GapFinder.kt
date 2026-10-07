package app.mangalens.gaps

import kotlin.math.max
import kotlin.math.min

/** Thresholds of the gap finder. Fractions are of the frame's area or of the reading column's width. */
class GapParams(
    /**
     * A white region that reaches only one edge of the reading column — art, or a balloon whose
     * tail reaches the panel, has cut into the gutter and left it in two — is still a gutter
     * when it runs this share of the column's width unbroken at some row. A region that reaches both edges is a gutter at any width,
     * and one that reaches neither is a balloon.
     */
    val wideRun: Float = 0.4f,

    /** Smallest gutter, as a share of the frame, measured on the sealed (eroded) region. */
    val minGapArea: Float = 0.0015f,

    /** Share of the frame's blocks that must be art for a page to count as one. */
    val artShare: Float = 0.012f,

    /**
     * A region this big that floats clear of the screen's edges is still treated as ink to be
     * protected, not as art: only what touches an edge, or is a solid rectangle, is a panel.
     * This is just the size past which a margin is no longer worth drawing.
     */
    val artArea: Float = 0.30f,

    /** A solid rectangle this big is a panel or a black box, and is left to the art. */
    val solidArea: Float = 0.008f,

    /** Smallest enclosed region that can be a balloon. */
    val balloonArea: Float = 0.0008f,

    /**
     * A gutter whose protected margins swallow more than this share of it is not a gutter:
     * it is a page of lettering or linework on white, and what little the margins leave
     * would only be a scatter of dark pockets.
     */
    val maxHaloShare: Float = 0.60f,
)

/**
 * The gaps of one frame, as rectangles in the frame's own pixel coordinates.
 *
 * [rects] holds `x0, y0, x1, y1` quadruples, half-open, disjoint, ready to be painted.
 */
class GapResult(
    val rects: IntArray,
    val rectCount: Int,
    /** Area the rectangles cover, in frame pixels. */
    val shadedPixels: Long,
    /** Whether the frame carried artwork: shading is only trusted on a page that has some. */
    val sawArt: Boolean,
    val gapCount: Int,
) {
    companion object {
        val EMPTY = GapResult(IntArray(0), 0, 0L, false, 0)
    }
}

/**
 * Finds the white gutters between a manhwa's panels, and nothing else.
 *
 * The hard part is not finding white; it is knowing which white may be darkened. Paper
 * inside a speech balloon must stay paper, black lettering on a white gutter must stay
 * readable, and a white sky in a panel must stay a sky. The rules, in the order they
 * bite:
 *
 *  1. *Paper* is strictly white — every channel 240 or more — so pale art, anti-aliased
 *     edges and compression ringing are never touched.
 *  2. The paper is eroded by two pixels before its regions are labelled. An outline with
 *     a hairline break would otherwise let the gutter flow into a balloon and darken its
 *     inside; eroded, any break under five pixels is sealed shut.
 *  3. A region is a gutter only if it reaches the reading column's edges — both of them for a
 *     band of any slant, one of them if it runs most of the column's width. A balloon is shut
 *     in by its outline and reaches neither, however wide it is.
 *  4. Everything that is not gutter — art, balloons, lettering — is labelled in turn. A
 *     balloon (an enclosed region that is mostly paper) is left exactly as drawn: the
 *     gutter stops at its outline. Solid panels and anything touching the frame's edge are
 *     art, and the gutter stops at them as well. Whatever else floats in the gutter —
 *     narration, sound effects, a scribble — gets a margin of protected paper, so its black
 *     ink is never left on a dark ground.
 *  5. A gutter that the margins swallow is dropped: it is a drawing, not a gap.
 *  6. In motion the result is eroded vertically by a margin, so a stale or mispredicted
 *     overlay can never reach art.
 */
object GapFinder {

    /** Frame pixels of margin around floating ink, by the size of the ink. */
    private const val HALO_SMALL = 8
    private const val HALO_MID = 14
    private const val HALO_LARGE = 24

    private const val MAX_RECTS = 6000

    /** Thickest band of full-width paper rows that is a seam between images rather than a gutter. */
    private const val MAX_SEAM = 6

    /** Narrowest black bar that the gutters meeting it are asked about: wider than a panel's border. */
    private const val BAR_MIN = 6
    private const val BAR_MIN_SHARE = 0.02f

    /** Share of the gutter rows that must meet a bar in the same column. */
    private const val BAR_STRAIGHT_SHARE = 0.6f

    /** Plane pixels between a black bar and the paper beside it: the soft edge between them. */
    private const val BAR_EDGE = 3

    /** Sampled rows of gutter that must meet a bar before they are believed. */
    private const val MIN_GUTTER_ROWS = 2

    /** Share of the rows that may cross a bar — the browser's bars, a site's header and footer. */
    private const val MAX_CROSSING_SHARE = 0.4f

    /** Shares of the screen, at the top and at the bottom, where the browser's and a site's bars may be. */
    private const val CHROME_TOP_SHARE = 0.2f
    private const val CHROME_BOTTOM_SHARE = 0.15f

    /** Side of the blocks the art check counts, in plane pixels, and the share of one that must be ink. */
    private const val ART_BLOCK = 16
    private const val ART_DENSITY = 55

    fun find(
        planes: Planes,
        marginPx: Int = 0,
        params: GapParams = GapParams(),
        artRecently: Boolean = false,
    ): GapResult {
        val w = planes.w
        val h = planes.h
        if (w < 16 || h < 16) return GapResult.EMPTY
        val area = w.toLong() * h
        val step = planes.step
        val sealR = if (step == 1) 2 else 1

        val column = column(planes)
        val colL = column.left
        val colR = column.right
        val colW = max(1, w - colL - colR)

        val sawArt = hasArt(planes, colL, colR, params)
        if (!sawArt && !artRecently) return GapResult(IntArray(0), 0, 0L, false, 0)

        // 2. Seal.
        val core = planes.paper.copy().erode(sealR, outside = true)

        // 3. Label the sealed paper and pick the gutters.
        val labels = label(core, connect8 = false)
        val wideMin = (params.wideRun * colW).toInt()
        val minArea = (params.minGapArea * area).toLong()
        // A gutter is the page itself: it runs out to the reading column's edge, on both sides if
        // it is a band, however slanted, and on one if art has cut into it. A balloon is shut in
        // by its outline and reaches neither, however wide it is.
        // A column that runs to the frame's edge is reached only by paper at the very edge: a white
        // panel boxed in a border, however thin, flush with the frame never gets there. Beside a bar
        // the sealed paper stops a seal's width short of the bar, plus the ramp of a soft edge, and
        // the tolerance says so.
        val reachL = if (colL == 0) 1 else colL + 2 * sealR + 2
        val reachR = if (colR == 0) w - 1 else w - colR - 2 * sealR - 2
        // Paper that runs on into a black bar, near the top or the bottom of the screen, is on
        // the browser's or the site's bars — a grey of theirs that passes for paper under the
        // shade — and not the page's: beside a gutter the bar is black.
        val chrome = Chrome(planes, column, sealR)
        val picked = ArrayList<Int>()
        for (c in 0 until labels.compCount) {
            if (labels.area[c] < minArea) continue
            val left = labels.minX[c] <= reachL
            val right = labels.maxX[c] >= reachR
            if (!((left && right) || ((left || right) && labels.maxRun[c] >= wideMin))) continue
            if (chrome.into(labels.minX[c], labels.maxX[c]) && chrome.holds(labels, c)) continue
            picked.add(c)
        }
        val seams = seamRows(planes, reachL, reachR, chrome)
        if (picked.isEmpty() && seams == null) return GapResult(IntArray(0), 0, 0L, sawArt, 0)

        val seed = BitPlane(w, h)
        for (c in picked) labels.paintComponent(c, seed)
        if (seams != null) seed.or(seams)
        var gutter = seed.copy().dilate(sealR).and(planes.paper)

        // 4. Everything that is not gutter, labelled eight-connected.
        val rest = labelComplement(gutter)
        val halo1 = BitPlane(w, h)
        val halo2 = BitPlane(w, h)
        val halo3 = BitPlane(w, h)
        val specks = BitPlane(w, h)
        val speckMax = max(6, 40 / (step * step))
        val artMin = (params.artArea * area).toLong()
        val solidMin = (params.solidArea * area).toLong()
        val balloonMin = (params.balloonArea * area).toLong()
        val roles = IntArray(rest.compCount)
        for (c in 0 until rest.compCount) {
            val a = rest.area[c]
            val bw = rest.maxX[c] - rest.minX[c]
            val bh = rest.maxY[c] - rest.minY[c] + 1
            // Whatever runs off the frame is art — unless it is a fragment: a letter the edge has
            // cut in half is still lettering, and wants its margin.
            if (rest.touchesFrame[c] && (bw >= 0.5f * colW || bh >= 0.5f * colW)) continue
            val fill = a.toFloat() / (bw.toFloat() * bh).coerceAtLeast(1f)
            var paperCount = 0L
            var inkCount = 0L
            var coreCount = 0L
            var faintCount = 0L
            val faint = planes.faint
            rest.forEachSegment(c) { y, x0, x1 ->
                paperCount += planes.paper.countRow(y, x0, x1)
                inkCount += planes.ink.countRow(y, x0, x1)
                coreCount += core.countRow(y, x0, x1)
                if (faint != null) faintCount += faint.countRow(y, x0, x1)
            }
            when {
                // A speck is dust: a few pixels too pale to be lettering. A grey hairline or a
                // fleck of a light watermark is also not ink, but it is more than dust — and the
                // shade reads it back as ink, so it must be protected here as it will be there.
                inkCount == 0L && faintCount == 0L && a <= speckMax -> roles[c] = ROLE_SPECK
                a >= artMin -> Unit
                // A solid rectangle with hardly any paper in it: a panel, or a black narration box.
                // (Not merely a full bounding box: a word of lettering fills its box too, with
                // the paper between its strokes.)
                fill >= 0.85f && a >= solidMin && paperCount <= 0.2f * a -> Unit
                // A big block is a panel whatever is inside it — a screentone is mostly paper between its
                // dots — and whatever its shape: slanted, rounded. Lettering is never a tenth of the
                // column deep.
                fill >= 0.6f && a >= solidMin && min(bw, bh) >= 0.1f * colW -> Unit
                // The same, rounded: an inset panel in a circle or an oval. Nothing a letterer draws
                // is this big, this dense and this full of ink.
                fill >= 0.7f && a >= solidMin && paperCount <= 0.2f * a && min(bw, bh) >= 0.15f * colW -> Unit
                // A balloon holds a real stretch of paper — one that survives the erosion, which
                // the paper between the strokes of lettering never does.
                a >= balloonMin && coreCount >= 0.25f * a -> Unit
                else -> roles[c] = ROLE_HALO
            }
        }
        for (c in 0 until rest.compCount) {
            when (roles[c]) {
                ROLE_SPECK -> rest.paintComponent(c, specks)
                ROLE_HALO -> {
                    val size = min(rest.maxX[c] - rest.minX[c], rest.maxY[c] - rest.minY[c] + 1) * step
                    val p = (0.45f * size).toInt()
                    val target = when {
                        p <= HALO_SMALL -> halo1
                        p <= HALO_MID + 2 -> halo2
                        else -> halo3
                    }
                    rest.paintComponent(c, target)
                }
            }
        }
        val halo = BitPlane(w, h)
        if (!halo1.isEmpty()) halo.or(halo1.dilate(radius(HALO_SMALL, step)))
        if (!halo2.isEmpty()) halo.or(halo2.dilate(radius(HALO_MID, step)))
        if (!halo3.isEmpty()) halo.or(halo3.dilate(radius(HALO_LARGE, step)))

        // 5. Drop gutters the margins swallow.
        val extra = if (seams != null) 1 else 0
        var kept = picked.size + extra
        if (!halo.isEmpty()) {
            val keepSeed = BitPlane(w, h)
            kept = 0
            if (seams != null) {
                keepSeed.or(seams)
                kept++
            }
            for (c in picked) {
                var coreArea = 0L
                var haloed = 0L
                labels.forEachRun(c) { y, x0, x1 ->
                    coreArea += x1 - x0
                    haloed += halo.countRow(y, x0, x1)
                }
                if (haloed <= params.maxHaloShare * coreArea) {
                    labels.paintComponent(c, keepSeed)
                    kept++
                }
            }
            if (kept < picked.size + extra) gutter = keepSeed.dilate(sealR).and(planes.paper)
        }
        if (kept == 0) return GapResult(IntArray(0), 0, 0L, sawArt, 0)

        val shade = gutter.or(specks).andNot(halo)
        // Only the exact pass takes in the edge: in motion the shade is pulled back from it anyway.
        if (planes.lum != null && marginPx == 0) growFringe(shade, planes, halo)

        // 6. Margin for motion.
        val marginRows = (marginPx + step - 1) / step
        if (marginRows > 0) shade.erodeV(marginRows, outside = false)
        // A coarse plane pixel stands for a block of frame pixels of which only one was looked
        // at. Pulling the shade back by one sample keeps it off a hairline between samples.
        if (step > 1) {
            shade.erodeH(1)
            if (marginRows == 0) shade.erodeV(1)
        }

        return extractRects(shade, step, planes.frameW, planes.frameH, kept)
    }

    /**
     * Hairline seams: the one to six rows of white that fractional scaling leaves between two
     * stacked images. Too thin to survive the seal, they would stay lit across the whole width
     * of the screen. A row that is paper from one edge of the reading column to the other can
     * only be a gutter, whatever its thickness, so thin bands of such rows are taken as one
     * (thicker ones are the business of the labelling above, and the gaps between lines of
     * lettering are kept clear by the margins). Only the exact pass looks: a coarse row would
     * take in a row of art with the seam.
     */
    private fun seamRows(planes: Planes, lo: Int, hi: Int, chrome: Chrome): BitPlane? {
        if (planes.step != 1) return null
        val w = planes.w
        var seams: BitPlane? = null
        var y = planes.validY0
        while (y < planes.validY1) {
            if (!spansRow(planes.paper, y, lo, hi)) {
                y++
                continue
            }
            var end = y + 1
            while (end < planes.validY1 && spansRow(planes.paper, end, lo, hi)) end++
            if (end - y <= MAX_SEAM) {
                val plane = seams ?: BitPlane(w, planes.h).also { seams = it }
                for (r in y until end) planes.paper.forEachRun(r) { a, b ->
                    if (a <= lo && b >= hi && !(chrome.into(a, b) && chrome.holds(r))) plane.setRun(r, a, b)
                }
            }
            y = end
        }
        return seams
    }

    private fun spansRow(paper: BitPlane, y: Int, lo: Int, hi: Int): Boolean {
        var spans = false
        paper.forEachRun(y) { a, b -> if (a <= lo && b >= hi) spans = true }
        return spans
    }

    /**
     * The rectangles of an earlier result, pulled back from every vertical edge by [marginPx].
     *
     * It must be the union that is eroded, not each rectangle on its own: a balloon's outline
     * is a stack of rectangles one row tall, and shrinking each of them would cut the gutter
     * into strips. The shape is rasterised at half resolution — conservatively, so no pixel is
     * covered that was not — eroded, and turned back into rectangles.
     */
    fun erodeRects(rects: IntArray, count: Int, frameW: Int, frameH: Int, marginPx: Int): GapResult {
        if (count == 0) return GapResult.EMPTY
        val step = 2
        val pw = (frameW + step - 1) / step
        val ph = (frameH + step - 1) / step
        val plane = BitPlane(pw, ph)
        for (i in 0 until count) {
            val x0 = (rects[i * 4] + step - 1) / step
            val x1 = rects[i * 4 + 2] / step
            val y0 = (rects[i * 4 + 1] + step - 1) / step
            val y1 = rects[i * 4 + 3] / step
            for (y in y0 until y1) plane.setRun(y, x0, x1)
        }
        plane.erodeV((marginPx + step - 1) / step, outside = false)
        if (plane.isEmpty()) return GapResult.EMPTY
        return extractRects(plane, step, frameW, frameH, 0)
    }

    /**
     * Takes the anti-aliased edge of the art into the shade.
     *
     * Where a black border meets the white of a gutter, the pixels between them are a ramp —
     * 242, 210, 146, 61, 25 — lighter than ink and darker than paper, and strict paper does not
     * include them. Left alone they are a pale hairline along every panel, lit against the dark.
     * They are blends of ink and paper, and shading them with the same translucent black makes
     * exactly the blend of ink and shaded paper.
     *
     * Texture is not a ramp, and textured art must not be nibbled at its edge, so a ramp is
     * taken in only when it is one all the way: walking out from the shade, brightness falls by
     * a clear step at every pixel and ends, within [FRINGE_DEPTH] pixels, on something dark
     * ([FRINGE_ANCHOR] or less) — ink, or the deep colour of the art. Flat pale art does not fall
     * away, a slow fade does not fall by a step, light texture never reaches dark: none of
     * them is touched. Paper is never a candidate, so the margins round lettering and the
     * insides of balloons are left as they are.
     *
     * Read back from a frame that already carries the shade, the same pixels show as dark
     * (a tenth of what they were, [Planes.shadedHi] at most) and are taken in on that account,
     * so the shade finds the same edge it drew. Pure black, 0..3, is left alone.
     */
    private fun growFringe(shade: BitPlane, planes: Planes, halo: BitPlane) {
        val lum = planes.lum ?: return
        val w = shade.w
        val h = shade.h
        val paper = planes.paper
        val hi = planes.shadedHi
        // Candidates are gathered from the shade as it stands, so no pass feeds on its own output.
        val start = shade.copy()

        fun lumAt(x: Int, y: Int): Int = if (x in 0 until w && y in 0 until h) lum[y * w + x].toInt() and 0xFF else -1
        fun free(x: Int, y: Int): Boolean =
            x in 0 until w && y in 0 until h && !shade.get(x, y) && !halo.get(x, y) && !paper.get(x, y)

        /**
         * Walks out from the shade edge at (x0, y0) in direction (dx, dy). The pixels out to the
         * first one at or below [anchor] must fall by at least [step] each, from [top] — the
         * brightness of the paper they are leaving — and there must be at least one of them:
         * a hard edge, white straight onto black, has no fringe. Those pixels, and the anchor
         * itself when it is at least [anchorMin], are taken in, up to the depth.
         */
        fun ramp(x0: Int, y0: Int, dx: Int, dy: Int, top: Int, step: Int, anchor: Int, anchorMin: Int, firstMin: Int): Boolean {
            var x = x0 + dx
            var y = y0 + dy
            var prev = top
            var anchorAt = -1
            for (k in 1..FRINGE_DEPTH + 1) {
                if (!free(x, y)) return false
                val v = lumAt(x, y)
                // the pixel beside the paper is a light one: on real edges it is, by a wide margin
                if (k == 1 && v > anchor && v < firstMin) return false
                if (v <= anchor) {
                    anchorAt = k
                    break
                }
                if (prev - v < step) return false
                prev = v
                x += dx
                y += dy
            }
            // no light pixel before the anchor: nothing to take in
            if (anchorAt < 2) return false
            // Ink is a mass: the pixels after the anchor stay dark. Texture bounces back. They are
            // judged as the page has them — beyond the fringe they are not shaded, whichever way
            // the edge is being read.
            for (k in 1..FRINGE_PERSIST) {
                x += dx
                y += dy
                if (lumAt(x, y) !in 0..FRINGE_ANCHOR) return false
            }
            x = x0 + dx
            y = y0 + dy
            for (k in 1..anchorAt) {
                if (k == anchorAt && lumAt(x, y) < anchorMin) break
                shade.set(x, y)
                x += dx
                y += dy
            }
            return true
        }

        fun walk(x0: Int, y0: Int, dx: Int, dy: Int) {
            if (lumAt(x0, y0) >= PLAIN_PAPER_LUM) {
                // An edge of plain white: the ramp is in the page's own brightness.
                ramp(x0, y0, dx, dy, 255, FRINGE_STEP, FRINGE_ANCHOR, FRINGE_ANCHOR / 3, FRINGE_FIRST)
            } else {
                // An edge where the shade is already on the glass: the same ramp, at a tenth of the
                // brightness. Only here — dark art looks like a shaded ramp and must not be taken for one.
                ramp(x0, y0, dx, dy, hi, 1, hi / 3, SHADED_FRINGE_MIN, SHADED_FRINGE_MIN)
            }
        }

        // Where the shade ends along a row: the two ends of every run.
        for (y in 0 until h) {
            start.forEachRun(y) { x0, x1 ->
                walk(x0, y, -1, 0)
                walk(x1 - 1, y, 1, 0)
            }
        }
        // Where it ends down a column: shade whose neighbour above (or below) is not shade. Found a
        // word at a time, so only the edge itself is visited, not the whole of every gutter.
        val above = BitPlane(w, h)
        val below = BitPlane(w, h)
        for (y in 0 until h) {
            val b = y * start.wpr
            for (k in 0 until start.wpr) {
                val cur = start.bits[b + k]
                val up = if (y > 0) start.bits[b - start.wpr + k] else 0L
                val down = if (y < h - 1) start.bits[b + start.wpr + k] else 0L
                above.bits[b + k] = cur and up.inv()
                below.bits[b + k] = cur and down.inv()
            }
        }
        for (y in 0 until h) {
            above.forEachRun(y) { x0, x1 -> for (x in x0 until x1) walk(x, y, 0, -1) }
            below.forEachRun(y) { x0, x1 -> for (x in x0 until x1) walk(x, y, 0, 1) }
        }
    }

    private const val FRINGE_DEPTH = 3
    private const val FRINGE_STEP = 12
    private const val FRINGE_ANCHOR = 90

    /** A boundary pixel at least this bright is plain paper; darker, it is paper under the shade. */
    private const val PLAIN_PAPER_LUM = 200

    /** Lightest-first: measured on real screenshots, 95% of anti-aliased edges start at 150 or more beside the paper. */
    private const val FRINGE_FIRST = 140

    /** Pixels after the anchor that must stay dark for it to be ink and not texture. */
    private const val FRINGE_PERSIST = 2
    private const val SHADED_FRINGE_MIN = 4

    private fun radius(px: Int, step: Int): Int = max(1, (px + step - 1) / step)

    private const val ROLE_SPECK = 1
    private const val ROLE_HALO = 2

    /** Where the browser's and a site's bars may be: bands at the top and bottom, and the black bars beside the strip. */
    private class Chrome(planes: Planes, column: Column, sealR: Int) {
        private val left = if (column.barLeft) column.left - 2 * sealR - BAR_EDGE else Int.MIN_VALUE
        private val right = if (column.barRight) planes.w - column.right + 2 * sealR + BAR_EDGE else Int.MAX_VALUE
        private val top = planes.validY0 + (CHROME_TOP_SHARE * (planes.validY1 - planes.validY0)).toInt()
        private val bottom = planes.validY1 - (CHROME_BOTTOM_SHARE * (planes.validY1 - planes.validY0)).toInt()

        /** Whether paper over columns [x0, x1) runs on into a black bar. */
        fun into(x0: Int, x1: Int) = x0 < left || x1 > right

        fun holds(y: Int) = y < top || y >= bottom

        /** Whether all of component [c] lies in the bands at the top or the bottom. */
        fun holds(labels: Labels, c: Int): Boolean {
            var above = true
            var below = true
            labels.forEachRun(c) { y, _, _ ->
                if (y >= top) above = false
                if (y < bottom) below = false
            }
            return above || below
        }
    }

    // ---- the reading column ------------------------------------------------------------

    /**
     * Columns at either edge that are uniform from the top of the frame to the bottom — the
     * black bars a reader leaves around a narrow strip, or a site's white margin — are not
     * part of the strip. A gutter spans the strip, not the screen.
     *
     * Not every row of the screen is the strip's, though. The browser's bars, a site's own
     * header and footer with their buttons, a floating button: all of them cross a black bar,
     * and one row of them would make the bar no bar at all — and leave no gutter able to reach
     * the strip's edge. So a black bar is also read where the gutters show it ([barPastCrossings]).
     */
    internal fun readingColumn(planes: Planes): Pair<Int, Int> = column(planes).let { it.left to it.right }

    /**
     * The strip's place on the screen: [left] and [right] plane columns at the edges are not part
     * of it. [barLeft] and [barRight] say that side's black bar was read past rows that cross it.
     */
    internal class Column(val left: Int, val right: Int, val barLeft: Boolean, val barRight: Boolean)

    internal fun column(planes: Planes): Column {
        val w = planes.w
        val wpr = planes.paper.wpr
        val allPaper = LongArray(wpr) { -1L }
        val allInk = LongArray(wpr) { -1L }
        val cap = (0.4f * w).toInt()
        val barMin = max(BAR_MIN, (BAR_MIN_SHARE * w).toInt())
        val minPaper = max(8, (0.2f * w).toInt())
        val sampled = max(0, (planes.validY1 - planes.validY0 + 3) / 4)
        val left = SideRows(sampled)
        val right = SideRows(sampled)
        val ink = planes.ink.bits
        val paper = planes.paper.bits
        var rows = 0
        var y = planes.validY0
        while (y < planes.validY1) {
            val base = y * wpr
            for (k in 0 until wpr) {
                allPaper[k] = allPaper[k] and paper[base + k]
                allInk[k] = allInk[k] and ink[base + k]
            }
            // Black in from each edge, and what follows it: paper running on for a fifth of the screen?
            val l = runForward(ink, base, 0, cap, w)
            left.run[rows] = l
            for (x in l..min(l + BAR_EDGE, w - 1)) {
                if (planes.paper.get(x, y)) {
                    left.paperAfter[rows] = runForward(paper, base, x, minPaper, w) >= minPaper
                    break
                }
            }
            val r = runBackward(ink, base, w - 1, cap)
            right.run[rows] = r
            val edge = w - 1 - r
            for (x in edge downTo max(edge - BAR_EDGE, 0)) {
                if (planes.paper.get(x, y)) {
                    right.paperAfter[rows] = runBackward(paper, base, x, minPaper) >= minPaper
                    break
                }
            }
            rows++
            y += 4
        }
        if (rows < 4) return Column(0, 0, false, false)
        fun uniform(x: Int): Boolean {
            val k = x ushr 6
            val bit = 1L shl (x and 63)
            return (allPaper[k] and bit) != 0L || (allInk[k] and bit) != 0L
        }
        var l = 0
        while (l < cap && uniform(l)) l++
        var r = 0
        while (r < cap && uniform(w - 1 - r)) r++
        val barL = barPastCrossings(left, rows, barMin, cap)
        val barR = barPastCrossings(right, rows, barMin, cap)
        if (barL > l) l = barL
        if (barR > r) r = barR
        if (w - l - r < 0.3f * w) return Column(0, 0, false, false)
        return Column(l, r, barL > 0 && barL == l, barR > 0 && barR == r)
    }

    /** Per sampled row, from one edge: how far black runs in, and whether paper then runs on for a good stretch. */
    private class SideRows(n: Int) {
        val run = IntArray(n)
        val paperAfter = BooleanArray(n)
    }

    /**
     * The width of a black bar on one side, read from the gutters that meet it; 0 when the rows
     * do not show one.
     *
     * A row that runs black from the screen's edge and then paper for a fifth of the screen is a
     * gutter meeting the bar, and the bar ends where its paper begins. The bar is believed when
     * those rows agree on where that is, it is wider than a panel's border, and the rows that
     * cross it short of there are a minority — what crosses a bar is a band of the screen, never
     * most of it. Paper that runs right out to the screen's edge in the middle of the frame is
     * a gutter with no bar beside it, and then there is none.
     */
    private fun barPastCrossings(side: SideRows, rows: Int, barMin: Int, cap: Int): Int {
        val run = side.run
        var bar = Int.MAX_VALUE
        var meeting = 0
        for (i in 0 until rows) {
            if (side.paperAfter[i] && run[i] >= barMin && run[i] < cap) {
                meeting++
                if (run[i] < bar) bar = run[i]
            }
        }
        if (meeting < MIN_GUTTER_ROWS) return 0
        // a bar's edge is straight: the gutters meet it in the same column
        var straight = 0
        for (i in 0 until rows) if (side.paperAfter[i] && run[i] >= bar && run[i] <= bar + 2 * BAR_EDGE) straight++
        if (straight < BAR_STRAIGHT_SHARE * meeting) return 0
        // The rows that cross the bar. Near the top and the bottom of the screen they are the
        // browser's and the site's bars, whose grey can even pass for paper under the shade;
        // paper that reaches the edge between those is the page's own.
        val top = (CHROME_TOP_SHARE * rows).toInt()
        val bottom = rows - (CHROME_BOTTOM_SHARE * rows).toInt()
        var crossings = 0
        var reachesEdge = 0
        for (i in 0 until rows) {
            if (run[i] >= bar - BAR_EDGE) continue
            crossings++
            if (i in top until bottom && run[i] < BAR_EDGE && side.paperAfter[i]) reachesEdge++
        }
        if (crossings > MAX_CROSSING_SHARE * rows) return 0
        if (reachesEdge >= MIN_GUTTER_ROWS) return 0
        return bar
    }

    /** How many pixels from [x0] onwards, at most [limit], are set in the row at [base] of a plane [w] wide. */
    private fun runForward(bits: LongArray, base: Int, x0: Int, limit: Int, w: Int): Int {
        var run = 0
        while (run < limit) {
            val x = x0 + run
            if (x >= w) return run
            val word = bits[base + (x ushr 6)] ushr (x and 63)
            val avail = 64 - (x and 63)
            val ones = java.lang.Long.numberOfTrailingZeros(word.inv())
            if (ones < avail) return min(limit, run + ones)
            run += avail
        }
        return limit
    }

    /** How many pixels from [x0] back towards the row's start, at most [limit], are set in the row at [base]. */
    private fun runBackward(bits: LongArray, base: Int, x0: Int, limit: Int): Int {
        var run = 0
        while (run < limit) {
            val x = x0 - run
            if (x < 0) return run
            val b = x and 63
            val word = bits[base + (x ushr 6)] shl (63 - b)
            val ones = java.lang.Long.numberOfLeadingZeros(word.inv())
            if (ones < b + 1) return min(limit, run + ones)
            run += b + 1
        }
        return limit
    }

    /**
     * Whether the frame carries artwork. A page is art-like where most of a block is not
     * paper; lettering is thin enough never to fill half a block, a photograph or a panel
     * fills nearly all of it. The reading column's margins are not art.
     */
    internal fun hasArt(planes: Planes, colL: Int, colR: Int, params: GapParams): Boolean {
        val block = ART_BLOCK
        val w = planes.w
        val x0 = colL
        val x1 = w - colR
        var artBlocks = 0L
        var by = planes.validY0
        while (by + block <= planes.validY1) {
            var bx = x0
            while (bx + block <= x1) {
                var nonPaper = 0
                var sampled = 0
                var r = 0
                while (r < block) {
                    nonPaper += block - planes.paper.countRow(by + r, bx, bx + block)
                    sampled += block
                    r += 2
                }
                if (nonPaper * 100 >= ART_DENSITY * sampled) artBlocks++
                bx += block
            }
            by += block
        }
        val total = ((x1 - x0) / block).toLong() * ((planes.validY1 - planes.validY0) / block)
        return total > 0 && artBlocks >= params.artShare * total
    }

    // ---- labelling ---------------------------------------------------------------------

    /** Connected components of a plane's runs. */
    internal class Labels(
        val plane: BitPlane,
        val compCount: Int,
        val area: LongArray,
        val maxRun: IntArray,
        /** Leftmost column and one past the rightmost column the component reaches. */
        val minX: IntArray,
        val maxX: IntArray,
        private val runX0: IntArray,
        private val runX1: IntArray,
        private val runRow: IntArray,
        /** Run indices grouped by component: component c owns `order[first[c] until first[c + 1]]`. */
        private val order: IntArray,
        private val first: IntArray,
    ) {
        fun forEachRun(comp: Int, f: (Int, Int, Int) -> Unit) {
            for (j in first[comp] until first[comp + 1]) {
                val i = order[j]
                f(runRow[i], runX0[i], runX1[i])
            }
        }

        fun paintComponent(comp: Int, into: BitPlane) {
            for (j in first[comp] until first[comp + 1]) {
                val i = order[j]
                into.setRun(runRow[i], runX0[i], runX1[i])
            }
        }
    }

    /** Groups item indices by their component with a counting sort: ([order], [first]). */
    private fun bucket(comp: IntArray, n: Int, count: Int): Pair<IntArray, IntArray> {
        val first = IntArray(count + 1)
        for (i in 0 until n) first[comp[i] + 1]++
        for (c in 0 until count) first[c + 1] += first[c]
        val fill = first.copyOf()
        val order = IntArray(n)
        for (i in 0 until n) order[fill[comp[i]]++] = i
        return order to first
    }

    internal fun label(plane: BitPlane, connect8: Boolean): Labels {
        val x0 = IntList()
        val x1 = IntList()
        val row = IntList()
        val parent = IntList()
        var prevStart = 0
        var prevEnd = 0
        for (y in 0 until plane.h) {
            val curStart = x0.n
            plane.forEachRun(y) { a, b ->
                parent.add(x0.n)
                x0.add(a)
                x1.add(b)
                row.add(y)
            }
            val curEnd = x0.n
            unionRows(parent, x0, x1, prevStart, prevEnd, curStart, curEnd, connect8)
            prevStart = curStart
            prevEnd = curEnd
        }
        return summarize(plane, x0, x1, row, parent)
    }

    private fun unionRows(
        parent: IntList, x0: IntList, x1: IntList,
        aStart: Int, aEnd: Int, bStart: Int, bEnd: Int, connect8: Boolean,
    ) {
        var i = aStart
        var j = bStart
        val slack = if (connect8) 1 else 0
        while (i < aEnd && j < bEnd) {
            if (x0.a[i] < x1.a[j] + slack && x0.a[j] < x1.a[i] + slack) union(parent, i, j)
            if (x1.a[i] < x1.a[j]) i++ else j++
        }
    }

    private fun find(parent: IntList, i: Int): Int {
        var x = i
        val p = parent.a
        while (p[x] != x) {
            p[x] = p[p[x]]
            x = p[x]
        }
        return x
    }

    private fun union(parent: IntList, i: Int, j: Int) {
        val a = find(parent, i)
        val b = find(parent, j)
        if (a != b) parent.a[max(a, b)] = min(a, b)
    }

    private fun summarize(plane: BitPlane, x0: IntList, x1: IntList, row: IntList, parent: IntList): Labels {
        val n = x0.n
        val id = IntArray(n)
        var count = 0
        for (i in 0 until n) {
            val r = find(parent, i)
            if (r == i) id[i] = count++ else id[i] = -1
        }
        val comp = IntArray(n)
        val area = LongArray(count)
        val maxRun = IntArray(count)
        val minX = IntArray(count) { Int.MAX_VALUE }
        val maxX = IntArray(count)
        for (i in 0 until n) {
            val c = id[find(parent, i)]
            comp[i] = c
            val len = x1.a[i] - x0.a[i]
            area[c] += len.toLong()
            if (len > maxRun[c]) maxRun[c] = len
            if (x0.a[i] < minX[c]) minX[c] = x0.a[i]
            if (x1.a[i] > maxX[c]) maxX[c] = x1.a[i]
        }
        val (order, first) = bucket(comp, n, count)
        return Labels(plane, count, area, maxRun, minX, maxX, x0.a, x1.a, row.a, order, first)
    }

    /** The components of everything that is not [gutter], eight-connected, with their extents. */
    internal class Rest(
        val compCount: Int,
        val area: LongArray,
        val minX: IntArray,
        val maxX: IntArray,
        val minY: IntArray,
        val maxY: IntArray,
        val touchesFrame: BooleanArray,
        private val segX0: IntArray,
        private val segX1: IntArray,
        private val segRow: IntArray,
        private val order: IntArray,
        private val first: IntArray,
    ) {
        fun forEachSegment(comp: Int, f: (Int, Int, Int) -> Unit) {
            for (j in first[comp] until first[comp + 1]) {
                val i = order[j]
                f(segRow[i], segX0[i], segX1[i])
            }
        }

        fun paintComponent(comp: Int, into: BitPlane) {
            for (j in first[comp] until first[comp + 1]) {
                val i = order[j]
                into.setRun(segRow[i], segX0[i], segX1[i])
            }
        }
    }

    private fun labelComplement(gutter: BitPlane): Rest {
        val w = gutter.w
        val h = gutter.h
        val x0 = IntList()
        val x1 = IntList()
        val row = IntList()
        val parent = IntList()
        var prevStart = 0
        var prevEnd = 0
        for (y in 0 until h) {
            val curStart = x0.n
            var cursor = 0
            gutter.forEachRun(y) { a, b ->
                if (a > cursor) {
                    parent.add(x0.n); x0.add(cursor); x1.add(a); row.add(y)
                }
                cursor = b
            }
            if (cursor < w) {
                parent.add(x0.n); x0.add(cursor); x1.add(w); row.add(y)
            }
            val curEnd = x0.n
            unionRows(parent, x0, x1, prevStart, prevEnd, curStart, curEnd, connect8 = true)
            prevStart = curStart
            prevEnd = curEnd
        }
        val n = x0.n
        val id = IntArray(n)
        var count = 0
        for (i in 0 until n) {
            val r = find(parent, i)
            if (r == i) id[i] = count++ else id[i] = -1
        }
        val comp = IntArray(n)
        val area = LongArray(count)
        val minX = IntArray(count) { Int.MAX_VALUE }
        val maxX = IntArray(count) { Int.MIN_VALUE }
        val minY = IntArray(count) { Int.MAX_VALUE }
        val maxY = IntArray(count) { Int.MIN_VALUE }
        val touches = BooleanArray(count)
        for (i in 0 until n) {
            val c = id[find(parent, i)]
            comp[i] = c
            area[c] += (x1.a[i] - x0.a[i]).toLong()
            if (x0.a[i] < minX[c]) minX[c] = x0.a[i]
            if (x1.a[i] > maxX[c]) maxX[c] = x1.a[i]
            if (row.a[i] < minY[c]) minY[c] = row.a[i]
            if (row.a[i] > maxY[c]) maxY[c] = row.a[i]
            if (x0.a[i] == 0 || x1.a[i] == w || row.a[i] == 0 || row.a[i] == h - 1) touches[c] = true
        }
        val (order, first) = bucket(comp, n, count)
        return Rest(count, area, minX, maxX, minY, maxY, touches, x0.a, x1.a, row.a, order, first)
    }

    // ---- rectangles --------------------------------------------------------------------

    /**
     * Turns the shade plane into disjoint rectangles: runs that sit exactly on top of
     * the same run in the row above are merged into it. A flat gutter is one rectangle;
     * a gutter with a balloon in it is a few hundred, one per row either side of the curve.
     * If a very busy page would need too many, rows are merged in groups, always keeping
     * only what every row of the group agrees on.
     */
    internal fun extractRects(shade: BitPlane, step: Int, frameW: Int, frameH: Int, gaps: Int): GapResult {
        var group = 1
        while (true) {
            val plane = if (group == 1) shade else groupRows(shade, group)
            val out = IntList(1024)
            var count = 0
            var overflow = false
            // Rectangles that were open in the previous row, as indices (x0,y0,x1,y1 quadruples in `out`).
            val capRuns = plane.w / 2 + 2
            var prev = IntArray(capRuns)
            var cur = IntArray(capRuns)
            var prevN = 0
            for (y in 0 until plane.h) {
                var curN = 0
                var pi = 0
                plane.forEachRun(y) { a, b ->
                    // Rectangles are disjoint and sorted, and so are the runs: one pass matches them.
                    while (pi < prevN && out.a[prev[pi] * 4] < a) pi++
                    if (pi < prevN && out.a[prev[pi] * 4] == a && out.a[prev[pi] * 4 + 2] == b) {
                        val r = prev[pi]
                        out.a[r * 4 + 3] = y + 1
                        cur[curN++] = r
                    } else {
                        out.add(a); out.add(y); out.add(b); out.add(y + 1)
                        cur[curN++] = count++
                    }
                }
                val t = prev; prev = cur; cur = t
                prevN = curN
                if (count > MAX_RECTS) {
                    overflow = true
                    break
                }
            }
            if (overflow) {
                // Too ragged to describe: coarsen, and if even that is too much, say nothing — a
                // page shaded in its top rows only, and white below, would look finished and be wrong.
                if (group >= 16) return GapResult(IntArray(0), 0, 0L, true, 0)
                group *= 2
                continue
            }
            return finish(out, count, group, step, frameW, frameH, gaps)
        }
    }

    private fun groupRows(src: BitPlane, g: Int): BitPlane {
        val outH = (src.h + g - 1) / g
        val dst = BitPlane(src.w, outH)
        for (gy in 0 until outH) {
            val base = gy * dst.wpr
            for (k in 0 until dst.wpr) {
                var v = -1L
                for (r in 0 until g) {
                    val y = gy * g + r
                    v = v and if (y < src.h) src.bits[y * src.wpr + k] else 0L
                }
                dst.bits[base + k] = v
            }
        }
        return dst
    }

    private fun finish(out: IntList, count: Int, group: Int, step: Int, frameW: Int, frameH: Int, gaps: Int): GapResult {
        val rects = IntArray(count * 4)
        var shaded = 0L
        val rowScale = step * group
        for (i in 0 until count) {
            val x0 = min(frameW, out.a[i * 4] * step)
            val y0 = min(frameH, out.a[i * 4 + 1] * rowScale)
            val x1 = min(frameW, out.a[i * 4 + 2] * step)
            val y1 = min(frameH, out.a[i * 4 + 3] * rowScale)
            rects[i * 4] = x0
            rects[i * 4 + 1] = y0
            rects[i * 4 + 2] = x1
            rects[i * 4 + 3] = y1
            shaded += (x1 - x0).toLong() * (y1 - y0)
        }
        return GapResult(rects, count, shaded, true, gaps)
    }
}

/** A growable int array, so labelling allocates no boxed integers. */
internal class IntList(capacity: Int = 256) {
    var a = IntArray(capacity)
    var n = 0

    fun add(v: Int) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }
}
