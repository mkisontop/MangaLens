package app.mangalens.gaps

/**
 * Where the pixels of a frame come from. Android supplies the capture buffer or a
 * bitmap; tests supply an array. A pixel is an int whose low 24 bits are the three
 * colour channels — which of red and blue comes first never matters here, because
 * every test the finder makes is symmetric in them.
 */
interface PixelSource {
    val width: Int
    val height: Int

    /** Copies row [y] — [width] pixels — into [dst]. */
    fun readRow(y: Int, dst: IntArray)

    fun pixel(x: Int, y: Int): Int
}

/** A [PixelSource] over a plain int array, row-major, [width] pixels per row. */
class ArrayPixels(override val width: Int, override val height: Int, val data: IntArray) : PixelSource {
    override fun readRow(y: Int, dst: IntArray) = System.arraycopy(data, y * width, dst, 0, width)
    override fun pixel(x: Int, y: Int): Int = data[y * width + x]
}

/**
 * How dark the gaps are painted, and so what paper looks like once it is painted.
 *
 * The shade is deliberately not opaque. Painted at [alpha] over a white gap it leaves
 * `(1 - alpha) * 255` of the paper showing through — a dark grey, not a hole — and that
 * is what keeps the whole scheme honest: the capture sees the page *through* the shade.
 * Paper under the shade has a known, narrow signature, so a frame with our own shade on
 * it reads exactly like the frame without, and anything that is *not* paper — art the
 * shade has strayed onto — shows up as the darker thing it is, instead of vanishing into
 * a flat black that hides its own mistakes.
 */
enum class ShadeLevel(val alpha: Float) {
    DIM(0.80f),
    DARK(0.90f),
    BLACK(0.95f),
}

/**
 * The numbers derived from a [ShadeLevel]; shared by the finder, the unshading and the view.
 *
 * [observedComposite] is what paper has actually been seen to look like under the shade on
 * this device, when that is not what the arithmetic says: a compositor that blends in linear
 * light, not in the gamma-encoded values every other one uses, leaves a much lighter grey.
 */
class ShadeStyle(
    val level: ShadeLevel = ShadeLevel.DARK,
    val observedComposite: Int? = null,
    /**
     * The darkest the window the shade is drawn in can show: an overlay that lets touches
     * through is capped by Android, and the shade's alpha is the level's or this, the lower.
     */
    val cap: Float = 1f,
) {
    val alpha: Float = minOf(level.alpha, cap)

    /** The overlay colour's alpha channel, 0..255. */
    val alpha255: Int = Math.round(alpha * 255f)

    /**
     * Channel values paper (240..255) takes on under the shade, with a little slack
     * for the compositor's rounding. A pixel with all three channels in this window
     * is paper that we have already painted.
     */
    val sigLo: Int = observedComposite?.let { it - OBSERVED_SLACK } ?: (((1f - alpha) * PAPER_MIN).toInt() - 2)
    val sigHi: Int = observedComposite?.let { it + OBSERVED_SLACK }
        ?: (Math.ceil(((1f - alpha) * 255f).toDouble()).toInt() + 2)

    /** What pure white becomes under the shade. */
    val whiteComposite: Int = observedComposite ?: Math.round((1f - alpha) * 255f)

    /**
     * The lightest value a pixel can have, unshaded, and still read as paper once the shade is
     * on it: the shaded-paper window opens at [sigLo], which is what this value becomes. Pixels
     * lighter than it are the faintest ringing and read as paper through the shade; pixels below
     * it, and above ink, are faint marks — hairlines, a light watermark — and read as ink.
     */
    val faintLight: Int = Math.ceil(255.0 * sigLo / maxOf(1, whiteComposite)).toInt().coerceIn(192, PAPER_MIN)

    fun isShadedPaper(p: Int): Boolean {
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        return r in sigLo..sigHi && g in sigLo..sigHi && b in sigLo..sigHi
    }

    /** The same style, with paper under the shade now known to look like [composite]. */
    fun observed(composite: Int) = ShadeStyle(level, composite, cap)

    companion object {
        /** Darkest channel value still called paper. All three channels at or above it is `0xF0` in the top nibble. */
        const val PAPER_MIN = 240

        private const val OBSERVED_SLACK = 4
    }
}

