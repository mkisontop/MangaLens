package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import app.mangalens.translate.ItemKind
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The lettering of one free-standing piece of text, erased.
 *
 * [patch] is a [rect]-sized ARGB bitmap: opaque where lettering was replaced
 * with reconstructed background, transparent everywhere else, so drawn over
 * the page at [rect] it removes the text and leaves the art untouched.
 * [mask] is [rect]-sized, row-major, true where the patch replaces a pixel.
 */
class Erasure(
    val rect: Rect,
    val patch: Bitmap,
    val mask: BooleanArray,
    /** True when the lettering sat on a flat background, where the erasure is exact. */
    val flat: Boolean,
    /** Dominant background colour around the lettering (opaque ARGB). */
    val background: Int,
    /** Fill colour of the erased lettering (opaque ARGB). */
    val textColor: Int,
    /** Colour of the erased lettering's outline, or null when it had none. */
    val outlineColor: Int?,
    /**
     * How busy the art under the lettering is, 0 (flat paper) to 1 (dense
     * texture): how far a local reconstruction is from what an artist would
     * have drawn there, and so how much an AI clean-up would add.
     */
    val busy: Float,
)

/**
 * Erases lettering that no balloon holds — narration on the art, side
 * comments, signs, sound effects — pixel by pixel, instead of hiding it
 * under a card.
 *
 * A scanlator cleans such text in two steps, and so does this: find exactly
 * which pixels are lettering, then paint what was behind them. The model's
 * box says where the text is but not which pixels are strokes, so the
 * strokes are found from the pixels, in the first of these ways that holds:
 *
 * - On paper — flat, a gradient, the white heart of a burst — read from a
 *   ring just outside the box, everything that stands clearly off it is
 *   lettering, provided it is of one or two colours (more is art the ring
 *   did not show).
 * - On paper only the box shows — a balloon the detector missed, a white
 *   knockout in a tone — the same, with the box's own dominant colour as
 *   the paper when it lies in one piece around the strokes.
 * - On art, by colour: the colour common inside the box and rare in the
 *   ring is the fill (followed through its shading), a colour hugging it is
 *   its outline, taken only as deep as the outline runs, and a white edge
 *   around that is taken too.
 * - On line art of the lettering's own colour, by weight: strokes too thin
 *   to be the lettering's are opened away.
 *
 * Whatever the way, pieces that mostly lie outside the box, run on out of
 * it, are screentone dots, or are hairlines far thinner and longer than
 * the lettering's strokes (speed lines, the rays of a burst) are art, and
 * stay.
 *
 * The strokes are then filled by push-pull interpolation from the pixels
 * around them — exact on flat paper, smooth through gradients — and on a
 * screentone the dots are continued through the hole, halo and all, so the
 * fill reads as tone rather than a white blotch or a grey smear. What cannot
 * be rebuilt locally (a face under a sound effect) is reported as
 * [Erasure.busy] for [AiCleaner] to redraw.
 *
 * All of it is plain arithmetic over one bulk read of the region: a few
 * milliseconds per item, cheap enough to run for every item as it streams in.
 */
object TextEraser {

    fun erase(
        page: Bitmap,
        box: Rect,
        kind: ItemKind,
        textColor: Int? = null,
        outlineColor: Int? = null,
    ): Erasure? {
        val clip = Rect(box)
        if (!clip.intersect(0, 0, page.width, page.height)) return null
        if (clip.width() < 3 || clip.height() < 3) return null
        val side = min(clip.width(), clip.height())
        val sfx = kind == ItemKind.SFX
        // A sound effect's box is looser and its strokes run wilder.
        val margin = (side * (if (sfx) 0.2f else 0.12f)).roundToInt().coerceIn(3, if (sfx) 24 else 14)
        val ringWidth = (side * 0.12f).roundToInt().coerceIn(4, 14)
        val pad = margin + ringWidth
        val rect = Rect(
            max(0, clip.left - pad),
            max(0, clip.top - pad),
            min(page.width, clip.right + pad),
            min(page.height, clip.bottom + pad),
        )
        val w = rect.width()
        val h = rect.height()
        val px = IntArray(w * h)
        page.getPixels(px, 0, w, rect.left, rect.top, w, h)
        val region = Region(
            w, h,
            clip.left - rect.left, clip.top - rect.top, clip.right - rect.left, clip.bottom - rect.top,
            margin,
        )
        val out = eraseRegion(px, region, sfx, textColor, outlineColor) ?: return null
        val patch = Bitmap.createBitmap(out.patch, w, h, Bitmap.Config.ARGB_8888)
        return Erasure(
            rect, patch, out.mask, out.flat, out.background, out.textColor, out.outlineColor, out.busy,
        )
    }

    /**
     * A work region: [w] x [h] pixels holding the model's box
     * ([boxL], [boxT]) - ([boxR], [boxB]), exclusive, and around it a
     * [margin] for the box's own inaccuracy, then the ring the background is
     * read from.
     */
    internal class Region(
        val w: Int,
        val h: Int,
        val boxL: Int,
        val boxT: Int,
        val boxR: Int,
        val boxB: Int,
        margin: Int,
    ) {
        val sL = max(0, boxL - margin)
        val sT = max(0, boxT - margin)
        val sR = min(w, boxR + margin)
        val sB = min(h, boxB + margin)
        val boxArea = (boxR - boxL) * (boxB - boxT)

        /** Per pixel: [RING], [MARGIN] or [BOX]. */
        val zone = ByteArray(w * h).also { z ->
            for (y in sT until sB) {
                for (x in sL until sR) {
                    z[y * w + x] = if (x in boxL until boxR && y in boxT until boxB) BOX else MARGIN
                }
            }
        }

        /**
         * The sides (bits 1, 2, 4, 8: left, right, top, bottom) where a
         * stroke at [x], [y] runs on into the ring, out of the searched area.
         */
        fun searchEdges(x: Int, y: Int): Int {
            var e = 0
            if (x == sL && sL > 0) e = e or 1
            if (x == sR - 1 && sR < w) e = e or 2
            if (y == sT && sT > 0) e = e or 4
            if (y == sB - 1 && sB < h) e = e or 8
            return e
        }
    }

    internal class Cleaned(
        val patch: IntArray,
        val mask: BooleanArray,
        val flat: Boolean,
        val background: Int,
        val textColor: Int,
        val outlineColor: Int?,
        val busy: Float,
    )

    internal const val RING: Byte = 0
    internal const val MARGIN: Byte = 1
    internal const val BOX: Byte = 2

    /** Share of ring pixels that must sit on the smooth background fit for the ring to count as flat. */
    private const val FLAT_SHARE = 0.8f

    /** A pixel this close to the background fit is plain background. */
    private const val FLAT_RESIDUAL = 20

    /** How far a pixel may stray from a lettering colour and still be that colour. */
    private const val COLOUR_TOL = 48

    /** Share of a lettering's pixels that may be neither its colours nor a blend of them with the paper. */
    private const val MAX_FOREIGN = 0.1f

    /** Share of a fill's edge an outline lies against; art only ever touches a stretch of it. */
    private const val WRAPPED = 0.7f

    /** Lettering covers less of its box than this; more is the art or the paper, mistaken. */
    private const val MAX_COVER = 0.7f

    /** Pyramid level the background fit is read at: 8 px cells, coarser than a stroke or a screentone dot. */
    private const val FIT_LEVEL = 3

