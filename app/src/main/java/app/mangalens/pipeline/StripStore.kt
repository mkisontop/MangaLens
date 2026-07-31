package app.mangalens.pipeline

import android.graphics.Rect
import app.mangalens.overlay.RenderBubble
import kotlin.math.abs

/**
 * Translations addressed by where they live on the strip, not on the screen.
 *
 * A manhwa chapter is one tall image and the screen a sliding window over it,
 * so a card keyed to screen coordinates dies on the first scroll and knows
 * nothing when its balloon returns. Entries are stored in strip space instead
 * — stripY = screenY + the scroll offset the frame was captured at — which
 * stays true for as long as the chapter does: [visible] re-derives the screen
 * layout for any offset, cards ride the content instead of clearing, and a
 * balloon scrolled back into view still wears the line it was given the first
 * time. The chapter reads as if it had been translated from the start.
 *
 * [claimedRects] closes the loop with the pipeline: balloons that already own
 * a card are handed back as exclusions, so OCR and detection spend nothing on
 * them and can never re-translate one a little differently. A re-detection
 * that slips through anyway — an exclusion missed by a few pixels — is
 * absorbed by [add]'s duplicate rule rather than stacking a second card on
 * the same balloon.
 *
 * The offsets the service believes come from scroll tracking, and tracking
 * error accumulates. [reground] treats stored entries as landmarks: fresh
 * detections whose content hashes match stored entries each vote for where
 * the strip really is, and the median vote corrects the offset without
 * letting any single false match steer it.
 *
 * The capture thread writes while the overlay asks from wherever it pleases,
 * so the methods are synchronized — and everything handed out is a copy,
 * because overlay code mutates Rects freely and stored strip geometry must
 * outlive any one frame.
 */
class StripStore(private val maxEntries: Int = 400) {

    private companion object {
        /** How far apart two alignment proposals may sit and still agree. */
        const val LAYOUT_TOLERANCE_PX = 14
    }

    private class Entry(val bubble: RenderBubble, val hash: Long) {
        val top get() = bubble.box.top
        val bottom get() = bubble.box.bottom
    }

    private val entries = ArrayList<Entry>()

    /**
     * Remembers [bubbles] captured while the strip was scrolled by [offset]
     * px, with [hashes] aligned by index as each balloon's content
     * fingerprint. An incoming bubble whose strip span overlaps an existing
     * entry of the same hash by more than half the incoming height is that
     * balloon seen again, and the existing entry — already wearing its line —
     * wins: replacing it would re-typeset a card the reader may be halfway
     * through. An equal hash at a genuinely different strip position is a new
     * balloon, because repeated lettering ("...", "!!") is how manhwa talk.
     *
     * Past [maxEntries] the entries whose stripTop lies farthest from the
     * current [offset] are dropped first — chapters are read roughly forward,
     * and the cards worth keeping are the ones a scroll could plausibly
     * revisit.
     */
    @Synchronized
    fun add(bubbles: List<RenderBubble>, hashes: List<Long>, offset: Int) {
        val n = minOf(bubbles.size, hashes.size)
        for (i in 0 until n) {
            val b = bubbles[i]
            val top = b.box.top + offset
            val bottom = b.box.bottom + offset
            val hash = hashes[i]
            val seen = entries.any { e ->
                e.hash == hash && (minOf(e.bottom, bottom) - maxOf(e.top, top)) * 2 > bottom - top
            }
            if (!seen) entries.add(Entry(shifted(b, offset), hash))
        }
        if (entries.size > maxEntries) {
            val keep = entries.sortedBy { abs(it.top - offset) }.take(maxEntries)
            entries.clear()
            entries.addAll(keep)
        }
    }

    /**
     * The cards a screen of [screenH] px shows at [offset], repositioned into
     * screen space, top first. Boxes — and balloon boxes, where a balloon was
     * detected — are fresh copies on every call.
     */
    @Synchronized
    fun visible(offset: Int, screenH: Int): List<RenderBubble> =
        entries.filter { it.top < offset + screenH && it.bottom > offset }
            .sortedBy { it.top }
            .map { shifted(it.bubble, -offset) }

