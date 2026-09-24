package app.mangalens.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

enum class LlmProvider { ANTHROPIC, OPENAI, GEMINI, OPENROUTER, CUSTOM }
enum class SourceLang { AUTO, KO, JA, ZH }
enum class CaptureMode { AUTO, MANUAL }

/**
 * How the AI reads the page. AUTO sends the page image for scripts that break
 * on-device OCR (vertical Japanese/Chinese) and cheap text-only requests for
 * everything else; ALWAYS forces vision; OFF keeps every request text-only.
 */
enum class AiVisionMode { AUTO, ALWAYS, OFF }

/**
 * How long the AI may think about a page before answering. Every current
 * model reasons before it writes, and the reasoning is where a stronger
 * model earns its keep — but it is also the wait before the first balloon
 * streams in. FAST asks for the least thinking the provider allows,
 * BALANCED for a little on the page image and the least on text, THOROUGH
 * for the provider's full depth. Providers that take no such control are
 * unaffected.
 */
enum class AiReasoning { FAST, BALANCED, THOROUGH }

/**
 * Everything a pass needs to know. Translation is the AI's alone — there is
 * no machine or on-device engine to pick — so what matters is which
 * provider answers, with which key and model.
 */
data class AppSettings(
    /**
     * Gemini unless the reader picks another: it is the recommended
     * provider, the fastest to answer a page, and has a free tier, so a new
     * reader can start without paying anyone.
     */
    val provider: LlmProvider = LlmProvider.GEMINI,
    val apiKey: String = "",
    val model: String = "",
    val customUrl: String = "",
    val sourceLang: SourceLang = SourceLang.AUTO,
    val mode: CaptureMode = CaptureMode.AUTO,
    val aiVision: AiVisionMode = AiVisionMode.AUTO,
    val aiReasoning: AiReasoning = AiReasoning.BALANCED,
    val dataSaver: Boolean = false,
    /**
     * Lets an image model redraw the art under lettering that sits on the
     * art itself — the scanlation redrawer's job, where a local
     * reconstruction can only smooth it over. Off by default: cleaning is
     * done on the device the way a scanlation cleaner works, and the redraw
     * costs an image request and is refused on explicit art. Gemini only.
     */
    val aiCleanup: Boolean = false,
    /**
     * Reports what each stage of a pass actually found, and outlines the
     * balloons detected in the page. When a balloon comes back untranslated the
     * cause is at OCR, at balloon detection, or at the model, and the fixes are
     * unrelated — without this there is no way to tell which from the screen.
     */
    val diagnostics: Boolean = false,
    val textScale: Float = 1.0f,
    val bgOpacity: Float = 1.0f,
    val stabilityMs: Int = 350,
    val ignoreTopPct: Float = 0.03f,
    val ignoreBottomPct: Float = 0.02f,
) {
    fun effectiveModel(): String = if (model.isNotBlank()) model else when (provider) {
        LlmProvider.ANTHROPIC -> "claude-sonnet-5"
        LlmProvider.OPENAI -> "gpt-4o-mini"
        LlmProvider.GEMINI -> "gemini-flash-latest"
        LlmProvider.OPENROUTER -> "anthropic/claude-sonnet-4.5"
        LlmProvider.CUSTOM -> ""
    }

    fun endpoint(): String = when (provider) {
        LlmProvider.ANTHROPIC -> "https://api.anthropic.com/v1/messages"
        LlmProvider.OPENAI -> "https://api.openai.com/v1/chat/completions"
        LlmProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        LlmProvider.OPENROUTER -> "https://openrouter.ai/api/v1/chat/completions"
        LlmProvider.CUSTOM -> customUrl
    }
}

/**
 * Preference names are deliberately explicit. They are persisted schema, so
 * deriving them with locale-sensitive casing would make a key written in one
 * locale unreadable in another.
 */
private object Keys {
    // "engine" once chose between the free Google, AI and on-device
    // engines. Stores written by those builds still hold it; it is never
    // read, and the name must not be reused for anything else.
    val PROVIDER = stringPreferencesKey("provider")
    val LEGACY_API_KEY = stringPreferencesKey("api_key")
    val LEGACY_MODEL = stringPreferencesKey("model")
    val CUSTOM_URL = stringPreferencesKey("custom_url")
    val SOURCE_LANG = stringPreferencesKey("source_lang")
    val MODE = stringPreferencesKey("mode")
    val AI_VISION = stringPreferencesKey("ai_vision")
    val AI_REASONING = stringPreferencesKey("ai_reasoning")
    val DATA_SAVER = booleanPreferencesKey("data_saver")
    val AI_CLEANUP = booleanPreferencesKey("ai_cleanup")
    val DIAGNOSTICS = booleanPreferencesKey("diagnostics")
    val TEXT_SCALE = floatPreferencesKey("text_scale")
    val BG_OPACITY = floatPreferencesKey("bg_opacity")
    val STABILITY_MS = intPreferencesKey("stability_ms")
    val IGNORE_TOP = floatPreferencesKey("ignore_top")
    val IGNORE_BOTTOM = floatPreferencesKey("ignore_bottom")