    internal fun eraseRegion(
        px: IntArray,
        reg: Region,
        sfx: Boolean,
        textColor: Int?,
        outlineColor: Int?,
    ): Cleaned? {
        val w = reg.w
        val h = reg.h
        val n = w * h
        val zone = reg.zone
        val chan = channels(px)
        var ringCount = 0
        for (z in zone) if (z == RING) ringCount++
        if (ringCount == 0) return null
        val ring = IntArray(ringCount)
        ringCount = 0
        for (i in 0 until n) if (zone[i] == RING) ring[ringCount++] = i

        // Background: a smooth fit to the ring. Pixels far off the ring's
        // median colour (further than its own spread, so a gradient
        // stays) are left out first — a dark shape filling a corner of the
        // ring would otherwise be fitted as paper; then the fit is redone
        // without the pixels it shows to be lines or stray strokes.
        val wt = FloatArray(n)
        for (i in ring) wt[i] = 1f
        val median = medianColour(px, wt)
        val res = IntArray(ringCount)
        val resHist = IntArray(256)
        for (k in ring.indices) {
            res[k] = dist(px[ring[k]], median)
            resHist[res[k]]++
        }
        val spread = max(40, percentile(resHist, ringCount, 0.75f) * 3 / 2)
        for (k in ring.indices) wt[ring[k]] = if (res[k] <= spread) 1f else 0f
        var fit = PushPull.fill(chan, wt, w, h, FIT_LEVEL) ?: return null
        resHist.fill(0)
        for (k in ring.indices) {
            res[k] = fieldDist(px, fit, ring[k])
            resHist[res[k]]++
        }
        val cut = max(24, 2 * percentile(resHist, ringCount, 0.5f))
        for (k in ring.indices) wt[ring[k]] = if (res[k] < cut && dist(px[ring[k]], median) <= spread) 1f else 0f
        fit = PushPull.fill(chan, wt, w, h, FIT_LEVEL) ?: fit
        resHist.fill(0)
        var resSum = 0L
        var robustSum = 0L
        var robust = 0
        for (i in ring) {
            val d = fieldDist(px, fit, i)
            resHist[d]++
            resSum += d
            if (wt[i] > 0f) { robustSum += d; robust++ }
        }
        var flatCount = 0
        for (d in 0 until FLAT_RESIDUAL) flatCount += resHist[d]
        val flatShare = flatCount.toFloat() / ringCount
        val texture = resSum.toFloat() / ringCount
        val robustTexture = if (robust > 0) robustSum.toFloat() / robust else texture
        val background = medianColour(px, wt)
        val ringShare = colourShares(px) { zone[it] == RING }

        val scene = Scene(px, chan, reg, background, ringShare)
        val grow = if (min(reg.boxR - reg.boxL, reg.boxB - reg.boxT) >= 20 || sfx) 2 else 1
        var flat = true
        val strokes = (if (flatShare < FLAT_SHARE) null else {
            scene.onPaper(fit, percentile(resHist, ringCount, 0.9f), outlineColor, robustTexture)
        })
            ?: scene.onInnerPaper(outlineColor, texture)
            ?: scene.onArt(textColor, outlineColor, grow, texture).also { flat = false }
            ?: scene.onInk(textColor, grow)
            ?: return null
        if (!strokes.exact) flat = false
        if (strokes.tone > 0) retoned(chan, strokes, zone, w, h)?.let { (wide, out) ->
            return Cleaned(out, wide, true, background, strokes.fill or OPAQUE, strokes.outline?.or(OPAQUE), strokes.busy)
        }
        val mask = strokes.mask
        val known = strokes.known
        if (count(known) == 0) return null

        val filled = PushPull.fill(chan, weights(known), w, h) ?: return null
        val out = IntArray(n)
        for (i in 0 until n) {
            if (mask[i]) out[i] = rgb(filled[3 * i], filled[3 * i + 1], filled[3 * i + 2])
        }
        var busy = strokes.busy
        if (!flat && texture > 6f && retexture(chan, known, mask, zone, w, h, out)) busy *= 0.6f
        return Cleaned(out, mask, flat, background, strokes.fill or OPAQUE, strokes.outline?.or(OPAQUE), busy)
    }

    /** What was found to be lettering, and which pixels may be trusted to rebuild it from. */
    private class Strokes(
        val mask: BooleanArray,
        val known: BooleanArray,
        val fill: Int,
        val outline: Int?,
        val busy: Float,
        /** Dot spacing of the screentone the lettering sits on, which the paper fill would leave out; 0 for none. */
        val tone: Int = 0,
        /** On a screentone, the pixels that are not tone: strokes and art the tone may not be copied from. */
        val untoned: BooleanArray? = null,
        /** Rebuilt from paper, and so exactly; false where art was guessed at. */
        val exact: Boolean = true,
    )

    /**
     * Lettering on screentone, re-toned: a letterer knocks the dots out
     * around the strokes, and filled with paper the strokes and that halo
     * read as a white blotch on the tone. The mask is widened by a dot
     * period to take the halo, and all of it is filled with the tone's own
     * lattice — or, for a hand-stippled or generated tone that keeps no
     * strict lattice, with dots copied whole spacings away along the axes.
     */
    private fun retoned(chan: FloatArray, strokes: Strokes, zone: ByteArray, w: Int, h: Int): Pair<BooleanArray, IntArray>? {
        val n = w * h
        val around = dilate(strokes.mask, w, h, 1)
        val basis = toneBasis(chan, BooleanArray(n) { !around[it] }, zone, w, h)
            ?: intArrayOf(strokes.tone, 0, 0, strokes.tone)
        val period = sqrt(min(basis[0] * basis[0] + basis[1] * basis[1], basis[2] * basis[2] + basis[3] * basis[3]).toFloat())
        val wide = dilate(strokes.mask, w, h, max(1, period.roundToInt()))
        val band = dilate(wide, w, h, 1)
        val known = BooleanArray(n) { !band[it] }
        val filled = PushPull.fill(chan, weights(known), w, h) ?: return null
        val out = IntArray(n)
        for (i in 0 until n) if (wide[i]) out[i] = rgb(filled[3 * i], filled[3 * i + 1], filled[3 * i + 2])
        val untoned = strokes.untoned
        val source = if (untoned == null) known else BooleanArray(n) { known[it] && !untoned[it] }
        return if (retexture(chan, known, wide, zone, w, h, out, basis, source)) wide to out else null
    }