/**
 * A frame as the finder sees it: which pixels are paper and which are ink, at a
 * resolution of one plane pixel per [step] frame pixels.
 *
 * *Paper* is white, or white seen through our own shade. *Ink* is anything clearly not
 * light — lettering, outlines, art. What is neither — the pale in-between of
 * anti-aliased edges, compression ringing, light tints — is left alone: never painted,
 * and never mistaken for lettering that needs protecting.
 */
class Planes(
    val frameW: Int,
    val frameH: Int,
    val step: Int,
    val paper: BitPlane,
    val ink: BitPlane,
    /** The plane rows [validY0, validY1) that were classified; the rest are the ignored bars. */
    val validY0: Int = 0,
    val validY1: Int = paper.h,
    /**
     * The darkest channel of every pixel, row-major — kept only by the exact pass, which
     * needs it to follow an anti-aliased edge out of the gutter. Null at half resolution.
     */
    val lum: ByteArray? = null,
    /** The top of the grey paper takes on under the shade: dark pixels up to this are candidates for a shaded edge. */
    val shadedHi: Int = 28,
    /**
     * Pixels that are neither paper nor ink but not near-paper either: some channel in 192..231,
     * the grey of a hairline or of a light watermark. Under the shade they read as ink, so they
     * must be told apart from the faintest ringing, which reads as paper under it. Null when the
     * builder was not asked to keep them.
     */
    val faint: BitPlane? = null,
    /** MangaLens's own controls, `x0, y0, x1, y1` in frame pixels: read as ink, and as no evidence about the page. */
    val keepOut: List<IntArray> = emptyList(),
    /**
     * Per plane row, whether it is one flat colour from edge to edge, MangaLens's controls aside:
     * the padding of a browser's or site's bar, which spans the screen, black bars and all.
     */
    val flat: BooleanArray? = null,
) {
    val w: Int get() = paper.w
    val h: Int get() = paper.h
}

object PlaneBuilder {

