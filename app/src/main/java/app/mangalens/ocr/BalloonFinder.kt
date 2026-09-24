package app.mangalens.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * One detected balloon.
 *
 * [box] is in full-resolution page coordinates. [mask] is the flooded
 * interior itself — row-major over the component's bounding box at the
 * analysis resolution, [maskW] by [maskH]. The overlay paints an opaque
 * cleaning fill through it, and the fill must stop where the balloon does:
 * painted through the box instead, every curve and tail squares off onto the
 * art around it. [inverted] marks a dark balloon carrying light lettering, so
 * the fill and the text drawn over it flip polarity together. [partial]
 * marks a balloon the frame edge cuts through — only part of it is on
 * screen, so it is trusted only where OCR actually read lettering inside it.
 */
data class Balloon(
    val box: Rect,
    val maskW: Int,
    val maskH: Int,
    val mask: BooleanArray,
    val inverted: Boolean,
    val partial: Boolean = false,
)

/**
 * Everything one binarization of the page yields: the balloons, and the
 * panel grid read off the gutters between panels.
 */
class PageScan(val balloons: List<Balloon>, val panels: List<Rect>)

/**
 * Finds speech balloons in the page pixels, independently of what OCR managed
 * to read.
 *
 * Clustering OCR lines by proximity infers a balloon from its contents, and
 * inherits every one of OCR's mistakes. A large balloon with generously spaced
 * columns — or one with a furigana column wedged between two kanji columns —
 * splits into several regions, and each fragment is then handed to the
 * translator as though it were a whole utterance. A model given 「よ」 with no
 * sentence around it does not decline to answer; it invents a line that fits.
 * The wrong translations and the blank balloons on a hard page are the same
 * defect seen twice.
 *
 * A balloon is instead detected for what it physically is: an enclosed light
 * region, bounded by dark ink, containing dark lettering. The dark outline
 * breaks the balloon's interior away from the page background, so it falls out
 * as its own connected component. This inverts the dependency that matters —
 * a balloon whose vertical text ML Kit garbled completely is still found, and
 * still becomes a region the vision model can read off the image.
 *
 * The page is analysed on a coarse grid, but every cell of that grid is
 * summarised three ways from the full-resolution pixels — mean, darkest and
 * lightest luminance — rather than by averaging alone. Averaging is what
 * made the detector blind on tablets: at a quarter scale a two-pixel outline
 * averages to mid-grey, reads as paper, and the balloon's interior leaks into
 * the page. The darkest pixel in a cell survives any downscale, so an outline
 * one pixel wide is still a wall the flood cannot cross. Fill is then judged
 * on the interior with its lettering holes filled back in, so a balloon
 * packed with text is as blobby as an empty one.
 *
 * Black narration boxes and flashback panels are the same object with the
 * polarity flipped: an enclosed dark region carrying light lettering. To the
 * light model their interior is indistinguishable from ink, so a mirrored
 * pass floods enclosed dark components under the same gates.
 *
 * Two balloons drawn overlapping — the joined balloons a letterer uses for
 * one character's consecutive lines, or two speakers' balloons touching —
 * flood as one component. The component is eroded until it falls into
 * separate cores and each core grows back over its own share, so each
 * balloon keeps its own region, its own text and its own card.
 */
object BalloonFinder {

    /** Analysis resolution. Balloons are large features; detail buys nothing. */
    private const val WORK_DIM = 640

    /** Mean luminance at or above this is balloon interior rather than ink or tone. */
    private const val LIGHT = 200

    /**
     * Interior floor for the tinted pass. Romance manhwa fills balloons with
     * pastel pinks and blues that sit under [LIGHT] and were invisible to
     * the white model. The looser floor would also admit the page and every
     * white balloon, so the pass leans on the same enclosure and shape gates
     * and on dedup against the earlier passes.
     */
    private const val TINTED_INTERIOR = 150

    /** Mean luminance below this counts as lettering. */
    private const val DARK = 128

    /**
     * Mean luminance at or below this is the interior of an inverted balloon.
     * Deliberately below [DARK]: downscaling blurs screentone toward
     * mid-grey, and the band between the two thresholds keeps that tone as
     * lettering only, never an interior the inverted flood could spread
     * through.
     */
    private const val INVERTED_INTERIOR = 110

    /**
     * A cell whose darkest pixel is below this is not paper: it holds ink, a
     * tone dot, a grey outline or a stroke of art, however thin. This is the
     * wall a light flood stops at, and it is what keeps a hairline outline a
     * wall at any resolution.
     */
    private const val PAPER_FLOOR = 170

    /**
     * A cell whose lightest pixel is above this is not the inside of a dark
     * box: it holds light lettering or the paper around the box.
     */
    private const val DARK_CEILING = 150