    /** One work region and the ways of finding the lettering in it. */
    private class Scene(
        val px: IntArray,
        val chan: FloatArray,
        val reg: Region,
        val background: Int,
        val ringShare: FloatArray,
    ) {
        val w = reg.w
        val h = reg.h
        val n = w * h
        val zone = reg.zone
        val minLetters = max(6, reg.boxArea / 400)

        /**
         * Lettering on flat paper, a gradient or a glow: everything that
         * stands clearly off the paper [fit], whose own pixels stray from
         * it by up to [noise]. The fit is redone with the paper between the
         * strokes, so a gradient under the text is followed rather than
         * bridged. Null when nothing stands off it, or when what does is
         * not lettering: of too many colours (art the ring happened not to
         * show), or most of the box (the paper was misjudged).
         *
         * On paper an erasure is exact, so the mask is generous: it takes
         * the strokes' faint anti-aliasing and JPEG ringing too, which left
         * behind read as a ghost of the text. It never takes a stroke that
         * is not lettering, though — a balloon's edge beside the text stays.
         */
        fun onPaper(fit: FloatArray, noise: Int, outlineColor: Int?, texture: Float, within: BooleanArray? = null): Strokes? {
            val threshold = (noise + 16).coerceIn(28, 80)
            val wt = FloatArray(n)
            for (i in 0 until n) wt[i] = if (fieldDist(px, fit, i) < threshold) 1f else 0f
            val paper = PushPull.fill(chan, wt, w, h, 2) ?: fit
            val dist = IntArray(n) { fieldDist(px, paper, it) }
            val raw = BooleanArray(n) { dist[it] > threshold }
            // Pieces are judged by their ink, the pixels at least half as far
            // off the paper as the darkest in the box: pale line work behind
            // the lettering would otherwise join it into one piece with the
            // art. The anti-aliased rims are taken back afterwards.
            val contrast = IntArray(256)
            var boxCount = 0
            for (i in 0 until n) if (zone[i] == BOX) { contrast[dist[i]]++; boxCount++ }
            val inkCut = max(threshold, percentile(contrast, boxCount, 0.98f) / 2)
            val ink = BooleanArray(n) { dist[it] > inkCut }
            val tone = screentone(ink, reg)
            var cores = dropHairlines(keepLetters(ink, reg, tone?.dot ?: 0), w, h)
            if (within != null) cores = enclosed(cores, within, w, h)
            if (count(cores) < minLetters) return null
            val letters = hysteresis(cores, raw, w, h, 2)
            if (countIn(letters, zone, BOX) > MAX_COVER * reg.boxArea) return null
            var maxDist = 0
            for (i in 0 until n) if (letters[i]) maxDist = max(maxDist, dist[i])
            val core = BooleanArray(n) { letters[it] && dist[it] >= maxDist * 2 / 3 }
            val fill = dominantColour(px, core)
            var outline = findOutline(px, reg, letters, fill, background, ringShare, outlineColor, fitted = true)
            // Every stroke pixel is the fill, the outline, or an anti-aliased
            // blend of them with each other or the paper. Every other pixel
            // is enough to judge by.
            fun unexplained(i: Int, o: Int?): Boolean {
                val p = px[i]
                if (dist(p, fill) < COLOUR_TOL || (o != null && dist(p, o) < COLOUR_TOL)) return false
                val under = rgb(paper[3 * i], paper[3 * i + 1], paper[3 * i + 2])
                if (blend(p, fill, under)) return false
                return !(o != null && (blend(p, o, under) || blend(p, fill, o)))
            }
            fun foreign(o: Int?): Float {
                var odd = 0
                var total = 0
                for (i in 0 until n step 2) {
                    if (!letters[i]) continue
                    total++
                    if (unexplained(i, o)) odd++
                }
                return if (total == 0) 0f else odd.toFloat() / total
            }
            var stray = foreign(outline)
            var edgedPastPaper = false
            if (stray > MAX_FOREIGN && outline == null) {
                // An outline on the far side of the paper from the fill — black
                // round white letters on a dark ground, white round black ones
                // on grey — stands off the paper just as the fill does, so it
                // is taken into the letters rather than found round them: what
                // the fill and the paper leave unexplained, when it is one
                // colour clearly not the fill's, is that outline.
                val odd = BooleanArray(n) { letters[it] && unexplained(it, null) }
                val c = dominantColour(px, odd)
                // Only an outline the paper lies between it and the fill.
                val beyond = dist(c, background) >= 20 && blend(background, fill, c)
                if (dist(c, fill) >= 80 && beyond && wraps(letters, fill, c) >= WRAPPED) {
                    val left = foreign(c)
                    if (left <= MAX_FOREIGN) {
                        outline = c
                        stray = left
                        edgedPastPaper = true
                    }
                }
            }
            if (stray > MAX_FOREIGN) return null
            val faint = max(8, noise + 4)
            // Such an edge is too near the ground to count as ink, and the
            // lettering often glows as well, a soft haze round the edge. Both
            // belong to it — all it touches that its colours explain — and
            // left on the art would outline the words.
            val lettering = if (!edgedPastPaper) letters else {
                val o = outline ?: fill
                val part = BooleanArray(n) {
                    raw[it] && !letters[it] && !unexplained(it, o)
                }
                hysteresis(letters, part, w, h, 3 * halfStroke(letters, w, h) + 4)
            }
            val art = BooleanArray(n) { raw[it] && !lettering[it] }
            val weak = BooleanArray(n) { dist[it] > faint && !art[it] }
            val mask = dilate(hysteresis(lettering, weak, w, h, 3), w, h, 1)
            for (i in 0 until n) if (art[i]) mask[i] = false
            // The paper right beside a stroke still carries its ringing:
            // rebuilt from, it would paint a faint copy of the text back.
            val band = dilate(mask, w, h, 2)
            // Art right against the strokes — lettering drawn over line
            // work — is what the paper fill cannot continue.
            var halo = 0
            var crowded = 0
            for (i in 0 until n) {
                if (!band[i] || mask[i]) continue
                halo++
                if (dist[i] > faint) crowded++
            }
            val crowding = if (halo == 0) 0f else crowded.toFloat() / halo
            return Strokes(
                mask = mask,
                known = BooleanArray(n) { !band[it] && dist[it] < threshold },
                fill = fill,
                outline = outline,
                busy = max(((texture - 3f) / 24f).coerceIn(0f, 1f) * 0.5f, (crowding * 2.5f).coerceAtMost(1f)),
                tone = tone?.spacing ?: 0,
                untoned = tone?.let { t -> dilate(pieces(ink, w, h) { it >= t.dot }, w, h, 2) },
            )
        }

        /**
         * How much of the edge of [letters]' [fill] lies against [outline]:
         * all round for an outline, which wraps every stroke; a stretch here
         * and there for art that merely touches the lettering.
         */
        private fun wraps(letters: BooleanArray, fill: Int, outline: Int): Float {
            val isFill = BooleanArray(n) { letters[it] && dist(px[it], fill) < COLOUR_TOL }
            val against = dilate(BooleanArray(n) { dist(px[it], outline) < COLOUR_TOL }, w, h, 2)
            var edge = 0
            var touching = 0
            for (i in 0 until n) {
                if (!isFill[i]) continue
                val x = i % w
                val y = i / w
                val onEdge = (x > 0 && !isFill[i - 1]) || (x < w - 1 && !isFill[i + 1]) ||
                    (y > 0 && !isFill[i - w]) || (y < h - 1 && !isFill[i + w])
                if (!onEdge) continue
                edge++
                if (against[i]) touching++
            }
            return if (edge == 0) 0f else touching.toFloat() / edge
        }

        /**
         * Lettering on paper the ring does not show: a balloon the detector
         * missed, a white knockout in a screentone, a caption box, whose
         * edge runs between the lettering and the ring. The paper is then
         * the colour most of the box is, provided it lies in one piece
         * around the strokes — the white fill of outlined sound-effect
         * glyphs is as common, but broken up glyph by glyph.
         */
        fun onInnerPaper(outlineColor: Int?, texture: Float): Strokes? {
            val hist = IntArray(4096)
            var boxCount = 0
            for (i in 0 until n) if (zone[i] == BOX) { hist[bin(px[i])]++; boxCount++ }
            var best = 0
            for (k in hist.indices) if (hist[k] > hist[best]) best = k
            val paper = settle(px, binCentre(best)) { zone[it] == BOX } ?: return null
            val near = BooleanArray(n) { zone[it] != RING && dist(px[it], paper) < PAPER_TOL }
            val inBox = countIn(near, zone, BOX)
            if (inBox < boxCount * 2 / 5) return null
            if (largestPiece(near, w, h) < count(near) * 7 / 10) return null
            val wt = FloatArray(n) { if (dist(px[it], paper) < PAPER_TOL) 1f else 0f }
            val fit = PushPull.fill(chan, wt, w, h, FIT_LEVEL) ?: return null
            val hist2 = IntArray(256)
            var k = 0
            var sum = 0L
            for (i in 0 until n) {
                if (wt[i] <= 0f) continue
                val d = fieldDist(px, fit, i)
                hist2[d]++
                sum += d
                k++
            }
            // Lettering in a missed balloon stands in its paper; the art
            // the box also caught — a figure's clothes, a burst's rays —
            // stands on the art.
            val strokes = onPaper(fit, percentile(hist2, k, 0.9f), outlineColor, sum.toFloat() / max(1, k), within = near)
                ?: return null
            return haloed(strokes, paper, texture) ?: strokes
        }

        /**
         * Lettering whose paper is only its own outline: black text in a
         * white halo over hair or a tone, which [onInnerPaper] takes for text
         * in a knocked-out patch. Filled with that paper, the halo would stay
         * on the art as a white ghost of the text. It is told apart by the
         * art showing among the letters, where a balloon or a caption box
         * has only paper, and by the paper ending within an outline's width
         * of the strokes all round. The halo is then taken with them, and
         * both are rebuilt from the art beyond.
         */
        private fun haloed(strokes: Strokes, paper: Int, texture: Float): Strokes? {
            val mask = strokes.mask
            val isPaper = BooleanArray(n) { dist(px[it], paper) < PAPER_TOL }
            var x0 = w
            var y0 = h
            var x1 = -1
            var y1 = -1
            for (i in 0 until n) {
                if (!mask[i] || zone[i] == RING) continue
                x0 = min(x0, i % w)
                x1 = max(x1, i % w)
                y0 = min(y0, i / w)
                y1 = max(y1, i / w)
            }
            if (x1 < 0) return null
            val clear = dilate(mask, w, h, 1)
            var free = 0
            var art = 0
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val i = y * w + x
                    if (clear[i]) continue
                    free++
                    if (!isPaper[i]) art++
                }
            }
            if (free == 0 || art < free * HALO_ART) return null
            // Paper's share of each ring round the strokes, a pixel wide, as
            // far out as an outline is ever drawn: under a stroke's width and
            // a half. A white strip or a balloon's paper reaches further.
            val reach = max(4, halfStroke(mask, w, h) * 3 / 2 + 2)
            val share = FloatArray(reach + HALO_BEYOND_RINGS + 1) { -1f }
            var inside = mask
            for (d in 1 until share.size) {
                val grown = dilate(mask, w, h, d)
                var all = 0
                var paperAt = 0
                for (i in 0 until n) {
                    if (!grown[i] || inside[i]) continue
                    all++
                    if (isPaper[i]) paperAt++
                }
                inside = grown
                if (all > 0) share[d] = paperAt.toFloat() / all
            }
            // The halo ends at the first ring that is mostly not paper, and
            // what lies beyond it is the art, not more paper.
            val width = (1..reach).firstOrNull { share[it] in 0f..HALO_EDGE } ?: return null
            val beyond = (width + 1..width + HALO_BEYOND_RINGS).map { share[it] }.filter { it >= 0f }
            if (beyond.isEmpty() || beyond.average() > HALO_BEYOND) return null
            val within = dilate(mask, w, h, width)
            val halo = BooleanArray(n) { mask[it] || (isPaper[it] && within[it]) }
            // With the rim where the halo blends into the art.
            val wide = dilate(halo, w, h, 2)
            val band = dilate(wide, w, h, 1)
            return Strokes(
                mask = wide,
                known = BooleanArray(n) { !band[it] },
                fill = strokes.fill,
                outline = paper,
                busy = (0.35f + (texture - 3f) / 30f).coerceIn(0f, 1f),
                exact = false,
            )
        }

        /**
         * [seed] flooded through the rest of a fill that is shaded — a sound
         * effect running from deep orange to yellow — where it drifts too far
         * from [fill] to be matched by colour: into neighbours only a small
         * step away, and never further from [fill] than twice the tolerance.
         * An outline or the art beyond it is a jump, and stops the flood.
         */
        private fun shaded(seed: BooleanArray, fill: Int): BooleanArray {
            val out = seed.copyOf()
            val queue = IntArray(n)
            var tail = 0
            for (i in 0 until n) if (seed[i]) queue[tail++] = i
            var head = 0
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        val j = yy * w + xx
                        if (out[j] || zone[j] == RING) continue
                        if (dist(px[j], px[i]) >= SHADE_STEP || dist(px[j], fill) >= 2 * COLOUR_TOL) continue
                        out[j] = true
                        queue[tail++] = j
                    }
                }
            }
            return out
        }

        /**
         * [letters] grown [depth] deep through the [outer]-coloured band
         * around their [inner] colour, or null when the grown lettering is
         * no longer lettering (it ran out of the box with the art).
         */
        private fun ring(letters: BooleanArray, inner: Int, outer: Int, depth: Int): BooleanArray? {
            val seam = BooleanArray(n) { zone[it] != RING && blend(px[it], inner, outer) }
            val grown = keepLetters(alongOutline(letters, near(px, zone, outer), seam, w, h, depth), reg, edgeShare = CUT_GLYPH)
            return if (count(grown) >= count(letters)) grown else null
        }

        /**
         * Lettering on art, told apart by colour: the fill is the colour
         * common in the box and rare in the ring, the outline a second
         * colour hugging it. Null when nothing in the box looks like either.
         */
        fun onArt(textColor: Int?, outlineColor: Int?, grow: Int, texture: Float): Strokes? {
            val boxShare = colourShares(px) { zone[it] == BOX }
            var f = verifyColour(px, zone, textColor, boxShare, ringShare)
                ?: peakColour(px, boxShare, ringShare) { zone[it] == BOX }
                ?: return null
            var nearF = shaded(near(px, zone, f), f)
            var letters = keepLetters(nearF, reg, edgeShare = CUT_GLYPH)
            if (count(letters) < minLetters) return null
            var o = findOutline(px, reg, letters, f, background, ringShare, outlineColor, fitted = false)
            if (o != null) {
                val nearO = near(px, zone, o)
                // The fill is the colour the outline encloses: less of it
                // touches anything that is neither.
                if (exposure(nearF, nearO, zone, w, h) > exposure(nearO, nearF, zone, w, h)) {
                    val t = f; f = o; o = t
                    nearF = shaded(nearO, f)
                    letters = keepLetters(nearF, reg, edgeShare = CUT_GLYPH)
                    if (count(letters) < minLetters) return null
                }
                // An outline is no wider than about the strokes it surrounds;
                // past that the growth is following art of the same colour.
                val half = halfStroke(letters, w, h)
                val depth = max(3, min(2 * half + 2, min(reg.boxR - reg.boxL, reg.boxB - reg.boxT) / 3))
                val outlined = ring(letters, f, o, depth)
                if (outlined == null) {
                    o = null
                } else {
                    letters = outlined
                    // A second ring around the first: the paper-white edge a
                    // letterer sets a sound effect off the art with. Only
                    // white — any other colour out there is the art itself —
                    // and only as deep as half a stroke, or it runs on into
                    // the paper between panels.
                    val glow = findOutline(px, reg, letters, o, background, ringShare, null, fitted = false, inside = f)
                    if (glow != null && dist(glow, OPAQUE or 0xFFFFFF) < PAPER_WHITE && dist(glow, f) >= 80) {
                        ring(letters, o, glow, max(3, half))?.let { letters = it }
                    }
                }
            }
            if (countIn(letters, zone, BOX) > MAX_COVER * reg.boxArea) {
                letters = keepLetters(near(px, zone, f, COLOUR_TOL / 2), reg, edgeShare = CUT_GLYPH)
                o = null
                if (count(letters) < minLetters) return null
                if (countIn(letters, zone, BOX) > MAX_COVER * reg.boxArea) return null
            }
            letters = dropHairlines(letters, w, h)
            val mask = dilate(letters, w, h, grow)
            val band = dilate(mask, w, h, 1)
            return Strokes(
                mask = mask,
                known = BooleanArray(n) { !band[it] },
                fill = f,
                outline = o,
                busy = (0.35f + (texture - 3f) / 30f).coerceIn(0f, 1f),
            )
        }

        /**
         * Heavy lettering over line art of its own colour — a sound effect
         * over speed lines, bold text in a burst — where colour tells
         * nothing apart. Stroke weight does: the pieces of ink too thin to
         * hold the lettering's stroke are opened away (eroded to their
         * cores, and only the cores grown back), and the lines go with them.
         */
        fun onInk(textColor: Int?, grow: Int): Strokes? {
            val lum = IntArray(256)
            var boxCount = 0
            for (i in 0 until n) {
                if (zone[i] != BOX) continue
                lum[luminance(px[i])]++
                boxCount++
            }
            val darkest = percentile(lum, boxCount, 0.03f)
            val seed = textColor?.and(0xFFFFFF) ?: ((darkest shl 16) or (darkest shl 8) or darkest)
            val ink = settle(px, seed) { zone[it] == BOX } ?: return null
            val raw = near(px, zone, ink)
            val depth = inner(raw, w, h)
            val hist = IntArray(256)
            var k = 0
            for (i in 0 until n) if (raw[i] && zone[i] == BOX) { hist[min(255, depth[i])]++; k++ }
            if (k < minLetters) return null
            // Half the stroke width, in thirds of a pixel: the depth most
            // of the ink reaches.
            val half = percentile(hist, k, 0.9f)
            if (half < 3 * MIN_HALF_STROKE) return null
            val core = BooleanArray(n) { depth[it] * 5 >= half * 3 }
            val opened = hysteresis(core, raw, w, h, half / 3 + 1)
            var letters = keepLetters(opened, reg, edgeShare = CUT_GLYPH)
            if (count(letters) < minLetters) return null
            if (countIn(letters, zone, BOX) > MAX_COVER * reg.boxArea) return null
            letters = dilate(letters, w, h, grow)
            val band = dilate(letters, w, h, 1)
            return Strokes(
                mask = letters,
                known = BooleanArray(n) { !band[it] },
                fill = ink,
                outline = null,
                busy = 1f,
            )
        }
    }

    /**
     * On art, lettering is found by its own colour, which the art rarely
     * shares; a piece that far inside the box is a glyph the box cut short.
     */
    private const val CUT_GLYPH = 0.6f

    /**
     * Share of the gaps among the letters that is art rather than paper,
     * past which the paper round them is their halo; a balloon, a caption
     * box or a white strip between panels holds next to none.
     */
    private const val HALO_ART = 0.35f

    /** Share of a ring round the strokes that is still paper, below which the halo has ended. */
    private const val HALO_EDGE = 0.6f

    /** The most of the rings just beyond a halo that may be paper; more is paper the text sits in. */
    private const val HALO_BEYOND = 0.5f

    /** Rings past a halo's edge that must show the art. */
    private const val HALO_BEYOND_RINGS = 4

    /** Largest colour step between neighbours within one shaded fill. */
    private const val SHADE_STEP = 16

    /** How far a pixel may stray from the paper colour and still be paper. */
    private const val PAPER_TOL = 24

    // ---- lettering colours ----

    /** Share of [select]ed pixels in each 4-bit-per-channel colour cell, blurred over neighbouring cells. */
    private inline fun colourShares(px: IntArray, select: (Int) -> Boolean): FloatArray {
        val hist = FloatArray(4096)
        var total = 0
        for (i in px.indices) {
            if (!select(i)) continue
            hist[bin(px[i])] += 1f
            total++
        }
        if (total > 0) for (k in hist.indices) hist[k] /= total
        return blurBins(hist)
    }

    /**
     * The colour most over-represented among the [select]ed pixels
     * compared with the ring: lettering is common inside its box and rare
     * outside it, where the art it sits on carries on.
     */
    private inline fun peakColour(
        px: IntArray,
        inside: FloatArray,
        ring: FloatArray,
        penalty: Float = 1.5f,
        select: (Int) -> Boolean,
    ): Int? {
        var best = -1
        var bestScore = 0.03f
        for (k in inside.indices) {
            val score = inside[k] - penalty * ring[k]
            if (score > bestScore) { bestScore = score; best = k }
        }
        if (best < 0) return null
        return settle(px, binCentre(best), select)
    }

    /** [seed] moved to the mean of the selected pixels near it, three times over. */
    private inline fun settle(px: IntArray, seed: Int, select: (Int) -> Boolean): Int? {
        var c = seed
        repeat(3) {
            var r = 0L
            var g = 0L
            var b = 0L
            var k = 0
            for (i in px.indices) {
                if (!select(i)) continue
                val p = px[i]
                if (dist(p, c) >= COLOUR_TOL) continue
                r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF; k++
            }
            if (k == 0) return null
            c = ((r / k).toInt() shl 16) or ((g / k).toInt() shl 8) or (b / k).toInt()
        }
        return c
    }

    /** The model's colour, when the pixels bear it out as lettering. */
    private fun verifyColour(px: IntArray, zone: ByteArray, colour: Int?, inside: FloatArray, ring: FloatArray): Int? {
        colour ?: return null
        val k = bin(colour)
        if (inside[k] < 0.02f || inside[k] < 1.5f * ring[k] + 0.01f) return null
        return settle(px, colour and 0xFFFFFF) { zone[it] == BOX }
    }

    /**
     * The colour of the stroke around the lettering, if it has one: a
     * colour concentrated in a thin band hugging the fill, clearly unlike
     * the fill and not just the fill's anti-aliasing fading into the
     * background. The ring counts against a candidate only lightly: art
     * often has the outline's colour too — white highlights, black line
     * work — just not packed around the fill. [fitted] when the background
     * is flat, so the outline must also stand off the paper. For a second
     * ring, [fill] is the first outline and [inside] the lettering's fill.
     */
    private fun findOutline(
        px: IntArray,
        reg: Region,
        letters: BooleanArray,
        fill: Int,
        background: Int,
        ring: FloatArray,
        given: Int?,
        fitted: Boolean,
        inside: Int? = null,
    ): Int? {
        val zone = reg.zone
        val band = dilate(letters, reg.w, reg.h, 3)
        for (i in band.indices) {
            band[i] = band[i] && !letters[i] && zone[i] != RING && dist(px[i], fill) >= COLOUR_TOL &&
                (inside == null || dist(px[i], inside) >= COLOUR_TOL)
        }
        val n = count(band)
        if (n < 8) return null
        val bandShare = colourShares(px) { band[it] }
        val candidate = given?.let { g -> settle(px, g and 0xFFFFFF) { band[it] } }
            ?: peakColour(px, bandShare, ring, penalty = 0.5f) { band[it] }
            ?: return null
        var hits = 0
        for (i in band.indices) if (band[i] && dist(px[i], candidate) < COLOUR_TOL) hits++
        if (hits < n / 4) return null
        if (dist(candidate, fill) < 80) return null
        // A colour on the way from the fill to the background is the fill's
        // anti-aliasing — except around an outline, where a white edge on
        // pale art lies on that way too and is wanted.
        val fb = euclid(fill, background)
        if (inside == null && euclid(fill, candidate) + euclid(candidate, background) < fb * 1.25f) return null
        if (fitted && dist(candidate, background) < 40) return null
        return candidate
    }

    /** Pixels in the search area within [tol] of [colour]. */
    private fun near(px: IntArray, zone: ByteArray, colour: Int, tol: Int = COLOUR_TOL): BooleanArray =
        BooleanArray(px.size) { zone[it] != RING && dist(px[it], colour) < tol }

    /** Share of [a]'s in-box pixels with a 4-neighbour that is neither [a] nor [b]. */
    private fun exposure(a: BooleanArray, b: BooleanArray, zone: ByteArray, w: Int, h: Int): Float {
        var total = 0
        var exposed = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                if (!a[i] || zone[i] != BOX) continue
                total++
                if ((!a[i - 1] && !b[i - 1]) || (!a[i + 1] && !b[i + 1]) ||
                    (!a[i - w] && !b[i - w]) || (!a[i + w] && !b[i + w])
                ) exposed++
            }
        }
        return if (total == 0) 0f else exposed.toFloat() / total
    }

    /**
     * [fill] grown through [outline]-coloured pixels only as deep as the
     * outline runs. Growing layer by layer, each layer of a real outline is
     * about as long as the one before; once it is exhausted only the art's
     * own lines, which merely touch it, carry on — a layer half the size
     * of the longest or less — and the growth stops there. The first
     * [EDGE_DEPTH] layers may also cross [edge] pixels: the anti-aliased
     * seam between fill and outline, which is neither colour. They are not
     * counted, and nothing grows through them any deeper, so a stretch of
     * art that happens to lie between the two colours is not followed.
     */
    private fun alongOutline(
        fill: BooleanArray,
        outline: BooleanArray,
        edge: BooleanArray,
        w: Int,
        h: Int,
        maxDepth: Int,
    ): BooleanArray {
        val n = w * h
        val depth = IntArray(n) { if (fill[it]) 0 else -1 }
        var frontier = IntArray(n)
        var next = IntArray(n)
        var fn = 0
        for (i in 0 until n) if (fill[i]) frontier[fn++] = i
        val layer = IntArray(maxDepth + 1)
        var d = 0
        while (fn > 0 && d < maxDepth) {
            d++
            var nn = 0
            var inked = 0
            for (k in 0 until fn) {
                val i = frontier[k]
                val x = i % w
                val y = i / w
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        val j = yy * w + xx
                        if (depth[j] >= 0) continue
                        if (outline[j]) inked++ else if (d > EDGE_DEPTH || !edge[j]) continue
                        depth[j] = d
                        next[nn++] = j
                    }
                }
            }
            layer[d] = inked
            val t = frontier; frontier = next; next = t
            fn = nn
        }
        var widest = 0
        var limit = d
        for (k in 1..d) {
            widest = max(widest, layer[k])
            if (k > EDGE_DEPTH && layer[k] < widest / 2) { limit = k - 1; break }
        }
        return BooleanArray(n) { depth[it] in 0..limit }
    }

    private const val EDGE_DEPTH = 2

    // ---- mask shaping ----

    /**
     * Calls [visit] once per 8-connected piece of [raw], with a buffer whose
     * first `size` entries are the piece's pixel indices.
     */
    private inline fun forEachPiece(raw: BooleanArray, w: Int, h: Int, visit: (IntArray, Int) -> Unit) {
        val n = w * h
        val seen = BooleanArray(n)
        val queue = IntArray(n)
        for (start in 0 until n) {
            if (!raw[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        val j = yy * w + xx
                        if (!raw[j] || seen[j]) continue
                        seen[j] = true
                        queue[tail++] = j
                    }
                }
            }
            visit(queue, tail)
        }
    }

    /**
     * The pieces of [cores] standing in [paper]: looking a few pixels out
     * from a piece, past its anti-aliased rim, most of what is there is
     * paper. A glyph in a balloon is ringed by the balloon's paper; a bit
     * of drawing the box also caught is ringed by more drawing.
     */
    private fun enclosed(cores: BooleanArray, paper: BooleanArray, w: Int, h: Int): BooleanArray {
        val keep = BooleanArray(cores.size)
        forEachPiece(cores, w, h) { piece, size ->
            var probes = 0
            var onPaper = 0
            for (k in 0 until size) {
                val i = piece[k]
                val x = i % w
                val y = i / w
                for (d in 0 until 4) {
                    val nx = x + if (d == 0) PROBE else if (d == 1) -PROBE else 0
                    val ny = y + if (d == 2) PROBE else if (d == 3) -PROBE else 0
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                    val j = ny * w + nx
                    if (cores[j]) continue
                    probes++
                    if (paper[j]) onPaper++
                }
            }
            if (probes == 0 || onPaper >= probes * ENCLOSED) for (k in 0 until size) keep[piece[k]] = true
        }
        return keep
    }

    /** How far out from a piece [enclosed] looks, past the anti-aliased rim. */
    private const val PROBE = 4

    /** Share of what surrounds a piece that must be paper for it to stand in the paper. */
    private const val ENCLOSED = 0.6f

    /**
     * The pieces of [raw] that are lettering: mostly inside the box, not
     * running on out of the searched area — a balloon's edge or a line of
     * the art that merely crosses the box does both — and not a screentone
     * dot, anything under [minArea] pixels. A piece that runs on out of
     * one side is kept when at least [edgeShare] of it is in the box: a
     * glyph the model's box cut short. One that runs out of two sides is a
     * line crossing the box.
     */
    internal fun keepLetters(raw: BooleanArray, reg: Region, minArea: Int = 0, edgeShare: Float = 0.85f): BooleanArray {
        val keep = BooleanArray(raw.size)
        forEachPiece(raw, reg.w, reg.h) { piece, size ->
            if (size < minArea) return@forEachPiece
            var inBox = 0
            var edges = 0
            for (k in 0 until size) {
                val i = piece[k]
                if (reg.zone[i] == BOX) inBox++
                edges = edges or reg.searchEdges(i % reg.w, i / reg.w)
            }
            val share = inBox.toFloat() / size
            val sides = Integer.bitCount(edges)
            if (share >= 0.35f && sides < 2 && (sides == 0 || share >= edgeShare)) {
                for (k in 0 until size) keep[piece[k]] = true
            }
        }
        return keep
    }

    /**
     * The screentone the lettering sits on, read off the pieces of [raw]
     * clear of the box — on a tone they are dozens of small dots of one
     * size — or null when it is not on one.
     */
    private fun screentone(raw: BooleanArray, reg: Region): Screentone? {
        val areas = ArrayList<Int>()
        val cx = ArrayList<Float>()
        val cy = ArrayList<Float>()
        var pieces = 0
        forEachPiece(raw, reg.w, reg.h) { piece, size ->
            for (k in 0 until size) if (reg.zone[piece[k]] == BOX) return@forEachPiece
            pieces++
            if (size > MAX_DOT) return@forEachPiece
            areas += size
            var sx = 0f
            var sy = 0f
            for (k in 0 until size) { sx += piece[k] % reg.w; sy += piece[k] / reg.w }
            cx += sx / size
            cy += sy / size
        }
        if (areas.size < 12 || areas.size < pieces * 3 / 5) return null
        // Dot spacing: the median distance from a dot to its nearest one.
        val m = min(cx.size, 300)
        val nearest = FloatArray(m) { i ->
            var best = Float.MAX_VALUE
            for (j in 0 until cx.size) {
                if (j == i) continue
                val dx = cx[i] - cx[j]
                val dy = cy[i] - cy[j]
                best = min(best, dx * dx + dy * dy)
            }
            sqrt(best)
        }
        nearest.sort()
        areas.sort()
        return Screentone(2 * areas[areas.size / 2] + 1, max(3, nearest[m / 2].roundToInt()))
    }

    /**
     * A screentone: pieces under [dot] pixels are its dots rather than
     * strokes — twice their median, which keeps the dots of an ellipsis,
     * drawn with the pen and much larger — and they lie about [spacing]
     * pixels apart.
     */
    private class Screentone(val dot: Int, val spacing: Int)

    private const val MAX_DOT = 80

    /** The pieces of [mask] whose size passes [keep]. */
    private inline fun pieces(mask: BooleanArray, w: Int, h: Int, keep: (Int) -> Boolean): BooleanArray {
        val out = BooleanArray(mask.size)
        forEachPiece(mask, w, h) { piece, size ->
            if (keep(size)) for (k in 0 until size) out[piece[k]] = true
        }
        return out
    }

    /** Size of the largest 8-connected piece of [mask]. */
    private fun largestPiece(mask: BooleanArray, w: Int, h: Int): Int {
        var best = 0
        forEachPiece(mask, w, h) { _, size -> best = max(best, size) }
        return best
    }

    /**
     * Chamfer distance, in thirds of a pixel, from each pixel of [mask] to
     * the nearest pixel outside it; 0 outside. Half a stroke's width at
     * its middle.
     */
    internal fun inner(mask: BooleanArray, w: Int, h: Int): IntArray {
        val big = 1 shl 20
        val d = IntArray(w * h) { if (mask[it]) big else 0 }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (d[i] == 0) continue
                var v = d[i]
                v = min(v, if (x > 0) d[i - 1] + 3 else 3)
                v = min(v, if (y > 0) d[i - w] + 3 else 3)
                v = min(v, if (x > 0 && y > 0) d[i - w - 1] + 4 else 4)
                v = min(v, if (x < w - 1 && y > 0) d[i - w + 1] + 4 else 4)
                d[i] = v
            }
        }
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                val i = y * w + x
                if (d[i] == 0) continue
                var v = d[i]
                v = min(v, if (x < w - 1) d[i + 1] + 3 else 3)
                v = min(v, if (y < h - 1) d[i + w] + 3 else 3)
                v = min(v, if (x < w - 1 && y < h - 1) d[i + w + 1] + 4 else 4)
                v = min(v, if (x > 0 && y < h - 1) d[i + w - 1] + 4 else 4)
                d[i] = v
            }
        }
        return d
    }

    /** Half the width of the strokes of [mask], in pixels: the depth most of it reaches. */
    private fun halfStroke(mask: BooleanArray, w: Int, h: Int): Int {
        val depth = inner(mask, w, h)
        val hist = IntArray(256)
        var k = 0
        for (i in depth.indices) if (depth[i] > 0) { hist[min(255, depth[i])]++; k++ }
        return if (k == 0) 0 else (percentile(hist, k, 0.9f) + 2) / 3
    }

    /**
     * [letters] without the pieces drawn far thinner than the lettering
     * itself: speed lines, hatching and the rays of a burst that a loose
     * box took in. The lettering's weight is read off the heaviest tenth
     * of the stroke-like ink — the lines can be most of it — counting only
     * pieces long for their width, so a solid patch of black art cannot
     * pass for the weight of the text. A hairline is under [HAIRLINE] of it,
     * longer than [LINE_LENGTH] stroke widths and [ELONGATED].
     */
    private fun dropHairlines(letters: BooleanArray, w: Int, h: Int): BooleanArray {
        val depth = inner(letters, w, h)
        val pieces = ArrayList<IntArray>()
        forEachPiece(letters, w, h) { piece, size ->
            var deepest = 0
            var x0 = w
            var x1 = 0
            var y0 = h
            var y1 = 0
            for (k in 0 until size) {
                val i = piece[k]
                deepest = max(deepest, depth[i])
                val x = i % w
                val y = i / w
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
            }
            val extent = max(x1 - x0, y1 - y0) + 1
            pieces += intArrayOf(deepest, size, piece[0], extent)
        }
        if (pieces.size < 2) return letters
        val strokes = pieces.filter { it[1] * 9 >= STROKE_LENGTH * it[0] * it[0] }
        val total = strokes.sumOf { it[1] }
        var acc = 0
        var weight = 0
        for (p in strokes.sortedByDescending { it[0] }) {
            acc += p[1]
            if (acc * 10 >= total) { weight = p[0]; break }
        }
        if (weight < 3 * MIN_HALF_STROKE) return letters
        val thin = BooleanArray(letters.size)
        var any = false
        // Length along the piece, against the lettering's stroke width:
        // a ray runs on, a dakuten or a full stop is as thin but short. And
        // a line reaches far for its ink, where a thin-drawn ♡ curls up.
        val strokeWidth = 2 * weight
        for (p in pieces) {
            val length = p[1] * 3 / max(1, 2 * p[0])
            val line = p[3] * p[3] >= ELONGATED * p[1]
            if (p[0] < weight * HAIRLINE && length * 3 > LINE_LENGTH * strokeWidth && line) { thin[p[2]] = true; any = true }
        }
        if (!any) return letters
        val out = letters.copyOf()
        forEachPiece(letters, w, h) { piece, size ->
            if (thin[piece[0]]) for (k in 0 until size) out[piece[k]] = false
        }
        return out
    }

    /** Area over half-width squared above which a piece is a stroke rather than a blob. */
    private const val STROKE_LENGTH = 12

    /** A stroke this thin (half-width, pixels) cannot be told from a line by weight. */
    private const val MIN_HALF_STROKE = 2

    private const val HAIRLINE = 0.4f

    private const val LINE_LENGTH = 4

    /** Extent squared over area above which a piece is a line, not a curled-up glyph. */
    private const val ELONGATED = 12

    private fun luminance(p: Int): Int = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    /**
     * [seed] grown [steps] times into 8-neighbours that are [weak]: the
     * anti-aliased fringe. Breadth-first from the seed's own edge, so the
     * cost is the growth, not the region.
     */
    private fun hysteresis(seed: BooleanArray, weak: BooleanArray, w: Int, h: Int, steps: Int): BooleanArray {
        val n = w * h
        val out = seed.copyOf()
        var frontier = IntArray(64)
        var fn = 0
        for (i in 0 until n) {
            if (!seed[i]) continue
            val x = i % w
            val edge = (x > 0 && !seed[i - 1]) || (x < w - 1 && !seed[i + 1]) ||
                (i >= w && !seed[i - w]) || (i + w < n && !seed[i + w])
            if (!edge) continue
            if (fn == frontier.size) frontier = frontier.copyOf(fn * 2)
            frontier[fn++] = i
        }
        var next = IntArray(frontier.size)
        repeat(steps) {
            var nn = 0
            for (k in 0 until fn) {
                val i = frontier[k]
                val x = i % w
                val y = i / w
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        val j = yy * w + xx
                        if (out[j] || !weak[j]) continue
                        out[j] = true
                        if (nn == next.size) next = next.copyOf(nn * 2)
                        next[nn++] = j
                    }
                }
            }
            val t = frontier; frontier = next; next = t
            if (next.size < frontier.size) next = IntArray(frontier.size)
            fn = nn
        }
        return out
    }

    /** [mask] dilated by a (2r+1)-square, with running counts in two passes. */
    internal fun dilate(mask: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        if (r <= 0) return mask.copyOf()
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var c = 0
            for (x in 0 until min(r, w)) if (mask[row + x]) c++
            for (x in 0 until w) {
                val add = x + r
                if (add < w && mask[row + add]) c++
                tmp[row + x] = c > 0
                val drop = x - r
                if (drop >= 0 && mask[row + drop]) c--
            }
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            var c = 0
            for (y in 0 until min(r, h)) if (tmp[y * w + x]) c++
            for (y in 0 until h) {
                val add = y + r
                if (add < h && tmp[add * w + x]) c++
                out[y * w + x] = c > 0
                val drop = y - r
                if (drop >= 0 && tmp[drop * w + x]) c--
            }
        }
        return out
    }

    // ---- texture ----

    /**
     * Continues a screentone through the erased strokes. The tone's dot
     * lattice is measured from the autocorrelation of the fine detail left
     * around the text; each erased pixel then takes the detail found a
     * whole number of lattice steps away, on the nearest pixel that was not
     * lettering, over the smooth fill. Returns false, leaving [out] as it
     * was, when the background has no lattice to continue — lines and
     * painted detail are left to the smooth fill and to AI clean-up. A
     * [lattice] already known is used as it is, and detail is copied only
     * from [source] pixels.
     */
    private fun retexture(
        chan: FloatArray,
        known: BooleanArray,
        mask: BooleanArray,
        zone: ByteArray,
        w: Int,
        h: Int,
        out: IntArray,
        lattice: IntArray? = null,
        source: BooleanArray = known,
    ): Boolean {
        val n = w * h
        val low = PushPull.fill(chan, weights(known), w, h, FIT_LEVEL) ?: return false
        val basis = lattice ?: toneBasis(chan, known, zone, w, h, low) ?: return false
        val steps = latticeSteps(basis, min(96, max(w, h)))
        if (steps.isEmpty()) return false
        var moved = 0
        var total = 0
        for (i in 0 until n) {
            if (!mask[i]) continue
            total++
            val x = i % w
            val y = i / w
            for (k in steps.indices step 2) {
                val sx = x + steps[k]
                val sy = y + steps[k + 1]
                if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue
                val j = sy * w + sx
                if (!source[j]) continue
                out[i] = rgb(
                    low[3 * i] + chan[3 * j] - low[3 * j],
                    low[3 * i + 1] + chan[3 * j + 1] - low[3 * j + 1],
                    low[3 * i + 2] + chan[3 * j + 2] - low[3 * j + 2],
                )
                moved++
                break
            }
        }
        return moved * 2 > total
    }

    /**
     * The dot lattice of the tone around the lettering, from the fine
     * detail of the [known] ring pixels over their smooth fit [low].
     */
    private fun toneBasis(
        chan: FloatArray,
        known: BooleanArray,
        zone: ByteArray,
        w: Int,
        h: Int,
        low: FloatArray? = PushPull.fill(chan, weights(known), w, h, FIT_LEVEL),
    ): IntArray? {
        low ?: return null
        val n = w * h
        val detail = FloatArray(n)
        for (i in 0 until n) {
            if (!known[i]) continue
            detail[i] = 0.299f * (chan[3 * i] - low[3 * i]) + 0.587f * (chan[3 * i + 1] - low[3 * i + 1]) +
                0.114f * (chan[3 * i + 2] - low[3 * i + 2])
        }
        return lattice(detail, BooleanArray(n) { known[it] && zone[it] == RING }, w, h)
    }

    /**
     * Two lattice vectors (x1, y1, x2, y2) of a dot screen in [detail],
     * read off peaks of its autocorrelation over [sample]d pixels. Null
     * when nothing repeats in two directions.
     * Offsets closer than 3 px are ignored: every image correlates with
     * itself a pixel or two away, and a line correlates along its length,
     * but only a real tone dips and then peaks again further out.
     */
    internal fun lattice(detail: FloatArray, sample: BooleanArray, w: Int, h: Int): IntArray? {
        val r = LATTICE_REACH
        val side = 2 * r + 1
        val corr = FloatArray(side * side)
        var total = 0
        for (b in sample) if (b) total++
        if (total == 0) return null
        // A thousand or so pixels see a dot screen as clearly as all of them.
        val stride = max(1, total / LATTICE_SAMPLES)
        val at = IntArray(total / stride + 1)
        val ax = IntArray(at.size)
        val ay = IntArray(at.size)
        var m = 0
        var seen = 0
        for (i in sample.indices) {
            if (!sample[i]) continue
            if (seen++ % stride == 0 && m < at.size) {
                at[m] = i
                ax[m] = i % w
                ay[m] = i / w
                m++
            }
        }
        for (dy in 0..r) {
            for (dx in -r..r) {
                if (dy == 0 && dx <= 0) continue
                var ab = 0.0
                var aa = 0.0
                for (k in 0 until m) {
                    val x = ax[k] + dx
                    if (x < 0 || x >= w || ay[k] + dy >= h) continue
                    val i = at[k]
                    val j = i + dy * w + dx
                    if (!sample[j]) continue
                    val a = detail[i]
                    val b = detail[j]
                    ab += a * b
                    aa += 0.5f * (a * a + b * b)
                }
                val c = if (aa > 1e-3) (ab / aa).toFloat() else 0f
                corr[(dy + r) * side + dx + r] = c
                corr[(-dy + r) * side - dx + r] = c
            }
        }
        val peaks = ArrayList<IntArray>()
        var top = 0f
        for (dy in -r..r) {
            for (dx in -r..r) {
                if (dx * dx + dy * dy < 9) continue
                val c = corr[(dy + r) * side + dx + r]
                if (c < 0.4f) continue
                // A tone repeats: halfway to the next dot is the paper
                // between dots, so the correlation dips there first.
                val half = corr[(Math.round(dy / 2f) + r) * side + Math.round(dx / 2f) + r]
                if (half > c - 0.4f) continue
                var isPeak = true
                for (ey in -1..1) for (ex in -1..1) {
                    val yy = dy + ey
                    val xx = dx + ex
                    if ((ex != 0 || ey != 0) && yy in -r..r && xx in -r..r && corr[(yy + r) * side + xx + r] > c) isPeak = false
                }
                if (!isPeak) continue
                peaks += intArrayOf(dx, dy, (c * 1000).toInt())
                top = max(top, c)
            }
        }
        if (peaks.isEmpty()) return null
        val v1 = peaks.filter { it[2] >= top * 800 }.minBy { it[0] * it[0] + it[1] * it[1] }
        val len1 = sqrt((v1[0] * v1[0] + v1[1] * v1[1]).toFloat())
        val others = peaks.filter {
            val cross = abs(v1[0] * it[1] - v1[1] * it[0])
            cross >= 0.5f * len1 * sqrt((it[0] * it[0] + it[1] * it[1]).toFloat())
        }
        if (others.isEmpty()) return null
        val top2 = others.maxOf { it[2] }
        val v2 = others.filter { it[2] >= top2 * 8 / 10 }.minBy { it[0] * it[0] + it[1] * it[1] }
        // A lattice repeats along every combination of its steps; two
        // chance peaks in painted art do not.
        for (sign in intArrayOf(1, -1)) {
            val sx = v1[0] + sign * v2[0]
            val sy = v1[1] + sign * v2[1]
            if (abs(sx) <= r && abs(sy) <= r && corr[(sy + r) * side + sx + r] < 0.25f) return null
        }
        return intArrayOf(v1[0], v1[1], v2[0], v2[1])
    }

    private const val LATTICE_REACH = 10

    private const val LATTICE_SAMPLES = 1200

    /** Lattice offsets (x, y, x, y, ...) within [reach], nearest first. */
    private fun latticeSteps(basis: IntArray, reach: Int): IntArray {
        val (x1, y1, x2, y2) = basis
        val shortest = min(x1 * x1 + y1 * y1, x2 * x2 + y2 * y2)
        val k = min(40, reach / max(1, sqrt(shortest.toFloat()).toInt()) + 1)
        val all = ArrayList<IntArray>()
        for (a in -k..k) {
            for (b in -k..k) {
                if (a == 0 && b == 0) continue
                val dx = a * x1 + b * x2
                val dy = a * y1 + b * y2
                val d2 = dx * dx + dy * dy
                if (d2 > reach * reach) continue
                all += intArrayOf(dx, dy, d2)
            }
        }
        all.sortBy { it[2] }
        val out = IntArray(min(all.size, 600) * 2)
        for (i in 0 until out.size / 2) {
            out[2 * i] = all[i][0]
            out[2 * i + 1] = all[i][1]
        }
        return out
    }

    // ---- pixels ----

    private const val OPAQUE = -0x1000000

    /** How near white a second ring must be to be a letterer's white edge. */
    private const val PAPER_WHITE = 40

    private fun channels(px: IntArray): FloatArray {
        val c = FloatArray(px.size * 3)
        for (i in px.indices) {
            val p = px[i]
            c[3 * i] = (p shr 16 and 0xFF).toFloat()
            c[3 * i + 1] = (p shr 8 and 0xFF).toFloat()
            c[3 * i + 2] = (p and 0xFF).toFloat()
        }
        return c
    }

    private fun rgb(r: Float, g: Float, b: Float): Int =
        OPAQUE or (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or
            b.roundToInt().coerceIn(0, 255)

    private fun binCentre(k: Int): Int =
        (((k shr 8) * 16 + 8) shl 16) or ((((k shr 4) and 15) * 16 + 8) shl 8) or ((k and 15) * 16 + 8)

    private fun bin(p: Int): Int = ((p shr 20 and 0xF) shl 8) or ((p shr 12 and 0xF) shl 4) or (p shr 4 and 0xF)

    /** Largest per-channel difference: a coloured stroke on white differs in one channel only. */
    internal fun dist(a: Int, b: Int): Int = max(
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)),
        max(abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)), abs((a and 0xFF) - (b and 0xFF))),
    )

    /** Whether [p] lies on the way from [a] to [b], as an anti-aliased edge between them does. */
    private fun blend(p: Int, a: Int, b: Int): Boolean {
        val ar = a shr 16 and 0xFF
        val ag = a shr 8 and 0xFF
        val ab = a and 0xFF
        val vr = ((b shr 16 and 0xFF) - ar).toFloat()
        val vg = ((b shr 8 and 0xFF) - ag).toFloat()
        val vb = ((b and 0xFF) - ab).toFloat()
        val pr = ((p shr 16 and 0xFF) - ar).toFloat()
        val pg = ((p shr 8 and 0xFF) - ag).toFloat()
        val pb = ((p and 0xFF) - ab).toFloat()
        val len2 = vr * vr + vg * vg + vb * vb
        val t = if (len2 > 0f) ((pr * vr + pg * vg + pb * vb) / len2).coerceIn(0f, 1f) else 0f
        val er = pr - t * vr
        val eg = pg - t * vg
        val eb = pb - t * vb
        return er * er + eg * eg + eb * eb <= BLEND_TOL * BLEND_TOL
    }

    private const val BLEND_TOL = 16f

    private fun euclid(a: Int, b: Int): Float {
        val dr = (a shr 16 and 0xFF) - (b shr 16 and 0xFF)
        val dg = (a shr 8 and 0xFF) - (b shr 8 and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }

    private fun fieldDist(px: IntArray, field: FloatArray, i: Int): Int {
        val p = px[i]
        return max(
            abs((p shr 16 and 0xFF) - field[3 * i]),
            max(abs((p shr 8 and 0xFF) - field[3 * i + 1]), abs((p and 0xFF) - field[3 * i + 2])),
        ).toInt().coerceIn(0, 255)
    }

    private fun percentile(hist: IntArray, total: Int, q: Float): Int {
        val target = (total * q).toInt()
        var acc = 0
        for (v in hist.indices) {
            acc += hist[v]
            if (acc > target) return v
        }
        return hist.size - 1
    }

    /** Per-channel median of the pixels [wt] marks. */
    private fun medianColour(px: IntArray, wt: FloatArray): Int {
        val r = IntArray(256)
        val g = IntArray(256)
        val b = IntArray(256)
        var n = 0
        for (i in px.indices) {
            if (wt[i] <= 0f) continue
            val p = px[i]
            r[p shr 16 and 0xFF]++; g[p shr 8 and 0xFF]++; b[p and 0xFF]++
            n++
        }
        if (n == 0) return OPAQUE or 0xFFFFFF
        return OPAQUE or (percentile(r, n, 0.5f) shl 16) or (percentile(g, n, 0.5f) shl 8) or percentile(b, n, 0.5f)
    }

    /** The most common colour of the [select]ed pixels, averaged over its neighbourhood. */
    private fun dominantColour(px: IntArray, select: BooleanArray): Int {
        val hist = IntArray(4096)
        for (i in px.indices) if (select[i]) hist[bin(px[i])]++
        var best = 0
        for (k in hist.indices) if (hist[k] > hist[best]) best = k
        var r = 0L
        var g = 0L
        var b = 0L
        var k = 0
        for (i in px.indices) {
            if (!select[i] || bin(px[i]) != best) continue
            val p = px[i]
            r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF; k++
        }
        if (k == 0) return OPAQUE
        val seed = ((r / k).toInt() shl 16) or ((g / k).toInt() shl 8) or (b / k).toInt()
        return settle(px, seed) { select[it] } ?: seed
    }

    /** Colour cells blurred with their 26 neighbours, so a colour on a cell boundary is not split in two. */
    private fun blurBins(h: FloatArray): FloatArray {
        var src = h
        for (axis in 0..2) {
            val step = when (axis) { 0 -> 1; 1 -> 16; else -> 256 }
            val dst = FloatArray(4096)
            for (k in 0 until 4096) {
                val c = (k / step) % 16
                var s = src[k]
                if (c > 0) s += src[k - step]
                if (c < 15) s += src[k + step]
                dst[k] = s
            }
            src = dst
        }
        return src
    }

    private fun count(mask: BooleanArray): Int {
        var c = 0
        for (b in mask) if (b) c++
        return c
    }

    private fun countIn(mask: BooleanArray, zone: ByteArray, z: Byte): Int {
        var c = 0
        for (i in mask.indices) if (mask[i] && zone[i] == z) c++
        return c
    }

    private fun weights(known: BooleanArray) = FloatArray(known.size) { if (known[it]) 1f else 0f }
}

