package app.mangalens.ui

import app.mangalens.settings.AiReasoning
import app.mangalens.settings.AiVisionMode
import app.mangalens.settings.AppSettings
import app.mangalens.settings.CaptureMode
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SourceLang
import app.mangalens.translate.GeminiHttpException
import app.mangalens.translate.GeminiRateLimited
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key flow shared by setup and Tweaks: what a paste saves, what the
 * store's echo may and may not overwrite, and what the test line says when
 * it fails. Hermetic: the AI is a stand-in lambda. The keys are synthetic.
 */
class KeyFlowTest {

    private class RecordingSink : SettingsSink {
        val keys = mutableListOf<Pair<LlmProvider, String>>()
        val models = mutableListOf<Pair<LlmProvider, String>>()
        override fun setProvider(v: LlmProvider) = Unit
        override fun setApiKey(provider: LlmProvider, v: String) {
            keys += provider to v
        }
        override fun setModel(provider: LlmProvider, v: String) {
            models += provider to v
        }
        override fun setCustomUrl(v: String) = Unit
        override fun setSourceLang(v: SourceLang) = Unit
        override fun setMode(v: CaptureMode) = Unit
        override fun setAiVision(v: AiVisionMode) = Unit
        override fun setAiReasoning(v: AiReasoning) = Unit
        override fun setDataSaver(v: Boolean) = Unit
        override fun setAiCleanup(v: Boolean) = Unit
        override fun setDiagnostics(v: Boolean) = Unit
        override fun setTextScale(v: Float) = Unit
        override fun setNoGhosts(v: Boolean) = Unit
        override fun setIgnoreTopPct(v: Float) = Unit
        override fun setStabilityMs(v: Int) = Unit
        override fun setAutoScrollButton(v: Boolean) = Unit
        override fun setScrollLevel(v: Int) = Unit
        override fun setSmartScroll(v: Boolean) = Unit
    }

    private val key = "test-key-" + "x".repeat(24)

    /** Runs the test line at once on the calling thread. */
    private fun sayHi(attempt: suspend (AppSettings) -> String) = SayHi(CoroutineScope(Dispatchers.Unconfined), attempt)

    @Test
    fun `a pasted key is trimmed, saved for its provider and tried at once`() {
        val sink = RecordingSink()
        val drafts = AiDrafts(AppSettings(), sink)
        var tried: String? = null
        val hi = sayHi { s -> tried = s.apiKey; "It's okay." }
        assertEquals(PasteCheck.OK, drafts.submitKey("  $key\n", hi))
        assertEquals(listOf(LlmProvider.GEMINI to key), sink.keys)
        assertEquals(key, tried)
        assertEquals(SayHi.Phase.Ok("It's okay."), hi.phase)
        assertEquals(key, drafts.settings.apiKey)
    }

    @Test
    fun `a clipboard with no key saves nothing and runs nothing`() {
        val sink = RecordingSink()
        val drafts = AiDrafts(AppSettings(), sink)
        val hi = sayHi { error("must not run") }
        assertEquals(PasteCheck.EMPTY, drafts.submitKey("", hi))
        assertEquals(PasteCheck.NOT_A_KEY, drafts.submitKey("copy this sentence", hi))
        assertTrue(sink.keys.isEmpty())
        assertEquals(SayHi.Phase.Idle, hi.phase)
        assertEquals("Your clipboard is empty. Copy the key first, then tap Paste.", pasteNotice(PasteCheck.EMPTY))
        assertEquals(null, pasteNotice(PasteCheck.OK))
    }

    @Test
    fun `the store's echo never rolls back typing, but a provider switch starts fresh`() {
        val sink = RecordingSink()
        val drafts = AiDrafts(AppSettings(), sink)
        drafts.editKey("test-key-abc")
        drafts.sync(AppSettings(apiKey = "test-key-ab"))
        assertEquals("test-key-abc", drafts.key)
        drafts.editModel("some-model")
        drafts.sync(AppSettings(apiKey = "test-key-abc", model = ""))
        assertEquals("some-model", drafts.model)

        drafts.sync(AppSettings(provider = LlmProvider.ANTHROPIC, apiKey = "test-key-other"))
        assertEquals("test-key-other", drafts.key)
        assertEquals("", drafts.model)
        assertEquals(LlmProvider.ANTHROPIC, drafts.settings.provider)
    }

