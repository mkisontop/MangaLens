package app.mangalens.translate

import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared HTTP plumbing for the text and vision LLM engines: one client with
 * upload-friendly timeouts, the Anthropic Messages shape, the OpenAI-compatible
 * chat shape (OpenAI, Gemini, OpenRouter, custom), and tolerant JSON digging.
 */
internal object LlmHttp {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    fun providerLabel(settings: AppSettings): String = when (settings.provider) {
        LlmProvider.ANTHROPIC -> "Claude"
        LlmProvider.OPENAI -> "OpenAI"
        LlmProvider.GEMINI -> "Gemini"
        LlmProvider.OPENROUTER -> "OpenRouter"
        LlmProvider.CUSTOM -> "Custom AI"
    }

    fun requireConfig(settings: AppSettings) {
        if (settings.provider != LlmProvider.CUSTOM && settings.apiKey.isBlank()) {
            throw RuntimeException("No API key set for " + providerLabel(settings))
        }
        if (settings.provider == LlmProvider.CUSTOM && settings.customUrl.isBlank()) {
            throw RuntimeException("No endpoint URL set")
        }
    }

    /**
     * Sends one user turn and returns the assistant text.
     * [anthropicContent] is a Messages-API content-block array; [openAiContent]
     * is either a plain String or a chat-completions content array — the two
     * wire formats for the same payload.
     */
    fun complete(
        settings: AppSettings,
        system: String,
        anthropicContent: JSONArray,
        openAiContent: Any,
        maxTokens: Int,
    ): String = when (settings.provider) {
        LlmProvider.ANTHROPIC -> anthropic(settings, system, anthropicContent, maxTokens)
        else -> openAiCompatible(settings, system, openAiContent, maxTokens)
    }

    private fun anthropic(settings: AppSettings, system: String, content: JSONArray, maxTokens: Int): String {
        // No temperature: current Claude models reject non-default sampling params.
        val body = JSONObject()
            .put("model", settings.effectiveModel())
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val request = Request.Builder()
            .url(settings.endpoint())
            .header("x-api-key", settings.apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw RuntimeException(providerLabel(settings) + " HTTP " + resp.code + ": " + text.take(200))
            }
            val blocks = JSONObject(text).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until blocks.length()) {
                val block = blocks.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            return sb.toString()
        }
    }

    private fun openAiCompatible(settings: AppSettings, system: String, content: Any, maxTokens: Int): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject()
            .put("model", settings.effectiveModel())
            .put("temperature", 0.3)
            .put("max_tokens", maxTokens)
            .put("messages", messages)
        val builder = Request.Builder()
            .url(settings.endpoint())
            .post(body.toString().toRequestBody(JSON))
        if (settings.apiKey.isNotBlank()) builder.header("Authorization", "Bearer " + settings.apiKey)
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw RuntimeException(providerLabel(settings) + " HTTP " + resp.code + ": " + text.take(200))
            }
            return JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content", "")
        }
    }

    /** Digs the response object out of prose/markdown-fenced replies. */
    fun extractJsonObject(raw: String): JSONObject {
        val cleaned = raw.replace("```json", "").replace("```", "").trim()
        val objStart = cleaned.indexOf('{')
        val objEnd = cleaned.lastIndexOf('}')
        if (objStart >= 0 && objEnd > objStart) {
            runCatching { return JSONObject(cleaned.substring(objStart, objEnd + 1)) }
        }
        // Bare-array reply: wrap so callers always see the object shape.
        val arrStart = cleaned.indexOf('[')
        val arrEnd = cleaned.lastIndexOf(']')
        if (arrStart >= 0 && arrEnd > arrStart) {
            runCatching {
                return JSONObject().put("bubbles", JSONArray(cleaned.substring(arrStart, arrEnd + 1)))
            }
        }
        throw RuntimeException("no JSON in LLM reply")
    }
}