    private val API_KEY_ANTHROPIC = stringPreferencesKey("api_key_anthropic")
    private val API_KEY_OPENAI = stringPreferencesKey("api_key_openai")
    private val API_KEY_GEMINI = stringPreferencesKey("api_key_gemini")
    private val API_KEY_OPENROUTER = stringPreferencesKey("api_key_openrouter")
    private val API_KEY_CUSTOM = stringPreferencesKey("api_key_custom")

    private val MODEL_ANTHROPIC = stringPreferencesKey("model_anthropic")
    private val MODEL_OPENAI = stringPreferencesKey("model_openai")
    private val MODEL_GEMINI = stringPreferencesKey("model_gemini")
    private val MODEL_OPENROUTER = stringPreferencesKey("model_openrouter")
    private val MODEL_CUSTOM = stringPreferencesKey("model_custom")

    fun apiKey(provider: LlmProvider): Preferences.Key<String> = when (provider) {
        LlmProvider.ANTHROPIC -> API_KEY_ANTHROPIC
        LlmProvider.OPENAI -> API_KEY_OPENAI
        LlmProvider.GEMINI -> API_KEY_GEMINI
        LlmProvider.OPENROUTER -> API_KEY_OPENROUTER
        LlmProvider.CUSTOM -> API_KEY_CUSTOM
    }

    fun model(provider: LlmProvider): Preferences.Key<String> = when (provider) {
        LlmProvider.ANTHROPIC -> MODEL_ANTHROPIC
        LlmProvider.OPENAI -> MODEL_OPENAI
        LlmProvider.GEMINI -> MODEL_GEMINI
        LlmProvider.OPENROUTER -> MODEL_OPENROUTER
        LlmProvider.CUSTOM -> MODEL_CUSTOM
    }
}

private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
    name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

/**
 * The provider builds from before Gemini became the default used when none
 * was saved. They never wrote that choice down, so what they stored with no
 * provider beside it — the old shared key and model, a key typed into the
 * Anthropic field — was meant for this one.
 */
private val LEGACY_DEFAULT_PROVIDER = LlmProvider.ANTHROPIC

/**
 * The provider a store that never saved one is read with. A new install
 * gets the default; one that holds an Anthropic key or model and no
 * provider was set up under the old default and keeps it, so changing the
 * default never strands a key that was working.
 */
private fun unsavedProvider(p: Preferences, credentials: Preferences): LlmProvider {
    val legacy = LEGACY_DEFAULT_PROVIDER
    val setUp = !credentials[Keys.apiKey(legacy)].isNullOrBlank() || !p[Keys.model(legacy)].isNullOrBlank()
    return if (setUp) legacy else AppSettings().provider
}

/**
 * Moves the old shared model to the provider that was selected when this
 * version first opens the store. A legacy value must never remain as a read
 * fallback: after a provider switch that would expose it again.
 *
 * This function is pure and idempotent because DataStore may retry migrations.
 */
internal fun migrateLegacyModelSettings(current: Preferences): Preferences {
    val legacy = current[Keys.LEGACY_MODEL] ?: return current
    val provider = enumOr(current[Keys.PROVIDER], LEGACY_DEFAULT_PROVIDER)
    return current.toMutablePreferences().apply {
        val scoped = Keys.model(provider)
        if (this[scoped] == null) this[scoped] = legacy
        remove(Keys.LEGACY_MODEL)
    }.toPreferences()
}

/** Copies the old shared key into the selected provider's no-backup credential slot. */
internal fun migrateLegacyApiKey(settings: Preferences, credentials: Preferences): Preferences {
    val legacy = settings[Keys.LEGACY_API_KEY] ?: return credentials
    val provider = enumOr(settings[Keys.PROVIDER], LEGACY_DEFAULT_PROVIDER)
    // A custom URL may be controlled by anyone, and the old shared key's true
    // provider cannot be proven. Re-entry is safer than forwarding it there.
    if (provider == LlmProvider.CUSTOM) return credentials
    return credentials.toMutablePreferences().apply {
        val scoped = Keys.apiKey(provider)
        if (this[scoped] == null) this[scoped] = legacy
    }.toPreferences()
}

private object LegacyModelSettingsMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[Keys.LEGACY_MODEL] != null

    override suspend fun migrate(currentData: Preferences): Preferences =
        migrateLegacyModelSettings(currentData)

    override suspend fun cleanUp() = Unit
}

private val Context.settingsStore by preferencesDataStore(
    name = "mangalens_settings",
    produceMigrations = { listOf(LegacyModelSettingsMigration) },
)

