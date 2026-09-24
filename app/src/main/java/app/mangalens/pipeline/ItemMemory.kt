package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import app.mangalens.translate.PageItem
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import kotlin.math.abs

/**
 * Lettering already translated, found again wherever it has scrolled to.
 *
 * A webtoon is read as a run of short scrolls, and every stop shows mostly
 * what the last stop showed, moved up the screen. The page cache cannot
 * help — the frame is new — so each stop used to wait for the model to read
 * balloons it had translated seconds earlier. Instead, every translated item
 * keeps a small fingerprint of its own pixels, and a new frame is searched
 * for it along the scroll axis: whatever is found is painted at once, from
 * memory, before any request goes out, and only the newly revealed lettering
 * waits for the model.
 *
 * Identity is the lettering itself, never a position. Two balloons drawn
 * close together, or a new balloon that scrolls into the exact spot an old
 * one held, are different lettering and are never mistaken for each other
 * — the failure that sank an earlier position-keyed memory. Three gates
 * stand between a fingerprint and a repaint: it must be distinctive (blank
 * paper matches anywhere), its best position on a quarter-scale search of
 * the scroll axis must beat every other position clearly (so a repeated
 * line is never painted onto its twin), and the ink at that position must
 * then match stroke for stroke at close to full resolution — at quarter
 * scale two lines differing in one character can look alike, and at full
 * detail they do not.
 */
internal class ItemMemory {

    private class Remembered(
        val item: PageItem,
        /** Fingerprint origin and size, in quarter-scale cells. */
        val cx: Int,
        val cy: Int,
        val cw: Int,
        val ch: Int,
        val cells: IntArray,
        val pageW: Int,
        val pageH: Int,
        val detail: Detail,
    ) {
        /** Recalls in a row that did not find this lettering on screen. */
        var missed = 0
    }

    /**
     * The lettering's ink at [f]-times reduction — near full resolution —
     * as a [w] by [h] mask: the strokes themselves, independent of how
     * bright the capture was.
     */
    private class Detail(val f: Int, val w: Int, val h: Int, val ink: BooleanArray, val darkInk: Boolean) {
        val inkCount = ink.count { it }
    }

    private val items = ArrayDeque<Remembered>()

    /** The frame recall last looked at, and the memories it found there: the same lettering, now re-read. */
    private var recalledOn: WeakReference<Bitmap>? = null
    private var recalledFrom: List<Remembered> = emptyList()

    /** The last frame's reduced greys, shared by recall and remember on the same frame. */
    private var grayOf: WeakReference<Bitmap>? = null
    private var grayCache: Gray? = null