    /**
     * A cell that is neither paper nor ink and varies little within itself
     * is shading, colour or a soft gradient — the texture of drawn art, and
     * never of a lettered balloon. Cells this flat are counted against a
     * candidate: a white panel with a character sketched in it is an
     * enclosed light region holding dark marks, and only the tone in the art
     * tells it apart from a balloon.
     */
    private const val FLAT_CONTRAST = 48

    /** Share of a balloon's interior that may be art-like before it is rejected. */
    private const val MAX_ART = 0.12f

    /** Fractions of the analysed page a balloon may occupy. */
    private const val MIN_AREA = 0.0012f
    private const val MAX_AREA = 0.30f

    /**
     * A balloon the frame edge cuts through can only be a modest share of the
     * screen; anything larger touching an edge is a panel or the page.
     */
    private const val PARTIAL_MAX_AREA = 0.08f

    /**
     * Component pixels over bounding-box area. Balloons are blobby and convex —
     * an ellipse fills about 0.79 of its box. The floor rejects ragged
     * highlights in the artwork.
     *
     * The ceiling rejects rectangles, and it is load-bearing: a panel with a
     * white background is itself an enclosed light region bounded by ink, and
     * satisfies every other test here. Taken for a balloon, it swallows every
     * balloon drawn inside it and welds their separate lines into one — two
     * characters speaking in one card, centred on the panel.
     */
    private const val MIN_FILL = 0.55f
    private const val MAX_FILL = 0.93f

    /**
     * The raw interior — the flooded cells alone, lettering excluded — may
     * fall this far under the fill floor before hole-filling is asked to
     * rescue the shape. Dense lettering can hide half a balloon's interior;
     * a hole any larger than that is art, not text.
     */
    private const val RAW_FILL_SLACK = 0.5f

    /** Share of the interior that must be lettering for a blob to be a balloon. */
    private const val MIN_INK = 0.02f
    private const val MAX_INK = 0.60f

    /**
     * Radii (work pixels) the ink is thickened by when hunting burst
     * balloons. Their border is a ring of radiating ticks, and the flood
     * leaks out through the gaps between them; thickening the ticks seals
     * gaps up to twice the radius. Two radii cover the tick spacing shout
     * balloons are drawn with at phone and at tablet resolution.
     */
    private val BURST_SEAL_RADII = intArrayOf(2, 3)

    /**
     * Fill floor for the sealed passes. Sealing eats the component's rim and
     * fattens the lettering inside it, so a burst balloon's interior reads
     * thinner than an ordinary balloon's; the ordinary floor would reject it.
     */
    private const val BURST_MIN_FILL = 0.38f

    /**
     * A detection centred inside another and at least this share of its
     * area is the same balloon found again by a later pass; anything
     * smaller is a balloon inside a panel.
     */
    private const val SAME_BALLOON_MIN_SHARE = 0.4f

    /** Smallest share of a joined component either balloon must be to split it. */
    private const val MIN_PART_SHARE = 0.16f

    /**
     * Widest neck, as a share of the smaller part's narrow dimension, that
     * still reads as two balloons touching rather than one balloon with a
     * waist.
     */
    private const val MAX_NECK_RATIO = 0.7f

    /** The boxes alone, exactly as [findDetailed] carries them in [Balloon.box]. */
    fun find(
        bitmap: Bitmap,
        ignoreTopPx: Int = 0,
        ignoreBottomPx: Int = 0,
        exclusions: List<Rect> = emptyList(),
    ): List<Rect> = findDetailed(bitmap, ignoreTopPx, ignoreBottomPx, exclusions).map { it.box }

    /**
     * Detects balloon regions, in full-resolution page coordinates, each
     * carrying its interior mask and polarity.
     *
     * @param exclusions regions never to report, such as the app's own overlay.
     */
    fun findDetailed(
        bitmap: Bitmap,
        ignoreTopPx: Int = 0,
        ignoreBottomPx: Int = 0,
        exclusions: List<Rect> = emptyList(),
    ): List<Balloon> = analyze(bitmap, ignoreTopPx, ignoreBottomPx, exclusions).balloons

