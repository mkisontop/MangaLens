package app.mangalens.overlay

import kotlin.math.sqrt

/**
 * Line breaking for balloon typesetting, kept pure: geometry comes in through
 * a measure lambda, so the shaping is testable without a device and usable
 * with any paint.
 *
 * Greedy filling is what reads as machine output — every line runs to the
 * margin and the last line keeps the crumbs ("I never thought it would /
 * end like / this"). A letterer shapes the block to the balloon: shorter
 * first and last lines, widest in the middle, because the balloon is round
 * and the text block should be too. The break is chosen in two steps. Greedy
 * fixes the line count, which is minimal — under a hard width cap no
 * strategy fits the words into fewer lines. Then dynamic programming picks,
 * among all breaks with that many lines, the one whose line widths sit
 * closest to an elliptical width profile. Exceeding the cap is forbidden
 * outright rather than penalized — except for a single word wider than the
 * cap, which no break can fix and which every candidate therefore pays for
 * equally; the caller resolves those by shrinking the type.
 */
object TypeSet {

    private val WS = Regex("\\s+")

    /**
     * Beyond this the width table's quadratic measure calls stop being free.
     * Text that long is a caption wall, not balloon dialogue, and nobody
     * hand-shapes those either: it falls back to the greedy break.
     */
    private const val DP_WORD_LIMIT = 48

    private const val INFEASIBLE = Float.MAX_VALUE / 4f

    /**
     * Large against any sum of taper misses, small against [INFEASIBLE]:
     * an unavoidable overrun must never look worse than no answer, and never
     * better than any break that avoids it.
     */
    private const val OVERFLOW_PENALTY = 1_000_000f

    /**
     * Breaks [text] into lines no wider than [maxWidth] under [measure],
     * never splitting a word. Deterministic: equal inputs give equal breaks.
     * The only lines that may exceed [maxWidth] are single words that alone
     * exceed it.
     */
    fun breakLines(text: String, measure: (String) -> Float, maxWidth: Float): List<String> {
        val words = text.trim().split(WS).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        if (words.size == 1) return words

        val greedy = greedyBreak(words, measure, maxWidth)
        val k = greedy.size
        if (k <= 1 || words.size > DP_WORD_LIMIT) return greedy

        val m = words.size
        // Lines are measured as the joined string, not as summed word
        // widths — kerning and space width belong to the paint, not to us.
        val width = Array(m) { FloatArray(m) }
        for (a in 0 until m) {
            val sb = StringBuilder(words[a])
            width[a][a] = measure(words[a])
            for (b in a + 1 until m) {
                sb.append(' ').append(words[b])
                width[a][b] = measure(sb.toString())
            }
        }

        // The chord of an ellipse sampled at each line's height: full width
        // in the middle rows, tapering toward the first and last.
        val target = FloatArray(k) { i ->
            val t = (2f * i - (k - 1)) / (k + 1)
            maxWidth * sqrt(1f - t * t)
        }

        // best[j][i]: cheapest way to set the first i words as j lines.
        val best = Array(k + 1) { FloatArray(m + 1) { INFEASIBLE } }
        val cut = Array(k + 1) { IntArray(m + 1) }
        best[0][0] = 0f
        for (j in 1..k) {
            for (i in j..m - (k - j)) {
                for (a in j - 1 until i) {
                    val before = best[j - 1][a]
                    if (before >= INFEASIBLE) continue
                    val w = width[a][i - 1]
                    if (w > maxWidth && i - 1 > a) continue
                    val miss = (w - target[j - 1]) / maxWidth
                    var cost = before + miss * miss
                    if (w > maxWidth) cost += OVERFLOW_PENALTY
                    if (cost < best[j][i]) {
                        best[j][i] = cost
                        cut[j][i] = a
                    }
                }
            }
        }
        // The greedy break is itself a valid assignment, so this cannot
        // trigger; kept so a future cost change degrades to greedy instead
        // of to an empty balloon.
        if (best[k][m] >= INFEASIBLE) return greedy

        val lines = MutableList(k) { "" }
        var end = m
        for (j in k downTo 1) {
            val start = cut[j][end]
            lines[j - 1] = words.subList(start, end).joinToString(" ")
            end = start
        }
        return lines
    }

