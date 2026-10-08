package app.mangalens.gaps

/**
 * The white of a strip that belongs to the art, as a mask in strip coordinates: the pixels
 * the shade must never cover, however white they are.
 *
 * Some of it can be read off the strip: paper shut in by ink — the inside of a balloon, the
 * counters of the lettering — is not the gutter, which runs out to the reading column's edges
 * on both sides. Paper that lies in a panel, though, can look exactly like a gutter, and only
 * the one who drew it knows; [panels] names those rows.
 */
internal fun protectedWhite(
    strip: Strip,
    /** The reading column, `[colL, colR)`: the page between any black bars at its sides. */
    colL: Int = 0,
    colR: Int = strip.w,
    /** Rows `[y0, y1)` that are panels: every paper pixel in them is the art's. */
    panels: List<IntArray> = emptyList(),
): BooleanArray {
    val w = strip.w
    val h = strip.h
    val px = strip.px
    val out = BooleanArray(w * h)
    for (p in panels) {
        for (y in p[0].coerceAtLeast(0) until p[1].coerceAtMost(h)) for (x in 0 until w) {
            if (Strip.isPaper(px[y * w + x])) out[y * w + x] = true
        }
    }
    // Paper shut in by ink: a 4-connected region of paper that does not run from one side of
    // the reading column to the other.
    val seen = BooleanArray(w * h)
    val stack = IntList(4096)
    val region = IntList(4096)
    for (start in 0 until w * h) {
        if (seen[start] || !Strip.isPaper(px[start])) continue
        stack.n = 0
        region.n = 0
        stack.add(start)
        seen[start] = true
        var left = false
        var right = false
        while (stack.n > 0) {
            val i = stack.a[--stack.n]
            region.add(i)
            val x = i % w
            if (x <= colL) left = true
            if (x >= colR - 1) right = true
            fun visit(j: Int) {
                if (!seen[j] && Strip.isPaper(px[j])) {
                    seen[j] = true
                    stack.add(j)
                }
            }
            if (x > 0) visit(i - 1)
            if (x < w - 1) visit(i + 1)
            if (i >= w) visit(i - w)
            if (i < (h - 1) * w) visit(i + w)
        }
        if (!(left && right)) for (k in 0 until region.n) out[region.a[k]] = true
    }
    return out
}

/** A strip and the white in it the shade must never cover. */
internal class WhiteScene(val strip: Strip, val white: BooleanArray, val panels: List<IntArray>)

/**
 * A strip whose panels hold white of their own — the art styles a reader sees darkened when the
 * shade cannot tell them from a gutter. In turn, panel after panel:
 *
 *  - a white shirt: a white shape, outlined, well inside textured art;
 *  - a white wall: white from the left edge of the screen to well past a third of the way
 *    across, art above, below and to its right — a gutter that art has cut into, to the finder;
 *  - line art on white: the panel's top part is white from edge to edge, under the panel's
 *    rule, with a figure drawn in thin lines across it;
 *  - a white panel: the whole panel is white inside a thin border box set in from the screen's
 *    edges, a figure in thin lines inside.
 *
 * Gutters between the panels are ordinary, with balloons and narration as [scrollStrip] has.
 */
internal fun whitePanelStrip(w: Int, seed: Long = 41): WhiteScene {
    val h = 14000
    val s = Strip(w, h, seed)
    val rnd = kotlin.random.Random(seed)
    val panels = ArrayList<IntArray>()
    var y = 0
    var n = 0
    fun ink(x0: Int, y0: Int, x1: Int, y1: Int) = s.solid(x0, y0, x1, y1, Strip.BLACK)

    /** An ellipse outline [t] pixels thick; white inside when [fill]. */
    fun ellipse(cx: Int, cy: Int, rx: Int, ry: Int, t: Int, fill: Boolean) {
        for (yy in cy - ry - t..cy + ry + t) for (xx in cx - rx - t..cx + rx + t) {
            if (xx !in 0 until w || yy !in 0 until h) continue
            val dx = (xx - cx).toDouble()
            val dy = (yy - cy).toDouble()
            val outer = (dx / (rx + t)) * (dx / (rx + t)) + (dy / (ry + t)) * (dy / (ry + t))
            val inner = (dx / rx) * (dx / rx) + (dy / ry) * (dy / ry)
            if (outer <= 1.0) s.px[yy * w + xx] = if (inner <= 1.0) { if (fill) Strip.WHITE else s.px[yy * w + xx] } else Strip.BLACK
        }
    }

    /** A figure in lines two pixels thick over white rows [y0, y1): a head, a body, arms, some hatching. */
    fun figure(cx: Int, y0: Int, y1: Int) {
        val hh = y1 - y0
        ellipse(cx, y0 + hh / 5, hh / 9, hh / 8, 2, fill = false)
        ink(cx - 1, y0 + hh / 3, cx + 1, y0 + hh * 4 / 5)
        for (k in 0 until hh / 4) {
            val xa = cx - k
            val xb = cx + k
            val yy = y0 + hh * 2 / 5 + k / 2
            ink(xa - 1, yy, xa + 1, yy + 2); ink(xb - 1, yy, xb + 1, yy + 2)
        }
        for (k in 0 until 6) ink(cx + 40 + k * 7, y0 + hh / 2, cx + 41 + k * 7, y0 + hh / 2 + 30)
        ink(cx - 120, y1 - 18, cx + 120, y1 - 16)
    }
    while (y < h) {
        val art = 420 + (n * 97) % 260
        val y0 = y
        val y1 = y + art
        s.art(0, y0, w, y1); s.rules(0, y0, w, y1)
        when (n % 4) {
            0 -> {
                // a white shirt, outlined, inside the art
                ellipse(w / 2 + rnd.nextInt(-60, 60), (y0 + y1) / 2, 150, art / 4, 4, fill = true)
            }
            1 -> {
                // a white wall from the screen's left edge, art above, below and to its right
                val wy0 = y0 + 70
                val wy1 = y1 - 70
                s.solid(0, wy0, w * 46 / 100, wy1, Strip.WHITE)
                ink(w * 46 / 100, wy0, w * 46 / 100 + 3, wy1)
            }
            2 -> {
                // line art on white: the top of the panel white from edge to edge, under its rule
                val by1 = y0 + 6 + art / 2
                s.solid(0, y0 + 6, w, by1, Strip.WHITE)
                figure(w / 2 + rnd.nextInt(-80, 80), y0 + 20, by1 - 10)
                ink(0, by1, w, by1 + 3)
            }
            else -> {
                // a white panel in a thin border box, set in from the screen's edges
                s.solid(12, y0 + 6, w - 12, y1 - 6, Strip.WHITE)
                ink(12, y0 + 6, 14, y1 - 6); ink(w - 14, y0 + 6, w - 12, y1 - 6)
                figure(w / 2 + rnd.nextInt(-80, 80), y0 + 30, y1 - 30)
            }
        }
        panels.add(intArrayOf(y0, y1))
        y = y1
        val gap = 250 + (n * 61) % 400
        if (n % 2 == 0) s.balloon(300 + (n * 53) % 200, y + gap / 2, 180, minOf(90, gap / 2 - 20))
        if (n % 3 == 1) s.text(80, y + 30, 640, y + gap - 30)
        y += gap
        n++
    }
    return WhiteScene(s, protectedWhite(s, panels = panels), panels)
}
