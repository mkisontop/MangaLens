package app.mangalens.translate

import app.mangalens.ocr.Script
import kotlin.math.roundToInt

/**
 * How much English a balloon has room for.
 *
 * The artist sized the balloon for the source text, and English needs more
 * characters to say the same thing: roughly two and a half to three and a
 * half per CJK glyph, depending on the language. Letters are narrower than
 * glyphs, so about twice the count fits in the same space at the same
 * size, and beyond that the type must shrink. A translator working for a
 * letterer writes to the balloon — tightens, cuts filler, picks the short
 * word — and that is what makes a page read as typeset rather than
 * overlaid. The budget here is generous: it lets the type stay at eighty
 * percent or more of its natural size, and it is a target for the model,
 * never a place to cut a sentence.
 */
object FitBudget {

    /** English characters per source glyph the balloon comfortably takes. */
    private const val PER_KANA_KANJI = 3.0f
    private const val PER_HANGUL = 3.4f
    private const val PER_HANZI = 3.4f
    private const val PER_LATIN = 1.15f

    /** Room every balloon has regardless of length: punctuation, a short word. */
    private const val SLACK = 6

    /** Fewest source glyphs a hint is given for; shorter lines are interjections. */
    private const val MIN_CJK = 3
    private const val MIN_LATIN = 8

    /** The character budget for [source], or null when the line is too short to need one. */
    fun chars(source: String): Int? {
        val hangul = Script.hangulCount(source)
        val kana = Script.kanaCount(source)
        val han = Script.hanCount(source)
        val cjk = hangul + kana + han
        if (cjk >= MIN_CJK) {
            val per = when {
                hangul * 2 >= cjk -> PER_HANGUL
                kana > 0 -> PER_KANA_KANJI
                else -> PER_HANZI
            }
            return (cjk * per).roundToInt() + SLACK
        }
        val letters = source.count { it.isLetter() }
        if (cjk == 0 && letters >= MIN_LATIN) {
            return (source.trim().length * PER_LATIN).roundToInt() + SLACK
        }
        return null
    }
}