    /**
     * [text] with every word wider than [maxWidth] cut into pieces that fit,
     * each piece but the last ending in a hyphen, and the pieces separated
     * as words so any breaker can set them on lines of their own.
     *
     * A letterer hyphenates a word that will not fit a balloon rather than
     * let it run over the outline; shrinking the whole block to a size where
     * one compound fits makes every other line unreadable. Words that fit
     * are left alone, so a caller can apply this blindly once shrinking has
     * run out. A word's own hyphens are the preferred cut points; otherwise
     * the word is cut into the fewest balanced pieces. Balanced pieces of the
     * fewest count each take about half the width or more, so two of them do
     * not fit one line together — a breaker cannot rejoin them into
     * "Donau- dampf" mid-line. A word no cut can bring under [maxWidth] comes
     * back as close as its cuts allow; the caller shrinks the type for it.
     *
     * Short words and names are set whole ([MIN_HYPHENATED], [isName])
     * unless [eager]: for a balloon so narrow that even the smallest type
     * cannot hold them, where a hyphen is better than a word over the
     * outline.
     */
    fun hyphenate(text: String, measure: (String) -> Float, maxWidth: Float, eager: Boolean = false): String {
        val words = text.trim().split(WS).filter { it.isNotEmpty() }
        if (words.none { measure(it) > maxWidth }) return text
        val shortest = if (eager) MIN_HYPHENATED_EAGER else MIN_HYPHENATED
        val out = StringBuilder()
        for ((i, word) in words.withIndex()) {
            if (out.isNotEmpty()) out.append(' ')
            if (measure(word) <= maxWidth || (!eager && isName(word, words.getOrNull(i - 1)))) {
                out.append(word)
            } else {
                splitWord(word, measure, maxWidth, shortest).joinTo(out, " ")
            }
        }
        return out.toString()
    }

    /**
     * A name, capitalised mid-sentence, is set whole: "Tortil- lano" reads
     * as a typo, where the type a size smaller does not. One too long for
     * any balloon is cut like any other word.
     */
    private fun isName(word: String, previous: String?): Boolean {
        val first = word.firstOrNull { it.isLetter() } ?: return false
        if (!first.isUpperCase() || previous == null || previous.last() in SENTENCE_END) return false
        return word.count { it.isLetter() } <= LONGEST_WHOLE_NAME
    }

    private const val SENTENCE_END = ".!?…:\"—"

    /** Letters in the longest name that is always set whole. */
    private const val LONGEST_WHOLE_NAME = 12

    /** Fewest letters on either side of a hyphen: "W-" / "What" is a stammer, not a break. */
    private const val MIN_PIECE = 3

    /**
     * Shortest word that is ever cut. "what-ever" or "Under-stood" in a
     * narrow balloon reads worse than slightly smaller type; a compound
     * twice that long has no size at which it fits.
     */
    private const val MIN_HYPHENATED = 11

    /** The same, when nothing else fits the balloon at any size. */
    private const val MIN_HYPHENATED_EAGER = 9

    private fun splitWord(word: String, measure: (String) -> Float, maxWidth: Float, shortest: Int): List<String> {
        // Its own hyphens first: "self-" / "control" reads as written.
        val parts = word.split(HYPHEN_AFTER).filter { it.isNotEmpty() }
        if (parts.size > 1 && parts.all { p -> p.count { it.isLetter() } >= MIN_PIECE }) {
            val out = ArrayList<String>()
            var line = ""
            for (part in parts) {
                val grown = line + part
                if (line.isEmpty() || measure(grown) <= maxWidth) {
                    line = grown
                } else {
                    out.add(line)
                    line = part
                }
            }
            out.add(line)
            return out.flatMap { if (measure(it) > maxWidth) balancedPieces(it, measure, maxWidth, shortest) else listOf(it) }
        }
        return balancedPieces(word, measure, maxWidth, shortest)
    }