    /**
     * Remembers [found], as read off [bitmap], for later frames. The
     * memories recall found on this same frame are that lettering seen
     * again, and give way to the new ones: one memory per piece of
     * lettering, never one per stop it was read at.
     */
    @Synchronized
    fun remember(bitmap: Bitmap, found: List<PageItem>) {
        if (found.isEmpty()) return
        val gray = grayFor(bitmap)
        if (recalledOn?.get() === bitmap && recalledFrom.isNotEmpty()) {
            val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Remembered, Boolean>())
            seen.addAll(recalledFrom)
            items.removeAll { it in seen }
            recalledFrom = emptyList()
        }
        for (item in found) {
            val r = fingerprint(gray, bitmap, item) ?: continue
            items.removeAll { overlaps(it, r) }
            items.addLast(r)
        }
        while (items.size > CAPACITY) items.removeFirst()
    }

    /** How many pieces of lettering are remembered. */
    internal val size: Int @Synchronized get() = items.size

    @Synchronized
    fun clear() {
        items.clear()
        recalledFrom = emptyList()
        recalledOn = null
        grayOf = null
        grayCache = null
        phasesOf = null
        phases = null
    }

    private fun grayFor(bitmap: Bitmap): Gray {
        grayCache?.takeIf { grayOf?.get() === bitmap }?.let { return it }
        return Gray.of(bitmap).also {
            grayCache = it
            grayOf = WeakReference(bitmap)
        }
    }

    private var phasesOf: WeakReference<Bitmap>? = null
    private var phases: Array<Gray?>? = null

    /**
     * [bitmap] reduced with its cells starting [phase] rows down. A page
     * scrolls by any number of rows, and a fingerprint's cells line up with
     * the frame's only at one of the [SCALE] phases: off by a row or two, a
     * cell averages other strokes and the line matches nowhere.
     */
    private fun grayAt(bitmap: Bitmap, phase: Int): Gray {
        if (phase == 0) return grayFor(bitmap)
        val cache = phases?.takeIf { phasesOf?.get() === bitmap }
            ?: arrayOfNulls<Gray>(SCALE).also {
                phases = it
                phasesOf = WeakReference(bitmap)
            }
        return cache[phase] ?: Gray.of(bitmap, phase).also { cache[phase] = it }
    }

    /**
     * The remembered items visible in [bitmap], moved to where they now sit,
     * in the order they were read — a balloon the model gave as two pieces
     * comes back top piece first, as it was lettered. Only vertical travel
     * is searched: that is how a page scrolls, and a page that zoomed or
     * turned is simply read afresh.
     *
     * Where two memories land on the same lettering, the newer one wins;
     * boxes that merely graze, as the pieces of one split balloon do, are
     * both kept.
     */
    @Synchronized
    fun recall(bitmap: Bitmap, ignoreTop: Int = 0, ignoreBottom: Int = 0): List<PageItem> {
        if (items.isEmpty()) return emptyList()
        val found = ArrayList<Pair<Int, PageItem>>()
        val from = ArrayList<Remembered>()
        // Lines on one screen moved by one scroll share a phase: the one
        // that placed the last line is tried first.
        var phase = 0
        for (i in items.indices.reversed()) {
            val r = items[i]
            val hit = r.pageW == bitmap.width && r.pageH == bitmap.height && runCatching { run {
                var at: Pair<Int, Int>? = null
                for (k in 0 until SCALE) {
                    val ph = (phase + k) % SCALE
                    val cy = find(grayAt(bitmap, ph), r) ?: continue
                    at = verify(bitmap, r, (cy - r.cy) * SCALE + ph) ?: continue
                    phase = ph
                    break
                }
                val (dx, dy) = at ?: return@run false
                val box = Rect(r.item.box).apply { offset(dx, dy) }
                // Lettering reaching into a band is lettered as a read letters
                // it; only lettering wholly inside one is left alone.
                if (box.bottom <= ignoreTop || box.top >= bitmap.height - ignoreBottom) return@run true
                if (box.top < 0 || box.bottom > bitmap.height) return@run true
                if (found.none { (_, it) -> sameSpot(it.box, box) }) found.add(i to r.item.copy(box = box))
                true
            } }.getOrDefault(false)
            if (hit) {
                r.missed = 0
                from.add(r)
            } else {
                r.missed++
            }
        }
        // Lettering not on screen for several stops in a row has scrolled
        // well away; searching every frame for it only delays the repaint.
        items.removeAll { it.missed >= FORGET_AFTER }
        recalledOn = WeakReference(bitmap)
        recalledFrom = from
        return found.sortedBy { it.first }.map { it.second }
    }

    /**
     * Lines of [last], the frame read before this one, that the edge of
     * [bitmap] now cuts. Lettering only partly on screen cannot be searched
     * for, but the page moved [dy] rows since [last] (a measured scroll), so
     * each such line is looked for there alone, and taken when the part on
     * screen — at least [minShare] of it — reproduces its strokes, as
     * [recall] demands of a whole line. A line
     * something else now covers, a toolbar, does not match and is left out.
     * Returned at the part of their new boxes still on screen: the reader
     * saw them lettered, and they scroll away lettered.
     */
    @Synchronized
    fun recallCut(
        bitmap: Bitmap,
        last: List<PageItem>,
        dy: Int,
        ignoreTop: Int = 0,
        ignoreBottom: Int = 0,
        minShare: Float = CUT_MIN_SHARE,
    ): List<PageItem> {
        val out = ArrayList<PageItem>()
        for (r in items) {
            if (r.pageW != bitmap.width || r.pageH != bitmap.height) continue
            if (last.none { it === r.item }) continue
            val box = Rect(r.item.box).apply { offset(0, dy) }
            if (box.top >= 0 && box.bottom <= bitmap.height) continue
            val shown = minOf(box.bottom, bitmap.height) - maxOf(box.top, 0)
            if (shown < box.height() * minShare) continue
            // Not one wholly inside a band, which a read would not letter either.
            if (box.bottom <= ignoreTop || box.top >= bitmap.height - ignoreBottom) continue
            val at = runCatching { verifyCut(bitmap, r, dy) }.getOrNull() ?: continue
            // Lettered like any line on screen: on the part of it still there.
            val moved = Rect(r.item.box).apply {
                offset(at.first, at.second)
                if (!intersect(0, 0, bitmap.width, bitmap.height)) return@apply
            }
            if (moved.height() < 4) continue
            if (out.none { sameSpot(it.box, moved) }) out.add(r.item.copy(box = moved))
        }
        return out
    }

    /**
     * [verify] for lettering the frame's edge cuts: its detail's rows that
     * are on screen, at the rows the scroll put them on (give or take a
     * cell), must reproduce its strokes line and glyph alike.
     */
    private fun verifyCut(bitmap: Bitmap, r: Remembered, dy: Int): Pair<Int, Int>? {
        val d = r.detail
        val f = d.f
        val box = r.item.box
        val reachX = 2
        val xL = minOf(reachX, box.left / f)
        val xR = minOf(reachX, (bitmap.width - box.left - d.w * f) / f)
        if (xL < 0 || xR < 0) return null
        val left = box.left - xL * f
        val gw = d.w + xL + xR
        val minRows = maxOf(MIN_CELLS, (GLYPH_PX / f).coerceAtLeast(4))
        var best = 0f
        var bestAt: Pair<Int, Int>? = null
        for (sy in -1..1) {
            val top = box.top + dy + sy * f
            // The detail's rows lying wholly on screen.
            val y0 = if (top < 0) (-top + f - 1) / f else 0
            val y1 = minOf(d.h, (bitmap.height - top) / f)
            if (y1 - y0 < minRows) continue
            var dInk = 0
            for (y in y0 until y1) for (x in 0 until d.w) if (d.ink[y * d.w + x]) dInk++
            if (dInk == 0) continue
            val g = sample(bitmap, left, top + y0 * f, gw, y1 - y0, f)
            val t = threshold(g) ?: continue
            val ink = BooleanArray(g.size) { if (d.darkInk) g[it] < t else g[it] > t }
            for (sx in 0..xL + xR) {
                var inter = 0
                var union = 0
                var count = 0
                for (y in y0 until y1) {
                    val gr = (y - y0) * gw + sx
                    val dr = y * d.w
                    for (x in 0 until d.w) {
                        val a = d.ink[dr + x]
                        val b = ink[gr + x]
                        if (b) count++
                        if (a && b) inter++
                        if (a || b) union++
                    }
                }
                if (union == 0) continue
                val ratio = count.toFloat() / dInk
                if (ratio < 0.8f || ratio > 1.25f) continue
                val j = inter.toFloat() / union
                if (j > best && everyGlyphMatches(d, ink, gw, sx, -y0, y0, y1)) {
                    best = j
                    bestAt = Pair((sx - xL) * f, dy + sy * f)
                }
            }
        }
        return bestAt?.takeIf { best >= MATCH_JACCARD }
    }

    /** Most of the smaller box lies in the larger: one piece of lettering, not neighbours. */
    private fun sameSpot(a: Rect, b: Rect): Boolean {
        val r = Rect()
        if (!r.setIntersect(a, b)) return false
        val smaller = minOf(a.width().toLong() * a.height(), b.width().toLong() * b.height()).coerceAtLeast(1L)
        return r.width().toLong() * r.height() * 2 > smaller
    }

    private fun overlaps(a: Remembered, b: Remembered): Boolean =
        a.item.src == b.item.src && Rect.intersects(a.item.box, b.item.box)

    private fun fingerprint(gray: Gray, bitmap: Bitmap, item: PageItem): Remembered? {
        val box = item.box
        val cx = (box.left / SCALE).coerceIn(0, gray.w - 1)
        val cy = (box.top / SCALE).coerceIn(0, gray.h - 1)
        val cw = ((box.right + SCALE - 1) / SCALE).coerceAtMost(gray.w) - cx
        val ch = ((box.bottom + SCALE - 1) / SCALE).coerceAtMost(gray.h) - cy
        if (cw < MIN_CELLS || ch < MIN_CELLS) return null
        val cells = IntArray(cw * ch)
        var sum = 0L
        for (y in 0 until ch) {
            System.arraycopy(gray.px, (cy + y) * gray.w + cx, cells, y * cw, cw)
        }
        for (v in cells) sum += v
        val mean = sum.toDouble() / cells.size
        var dev = 0.0
        for (v in cells) dev += abs(v - mean)
        // Too flat to be told apart from anywhere else.
        if (dev / cells.size < MIN_CONTRAST) return null
        val detail = detailOf(bitmap, box) ?: return null
        return Remembered(item, cx, cy, cw, ch, cells, bitmap.width, bitmap.height, detail)
    }

    /** The ink inside [box], or null when it holds too little contrast to be lettering. */
    private fun detailOf(bitmap: Bitmap, box: Rect): Detail? {
        val area = box.width().toLong() * box.height()
        var f = DETAIL_SCALE
        while (area / (f.toLong() * f) > DETAIL_MAX_CELLS) f++
        val w = box.width() / f
        val h = box.height() / f
        if (w < MIN_CELLS || h < MIN_CELLS) return null
        if (box.left < 0 || box.top < 0 || box.left + w * f > bitmap.width || box.top + h * f > bitmap.height) return null
        val g = sample(bitmap, box.left, box.top, w, h, f)
        val t = threshold(g) ?: return null
        var above = 0
        for (v in g) if (v > t) above++
        // Lettering is the minority: dark strokes on light paper, or light
        // strokes on a dark box.
        val darkInk = above * 2 >= g.size
        return Detail(f, w, h, BooleanArray(g.size) { if (darkInk) g[it] < t else g[it] > t }, darkInk)
    }

    /**
     * The full-detail offset at which [r]'s ink sits, searched around the
     * coarse vertical offset [coarse], or null when no offset reproduces
     * its strokes closely enough to be the same lettering.
     */
    private fun verify(bitmap: Bitmap, r: Remembered, coarse: Int): Pair<Int, Int>? {
        val d = r.detail
        val f = d.f
        val box = r.item.box
        val reachX = 2
        val reachY = (SCALE * 2 + f - 1) / f
        // The search reaches a little either way of where the coarse pass
        // put the line, less where the screen ends: lettering running to
        // the edge of the screen — a full-width caption — is still checked.
        val y0 = box.top + coarse
        val xL = minOf(reachX, box.left / f)
        val xR = minOf(reachX, (bitmap.width - box.left - d.w * f) / f)
        val yT = minOf(reachY, y0 / f)
        val yB = minOf(reachY, (bitmap.height - y0 - d.h * f) / f)
        if (xL < 0 || xR < 0 || yT < 0 || yB < 0) return null
        val left = box.left - xL * f
        val top = y0 - yT * f
        val gw = d.w + xL + xR
        val gh = d.h + yT + yB
        val g = sample(bitmap, left, top, gw, gh, f)
        val t = threshold(g) ?: return null
        val ink = BooleanArray(g.size) { if (d.darkInk) g[it] < t else g[it] > t }
        var best = 0f
        var bestX = 0
        var bestY = 0
        for (sy in 0..yT + yB) {
            for (sx in 0..xL + xR) {
                var inter = 0
                var union = 0
                var count = 0
                for (y in 0 until d.h) {
                    val gr = (y + sy) * gw + sx
                    val dr = y * d.w
                    for (x in 0 until d.w) {
                        val a = d.ink[dr + x]
                        val b = ink[gr + x]
                        if (b) count++
                        if (a && b) inter++
                        if (a || b) union++
                    }
                }
                if (union == 0) continue
                val ratio = count.toFloat() / d.inkCount.coerceAtLeast(1)
                if (ratio < 0.8f || ratio > 1.25f) continue
                val j = inter.toFloat() / union
                if (j > best) {
                    best = j
                    bestX = sx
                    bestY = sy
                }
            }
        }
        if (best < MATCH_JACCARD) return null
        if (!everyGlyphMatches(d, ink, gw, bestX, bestY)) return null
        return Pair((bestX - xL) * f, coarse + (bestY - yT) * f)
    }

    /**
     * A whole-line overlap forgives a single changed character — six of
     * seven glyphs alike still overlap well — so the lettering is also
     * checked tile by tile, each tile about a glyph across: every tile with
     * ink in it must match on its own.
     */
    private fun everyGlyphMatches(
        d: Detail,
        ink: BooleanArray,
        gw: Int,
        sx: Int,
        sy: Int,
        from: Int = 0,
        to: Int = d.h,
    ): Boolean {
        val tile = (GLYPH_PX / d.f).coerceAtLeast(4)
        var ty = from
        while (ty < to) {
            var tx = 0
            while (tx < d.w) {
                var inter = 0
                var union = 0
                var cells = 0
                for (y in ty until minOf(ty + tile, to)) {
                    val gr = (y + sy) * gw + sx
                    val dr = y * d.w
                    for (x in tx until minOf(tx + tile, d.w)) {
                        val a = d.ink[dr + x]
                        val b = ink[gr + x]
                        if (a && b) inter++
                        if (a || b) union++
                        cells++
                    }
                }
                if (union * 100 >= cells * TILE_MIN_INK_PCT && inter.toFloat() / union < TILE_JACCARD) return false
                tx += tile
            }
            ty += tile
        }
        return true
    }

    /** Grey levels of the [w] x [h] cells of [f] x [f] pixels starting at ([left], [top]). */
    private fun sample(bitmap: Bitmap, left: Int, top: Int, w: Int, h: Int, f: Int): IntArray {
        val out = IntArray(w * h)
        val row = IntArray(w * f * f)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w * f, left, top + y * f, w * f, f)
            for (x in 0 until w) {
                var s = 0
                for (dy in 0 until f) {
                    val base = dy * w * f + x * f
                    for (dx in 0 until f) {
                        val p = row[base + dx]
                        s += ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    }
                }
                out[y * w + x] = s / (f * f)
            }
        }
        return out
    }

    /** Midway between paper and ink, or null when the two are too alike to tell apart. */
    private fun threshold(g: IntArray): Int? {
        val hist = IntArray(256)
        for (v in g) hist[v.coerceIn(0, 255)]++
        fun pct(p: Int): Int {
            val target = g.size.toLong() * p / 100
            var acc = 0L
            for (i in 0..255) {
                acc += hist[i]
                if (acc > target) return i
            }
            return 255
        }
        val lo = pct(5)
        val hi = pct(95)
        if (hi - lo < MIN_INK_CONTRAST) return null
        return (lo + hi) / 2
    }

    /**
     * The quarter-scale row where [r]'s fingerprint now sits in [gray], or
     * null when it is nowhere, or in two places at once.
     */
    private fun find(gray: Gray, r: Remembered): Int? {
        if (r.cx + r.cw > gray.w || r.ch > gray.h) return null
        val limit = r.cells.size.toLong() * MATCH_MEAN
        // Positions far worse than a match stop being summed early; they
        // only need to be known as "not it".
        val cap = limit * 4
        val rows = gray.h - r.ch + 1
        val sads = LongArray(rows)
        for (y in 0 until rows) {
            var sad = 0L
            var row = 0
            while (row < r.ch && sad < cap) {
                val g = (y + row) * gray.w + r.cx
                val c = row * r.cw
                for (x in 0 until r.cw) sad += abs(gray.px[g + x] - r.cells[c + x])
                row++
            }
            sads[y] = sad
        }
        var bestY = 0
        for (y in 1 until rows) if (sads[y] < sads[bestY]) bestY = y
        val best = sads[bestY]
        if (best > limit) return null
        // A repeated line — the same "…" twice — must not be painted on its
        // twin: the best place has to beat every other place clearly.
        var second = Long.MAX_VALUE
        for (y in 0 until rows) {
            if (abs(y - bestY) > r.ch / 2 && sads[y] < second) second = sads[y]
        }
        if (second != Long.MAX_VALUE && second < best * 2 + r.cells.size * 2) return null
        return bestY
    }

    /** Quarter-scale grey image of a frame, its cells starting [phase] rows down. */
    private class Gray(val w: Int, val h: Int, val px: IntArray) {
        companion object {
            fun of(bitmap: Bitmap, phase: Int = 0): Gray {
                val w = bitmap.width / SCALE
                val h = (bitmap.height - phase) / SCALE
                val px = IntArray(w * h)
                val row = IntArray(bitmap.width * SCALE)
                for (y in 0 until h) {
                    bitmap.getPixels(row, 0, bitmap.width, 0, y * SCALE + phase, bitmap.width, SCALE)
                    for (x in 0 until w) {
                        var s = 0
                        for (dy in 0 until SCALE) {
                            val base = dy * bitmap.width + x * SCALE
                            for (dx in 0 until SCALE) {
                                val p = row[base + dx]
                                s += ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                            }
                        }
                        px[y * w + x] = s / (SCALE * SCALE)
                    }
                }
                return Gray(w, h, px)
            }
        }
    }

    private companion object {
        const val SCALE = 4
        const val CAPACITY = 80

        /** Recalls in a row that may miss a memory before it is forgotten. */
        const val FORGET_AFTER = 4
        const val MIN_CELLS = 4

        /** Mean absolute deviation, in grey levels, below which a patch is blank paper. */
        const val MIN_CONTRAST = 10.0

        /** Mean per-cell difference, in grey levels, under which a position matches. */
        const val MATCH_MEAN = 7

        /** Reduction of the stroke-level check: every second pixel. */
        const val DETAIL_SCALE = 2

        /** Cells a stroke mask may hold; larger lettering is checked at a coarser step. */
        const val DETAIL_MAX_CELLS = 24_000L

        /** Paper-to-ink contrast, in grey levels, below which there are no strokes to compare. */
        const val MIN_INK_CONTRAST = 60

        /** Share of ink that must coincide, stroke for stroke, for lettering to be the same. */
        const val MATCH_JACCARD = 0.8f

        /** Side of a glyph-sized tile, in page pixels. */
        const val GLYPH_PX = 22

        /** A tile with at least this share of its cells inked counts as holding a glyph. */
        const val TILE_MIN_INK_PCT = 6

        /** Overlap every glyph-sized tile must reach on its own. */
        const val TILE_JACCARD = 0.6f

        /**
         * The least of a line the frame's edge cuts that must be on screen
         * for it to be recalled: its whole English then still fits, legibly,
         * in what is left of it.
         */
        const val CUT_MIN_SHARE = 0.5f
    }
}
