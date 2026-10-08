package app.mangalens.gaps

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * What each pixel of a white-art scene is, for judging the shade laid over it.
 *
 * Only paper — every channel 240 or more — can be [GUTTER], [PROTECTED] or [OPEN]; whatever
 * else is drawn is [ART]. [DONTCARE] is where no answer is wrong: the two pixels either side
 * of a line between gutter and protected white, the margin the finder leaves round lettering,
 * the gap in a broken border.
 */
internal object Truth {
    const val DONTCARE: Byte = 0

    /** White between panels, or beside a narrower one: it should be shaded. */
    const val GUTTER: Byte = 1

    /**
     * White of the art that the pixels alone set apart from every gutter: shut off by something
     * drawn — a border, an outline — like a white panel interior, a sky inside a frame, a white
     * shirt inside its outline; or of another white than the page's gutters, like a sky whose
     * tinted near-white (242..250, never the gutter's flat 255) reaches a panel's open edge. It
     * must never be shaded.
     */
    const val PROTECTED: Byte = 2

    /**
     * White of the art that runs into a gutter through white: a borderless figure's ground, a
     * gradient that dissolves into the paper, speed lines that bleed off the strip, a border
     * with breaks wider than the seal. A reader keeps it white; the finder, by its own account
     * (the README's known limit), cannot tell it from the gutter. Measured, not promised.
     */
    const val OPEN: Byte = 3

    /** Anything that is not paper. Shading it beyond the anti-aliased fringe is damage. */
    const val ART: Byte = 4

    /** Paper within this many pixels of white of the other kind is [DONTCARE]. */
    const val BAND = 2
}

/** A scene: a strip and, pixel for pixel in strip coordinates, the [Truth] of it. */
internal class Scene(
    val name: String,
    /** One line on what the scene is and why it is hard. */
    val note: String,
    val strip: Strip,
    val truth: ByteArray,
    /** Whether the scene's gutters are plain bands and margins whose recall is held to account. */
    val plainGutters: Boolean,
) {
    val w: Int get() = strip.w
    val h: Int get() = strip.h
}

/**
 * Draws a scene and its truth together. Paper is gutter unless a region says otherwise;
 * regions marked later win over regions marked earlier, so a protected shirt can sit inside
 * an open ground inside a don't-care ring.
 */
internal class SceneBuilder(val name: String, h: Int, seed: Long, w: Int = WhiteArtScenes.W) {
    val s = Strip(w, h, seed)
    val rnd = Random(seed * 31 + 7)
    private val marks = ByteArray(w * h) { UNSET }

    /** Marks [x0, x1) × [y0, y1) as [what]: [Truth.PROTECTED], [Truth.OPEN] or [Truth.DONTCARE]. */
    fun mark(x0: Int, y0: Int, x1: Int, y1: Int, what: Byte) {
        for (y in maxOf(0, y0) until min(s.h, y1)) {
            val a = maxOf(0, x0)
            val b = min(s.w, x1)
            if (a < b) marks.fill(what, y * s.w + a, y * s.w + b)
        }
    }

    /** Marks the inside of an ellipse as [what]. */
    fun markEllipse(cx: Int, cy: Int, rx: Int, ry: Int, what: Byte) {
        for (y in cy - ry..cy + ry) for (x in cx - rx..cx + rx) {
            if (x !in 0 until s.w || y !in 0 until s.h) continue
            val ex = (x - cx).toDouble() / rx
            val ey = (y - cy).toDouble() / ry
            if (ex * ex + ey * ey <= 1.0) marks[y * s.w + x] = what
        }
    }

    /** A speech balloon whose inside is protected. */
    fun balloon(cx: Int, cy: Int, rx: Int, ry: Int, thick: Int = 5) {
        s.balloon(cx, cy, rx, ry, thick)
        markEllipse(cx, cy, rx, ry, Truth.PROTECTED)
    }

    /** Narration on the white. The finder rightly keeps a margin of white round lettering: that margin is nobody's business here. */
    fun narration(x0: Int, y0: Int, x1: Int, y1: Int, color: Int = Strip.BLACK) {
        s.text(x0, y0, x1, y1, color)
        mark(x0 - 30, y0 - 30, x1 + 30, y1 + 30, Truth.DONTCARE)
    }