    /**
     * The fewest near-equal pieces of [word] that fit [maxWidth] once
     * hyphenated, cut only between two letters with [MIN_PIECE] letters
     * either side — never through "...?!" or a stammer — and only in words
     * of [shortest] letters or more. A word with no such cut is
     * returned whole; one that cannot be cut narrow enough comes back in
     * the fewest pieces its cuts allow, for the caller to shrink.
     */
    private fun balancedPieces(word: String, measure: (String) -> Float, maxWidth: Float, shortest: Int): List<String> {
        val letters = IntArray(word.length + 1)
        for (i in word.indices) letters[i + 1] = letters[i] + if (word[i].isLetter()) 1 else 0
        val total = letters[word.length]
        if (total < shortest) return listOf(word)
        // Letters run by run: a cut needs MIN_PIECE letters of its own run
        // on each side, so "S-Sorry" and "waiting...aah" keep their halves.
        val runStart = IntArray(word.length)
        val runEnd = IntArray(word.length)
        for (i in word.indices) {
            runStart[i] = if (i > 0 && word[i - 1].isLetter() && word[i].isLetter()) runStart[i - 1] else i
        }
        for (i in word.indices.reversed()) {
            runEnd[i] = if (i < word.length - 1 && word[i + 1].isLetter() && word[i].isLetter()) runEnd[i + 1] else i + 1
        }
        val cuts = (1 until word.length).filter { i ->
            word[i - 1].isLetter() && word[i].isLetter() &&
                i - runStart[i - 1] >= MIN_PIECE && runEnd[i] - i >= MIN_PIECE &&
                "${word[i - 1].lowercaseChar()}${word[i].lowercaseChar()}" !in DIGRAPHS
        }
        if (cuts.isEmpty()) return listOf(word)
        fun piecesAt(chosen: List<Int>): List<String> {
            val out = ArrayList<String>(chosen.size + 1)
            var start = 0
            for (c in chosen) {
                out.add(word.substring(start, c) + "-")
                start = c
            }
            out.add(word.substring(start))
            return out
        }
        var fewest: List<String>? = null
        val first = (measure(word) / maxWidth).toInt().coerceAtLeast(1) + 1
        for (n in first..cuts.size + 1) {
            // Each cut nearest its even share of the letters, and after the last.
            val chosen = ArrayList<Int>(n - 1)
            for (i in 1 until n) {
                val target = total * i / n
                val c = cuts.filter { chosen.isEmpty() || letters[it] - letters[chosen.last()] >= MIN_PIECE }
                    .minByOrNull { kotlin.math.abs(letters[it] - target) + if (between(word, it)) 0f else SYLLABLE_MISS }
                    ?: break
                chosen.add(c)
            }
            if (chosen.size != n - 1) break
            val pieces = piecesAt(chosen)
            if (fewest == null) fewest = pieces
            if (pieces.all { measure(it) <= maxWidth }) return pieces
        }
        return fewest ?: listOf(word)
    }

    private val HYPHEN_AFTER = Regex("(?<=-)")

    /** Letters a cut may drift from its even share to land between syllables. */
    private const val SYLLABLE_MISS = 1.5f

    private const val VOWELS = "aeiouyäöüàáâèéêëìíîïòóôùúûæøå"

    /** Letter pairs that spell one sound; a hyphen between them misleads the reader. */
    private val DIGRAPHS = setOf("ch", "sh", "th", "ph", "gh", "ck", "qu", "wh", "ng")

