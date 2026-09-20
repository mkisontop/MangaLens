package app.mangalens.ocr

import android.graphics.Rect
import app.mangalens.settings.SourceLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scores split-sentence detection on a labelled corpus.
 *
 * The negatives carry the weight here. Linking is a judgement about grammar
 * made without a parser, and the expensive mistake is a false positive: two
 * separate lines welded into one sentence produce confident nonsense, whereas
 * a missed link merely leaves a tail clause translated on its own. The corpus
 * therefore includes the constructions most likely to fool a
 * suffix-matching rule — above all the particles that are connectives
 * mid-sentence and sentence-final elsewhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UtteranceAccuracyTest {

    private class Case(
        val first: String,
        val second: String,
        val lang: SourceLang,
        /** True when the two balloons really are one sentence. */
        val linked: Boolean,
        val why: String,
    )

    private val corpus = listOf(
        // ---- genuinely split sentences ----
        Case("あいつが", "来たのか", SourceLang.JA, true, "subject particle, verb in the next balloon"),
        Case("わたしは…", "もう戻らない", SourceLang.JA, true, "trailing off into the next balloon"),
        Case("でも、それって", "おかしくない？", SourceLang.JA, true, "quotative って"),
        Case("きのう見たんだけど", "すごかった", SourceLang.JA, true, "けど joins the clauses"),
        Case("こんなところで", "何してるの", SourceLang.JA, true, "locative で"),
        Case("それを聞いて", "安心した", SourceLang.JA, true, "て-form chains the verbs"),
        Case("行かなきゃ、", "急いで", SourceLang.JA, true, "comma mid-sentence"),
        Case("내가 가는데", "왜 안 와", SourceLang.KO, true, "-는데 is a connective ending"),
        Case("배가 고파서", "먼저 먹었어", SourceLang.KO, true, "-아서 gives the reason"),
        Case("我知道但是", "已经太晚了", SourceLang.ZH, true, "但是 opens the contrast"),

        // ---- separate sentences that must not merge ----
        Case("行くよ。", "待って", SourceLang.JA, false, "full stop closes the line"),
        Case("本当に？", "そうだよ", SourceLang.JA, false, "question, then a different speaker answers"),
        Case("やめろ！", "うるさい", SourceLang.JA, false, "shout, then a reply"),
        Case("あいつが", "「来たのか", SourceLang.JA, false, "the next balloon opens a quotation"),
        Case("終わりだ", "そうか", SourceLang.JA, false, "だ closes the assertion"),
        Case("가자.", "응", SourceLang.KO, false, "full stop closes the line"),
        Case("好了。", "走吧", SourceLang.ZH, false, "full stop closes the line"),

        // ---- particles that end sentences as often as they join them ----
        Case("そうなの", "びっくりした", SourceLang.JA, false, "の here is sentence-final, not a nominaliser"),
        Case("そうだな", "行こうか", SourceLang.JA, false, "な is a sentence-final particle"),
        Case("きれいだね", "ほんとに", SourceLang.JA, false, "ね closes the line"),
        Case("いいよ", "ありがとう", SourceLang.JA, false, "よ closes the line"),

        // ---- a line and the grunt that answers it ----
        Case("大丈夫だから", "うん", SourceLang.JA, false, "sentence-final から, answered with a grunt"),
        Case("知らないって", "え？", SourceLang.JA, false, "sentence-final quotative って, answered"),
        Case("別にいいし", "そう", SourceLang.JA, false, "sentence-final し, answered"),
        Case("가야 하는데", "응", SourceLang.KO, false, "-는데 trailing, answered with a grunt"),
        Case("我知道但是", "嗯", SourceLang.ZH, false, "但是 trailing, answered with a grunt"),

        // ---- requests and quotatives that only look like chains ----
        Case("待って", "今行くから", SourceLang.JA, false, "待って is a request, not a te-form chain"),
        Case("やめて", "なんでだよ", SourceLang.JA, false, "やめて is a request"),
        Case("急いで", "わかってる", SourceLang.JA, false, "急いで is a request"),
        Case("알았다고", "그래 알았어", SourceLang.KO, false, "-다고 closes a line as a quotative"),
        Case("빨리 가자고", "알았다니까", SourceLang.KO, false, "-자고 closes a line as a quotative"),

        // ---- endings dropped from the tables because they close lines ----
        Case("내 거야", "뭐라고?", SourceLang.KO, false, "-야 is the casual copula ending"),
        Case("빨리 가라", "싫어", SourceLang.KO, false, "-라 is an imperative ending"),
        Case("我知道了", "那就好", SourceLang.ZH, false, "了 closes the line"),
        Case("這是我的", "是嗎", SourceLang.ZH, false, "的 closes the line"),

        // ---- and chains that still must link ----
        Case("私は", "行きません", SourceLang.JA, true, "topic particle, predicate in the next balloon"),
        Case("もしかして", "あの人が犯人", SourceLang.JA, true, "an adverb ending in て is not a request"),
        Case("それを聞いて", "ほっとしたよ", SourceLang.JA, true, "a te-form chain long enough to be one"),
        Case("그러니까 내 말은", "네가 틀렸다고", SourceLang.KO, true, "-은 topic, predicate follows"),
    )

    /** Stacks the pair as two balloons in one panel, close enough to link. */
    private fun pair(case: Case): List<Bubble> = listOf(
        Bubble(case.first, Rect(100, 100, 300, 200), true),
        Bubble(case.second, Rect(100, 220, 300, 320), true),
    )

    @Test
    fun `split-sentence detection scores on a labelled corpus`() {
        var truePos = 0
        var falsePos = 0
        var trueNeg = 0
        var falseNeg = 0
        val mistakes = ArrayList<String>()

        for (case in corpus) {
            val linked = Utterance.link(pair(case), case.lang)[0].runId >= 0
            when {
                case.linked && linked -> truePos++
                case.linked && !linked -> {
                    falseNeg++
                    mistakes += "MISSED  \"${case.first}\" + \"${case.second}\" — ${case.why}"
                }
                !case.linked && linked -> {
                    falsePos++
                    mistakes += "WELDED  \"${case.first}\" + \"${case.second}\" — ${case.why}"
                }
                else -> trueNeg++
            }
        }

        val positives = truePos + falseNeg
        val negatives = trueNeg + falsePos
        val report = buildString {
            append("\nsplit-sentence detection — labelled corpus\n")
            append("  linked correctly      $truePos/$positives\n")
            append("  kept apart correctly  $trueNeg/$negatives\n")
            if (mistakes.isEmpty()) append("  no mistakes\n") else {
                append("  mistakes:\n")
                mistakes.forEach { append("    $it\n") }
            }
        }
        println(report)

        // A false positive invents a sentence that was never on the page, so
        // none are tolerated. Misses are survivable and merely cost context.
        assertEquals(report, 0, falsePos)
        assertTrue(report, truePos >= positives - 1)
    }
}
