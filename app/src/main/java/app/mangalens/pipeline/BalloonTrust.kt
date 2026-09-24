package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import app.mangalens.ocr.Balloon

/**
 * Whether a detected balloon may be wiped clean.
 *
 * Balloon detection works on the page alone, and a lot of manga art is
 * exactly what it looks for: an enclosed patch of white paper bounded by
 * ink. A face drawn in line art, a highlight on a cape, the counter of a
 * big sound-effect glyph — each passes as a balloon, and cleaning one
 * paints flat paper over a face. What tells them apart is what is inside:
 * a balloon holds lettering and nothing else. The model has already said
 * where the lettering is, so the interior is checked for ink anywhere else
 * — eyes, mouths, hatching, screentone — and a detection with any real
 * amount of it is not trusted as a balloon. Its lettering is then erased
 * stroke by stroke instead, which leaves the art around it untouched.
 */
internal object BalloonTrust {

    /** Share of the interior, away from the lettering, that may be ink before the balloon is art. */
    private const val MAX_STRAY_INK = 0.012f

    /**
     * Mean grey-level change between neighbouring samples above which the
     * interior is textured — screentone, hatching — rather than paper.
     * Flat and gradient balloons stay within a few levels.
     */
    private const val MAX_TEXTURE = 9f

    /**
     * The same for a balloon found from its own lettering outward, which
     * has already shown itself walled by ink with paper round its text:
     * the texture left is art showing faintly through a see-through
     * balloon's wash, which cleaning with the balloon's own tone is right
     * to smooth away.
     */
    const val SEEN_THROUGH_TEXTURE = 24f

    /** Length to thickness past which a text box is a line, with a direction to run on in. */
    private const val LINE_ASPECT = 1.5f

    /** Share of the interior that is ink in a balloon holding any text at all; a short line in a big balloon is little. */
    private const val MIN_LETTERING = 0.005f

    /** Share of its own bounds a block of lettering's ink fills; features of a face fill far less. */
    private const val MIN_BLOCK_DENSITY = 0.12f

    /** Pixels between samples, each way. */
    private const val SAMPLE_STEP = 2

    /** Mask cells next to the outside skipped, so the balloon's own outline never counts. */
    private const val EDGE_CELLS = 2

    /** Length to thickness past which a text box's shape alone says which way its lettering runs. */
    private const val SQUARISH = 1.15f

    /** Widest gap, in glyphs, between the lettering and a column of ink that continues it. */
    private const val NEIGHBOUR_GAP = 0.9f

    /** Widest run of ink, in glyphs, that is still one column of lettering. */
    private const val MAX_COLUMN = 1.7f

    /**
     * Glyphs a neighbouring column may run past the lettering's own span at
     * either end: a balloon's first column is often longer than the one the
     * box went round.
     */
    private const val SPAN_SLACK = 2.5f

    /** Share of a column's ink that may lie beyond that span before it is art. */
    private const val MAX_SPILL = 0.25f

    /**
     * True when [balloon]'s interior holds nothing but the lettering in
     * [text] — boxes in page pixels, grown a little here for their margin
     * of error.
     */
    fun holdsOnly(bitmap: Bitmap, balloon: Balloon, text: List<Rect>, maxTexture: Float = MAX_TEXTURE): Boolean {
        val grown = withNeighbours(bitmap, balloon, text).map { t ->
            val m = (minOf(t.width(), t.height()) * 0.2f).toInt() + 4
            // And a glyph further along the line at either end: the model's
            // box often stops one character short of a column, and that
            // character is lettering, not art in the balloon.
            val glyph = minOf(t.width(), t.height())
            when {
                t.height() > t.width() * LINE_ASPECT -> Rect(t.left - m, t.top - m - glyph, t.right + m, t.bottom + m + glyph)
                t.width() > t.height() * LINE_ASPECT -> Rect(t.left - m - glyph, t.top - m, t.right + m + glyph, t.bottom + m)
                else -> Rect(t.left - m, t.top - m, t.right + m, t.bottom + m)
            }
        }
        val seen = look(bitmap, balloon, grown) ?: return true
        // A balloon so full of lettering that nothing else is left to look at.
        if (seen.samples < 40) return true
        if (seen.texture > maxTexture) return false
        return seen.ink.toFloat() / seen.samples <= MAX_STRAY_INK
    }

