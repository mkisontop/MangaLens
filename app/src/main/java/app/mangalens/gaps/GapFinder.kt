package app.mangalens.gaps

import kotlin.math.max
import kotlin.math.min

/** Thresholds of the gap finder. Fractions are of the frame's area or of the reading column's width. */
class GapParams(
    /**
     * A white region is a gutter, not a balloon, when it runs this share of the reading
     * column's width unbroken at some row. Balloons sit inside the white; the gutter is
     * what the art above and below it is cut from.
     */
    val wideRun: Float = 0.75f,

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
 *  3. A region is a gutter only if it runs most of the reading column's width at some row.
 *     A balloon is enclosed by its outline and never does.
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

        val (colL, colR) = readingColumn(planes)
        val colW = max(1, w - colL - colR)

        val sawArt = hasArt(planes, colL, colR, params)
        if (!sawArt && !artRecently) return GapResult(IntArray(0), 0, 0L, false, 0)

        // 2. Seal.
        val core = planes.paper.copy().erode(sealR, outside = true)

        // 3. Label the sealed paper and pick the gutters.
        val labels = label(core, connect8 = false)
        val wideMin = (params.wideRun * colW).toInt()
        val minArea = (params.minGapArea * area).toLong()
        val picked = ArrayList<Int>()
        for (c in 0 until labels.compCount) {
            if (labels.area[c] >= minArea && labels.maxRun[c] >= wideMin) picked.add(c)
        }
        if (picked.isEmpty()) return GapResult(IntArray(0), 0, 0L, sawArt, 0)

        val seed = BitPlane(w, h)
        for (c in picked) labels.paintComponent(c, seed)
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
            if (rest.touchesFrame[c]) continue
            val a = rest.area[c]
            val bw = rest.maxX[c] - rest.minX[c]
            val bh = rest.maxY[c] - rest.minY[c] + 1
            val fill = a.toFloat() / (bw.toFloat() * bh).coerceAtLeast(1f)
            var paperCount = 0L
            var inkCount = 0L
            var coreCount = 0L
            rest.forEachSegment(c) { y, x0, x1 ->
                paperCount += planes.paper.countRow(y, x0, x1)
                inkCount += planes.ink.countRow(y, x0, x1)
                coreCount += core.countRow(y, x0, x1)
            }
            when {
                inkCount == 0L && a <= speckMax -> roles[c] = ROLE_SPECK
                a >= artMin -> Unit
                // A solid rectangle with hardly any paper in it: a panel, or a black narration box.
                // (Not merely a full bounding box: a word of lettering fills its box too, with
                // the paper between its strokes.)
                fill >= 0.85f && a >= solidMin && paperCount <= 0.2f * a -> Unit
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
        var kept = picked.size
        if (!halo.isEmpty()) {
            val keepSeed = BitPlane(w, h)
            kept = 0
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
            if (kept < picked.size) gutter = keepSeed.dilate(sealR).and(planes.paper)
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

    // ---- the reading column ------------------------------------------------------------

    /**
     * Columns at either edge that are uniform from the top of the frame to the bottom — the
     * black bars a reader leaves around a narrow strip, or a site's white margin — are not
     * part of the strip. A gutter spans the strip, not the screen.
     */
    internal fun readingColumn(planes: Planes): Pair<Int, Int> {
        val w = planes.w
        val h = planes.h
        val wpr = planes.paper.wpr
        val allPaper = LongArray(wpr) { -1L }
        val allInk = LongArray(wpr) { -1L }
        var rows = 0
        var y = planes.validY0
        while (y < planes.validY1) {
            val base = y * wpr
            for (k in 0 until wpr) {
                allPaper[k] = allPaper[k] and planes.paper.bits[base + k]
                allInk[k] = allInk[k] and planes.ink.bits[base + k]
            }
            rows++
            y += 4
        }
        if (rows < 4) return 0 to 0
        fun uniform(x: Int): Boolean {
            val k = x ushr 6
            val bit = 1L shl (x and 63)
            return (allPaper[k] and bit) != 0L || (allInk[k] and bit) != 0L
        }
        val cap = (0.4f * w).toInt()
        var l = 0
        while (l < cap && uniform(l)) l++
        var r = 0
        while (r < cap && uniform(w - 1 - r)) r++
        if (w - l - r < 0.3f * w) return 0 to 0
        return l to r
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

    private fun label(plane: BitPlane, connect8: Boolean): Labels {
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
        for (i in 0 until n) {
            val c = id[find(parent, i)]
            comp[i] = c
            val len = x1.a[i] - x0.a[i]
            area[c] += len.toLong()
            if (len > maxRun[c]) maxRun[c] = len
        }
        val (order, first) = bucket(comp, n, count)
        return Labels(plane, count, area, maxRun, x0.a, x1.a, row.a, order, first)
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