    /** Runs [draw], then puts back every pixel outside [x0, x1) × [y0, y1): drawing clipped to a panel. */
    fun clipped(x0: Int, y0: Int, x1: Int, y1: Int, draw: () -> Unit) {
        val before = s.px.copyOf()
        draw()
        for (y in 0 until s.h) {
            val row = y * s.w
            if (y < y0 || y >= y1) {
                System.arraycopy(before, row, s.px, row, s.w)
                continue
            }
            if (x0 > 0) System.arraycopy(before, row, s.px, row, min(x0, s.w))
            if (x1 < s.w) System.arraycopy(before, row + x1, s.px, row + x1, s.w - x1)
        }
    }

    /**
     * Panels down the strip from [start]: [draw] is given the panel's index, its rows and the
     * end of the gutter after it, for as long as a whole panel fits.
     */
    fun panels(start: Int, heights: IntArray, gaps: IntArray, draw: (k: Int, y0: Int, y1: Int, gapEnd: Int) -> Unit) {
        var y = start
        var k = 0
        while (y + heights[k % heights.size] <= s.h - 40) {
            val y1 = y + heights[k % heights.size]
            val gapEnd = min(s.h, y1 + gaps[k % gaps.size])
            draw(k, y, y1, gapEnd)
            y = gapEnd
            k++
        }
    }

    /**
     * A figure: an outlined head under dark hair, and a white shirt shut in by its outline —
     * enclosed white, so protected. Returns its bounding box, `x0, y0, x1, y1`.
     */
    fun figure(cx: Int, top: Int, scale: Double = 1.0): IntArray {
        fun d(v: Int) = (v * scale).toInt()
        val headY = top + d(70)
        val bodyY = top + d(280)
        // shirt: outline, then the white inside it, then a collar and a seam drawn on the white
        s.ellipse(cx, bodyY, d(120) + 4, d(140) + 4, Strip.BLACK)
        s.ellipse(cx, bodyY, d(120), d(140), Strip.WHITE)
        markEllipse(cx, bodyY, d(120), d(140), Truth.PROTECTED)
        s.line(cx - d(40).toDouble(), bodyY - d(120).toDouble(), cx.toDouble(), bodyY - d(70).toDouble(), 3.0)
        s.line(cx + d(40).toDouble(), bodyY - d(120).toDouble(), cx.toDouble(), bodyY - d(70).toDouble(), 3.0)
        s.line(cx.toDouble(), bodyY - d(70).toDouble(), cx.toDouble(), bodyY + d(100).toDouble(), 2.0)
        // neck and head
        s.solid(cx - d(16), headY + d(50), cx + d(16), bodyY - d(125), SKIN)
        s.ellipse(cx, headY, d(55) + 3, d(68) + 3, Strip.BLACK)
        s.ellipse(cx, headY, d(55), d(68), SKIN)
        s.ellipse(cx, headY - d(30), d(58), d(40), HAIR)
        s.ellipse(cx - d(20), headY + d(8), d(6), d(9), Strip.BLACK)
        s.ellipse(cx + d(20), headY + d(8), d(6), d(9), Strip.BLACK)
        return intArrayOf(cx - d(124) - 4, top - 4, cx + d(124) + 4, bodyY + d(144) + 4)
    }

    fun build(note: String, plainGutters: Boolean): Scene {
        val w = s.w
        val h = s.h
        val truth = ByteArray(w * h)
        val gutter = BitPlane(w, h)
        val kept = BitPlane(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val m = marks[i]
            truth[i] = when {
                m == Truth.DONTCARE -> Truth.DONTCARE
                !Strip.isPaper(s.px[i]) -> Truth.ART
                m == Truth.PROTECTED || m == Truth.OPEN -> m.also { kept.set(x, y) }
                else -> Truth.GUTTER.also { gutter.set(x, y) }
            }
        }
        // where gutter meets kept white with nothing drawn between them, the line between is anyone's
        val nearGutter = gutter.copy().dilate(Truth.BAND)
        val nearKept = kept.copy().dilate(Truth.BAND)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val t = truth[i]
            if ((t == Truth.GUTTER && nearKept.get(x, y)) || ((t == Truth.PROTECTED || t == Truth.OPEN) && nearGutter.get(x, y))) {
                truth[i] = Truth.DONTCARE
            }
        }
        return Scene(name, note, s, truth, plainGutters)
    }

    companion object {
        private const val UNSET: Byte = -1
        val SKIN = Strip.rgb(236, 196, 170)
        val HAIR = Strip.rgb(40, 32, 48)
    }
}

/**
 * White art and real gutters, drawn with their truth: the cases where deciding which white
 * is gutter is hardest. Every strip is [W] wide and a few thousand rows tall; scenes are
 * built on demand, one at a time, so a run never holds more than one.
 */