    /**
     * Where the lettering in [balloon] is, when all its ink is one compact
     * block of it: enough ink to be a line of text, on plain paper, and
     * dense within its own bounds. A face that passed for a balloon has
     * ink too — two eyes and a mouth — but spread thin across the whole of
     * it. Null for an empty balloon, textured paper, or scattered ink.
     */
    fun letteringBlock(bitmap: Bitmap, balloon: Balloon): Rect? {
        val seen = look(bitmap, balloon, emptyList()) ?: return null
        val block = seen.inkBox ?: return null
        // The paper's own texture: every stroke of a balloon full of
        // lettering is a step of its own, and counted they made any real
        // balloon's lettering look like screentone.
        if (seen.samples < 40 || seen.paperTexture > MAX_TEXTURE) return null
        if (seen.ink.toFloat() / seen.samples < MIN_LETTERING) return null
        // Samples fall every second pixel each way: one per four pixels.
        val cells = (block.width().toLong() * block.height() / (SAMPLE_STEP * SAMPLE_STEP)).coerceAtLeast(1L)
        return block.takeIf { seen.ink.toFloat() / cells >= MIN_BLOCK_DENSITY }
    }

    /**
     * [text] grown by the columns — or, for horizontal lettering, the
     * rows — of ink that continue it inside [balloon]. The model's box
     * around a vertical balloon's lettering often leaves a column out:
     * the short first one, or a last one holding a single glyph. That
     * column is the same line's lettering, and counted as stray ink it
     * made the whole balloon look like art, so only the boxed columns were
     * erased and the rest stayed on the page beside the English.
     *
     * A run of ink joins when it starts within a glyph's width of the
     * lettering, is no wider than a column, and lies along the lettering's
     * own span — a column of text beside a column of text. Ink that runs
     * on past that span, like the features of a face drawn beside the
     * text, does not.
     */
    private fun withNeighbours(bitmap: Bitmap, balloon: Balloon, text: List<Rect>): List<Rect> {
        if (text.isEmpty()) return text
        val grid = inkGrid(bitmap, balloon) ?: return text
        return text.map { grow(grid, it) }
    }

    private class InkGrid(val left: Int, val top: Int, val w: Int, val h: Int, val ink: BooleanArray) {
        fun gx(x: Int) = ((x - left) / SAMPLE_STEP).coerceIn(0, w - 1)
        fun gy(y: Int) = ((y - top) / SAMPLE_STEP).coerceIn(0, h - 1)
        fun px(gx: Int) = left + gx * SAMPLE_STEP
        fun py(gy: Int) = top + gy * SAMPLE_STEP
    }

    /** Ink samples over [balloon]'s interior, away from its outline. */
    private fun inkGrid(bitmap: Bitmap, balloon: Balloon): InkGrid? {
        val mw = balloon.maskW
        val mh = balloon.maskH
        val box = balloon.box
        if (mw < 3 || mh < 3 || box.width() < 8 || box.height() < 8) return null
        val deep = erode(interiorWithHoles(balloon.mask, mw, mh), mw, mh, EDGE_CELLS)
        val left = box.left.coerceAtLeast(0)
        val top = box.top.coerceAtLeast(0)
        val right = box.right.coerceAtMost(bitmap.width)
        val bottom = box.bottom.coerceAtMost(bitmap.height)
        if (right - left < 4 || bottom - top < 4) return null
        val w = (right - left + SAMPLE_STEP - 1) / SAMPLE_STEP
        val h = (bottom - top + SAMPLE_STEP - 1) / SAMPLE_STEP
        val ink = BooleanArray(w * h)
        val row = IntArray(right - left)
        for (gy in 0 until h) {
            val y = top + gy * SAMPLE_STEP
            bitmap.getPixels(row, 0, row.size, left, y, row.size, 1)
            val cy = ((y - box.top).toLong() * mh / box.height()).toInt().coerceIn(0, mh - 1)
            for (gx in 0 until w) {
                val x = left + gx * SAMPLE_STEP
                val cx = ((x - box.left).toLong() * mw / box.width()).toInt().coerceIn(0, mw - 1)
                if (!deep[cy * mw + cx]) continue
                val p = row[x - left]
                val lum = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                ink[gy * w + gx] = if (balloon.inverted) lum > 150 else lum < 110
            }
        }
        return InkGrid(left, top, w, h, ink)
    }