    /** Balloons and panels from one binarization of the page. */
    fun analyze(
        bitmap: Bitmap,
        ignoreTopPx: Int = 0,
        ignoreBottomPx: Int = 0,
        exclusions: List<Rect> = emptyList(),
    ): PageScan {
        val scale = min(1f, WORK_DIM.toFloat() / max(bitmap.width, bitmap.height))
        val w = max(1, (bitmap.width * scale).toInt())
        val h = max(1, (bitmap.height * scale).toInt())
        if (w < 16 || h < 16) return PageScan(emptyList(), emptyList())

        val planes = Planes.of(bitmap, w, h)
        val n = w * h
        val light = BooleanArray(n)
        val dark = BooleanArray(n)
        val darkInterior = BooleanArray(n)
        val tinted = BooleanArray(n)
        val paper = BooleanArray(n)
        val inkWall = BooleanArray(n)
        val solidDark = BooleanArray(n)
        val edge = BooleanArray(n)
        val flatMid = BooleanArray(n)
        val flatDim = BooleanArray(n)
        for (i in 0 until n) {
            val mean = planes.mean[i]
            val lo = planes.min[i]
            val hi = planes.max[i]
            light[i] = mean >= LIGHT
            dark[i] = mean < DARK
            darkInterior[i] = mean <= INVERTED_INTERIOR
            tinted[i] = mean >= TINTED_INTERIOR
            paper[i] = lo >= PAPER_FLOOR
            inkWall[i] = hi >= DARK_CEILING
            solidDark[i] = hi < 90
            // A drawn line: solid ink, or a cell straddling light and dark.
            edge[i] = lo < 90 || hi - lo >= 100
            val flat = hi - lo <= FLAT_CONTRAST
            flatMid[i] = flat && mean in DARK until LIGHT
            flatDim[i] = flat && mean in (INVERTED_INTERIOR + 1) until LIGHT
        }
        // The light flood may only enter paper: a cell holding any ink at
        // all is a wall. Lettering fattens by up to a cell each side, which
        // hole-filling gives back.
        val open = BooleanArray(n) { light[it] && paper[it] }
        val geom = Geometry(w, h, scale, bitmap, ignoreTopPx, ignoreBottomPx, exclusions)

        // Components within one pass are disjoint by construction; only the
        // later passes need deduping against what the earlier ones found.
        val out = ArrayList<Balloon>()
        fun add(found: List<Balloon>) {
            for (b in found) if (out.none { sameBalloon(it.box, b.box) }) out.add(b)
        }

        out.addAll(sweep(Pass(open, dark, flatMid, MIN_FILL, inverted = false, allowEdge = true), geom))

        // Second pass for burst balloons — the shouted lines whose border is
        // a ring of radiating ticks rather than a drawn curve. The gaps
        // between ticks let the flood leak into the page, so the balloon is
        // never enclosed and pass one cannot see it: exactly the balloons
        // that then went untranslated or, worse, floated to wherever a model
        // guessed. Thickened ink seals the gaps and they fall out as
        // ordinary components.
        val ink = BooleanArray(n) { !paper[it] }
        for (r in BURST_SEAL_RADII) {
            val sealed = dilate(ink, w, h, r)
            val sealedOpen = BooleanArray(n) { open[it] && !sealed[it] }
            val pass = Pass(sealedOpen, dark, flatMid, BURST_MIN_FILL, inverted = false, allowEdge = r == BURST_SEAL_RADII[0], giveBack = open, seal = r)
            add(sweep(pass, geom, known = out))
        }

        // Pastel balloons: the interior threshold relaxes to catch pink and
        // blue fills, every other gate stays, and anything the white pass
        // already found dedupes away. Paper here is anything without real
        // ink in it, since the fill itself sits under the paper floor.
        val tintedOpen = BooleanArray(n) { tinted[it] && planes.min[it] >= DARK }
        val flatTint = BooleanArray(n) { (planes.max[it] - planes.min[it] <= FLAT_CONTRAST) && planes.mean[it] in DARK until TINTED_INTERIOR }
        add(sweep(Pass(tintedOpen, dark, flatTint, MIN_FILL, inverted = false, allowEdge = false), geom))

        // Third pass, polarity flipped: the black narration and flashback
        // boxes whose interior is exactly what the light model calls ink, so
        // the first two passes cannot see them at all — those boxes simply
        // went untranslated on every flashback page.
        val darkOpen = BooleanArray(n) { darkInterior[it] && !inkWall[it] }
        add(sweep(Pass(darkOpen, light, flatDim, MIN_FILL, inverted = true, allowEdge = false), geom))

        val balloons = dropContainers(out)
        val panels = PageLayout.panels(paper, solidDark, edge, w, h).map { r ->
            Rect(
                (r.left / scale).toInt(),
                (r.top / scale).toInt(),
                ((r.right) / scale).toInt().coerceAtMost(bitmap.width),
                ((r.bottom) / scale).toInt().coerceAtMost(bitmap.height),
            )
        }
        return PageScan(balloons, panels)
    }