    /**
     * Classifies every [step]-th pixel of every [step]-th row of [src].
     *
     * Paper is `(p and 0xF0F0F0) == 0xF0F0F0` — every channel in 240..255 — which is one
     * mask-and-compare per pixel and, because all three channels sit in a 16-wide band, a
     * chroma bound for free. Ink is "some channel below 192", `(p and 0xC0C0C0) != 0xC0C0C0`.
     * Rows within [ignoreTop] / [ignoreBottom] frame rows of the edges are left unclassified:
     * the browser's own bars are not the manhwa's gaps. Inside the [keepOut] rectangles
     * (`x0, y0, x1, y1` in frame pixels) — MangaLens's own controls, over the page — every
     * pixel is ink: they are not the page, and are never to be shaded, nor to cut a black bar
     * beside the strip in two.
     */
    fun build(
        src: PixelSource,
        step: Int,
        style: ShadeStyle,
        ignoreTop: Int = 0,
        ignoreBottom: Int = 0,
        keepOut: List<IntArray> = emptyList(),
    ): Planes {
        val fw = src.width
        val fh = src.height
        val pw = (fw + step - 1) / step
        val ph = (fh + step - 1) / step
        val paper = BitPlane(pw, ph)
        val ink = BitPlane(pw, ph)
        val faint = BitPlane(pw, ph)
        val lum = if (step == 1) ByteArray(pw * ph) else null
        val row = IntArray(fw)
        val wpr = paper.wpr
        val lo = style.sigLo
        val hi = style.sigHi
        val faintLight = style.faintLight
        val flat = BooleanArray(ph)
        for (py in 0 until ph) {
            val y = py * step
            if (y < ignoreTop || y >= fh - ignoreBottom) continue
            src.readRow(y, row)
            flat[py] = flatRow(row, fw, step, y, keepOut)
            val base = py * wpr
            for (k in 0 until wpr) {
                var pv = 0L
                var iv = 0L
                var fv = 0L
                val x0 = k shl 6
                val n = minOf(64, pw - x0)
                var x = x0 * step
                for (i in 0 until n) {
                    val p = row[x]
                    if (lum != null) {
                        val r = (p ushr 16) and 0xFF
                        val g = (p ushr 8) and 0xFF
                        val b = p and 0xFF
                        lum[py * pw + x0 + i] = minOf(r, g, b).toByte()
                    }
                    x += step
                    if ((p and 0xF0F0F0) == 0xF0F0F0) {
                        pv = pv or (1L shl i)
                    } else if ((p and 0xC0C0C0) != 0xC0C0C0) {
                        // Dark: either paper under our own shade or ink. Light pixels never get
                        // here, so the window compares are only paid for on dark art.
                        if (inWindow(p, lo, hi)) {
                            pv = pv or (1L shl i)
                        } else {
                            iv = iv or (1L shl i)
                        }
                    } else if (((p ushr 16) and 0xFF) < faintLight || ((p ushr 8) and 0xFF) < faintLight || (p and 0xFF) < faintLight) {
                        // light, but not light enough to pass for paper under the shade
                        fv = fv or (1L shl i)
                    }
                }
                paper.bits[base + k] = pv
                ink.bits[base + k] = iv
                faint.bits[base + k] = fv
            }
        }
        for (r in keepOut) {
            // the plane pixels whose sample falls inside the rectangle
            val x0 = (r[0].coerceAtLeast(0) + step - 1) / step
            val x1 = (r[2].coerceAtMost(fw) + step - 1) / step
            val ry0 = (r[1].coerceAtLeast(0) + step - 1) / step
            val ry1 = (r[3].coerceAtMost(fh) + step - 1) / step
            for (py in ry0 until minOf(ry1, ph)) {
                val y = py * step
                if (y < ignoreTop || y >= fh - ignoreBottom) continue
                paper.clearRun(py, x0, x1)
                faint.clearRun(py, x0, x1)
                ink.setRun(py, x0, x1)
                if (lum != null) for (x in x0 until minOf(x1, pw)) lum[py * pw + x] = 0
            }
        }
        val y0 = ((ignoreTop + step - 1) / step).coerceIn(0, ph)
        val y1 = ((fh - ignoreBottom + step - 1) / step).coerceIn(y0, ph)
        return Planes(fw, fh, step, paper, ink, y0, y1, lum, style.sigHi, faint, keepOut, flat)
    }

    /** Whether every [step]-th pixel of frame [row] [y], those under [keepOut] aside, is within [FLAT_RANGE] of one colour. */
    private fun flatRow(row: IntArray, fw: Int, step: Int, y: Int, keepOut: List<IntArray>): Boolean {
        var rLo = 255; var rHi = 0; var gLo = 255; var gHi = 0; var bLo = 255; var bHi = 0
        var x = 0
        while (x < fw) {
            var skip = false
            for (k in keepOut) if (y >= k[1] && y < k[3] && x >= k[0] && x < k[2]) { skip = true; break }
            if (!skip) {
                val p = row[x]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                if (r < rLo) rLo = r
                if (r > rHi) rHi = r
                if (g < gLo) gLo = g
                if (g > gHi) gHi = g
                if (b < bLo) bLo = b
                if (b > bHi) bHi = b
                if (rHi - rLo > FLAT_RANGE || gHi - gLo > FLAT_RANGE || bHi - bLo > FLAT_RANGE) return false
            }
            x += step
        }
        return true
    }

    /** How far apart, per channel, the pixels of a flat row may be: compression noise, a faint gradient. */
    private const val FLAT_RANGE = 20

    private fun inWindow(p: Int, lo: Int, hi: Int): Boolean {
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        return r in lo..hi && g in lo..hi && b in lo..hi
    }
}