    /** [t] grown column by column (or row by row) over the ink that continues it. */
    private fun grow(g: InkGrid, t: Rect): Rect {
        var x0 = g.gx(t.left)
        var x1 = g.gx(t.right - 1)
        var y0 = g.gy(t.top)
        var y1 = g.gy(t.bottom - 1)
        if (x1 < x0 || y1 < y0) return t
        // A box taller than wide holds columns; one wider than tall, rows.
        // Only a squarish box is judged by its ink: columns split by empty
        // samples across it, where a single column is split along its
        // length by the gaps between its glyphs and says nothing.
        val cols = runs(x0, x1) { x -> anyInk(g, x, x, y0, y1) }
        val rows = runs(y0, y1) { y -> anyInk(g, x0, x1, y, y) }
        val vertical = when {
            t.height() >= t.width() * SQUARISH -> true
            t.width() >= t.height() * SQUARISH -> false
            else -> cols.size >= rows.size
        }
        if (vertical) {
            val glyph = cols.map { it.last - it.first + 1 }.sorted().let { if (it.isEmpty()) x1 - x0 + 1 else it[it.size / 2] }
            val reach = maxOf(1, (glyph * NEIGHBOUR_GAP).toInt())
            val span0 = (y0 - glyph * SPAN_SLACK).toInt().coerceAtLeast(0)
            val span1 = (y1 + glyph * SPAN_SLACK).toInt().coerceAtMost(g.h - 1)
            while (true) {
                val next = nextRun(x0 - 1, -1, reach, g.w) { x -> anyInk(g, x, x, span0, span1) } ?: break
                if (!fits(next, glyph) || strays(g, next.first, next.last, span0, span1, vertical = true)) break
                x0 = minOf(next.first, next.last)
            }
            while (true) {
                val next = nextRun(x1 + 1, 1, reach, g.w) { x -> anyInk(g, x, x, span0, span1) } ?: break
                if (!fits(next, glyph) || strays(g, next.first, next.last, span0, span1, vertical = true)) break
                x1 = maxOf(next.first, next.last)
            }
        } else {
            val glyph = rows.map { it.last - it.first + 1 }.sorted().let { if (it.isEmpty()) y1 - y0 + 1 else it[it.size / 2] }
            val reach = maxOf(1, (glyph * NEIGHBOUR_GAP).toInt())
            val span0 = (x0 - glyph * SPAN_SLACK).toInt().coerceAtLeast(0)
            val span1 = (x1 + glyph * SPAN_SLACK).toInt().coerceAtMost(g.w - 1)
            while (true) {
                val next = nextRun(y0 - 1, -1, reach, g.h) { y -> anyInk(g, span0, span1, y, y) } ?: break
                if (!fits(next, glyph) || strays(g, span0, span1, next.first, next.last, vertical = false)) break
                y0 = minOf(next.first, next.last)
            }
            while (true) {
                val next = nextRun(y1 + 1, 1, reach, g.h) { y -> anyInk(g, span0, span1, y, y) } ?: break
                if (!fits(next, glyph) || strays(g, span0, span1, next.first, next.last, vertical = false)) break
                y1 = maxOf(next.first, next.last)
            }
        }
        return Rect(
            minOf(t.left, g.px(x0)), minOf(t.top, g.py(y0)),
            maxOf(t.right, g.px(x1) + SAMPLE_STEP), maxOf(t.bottom, g.py(y1) + SAMPLE_STEP),
        )
    }

    private fun anyInk(g: InkGrid, x0: Int, x1: Int, y0: Int, y1: Int): Boolean {
        for (y in y0..y1) for (x in x0..x1) if (g.ink[y * g.w + x]) return true
        return false
    }

