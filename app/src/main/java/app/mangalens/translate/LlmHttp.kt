package app.mangalens.translate

import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared HTTP plumbing for the text and vision LLM engines: one client with
 * upload-friendly timeouts, the Anthropic Messages shape, the OpenAI-compatible
 * chat shape (OpenAI, Gemini, OpenRouter, custom), streaming for both, and
 * tolerant JSON digging.
 *
 * Every request is laid out stable-first: the system prompt, then the
 * series memory (glossary and cast, which change only when a new name is
 * met), then the page — its image and its regions — last. Providers that
 * cache a request prefix get to reuse everything up to the page on every
 * call; on the Anthropic API the two stable blocks carry explicit cache
 * breakpoints, so a page costs its own tokens and little else.
 */
internal object LlmHttp {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /**
     * Claude models that take an effort setting. Translation is not a
     * problem to be reasoned about at length — the page is the context, and
     * the draft is already on screen — so the polish asks for less thinking
     * than the default and comes back sooner. Older models reject the
     * parameter outright, and get none.
     */
    private val EFFORT_MODELS = Regex("^claude-(opus-(5|4-[5-9])|sonnet-(5|4-[6-9])|fable|mythos)")

    fun providerLabel(settings: AppSettings): String = when (settings.provider) {
        LlmProvider.ANTHROPIC -> "Claude"
        LlmProvider.OPENAI -> "OpenAI"
        LlmProvider.GEMINI -> "Gemini"
        LlmProvider.OPENROUTER -> "OpenRouter"
        LlmProvider.CUSTOM -> "Custom AI"
    }

    /** [wanted] where the configured model takes an effort level, else null. */
    fun effortFor(settings: AppSettings, wanted: String): String? =
        if (settings.provider == LlmProvider.ANTHROPIC && EFFORT_MODELS.containsMatchIn(settings.effectiveModel())) {
            wanted
        } else {
            null
        }

