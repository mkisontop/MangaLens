package app.mangalens.translate

import app.mangalens.ocr.Script

/**
 * Rejects translations that would read as garbage on the page: empty results
 * and source echoed back untranslated. Better an untouched bubble than junk.
 *
 * Romanized kana is not gated. The model is told to skip what it cannot
 * read rather than romanize it, and a shouted name (カナタ! -> "Kanata!") is
 * legitimate romaji that a romanization check would throw away.
 */
object JunkFilter {

    /** Returns null when the translation should not be rendered at all. */
    fun accept(source: String, translated: String): String? {
        val out = translated.trim()
        if (out.isEmpty()) return null
        // Untranslated echo: painting the original text over itself helps nobody.
        if (Script.cjkCount(out) > out.length * 0.4f) return null
        // The Latin flavour of the same failure — a Spanish source coming
        // back as the same Spanish. Short identical answers are left alone:
        // "No!" translates to "No!" legitimately.
        if (Script.cjkCount(source) == 0) {
            val a = source.lowercase().filter { it.isLetterOrDigit() }
            if (a.length >= 12 && a == out.lowercase().filter { it.isLetterOrDigit() }) return null
        }
        val letters = out.count { it.isLetter() }
        if (letters < 2) return null
        return out
    }
}
