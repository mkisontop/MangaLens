package app.mangalens.ocr

import android.graphics.Bitmap
import android.graphics.Color
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
 * the fill and the text drawn over it flip polarity together.
 */
data class Balloon(
    val box: Rect,
    val maskW: Int,
    val maskH: Int,
    val mask: BooleanArray,
    val inverted: Boolean,
)

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
 * Black narration boxes and flashback panels are the same object with the
 * polarity flipped: an enclosed dark region carrying light lettering. To the
 * light model their interior is indistinguishable from ink, so a mirrored
 * pass floods enclosed dark components under the same gates. It needs no
 * sealed variant — dark boxes are drawn solid, not as rings of ticks.
 */
object BalloonFinder {

    /** Analysis resolution. Balloons are large features; detail buys nothing. */
    private const val WORK_DIM = 640

    /** Luminance at or above this is balloon interior rather than ink or tone. */
    private const val LIGHT = 200

    /**
     * Interior floor for the tinted pass. Romance manhwa fills balloons with
     * pastel pinks and blues that sit under [LIGHT] and were invisible to
     * the white model. The looser floor would also admit the page and every
     * white balloon, so the pass leans on the same enclosure and shape gates
     * and on dedup against the earlier passes.
     */
    private const val TINTED_INTERIOR = 150

    /** Luminance below this counts as lettering. */
    private const val DARK = 128

    /**
     * Luminance at or below this is the interior of an inverted balloon.
     * Deliberately below [DARK]: downscaling blurs screentone toward
     * mid-grey, and the band between the two thresholds keeps that tone as
     * lettering only, never an interior the inverted flood could spread
     * through.
     */
    private const val INVERTED_INTERIOR = 110

    /** Fractions of the analysed page a balloon may occupy. */
    private const val MIN_AREA = 0.0012f
    private const val MAX_AREA = 0.30f

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

    /** Share of the box that must be lettering for a light blob to be a balloon. */
    private const val MIN_INK = 0.02f
    private const val MAX_INK = 0.55f

    /**
     * Radius (work pixels) the ink is thickened by when hunting burst
     * balloons. Their border is a ring of radiating ticks, and the flood
     * leaks out through the gaps between them; thickening the ticks seals
     * gaps up to twice this radius, which covers the tick spacing shout
     * balloons are actually drawn with.
     */
    private const val BURST_SEAL_RADIUS = 2

