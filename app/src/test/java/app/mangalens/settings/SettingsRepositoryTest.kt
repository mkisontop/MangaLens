package app.mangalens.settings

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * An install from before Gemini became the default is on Anthropic without
 * ever having saved it. Editing that provider's key writes the choice down,
 * so clearing the key to paste a new one never switches the reader to
 * Gemini halfway through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

    @Test
    fun `editing a provider's key keeps that provider even while the field is empty`() = runBlocking {
        val repo = SettingsRepository(RuntimeEnvironment.getApplication())
        assertEquals("a fresh install reads with Gemini", LlmProvider.GEMINI, repo.current().provider)
        repo.setApiKey(LlmProvider.ANTHROPIC, "")
        assertEquals(LlmProvider.ANTHROPIC, repo.current().provider)
        repo.setApiKey(LlmProvider.ANTHROPIC, "new-key")
        assertEquals("new-key", repo.current().apiKey)
        // A provider chosen on purpose is never overwritten by an edit.
        repo.setProvider(LlmProvider.OPENAI)
        repo.setModel(LlmProvider.ANTHROPIC, "claude-model")
        assertEquals(LlmProvider.OPENAI, repo.current().provider)
    }

    @Test
    fun `auto-scroll's settings are kept, and its speed stays within its levels`() = runBlocking {
        val repo = SettingsRepository(RuntimeEnvironment.getApplication())
        val fresh = repo.current()
        assertEquals(true, fresh.autoScrollButton)
        assertEquals(4, fresh.scrollLevel)
        assertEquals(true, fresh.smartScroll)
        repo.setAutoScrollButton(false)
        repo.setScrollLevel(7)
        repo.setSmartScroll(false)
        val saved = repo.current()
        assertEquals(false, saved.autoScrollButton)
        assertEquals(7, saved.scrollLevel)
        assertEquals(false, saved.smartScroll)
        repo.setScrollLevel(99)
        assertEquals(10, repo.current().scrollLevel)
        repo.setScrollLevel(-2)
        assertEquals(1, repo.current().scrollLevel)
    }
}