    @Test
    fun `an untouched field takes the first real value from the store`() {
        val drafts = AiDrafts(AppSettings(), RecordingSink())
        drafts.sync(AppSettings(apiKey = key, customUrl = "http://127.0.0.1/v1/chat"))
        assertEquals(key, drafts.key)
        assertEquals("http://127.0.0.1/v1/chat", drafts.customUrl)
    }

    @Test
    fun `a refused key is marked rejected, and a busy one is not`() {
        val refused = sayHi { throw GeminiHttpException(400, "API key not valid. Please pass a valid API key.") }
        refused.run(AppSettings(apiKey = key))
        val failed = refused.phase as SayHi.Phase.Failed
        assertTrue(failed.rejected)
        assertTrue(refused.rejects(AppSettings(apiKey = key)))
        assertEquals(friendlyTestFailure("key rejected", "Gemini"), failed.message)
        // A new key, or another provider, is no longer held to that refusal.
        assertFalse(refused.rejects(AppSettings(apiKey = key + "y")))
        assertFalse(refused.rejects(AppSettings(provider = LlmProvider.OPENAI, apiKey = key)))

        val busy = sayHi { throw GeminiRateLimited("quota") }
        busy.run(AppSettings(apiKey = key))
        assertFalse(busy.rejects(AppSettings(apiKey = key)))
        assertEquals(friendlyTestFailure("rate limited", "Gemini"), (busy.phase as SayHi.Phase.Failed).message)

        val offline = sayHi { throw IOException("no route") }
        offline.run(AppSettings(provider = LlmProvider.ANTHROPIC, apiKey = key))
        assertEquals(friendlyTestFailure("network", "Claude"), (offline.phase as SayHi.Phase.Failed).message)
    }

    @Test
    fun `a test with no key or endpoint never reaches the AI and offers no retry`() {
        var calls = 0
        val hi = sayHi { calls++; "hi" }
        assertFalse(hi.runIfReady(AppSettings()))
        assertEquals(0, calls)
        val failed = hi.phase as SayHi.Phase.Failed
        assertEquals("Paste your Gemini key above first, then test.", failed.message)
        assertFalse(failed.rejected)
        assertFalse(failed.retry)
        assertFalse(hi.rejects(AppSettings()))

        assertFalse(hi.runIfReady(AppSettings(provider = LlmProvider.CUSTOM, apiKey = key)))
        assertEquals("Add your server's URL above first, then test.", (hi.phase as SayHi.Phase.Failed).message)
        assertEquals(0, calls)

        assertTrue(hi.runIfReady(AppSettings(apiKey = key)))
        assertEquals(1, calls)
        assertEquals(SayHi.Phase.Ok("hi"), hi.phase)
    }

    @Test
    fun `saving a custom token with no endpoint yet asks for the endpoint`() {
        var calls = 0
        val hi = sayHi { calls++; "hi" }
        val drafts = AiDrafts(AppSettings(provider = LlmProvider.CUSTOM), RecordingSink())
        assertEquals(PasteCheck.OK, drafts.submitKey(key, hi))
        assertEquals(0, calls)
        assertEquals("Add your server's URL above first, then test.", (hi.phase as SayHi.Phase.Failed).message)
    }

    @Test
    fun `only a failure that trying again could fix offers a retry`() {
        val refused = sayHi { throw GeminiHttpException(400, "API key not valid. Please pass a valid API key.") }
        refused.run(AppSettings(apiKey = key))
        assertFalse((refused.phase as SayHi.Phase.Failed).retry)
        val busy = sayHi { throw GeminiRateLimited("quota") }
        busy.run(AppSettings(apiKey = key))
        assertTrue((busy.phase as SayHi.Phase.Failed).retry)
    }

    @Test
    fun `a slow answer times out instead of cancelling the test quietly`() {
        val hi = SayHi(CoroutineScope(Dispatchers.Unconfined)) { withTimeout(1) { delay(5_000); "late" } }
        hi.run(AppSettings(apiKey = key))
        val deadline = System.currentTimeMillis() + 5_000
        while (hi.phase == SayHi.Phase.Running && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(SayHi.Phase.Failed(friendlyTestFailure("timeout", "Gemini"), rejected = false), hi.phase)
    }
}
