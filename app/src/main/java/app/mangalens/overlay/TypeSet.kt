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