    /**
     * Executes a call so coroutine cancellation aborts the HTTP request —
     * scrolling away kills in-flight translations instead of letting them
     * finish for nobody.
     */
    suspend fun await(call: Call): Response = suspendCancellableCoroutine { cont ->
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
        cont.invokeOnCancellation { runCatching { call.cancel() } }
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
     *
     * @param stable the part of the turn that rarely changes between pages.
     * @param imageJpegB64 the marked page image, or null for a text-only turn.
     * @param page the per-page payload, sent last.
     * @param effort an effort level for models that take one (see [effortFor]).
     * @param onDelta when given, the reply is streamed and every piece of
     *   text is handed over as it arrives; the complete text is still the
     *   return value. Cancelling the caller aborts the stream.
     */
    suspend fun complete(
        settings: AppSettings,
        system: String,
        stable: String,
        imageJpegB64: String?,
        page: String,
        maxTokens: Int,
        effort: String? = null,
        onDelta: (suspend (String) -> Unit)? = null,
    ): String {
        val anthropic = settings.provider == LlmProvider.ANTHROPIC
        val streaming = onDelta != null
        val body = if (anthropic) {
            anthropicBody(settings, system, stable, imageJpegB64, page, maxTokens, effort, streaming)
        } else {
            openAiBody(settings, system, stable, imageJpegB64, page, maxTokens, streaming)
        }
        val builder = Request.Builder()
            .url(settings.endpoint())
            .post(body.toString().toRequestBody(JSON))
        if (anthropic) {
            builder.header("x-api-key", settings.apiKey).header("anthropic-version", "2023-06-01")
        } else if (settings.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer " + settings.apiKey)
        }
        val call = client.newCall(builder.build())
        await(call).use { resp ->
            if (!resp.isSuccessful) {
                val text = resp.body?.string() ?: ""
                throw RuntimeException(providerLabel(settings) + " HTTP " + resp.code + ": " + text.take(200))
            }
            val source = resp.body?.source() ?: return ""
            // A server that streams says so; one that ignored the request
            // for a stream answers with a plain document, which is handled
            // as such rather than parsed as events.
            val type = resp.header("Content-Type") ?: ""
            val looksLikeEvents = type.contains("text/event-stream", ignoreCase = true) ||
                source.peekStartsWith("event:") || source.peekStartsWith("data:")
            if (streaming && looksLikeEvents) {
                return readEvents(call, source, anthropic, onDelta!!)
            }
            val text = source.readUtf8()
            val out = if (anthropic) anthropicText(text) else openAiText(text)
            if (streaming) onDelta!!(out)
            return out
        }
    }

    private fun BufferedSource.peekStartsWith(prefix: String): Boolean =
        runCatching { request(prefix.length.toLong()) && peek().readUtf8(prefix.length.toLong()) == prefix }
            .getOrDefault(false)

    // ---- request shapes ----

    private fun anthropicBody(
        settings: AppSettings,
        system: String,
        stable: String,
        image: String?,
        page: String,
        maxTokens: Int,
        effort: String?,
        stream: Boolean,
    ): JSONObject {
        fun cached(text: String) = JSONObject()
            .put("type", "text")
            .put("text", text)
            .put("cache_control", JSONObject().put("type", "ephemeral"))

        val content = JSONArray().put(cached(stable))
        if (image != null) {
            content.put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject()
                        .put("type", "base64")
                        .put("media_type", "image/jpeg")
                        .put("data", image)
                )
            )
        }
        content.put(JSONObject().put("type", "text").put("text", page))
        // No temperature: current Claude models reject non-default sampling params.
        val body = JSONObject()
            .put("model", settings.effectiveModel())
            .put("max_tokens", maxTokens)
            .put("system", JSONArray().put(cached(system)))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        if (effort != null) body.put("output_config", JSONObject().put("effort", effort))
        if (stream) body.put("stream", true)
        return body
    }

    private fun openAiBody(
        settings: AppSettings,
        system: String,
        stable: String,
        image: String?,
        page: String,
        maxTokens: Int,
        stream: Boolean,
    ): JSONObject {
        // Text-only turns go as one plain string: every compatible server
        // accepts that, and some accept nothing else.
        val userContent: Any = if (image == null) {
            stable + "\n\n" + page
        } else {
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", stable))
                .put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$image")
                    )
                )
                .put(JSONObject().put("type", "text").put("text", page))
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", userContent))
        // Greedy decoding. Re-reading a page must not re-word it: the cache is
        // keyed on OCR text, and OCR varies slightly between two captures of
        // the same page, so a re-read often misses the cache and asks again.
        // With sampling on, that second answer differs from the first — the
        // same panel worded two ways depending on when you looked at it.
        val body = JSONObject()
            .put("model", settings.effectiveModel())
            .put("temperature", 0)
            .put("max_tokens", maxTokens)
            .put("messages", messages)
        if (stream) body.put("stream", true)
        return body
    }

    // ---- response shapes ----

    private fun anthropicText(json: String): String {
        val blocks = JSONObject(json).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until blocks.length()) {
            val block = blocks.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString()
    }

    private fun openAiText(json: String): String = JSONObject(json)
        .getJSONArray("choices")
        .getJSONObject(0)
        .getJSONObject("message")
        .optString("content", "")

    /**
     * Reads a server-sent event stream to its end, handing each text delta
     * to [onDelta] and returning the whole text. The read blocks the
     * thread between events; cancelling the coroutine cancels the call,
     * which fails the read and ends the stream.
     */
    private suspend fun readEvents(
        call: Call,
        source: BufferedSource,
        anthropic: Boolean,
        onDelta: suspend (String) -> Unit,
    ): String {
        val full = StringBuilder()
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) runCatching { call.cancel() }
        }
        try {
            val data = StringBuilder()
            suspend fun dispatch() {
                if (data.isEmpty()) return
                val payload = data.toString()
                data.setLength(0)
                if (payload == "[DONE]") return
                val o = runCatching { JSONObject(payload) }.getOrNull() ?: return
                val text = if (anthropic) anthropicDelta(o) else openAiDelta(o)
                if (text.isNotEmpty()) {
                    full.append(text)
                    onDelta(text)
                }
            }
            while (true) {
                val line = try {
                    source.readUtf8Line()
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } ?: break
                when {
                    line.isEmpty() -> dispatch()
                    line.startsWith("data:") -> data.append(line.substring(5).trim())
                    // event: and comment lines carry nothing the payload does not.
                }
            }
            dispatch()
        } finally {
            handle?.dispose()
        }
        return full.toString()
    }

    private fun anthropicDelta(o: JSONObject): String = when (o.optString("type")) {
        "content_block_delta" -> {
            val delta = o.optJSONObject("delta")
            if (delta != null && delta.optString("type") == "text_delta") delta.optString("text", "") else ""
        }
        "error" -> throw RuntimeException(o.optJSONObject("error")?.optString("message") ?: "stream error")
        else -> ""
    }

    private fun openAiDelta(o: JSONObject): String {
        val choice = o.optJSONArray("choices")?.optJSONObject(0) ?: return ""
        val delta = choice.optJSONObject("delta") ?: return ""
        return if (delta.isNull("content")) "" else delta.optString("content", "")
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
