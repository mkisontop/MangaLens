package app.mangalens.pipeline

import android.graphics.Rect
import app.mangalens.translate.ItemKind
import app.mangalens.translate.PageItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A line read again at a later stop keeps the words the reader already
 * saw — but only when both reads cut the lettering the same way. A balloon
 * read whole at one stop and in pieces at the next must never say a line
 * twice, lose half of itself, or come out back to front.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WordingTest {

    private val whole = Rect(200, 300, 520, 420)
    private val top = Rect(200, 300, 520, 360)
    private val bottom = Rect(200, 358, 520, 420)

    private fun item(box: Rect, src: String, en: String) = PageItem(Rect(box), ItemKind.SPEECH, src, en)

    @Test
    fun theSameLineReadAgainKeepsItsWords() {
        val recalled = listOf(item(whole, "どこ行ってたの", "Where were you?"))
        val fresh = listOf(item(Rect(204, 302, 518, 424), "どこ行ってたの", "Where have you been?"))
        val out = Wording.keep(recalled, fresh)
        assertEquals(listOf("Where were you?"), out.map { it.en })
        assertEquals("the new geometry stands", fresh[0].box, out[0].box)
    }

    @Test
    fun aBalloonReadWholeThenInPiecesIsNeverSaidTwice() {
        val recalled = listOf(item(whole, "どこ行ってたの探したよ", "Where were you? I looked everywhere."))
        val fresh = listOf(
            item(top, "どこ行ってたの", "Where were you?"),
            item(bottom, "探したよ", "I looked everywhere."),
        )
        assertEquals(listOf("Where were you?", "I looked everywhere."), Wording.keep(recalled, fresh).map { it.en })
    }

    @Test
    fun piecesReadWholeAtTheNextStopAreNeitherHalvedNorReversed() {
        val recalled = listOf(
            item(top, "どこ行ってたの", "Where were you?"),
            item(bottom, "探したよ", "I looked everywhere."),
        )
        val fresh = listOf(item(whole, "どこ行ってたの探したよ", "Where were you? I looked all over."))
        assertEquals(listOf("Where were you? I looked all over."), Wording.keep(recalled, fresh).map { it.en })
    }

    @Test
    fun aDifferentLineInTheSameSpotIsNotGivenTheOldWords() {
        val recalled = listOf(item(whole, "どこ行ってたの", "Where were you?"))
        val fresh = listOf(item(whole, "ずっと待ってた", "I waited the whole time."))
        assertEquals(listOf("I waited the whole time."), Wording.keep(recalled, fresh).map { it.en })
    }

    @Test
    fun aLineTheModelPassedOverThisTimeStays() {
        val elsewhere = Rect(200, 900, 520, 980)
        val recalled = listOf(item(whole, "どこ行ってたの", "Where were you?"), item(elsewhere, "ここだよ", "Over here."))
        val fresh = listOf(item(whole, "どこ行ってたの", "Where have you been?"))
        assertEquals(listOf("Where were you?", "Over here."), Wording.keep(recalled, fresh).map { it.en })
    }
}