    /**
     * Screen-space rects already spoken for by a card at [offset], for the
     * pipeline's OCR and detection exclusions. The claim is the detected
     * balloon where one is known — the card wipes the whole balloon, so the
     * whole balloon is claimed — and the card's own box otherwise.
     */
    @Synchronized
    fun claimedRects(offset: Int, screenH: Int): List<Rect> =
        entries.filter { it.top < offset + screenH && it.bottom > offset }
            .sortedBy { it.top }
            .map { e ->
                val r = Rect(e.bubble.balloon?.box ?: e.bubble.box)
                r.offset(0, -offset)
                r
            }

    /**
     * The offset the strip is actually at, judged from freshly [detected]
     * (screen rect, content hash) pairs matched against stored entries — or
     * null when fewer than two detections match, because a single match is
     * exactly what a repeated balloon or a hash collision produces. A hash
     * stored more than once votes with the entry implying the correction
     * closest to the believed [offset]; the answer is the median of the
     * votes, so one wrong match cannot move it.
     */
    @Synchronized
    fun reground(detected: List<Pair<Rect, Long>>, offset: Int): Int? {
        val votes = ArrayList<Int>()
        for ((box, hash) in detected) {
            var vote: Int? = null
            for (e in entries) {
                if (e.hash != hash) continue
                val candidate = e.top - box.top
                if (vote == null || abs(candidate - offset) < abs(vote - offset)) vote = candidate
            }
            if (vote != null) votes.add(vote)
        }
        if (votes.size >= 2) {
            votes.sort()
            return votes[votes.size / 2]
        }
        return regroundByLayout(detected, offset)
    }

    /**
     * Content hashes are exact-match, and the same balloon re-detected on a
     * later pass is cropped a little differently — enough jitter that a
     * revisit can produce no hash votes at all, and treating that as "wrong
     * strip" wipes a chapter of translations over a fingerprint quirk. The
     * balloons' vertical layout is a landmark the hashes cannot lose: the
     * spacing pattern of balloon tops is as good as a barcode on a real
     * page. Every (detected, stored) pairing proposes the offset that would
     * align it; the proposal that at least two distinct detections agree on
     * (within [LAYOUT_TOLERANCE_PX]) is where the strip actually is. A
     * swapped page's balloons land at unrelated spacings and never gather
     * two agreeing proposals.
     */
    private fun regroundByLayout(detected: List<Pair<Rect, Long>>, offset: Int): Int? {
        if (detected.size < 2 || entries.size < 2) return null
        val proposals = ArrayList<Int>()
        for ((box, _) in detected) {
            for (e in entries) {
                proposals.add(e.top - box.top)
            }
        }
        var best: MutableList<Int>? = null
        for (p in proposals) {
            val support = ArrayList<Int>()
            for ((box, _) in detected) {
                var closest: Int? = null
                for (e in entries) {
                    val c = e.top - box.top
                    if (abs(c - p) <= LAYOUT_TOLERANCE_PX &&
                        (closest == null || abs(c - p) < abs(closest - p))
                    ) {
                        closest = c
                    }
                }
                closest?.let { support.add(it) }
            }
            if (support.size >= 2 && (best == null || support.size > best.size ||
                    (support.size == best.size && abs(p - offset) < abs(best[best.size / 2] - offset)))
            ) {
                support.sort()
                best = support
            }
        }
        return best?.let { it[it.size / 2] }
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size

    /** A copy of [b] moved [dy] px down the page, its balloon moved with it. */
    private fun shifted(b: RenderBubble, dy: Int): RenderBubble = b.copy(
        box = Rect(b.box).apply { offset(0, dy) },
        balloon = b.balloon?.let { it.copy(box = Rect(it.box).apply { offset(0, dy) }) },
    )
}
