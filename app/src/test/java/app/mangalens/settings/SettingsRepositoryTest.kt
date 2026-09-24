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
}
