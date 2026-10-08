package app.mangalens.gaps

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * A synthetic webtoon strip: textured colour art, flat white gutters, speech balloons with
 * outlines and lettering, narration set straight onto the white. The real thing cannot
 * be shipped in a repository, but everything the gap finder has to be right about — what
 * may be darkened and what must not be touched — can be drawn exactly, and so judged exactly.
 *
 * Alongside the pixels the strip keeps the truth: [paperTruth] marks the pixels that are
 * strict paper (every channel at least 240). The one invariant that must never break is
 * that the shade covers paper and only paper.
 */
internal class Strip(val w: Int, val h: Int, seed: Long = 7L) {

    val px = IntArray(w * h) { WHITE }
    private val rnd = Random(seed)

    /** Textured colour art — never close to white, never flat. */
    fun art(x0: Int, y0: Int, x1: Int, y1: Int, hue: Int = rnd.nextInt(360)) {
        val fx = 0.011 + rnd.nextDouble() * 0.02
        val fy = 0.017 + rnd.nextDouble() * 0.02
        val px0 = rnd.nextDouble() * 6.0
        val py0 = rnd.nextDouble() * 6.0
        for (y in y0 until y1) for (x in x0 until x1) {
            if (x !in 0 until w || y !in 0 until h) continue
            val base = 0.5 + 0.28 * sin(x * fx * 2 * PI + px0) * sin(y * fy * 2 * PI + py0) +
                0.12 * sin((x + y) * 0.07 + hue)
            // stripes every few rows give a scroll tracker real structure to hold on to
            val band = if ((y / 11 + (hue % 5)) % 3 == 0) -0.10 else 0.0
            val n = (rnd.nextInt(11) - 5) / 255.0
            val v = (base + band + n).coerceIn(0.08, 0.80)
            val r = (v * (120 + (hue * 7) % 120)).toInt().coerceIn(10, 215)
            val g = (v * (110 + (hue * 13) % 130)).toInt().coerceIn(10, 215)
            val b = (v * (100 + (hue * 29) % 140)).toInt().coerceIn(10, 215)
            px[y * w + x] = rgb(r, g, b)
        }
    }

    fun solid(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        for (y in y0 until y1) for (x in x0 until x1) if (x in 0 until w && y in 0 until h) px[y * w + x] = color
    }

    /** A black rule of [t] pixels along the top and bottom edges of an art block, as in the reference screenshots. */
    fun rules(x0: Int, y0: Int, x1: Int, y1: Int, t: Int = 6) {
        solid(x0, y0, x1, y0 + t, BLACK)
        solid(x0, y1 - t, x1, y1, BLACK)
    }

    /** A panel's border: the outline of [x0, x1) × [y0, y1), [t] pixels thick, drawn inside it. The inside is left as it was. */
    fun box(x0: Int, y0: Int, x1: Int, y1: Int, t: Int, color: Int = BLACK) {
        solid(x0, y0, x1, y0 + t, color)
        solid(x0, y1 - t, x1, y1, color)
        solid(x0, y0, x0 + t, y1, color)
        solid(x1 - t, y0, x1, y1, color)
    }

    /**
     * A straight stroke [width] pixels wide with round ends, from (xa, ya) to (xb, yb): a line
     * of line-art, a speed line. Every pixel whose centre lies within half the width of the
     * segment is painted, so a stroke one pixel wide is one pixel wide and nothing paler.
     */
    fun line(xa: Double, ya: Double, xb: Double, yb: Double, width: Double, color: Int = BLACK) {
        val r = width / 2
        val dx = xb - xa
        val dy = yb - ya
        val len2 = (dx * dx + dy * dy).coerceAtLeast(1e-9)
        val x0 = (minOf(xa, xb) - r).toInt() - 1
        val x1 = (maxOf(xa, xb) + r).toInt() + 1
        val y0 = (minOf(ya, yb) - r).toInt() - 1
        val y1 = (maxOf(ya, yb) + r).toInt() + 1
        for (y in y0..y1) for (x in x0..x1) {
            if (x !in 0 until w || y !in 0 until h) continue
            val t = (((x - xa) * dx + (y - ya) * dy) / len2).coerceIn(0.0, 1.0)
            val ex = xa + t * dx - x
            val ey = ya + t * dy - y
            if (ex * ex + ey * ey <= r * r) px[y * w + x] = color
        }
    }

