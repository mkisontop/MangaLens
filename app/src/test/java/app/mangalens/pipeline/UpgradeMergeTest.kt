package app.mangalens.pipeline

import android.graphics.Color
import android.graphics.Rect
import app.mangalens.overlay.RenderBubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rule the reader experiences as "the line didn't vanish while I was
 * reading it": the answer a pass ends with may replace the lines that
 * streamed in, but may never leave fewer cards than the page already showed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpgradeMergeTest {

    private fun bubble(box: Rect, text: String) = RenderBubble(
        box = box,
        translated = text,
        original = "原文",
        bgColor = Color.WHITE,
        textColor = Color.BLACK,
        vertical = false,
    )

    @Test
    fun `an empty answer keeps every streamed card`() {
        val streamed = listOf(
            bubble(Rect(0, 0, 100, 60), "STREAMED ONE"),
            bubble(Rect(0, 100, 100, 160), "STREAMED TWO"),
        )
        val merged = UpgradeMerge.merge(streamed, emptyList())
        assertEquals(listOf("STREAMED ONE", "STREAMED TWO"), merged.map { it.translated })
    }

    @Test
    fun `a full answer replaces what streamed entirely`() {
        val streamed = listOf(bubble(Rect(0, 0, 100, 60), "streamed"))
        val answer = listOf(bubble(Rect(2, 3, 98, 58), "ANSWER"))
        val merged = UpgradeMerge.merge(streamed, answer)
        assertEquals(listOf("ANSWER"), merged.map { it.translated })
    }

    @Test
    fun `a partial answer keeps the streamed cards it did not cover`() {
        val streamed = listOf(
            bubble(Rect(0, 0, 100, 60), "COVERED"),
            bubble(Rect(0, 200, 100, 260), "UNCOVERED"),
        )
        val answer = listOf(bubble(Rect(1, 2, 99, 59), "ANSWER"))
        val merged = UpgradeMerge.merge(streamed, answer)
        assertEquals(2, merged.size)
        assertTrue(merged.any { it.translated == "ANSWER" })
        assertTrue(merged.any { it.translated == "UNCOVERED" })
        assertTrue(merged.none { it.translated == "COVERED" })
    }

    @Test
    fun `a grazing overlap does not count as covered`() {
        // 100x60 card; the answer's box overlaps only a 20x12 corner (4% of it).
        val streamed = listOf(bubble(Rect(0, 0, 100, 60), "STREAMED"))
        val answer = listOf(bubble(Rect(80, 48, 200, 120), "ELSEWHERE"))
        val merged = UpgradeMerge.merge(streamed, answer)
        assertEquals(2, merged.size)
    }

    @Test
    fun `nothing streamed passes the answer through untouched`() {
        val answer = listOf(bubble(Rect(0, 0, 50, 30), "A"))
        assertEquals(answer, UpgradeMerge.merge(emptyList(), answer))
    }
}
