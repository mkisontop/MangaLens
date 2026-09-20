package app.mangalens.ocr

import app.mangalens.settings.SourceLang

/**
 * Stitches one utterance back together across the bubbles it was split into.
 *
 * A single line of comic dialogue is routinely broken over two or three
 * balloons for pacing ("あいつが……" / "……来たのか"). Japanese and Korean drop
 * both the subject and, mid-sentence, the verb, so a tail bubble read on its
 * own is not merely missing flavour — it is genuinely ambiguous, and a
 * translator handed it alone will invent a subject. Tense, politeness and
 * whether the clause is even affirmative can all live in the bubble that
 * follows.
 *
 * Bubbles are tagged rather than concatenated: each keeps its own box so the
 * overlay still lands on the right balloon, while the translator is told which
 * bubbles form one sentence and in what order, and renders English that flows
 * across them.
 *
 * The detector is deliberately conservative. Wrongly welding two characters'
 * lines together is a worse failure than translating a tail bubble alone, so a
 * run needs a grammatical dangling cue *and* physical proximity.
 */
object Utterance {

    /** A run this long is almost certainly a detection error, not a sentence. */
    private const val MAX_RUN = 4

    /**
     * Ends a sentence outright. A bubble ending in one of these is complete and
     * never opens a run. Note that "……" is deliberately absent: a trailing-off
     * mark invites continuation rather than closing the line, and is the single
     * most common way a split sentence signals its own first half.
     */
    private val TERMINATORS = setOf(
        '。', '．', '！', '？', '!', '?', '.', '」', '』', '”', '"', ')', '）',
    )

    /**
     * Japanese clause connectives and case particles that cannot legally end a
     * sentence — seeing one at a bubble's tail means the clause continues.
     *
     * Conspicuously absent: の, な and や. Each joins clauses in prose but is
     * overwhelmingly a sentence-final particle in comics dialogue — 「そうなの」
     * and 「そうだな」 are complete lines, and treating them as dangling welds
     * them onto whatever the next character says. A particle earns a place
     * here only if it cannot close a spoken line.
     */
    private val JA_CONNECTIVES = listOf(
        "けれど", "けれども", "のに", "ので", "から", "けど", "たら", "なら", "ながら",
        "ため", "って", "とか", "ても", "でも", "だが", "しかし",
        "が", "を", "に", "へ", "と", "は", "も", "で", "て", "し",
    )

    /**
     * Korean connective endings (-고, -서, -는데 …). A clause ending in one of
     * these is grammatically mid-sentence.
     *
     * Absent on purpose: 야 and 라. Each joins clauses in one reading (-아야
     * "must", -(이)라 "because it is") and closes a line in a far commoner
     * one — 「내 거야」 is "it's mine", 「가라」 is "go!" — so either welds a
     * reply onto the line before it.
     */
    private val KO_CONNECTIVES = listOf(
        "는데", "은데", "ㄴ데", "지만", "니까", "아서", "어서", "면서", "거나", "든지",
        "라서", "이라", "하고", "고", "서", "며", "면",
        // Topic and object particles: a line ending in one has its predicate
        // in the next balloon, as with は and を.
        "은", "는", "을", "를",
    )

    /**
     * Korean quotative endings that close a spoken line even though they end
     * in the connective 고: 「알았다고」 is "I said I get it", not the front
     * half of anything.
     */
    private val KO_FINAL_QUOTATIVES = listOf("다고", "라고", "냐고", "자고", "다구", "라구")

    /**
     * Chinese conjunctions and tails that run on. 的 and 了 are absent for
     * the same reason の and な are absent from the Japanese table: both
     * join clauses in prose and both end spoken lines constantly — 「我知道了」
     * and 「這是我的」 are complete — and balloons in Chinese editions
     * routinely carry no final punctuation to say so.
     */
    private val ZH_CONNECTIVES = listOf(
        "但是", "可是", "因为", "因為", "所以", "而且", "然后", "然後", "虽然", "雖然", "如果",
        "和", "跟", "而", "就", "还", "還", "又",
    )

    /**
     * Two characters or fewer of reply — うん, ええ, はい, いや, 嗯, 응, 네,
     * え？ — after a line that merely ends in a bare particle. A sentence
     * split across balloons does not end in an interjection; two speakers
     * trading a line and a grunt do, and that is the weld that invents a
     * sentence. A first half that trails off (…、) still takes any tail.
     */
    private const val MAX_REPLY_CJK = 2

    /**
     * A short Japanese line ending in て/で is a request — 「待って」「やめて」
     * 「見て」「急いで」 — not a clause chained into the next balloon.
     * 「それを聞いて」 is long enough to be the chain.
     */
    private const val MAX_REQUEST_CJK = 4