    /** A filled ellipse centred on ([cx], [cy]) with radii [rx] and [ry]. */
    fun ellipse(cx: Int, cy: Int, rx: Int, ry: Int, color: Int) {
        for (y in cy - ry..cy + ry) for (x in cx - rx..cx + rx) {
            if (x !in 0 until w || y !in 0 until h) continue
            val ex = (x - cx).toDouble() / rx
            val ey = (y - cy).toDouble() / ry
            if (ex * ex + ey * ey <= 1.0) px[y * w + x] = color
        }
    }

    /**
     * Fades rows [y0, y1) of columns [x0, x1) into pure white: the row at [y0] is left as it
     * was when [whiteAtTop] is false and is pure white when it is true, and the blend runs
     * evenly to the other end — art, or a screentone, dissolving into the paper at a panel's edge.
     */
    fun fadeToWhite(x0: Int, y0: Int, x1: Int, y1: Int, whiteAtTop: Boolean) {
        val n = (y1 - y0).coerceAtLeast(1)
        for (y in y0 until y1) {
            val k = (y - y0).toDouble() / (n - 1).coerceAtLeast(1)
            val t = if (whiteAtTop) 1.0 - k else k
            for (x in x0 until x1) {
                if (x !in 0 until w || y !in 0 until h) continue
                val p = px[y * w + x]
                fun mix(c: Int) = Math.round(c + (255 - c) * t).toInt().coerceIn(0, 255)
                px[y * w + x] = rgb(mix((p ushr 16) and 0xFF), mix((p ushr 8) and 0xFF), mix(p and 0xFF))
            }
        }
    }

    /**
     * A screentone over [x0, x1) × [y0, y1): round dots of [color] on a [pitch]-pixel grid on
     * the white, their radius shrinking from [maxRadius] at the bottom to nothing at the top,
     * so the tone thins out into pure paper.
     */
    fun toneFadingUp(x0: Int, y0: Int, x1: Int, y1: Int, pitch: Int = 6, maxRadius: Double = 2.6, color: Int = BLACK) {
        val n = (y1 - y0).coerceAtLeast(1)
        var cy = y0 + pitch / 2
        while (cy < y1) {
            val r = maxRadius * (cy - y0).toDouble() / n
            var cx = x0 + pitch / 2 + if (((cy - y0) / pitch) % 2 == 0) 0 else pitch / 2
            while (cx < x1) {
                if (r >= 0.5) {
                    val ri = r.toInt() + 1
                    for (y in cy - ri..cy + ri) for (x in cx - ri..cx + ri) {
                        if (x !in x0 until x1 || y !in y0 until y1 || x !in 0 until w || y !in 0 until h) continue
                        val ex = (x - cx).toDouble()
                        val ey = (y - cy).toDouble()
                        if (ex * ex + ey * ey <= r * r) px[y * w + x] = color
                    }
                }
                cx += pitch
            }
            cy += pitch
        }
    }

    /**
     * A speech balloon: white inside, [thick] pixels of black outline, lettering made of small
     * dark blobs. [leak] opens a gap of that many pixels in the outline, at the right-hand side.
     */
    fun balloon(cx: Int, cy: Int, rx: Int, ry: Int, thick: Int = 5, leak: Int = 0, lettering: Boolean = true) {
        for (y in cy - ry - thick..cy + ry + thick) for (x in cx - rx - thick..cx + rx + thick) {
            if (x !in 0 until w || y !in 0 until h) continue
            val dx = (x - cx).toDouble()
            val dy = (y - cy).toDouble()
            val outer = (dx / (rx + thick)) * (dx / (rx + thick)) + (dy / (ry + thick)) * (dy / (ry + thick))
            val inner = (dx / rx) * (dx / rx) + (dy / ry) * (dy / ry)
            if (outer <= 1.0) px[y * w + x] = if (inner <= 1.0) WHITE else BLACK
        }
        if (leak > 0) {
            for (y in cy - leak / 2..cy + leak / 2) for (x in cx + rx - 2..cx + rx + thick + 2) {
                if (x in 0 until w && y in 0 until h) px[y * w + x] = WHITE
            }
        }
        if (lettering) text(cx - rx * 6 / 10, cy - ry * 5 / 10, cx + rx * 6 / 10, cy + ry * 5 / 10)
    }

