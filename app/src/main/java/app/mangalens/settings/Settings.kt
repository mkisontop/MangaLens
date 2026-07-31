package app.mangalens.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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

/**
 * How AI Pro reads the page. AUTO sends the page image for scripts that break
 * on-device OCR (vertical Japanese/Chinese) and cheap text-only requests for
 * everything else; ALWAYS forces vision; OFF keeps every request text-only.
 */
enum class AiVisionMode { AUTO, ALWAYS, OFF }

data class AppSettings(
    val engine: EngineKind = EngineKind.GOOGLE,
    val provider: LlmProvider = LlmProvider.ANTHROPIC,
    val apiKey: String = "",
    val model: String = "",
    val customUrl: String = "",
    val sourceLang: SourceLang = SourceLang.AUTO,
    val mode: CaptureMode = CaptureMode.AUTO,
    val aiVision: AiVisionMode = AiVisionMode.AUTO,
    val dataSaver: Boolean = false,
    /**
     * Reports what each stage of a pass actually found, and outlines the
     * balloons detected in the page. When a balloon comes back untranslated the
     * cause is at OCR, at balloon detection, or at the model, and the fixes are
     * unrelated — without this there is no way to tell which from the screen.
     */
    val diagnostics: Boolean = false,
    val textScale: Float = 1.0f,
    val bgOpacity: Float = 1.0f,
    /**
     * Cards ride the strip: scroll tracking keeps translations glued to
     * their balloons, and a balloon scrolled back into view still wears its
     * card — the chapter reads as if it was translated from the start.
     * Auto-live mode only; falls back to the classic clear-and-retranslate
     * loop whenever tracking cannot lock (tap-to-turn readers, page swaps).
     */
    val stickyScroll: Boolean = true,
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
        val AI_VISION = stringPreferencesKey("ai_vision")
        val DATA_SAVER = booleanPreferencesKey("data_saver")
        val DIAGNOSTICS = booleanPreferencesKey("diagnostics")
        val TEXT_SCALE = floatPreferencesKey("text_scale")
        val BG_OPACITY = floatPreferencesKey("bg_opacity")
        val STICKY_SCROLL = booleanPreferencesKey("sticky_scroll")
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
            aiVision = enumOr(p[Keys.AI_VISION], d.aiVision),
            dataSaver = p[Keys.DATA_SAVER] ?: d.dataSaver,
            diagnostics = p[Keys.DIAGNOSTICS] ?: d.diagnostics,
            textScale = p[Keys.TEXT_SCALE] ?: d.textScale,
            bgOpacity = p[Keys.BG_OPACITY] ?: d.bgOpacity,
            stickyScroll = p[Keys.STICKY_SCROLL] ?: d.stickyScroll,
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
    suspend fun setAiVision(v: AiVisionMode) = context.settingsStore.edit { it[Keys.AI_VISION] = v.name }
    suspend fun setDataSaver(v: Boolean) = context.settingsStore.edit { it[Keys.DATA_SAVER] = v }
    suspend fun setDiagnostics(v: Boolean) = context.settingsStore.edit { it[Keys.DIAGNOSTICS] = v }
    suspend fun setTextScale(v: Float) = context.settingsStore.edit { it[Keys.TEXT_SCALE] = v }
    suspend fun setBgOpacity(v: Float) = context.settingsStore.edit { it[Keys.BG_OPACITY] = v }
    suspend fun setStickyScroll(v: Boolean) = context.settingsStore.edit { it[Keys.STICKY_SCROLL] = v }
    suspend fun setStabilityMs(v: Int) = context.settingsStore.edit { it[Keys.STABILITY_MS] = v }
    suspend fun setIgnoreTopPct(v: Float) = context.settingsStore.edit { it[Keys.IGNORE_TOP] = v }
    suspend fun setIgnoreBottomPct(v: Float) = context.settingsStore.edit { it[Keys.IGNORE_BOTTOM] = v }
}
