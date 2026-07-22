package app.mangalens.translate

import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SourceLang
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The "master translator" engine: sends the whole page of bubbles in one request
 * to an LLM (Claude by default), together with a rolling window of recent bubbles
 * so pronouns, tone and running jokes stay consistent across pages.
 */
class LlmEngine(private val settings: AppSettings) : TranslationEngine {

    override val label: String = when (settings.provider) {
        LlmProvider.ANTHROPIC -> "Claude"
        LlmProvider.OPENAI -> "OpenAI"
        LlmProvider.GEMINI -> "Gemini"
        LlmProvider.OPENROUTER -> "OpenRouter"
        LlmProvider.CUSTOM -> "Custom AI"
    }

    override val cacheNamespace: String get() = label + ":" + settings.effectiveModel()

    override suspend fun translate(items: List<String>, lang: SourceLang): List<String> =
        withContext(Dispatchers.IO) {
            if (settings.provider != LlmProvider.CUSTOM && settings.apiKey.isBlank()) {
                throw RuntimeException("No API key set for " + label)
            }
            if (settings.provider == LlmProvider.CUSTOM && settings.customUrl.isBlank()) {
                throw RuntimeException("No endpoint URL set")
            }

            val bubbles = JSONArray()
            items.forEachIndexed { i, t -> bubbles.put(JSONObject().put("id", i).put("text", t)) }
            val user = JSONObject()
                .put("source_language", lang.name.lowercase())
                .put("recent_bubbles_for_context", JSONArray(snapshotContext()))
                .put("bubbles", bubbles)
                .toString()

            val raw = when (settings.provider) {
                LlmProvider.ANTHROPIC -> callAnthropic(user)
                else -> callOpenAiCompatible(user)
            }

            val arr = extractJsonArray(raw)
            val out = MutableList(items.size) { "" }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optInt("id", -1)
                val en = o.optString("en", "")
                if (id in items.indices && en.isNotBlank()) out[id] = en
            }
            items.forEachIndexed { i, src -> if (out[i].isNotBlank()) remember(src, out[i]) }
            out
        }

    private fun callAnthropic(user: String): String {
        val body = JSONObject()
            .put("model", settings.effectiveModel())
            .put("max_tokens", 2000)
            .put("temperature", 0.25)
            .put("system", SYSTEM_PROMPT)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        val request = Request.Builder()
            .url(settings.endpoint())
            .header("x-api-key", settings.apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException(label + " HTTP " + resp.code + ": " + text.take(200))
            val content = JSONObject(text).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            return sb.toString()
        }
    }

    private fun callOpenAiCompatible(user: String): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", settings.effectiveModel())
            .put("temperature", 0.25)
            .put("max_tokens", 2000)
            .put("messages", messages)
        val builder = Request.Builder()
            .url(settings.endpoint())
            .post(body.toString().toRequestBody(JSON))
        if (settings.apiKey.isNotBlank()) builder.header("Authorization", "Bearer " + settings.apiKey)
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException(label + " HTTP " + resp.code + ": " + text.take(200))
            return JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content", "")
        }
    }

    private fun extractJsonArray(raw: String): JSONArray {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) throw RuntimeException("no JSON array in LLM reply")
        return JSONArray(raw.substring(start, end + 1))
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        // Rolling context shared across requests so dialogue stays coherent.
        private val recent = ArrayDeque<String>()

        @Synchronized
        private fun snapshotContext(): List<String> = recent.toList()

        @Synchronized
        private fun remember(src: String, en: String) {
            recent.addLast(src + " => " + en)
            while (recent.size > 24) recent.removeFirst()
        }

        @Synchronized
        fun resetContext() = recent.clear()

        private val SYSTEM_PROMPT = """
You are an elite manga/manhwa/manhua localization translator. You receive speech bubbles OCR-scanned from one comic page, listed in reading order, sometimes with recent earlier bubbles as context. Translate every bubble into natural, fluent English that reads like an official licensed release.

Rules:
- Write the way real people speak. Use contractions and match each line's emotion and register (casual, formal, shouting, whispering, sarcastic).
- Keep character names romanized. Keep honorifics that carry nuance (oppa, hyung, noona, unnie, -nim, -ssi, senpai, -san, -sama, shifu, gege, jiejie).
- Translate sound effects into punchy English onomatopoeia (THUD, WHOOSH, BA-DUMP, KRAK).
- OCR text may contain recognition errors. Infer the intended words from context instead of translating garbage literally.
- Keep lines as tight as real typeset dialogue. No translator notes, no explanations, no romanization of the source text.
- If a bubble is unreadable noise, return your best guess or an empty string.

Respond with ONLY a JSON array, one object per input bubble: [{"id": <number>, "en": "<English translation>"}]. No markdown fences, no commentary.
""".trim()
    }
}
