package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import app.mangalens.translate.PageItem
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
    )

    /**
     * The lettering's ink at [f]-times reduction — near full resolution —
     * as a [w] by [h] mask: the strokes themselves, independent of how
     * bright the capture was.
     */
    private class Detail(val f: Int, val w: Int, val h: Int, val ink: BooleanArray, val darkInk: Boolean) {
        val inkCount = ink.count { it }
    }

    private val items = ArrayDeque<Remembered>()

    /** Remembers [found], as read off [bitmap], for later frames. */
    @Synchronized
    fun remember(bitmap: Bitmap, found: List<PageItem>) {
        if (found.isEmpty()) return
        val gray = Gray.of(bitmap)
        for (item in found) {
            val r = fingerprint(gray, bitmap, item) ?: continue
            items.removeAll { overlaps(it, r) }
            items.addLast(r)
        }
        while (items.size > CAPACITY) items.removeFirst()
    }

    @Synchronized
    fun clear() = items.clear()

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
        val gray = Gray.of(bitmap)
        val found = ArrayList<Pair<Int, PageItem>>()
        for (i in items.indices.reversed()) {
            val r = items[i]
            if (r.pageW != bitmap.width || r.pageH != bitmap.height) continue
            val cy = find(gray, r) ?: continue
            val coarse = (cy - r.cy) * SCALE
            val (dx, dy) = verify(bitmap, r, coarse) ?: continue
            val box = Rect(r.item.box).apply { offset(dx, dy) }
            if (box.top < ignoreTop || box.bottom > bitmap.height - ignoreBottom) continue
            if (box.top < 0 || box.bottom > bitmap.height) continue
            if (found.any { (_, it) -> sameSpot(it.box, box) }) continue
            found.add(i to r.item.copy(box = box))
        }
        return found.sortedBy { it.first }.map { it.second }
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
        val left = box.left - reachX * f
        val top = box.top + coarse - reachY * f
        val gw = d.w + reachX * 2
        val gh = d.h + reachY * 2
        if (left < 0 || top < 0 || left + gw * f > bitmap.width || top + gh * f > bitmap.height) return null
        val g = sample(bitmap, left, top, gw, gh, f)
        val t = threshold(g) ?: return null
        val ink = BooleanArray(g.size) { if (d.darkInk) g[it] < t else g[it] > t }
        var best = 0f
        var bestX = 0
        var bestY = 0
        for (sy in 0..reachY * 2) {
            for (sx in 0..reachX * 2) {
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
        return Pair((bestX - reachX) * f, coarse + (bestY - reachY) * f)
    }

    /**
     * A whole-line overlap forgives a single changed character — six of
     * seven glyphs alike still overlap well — so the lettering is also
     * checked tile by tile, each tile about a glyph across: every tile with
     * ink in it must match on its own.
     */
    private fun everyGlyphMatches(d: Detail, ink: BooleanArray, gw: Int, sx: Int, sy: Int): Boolean {
        val tile = (GLYPH_PX / d.f).coerceAtLeast(4)
        var ty = 0
        while (ty < d.h) {
            var tx = 0
            while (tx < d.w) {
                var inter = 0
                var union = 0
                var cells = 0
                for (y in ty until minOf(ty + tile, d.h)) {
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

    /** Quarter-scale grey image of a frame. */
    private class Gray(val w: Int, val h: Int, val px: IntArray) {
        companion object {
            fun of(bitmap: Bitmap): Gray {
                val w = bitmap.width / SCALE
                val h = bitmap.height / SCALE
                val px = IntArray(w * h)
                val row = IntArray(bitmap.width * SCALE)
                for (y in 0 until h) {
                    bitmap.getPixels(row, 0, bitmap.width, 0, y * SCALE, bitmap.width, SCALE)
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
    }
}