    /**
     * Lines of lettering, 16 pixels tall: each glyph is a few two-pixel strokes of a cell
     * eleven pixels wide, about a third of it ink — the density of real type, which is
     * what the art check has to tell apart from a panel.
     */
    fun text(x0: Int, y0: Int, x1: Int, y1: Int, color: Int = BLACK) {
        var y = y0
        while (y + 16 <= y1) {
            var x = x0
            while (x < x1) {
                val len = 3 + rnd.nextInt(6)
                for (g in 0 until len) {
                    val gx = x + g * 11
                    if (gx + 9 > x1) break
                    fun bar(xa: Int, ya: Int, xb: Int, yb: Int) {
                        for (yy in ya until yb) for (xx in xa until xb) {
                            val X = gx + xx
                            if (X in 0 until w && y + yy in 0 until h) px[(y + yy) * w + X] = color
                        }
                    }
                    if (rnd.nextInt(10) < 7) bar(1, 0, 3, 16)
                    if (rnd.nextInt(10) < 6) bar(6, 0, 8, 16)
                    if (rnd.nextInt(10) < 5) bar(1, 0, 8, 2)
                    if (rnd.nextInt(10) < 5) bar(1, 7, 8, 9)
                    if (rnd.nextInt(10) < 4) bar(1, 14, 8, 16)
                }
                x += len * 11 + 16
            }
            y += 30
        }
    }

    /** Rows [y0, y1) copied out as a frame of the strip's width. */
    fun frame(y0: Int, rows: Int): IntArray {
        val out = IntArray(w * rows) { BLACK }
        for (r in 0 until rows) {
            val y = y0 + r
            if (y in 0 until h) System.arraycopy(px, y * w, out, r * w, w)
        }
        return out
    }

    companion object {
        val WHITE = 0xFFFFFFFF.toInt()
        val BLACK = 0xFF000000.toInt()
        fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

        /** Strict paper: the same test the finder makes. */
        fun isPaper(p: Int) = (p and 0xF0F0F0) == 0xF0F0F0
    }
}

/**
 * The bars that stay put over a page [w] wide and [h] tall: a browser's tabs (in a grey that
 * reads as paper under the shade) and address bar and a site's header across rows [0, top), a
 * site's footer with a button at either end and the system's navigation bar across [bottom, h).
 * Only those rows are painted; the rest is left white.
 */
internal fun chromeBars(w: Int, h: Int, top: Int, bottom: Int, seed: Long = 3): Strip {
    val s = Strip(w, h, seed)
    val tabs = top * 3 / 10
    val address = top * 13 / 20
    s.solid(0, 0, w, tabs, Strip.rgb(23, 23, 25))
    s.text(14, tabs / 3, w - 20, tabs / 3 + 18, Strip.rgb(230, 230, 232))
    s.solid(0, tabs, w, address, Strip.rgb(35, 38, 47))
    s.text(14, tabs + 24, w * 3 / 4, tabs + 44, Strip.rgb(232, 232, 232))
    s.solid(0, address, w, top, Strip.rgb(33, 33, 35))
    s.solid(20, address + 10, 70, top - 10, Strip.rgb(250, 250, 250))
    s.text(90, address + 20, 260, address + 42, Strip.rgb(240, 240, 240))
    s.solid(w - 70, address + 10, w - 20, top - 10, Strip.rgb(250, 250, 250))
    val nav = bottom + (h - bottom) * 6 / 10
    s.solid(0, bottom, w, nav, Strip.rgb(33, 33, 35))
    s.solid(16, bottom + 10, 110, nav - 10, Strip.rgb(124, 58, 237)); s.text(30, bottom + 22, 100, bottom + 44, Strip.WHITE)
    s.solid(w - 110, bottom + 10, w - 16, nav - 10, Strip.rgb(124, 58, 237)); s.text(w - 96, bottom + 22, w - 26, bottom + 44, Strip.WHITE)
    s.solid(0, nav, w, h, Strip.rgb(26, 25, 31))
    s.solid(w - 90, nav + 8, w - 60, h - 8, Strip.rgb(220, 220, 224))
    return s
}