    /**
     * Fill floor for the sealed pass. Sealing eats the component's rim and
     * fattens the lettering inside it, so a burst balloon's interior reads
     * thinner than an ordinary balloon's; the ordinary floor would reject it.
     */
    private const val BURST_MIN_FILL = 0.38f

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
    ): List<Balloon> {
        val scale = min(1f, WORK_DIM.toFloat() / max(bitmap.width, bitmap.height))
        val w = max(1, (bitmap.width * scale).toInt())
        val h = max(1, (bitmap.height * scale).toInt())
        if (w < 16 || h < 16) return emptyList()

        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()

        val light = BooleanArray(w * h)
        val dark = BooleanArray(w * h)
        val darkInterior = BooleanArray(w * h)
        val tinted = BooleanArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            light[i] = lum >= LIGHT
            dark[i] = lum < DARK
            darkInterior[i] = lum <= INVERTED_INTERIOR
            tinted[i] = lum >= TINTED_INTERIOR
        }

        val found = sweep(
            light, dark, barrier = null, w, h, scale,
            bitmap, ignoreTopPx, ignoreBottomPx, exclusions, MIN_FILL, inverted = false,
        )

        // Second pass for burst balloons — the shouted lines whose border is
        // a ring of radiating ticks rather than a drawn curve. The gaps
        // between ticks let the flood leak into the page, so the balloon is
        // never enclosed and pass one cannot see it: exactly the balloons
        // that then went untranslated or, worse, floated to wherever a model
        // guessed. Thickened ink seals the gaps and they fall out as
        // ordinary components.
        val sealed = dilate(dark, w, h, BURST_SEAL_RADIUS)
        val burst = sweep(
            light, dark, barrier = sealed, w, h, scale,
            bitmap, ignoreTopPx, ignoreBottomPx, exclusions, BURST_MIN_FILL, inverted = false,
        )
        val out = ArrayList(found)
        for (b in burst) {
            if (out.none { sameBalloon(it.box, b.box) }) out.add(b)
        }

        // Pastel balloons: the interior threshold relaxes to catch pink and
        // blue fills, every other gate stays, and anything the white pass
        // already found dedupes away.
        val pastel = sweep(
            tinted, dark, barrier = null, w, h, scale,
            bitmap, ignoreTopPx, ignoreBottomPx, exclusions, MIN_FILL, inverted = false,
        )
        for (b in pastel) {
            if (out.none { sameBalloon(it.box, b.box) }) out.add(b)
        }

        // Third pass, polarity flipped: the black narration and flashback
        // boxes whose interior is exactly what the light model calls ink, so
        // the first two passes cannot see them at all — those boxes simply
        // went untranslated on every flashback page.
        val negative = sweep(
            darkInterior, light, barrier = null, w, h, scale,
            bitmap, ignoreTopPx, ignoreBottomPx, exclusions, MIN_FILL, inverted = true,
        )
        for (b in negative) {
            if (out.none { sameBalloon(it.box, b.box) }) out.add(b)
        }
        return dropContainers(out)
    }

    /**
     * One connected-component sweep. [interior] is flooded, [barrier]
     * additionally blocks the flood, and [lettering] is the opposite polarity
     * that must appear inside the box in believable quantity.
     */
    private fun sweep(
        interior: BooleanArray,
        lettering: BooleanArray,
        barrier: BooleanArray?,
        w: Int,
        h: Int,
        scale: Float,
        bitmap: Bitmap,
        ignoreTopPx: Int,
        ignoreBottomPx: Int,
        exclusions: List<Rect>,
        minFill: Float,
        inverted: Boolean,
    ): List<Balloon> {
        val open = if (barrier == null) interior else BooleanArray(w * h) { interior[it] && !barrier[it] }
        val out = ArrayList<Balloon>()
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val cells = IntArray(w * h)
        val minArea = (MIN_AREA * w * h).toInt().coerceAtLeast(24)
        val maxArea = (MAX_AREA * w * h).toInt()

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
            var touchesEdge = false

            while (top > 0) {
                val idx = stack[--top]
                val x = idx % w
                val y = idx / w
                cells[count++] = idx
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touchesEdge = true

                if (x > 0) push(stack, top, idx - 1, open, seen)?.let { top = it }
                if (x < w - 1) push(stack, top, idx + 1, open, seen)?.let { top = it }
                if (y > 0) push(stack, top, idx - w, open, seen)?.let { top = it }
                if (y < h - 1) push(stack, top, idx + w, open, seen)?.let { top = it }
            }

            // The page background — either polarity — is huge and runs to the edge.
            if (touchesEdge || count < minArea || count > maxArea) continue

            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            if (boxW < 10 || boxH < 10) continue
            val fill = count.toFloat() / (boxW * boxH)
            if (fill < minFill || fill > MAX_FILL) continue
            // Extreme slivers are panel highlights, not balloons.
            val ratio = boxW.toFloat() / boxH
            if (ratio > 12f || ratio < 1f / 12f) continue

            // A balloon holds lettering of the opposite polarity. A blank
            // highlight — or a featureless patch of night sky — does not.
            var ink = 0
            for (y in minY..maxY) {
                var i = y * w + minX
                for (x in minX..maxX) {
                    if (lettering[i]) ink++
                    i++
                }
            }
            val inkFraction = ink.toFloat() / (boxW * boxH)
            if (inkFraction < MIN_INK || inkFraction > MAX_INK) continue

            val full = Rect(
                (minX / scale).toInt(),
                (minY / scale).toInt(),
                ((maxX + 1) / scale).toInt(),
                ((maxY + 1) / scale).toInt(),
            )
            if (full.bottom <= ignoreTopPx || full.top >= bitmap.height - ignoreBottomPx) continue
            if (exclusions.any { Rect.intersects(it, full) }) continue

            val mask = BooleanArray(boxW * boxH)
            for (n in 0 until count) {
                val cell = cells[n]
                mask[(cell / w - minY) * boxW + (cell % w - minX)] = true
            }
            // The flood only ever enters interior-colored cells, so the mask
            // arrives with letter-shaped holes exactly where the lettering
            // is — and a cleaning fill painted through it would spare the
            // very text it exists to erase. Anything enclosed by interior is
            // interior: flood the complement from the box border and claim
            // whatever it cannot reach.
            fillHoles(mask, boxW, boxH)
            out.add(Balloon(full, boxW, boxH, mask, inverted))
        }
        return out
    }

    /** True when the two detections describe one balloon (sealing shrinks rims). */
    private fun sameBalloon(a: Rect, b: Rect): Boolean {
        if (a.contains(b.centerX(), b.centerY()) || b.contains(a.centerX(), a.centerY())) return true
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
