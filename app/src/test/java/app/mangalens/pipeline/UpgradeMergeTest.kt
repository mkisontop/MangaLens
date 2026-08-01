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
 * The rule the reader experiences as "the translation didn't vanish while I
 * was reading it": an upgrade may replace draft cards, but may never leave
 * fewer cards than the draft showed.
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
    fun `an empty polish keeps every draft card`() {
        val draft = listOf(
            bubble(Rect(0, 0, 100, 60), "DRAFT ONE"),
            bubble(Rect(0, 100, 100, 160), "DRAFT TWO"),
        )
        val merged = UpgradeMerge.merge(draft, emptyList())
        assertEquals(listOf("DRAFT ONE", "DRAFT TWO"), merged.map { it.translated })
    }

    @Test
    fun `a full polish replaces the draft entirely`() {
        val draft = listOf(bubble(Rect(0, 0, 100, 60), "draft"))
        val polished = listOf(bubble(Rect(2, 3, 98, 58), "POLISHED"))
        val merged = UpgradeMerge.merge(draft, polished)
        assertEquals(listOf("POLISHED"), merged.map { it.translated })
    }

    @Test
    fun `a partial polish keeps the drafts it did not answer`() {
        val draft = listOf(
            bubble(Rect(0, 0, 100, 60), "ANSWERED DRAFT"),
            bubble(Rect(0, 200, 100, 260), "ORPHAN DRAFT"),
        )
        val polished = listOf(bubble(Rect(1, 2, 99, 59), "POLISHED"))
        val merged = UpgradeMerge.merge(draft, polished)
        assertEquals(2, merged.size)
        assertTrue(merged.any { it.translated == "POLISHED" })
        assertTrue(merged.any { it.translated == "ORPHAN DRAFT" })
        assertTrue(merged.none { it.translated == "ANSWERED DRAFT" })
    }

    @Test
    fun `a grazing overlap does not count as answered`() {
        // 100x60 draft; polished box overlaps only a 20x12 corner (4% of it).
        val draft = listOf(bubble(Rect(0, 0, 100, 60), "DRAFT"))
        val polished = listOf(bubble(Rect(80, 48, 200, 120), "ELSEWHERE"))
        val merged = UpgradeMerge.merge(draft, polished)
        assertEquals(2, merged.size)
    }

    @Test
    fun `no draft passes the polish through untouched`() {
        val polished = listOf(bubble(Rect(0, 0, 50, 30), "P"))
        assertEquals(polished, UpgradeMerge.merge(emptyList(), polished))
    }
}
