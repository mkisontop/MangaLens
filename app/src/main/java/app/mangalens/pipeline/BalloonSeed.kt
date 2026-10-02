package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import app.mangalens.ocr.Balloon
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The balloon around one line of lettering, found from the lettering
 * outward.
 *
 * Page-wide balloon detection looks for enclosed white paper and misses
 * whole families of balloons real pages are full of: a see-through balloon
 * whose paper lets the art show faintly through, a balloon breaking a
 * panel border whose paper runs straight into the gutter, a balloon the
 * page edge cuts off. The line inside one of those was erased only where
 * the model's box said, so the column its box missed stayed on the page,
 * and the English was set in that box, small, instead of in the balloon.
 *
 * Here the model has already said where the lettering is, so the search
 * starts from there, on a fine grid of the page around it: the paper
 * between the glyphs is the balloon's own paper, and the flood spreads
 * over everything near that tone — faint art under a see-through wash
 * included — until it meets ink. A flood that escapes through a gutter or
 * a break in the outline is cut at its narrowest: the balloon is a fat
 * blob, the gutter a thin strip, and only the fat part around the
 * lettering is kept. What is left must look like a balloon — mostly
 * walled by ink, blobby, and not much larger than its text needs — or
 * nothing is returned, and the lettering is erased where it stands.
 */
internal object BalloonSeed {

    /** Why a search gave up, for the page harness; null in the app. */
    @Volatile
    internal var trace: ((String) -> Unit)? = null

    private fun no(why: String): Balloon? {
        trace?.invoke(why)
        return null
    }

    /** Most grid cells the search covers; the cell size grows to stay under it. */
    private const val MAX_CELLS = 120_000

    /**
     * Search area round the lettering, plus [REACH_PX] pixels: across the
     * text, the larger of these shares of its long and short sides — a box
     * round one column of a four-column balloon still reaches the far
     * columns — and along it, a share of its long side.
     */
    private const val REACH_ACROSS = 0.55f
    private const val REACH_SHORT = 2.4f
    private const val REACH_ALONG = 0.4f
    private const val REACH_PX = 24

    /** Darkest pixel this far under the paper makes a cell ink: a wall the flood stops at. */
    private const val WALL_DROP = 90

    /** Mean this close to the paper is paper, faint art under a see-through wash included. */
    private const val TOLERANCE = 45

    /** Paper darker than this is no balloon paper this search trusts. */
    private const val MIN_PAPER = 120

    private const val MIN_SEEDS = 4

    /** Cells the ink is thickened by, in turn, when the flood seeps through screentone. */
    private val SEALS = intArrayOf(0, 1, 2)

    /** Neck radii tried, in pixels, when the flood escapes: widest gutter or outline gap cut. */
    private val NECK_PX = intArrayOf(5, 9, 14, 20)

    /** Cells past a wall within which more of the same region makes the wall a crack. */
    private const val CRACK = 4

    /** Share of the region's edge that must be ink (or the page edge) for it to be walled like a balloon. */
    private const val MIN_WALLED = 0.6f

    /** Region over its convex hull: balloons are nearly convex, bursts included. */
    private const val MIN_SOLIDITY = 0.72f

    /** Region over its bounding box: balloons are blobs, bursts included. */
    private const val MIN_BLOB = 0.42f

    /** The balloon against the lettering box: at most this much larger. */
    private const val MAX_GROWTH = 10f

    /** A flood up to this much larger than its lettering is taken as it is, without trying cuts. */
    private const val SNUG = 3.5f

    /** Share of the balloon that must lie outside the lettering box: its margin of paper. */
    private const val MIN_MARGIN = 0.3f

    /** Share of the lettering box that must lie inside the balloon. */
    private const val MIN_COVER = 0.7f

