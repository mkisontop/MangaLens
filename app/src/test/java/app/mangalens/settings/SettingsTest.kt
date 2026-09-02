package app.mangalens.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsTest {

    private val providerKey = stringPreferencesKey("provider")
    private val legacyApiKey = stringPreferencesKey("api_key")
    private val legacyModelKey = stringPreferencesKey("model")

    private fun apiKey(provider: LlmProvider) = stringPreferencesKey(
        when (provider) {
            LlmProvider.ANTHROPIC -> "api_key_anthropic"
            LlmProvider.OPENAI -> "api_key_openai"
            LlmProvider.GEMINI -> "api_key_gemini"
            LlmProvider.OPENROUTER -> "api_key_openrouter"
            LlmProvider.CUSTOM -> "api_key_custom"
        }
    )

    private fun modelKey(provider: LlmProvider) = stringPreferencesKey(
        when (provider) {
            LlmProvider.ANTHROPIC -> "model_anthropic"
            LlmProvider.OPENAI -> "model_openai"
            LlmProvider.GEMINI -> "model_gemini"
            LlmProvider.OPENROUTER -> "model_openrouter"
            LlmProvider.CUSTOM -> "model_custom"
        }
    )

    private fun withProvider(prefs: Preferences, provider: LlmProvider): Preferences =
        prefs.toMutablePreferences().apply { this[providerKey] = provider.name }.toPreferences()

    private data class Migrated(val settings: Preferences, val credentials: Preferences)

    /** Mirrors the two DataStore migrations, including API-key cleanup. */
    private fun migrate(old: Preferences, credentials: Preferences = preferencesOf()): Migrated {
        val migratedCredentials = migrateLegacyApiKey(old, credentials)
        val migratedSettings = migrateLegacyModelSettings(old).toMutablePreferences().apply {
            remove(legacyApiKey)
        }.toPreferences()
        return Migrated(migratedSettings, migratedCredentials)
    }

    @Test
    fun `legacy values migrate only to the provider saved at upgrade`() {
        val old = preferencesOf(
            providerKey to LlmProvider.OPENROUTER.name,
            legacyApiKey to "sk-or-v1-old",
            legacyModelKey to "google/gemini-2.5-flash",
        )

        val migrated = migrate(old)
        val openRouter = settingsFromPreferences(migrated.settings, migrated.credentials)
        val anthropic = settingsFromPreferences(
            withProvider(migrated.settings, LlmProvider.ANTHROPIC),
            migrated.credentials,
        )

        assertEquals("sk-or-v1-old", openRouter.apiKey)
        assertEquals("google/gemini-2.5-flash", openRouter.model)
        assertEquals("", anthropic.apiKey)
        assertEquals("", anthropic.model)
        assertFalse(legacyApiKey in migrated.settings)
        assertFalse(legacyModelKey in migrated.settings)
    }

    @Test
    fun `reasoning defaults to balanced and reads back what was stored`() {
        assertEquals(AiReasoning.BALANCED, settingsFromPreferences(preferencesOf()).aiReasoning)
        val stored = preferencesOf(stringPreferencesKey("ai_reasoning") to "THOROUGH")
        assertEquals(AiReasoning.THOROUGH, settingsFromPreferences(stored).aiReasoning)
        // An unknown value from a newer build falls back rather than crashing.
        val odd = preferencesOf(stringPreferencesKey("ai_reasoning") to "GALACTIC")
        assertEquals(AiReasoning.BALANCED, settingsFromPreferences(odd).aiReasoning)
    }

    @Test
    fun `each provider reads only its own key and model`() {
        val stored = preferencesOf(
            providerKey to LlmProvider.OPENROUTER.name,
            modelKey(LlmProvider.OPENROUTER) to "anthropic/claude-sonnet-4.5",
            modelKey(LlmProvider.GEMINI) to "gemini-flash-latest",
        )
        val credentials = preferencesOf(
            apiKey(LlmProvider.OPENROUTER) to "openrouter-key",
            apiKey(LlmProvider.GEMINI) to "gemini-key",
        )

        val openRouter = settingsFromPreferences(stored, credentials)
        val gemini = settingsFromPreferences(withProvider(stored, LlmProvider.GEMINI), credentials)
        val openAi = settingsFromPreferences(withProvider(stored, LlmProvider.OPENAI), credentials)

        assertEquals("openrouter-key", openRouter.apiKey)
        assertEquals("anthropic/claude-sonnet-4.5", openRouter.model)
        assertEquals("gemini-key", gemini.apiKey)
        assertEquals("gemini-flash-latest", gemini.model)
        assertEquals("", openAi.apiKey)
        assertEquals("", openAi.model)
    }

    @Test
    fun `migration never overwrites an existing scoped value`() {
        val old = preferencesOf(
            providerKey to LlmProvider.OPENROUTER.name,
            legacyApiKey to "legacy-key",
            legacyModelKey to "legacy-model",
            modelKey(LlmProvider.OPENROUTER) to "current-model",
        )
        val existingCredentials = preferencesOf(apiKey(LlmProvider.OPENROUTER) to "current-key")

        val migrated = migrate(old, existingCredentials)
        val settings = settingsFromPreferences(migrated.settings, migrated.credentials)

        assertEquals("current-key", settings.apiKey)
        assertEquals("current-model", settings.model)
        assertFalse(legacyApiKey in migrated.settings)
        assertFalse(legacyModelKey in migrated.settings)
    }

    @Test
    fun `migration is idempotent`() {
        val old = preferencesOf(
            providerKey to LlmProvider.GEMINI.name,
            legacyApiKey to "gemini-key",
            legacyModelKey to "gemini-flash-latest",
        )

        val once = migrate(old)
        val twice = migrate(once.settings, once.credentials)

        assertEquals(once.settings.asMap(), twice.settings.asMap())
        assertEquals(once.credentials.asMap(), twice.credentials.asMap())
        assertEquals("gemini-key", settingsFromPreferences(twice.settings, twice.credentials).apiKey)
        assertEquals("gemini-flash-latest", settingsFromPreferences(twice.settings, twice.credentials).model)
    }

    @Test
    fun `invalid legacy provider safely migrates to the default provider`() {
        val old = preferencesOf(
            providerKey to "REMOVED_PROVIDER",
            legacyApiKey to "old-key",
            legacyModelKey to "old-model",
        )

        val migrated = migrate(old)
        val settings = settingsFromPreferences(migrated.settings, migrated.credentials)

        assertEquals(LlmProvider.ANTHROPIC, settings.provider)
        assertEquals("old-key", settings.apiKey)
        assertEquals("old-model", settings.model)
        assertEquals(
            "",
            settingsFromPreferences(
                withProvider(migrated.settings, LlmProvider.OPENROUTER),
                migrated.credentials,
            ).apiKey,
        )
    }

    @Test
    fun `legacy shared key is never forwarded to a custom endpoint`() {
        val old = preferencesOf(
            providerKey to LlmProvider.CUSTOM.name,
            legacyApiKey to "possibly-another-provider-key",
            legacyModelKey to "custom-model",
        )

        val migrated = migrate(old)
        val settings = settingsFromPreferences(migrated.settings, migrated.credentials)

        assertEquals(LlmProvider.CUSTOM, settings.provider)
        assertEquals("", settings.apiKey)
        assertEquals("custom-model", settings.model)
        assertFalse(legacyApiKey in migrated.settings)
    }

    @Test
    fun `blank provider models resolve to their own defaults`() {
        val expected = mapOf(
            LlmProvider.ANTHROPIC to "claude-sonnet-5",
            LlmProvider.OPENAI to "gpt-4o-mini",
            LlmProvider.GEMINI to "gemini-flash-latest",
            LlmProvider.OPENROUTER to "anthropic/claude-sonnet-4.5",
            LlmProvider.CUSTOM to "",
        )

        expected.forEach { (provider, model) ->
            assertEquals(model, AppSettings(provider = provider).effectiveModel())
        }
    }
}