private class LegacyApiKeyMigration(private val context: Context) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        context.settingsStore.data.first()[Keys.LEGACY_API_KEY] != null

    override suspend fun migrate(currentData: Preferences): Preferences =
        migrateLegacyApiKey(context.settingsStore.data.first(), currentData)

    override suspend fun cleanUp() {
        context.settingsStore.edit { it.remove(Keys.LEGACY_API_KEY) }
    }
}

private object CredentialsStore {
    @Volatile
    private var instance: DataStore<Preferences>? = null

    fun get(context: Context): DataStore<Preferences> = instance ?: synchronized(this) {
        instance ?: run {
            val appContext = context.applicationContext
            PreferenceDataStoreFactory.create(
                migrations = listOf(LegacyApiKeyMigration(appContext)),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = {
                    appContext.noBackupFilesDir.resolve("mangalens_credentials.preferences_pb")
                },
            ).also { instance = it }
        }
    }
}

private val Context.credentialsStore: DataStore<Preferences>
    get() = CredentialsStore.get(this)

/** Decodes only the selected provider's key and model into the active snapshot. */
internal fun settingsFromPreferences(p: Preferences, credentials: Preferences = p): AppSettings {
    val d = AppSettings()
    val provider = enumOr(p[Keys.PROVIDER], unsavedProvider(p, credentials))
    return AppSettings(
        provider = provider,
        apiKey = credentials[Keys.apiKey(provider)] ?: d.apiKey,
        model = p[Keys.model(provider)] ?: d.model,
        customUrl = p[Keys.CUSTOM_URL] ?: d.customUrl,
        sourceLang = enumOr(p[Keys.SOURCE_LANG], d.sourceLang),
        mode = enumOr(p[Keys.MODE], d.mode),
        aiVision = enumOr(p[Keys.AI_VISION], d.aiVision),
        aiReasoning = enumOr(p[Keys.AI_REASONING], d.aiReasoning),
        dataSaver = p[Keys.DATA_SAVER] ?: d.dataSaver,
        aiCleanup = p[Keys.AI_CLEANUP] ?: d.aiCleanup,
        diagnostics = p[Keys.DIAGNOSTICS] ?: d.diagnostics,
        textScale = p[Keys.TEXT_SCALE] ?: d.textScale,
        bgOpacity = p[Keys.BG_OPACITY] ?: d.bgOpacity,
        stabilityMs = p[Keys.STABILITY_MS] ?: d.stabilityMs,
        ignoreTopPct = p[Keys.IGNORE_TOP] ?: d.ignoreTopPct,
        ignoreBottomPct = p[Keys.IGNORE_BOTTOM] ?: d.ignoreBottomPct,
    )
}

class SettingsRepository(private val context: Context) {

    val flow: Flow<AppSettings> = combine(
        context.settingsStore.data,
        context.credentialsStore.data,
        ::settingsFromPreferences,
    )

    suspend fun current(): AppSettings = flow.first()

    suspend fun setProvider(v: LlmProvider) = context.settingsStore.edit { it[Keys.PROVIDER] = v.name }
    suspend fun setApiKey(provider: LlmProvider, v: String) =
        context.credentialsStore.edit { it[Keys.apiKey(provider)] = v }
    suspend fun setModel(provider: LlmProvider, v: String) =
        context.settingsStore.edit { it[Keys.model(provider)] = v }
    suspend fun setCustomUrl(v: String) = context.settingsStore.edit { it[Keys.CUSTOM_URL] = v }
    suspend fun setSourceLang(v: SourceLang) = context.settingsStore.edit { it[Keys.SOURCE_LANG] = v.name }
    suspend fun setMode(v: CaptureMode) = context.settingsStore.edit { it[Keys.MODE] = v.name }
    suspend fun setAiVision(v: AiVisionMode) = context.settingsStore.edit { it[Keys.AI_VISION] = v.name }
    suspend fun setAiReasoning(v: AiReasoning) = context.settingsStore.edit { it[Keys.AI_REASONING] = v.name }
    suspend fun setDataSaver(v: Boolean) = context.settingsStore.edit { it[Keys.DATA_SAVER] = v }
    suspend fun setAiCleanup(v: Boolean) = context.settingsStore.edit { it[Keys.AI_CLEANUP] = v }
    suspend fun setDiagnostics(v: Boolean) = context.settingsStore.edit { it[Keys.DIAGNOSTICS] = v }
    suspend fun setTextScale(v: Float) = context.settingsStore.edit { it[Keys.TEXT_SCALE] = v }
    suspend fun setBgOpacity(v: Float) = context.settingsStore.edit { it[Keys.BG_OPACITY] = v }
    suspend fun setStabilityMs(v: Int) = context.settingsStore.edit { it[Keys.STABILITY_MS] = v }
    suspend fun setIgnoreTopPct(v: Float) = context.settingsStore.edit { it[Keys.IGNORE_TOP] = v }
    suspend fun setIgnoreBottomPct(v: Float) = context.settingsStore.edit { it[Keys.IGNORE_BOTTOM] = v }
}