    /**
     * Whether a cut before [i] falls between two consonants — "schiff-fahrt",
     * "gesell-schaft", "ket-chup" — the one syllable boundary that needs no
     * dictionary, and the one readers stumble over least.
     */
    private fun between(word: String, i: Int): Boolean =
        word[i - 1].lowercaseChar() !in VOWELS && word[i].lowercaseChar() !in VOWELS

    /** A shaped break: the lines, and how far short of their caps they fell. */
    class Fit(val lines: List<String>, val cost: Float)

    /**
     * The words of one text under one paint, with every line width the
     * shape-fitting search could ask for measured once. [fit] is then pure
     * arithmetic, cheap enough to try dozens of line profiles per balloon.
     */
    class Shaper(text: String, measure: (String) -> Float) {
        val words: List<String> = text.trim().split(WS).filter { it.isNotEmpty() }

        /** width[a][b]: the joined width of words a..b, measured as one string. */
        private val width: Array<FloatArray>? = if (words.size in 1..DP_WORD_LIMIT) {
            val m = words.size
            Array(m) { a ->
                val row = FloatArray(m)
                val sb = StringBuilder(words[a])
                row[a] = measure(words[a])
                for (b in a + 1 until m) {
                    sb.append(' ').append(words[b])
                    row[b] = measure(sb.toString())
                }
                row
            }
        } else {
            null
        }

        /** False past [DP_WORD_LIMIT] words, where [fit] and [greedyLines] give no answer worth using. */
        val shapeable: Boolean get() = width != null

        /** The widest single word; no line can be narrower than this. */
        val widestWord: Float = width?.let { w -> w.indices.maxOf { w[it][it] } } ?: 0f

        /** How many lines a greedy break needs under [maxWidth] — the fewest possible. */
        fun greedyLines(maxWidth: Float): Int {
            val w = width ?: return words.size
            var lines = 1
            var start = 0
            for (i in 1 until words.size) {
                if (w[start][i] > maxWidth) {
                    lines++
                    start = i
                }
            }
            return lines
        }

        /**
         * Sets the words as exactly `caps.size` lines, line i no wider than
         * caps[i] and each as close to its cap as the words allow — the
         * caps being the balloon's own width at the rows each line will
         * occupy. Null when no such break exists, including when a single
         * word is wider than its cap; the caller answers that with a smaller
         * type or a different band of the balloon.
         */
        fun fit(caps: FloatArray): Fit? {
            val w = width ?: return null
            val m = words.size
            val k = caps.size
            if (k < 1 || k > m) return null
            val best = Array(k + 1) { FloatArray(m + 1) { INFEASIBLE } }
            val cut = Array(k + 1) { IntArray(m + 1) }
            best[0][0] = 0f
            for (j in 1..k) {
                val cap = caps[j - 1]
                if (cap <= 0f) return null
                for (i in j..m - (k - j)) {
                    for (a in j - 1 until i) {
                        val before = best[j - 1][a]
                        if (before >= INFEASIBLE) continue
                        val lw = w[a][i - 1]
                        if (lw > cap) continue
                        val slack = (cap - lw) / cap
                        val cost = before + slack * slack
                        if (cost < best[j][i]) {
                            best[j][i] = cost
                            cut[j][i] = a
                        }
                    }
                }
            }
            if (best[k][m] >= INFEASIBLE) return null
            val lines = MutableList(k) { "" }
            var end = m
            for (j in k downTo 1) {
                val start = cut[j][end]
                lines[j - 1] = words.subList(start, end).joinToString(" ")
                end = start
            }
            return Fit(lines, best[k][m])
        }
    }

    private fun greedyBreak(
        words: List<String>,
        measure: (String) -> Float,
        maxWidth: Float,
    ): List<String> {
        val lines = ArrayList<String>()
        var line = words[0]
        for (i in 1 until words.size) {
            val grown = line + " " + words[i]
            if (measure(grown) <= maxWidth) {
                line = grown
            } else {
                lines.add(line)
                line = words[i]
            }
        }
        lines.add(line)
        return lines
    }
}
