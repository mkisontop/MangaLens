package app.mangalens.ui

import app.mangalens.settings.AppSettings
import app.mangalens.settings.CaptureMode
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SourceLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home screen's decisions. Keys here are obviously synthetic: none has
 * any provider's real format beyond the one prefix the Gemini hint looks at.
 */
class HomeLogicTest {

    private fun stage(
        loaded: Boolean = true,
        running: Boolean = false,
        paused: Boolean = false,
        overlay: Boolean = true,
        ai: Boolean = true,
        latch: Boolean = false,
    ) = homeStage(loaded, running, paused, overlay, ai, latch)

    @Test
    fun `nothing shows until the settings have loaded`() {
        assertEquals(Stage.LOADING, stage(loaded = false))
        assertEquals(Stage.LOADING, stage(loaded = false, running = true))
    }

    @Test
    fun `running wins over missing setup so a reader can always stop`() {
        assertEquals(Stage.RUNNING, stage(running = true, overlay = false, ai = false))
        assertEquals(Stage.RUNNING, stage(running = true, latch = true))
    }

    @Test
    fun `paused shows only while running`() {
        assertEquals(Stage.PAUSED, stage(running = true, paused = true))
        assertEquals(Stage.READY, stage(running = false, paused = true))
    }

    @Test
    fun `setup shows while anything is missing, and the latch keeps it after`() {
        assertEquals(Stage.SETUP, stage(overlay = false))
        assertEquals(Stage.SETUP, stage(ai = false))
        assertEquals(Stage.SETUP, stage(overlay = false, ai = false))
        assertEquals(Stage.SETUP, stage(latch = true))
        assertEquals(Stage.READY, stage())
    }

    @Test
    fun `paste check turns away what is plainly not a key`() {
        val g = LlmProvider.GEMINI
        assertEquals(PasteCheck.EMPTY, pasteCheck(g, ""))
        assertEquals(PasteCheck.EMPTY, pasteCheck(g, "   \n"))
        assertEquals(PasteCheck.NOT_A_KEY, pasteCheck(g, "hello world"))
        assertEquals(PasteCheck.NOT_A_KEY, pasteCheck(g, "https://example.com/key"))
        assertEquals(PasteCheck.NOT_A_KEY, pasteCheck(g, "HTTP://EXAMPLE.COM/" + "k".repeat(20)))
        assertEquals(PasteCheck.NOT_A_KEY, pasteCheck(g, "short-token1"))
        assertEquals(PasteCheck.NOT_A_KEY, pasteCheck(g, "x".repeat(301)))
    }

    @Test
    fun `paste check accepts a key, trimmed`() {
        val key = "test-key-" + "x".repeat(24)
        for (p in LlmProvider.entries) assertEquals(PasteCheck.OK, pasteCheck(p, key))
        assertEquals(PasteCheck.OK, pasteCheck(LlmProvider.GEMINI, "  $key  "))
        assertEquals(PasteCheck.OK, pasteCheck(LlmProvider.GEMINI, "$key\n"))
    }

    @Test
    fun `a custom endpoint's token may be any length`() {
        assertEquals(PasteCheck.OK, pasteCheck(LlmProvider.CUSTOM, "short-token1"))
        assertEquals(PasteCheck.NOT_A_KEY, pasteCheck(LlmProvider.CUSTOM, "two words"))
    }

    @Test
    fun `the Gemini hint looks only at the familiar prefix and shape`() {
        assertTrue(looksLikeGeminiKey("AIza" + "x".repeat(35)))
        assertFalse(looksLikeGeminiKey(""))
        assertFalse(looksLikeGeminiKey("AIza" + "x".repeat(10) + " " + "x".repeat(24)))
        assertFalse(looksLikeGeminiKey("test" + "x".repeat(35)))
        assertFalse(looksLikeGeminiKey("AIza" + "x".repeat(5)))
    }

    @Test
    fun `every test failure gets a message the reader can act on`() {
        val p = "Gemini"
        val causes = listOf(
            "key rejected", "network", "rate limited", "model unavailable", "declined",
            "HTTP 404", "HTTP 503", "timeout", "unexpected error",
        )
        val messages = causes.map { friendlyTestFailure(it, p) }
        assertEquals("every cause says something different", messages.size - 1, messages.toSet().size)
        assertEquals(messages[3], messages[5])
        assertEquals("Gemini said no to that key. Copy the whole key again, then tap Paste.", messages[0])
        assertEquals("I can't reach the internet. Check your connection and try again.", messages[1])
        assertTrue(messages[2].startsWith("Gemini says slow down"))
        assertTrue(messages[3].contains("Tweaks → AI brain"))
        assertTrue(messages[4].startsWith("Gemini declined"))
        assertEquals("Gemini is having a moment. Try again shortly.", messages[6])
        assertEquals("Gemini took too long to answer. Try again?", messages[7])
        assertEquals("Something went wrong (unexpected error). Try again?", messages[8])
        assertEquals("Gemini is having a moment. Try again shortly.", friendlyTestFailure("HTTP 500", p))
    }

    @Test
    fun `the Tweaks summary names language, mode and provider`() {
        val langs = mapOf(
            SourceLang.AUTO to "Auto language", SourceLang.KO to "Korean",
            SourceLang.JA to "Japanese", SourceLang.ZH to "Chinese",
        )
        val providers = mapOf(
            LlmProvider.GEMINI to "Gemini", LlmProvider.ANTHROPIC to "Claude", LlmProvider.OPENAI to "OpenAI",
            LlmProvider.OPENROUTER to "OpenRouter", LlmProvider.CUSTOM to "Custom AI",
        )
        for ((lang, langLabel) in langs) for ((provider, label) in providers) {
            val s = AppSettings(sourceLang = lang, provider = provider)
            assertEquals("$langLabel · Hands-free · $label", tweaksSummary(s))
            assertEquals("$langLabel · Tap to translate · $label", tweaksSummary(s.copy(mode = CaptureMode.MANUAL)))
        }
    }

    @Test
    fun `every provider but a custom endpoint links to where its key is made`() {
        for (p in LlmProvider.entries) {
            val help = providerKeyHelp(p)
            assertTrue(help.message.isNotBlank())
            assertTrue(apiKeyLabel(p).isNotBlank())
            if (p == LlmProvider.CUSTOM) {
                assertNull(help.url)
            } else {
                assertTrue("$p", help.url!!.startsWith("https://"))
                assertTrue(help.linkLabel.endsWith("↗"))
            }
        }
        assertEquals(GEMINI_KEY_URL, providerKeyHelp(LlmProvider.GEMINI).url)
    }

    @Test
    fun `the AI brain header says what is still missing`() {
        assertEquals("Gemini · no key yet" to false, aiBrainSummary(AppSettings()))
        assertEquals("Gemini · key saved ✓" to true, aiBrainSummary(AppSettings(apiKey = "test-key-" + "x".repeat(24))))
        assertEquals("Custom AI · no endpoint yet" to false, aiBrainSummary(AppSettings(provider = LlmProvider.CUSTOM)))
        assertEquals(
            "Custom AI · endpoint set ✓" to true,
            aiBrainSummary(AppSettings(provider = LlmProvider.CUSTOM, customUrl = "http://127.0.0.1/v1/chat")),
        )
    }

    @Test
    fun `the mark never splits across lines, and tips are distinct`() {
        assertEquals("文⁠A", MARK)
        assertEquals(TIPS.size, TIPS.toSet().size)
        assertNotEquals(0, TIPS.size)
        assertTrue(TIPS.none { "文A" in it })
    }
}
