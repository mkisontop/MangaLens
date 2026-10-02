package app.mangalens.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.mangalens.overlay.FloatingButtonView
import app.mangalens.overlay.OverlayStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every colour pair the design puts text or a boundary on, held to WCAG in
 * both palettes: 4.5 for text, 3.0 for shapes. The overlay's colours are
 * checked against both a white and a black page, since it floats over
 * whatever the reader has open.
 */
class PopContrastTest {

    private fun argb(c: Color) = c.toArgb()

    /** [top] (with alpha) laid over opaque [bottom]. */
    private fun over(top: Int, bottom: Int): Int {
        val a = (top ushr 24) / 255.0
        fun ch(shift: Int): Int {
            val t = (top shr shift) and 0xFF
            val b = (bottom shr shift) and 0xFF
            return (t * a + b * (1 - a) + 0.5).toInt()
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun check(name: String, fg: Int, bg: Int, min: Double) {
        val r = contrastRatio(fg, bg)
        assertTrue("$name is %.2f:1, needs %.1f:1".format(r, min), r >= min)
    }

    private fun textPairs(p: PopColors) = listOf(
        "ink/paper" to (p.ink to p.paper),
        "ink/surface" to (p.ink to p.surface),
        "inkSoft/paper" to (p.inkSoft to p.paper),
        "inkSoft/surface" to (p.inkSoft to p.surface),
        "inkSoft/surfaceHi" to (p.inkSoft to p.surfaceHi),
        "onPunch/punch" to (p.onPunch to p.punch),
        "onZap/zap" to (p.onZap to p.zap),
        "punchText/paper" to (p.punchText to p.paper),
        "punchText/surface" to (p.punchText to p.surface),
        "punchText/zapSoft" to (p.punchText to p.zapSoft),
        "ink/zapSoft" to (p.ink to p.zapSoft),
        "faceInk/sleepBody" to (p.faceInk to p.sleepBody),
        "faceInk/zap" to (p.faceInk to p.zap),
    )

    private fun shapePairs(p: PopColors) = listOf(
        "faceInk/punch" to (p.faceInk to p.punch),
        "stroke/paper" to (p.stroke to p.paper),
        "stroke/surface" to (p.stroke to p.surface),
        "zap/faceInk" to (p.zap to p.faceInk),
        "zapSoftStroke/zapSoft" to (p.zapSoftStroke to p.zapSoft),
        "zapSoftStroke/paper" to (p.zapSoftStroke to p.paper),
    )

    @Test
    fun `text pairs reach 4·5 to 1 in both themes`() {
        for ((theme, p) in listOf("light" to LightPop, "dark" to DarkPop)) {
            for ((name, pair) in textPairs(p)) check("$theme $name", argb(pair.first), argb(pair.second), 4.5)
        }
    }

    @Test
    fun `outlines and Fuki's features reach 3 to 1 in both themes`() {
        for ((theme, p) in listOf("light" to LightPop, "dark" to DarkPop)) {
            for ((name, pair) in shapePairs(p)) check("$theme $name", argb(pair.first), argb(pair.second), 3.0)
        }
    }

    @Test
    fun `Fuki's face colours do not change with the theme`() {
        assertEquals(LightPop.faceInk, DarkPop.faceInk)
        assertEquals(LightPop.sleepBody, DarkPop.sleepBody)
    }

    @Test
    fun `the status pill reads over white pages and black ones`() {
        for (page in listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt())) {
            val bg = over(OverlayStyle.PILL_BG, page)
            check("pill text", OverlayStyle.PILL_TEXT, bg, 4.5)
            check("pill alert", OverlayStyle.PILL_ALERT, bg, 4.5)
        }
    }

    @Test
    fun `menu items and the stop item read on the menu's paper`() {
        check("menu ink", OverlayStyle.MENU_INK, OverlayStyle.MENU_PAPER, 4.5)
        check("menu stop", OverlayStyle.MENU_STOP, OverlayStyle.MENU_PAPER, 4.5)
        check("menu ink pressed", OverlayStyle.MENU_INK, OverlayStyle.MENU_PRESSED, 4.5)
    }

    @Test
    fun `the button's glyph and disc read in both states over any page`() {
        for (page in listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt())) {
            val live = over(FloatingButtonView.LIVE_FILL, page)
            val paused = over(FloatingButtonView.PAUSED_FILL, page)
            check("live glyph", FloatingButtonView.LIVE_GLYPH, live, 4.5)
            check("paused glyph", FloatingButtonView.PAUSED_GLYPH, paused, 4.5)
        }
        check("live rim on white", FloatingButtonView.LIVE_STROKE, 0xFFFFFFFF.toInt(), 3.0)
        check("live disc on black", over(FloatingButtonView.LIVE_FILL, 0xFF000000.toInt()), 0xFF000000.toInt(), 3.0)
        check("paused disc on white", over(FloatingButtonView.PAUSED_FILL, 0xFFFFFFFF.toInt()), 0xFFFFFFFF.toInt(), 3.0)
        check("paused rim on black", FloatingButtonView.PAUSED_STROKE, 0xFF000000.toInt(), 3.0)
    }

    @Test
    fun `the contrast formula matches WCAG's anchors`() {
        assertEquals(21.0, contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, contrastRatio(0xFF777777.toInt(), 0xFF777777.toInt()), 0.0001)
        assertEquals(4.54, contrastRatio(0xFF767676.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }
}