    /**
     * The balloon holding the lettering in [box], with its interior mask,
     * lettering included, at the search grid's resolution. Null when the
     * lettering sits on the art rather than in a balloon.
     */
    fun find(bitmap: Bitmap, box: Rect): Balloon? {
        if (box.width() < 4 || box.height() < 4) return null
        // Room for the balloon round the text: a column's balloon is a few
        // columns wider than it and not much taller; a line's, the reverse.
        val long = max(box.width(), box.height())
        val short = minOf(box.width(), box.height())
        val across = (max(long * REACH_ACROSS, short * REACH_SHORT)).toInt() + REACH_PX
        val along = (long * REACH_ALONG).toInt() + REACH_PX
        val tall = box.height() >= box.width()
        val rx = if (tall) across else along
        val ry = if (tall) along else across
        val roi = Rect(box.left - rx, box.top - ry, box.right + rx, box.bottom + ry)
        if (!roi.intersect(0, 0, bitmap.width, bitmap.height)) return null
        val cell = ceil(sqrt(roi.width().toDouble() * roi.height() / MAX_CELLS)).toInt().coerceAtLeast(1)
        val g = Grid.of(bitmap, roi, cell)
        val w = g.w
        val h = g.h

        // The paper between the glyphs, where the model says the lettering is.
        val bx0 = ((box.left - roi.left) / cell).coerceIn(0, w - 1)
        val by0 = ((box.top - roi.top) / cell).coerceIn(0, h - 1)
        val bx1 = ((box.right - 1 - roi.left) / cell).coerceIn(0, w - 1)
        val by1 = ((box.bottom - 1 - roi.top) / cell).coerceIn(0, h - 1)
        val tones = ArrayList<Int>()
        for (y in by0..by1) for (x in bx0..bx1) {
            val i = y * w + x
            if (g.lo[i] >= MIN_PAPER - WALL_DROP / 2) tones.add(g.mean[i])
        }
        if (tones.size < MIN_SEEDS) return no("no paper between the glyphs")
        tones.sort()
        val paper = tones[(tones.size * 4) / 5]
        if (paper < MIN_PAPER) return no("paper too dark ($paper)")

        val wall = BooleanArray(w * h) { g.lo[it] < paper - WALL_DROP }
        val open = BooleanArray(w * h) { !wall[it] && kotlin.math.abs(g.mean[it] - paper) <= TOLERANCE }
        val boxSeeds = ArrayList<Int>()
        for (y in by0..by1) for (x in bx0..bx1) if (open[y * w + x]) boxSeeds.add(y * w + x)
        if (boxSeeds.size < MIN_SEEDS) return no("too few seeds (${boxSeeds.size}) at paper $paper")

        // Which sides of the search area are the page's own edge: a balloon may run off those.
        val pageLeft = roi.left == 0
        val pageTop = roi.top == 0
        val pageRight = roi.right == bitmap.width
        val pageBottom = roi.bottom == bitmap.height
        fun escapes(i: Int): Boolean {
            val x = i % w
            val y = i / w
            return (x == 0 && !pageLeft) || (x == w - 1 && !pageRight) || (y == 0 && !pageTop) || (y == h - 1 && !pageBottom)
        }

        val boxCells = (bx1 - bx0 + 1) * (by1 - by0 + 1)
        var why = "no candidate"
        fun accept(region: BooleanArray): Shape? {
            val shape = Shape.of(region, w, h)
            if (shape.area == 0) return null
            if (region.indices.any { region[it] && escapes(it) }) {
                why = "escapes the search area"
                trace?.invoke("candidate of ${shape.area} cells: escapes")
                return null
            }
            val verdict = judge(shape, w, h, bx0, by0, bx1, by1, wall, pageLeft, pageTop, pageRight, pageBottom)
            if (verdict != null) {
                why = verdict
                trace?.invoke("candidate of ${shape.area} cells: $verdict")
            }
            return shape.takeIf { verdict == null }
        }

        // Screentone around a balloon is dots with paper between them, and
        // on a fine grid the flood can seep from gap to gap across a whole
        // toned panel. Sealed first by nothing, then by thickening the ink
        // a cell or two — the gaps between dots close, the balloon's own
        // rim is given back after.
        var best: Shape? = null
        for (seal in SEALS) {
            val sealedOpen = if (seal == 0) open else {
                val thick = dilate(wall, w, h, seal)
                BooleanArray(w * h) { open[it] && !thick[it] }
            }
            val seeds = boxSeeds.filter { sealedOpen[it] }
            if (seeds.size < MIN_SEEDS) continue
            var base = filled(flood(sealedOpen, w, h, seeds), w, h)
            if (seal > 0) base = filled(grow(base, open, w, h, seal), w, h)
            // Most balloons are the flood itself, walled and snug round their text.
            val whole = accept(base)
            best = whole?.takeIf { it.area <= boxCells * SNUG }
            if (best == null) {
                // Cut from the widest neck down: the first cut that passes
                // for a balloon is the smallest that does.
                val depth = depth(base, w, h)
                for (px in NECK_PX.reversed()) {
                    val region = cut(base, depth, w, h, seeds, (px / cell).coerceAtLeast(1)) ?: continue
                    best = accept(region) ?: continue
                    break
                }
                if (best == null) best = whole
            }
            if (best != null) break
        }
        val shape = best ?: return no("$why (paper $paper)")
        val bw = shape.maxX - shape.minX + 1
        val bh = shape.maxY - shape.minY + 1
        val mask = BooleanArray(bw * bh) { j -> shape.region[(shape.minY + j / bw) * w + shape.minX + j % bw] }
        val partial = (shape.minX == 0 && pageLeft) || (shape.minY == 0 && pageTop) ||
            (shape.maxX == w - 1 && pageRight) || (shape.maxY == h - 1 && pageBottom)
        return Balloon(
            Rect(
                roi.left + shape.minX * cell,
                roi.top + shape.minY * cell,
                (roi.left + (shape.maxX + 1) * cell).coerceAtMost(bitmap.width),
                (roi.top + (shape.maxY + 1) * cell).coerceAtMost(bitmap.height),
            ),
            bw, bh, mask, inverted = false, partial = partial,
        )
    }