internal object WhiteArtScenes {
    const val W = 720

    private fun grey(v: Int) = Strip.rgb(v, v, v)

    private val makers: List<() -> Scene> = listOf(
        // plain gutters: what must still go dark
        ::plainGutters,
        ::gutterBalloonsNarration,
        ::narrowPanels,
        ::uniformInsetArt,
        ::barsPlain,
        // white shut in by a border or an outline: what must stay white
        { boxed("boxed-inset60-b1-k0", 201, inset = 60, border = 1, grey = 0, note = "the confirmed case: uniform white margins, 1 px black border, white interior above art") },
        { boxed("boxed-inset60-b2-g90", 202, inset = 60, border = 2, grey = 90, note = "as above, 2 px dark-grey border") },
        { boxed("boxed-inset60-b3-g160", 203, inset = 60, border = 3, grey = 160, note = "as above, 3 px mid-grey border") },
        { boxed("boxed-inset0-b1-k0", 204, inset = 0, border = 1, grey = 0, note = "panels flush with the strip's edges, 1 px border, white page") },
        { boxed("boxed-inset40-b2-k0-grit245", 205, inset = 40, border = 2, grey = 0, paper = 245, grain = 3, note = "inset 40, off-white grainy paper with soft edges") },
        { boxed("boxed-inset120-b1-g160", 206, inset = 120, border = 1, grey = 160, note = "deep inset 120, 1 px light-grey border") },
        { boxed("boxed-bars120-inset0-b1-k0", 207, inset = 0, border = 1, grey = 0, bars = 120, note = "black bars beside the strip, panels flush with them, 1 px border") },
        { boxed("boxed-bars120-inset20-b2-g90", 208, inset = 20, border = 2, grey = 90, bars = 120, note = "black bars, white page margin of 20 between bar and panel, 2 px grey border") },
        { boxed("boxed-bars100-inset0-b2-g160-grit248", 209, inset = 0, border = 2, grey = 160, bars = 100, paper = 248, grain = 4, note = "black bars, 2 px light border flush with them, grainy off-white") },
        ::tallWhitePanel,
        ::whiteSky,
        ::shirtAndHood,
        ::lineArt,
        ::snow,
        ::speedLinesBoxed,
        ::speedLinesRadial,
        { boxed("broken-border-3px-inset60-b2", 210, inset = 60, border = 2, grey = 0, breakPx = 3, note = "border with 3 px breaks every 150 px: narrower than the seal") },
        { boxed("antialiased-border-inset60", 211, inset = 60, border = 5, grey = 0, profile = intArrayOf(228, 150, 40, 150, 228), note = "1 px black border anti-aliased either side (228,150,40,150,228)") },
        ::toneFadeBoxed,
        ::sideBySide,
        ::whiteGroundFigure,
        ::tintedSkyOpenEdge,
        // white of the art that runs into a gutter through white: the known limit, measured
        { boxed("broken-border-10px-inset60-b2", 212, inset = 60, border = 2, grey = 0, breakPx = 10, note = "border with 10 px breaks: the interior is joined to the margin by white") },
        ::borderlessFigure,
        ::toneFadeToGutter,
        ::speedLinesOpen,
    )

    val count: Int get() = makers.size

    fun all(): Sequence<Scene> = makers.asSequence().map { it() }

    // ---- plain gutters -------------------------------------------------------------------

    private fun plainGutters(): Scene {
        val b = SceneBuilder("plain-gutters", 3600, 101)
        b.panels(0, intArrayOf(640, 600, 700), intArrayOf(320, 260, 380)) { _, y0, y1, _ ->
            b.s.art(0, y0, W, y1); b.s.rules(0, y0, W, y1)
        }
        return b.build("full-width art, plain white gutters: the control", plainGutters = true)
    }

    private fun gutterBalloonsNarration(): Scene {
        val b = SceneBuilder("gutter-balloons-narration", 3800, 102)
        b.panels(0, intArrayOf(560, 520, 600), intArrayOf(520, 460, 560)) { k, y0, y1, gapEnd ->
            b.s.art(0, y0, W, y1); b.s.rules(0, y0, W, y1)
            val mid = (y1 + gapEnd) / 2
            if (gapEnd - y1 < 400) return@panels
            when (k % 3) {
                0 -> b.balloon(360, mid, 230, 90)
                1 -> b.narration(100, mid - 60, 620, mid + 60)
                else -> {
                    b.balloon(200, mid - 70, 150, 70)
                    b.balloon(520, mid + 60, 150, 70)
                }
            }
        }
        return b.build("plain gutters holding balloons (protected insides) and narration", plainGutters = true)
    }