    /**
     * Assigns run ids to [bubbles], which must already be in reading order.
     * Returns a copy with [Bubble.runId] and [Bubble.runPart] populated;
     * standalone bubbles keep `runId = -1`.
     */
    fun link(bubbles: List<Bubble>, lang: SourceLang): List<Bubble> {
        if (bubbles.size < 2) return bubbles
        val runIds = IntArray(bubbles.size) { -1 }
        val parts = IntArray(bubbles.size)
        var nextRun = 0

        var i = 0
        while (i < bubbles.size - 1) {
            if (!opensRun(bubbles[i], lang)) {
                i++
                continue
            }
            // Walk forward while each bubble keeps dangling into the next.
            var end = i
            while (end < bubbles.size - 1 &&
                end - i + 1 < MAX_RUN &&
                continues(bubbles[end], bubbles[end + 1], lang)
            ) {
                end++
            }
            if (end > i) {
                val id = nextRun++
                for (k in i..end) {
                    runIds[k] = id
                    parts[k] = k - i
                }
                i = end + 1
            } else {
                i++
            }
        }

        return bubbles.mapIndexed { idx, b ->
            if (runIds[idx] < 0) b else b.copy(runId = runIds[idx], runPart = parts[idx])
        }
    }

    /** A bubble can start a run only if it is dialogue that does not conclude. */
    private fun opensRun(b: Bubble, lang: SourceLang): Boolean =
        b.kind == BubbleKind.DIALOGUE && dangles(b.text, lang)

    /**
     * True when [a] grammatically runs into [b] and the two are close enough on
     * the page to plausibly be the same speaker's line.
     */
    private fun continues(a: Bubble, b: Bubble, lang: SourceLang): Boolean {
        if (a.kind != BubbleKind.DIALOGUE || b.kind != BubbleKind.DIALOGUE) return false
        if (!dangles(a.text, lang)) return false
        // A tail that opens with a quote or a capital-style opener is a new line.
        if (b.text.firstOrNull() in setOf('「', '『', '(', '（')) return false
        // A bare particle followed by a grunt is an exchange, not a sentence.
        if (!trailsOff(a.text) && Script.cjkCount(b.text) <= MAX_REPLY_CJK) return false
        return near(a, b)
    }

    /** True when the line ends in punctuation that explicitly invites more: …、, */
    private fun trailsOff(raw: String): Boolean {
        val last = raw.trim().trimEnd('　', ' ').lastOrNull() ?: return false
        return last == '…' || last == '‥' || last == '、' || last == ',' || last == '，'
    }

    /**
     * Proximity gate. Bubbles in one utterance sit in the same panel, so the
     * gap between them is small relative to the bubbles themselves. Measured
     * against the larger bubble so a tiny tail balloon still links to the big
     * one that opened the sentence.
     */
    private fun near(a: Bubble, b: Bubble): Boolean {
        val reach = maxOf(
            maxOf(a.box.width(), a.box.height()),
            maxOf(b.box.width(), b.box.height()),
        ).coerceAtLeast(24) * 1.6f

        val dx = when {
            b.box.left > a.box.right -> (b.box.left - a.box.right).toFloat()
            a.box.left > b.box.right -> (a.box.left - b.box.right).toFloat()
            else -> 0f
        }
        val dy = when {
            b.box.top > a.box.bottom -> (b.box.top - a.box.bottom).toFloat()
            a.box.top > b.box.bottom -> (a.box.top - b.box.bottom).toFloat()
            else -> 0f
        }
        return dx <= reach && dy <= reach
    }

    /** True when the text ends mid-clause and needs the next bubble to resolve. */
    fun dangles(raw: String, lang: SourceLang): Boolean {
        val t = raw.trim().trimEnd('　', ' ')
        if (t.isEmpty()) return false
        // Too short to judge: a lone interjection is complete as it stands.
        if (Script.cjkCount(t) < 2) return false

        val last = t.last()
        // Trailing off ("……") is the classic split-bubble opener, and unlike a
        // full stop it invites continuation rather than closing the line.
        if (last == '…' || last == '‥' || last == '、' || last == ',' || last == '，') return true
        if (last in TERMINATORS) return false
        // A cut-off glottal stop or a stretched vowel is mid-breath.
        if (last == 'っ' || last == 'ッ' || last == 'ー' || last == '~' || last == '～') return true

        val cjk = Script.cjkCount(t)

        val script = when (lang) {
            SourceLang.KO -> SourceLang.KO
            SourceLang.ZH -> SourceLang.ZH
            SourceLang.JA -> SourceLang.JA
            // In AUTO the script itself picks the table.
            SourceLang.AUTO -> when {
                Script.hangulCount(t) > 0 -> SourceLang.KO
                Script.kanaCount(t) > 0 -> SourceLang.JA
                else -> SourceLang.ZH
            }
        }
        return when (script) {
            SourceLang.KO -> {
                if (KO_FINAL_QUOTATIVES.any { t.endsWith(it) }) false
                else KO_CONNECTIVES.any { t.endsWith(it) }
            }
            SourceLang.JA -> {
                if ((last == 'て' || last == 'で') && cjk <= MAX_REQUEST_CJK) false
                else JA_CONNECTIVES.any { t.endsWith(it) }
            }
            else -> ZH_CONNECTIVES.any { t.endsWith(it) }
        }
    }
}
