package app.mangalens.ocr

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The failure this guards: the vision model answered a shout balloon as an
 * unanchored entry, its self-reported box drifted half a screen, and the
 * card was painted over the chapter title art — while the OCR lines had the
 * exact position of that text all along. Anchoring by the text itself must
 * recover the true position, and must refuse to guess when the page doesn't
 * actually show the text in one place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextAnchorTest {

    private fun line(text: String, top: Int, left: Int = 300, width: Int = 320) =
        OcrLine(text, Rect(left, top, left + width, top + 40), false)

    @Test
    fun `a single line holding the whole text anchors to that line`() {
        val lines = listOf(
            line("¡YA NO SÉ!", 180),
            line("OTRA COSA", 900),
        )
        assertEquals(Rect(300, 180, 620, 220), TextAnchor.locate("¡YA NO SÉ!", lines))
    }

    @Test
    fun `stacked lines of one balloon anchor as their union`() {
        val lines = listOf(
            line("¡AH... MI MENTE", 700),
            line("SE ESTÁ QUEDAN-", 748),
            line("DO EN BLANCO", 796),
            line("OTRA VEZ!", 844),
        )
        val box = TextAnchor.locate("¡AH... MI MENTE SE ESTÁ QUEDANDO EN BLANCO OTRA VEZ!", lines)
        assertEquals(Rect(300, 700, 620, 884), box)
    }

    @Test
    fun `case and accents do not break the match`() {
        val lines = listOf(line("Demonios ha podido pasar esto?", 400))
        assertTrue(TextAnchor.locate("¿DEMONIOS HA PODIDO PASAR ESTO?", lines) != null)
    }

    @Test
    fun `the same word elsewhere cannot stretch the anchor across the page`() {
        // "mirando" appears in another balloon far away; the anchor must stay
        // on the cluster that carries the sentence, not span both.
        val lines = listOf(
            line("DALYOUNG ESTÁ", 300),
            line("MIRANDO...", 348),
            line("MIRANDO", 1500),
        )
        val box = TextAnchor.locate("DALYOUNG ESTÁ MIRANDO...", lines)!!
        assertTrue("anchor must not reach the far duplicate, got $box", box.bottom < 500)
    }

    @Test
    fun `a stray short fragment is not taken for the sentence`() {
        val lines = listOf(line("DE", 900, width = 60))
        assertNull(TextAnchor.locate("¡D-DE VERDAD ESTOY BIEN! ¡UH!", lines))
    }

    @Test
    fun `no match means no anchor rather than a guess`() {
        val lines = listOf(line("COMPLETAMENTE DISTINTO", 500))
        assertNull(TextAnchor.locate("¡YA NO SÉ!", lines))
    }

    @Test
    fun `cjk text anchors the same way`() {
        val lines = listOf(
            line("괜찮아요", 220),
            line("정말로", 268),
        )
        assertEquals(Rect(300, 220, 620, 308), TextAnchor.locate("괜찮아요 정말로", lines))
    }
}