    private fun narrowPanels(): Scene {
        val b = SceneBuilder("narrow-panels", 3600, 103)
        b.panels(200, intArrayOf(700, 640, 760), intArrayOf(260, 300, 240)) { k, y0, y1, _ ->
            val (x0, x1) = when (k % 3) {
                0 -> 120 to W
                1 -> 0 to 600
                else -> 120 to 600
            }
            b.s.art(x0, y0, x1, y1); b.s.rules(x0, y0, x1, y1)
        }
        return b.build("panels narrower than the strip: the white beside them is gutter", plainGutters = true)
    }

    private fun uniformInsetArt(): Scene {
        val b = SceneBuilder("uniform-inset60-art", 3800, 104)
        b.panels(160, intArrayOf(800, 700, 900), intArrayOf(240, 280, 220)) { _, y0, y1, _ ->
            b.s.art(60, y0, 660, y1)
            b.s.box(60, y0, 660, y1, 2)
        }
        return b.build("every panel inset 60 with a 2 px border and art to its edges: margins are uniform columns of gutter", plainGutters = true)
    }

    private fun barsPlain(): Scene {
        val b = SceneBuilder("bars-plain", 3800, 105)
        b.s.solid(0, 0, 120, b.s.h, Strip.BLACK)
        b.s.solid(600, 0, W, b.s.h, Strip.BLACK)
        b.panels(100, intArrayOf(700, 640), intArrayOf(420, 380)) { k, y0, y1, gapEnd ->
            b.s.art(120, y0, 600, y1); b.s.rules(120, y0, 600, y1)
            val mid = (y1 + gapEnd) / 2
            if (gapEnd - y1 < 300) return@panels
            if (k % 2 == 0) b.balloon(360, mid, 180, 70) else b.narration(150, mid - 40, 570, mid + 40)
        }
        return b.build("black bars beside a 480 px strip, plain gutters with a balloon or narration", plainGutters = true)
    }

    // ---- white shut in by a border -------------------------------------------------------

    /**
     * Panels with a [border]-pixel outline of grey [grey] (or the grey levels of [profile],
     * outermost first), [inset] from the strip's edges, the strip between black [bars] of
     * that width if any. [inside] paints each interior; by default the top three fifths are
     * white and art fills the rest. Breaks of [breakPx] are cut into the border every 150 px;
     * from five pixels on they let the gutter's white in, and the interior is [Truth.OPEN].
     */
    private fun boxed(
        name: String,
        seed: Long,
        inset: Int,
        border: Int,
        grey: Int,
        note: String,
        bars: Int = 0,
        paper: Int = 255,
        grain: Int = 0,
        breakPx: Int = 0,
        profile: IntArray? = null,
        heights: IntArray = intArrayOf(900, 760, 1000),
        gaps: IntArray = intArrayOf(260, 320, 220),
        plainGutters: Boolean = true,
        inside: SceneBuilder.(k: Int, x0: Int, y0: Int, x1: Int, y1: Int) -> Unit = { _, x0, y0, x1, y1 -> whiteAboveArt(this, x0, y0, x1, y1) },
    ): Scene {
        val b = SceneBuilder(name, 3800, seed)
        val h = b.s.h
        if (bars > 0) {
            b.s.solid(0, 0, bars, h, Strip.BLACK)
            b.s.solid(W - bars, 0, W, h, Strip.BLACK)
        }
        val x0 = bars + inset
        val x1 = W - bars - inset
        val interior = if (breakPx >= 5) Truth.OPEN else Truth.PROTECTED
        b.panels(180, heights, gaps) { k, y0, y1, _ ->
            val ix0 = x0 + border
            val iy0 = y0 + border
            val ix1 = x1 - border
            val iy1 = y1 - border
            b.mark(ix0, iy0, ix1, iy1, interior)
            b.clipped(ix0, iy0, ix1, iy1) { b.inside(k, ix0, iy0, ix1, iy1) }
            if (profile != null) {
                for ((i, v) in profile.withIndex()) b.s.box(x0 + i, y0 + i, x1 - i, y1 - i, 1, grey(v))
            } else {
                b.s.box(x0, y0, x1, y1, border, grey(grey))
            }
            if (breakPx > 0) {
                var x = x0 + 100
                while (x + breakPx < x1 - 20) {
                    b.s.solid(x, y0, x + breakPx, y0 + border, Strip.WHITE); b.mark(x - 2, y0 - 2, x + breakPx + 2, y0 + border + 2, Truth.DONTCARE)
                    b.s.solid(x, y1 - border, x + breakPx, y1, Strip.WHITE); b.mark(x - 2, y1 - border - 2, x + breakPx + 2, y1 + 2, Truth.DONTCARE)
                    x += 150
                }
                var y = y0 + 100
                while (y + breakPx < y1 - 20) {
                    b.s.solid(x0, y, x0 + border, y + breakPx, Strip.WHITE); b.mark(x0 - 2, y - 2, x0 + border + 2, y + breakPx + 2, Truth.DONTCARE)
                    b.s.solid(x1 - border, y, x1, y + breakPx, Strip.WHITE); b.mark(x1 - border - 2, y - 2, x1 + 2, y + breakPx + 2, Truth.DONTCARE)
                    y += 150
                }
            }
        }
        if (paper != 255 || grain > 0) gritty(b.s, paper, grain, soft = grain > 0, seed = seed)
        return b.build(note, plainGutters)
    }

