package app.mangalens.translate

import android.graphics.Rect

/**
 * What a piece of lettering is, as the model read it off the page.
 *
 * The kind decides how the English is set: speech and thought go into their
 * balloon, narration into its box, text drawn straight onto the art keeps
 * the art's own colours and outline, and a sound effect is lettered as one.
 */
enum class ItemKind {
    SPEECH,
    THOUGHT,
    NARRATION,
    SFX,

    /** Lettering drawn directly onto the art: side comments, signs, thoughts over a figure. */
    ART_TEXT;

    companion object {
        fun parse(s: String?): ItemKind = when (s?.trim()?.lowercase()) {
            "thought" -> THOUGHT
            "narration", "caption" -> NARRATION
            "sfx", "sound" -> SFX
            "art_text", "art", "sign", "side" -> ART_TEXT
            else -> SPEECH
        }
    }
}

/**
 * One piece of lettering found and translated by the AI page reader, in
 * page pixel coordinates.
 *
 * The model finds the text itself — [box] is its own box around the
 * lettering, not an on-device region it was handed — so a page can be sent
 * the moment the screen goes still, without waiting for OCR or balloon
 * detection. On-device analysis then decides where the English actually
 * goes: a detected balloon claims every item inside it, and anything else is
 * erased stroke by stroke and re-lettered in place.
 */
data class PageItem(
    /** Tight around the lettering itself, in page pixels. */
    val box: Rect,
    val kind: ItemKind,
    /** The original lettering as the model read it. */
    val src: String,
    val en: String,
    /** Speaker the line was attributed to; blank when unattributed or not dialogue. */
    val who: String = "",
    /** True when the source is set in vertical columns. */
    val vertical: Boolean = false,
    /** Fill colour of the original lettering (opaque ARGB), when the model reported one. */
    val textColor: Int? = null,
    /** Colour of the stroke around the original lettering, when it has one. */
    val outlineColor: Int? = null,
    /** Shouted: a jagged burst balloon, or oversized heavy lettering. */
    val loud: Boolean = false,
)