    /**
     * Per-cell luminance summary of the page at analysis resolution: the
     * mean, and the darkest and lightest pixel each cell contains.
     */
    internal class Planes(val w: Int, val h: Int, val mean: IntArray, val min: IntArray, val max: IntArray) {
        companion object {
            fun of(bitmap: Bitmap, w: Int, h: Int): Planes {
                val srcW = bitmap.width
                val srcH = bitmap.height
                val n = w * h
                val sum = IntArray(n)
                val cnt = IntArray(n)
                val lo = IntArray(n) { 255 }
                val hi = IntArray(n)
                val cxOf = IntArray(srcW) { (it.toLong() * w / srcW).toInt().coerceAtMost(w - 1) }
                val band = min(srcH, max(1, (1 shl 20) / max(1, srcW)))
                val px = IntArray(srcW * band)
                var y = 0
                while (y < srcH) {
                    val rows = min(band, srcH - y)
                    bitmap.getPixels(px, 0, srcW, 0, y, srcW, rows)
                    for (r in 0 until rows) {
                        val cy = ((y + r).toLong() * h / srcH).toInt().coerceAtMost(h - 1)
                        val cellRow = cy * w
                        val srcRow = r * srcW
                        for (x in 0 until srcW) {
                            val p = px[srcRow + x]
                            val lum = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                            val i = cellRow + cxOf[x]
                            sum[i] += lum
                            cnt[i]++
                            if (lum < lo[i]) lo[i] = lum
                            if (lum > hi[i]) hi[i] = lum
                        }
                    }
                    y += rows
                }
                val mean = IntArray(n)
                for (i in 0 until n) {
                    if (cnt[i] > 0) {
                        mean[i] = sum[i] / cnt[i]
                    } else {
                        // No source pixel landed here; only possible when the
                        // page is smaller than the grid. Read it as blank paper.
                        mean[i] = 255
                        lo[i] = 255
                        hi[i] = 255
                    }
                }
                return Planes(w, h, mean, lo, hi)
            }
        }
    }

    /** One flood configuration. */
    private class Pass(
        /** Cells the flood may enter. */
        val open: BooleanArray,
        /** Cells of the opposite polarity that must appear inside in believable quantity. */
        val lettering: BooleanArray,
        /** Cells that read as drawn art rather than paper or lettering. */
        val art: BooleanArray,
        val minFill: Float,
        val inverted: Boolean,
        /** Whether a component cut by the frame edge may be reported as partial. */
        val allowEdge: Boolean,
        /**
         * For a sealed pass, the cells open before sealing, and how many
         * cells the ink was thickened by: a find is flooded out over them
         * again ([balloonOf]).
         */
        val giveBack: BooleanArray? = null,
        val seal: Int = 0,
    )

    private class Geometry(
        val w: Int,
        val h: Int,
        val scale: Float,
        val bitmap: Bitmap,
        val ignoreTopPx: Int,
        val ignoreBottomPx: Int,
        val exclusions: List<Rect>,
    )