    private fun whiteAboveArt(b: SceneBuilder, x0: Int, y0: Int, x1: Int, y1: Int) {
        b.s.art(x0, y0 + (y1 - y0) * 3 / 5, x1, y1)
    }

    private fun tallWhitePanel(): Scene = boxed(
        "tall-white-panel-inset40-b2", 213, inset = 40, border = 2, grey = 0,
        heights = intArrayOf(2300, 700), gaps = intArrayOf(300, 260), plainGutters = false,
        note = "a white panel 2300 rows tall, a figure in it: whole frames inside the white, framed only left and right",
    ) { k, x0, y0, x1, y1 ->
        if (k % 2 == 0) {
            figure((x0 + x1) / 2, y0 + 700)
            s.art(x0, y1 - 250, x1, y1)
        } else {
            whiteAboveArt(this, x0, y0, x1, y1)
        }
    }

    private fun whiteSky(): Scene = boxed(
        "white-sky-inset0-b3-g90", 214, inset = 0, border = 3, grey = 90,
        note = "a white sky with outlined clouds and birds over a jagged horizon, full-width panels with a 3 px grey border",
    ) { _, x0, y0, x1, y1 ->
        val ih = y1 - y0
        val ground = y0 + ih * 55 / 100
        s.art(x0, ground - 60, x1, y1)
        val horizon = IntArray(x1 - x0) { i -> ground + (30 * sin((x0 + i) * 0.03) + 15 * sin((x0 + i) * 0.11)).toInt() }
        for (i in horizon.indices) s.solid(x0 + i, ground - 60, x0 + i + 1, horizon[i], Strip.WHITE)
        for (i in 1 until horizon.size) s.line((x0 + i - 1).toDouble(), horizon[i - 1].toDouble(), (x0 + i).toDouble(), horizon[i].toDouble(), 2.0, grey(40))
        repeat(4) {
            val cx = x0 + 80 + rnd.nextInt(x1 - x0 - 160)
            val cy = y0 + 60 + rnd.nextInt(maxOf(1, ih * 35 / 100))
            val rx = 50 + rnd.nextInt(60)
            val ry = 20 + rnd.nextInt(20)
            s.ellipse(cx, cy, rx + 2, ry + 2, grey(90))
            s.ellipse(cx, cy, rx, ry, Strip.WHITE)
        }
        repeat(6) {
            val bx = x0 + 40 + rnd.nextInt(x1 - x0 - 80).toDouble()
            val by = y0 + 40 + rnd.nextInt(maxOf(1, ih * 40 / 100)).toDouble()
            s.line(bx - 9, by - 4, bx, by, 2.0)
            s.line(bx, by, bx + 9, by - 4, 2.0)
        }
    }

