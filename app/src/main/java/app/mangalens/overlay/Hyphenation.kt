package app.mangalens.overlay

/**
 * Where an English word may take a hyphen: Liang's algorithm over the TeX
 * patterns for American English (hyph-en-us.tex, bundled unmodified), the
 * data Android's own text layout and LibreOffice hyphenate with.
 * "des-truc-tive", "whis-per-ing", "con-grat-u-la-tions", where a rule of
 * thumb reads "dest-ructive" or "whi-spering".
 */
internal object Hyphenation {

    /** Each pattern's letters ("ach" for ".ach4") to its digits, one per gap. */
    private val patterns = HashMap<String, ByteArray>()

    /** Words the patterns get wrong, with their gaps spelled out. */
    private val exceptions = HashMap<String, BooleanArray>()

    private var longest = 0

    init {
        load()
    }

    /** Loads the patterns, once; ahead of the first word that needs them. */
    fun warm() = Unit

    /**
     * For each gap in [word], the one before letter i, whether a hyphen may
     * go there. A word that is not all plain letters a-z comes back with
     * none: the patterns know nothing of it.
     */
    fun points(word: String): BooleanArray {
        val w = word.lowercase()
        val out = BooleanArray(w.length + 1)
        if (w.isEmpty() || w.any { it !in 'a'..'z' }) return out
        exceptions[w]?.let { return it.copyOf() }
        // Every pattern found in ".word." votes on the gaps it spans; the
        // highest vote wins, and an odd one allows a hyphen.
        val text = ".$w."
        val votes = ByteArray(text.length + 1)
        for (start in text.indices) {
            for (end in start + 1..minOf(text.length, start + longest)) {
                val digits = patterns[text.substring(start, end)] ?: continue
                for (k in digits.indices) if (digits[k] > votes[start + k]) votes[start + k] = digits[k]
            }
        }
        for (i in 1 until w.length) out[i] = votes[i + 1] % 2 == 1
        return out
    }

    private fun load() {
        val stream = Hyphenation::class.java.getResourceAsStream(PATTERNS) ?: return
        var section = ""
        stream.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.substringBefore('%').trim()
                when {
                    line.startsWith("\\patterns{") -> section = "patterns"
                    line.startsWith("\\hyphenation{") -> section = "hyphenation"
                    line == "}" -> section = ""
                    line.isEmpty() || section.isEmpty() -> Unit
                    else -> for (token in line.split(' ', '\t')) {
                        if (token.isEmpty()) continue
                        if (section == "patterns") addPattern(token) else addException(token)
                    }
                }
            }
        }
    }

    /** "a1b2c": letters "abc", and 0, 1, 2, 0 in the gaps before, between and after them. */
    private fun addPattern(token: String) {
        val letters = StringBuilder()
        val digits = ByteArray(token.length + 1)
        for (c in token) {
            if (c.isDigit()) {
                digits[letters.length] = (c - '0').toByte()
            } else {
                letters.append(c)
            }
        }
        patterns[letters.toString()] = digits.copyOf(letters.length + 1)
        longest = maxOf(longest, letters.length)
    }

    /** "as-so-ciate": hyphens after "as" and "asso", and nowhere else. */
    private fun addException(token: String) {
        val word = token.replace("-", "")
        val gaps = BooleanArray(word.length + 1)
        var letters = 0
        for (c in token) if (c == '-') gaps[letters] = true else letters++
        exceptions[word] = gaps
    }

    private const val PATTERNS = "hyph-en-us.tex"
}