/** Paints the rows of [bars] outside [top, bottom) over a frame of the same size. */
internal fun overlayBars(bars: Strip, top: Int, bottom: Int): (IntArray) -> Unit = { f ->
    System.arraycopy(bars.px, 0, f, 0, top * bars.w)
    System.arraycopy(bars.px, bottom * bars.w, f, bottom * bars.w, (bars.h - bottom) * bars.w)
}

/** Blends an overlay of [rects] over [page] the way the compositor does. */
internal fun composite(
    page: IntArray, w: Int, h: Int, rects: IntArray, count: Int, style: ShadeStyle, dy: Int = 0,
    /** A compositor that blends in linear light rather than in the encoded values. */
    linear: Boolean = false,
    /** The screen rows the overlay is cut to. */
    top: Int = 0,
    bottom: Int = Int.MAX_VALUE,
): IntArray {
    val out = page.copyOf()
    val a = style.alpha
    fun chan(v: Int): Int {
        if (!linear) return Math.round((1f - a) * v)
        val lin = Math.pow(v / 255.0, 2.2) * (1.0 - a)
        return Math.round(255.0 * Math.pow(lin, 1 / 2.2)).toInt()
    }
    for (i in 0 until count) {
        val x0 = rects[i * 4].coerceIn(0, w)
        val y0 = (rects[i * 4 + 1] + dy).coerceIn(maxOf(0, top), h)
        val x1 = rects[i * 4 + 2].coerceIn(0, w)
        val y1 = (rects[i * 4 + 3] + dy).coerceIn(0, minOf(h, bottom))
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = out[y * w + x]
            out[y * w + x] = Strip.rgb(chan((p ushr 16) and 0xFF), chan((p ushr 8) and 0xFF), chan(p and 0xFF))
        }
    }
    return out
}

/** Pixels the rectangles cover, as a flat mask over the frame. */
internal fun coverage(rects: IntArray, count: Int, w: Int, h: Int, dy: Int = 0): BooleanArray {
    val m = BooleanArray(w * h)
    for (i in 0 until count) {
        val x0 = rects[i * 4].coerceIn(0, w)
        val y0 = (rects[i * 4 + 1] + dy).coerceIn(0, h)
        val x1 = rects[i * 4 + 2].coerceIn(0, w)
        val y1 = (rects[i * 4 + 3] + dy).coerceIn(0, h)
        for (y in y0 until y1) for (x in x0 until x1) m[y * w + x] = true
    }
    return m
}

internal fun absDiff(a: Int, b: Int) = abs(a - b)

/**
 * Pixels the shade covers that are neither paper nor the anti-aliased edge of paper — that
 * is, covered pixels with no paper within [reach] pixels. Anything counted here is art that
 * was darkened.
 */
internal fun artDamage(page: IntArray, covered: BooleanArray, w: Int, h: Int, reach: Int = 4): Int {
    var bad = 0
    for (y in 0 until h) for (x in 0 until w) {
        if (!covered[y * w + x] || Strip.isPaper(page[y * w + x])) continue
        var near = false
        loop@ for (dy in -reach..reach) for (dx in -reach..reach) {
            val xx = x + dx
            val yy = y + dy
            if (xx in 0 until w && yy in 0 until h && Strip.isPaper(page[yy * w + xx])) { near = true; break@loop }
        }
        if (!near) bad++
    }
    return bad
}