    private fun shirtAndHood(): Scene = boxed(
        "white-shirt-hood-inset30-b2", 215, inset = 30, border = 2, grey = 0,
        note = "art-filled panels, a white hood outlined against the left border (in every other panel wider than 40% of the column), a white shirt cut by the bottom border",
    ) { k, x0, y0, x1, y1 ->
        val ih = y1 - y0
        s.art(x0, y0, x1, y1)
        // a hood, its white running into the panel's left border; every other one a cloak, wide
        // enough for a run of it to pass for a gutter that art has cut into
        val hx = x0 + 70
        val hy = y0 + ih * 45 / 100
        val hr = if (k % 2 == 0) 170 else 300
        s.ellipse(hx, hy, hr + 3, 203, Strip.BLACK)
        s.ellipse(hx, hy, hr, 200, Strip.WHITE)
        s.ellipse(hx + 30, hy + 20, 73, 88, Strip.BLACK)
        s.ellipse(hx + 30, hy + 20, 70, 85, SceneBuilder.SKIN)
        s.line((hx - 60).toDouble(), (hy - 150).toDouble(), (hx + 100).toDouble(), (hy - 90).toDouble(), 2.0)
        // a white shirt, cut by the bottom border
        val sx = x0 + (x1 - x0) * 65 / 100
        s.ellipse(sx, y1 - 30, 153, 163, Strip.BLACK)
        s.ellipse(sx, y1 - 30, 150, 160, Strip.WHITE)
        s.line((sx - 50).toDouble(), (y1 - 180).toDouble(), sx.toDouble(), (y1 - 110).toDouble(), 3.0)
        s.line((sx + 50).toDouble(), (y1 - 180).toDouble(), sx.toDouble(), (y1 - 110).toDouble(), 3.0)
    }

    private fun lineArt(): Scene = boxed(
        "lineart-inset40-b2-g90", 216, inset = 40, border = 2, grey = 90,
        note = "line-art on a white ground: strokes all over the interior, some meeting the border",
    ) { _, x0, y0, x1, y1 ->
        val ih = y1 - y0
        val iw = x1 - x0
        val low = y0 + ih * 3 / 4
        repeat(40) {
            val xa = x0 + rnd.nextInt(iw).toDouble()
            val ya = y0 + rnd.nextInt(low - y0).toDouble()
            val ang = rnd.nextDouble() * PI
            val len = 40 + rnd.nextInt(160)
            s.line(xa, ya, xa + len * cos(ang), ya + len * sin(ang), 1.5 + rnd.nextDouble() * 1.5)
        }
        repeat(6) {
            val ya = y0 + rnd.nextInt(low - y0).toDouble()
            val fromLeft = rnd.nextBoolean()
            val xa = if (fromLeft) x0.toDouble() else (x1 - 1).toDouble()
            s.line(xa, ya, x0 + iw / 2.0, ya + rnd.nextInt(120) - 60, 2.0)
        }
        val cx = x0 + iw / 2
        val cy = y0 + ih / 3
        s.ellipse(cx, cy, 82, 82, Strip.BLACK)
        s.ellipse(cx, cy, 80, 80, Strip.WHITE)
        s.art(x0, low, x1, y1)
    }

    private fun snow(): Scene = boxed(
        "snow-inset40-b2-off245", 217, inset = 40, border = 2, grey = 0, paper = 245, grain = 2,
        note = "a snowy scene: white sky and white ground reaching the border, pale-blue shadows, dark trees, off-white paper",
    ) { _, x0, y0, x1, y1 ->
        val ih = y1 - y0
        val treeTop = y0 + ih * 35 / 100
        val treeBase = y0 + ih * 70 / 100
        s.solid(x0, treeBase - 40, x1, treeBase, Strip.rgb(200, 212, 232))
        var tx = x0 + 30
        while (tx < x1 - 30) {
            val top = treeTop + rnd.nextInt(60)
            for (y in top until treeBase) {
                val half = (y - top) * 45 / (treeBase - top)
                s.solid(tx - half, y, tx + half + 1, y + 1, Strip.rgb(28, 64, 44))
            }
            // snow on the branches: specks of paper on the dark
            repeat(10) { s.ellipse(tx - 20 + rnd.nextInt(40), top + 20 + rnd.nextInt(treeBase - top - 30), 2, 2, Strip.WHITE) }
            tx += 70 + rnd.nextInt(40)
        }
        repeat(5) {
            s.ellipse(x0 + rnd.nextInt(x1 - x0), treeBase + 40 + rnd.nextInt(maxOf(1, y1 - treeBase - 80)), 60 + rnd.nextInt(60), 10 + rnd.nextInt(10), Strip.rgb(205, 218, 236))
        }
        repeat(12) { s.ellipse(x0 + 40 + rnd.nextInt(x1 - x0 - 80), treeBase + 30 + rnd.nextInt(maxOf(1, y1 - treeBase - 60)), 4, 3, Strip.rgb(70, 70, 80)) }
    }

