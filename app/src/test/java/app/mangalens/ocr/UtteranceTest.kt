package app.mangalens.ocr

import android.graphics.Rect
import app.mangalens.settings.SourceLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Linking is deliberately reluctant: welding two characters' lines into one
 * sentence reads far worse than translating a tail clause on its own, so these
 * cases pin the misses as firmly as the hits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UtteranceTest {

    /** Stacks bubbles with a small vertical gap, as balloons in one panel sit. */
    private fun column(vararg texts: String, kind: BubbleKind = BubbleKind.DIALOGUE): List<Bubble> =
        texts.mapIndexed { i, t ->
            val top = 100 + i * 120
            Bubble(t, Rect(100, top, 200, top + 100), true, kind)
        }

    private fun runsOf(bubbles: List<Bubble>) = bubbles.map { it.runId }

    @Test
    fun `a clause ending in a connective particle links to the next balloon`() {
        // "あいつが" cannot end a sentence — the subject it marks has no verb
        // yet, and that verb is in the following balloon.
        val linked = Utterance.link(column("あいつが", "来たのか"), SourceLang.JA)

        assertEquals(listOf(0, 0), runsOf(linked))
        assertEquals(listOf(0, 1), linked.map { it.runPart })
    }

    @Test
    fun `trailing off links, because that is how a split line signals itself`() {
        val linked = Utterance.link(column("わたしは…", "もう戻らない"), SourceLang.JA)
        assertEquals(listOf(0, 0), runsOf(linked))
    }

    @Test
    fun `a completed sentence does not swallow the line after it`() {
        val linked = Utterance.link(column("行くよ。", "待って"), SourceLang.JA)
        assertEquals(listOf(-1, -1), runsOf(linked))
    }

    @Test
    fun `a question mark closes the line`() {
        val linked = Utterance.link(column("本当に？", "そうだよ"), SourceLang.JA)
        assertEquals(listOf(-1, -1), runsOf(linked))
    }

    @Test
    fun `balloons far apart are separate lines however they end`() {
        val near = Bubble("あいつが", Rect(100, 100, 200, 200), true)
        val far = Bubble("来たのか", Rect(100, 900, 200, 1000), true)

        assertEquals(listOf(-1, -1), runsOf(Utterance.link(listOf(near, far), SourceLang.JA)))
    }

    @Test
    fun `an opening quote starts a new line even after a dangling clause`() {
        val linked = Utterance.link(column("あいつが", "「来たのか"), SourceLang.JA)
        assertEquals(listOf(-1, -1), runsOf(linked))
    }

    @Test
    fun `Korean connective endings link`() {
        val linked = Utterance.link(column("내가 가는데", "왜 안 와"), SourceLang.KO)
        assertEquals(listOf(0, 0), runsOf(linked))
    }

    @Test
    fun `Chinese conjunctions link`() {
        val linked = Utterance.link(column("我知道但是", "已经太晚了"), SourceLang.ZH)
        assertEquals(listOf(0, 0), runsOf(linked))
    }

    @Test
    fun `auto mode picks the rule table from the script itself`() {
        assertTrue(Utterance.dangles("내가 가는데", SourceLang.AUTO))
        assertTrue(Utterance.dangles("あいつが", SourceLang.AUTO))
        assertFalse(Utterance.dangles("行くよ。", SourceLang.AUTO))
    }

    @Test
    fun `sound effects never join a sentence`() {
        val sfx = column("ドォン", kind = BubbleKind.SFX)
        val dialogue = column("あいつが")
        val mixed = listOf(dialogue[0], sfx[0].copy(box = Rect(100, 220, 200, 320)))

        assertEquals(listOf(-1, -1), runsOf(Utterance.link(mixed, SourceLang.JA)))
    }

    @Test
    fun `a lone interjection is complete on its own`() {
        // One or two kana is an "ah" or an "eh" — never the front half of a
        // sentence, whatever character it happens to end on.
        assertFalse(Utterance.dangles("え", SourceLang.JA))
        assertFalse(Utterance.dangles("は", SourceLang.JA))
    }

    @Test
    fun `a run stops at four balloons`() {
        val linked = Utterance.link(
            column("あいつが", "ずっと", "まえから", "ここに", "いたんだ"),
            SourceLang.JA,
        )
        // The first four link; the fifth is left to stand alone rather than
        // letting a mis-detection run away down the page.
        assertEquals(listOf(0, 0, 0, 0, -1), runsOf(linked))
        assertEquals(listOf(0, 1, 2, 3, 0), linked.map { it.runPart })
    }

    @Test
    fun `unlinked bubbles are returned unchanged`() {
        val input = column("行くよ。", "待って。")
        val linked = Utterance.link(input, SourceLang.JA)
        assertEquals(input, linked)
    }
}