/**
 * Push-pull interpolation (Gortler et al., the Lumigraph): scattered known
 * pixels are averaged up an image pyramid until every cell has data, then
 * pushed back down with bilinear upsampling into the cells that had none.
 * The result is smooth, exact on flat colour, follows gradients, and costs
 * a few passes over the image whatever the shape of the hole.
 */
internal object PushPull {

    private class Level(val w: Int, val h: Int, val c: FloatArray, val wt: FloatArray)

    /**
     * [c] (interleaved RGB, [w] x [h]) with every pixel whose weight in
     * [wt] is 0 filled in from the ones that have weight. With [floor] > 0
     * every pixel instead takes the value interpolated from pyramid level
     * [floor] — a smooth fit at 2^[floor]-pixel cells rather than a fill.
     * Null when no pixel has weight.
     */
    fun fill(c: FloatArray, wt: FloatArray, w: Int, h: Int, floor: Int = 0): FloatArray? {
        val levels = ArrayList<Level>()
        var cur = Level(w, h, c.copyOf(), wt)
        levels += cur
        while (cur.w > 1 || cur.h > 1) {
            val nw = (cur.w + 1) / 2
            val nh = (cur.h + 1) / 2
            val nc = FloatArray(nw * nh * 3)
            val nwt = FloatArray(nw * nh)
            for (y in 0 until nh) {
                for (x in 0 until nw) {
                    var sw = 0f
                    var r = 0f
                    var g = 0f
                    var b = 0f
                    for (dy in 0..1) {
                        val yy = 2 * y + dy
                        if (yy >= cur.h) continue
                        for (dx in 0..1) {
                            val xx = 2 * x + dx
                            if (xx >= cur.w) continue
                            val i = yy * cur.w + xx
                            val ww = cur.wt[i]
                            if (ww <= 0f) continue
                            sw += ww
                            r += ww * cur.c[3 * i]
                            g += ww * cur.c[3 * i + 1]
                            b += ww * cur.c[3 * i + 2]
                        }
                    }
                    if (sw > 0f) {
                        val o = y * nw + x
                        nc[3 * o] = r / sw
                        nc[3 * o + 1] = g / sw
                        nc[3 * o + 2] = b / sw
                        nwt[o] = min(1f, sw)
                    }
                }
            }
            cur = Level(nw, nh, nc, nwt)
            levels += cur
        }
        if (cur.wt[0] <= 0f) return null
        val top = min(floor, levels.size - 1)
        for (k in levels.size - 2 downTo top) {
            val fine = levels[k]
            upsample(levels[k + 1], fine.w, fine.h, 2, fine.c, fine.wt)
        }
        if (top > 0) upsample(levels[top], w, h, 1 shl top, levels[0].c, null)
        return levels[0].c
    }