    /** Runs of consecutive indices in [from]..[to] where [hit] holds. */
    private fun runs(from: Int, to: Int, hit: (Int) -> Boolean): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = -1
        for (i in from..to) {
            if (hit(i)) {
                if (start < 0) start = i
            } else if (start >= 0) {
                out.add(start until i)
                start = -1
            }
        }
        if (start >= 0) out.add(start..to)
        return out
    }

    /**
     * The next run of ink stepping from [from] by [dir], if it starts
     * within [reach] samples; its range runs from its near edge to its far
     * one. Null when there is none that close.
     */
    private fun nextRun(from: Int, dir: Int, reach: Int, limit: Int, hit: (Int) -> Boolean): IntRange? {
        var i = from
        var gap = 0
        while (i in 0 until limit && !hit(i)) {
            gap++
            if (gap > reach) return null
            i += dir
        }
        if (i !in 0 until limit) return null
        val near = i
        while (i + dir in 0 until limit && hit(i + dir)) i += dir
        return if (dir > 0) near..i else i..near
    }

    /** A run no wider than a column of lettering. */
    private fun fits(run: IntRange, glyph: Int): Boolean = run.last - run.first + 1 <= glyph * MAX_COLUMN

    /**
     * True when the ink in the run spills past the lettering's span: it
     * belongs to something drawn beside the text that runs on past it.
     */
    private fun strays(g: InkGrid, x0: Int, x1: Int, y0: Int, y1: Int, vertical: Boolean): Boolean {
        var inside = 0
        var outside = 0
        if (vertical) {
            for (y in 0 until g.h) for (x in minOf(x0, x1)..maxOf(x0, x1)) {
                if (!g.ink[y * g.w + x]) continue
                if (y in y0..y1) inside++ else outside++
            }
        } else {
            for (y in minOf(y0, y1)..maxOf(y0, y1)) for (x in 0 until g.w) {
                if (!g.ink[y * g.w + x]) continue
                if (x in x0..x1) inside++ else outside++
            }
        }
        return inside == 0 || outside > inside * MAX_SPILL
    }

    /**
     * [texture] is the mean step between neighbouring samples; [paperTexture]
     * the same between samples neither of which is ink, which lettering of
     * any density leaves alone.
     */
    private class Look(val samples: Int, val ink: Int, val texture: Float, val paperTexture: Float, val inkBox: Rect?)

    /**
     * Samples of [balloon]'s interior away from its outline and outside
     * [skip]: how many, how many of them ink and where, and the mean
     * grey-level step between neighbours. Null for a detection too small
     * to judge.
     */
    private fun look(bitmap: Bitmap, balloon: Balloon, skip: List<Rect>): Look? {
        val mw = balloon.maskW
        val mh = balloon.maskH
        val box = balloon.box
        if (mw < 3 || mh < 3 || box.width() < 8 || box.height() < 8) return null
        val interior = interiorWithHoles(balloon.mask, mw, mh)
        val deep = erode(interior, mw, mh, EDGE_CELLS)
        val grown = skip
        val left = box.left.coerceAtLeast(0)
        val right = box.right.coerceAtMost(bitmap.width)
        if (right - left < 2) return null
        val row = IntArray(right - left)
        var samples = 0
        var ink = 0
        var inkBox: Rect? = null
        var steps = 0
        var change = 0L
        var paperSteps = 0
        var paperChange = 0L
        var y = box.top.coerceAtLeast(0)
        val bottom = box.bottom.coerceAtMost(bitmap.height)
        while (y < bottom) {
            val cy = ((y - box.top).toLong() * mh / box.height()).toInt().coerceIn(0, mh - 1)
            bitmap.getPixels(row, 0, row.size, left, y, row.size, 1)
            var x = left
            var prev = -1
            var prevInk = false
            while (x < right) {
                val cx = ((x - box.left).toLong() * mw / box.width()).toInt().coerceIn(0, mw - 1)
                if (deep[cy * mw + cx] && grown.none { it.contains(x, y) }) {
                    val p = row[x - left]
                    val lum = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    samples++
                    val isInk = if (balloon.inverted) lum > 150 else lum < 110
                    if (isInk) {
                        ink++
                        inkBox?.union(x, y) ?: run { inkBox = Rect(x, y, x + 1, y + 1) }
                    }
                    if (prev >= 0) {
                        change += kotlin.math.abs(lum - prev)
                        steps++
                        if (!isInk && !prevInk) {
                            paperChange += kotlin.math.abs(lum - prev)
                            paperSteps++
                        }
                    }
                    prev = lum
                    prevInk = isInk
                } else {
                    prev = -1
                }
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        return Look(
            samples, ink,
            if (steps > 20) change.toFloat() / steps else 0f,
            if (paperSteps > 20) paperChange.toFloat() / paperSteps else 0f,
            inkBox,
        )
    }

    /**
     * The mask with its holes filled: everything not reachable from the
     * mask's border through non-mask cells. The flood stops at lettering,
     * so lettering — and anything else drawn inside — shows up as holes.
     */
    private fun interiorWithHoles(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val outside = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        fun push(i: Int) {
            if (!mask[i] && !outside[i]) {
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

    private fun erode(mask: BooleanArray, w: Int, h: Int, times: Int): BooleanArray {
        var cur = mask
        repeat(times) {
            val next = BooleanArray(w * h)
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    next[i] = cur[i] && cur[i - 1] && cur[i + 1] && cur[i - w] && cur[i + w]
                }
            }
            cur = next
        }
        return cur
    }
}
