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

    /**
     * The screen's bottom edge cut the balloon when it was first read: the
     * model read what showed, 打工 of 打工？, and said "a part-time job...".
     * Scrolled into full view it reads the whole line; the fragment must
     * give way, or the line stays half-said for good.
     */
    @Test
    fun aLineTheScreenEdgeCutGivesWayToItsWholeReading() {
        // Recalled where the scroll moved it: the column down to the old edge.
        val cut = listOf(item(Rect(397, 1066, 450, 1189), "打工", "a part-time job..."))
        val whole = listOf(item(Rect(397, 1066, 450, 1240), "打工？", "A part-time job?"))
        assertEquals(listOf("A part-time job?"), Wording.keep(cut, whole).map { it.en })
        // Two columns, the second cut two characters short of its end.
        val cutLong = listOf(item(Rect(610, 904, 700, 1134), "我們家的陽斗\n隨時可以過去接", "Haruto can come pick you up anytime,"))
        val wholeLong = listOf(item(Rect(610, 902, 700, 1195), "我們家的陽斗\n隨時可以過去接你啦", "Our Haruto can pick you up anytime!"))
        assertEquals(listOf("Our Haruto can pick you up anytime!"), Wording.keep(cutLong, wholeLong).map { it.en })
    }

    /**
     * The edge cut only a three-column balloon's last column: the whole
     * reading is no taller, but the recalled one is known to be partial,
     * and gives way all the same.
     */
    @Test
    fun aLineKnownToBeCutGivesWayEvenWhenNoTaller() {
        val box = Rect(700, 900, 860, 1180)
        val cut = item(box, "這都已經是今天\n做的第四臺手術了！\n請多", "This is already your fourth surgery today! Please")
        val whole = item(Rect(702, 902, 858, 1180), "這都已經是今天\n做的第四臺手術了！\n請多少休息一會吧", "That's your fourth surgery today! Please rest a bit.")
        assertEquals(listOf("This is already your fourth surgery today! Please"), Wording.keep(listOf(cut), listOf(whole)).map { it.en })
        assertEquals(listOf("That's your fourth surgery today! Please rest a bit."), Wording.keep(listOf(cut), listOf(whole), setOf(cut)).map { it.en })
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