    /** A candidate region and its bounds, in grid cells. */
    private class Shape(val region: BooleanArray, val area: Int, val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {
        companion object {
            fun of(region: BooleanArray, w: Int, h: Int): Shape {
                var area = 0
                var minX = w
                var minY = h
                var maxX = -1
                var maxY = -1
                for (i in region.indices) {
                    if (!region[i]) continue
                    area++
                    val x = i % w
                    val y = i / w
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                return Shape(region, area, minX, minY, maxX, maxY)
            }
        }
    }

    /**
     * Why [s] is not a balloon round the lettering box (cells [bx0]..[bx1],
     * [by0]..[by1]), or null when it is one: not far larger than its text,
     * covering the text, with a margin of paper round it — text with a
     * white outline drawn on the art has only its own outline — blobby,
     * and walled by ink nearly all the way round.
     */
    private fun judge(
        s: Shape,
        gridW: Int,
        gridH: Int,
        bx0: Int,
        by0: Int,
        bx1: Int,
        by1: Int,
        wall: BooleanArray,
        pageLeft: Boolean,
        pageTop: Boolean,
        pageRight: Boolean,
        pageBottom: Boolean,
    ): String? {
        val boxCells = (bx1 - bx0 + 1) * (by1 - by0 + 1)
        if (s.area > boxCells * MAX_GROWTH) return "too big: ${s.area / boxCells.coerceAtLeast(1)}x the lettering"
        var covered = 0
        for (y in by0..by1) for (x in bx0..bx1) if (s.region[y * gridW + x]) covered++
        if (covered < boxCells * MIN_COVER) return "covers ${covered * 100 / boxCells.coerceAtLeast(1)}% of the lettering"
        if (s.area - covered < s.area * MIN_MARGIN) return "no paper round the lettering"
        val bw = s.maxX - s.minX + 1
        val bh = s.maxY - s.minY + 1
        if (s.area < bw * bh * MIN_BLOB) return "not a blob: ${s.area * 100 / (bw * bh)}% of its box"
        val solid = solidity(s, gridW)
        if (solid < MIN_SOLIDITY) return "ragged: ${(solid * 100).toInt()}% of its hull"
        val walled = walledShare(s.region, wall, gridW, gridH, pageLeft, pageTop, pageRight, pageBottom)
        if (walled < MIN_WALLED) return "walled only ${(walled * 100).toInt()}%"
        return null
    }

    /**
     * The region's area over its convex hull's. A balloon is nearly
     * convex — an oval, a cloud, even a burst of spikes — where paper that
     * runs between speed lines or round a face is ragged all over.
     */
    private fun solidity(s: Shape, w: Int): Float {
        // Each row's outermost cells, corners included: enough for the hull.
        val pts = ArrayList<Long>()
        for (y in s.minY..s.maxY) {
            var first = -1
            var last = -1
            for (x in s.minX..s.maxX) if (s.region[y * w + x]) {
                if (first < 0) first = x
                last = x
            }
            if (first < 0) continue
            pts.add(pack(first, y)); pts.add(pack(first, y + 1))
            pts.add(pack(last + 1, y)); pts.add(pack(last + 1, y + 1))
        }
        if (pts.size < 3) return 0f
        pts.sortWith(compareBy<Long>({ it shr 32 }, { it and 0xFFFFFFFFL }))
        val hull = LongArray(pts.size * 2)
        var k = 0
        fun cross(o: Long, a: Long, b: Long): Long =
            (ux(a) - ux(o)) * (uy(b) - uy(o)) - (uy(a) - uy(o)) * (ux(b) - ux(o))
        for (p in pts) {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], p) <= 0) k--
            hull[k++] = p
        }
        val lower = k + 1
        for (i in pts.size - 2 downTo 0) {
            val p = pts[i]
            while (k >= lower && cross(hull[k - 2], hull[k - 1], p) <= 0) k--
            hull[k++] = p
        }
        var twice = 0L
        for (i in 0 until k - 1) twice += ux(hull[i]) * uy(hull[i + 1]) - ux(hull[i + 1]) * uy(hull[i])
        val hullArea = kotlin.math.abs(twice) / 2f
        return if (hullArea <= 0f) 0f else s.area / hullArea
    }

    private fun pack(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
    private fun ux(p: Long): Long = p shr 32
    private fun uy(p: Long): Long = p and 0xFFFFFFFFL

    /** [base] cut at neck radius [r] cells: its fat core round the lettering, grown back by [r]. Null when no core is left. */
    private fun cut(base: BooleanArray, depth: IntArray, w: Int, h: Int, seeds: List<Int>, r: Int): BooleanArray? {
        val core = BooleanArray(w * h) { base[it] && depth[it] > r }
        // Seeds sit between glyphs, often shallower than the core: start from the core cell nearest them.
        val start = seeds.firstOrNull { core[it] } ?: nearestCore(core, w, h, seeds) ?: return null
        val piece = flood(core, w, h, listOf(start))
        return filled(grow(piece, base, w, h, r), w, h)
    }

    /** Per-cell darkest pixel and mean luminance over [roi], in cells of [cell] pixels. */
    private class Grid(val w: Int, val h: Int, val lo: IntArray, val mean: IntArray) {
        companion object {
            fun of(bitmap: Bitmap, roi: Rect, cell: Int): Grid {
                val w = (roi.width() + cell - 1) / cell
                val h = (roi.height() + cell - 1) / cell
                val lo = IntArray(w * h) { 255 }
                val sum = IntArray(w * h)
                val cnt = IntArray(w * h)
                val row = IntArray(roi.width())
                for (y in roi.top until roi.bottom) {
                    bitmap.getPixels(row, 0, row.size, roi.left, y, row.size, 1)
                    val base = ((y - roi.top) / cell) * w
                    for (x in row.indices) {
                        val p = row[x]
                        val lum = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                        val i = base + x / cell
                        sum[i] += lum
                        cnt[i]++
                        if (lum < lo[i]) lo[i] = lum
                    }
                }
                val mean = IntArray(w * h) { if (cnt[it] > 0) sum[it] / cnt[it] else 255 }
                return Grid(w, h, lo, mean)
            }
        }
    }

    /** Cells reachable from [seeds] through [open] ones, four-connected. */
    private fun flood(open: BooleanArray, w: Int, h: Int, seeds: List<Int>): BooleanArray {
        val out = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        for (s in seeds) if (!out[s]) {
            out[s] = true
            stack[sp++] = s
        }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w
            val y = i / w
            if (x > 0 && open[i - 1] && !out[i - 1]) { out[i - 1] = true; stack[sp++] = i - 1 }
            if (x < w - 1 && open[i + 1] && !out[i + 1]) { out[i + 1] = true; stack[sp++] = i + 1 }
            if (y > 0 && open[i - w] && !out[i - w]) { out[i - w] = true; stack[sp++] = i - w }
            if (y < h - 1 && open[i + w] && !out[i + w]) { out[i + w] = true; stack[sp++] = i + w }
        }
        return out
    }

    /** [region] with everything it encloses — the lettering — filled in. */
    private fun filled(region: BooleanArray, w: Int, h: Int): BooleanArray {
        val outside = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        fun push(i: Int) {
            if (!region[i] && !outside[i]) {
                outside[i] = true
                stack[sp++] = i
            }
        }
        for (x in 0 until w) {
            push(x)
            push((h - 1) * w + x)
        }
        for (y in 0 until h) {
            push(y * w)
            push(y * w + w - 1)
        }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        return BooleanArray(w * h) { !outside[it] }
    }

    /** [mask] thickened by [r] cells, four-connected steps. */
    private fun dilate(mask: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        var cur = mask
        repeat(r) {
            val next = cur.copyOf()
            for (i in cur.indices) {
                if (!cur[i]) continue
                val x = i % w
                val y = i / w
                if (x > 0) next[i - 1] = true
                if (x < w - 1) next[i + 1] = true
                if (y > 0) next[i - w] = true
                if (y < h - 1) next[i + w] = true
            }
            cur = next
        }
        return cur
    }

    /** Chamfer distance, in cells, from each region cell to the nearest cell outside it. */
    private fun depth(region: BooleanArray, w: Int, h: Int): IntArray {
        val inf = w + h
        val d = IntArray(w * h) { if (region[it]) inf else 0 }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (d[i] == 0) continue
            var v = d[i]
            v = if (x > 0) minOf(v, d[i - 1] + 1) else 0
            v = if (y > 0) minOf(v, d[i - w] + 1) else 0
            d[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            if (d[i] == 0) continue
            var v = d[i]
            v = if (x < w - 1) minOf(v, d[i + 1] + 1) else 0
            v = if (y < h - 1) minOf(v, d[i + w] + 1) else 0
            d[i] = v
        }
        return d
    }

    private fun nearestCore(core: BooleanArray, w: Int, h: Int, seeds: List<Int>): Int? {
        // Breadth-first from the seeds until a core cell turns up.
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        for (s in seeds) if (!seen[s]) {
            seen[s] = true
            queue[tail++] = s
        }
        while (head < tail) {
            val i = queue[head++]
            if (core[i]) return i
            val x = i % w
            val y = i / w
            if (x > 0 && !seen[i - 1]) { seen[i - 1] = true; queue[tail++] = i - 1 }
            if (x < w - 1 && !seen[i + 1]) { seen[i + 1] = true; queue[tail++] = i + 1 }
            if (y > 0 && !seen[i - w]) { seen[i - w] = true; queue[tail++] = i - w }
            if (y < h - 1 && !seen[i + w]) { seen[i + w] = true; queue[tail++] = i + w }
        }
        return null
    }

    /** [piece] grown by up to [r] cells, staying inside [within]. */
    private fun grow(piece: BooleanArray, within: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val dist = IntArray(w * h) { -1 }
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        for (i in piece.indices) if (piece[i]) {
            dist[i] = 0
            queue[tail++] = i
        }
        while (head < tail) {
            val i = queue[head++]
            if (dist[i] >= r) continue
            val x = i % w
            val y = i / w
            fun visit(j: Int) {
                if (within[j] && dist[j] < 0) {
                    dist[j] = dist[i] + 1
                    queue[tail++] = j
                }
            }
            if (x > 0) visit(i - 1)
            if (x < w - 1) visit(i + 1)
            if (y > 0) visit(i - w)
            if (y < h - 1) visit(i + w)
        }
        return BooleanArray(w * h) { dist[it] >= 0 }
    }

    /**
     * Share of the region's edge that is walled: the cell beyond it ink —
     * with no more of the region just past the ink — or the page's own
     * edge. A balloon is walled by its outline nearly all the way round,
     * with the art outside it. Paper that runs between speed lines has
     * itself on both sides of every line: those lines are cracks in one
     * sheet of paper, not a wall round a balloon.
     */
    private fun walledShare(
        region: BooleanArray,
        wall: BooleanArray,
        w: Int,
        h: Int,
        pageLeft: Boolean,
        pageTop: Boolean,
        pageRight: Boolean,
        pageBottom: Boolean,
    ): Float {
        var edges = 0
        var walled = 0
        for (i in region.indices) {
            if (!region[i]) continue
            val x = i % w
            val y = i / w
            for (d in 0 until 4) {
                val dx = DX[d]
                val dy = DY[d]
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until h) {
                    edges++
                    val pageEdge = (nx < 0 && pageLeft) || (nx >= w && pageRight) || (ny < 0 && pageTop) || (ny >= h && pageBottom)
                    if (pageEdge) walled++
                    continue
                }
                val j = ny * w + nx
                if (region[j]) continue
                edges++
                if (!wall[j]) continue
                // Past the ink: more of this same region close by is a crack.
                var crack = false
                for (k in 2..CRACK) {
                    val cx = x + dx * k
                    val cy = y + dy * k
                    if (cx !in 0 until w || cy !in 0 until h) break
                    if (region[cy * w + cx]) {
                        crack = true
                        break
                    }
                }
                if (!crack) walled++
            }
        }
        return if (edges == 0) 0f else walled.toFloat() / edges
    }

    private val DX = intArrayOf(-1, 1, 0, 0)
    private val DY = intArrayOf(0, 0, -1, 1)
}