    /**
     * One connected-component sweep over [pass]. A find that is the same
     * balloon as one in [known] is dropped before it is flooded out again.
     */
    private fun sweep(pass: Pass, g: Geometry, known: List<Balloon> = emptyList()): List<Balloon> {
        val w = g.w
        val h = g.h
        val open = pass.open
        val out = ArrayList<Balloon>()
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val cells = IntArray(w * h)
        val minArea = (MIN_AREA * w * h).toInt().coerceAtLeast(24)
        val maxArea = (MAX_AREA * w * h).toInt()
        val partialMaxArea = (PARTIAL_MAX_AREA * w * h).toInt()

        for (start in open.indices) {
            if (!open[start] || seen[start]) continue

            // Flood the region. Iterative: a full-page background component
            // would blow a recursive stack.
            var top = 0
            stack[top++] = start
            seen[start] = true
            var count = 0
            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0
            var edges = 0

            while (top > 0) {
                val idx = stack[--top]
                val x = idx % w
                val y = idx / w
                cells[count++] = idx
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x == 0) edges = edges or 1
                if (y == 0) edges = edges or 2
                if (x == w - 1) edges = edges or 4
                if (y == h - 1) edges = edges or 8

                if (x > 0) push(stack, top, idx - 1, open, seen)?.let { top = it }
                if (x < w - 1) push(stack, top, idx + 1, open, seen)?.let { top = it }
                if (y > 0) push(stack, top, idx - w, open, seen)?.let { top = it }
                if (y < h - 1) push(stack, top, idx + w, open, seen)?.let { top = it }
            }

            // The page background — either polarity — is huge and runs to the
            // edge. A balloon the edge cuts through also runs to it, but only
            // to one edge, and stays a modest share of the screen.
            val partial = edges != 0
            if (partial) {
                if (!pass.allowEdge || Integer.bitCount(edges) != 1 || count > partialMaxArea) continue
            }
            if (count < minArea || count > maxArea) continue

            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            if (boxW < 10 || boxH < 10) continue
            val boxArea = boxW * boxH
            val rawFill = count.toFloat() / boxArea
            // Extreme slivers are panel highlights, not balloons.
            val ratio = boxW.toFloat() / boxH
            if (ratio > 12f || ratio < 1f / 12f) continue

            val mask = BooleanArray(boxArea)
            for (k in 0 until count) {
                val cell = cells[k]
                mask[(cell / w - minY) * boxW + (cell % w - minX)] = true
            }
            // The flood only ever enters interior-colored cells, so the mask
            // arrives with letter-shaped holes exactly where the lettering
            // is — and a cleaning fill painted through it would spare the
            // very text it exists to erase. Anything enclosed by interior is
            // interior: flood the complement from the box border and claim
            // whatever it cannot reach.
            fillHoles(mask, boxW, boxH)

            val accepted = judge(mask, boxW, boxH, minX, minY, rawFill, pass, g)
            if (!accepted) continue

            // One component can be two balloons drawn touching. Erode it
            // until it falls into separate cores; if both cores are real
            // balloons in their own right, report each on its own.
            val parts = splitJoined(mask, boxW, boxH, pass, g, minX, minY)
            if (parts != null) {
                out.addAll(parts)
                continue
            }
            if (known.any { sameBalloon(it.box, pageRect(minX, minY, boxW, boxH, g)) }) continue
            out.add(balloonOf(mask, boxW, boxH, minX, minY, pass, partial, g))
        }
        return out
    }

    /**
     * Applies the shape and content gates to one hole-filled component.
     * [rawFill] is the flooded share of the box before hole-filling — a
     * rectangle is a panel however much text it holds, and a hole larger
     * than the lettering could make is art.
     */
    private fun judge(
        mask: BooleanArray,
        boxW: Int,
        boxH: Int,
        minX: Int,
        minY: Int,
        rawFill: Float,
        pass: Pass,
        g: Geometry,
    ): Boolean {
        if (rawFill > MAX_FILL) return false
        var filled = 0
        for (m in mask) if (m) filled++
        val fill = filled.toFloat() / (boxW * boxH)
        if (fill < pass.minFill) return false
        if (rawFill < pass.minFill * RAW_FILL_SLACK) return false

        // A balloon holds lettering of the opposite polarity. A blank
        // highlight — or a featureless patch of night sky — does not. And it
        // holds nothing else: shading and colour inside it mean it is a
        // panel with art in it, not a balloon with text in it.
        var ink = 0
        var art = 0
        for (y in 0 until boxH) {
            val row = (minY + y) * g.w + minX
            val mrow = y * boxW
            for (x in 0 until boxW) {
                if (!mask[mrow + x]) continue
                val i = row + x
                if (pass.lettering[i]) ink++
                if (pass.art[i]) art++
            }
        }
        val inkFraction = ink.toFloat() / filled
        if (inkFraction < MIN_INK || inkFraction > MAX_INK) return false
        if (art.toFloat() / filled > MAX_ART) return false

        val full = pageRect(minX, minY, boxW, boxH, g)
        if (full.bottom <= g.ignoreTopPx || full.top >= g.bitmap.height - g.ignoreBottomPx) return false
        if (g.exclusions.any { Rect.intersects(it, full) }) return false
        return true
    }

    private fun pageRect(minX: Int, minY: Int, boxW: Int, boxH: Int, g: Geometry) = Rect(
        (minX / g.scale).toInt(),
        (minY / g.scale).toInt(),
        ((minX + boxW) / g.scale).toInt(),
        ((minY + boxH) / g.scale).toInt(),
    )

    /**
     * The balloon [mask] describes: a component's hole-filled interior, its
     * box at [minX], [minY] in work cells.
     *
     * A sealed pass's find is flooded out again over the unsealed paper
     * ([Pass.giveBack]). Thickening the ink by [Pass.seal] cells ate that
     * much off the balloon's paper along its outline and fattened the
     * lettering by as much, so a line set close to the outline fused with
     * it, never read as a hole in the paper, and was left out of the mask:
     * the cleaning painted round it and the line stayed on the page. The
     * unsealed flood reaches the paper between that line and the outline
     * again and closes round it. It goes no further than the find's own
     * hull — the cells lying between cells of the find along a row or a
     * column — and the rim sealing ate round that, so it barely reaches
     * through the break in the outline that needed sealing.
     */
    private fun balloonOf(
        mask: BooleanArray,
        boxW: Int,
        boxH: Int,
        minX: Int,
        minY: Int,
        pass: Pass,
        partial: Boolean,
        g: Geometry,
    ): Balloon {
        val back = pass.giveBack
        val r = pass.seal
        if (back == null || r <= 0) return Balloon(pageRect(minX, minY, boxW, boxH, g), boxW, boxH, mask, pass.inverted, partial)
        val x0 = max(0, minX - r - 1)
        val y0 = max(0, minY - r - 1)
        val gw = min(g.w, minX + boxW + r + 1) - x0
        val gh = min(g.h, minY + boxH + r + 1) - y0
        val n = gw * gh
        val own = BooleanArray(n)
        val rowL = IntArray(gh) { gw }
        val rowR = IntArray(gh) { -1 }
        val colT = IntArray(gw) { gh }
        val colB = IntArray(gw) { -1 }
        for (y in 0 until boxH) {
            for (x in 0 until boxW) {
                if (!mask[y * boxW + x]) continue
                val ex = x + minX - x0
                val ey = y + minY - y0
                own[ey * gw + ex] = true
                rowL[ey] = min(rowL[ey], ex)
                rowR[ey] = max(rowR[ey], ex)
                colT[ex] = min(colT[ex], ey)
                colB[ex] = max(colB[ex], ey)
            }
        }
        val hull = BooleanArray(n) { i ->
            val x = i % gw
            val y = i / gw
            x in rowL[y]..rowR[y] || y in colT[x]..colB[x]
        }
        val reach = dilate(hull, gw, gh, r + 1)
        val flooded = own.copyOf()
        val stack = IntArray(n)
        var top = 0
        for (i in 0 until n) if (own[i]) stack[top++] = i
        fun flood(i: Int) {
            if (!flooded[i] && reach[i] && back[(i / gw + y0) * g.w + i % gw + x0]) {
                flooded[i] = true
                stack[top++] = i
            }
        }
        while (top > 0) {
            val i = stack[--top]
            val x = i % gw
            if (x > 0) flood(i - 1)
            if (x < gw - 1) flood(i + 1)
            if (i >= gw) flood(i - gw)
            if (i + gw < n) flood(i + gw)
        }
        fillHoles(flooded, gw, gh)
        var l = gw
        var t = gh
        var rt = -1
        var bt = -1
        for (i in 0 until n) {
            if (!flooded[i]) continue
            val x = i % gw
            val y = i / gw
            if (x < l) l = x
            if (x > rt) rt = x
            if (y < t) t = y
            if (y > bt) bt = y
        }
        val cw = rt - l + 1
        val ch = bt - t + 1
        val cropped = BooleanArray(cw * ch) { j -> flooded[(t + j / cw) * gw + l + j % cw] }
        return Balloon(pageRect(x0 + l, y0 + t, cw, ch, g), cw, ch, cropped, pass.inverted, partial)
    }

    // ---- joined balloons ----

    /**
     * Splits a component that is really two (or more) balloons touching.
     *
     * The filled mask is eroded one cell at a time. A single balloon shrinks
     * to a single core and then to nothing; two joined balloons part at the
     * neck between them into two cores well before either vanishes. Each
     * core then grows back across the original mask, claiming the cells
     * nearest to it, and every part is judged as a balloon in its own right —
     * a tail or a waist that erodes into a "core" without lettering of its
     * own does not count, and the whole component is kept as one.
     *
     * Returns null when the component is one balloon.
     */
    private fun splitJoined(
        mask: BooleanArray,
        boxW: Int,
        boxH: Int,
        pass: Pass,
        g: Geometry,
        minX: Int,
        minY: Int,
    ): List<Balloon>? {
        var filled = 0
        for (m in mask) if (m) filled++
        val minCore = max(12, (filled * MIN_PART_SHARE).toInt())
        if (filled < minCore * 2) return null
        val dist = distanceTransform(mask, boxW, boxH)
        val maxR = min(boxW, boxH) / 4
        val eroded = BooleanArray(mask.size)
        for (r in 1..maxR) {
            var any = false
            for (i in mask.indices) {
                eroded[i] = mask[i] && dist[i] > r
                if (eroded[i]) any = true
            }
            if (!any) return null
            val cores = components(eroded, boxW, boxH).filter { it.size >= minCore }
            if (cores.size < 2) continue

            val owner = grow(mask, boxW, boxH, cores)
            val parts = cores.indices.map { k ->
                var l = boxW
                var t = boxH
                var rgt = -1
                var btm = -1
                for (i in mask.indices) {
                    if (owner[i] != k) continue
                    val x = i % boxW
                    val y = i / boxW
                    if (x < l) l = x
                    if (x > rgt) rgt = x
                    if (y < t) t = y
                    if (y > btm) btm = y
                }
                Rect(l, t, rgt + 1, btm + 1)
            }
            // The neck between the parts must be narrow next to the parts
            // themselves, and the erosion that found it shallow: a wide
            // waist is one balloon's shape, not two balloons' contact.
            var neck = 0
            for (i in mask.indices) {
                val o = owner[i]
                if (o < 0) continue
                val x = i % boxW
                if (x + 1 < boxW && owner[i + 1] >= 0 && owner[i + 1] != o) neck++
                if (i + boxW < mask.size && owner[i + boxW] >= 0 && owner[i + boxW] != o) neck++
            }
            val narrowest = parts.minOf { min(it.width(), it.height()) }
            if (neck > narrowest * MAX_NECK_RATIO) return null
            if (r > narrowest * 0.3f) return null

            val out = ArrayList<Balloon>(parts.size)
            for ((k, box) in parts.withIndex()) {
                val pw = box.width()
                val ph = box.height()
                if (pw < 10 || ph < 10) return null
                val pm = BooleanArray(pw * ph)
                var raw = 0
                for (y in 0 until ph) {
                    for (x in 0 until pw) {
                        val i = (box.top + y) * boxW + (box.left + x)
                        if (owner[i] == k) {
                            pm[y * pw + x] = true
                            raw++
                        }
                    }
                }
                val ratio = pw.toFloat() / ph
                if (ratio > 12f || ratio < 1f / 12f) return null
                // Each part is a hole-filled shape already; its raw fill is
                // the same share, and the gates ask each part to be a balloon
                // holding lettering of its own.
                val rawFill = raw.toFloat() / (pw * ph)
                if (!judge(pm, pw, ph, minX + box.left, minY + box.top, rawFill, pass, g)) return null
                out.add(Balloon(pageRect(minX + box.left, minY + box.top, pw, ph, g), pw, ph, pm, pass.inverted, false))
            }
            return out
        }
        return null
    }

    /** 4-neighbour distance from each mask cell to the nearest cell outside it (or the border). */
    private fun distanceTransform(mask: BooleanArray, w: Int, h: Int): IntArray {
        val inf = w + h
        val d = IntArray(mask.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!mask[i]) {
                    d[i] = 0
                    continue
                }
                var best = min(x + 1, min(y + 1, min(w - x, h - y)))
                if (x > 0) best = min(best, d[i - 1] + 1)
                if (y > 0) best = min(best, d[i - w] + 1)
                d[i] = min(best, inf)
            }
        }
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                val i = y * w + x
                if (!mask[i]) continue
                var best = d[i]
                if (x < w - 1) best = min(best, d[i + 1] + 1)
                if (y < h - 1) best = min(best, d[i + w] + 1)
                d[i] = best
            }
        }
        return d
    }

    /** Connected components (4-neighbour) of [open], each as its cell indices. */
    private fun components(open: BooleanArray, w: Int, h: Int): List<IntArray> {
        val seen = BooleanArray(open.size)
        val stack = IntArray(open.size)
        val out = ArrayList<IntArray>()
        val buf = IntArray(open.size)
        for (start in open.indices) {
            if (!open[start] || seen[start]) continue
            var top = 0
            var count = 0
            stack[top++] = start
            seen[start] = true
            while (top > 0) {
                val idx = stack[--top]
                buf[count++] = idx
                val x = idx % w
                val y = idx / w
                if (x > 0) push(stack, top, idx - 1, open, seen)?.let { top = it }
                if (x < w - 1) push(stack, top, idx + 1, open, seen)?.let { top = it }
                if (y > 0) push(stack, top, idx - w, open, seen)?.let { top = it }
                if (y < h - 1) push(stack, top, idx + w, open, seen)?.let { top = it }
            }
            out.add(buf.copyOf(count))
        }
        return out
    }

    /**
     * Grows every core back over [mask] at once, breadth-first, so each
     * mask cell ends up owned by the core it is nearest to along the
     * interior. Returns the owner index per cell, -1 outside the mask.
     */
    private fun grow(mask: BooleanArray, w: Int, h: Int, cores: List<IntArray>): IntArray {
        val owner = IntArray(mask.size) { -1 }
        val queue = IntArray(mask.size)
        var head = 0
        var tail = 0
        for ((k, core) in cores.withIndex()) {
            for (i in core) {
                owner[i] = k
                queue[tail++] = i
            }
        }
        while (head < tail) {
            val idx = queue[head++]
            val k = owner[idx]
            val x = idx % w
            val y = idx / w
            if (x > 0 && mask[idx - 1] && owner[idx - 1] < 0) { owner[idx - 1] = k; queue[tail++] = idx - 1 }
            if (x < w - 1 && mask[idx + 1] && owner[idx + 1] < 0) { owner[idx + 1] = k; queue[tail++] = idx + 1 }
            if (y > 0 && mask[idx - w] && owner[idx - w] < 0) { owner[idx - w] = k; queue[tail++] = idx - w }
            if (y < h - 1 && mask[idx + w] && owner[idx + w] < 0) { owner[idx + w] = k; queue[tail++] = idx + w }
        }
        return owner
    }

    // ---- shared helpers ----

    /**
     * True when the two detections describe one balloon. The sealed passes
     * find a balloon again with its rim eaten — smaller, centred on the
     * same spot — and that copy must fold into the first find. A region
     * holding another's centre but several times its size is not a copy;
     * it is something the balloon sits inside, and [dropContainers] must
     * get to see both.
     */
    private fun sameBalloon(a: Rect, b: Rect): Boolean {
        if (a.contains(b.centerX(), b.centerY()) || b.contains(a.centerX(), a.centerY())) {
            val areaA = a.width().toLong() * a.height()
            val areaB = b.width().toLong() * b.height()
            if (min(areaA, areaB) >= max(areaA, areaB) * SAME_BALLOON_MIN_SHARE) return true
        }
        val ix = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val iy = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (ix <= 0 || iy <= 0) return false
        val inter = ix.toLong() * iy
        val union = a.width().toLong() * a.height() + b.width().toLong() * b.height() - inter
        return inter.toFloat() / union > 0.3f
    }

    /**
     * Marks every false cell not reachable from the box border as interior.
     * The lettering (and, for burst balloons, the sealed halo around it) is
     * enclosed by flooded cells on all sides; the true outside always touches
     * the bounding box edge.
     */
    private fun fillHoles(mask: BooleanArray, w: Int, h: Int) {
        val outside = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        var top = 0

        fun seed(idx: Int) {
            if (!mask[idx] && !outside[idx]) {
                outside[idx] = true
                stack[top++] = idx
            }
        }
        for (x in 0 until w) {
            seed(x)
            seed((h - 1) * w + x)
        }
        for (y in 0 until h) {
            seed(y * w)
            seed(y * w + w - 1)
        }
        while (top > 0) {
            val idx = stack[--top]
            val x = idx % w
            val y = idx / w
            if (x > 0) seed(idx - 1)
            if (x < w - 1) seed(idx + 1)
            if (y > 0) seed(idx - w)
            if (y < h - 1) seed(idx + w)
        }
        for (i in mask.indices) {
            if (!mask[i] && !outside[i]) mask[i] = true
        }
    }

    /** Box dilation by [r], run as horizontal then vertical passes. */
    private fun dilate(mask: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val horiz = BooleanArray(mask.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (!mask[row + x]) continue
                val from = max(0, x - r)
                val to = min(w - 1, x + r)
                for (x2 in from..to) horiz[row + x2] = true
            }
        }
        val out = BooleanArray(mask.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (!horiz[row + x]) continue
                val from = max(0, y - r)
                val to = min(h - 1, y + r)
                for (y2 in from..to) out[y2 * w + x] = true
            }
        }
        return out
    }

    /**
     * Removes regions that enclose other regions.
     *
     * No balloon contains another balloon, so a region that does is something
     * a balloon sits inside — a light panel, an inset, a page margin caught
     * between borders. Left in, it claims the text of everything it contains
     * and merges separate speakers into a single line.
     */
    private fun dropContainers(found: List<Balloon>): List<Balloon> {
        if (found.size < 2) return found
        return found.filter { outer ->
            found.none { inner -> inner !== outer && encloses(outer.box, inner.box) }
        }
    }

    /** True when [outer] holds essentially all of [inner] and is clearly bigger. */
    private fun encloses(outer: Rect, inner: Rect): Boolean {
        if (!outer.contains(inner.centerX(), inner.centerY())) return false
        val ix = minOf(outer.right, inner.right) - maxOf(outer.left, inner.left)
        val iy = minOf(outer.bottom, inner.bottom) - maxOf(outer.top, inner.top)
        if (ix <= 0 || iy <= 0) return false
        val innerArea = inner.width().toLong() * inner.height()
        if (innerArea <= 0L) return false
        val covered = (ix.toLong() * iy).toFloat() / innerArea
        val outerArea = outer.width().toLong() * outer.height()
        return covered > 0.9f && outerArea > innerArea * 1.3f
    }

    /** Pushes a neighbour if it is unvisited and open to the flood; returns the new stack top. */
    private fun push(
        stack: IntArray,
        top: Int,
        idx: Int,
        open: BooleanArray,
        seen: BooleanArray,
    ): Int? {
        if (!open[idx] || seen[idx]) return null
        seen[idx] = true
        stack[top] = idx
        return top + 1
    }
}