    private fun speedLinesBoxed(): Scene = boxed(
        "speed-lines-inset60-b1", 218, inset = 60, border = 1, grey = 0,
        note = "horizontal speed lines 1-2 px thick, 3-12 rows apart, some from border to border, in a 1 px border inset 60",
    ) { _, x0, y0, x1, y1 ->
        val iw = x1 - x0
        val end = y0 + (y1 - y0) * 7 / 10
        var y = y0 + 20
        while (y < end) {
            val t = 1 + rnd.nextInt(2)
            val full = rnd.nextInt(3) == 0
            val xa = if (full) x0 else x0 + rnd.nextInt(iw / 3)
            val xb = if (full) x1 else min(x1, xa + iw / 3 + rnd.nextInt(iw / 2))
            s.solid(xa, y, xb, y + t, Strip.BLACK)
            y += t + 3 + rnd.nextInt(10)
        }
        s.art(x0, end + 40, x1, y1)
    }

    private fun speedLinesRadial(): Scene = boxed(
        "speed-lines-radial-bars100-b2", 219, inset = 0, border = 2, grey = 0, bars = 100,
        note = "focus lines from the border towards a white centre, panels flush with black bars",
    ) { _, x0, y0, x1, y1 ->
        val cx = (x0 + x1) / 2.0
        val cy = (y0 + y1) / 2.0
        repeat(90) { i ->
            val ang = 2 * PI * i / 90 + rnd.nextDouble() * 0.03
            val dx = cos(ang)
            val dy = sin(ang)
            // where the ray from the centre leaves the interior
            val tx = if (dx > 0) (x1 - 1 - cx) / dx else if (dx < 0) (x0 - cx) / dx else Double.MAX_VALUE
            val ty = if (dy > 0) (y1 - 1 - cy) / dy else if (dy < 0) (y0 - cy) / dy else Double.MAX_VALUE
            val far = min(tx, ty)
            val near = 120.0 + rnd.nextInt(80)
            if (far > near) s.line(cx + dx * near, cy + dy * near, cx + dx * far, cy + dy * far, 1.5 + rnd.nextDouble())
        }
    }

    private fun toneFadeBoxed(): Scene = boxed(
        "tone-fade-inset40-b2", 220, inset = 40, border = 2, grey = 0,
        note = "inside a 2 px border: a screentone thinning out to pure white, or art fading to white, towards the top",
    ) { k, x0, y0, x1, y1 ->
        val ih = y1 - y0
        val a = y0 + ih * 35 / 100
        val b = y0 + ih * 70 / 100
        if (k % 2 == 0) {
            s.toneFadingUp(x0, a, x1, b)
            s.art(x0, b, x1, y1)
        } else {
            s.art(x0, a, x1, y1)
            s.fadeToWhite(x0, a, x1, b, whiteAtTop = true)
        }
    }

    private fun sideBySide(): Scene {
        val b = SceneBuilder("side-by-side-b2", 3800, 221)
        val cols = arrayOf(16 to 352, 368 to 704)
        b.panels(160, intArrayOf(800, 900, 760), intArrayOf(240, 280, 260)) { k, y0, y1, _ ->
            for ((c, col) in cols.withIndex()) {
                val (x0, x1) = col
                b.s.box(x0, y0, x1, y1, 2)
                b.mark(x0 + 2, y0 + 2, x1 - 2, y1 - 2, Truth.PROTECTED)
                val ih = y1 - y0
                if ((k + c) % 2 == 0) b.s.art(x0 + 2, y0 + ih * 3 / 5, x1 - 2, y1 - 2) else b.s.art(x0 + 2, y0 + 2, x1 - 2, y0 + ih * 2 / 5)
            }
        }
        return b.build("two white-interior panels side by side, a 16 px gutter between them and 16 px margins", plainGutters = false)
    }

    private fun whiteGroundFigure(): Scene = boxed(
        "white-ground-figure-inset40-b1-g160", 222, inset = 40, border = 1, grey = 160,
        note = "a panel whose ground is white, a figure standing in it, 1 px light-grey border",
    ) { k, x0, y0, x1, y1 ->
        if (k % 2 == 0) figure((x0 + x1) / 2 + 40, y0 + 120, 1.2) else whiteAboveArt(this, x0, y0, x1, y1)
    }

