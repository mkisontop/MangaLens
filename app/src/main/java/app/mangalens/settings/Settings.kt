package app.mangalens.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class EngineKind { GOOGLE, LLM, MLKIT }
enum class LlmProvider { ANTHROPIC, OPENAI, GEMINI, OPENROUTER, CUSTOM }
enum class SourceLang { AUTO, KO, JA, ZH }
enum class CaptureMode { AUTO, MANUAL }

data class AppSettings(
    val engine: EngineKind = EngineKind.GOOGLE,
    val provider: LlmProvider = LlmProvider.ANTHROPIC,
    val apiKey: String = "",
    val model: String = "",
    val customUrl: String = "",
    val sourceLang: SourceLang = SourceLang.AUTO,
    val mode: CaptureMode = CaptureMode.AUTO,
    val textScale: Float = 1.0f,
    val bgOpacity: Float = 1.0f,
    val stabilityMs: Int = 350,
    val ignoreTopPct: Float = 0.03f,
    val ignoreBottomPct: Float = 0.02f,
) {
    fun effectiveModel(): String = if (model.isNotBlank()) model else when (provider) {
        LlmProvider.ANTHROPIC -> "claude-sonnet-5"
        LlmProvider.OPENAI -> "gpt-4o-mini"
        LlmProvider.GEMINI -> "gemini-2.0-flash"
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

private val Context.settingsStore by preferencesDataStore(name = "mangalens_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val ENGINE = stringPreferencesKey("engine")
        val PROVIDER = stringPreferencesKey("provider")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
        val CUSTOM_URL = stringPreferencesKey("custom_url")
        val SOURCE_LANG = stringPreferencesKey("source_lang")
        val MODE = stringPreferencesKey("mode")
        val TEXT_SCALE = floatPreferencesKey("text_scale")
        val BG_OPACITY = floatPreferencesKey("bg_opacity")
        val STABILITY_MS = intPreferencesKey("stability_ms")
        val IGNORE_TOP = floatPreferencesKey("ignore_top")
        val IGNORE_BOTTOM = floatPreferencesKey("ignore_bottom")
    }

    val flow: Flow<AppSettings> = context.settingsStore.data.map { p -> fromPrefs(p) }

    suspend fun current(): AppSettings = flow.first()

    private fun fromPrefs(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            engine = enumOr(p[Keys.ENGINE], d.engine),
            provider = enumOr(p[Keys.PROVIDER], d.provider),
            apiKey = p[Keys.API_KEY] ?: d.apiKey,
            model = p[Keys.MODEL] ?: d.model,
            customUrl = p[Keys.CUSTOM_URL] ?: d.customUrl,
            sourceLang = enumOr(p[Keys.SOURCE_LANG], d.sourceLang),
            mode = enumOr(p[Keys.MODE], d.mode),
            textScale = p[Keys.TEXT_SCALE] ?: d.textScale,
            bgOpacity = p[Keys.BG_OPACITY] ?: d.bgOpacity,
            stabilityMs = p[Keys.STABILITY_MS] ?: d.stabilityMs,
            ignoreTopPct = p[Keys.IGNORE_TOP] ?: d.ignoreTopPct,
            ignoreBottomPct = p[Keys.IGNORE_BOTTOM] ?: d.ignoreBottomPct,
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    suspend fun setEngine(v: EngineKind) = context.settingsStore.edit { it[Keys.ENGINE] = v.name }
    suspend fun setProvider(v: LlmProvider) = context.settingsStore.edit { it[Keys.PROVIDER] = v.name }
    suspend fun setApiKey(v: String) = context.settingsStore.edit { it[Keys.API_KEY] = v }
    suspend fun setModel(v: String) = context.settingsStore.edit { it[Keys.MODEL] = v }
    suspend fun setCustomUrl(v: String) = context.settingsStore.edit { it[Keys.CUSTOM_URL] = v }
    suspend fun setSourceLang(v: SourceLang) = context.settingsStore.edit { it[Keys.SOURCE_LANG] = v.name }
    suspend fun setMode(v: CaptureMode) = context.settingsStore.edit { it[Keys.MODE] = v.name }
    suspend fun setTextScale(v: Float) = context.settingsStore.edit { it[Keys.TEXT_SCALE] = v }
    suspend fun setBgOpacity(v: Float) = context.settingsStore.edit { it[Keys.BG_OPACITY] = v }
    suspend fun setStabilityMs(v: Int) = context.settingsStore.edit { it[Keys.STABILITY_MS] = v }
    suspend fun setIgnoreTopPct(v: Float) = context.settingsStore.edit { it[Keys.IGNORE_TOP] = v }
    suspend fun setIgnoreBottomPct(v: Float) = context.settingsStore.edit { it[Keys.IGNORE_BOTTOM] = v }
}
