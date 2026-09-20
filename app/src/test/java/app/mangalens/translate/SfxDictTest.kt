package app.mangalens.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sound effects as the page letters them and as OCR returns them, not as a
 * dictionary spells them: hiragana for the soft ones, a kanji 一 where the
 * recognizer misread a long-vowel bar, stretched tails, and the Korean and
 * Chinese forms — traditional included.
 */
class SfxDictTest {

    @Test
    fun `hiragana and katakana are one sound effect`() {
        assertEquals("SQUEEZE", SfxDict.lookup("ぎゅ"))
        assertEquals("SQUEEZE", SfxDict.lookup("ギュッ"))
        assertEquals("BA-DUMP BA-DUMP", SfxDict.lookup("どきどき"))
        assertEquals("BA-DUMP BA-DUMP", SfxDict.lookup("ドキドキ"))
        assertEquals("MURMUR", SfxDict.lookup("ざわざわ"))
        assertEquals("…SILENCE…", SfxDict.lookup("しーん"))
    }

    @Test
    fun `a long vowel bar misread as the kanji one still matches`() {
        assertEquals("BOOM", SfxDict.lookup("ド一ン"))
        assertEquals("…SILENCE…", SfxDict.lookup("シ一ン"))
        assertEquals("…STARE…", SfxDict.lookup("ジ―"))
    }

    @Test
    fun `stretched and repeated forms take the longest stem`() {
        assertEquals("RUMBLE", SfxDict.lookup("ゴゴゴゴゴ"))
        assertEquals("GULP GULP", SfxDict.lookup("ゴクゴクゴク"))
        assertEquals("GULP", SfxDict.lookup("ゴクッ"))
        assertEquals("…STARE…", SfxDict.lookup("ジロジロ"))
        assertEquals("GLARE", SfxDict.lookup("ジロッ"))
    }

    @Test
    fun `states are rendered as states, not noises`() {
        assertEquals("…SHOCK…", SfxDict.lookup("ガーン"))
        assertEquals("…DAZED…", SfxDict.lookup("ぼーっ"))
        assertEquals("…IRRITATED…", SfxDict.lookup("イライラ"))
        assertEquals("…DAZED…", SfxDict.lookup("멍하니"))
    }

    @Test
    fun `korean and chinese, simplified and traditional`() {
        assertEquals("THUD THUD", SfxDict.lookup("쿵쿵"))
        assertEquals("CLICK", SfxDict.lookup("찰칵"))
        assertEquals("NOD NOD", SfxDict.lookup("끄덕끄덕"))
        assertEquals("RUMBLE", SfxDict.lookup("轰隆隆"))
        assertEquals("RUMBLE", SfxDict.lookup("轟隆隆"))
        assertEquals("SOB SOB", SfxDict.lookup("嗚嗚"))
        assertEquals("THUMP", SfxDict.lookup("撲通"))
    }

    @Test
    fun `a stray border stroke in front is ignored and unknown text is left alone`() {
        assertEquals("THUD", SfxDict.lookup("ードン"))
        assertNull(SfxDict.lookup("ケーキ"))
        assertNull(SfxDict.lookup(""))
    }
}
