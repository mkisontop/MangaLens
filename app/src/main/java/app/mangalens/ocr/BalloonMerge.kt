package app.mangalens.ocr

import android.graphics.Rect
import app.mangalens.settings.SourceLang

/**
 * Reconciles what OCR clustered with what the page actually shows.
 *
 * [BubbleGrouper] infers balloons from the spacing of the text it could read;
 * [BalloonFinder] sees the balloons themselves. Where the two disagree the
 * pixels win, because the disagreements are exactly the hard cases: a wide
 * balloon whose columns clustered apart, a furigana column driving a wedge
 * between two kanji columns, or a balloon whose vertical lettering OCR never
 * resolved at all.
 *
 * Fragments sharing a balloon are welded into one region carrying the whole
 * utterance, and a balloon OCR found nothing in still becomes a region so the
 * vision model gets asked about it. Text outside every balloon — sound effects
 * painted across the art, captions, narration boxes — passes through
 * untouched.
 */
object BalloonMerge {

    /** A fragment counts as inside a balloon when most of it lies within. */
    private const val MIN_CONTAINMENT = 0.6f

    /**
     * @param includeEmpty add regions for balloons holding no readable text.
     *   Only useful when something downstream can read the image itself; the
     *   machine engines have nothing to translate and would render blanks.
     */
    fun apply(
        bubbles: List<Bubble>,
        balloons: List<Rect>,
        lang: SourceLang,
        includeEmpty: Boolean,
    ): List<Bubble> {
        if (balloons.isEmpty()) return bubbles

        val claimed = BooleanArray(bubbles.size)
        val merged = ArrayList<Bubble>(bubbles.size)

        for (balloon in balloons) {
            val members = ArrayList<Bubble>()
            for ((i, b) in bubbles.withIndex()) {
                if (claimed[i]) continue
                if (containment(b.box, balloon) >= MIN_CONTAINMENT) {
                    claimed[i] = true
                    members.add(b)
                }
            }
            when {
                members.isEmpty() -> if (includeEmpty) {
                    // Nothing legible inside, but there is plainly a balloon
                    // here. Hand it over with no text rather than leaving a
                    // hole the translator is never told about.
                    merged.add(Bubble("", Rect(balloon), vertical = false))
                }
                members.size == 1 -> {
                    // Keep OCR's tighter box; one fragment means no dispute.
                    merged.add(members[0])
                }
                else -> merged.add(weld(members, balloon, lang))
            }
        }

        // Anything outside every balloon keeps its own geometry.
        for ((i, b) in bubbles.withIndex()) if (!claimed[i]) merged.add(b)
        return merged
    }

    /** Joins fragments into the single utterance the balloon holds. */
    private fun weld(members: List<Bubble>, balloon: Rect, lang: SourceLang): Bubble {
        val vertical = members.count { it.vertical } * 2 > members.size
        // Vertical text runs in columns read right to left; horizontal text
        // runs in lines read top to bottom.
        val ordered = if (vertical) {
            members.sortedByDescending { it.box.centerX() }
        } else {
            members.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        }
        // Latin words need the spaces CJK does without; KO keeps them too.
        val latin = members.sumOf { Script.cjkCount(it.text) } == 0
        val sep = if (lang == SourceLang.KO || latin) " " else ""
        val text = ordered.joinToString(sep) { it.text.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
        // A balloon holding dialogue is dialogue, even if an oversized glyph
        // inside it tripped the sound-effect heuristic.
        val kind = if (ordered.any { it.kind == BubbleKind.DIALOGUE }) {
            BubbleKind.DIALOGUE
        } else {
            BubbleKind.SFX
        }
        return Bubble(text, Rect(balloon), vertical, kind)
    }

    /** Fraction of [box] that lies inside [within]. */
    private fun containment(box: Rect, within: Rect): Float {
        val ix = minOf(box.right, within.right) - maxOf(box.left, within.left)
        val iy = minOf(box.bottom, within.bottom) - maxOf(box.top, within.top)
        if (ix <= 0 || iy <= 0) return 0f
        val area = box.width().toLong() * box.height()
        if (area <= 0L) return 0f
        return (ix.toLong() * iy).toFloat() / area
    }
}