    /**
     * Bilinear upsampling of [coarse] by [scale] into [out] ([w] x [h]),
     * blended with what [out] holds by its [own] weight; every pixel is
     * replaced when [own] is null.
     */
    private fun upsample(coarse: Level, w: Int, h: Int, scale: Int, out: FloatArray, own: FloatArray?) {
        val inv = 1f / scale
        val shift = 0.5f * inv - 0.5f
        val x0 = IntArray(w)
        val x1 = IntArray(w)
        val tx = FloatArray(w)
        for (x in 0 until w) {
            val fx = x * inv + shift
            val i0 = floor(fx).toInt().coerceIn(0, coarse.w - 1)
            x0[x] = 3 * i0
            x1[x] = 3 * min(i0 + 1, coarse.w - 1)
            tx[x] = (fx - i0).coerceIn(0f, 1f)
        }
        val c = coarse.c
        val row = FloatArray(3 * coarse.w)
        for (y in 0 until h) {
            val fy = y * inv + shift
            val y0 = floor(fy).toInt().coerceIn(0, coarse.h - 1)
            val r0 = 3 * y0 * coarse.w
            val r1 = 3 * min(y0 + 1, coarse.h - 1) * coarse.w
            val ty = (fy - y0).coerceIn(0f, 1f)
            for (k in row.indices) row[k] = c[r0 + k] + (c[r1 + k] - c[r0 + k]) * ty
            val base = 3 * y * w
            for (x in 0 until w) {
                val keep = if (own == null) 0f else own[y * w + x]
                if (keep >= 1f) continue
                val a = x0[x]
                val b = x1[x]
                val t = tx[x]
                val o = base + 3 * x
                val r = row[a] + (row[b] - row[a]) * t
                val g = row[a + 1] + (row[b + 1] - row[a + 1]) * t
                val bl = row[a + 2] + (row[b + 2] - row[a + 2]) * t
                if (keep <= 0f) {
                    out[o] = r
                    out[o + 1] = g
                    out[o + 2] = bl
                } else {
                    out[o] = keep * out[o] + (1f - keep) * r
                    out[o + 1] = keep * out[o + 1] + (1f - keep) * g
                    out[o + 2] = keep * out[o + 2] + (1f - keep) * bl
                }
            }
        }
    }
}