    /**
     * The reader's own failure, drawn: a sky that pales from light blue to a tinted near-white
     * at a panel's bottom edge, which has no border, straight onto a pure-white gutter. Its
     * lightest rows are paper by the 240 rule, and joined to the gutter; only their tint — the
     * sky is never the gutter's flat white — tells them apart. Panels alternate their insets,
     * so no column of the screen is uniform.
     */
    private fun tintedSkyOpenEdge(): Scene {
        val b = SceneBuilder("tinted-sky-open-edge", 3800, 226)
        b.panels(150, intArrayOf(800, 760, 860), intArrayOf(300, 280, 320)) { k, y0, y1, _ ->
            val (x0, x1) = if (k % 2 == 0) 20 to 600 else 120 to 700
            b.mark(x0, y0, x1, y1, Truth.PROTECTED)
            for (y in y0 until y1) {
                val t = (y - y0).toDouble() / (y1 - y0 - 1)
                for (x in x0 until x1) {
                    val n = b.rnd.nextInt(3) - 1
                    b.s.px[y * W + x] = Strip.rgb(
                        (200 + 47 * t + n).toInt().coerceIn(0, 254),
                        (225 + 26 * t + n).toInt().coerceIn(0, 254),
                        (240 + 5 * t + n).toInt().coerceIn(0, 254),
                    )
                }
            }
            // a figure's mass in the lower left, standing on the open edge
            b.s.art(x0, y0 + (y1 - y0) / 2, x0 + (x1 - x0) * 45 / 100, y1)
            // a border at the top and the sides; the bottom is open
            b.s.solid(x0, y0, x1, y0 + 2, Strip.BLACK)
            b.s.solid(x0, y0, x0 + 2, y1, Strip.BLACK)
            b.s.solid(x1 - 2, y0, x1, y1, Strip.BLACK)
        }
        return b.build("a light-blue sky paling to a tinted near-white at a borderless bottom edge, onto a pure-white gutter (as on the reader's own strip)", plainGutters = true)
    }

    // ---- white joined to a gutter by white: the known limit ------------------------------

    private fun borderlessFigure(): Scene {
        val b = SceneBuilder("borderless-figure", 3800, 223)
        b.panels(0, intArrayOf(560, 600), intArrayOf(1100, 960)) { k, y0, y1, gapEnd ->
            b.s.art(0, y0, W, y1); b.s.rules(0, y0, W, y1)
            if (gapEnd - y1 < 800) return@panels
            val cx = if (k % 2 == 0) 300 else 440
            val top = y1 + 200
            // the ground round the figure: out to 16 px it is the figure's, out to 60 px anyone's
            b.mark(cx - 200, top - 60, cx + 200, top + 520, Truth.DONTCARE)
            b.mark(cx - 156, top - 16, cx + 156, top + 476, Truth.OPEN)
            b.figure(cx, top)
        }
        return b.build("a figure on the white with no border, between full-width panels", plainGutters = false)
    }

    private fun toneFadeToGutter(): Scene {
        val b = SceneBuilder("tone-fade-to-gutter", 3800, 224)
        b.panels(100, intArrayOf(760, 700), intArrayOf(300, 340)) { k, y0, y1, _ ->
            b.mark(0, y0, W, y1, Truth.OPEN)
            if (k % 2 == 0) {
                b.s.art(0, y0, W, y1)
                b.s.fadeToWhite(0, y1 - 180, W, y1, whiteAtTop = false)
                b.s.fadeToWhite(0, y0, W, y0 + 120, whiteAtTop = true)
            } else {
                b.s.toneFadingUp(0, y0, W, y0 + 260)
                b.s.art(0, y0 + 260, W, y1); b.s.rules(0, y1 - 6, W, y1, 6)
            }
        }
        return b.build("art fading to pure white, and a screentone thinning out, straight into the gutter", plainGutters = false)
    }

    private fun speedLinesOpen(): Scene {
        val b = SceneBuilder("speed-lines-open", 3800, 225)
        b.panels(0, intArrayOf(600, 560), intArrayOf(1200, 1100)) { _, y0, y1, gapEnd ->
            b.s.art(0, y0, W, y1); b.s.rules(0, y0, W, y1)
            if (gapEnd - y1 < 900) return@panels
            val a = y1 + 150
            val z = gapEnd - 150
            b.mark(0, a - 24, W, z + 24, Truth.DONTCARE)
            b.mark(0, a + 24, W, z - 24, Truth.OPEN)
            var y = a
            while (y < z) {
                val t = 1 + b.rnd.nextInt(2)
                val full = b.rnd.nextInt(3) != 0
                val xa = if (full) 0 else b.rnd.nextInt(W / 3)
                val xb = if (full) W else min(W, xa + W / 3 + b.rnd.nextInt(W / 2))
                b.s.solid(xa, y, xb, y + t, Strip.BLACK)
                y += t + 2 + b.rnd.nextInt(12)
            }
        }
        return b.build("speed lines on the white running off both edges of the strip, no border", plainGutters = false)
    }
}
