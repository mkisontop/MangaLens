package app.mangalens.translate

import app.mangalens.settings.AiReasoning
import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
 * upload-friendly timeouts, the Anthropic Messages shape, Google's native
 * Gemini shape ([GeminiApi]), the OpenAI-compatible chat shape (OpenAI,
 * OpenRouter, custom), streaming for all three, and tolerant JSON digging.
 *
 * Every request is laid out stable-first: the system prompt, then the
 * series memory (glossary and cast, which change only when a new name is
 * met), then the page — its images and its regions — last. Providers that
 * cache a request prefix get to reuse everything up to the page on every
 * call; on the Anthropic API the two stable blocks carry explicit cache
 * breakpoints, so a page costs its own tokens and little else.
 *
 * Every current model reasons before it answers, and each provider takes
 * that instruction in its own dialect. The reader's [AiReasoning] choice is
 * translated per provider and per model here, so a stronger model is asked
 * to think as much as the reader wants and no more — the thinking is where
 * it earns its keep, and also the whole of the wait before the first
 * balloon streams in. Models that take no such control get none, and the
 * request stays exactly what it was.
 */
internal object LlmHttp {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /** Claude models that take an effort level; older ones reject the parameter. */
    private val CLAUDE_EFFORT = Regex("^claude-(opus-(5|4-[5-9])|sonnet-(5|4-[6-9])|fable|mythos)")

    /**
     * OpenAI reasoning models. They take a reasoning effort, they reject
     * `max_tokens` in favour of `max_completion_tokens`, and they reject
     * any temperature but the default — three ways a request written for
     * gpt-4o fails outright on them.
     */
    private val OPENAI_REASONING = Regex("^(gpt-5|o[1-9])")

    /** OpenRouter model ids whose upstream takes a reasoning effort. */
    private val OPENROUTER_REASONING = Regex("claude|gemini-(2\\.5|3)|gpt-5|/o[1-9]|grok|deepseek-r|qwen3|glm-4\\.[5-9]|kimi")

    /** OpenRouter upstreams that want the default temperature left alone. */
    private val OPENROUTER_DEFAULT_TEMPERATURE = Regex("gemini-3|gpt-5|/o[1-9]")

    fun providerLabel(settings: AppSettings): String = when (settings.provider) {
        LlmProvider.ANTHROPIC -> "Claude"
        LlmProvider.OPENAI -> "OpenAI"
        LlmProvider.GEMINI -> "Gemini"
        LlmProvider.OPENROUTER -> "OpenRouter"
        LlmProvider.CUSTOM -> "Custom AI"
    }

    /**
     * The thinking level to ask for: "low", "medium" or "high". Reading the
     * page image is where thinking pays — who is speaking, what a stylised
     * glyph says — so the balanced setting spends a little there and the
     * least on text, which is a translation with the context already in
     * hand.
     */
    fun effortLevel(settings: AppSettings, vision: Boolean): String = when (settings.aiReasoning) {
        AiReasoning.FAST -> "low"
        AiReasoning.BALANCED -> if (vision) "medium" else "low"
        AiReasoning.THOROUGH -> "high"
    }

    /**
     * Executes a call so coroutine cancellation aborts the HTTP request —
     * scrolling away kills in-flight translations instead of letting them
     * finish for nobody.
     *
     * A response can land in the same instant its caller is cancelled, and
     * is then never delivered; it is closed rather than left holding its
     * connection.
     */
    suspend fun await(call: Call): Response = suspendCancellableCoroutine { cont ->
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, undelivered, _ -> undelivered.close() }
            }
        })
        cont.invokeOnCancellation { runCatching { call.cancel() } }
    }

    /**
     * Runs a blocking read so that cancelling the caller cancels [call] at
     * once. The read blocks its thread between events, so nothing on that
     * thread can notice the cancellation; a hook on the caller cancels the
     * call instead, which fails the read and ends the stream. Without it a
     * cancelled request would hold its connection until the next event —
     * up to several seconds before the first token.
     *
     * The hook is in place before the read starts and runs on whichever
     * thread does the cancelling. A watcher that first had to be scheduled
     * would miss a cancel landing before its turn, and would never get a
     * turn while every thread it could run on sat blocked in a read; a
     * completion handler on the caller's job would wait for the read to
     * return first.
     *
     * A read that ends on its own leaves the call alone: cancelling a
     * finished HTTP/1.1 call closes its socket, and the next page would pay
     * for a fresh handshake.
     */
    internal suspend fun <T> abortOnCancel(call: Call, block: suspend () -> T): T = coroutineScope {
        val ended = AtomicBoolean(false)
        val hook = launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine<Unit> { cont ->
                cont.invokeOnCancellation { if (!ended.get()) runCatching { call.cancel() } }
            }
        }
        try {
            block()
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            ended.set(true)
            hook.cancel()
        }
    }

    /**
     * [key] as it can go in a header: trimmed, and without anything outside
     * printable ASCII. A key copied from a web page or a chat often brings
     * a zero-width or no-break space along, which no key contains and
     * OkHttp refuses in a header.
     */
    fun cleanKey(key: String): String = key.filter { it in ' '..'~' }.trim()

    /**
     * [builder] with [key], cleaned, in the header [name]. OkHttp quotes a
     * value it refuses in its error unless the header is one it knows to
     * be secret, and it does not know x-goog-api-key or x-api-key: a key it
     * refused would be shown on screen in full.
     */
    fun keyHeader(builder: Request.Builder, name: String, key: String): Request.Builder =
        withoutKeyInErrors { builder.header(name, cleanKey(key)) }

    /**
     * Runs [build], rethrowing a refused header without the value OkHttp
     * quoted — and without the original as its cause, since the cause's
     * message is the one that holds the key.
     */
    internal inline fun <T> withoutKeyInErrors(build: () -> T): T = try {
        build()
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("API key contains characters that are not allowed")
    }

    fun requireConfig(settings: AppSettings) {
        if (settings.provider != LlmProvider.CUSTOM && cleanKey(settings.apiKey).isEmpty()) {
            throw RuntimeException("No API key set for " + providerLabel(settings))
        }
        if (settings.provider == LlmProvider.CUSTOM && settings.customUrl.isBlank()) {
            throw RuntimeException("No endpoint URL set")
        }
    }

    /**
     * What the reader still has to set up before the AI can be asked, in
     * the words the status pill uses, or null when nothing is missing. The
     * checks are [requireConfig]'s, said as what to do rather than as what
     * went wrong: translation is the AI's alone, so without this there is
     * nothing to try, and no point spending a pass finding that out.
     */
    fun setupNeeded(settings: AppSettings): String? = when {
        settings.provider == LlmProvider.CUSTOM ->
            if (settings.customUrl.isBlank()) "Add your AI endpoint in MangaLens" else null
        cleanKey(settings.apiKey).isEmpty() -> "Add your " + providerLabel(settings) + " key in MangaLens"
        else -> null
    }

    /**
     * Sends one user turn and returns the assistant text.
     *
     * @param stable the part of the turn that rarely changes between pages.
     * @param images base64 JPEGs, the marked page first and any region
     *   close-ups after it; empty for a text-only turn.
     * @param page the per-page payload, sent last.
     * @param effort the thinking level, from [effortLevel].
     * @param vision whether this is the page-image path, which is allowed a
     *   longer answer.
     * @param onDelta when given, the reply is streamed and every piece of
     *   text is handed over as it arrives; the complete text is still the
     *   return value. Cancelling the caller aborts the stream.
     */
    suspend fun complete(
        settings: AppSettings,
        system: String,
        stable: String,
        images: List<String>,
        page: String,
        effort: String,
        vision: Boolean,
        onDelta: (suspend (String) -> Unit)? = null,
    ): String {
        if (settings.provider == LlmProvider.GEMINI) {
            // A model picked months ago may since have been retired; the
            // newest Flash answers instead, and once Google has said the
            // model is gone it is not asked again. One Google is turning
            // away as overloaded is stood in for (GeminiApi.relief).
            val chosen = settings.effectiveModel()
            val model = GeminiApi.available(if (chosen != GeminiApi.FALLBACK_MODEL && GeminiApi.isMissing(chosen)) GeminiApi.FALLBACK_MODEL else chosen)
            var shown = false
            val relay: (suspend (String) -> Unit)? = if (onDelta == null) {
                null
            } else {
                { text ->
                    shown = true
                    onDelta(text)
                }
            }
            return try {
                gemini(settings, model, system, stable, images, page, effort, vision, relay)
            } catch (e: GeminiModelMissing) {
                // Text already handed on cannot be taken back, so a model
                // that goes missing mid-reply is that reply's failure.
                if (model == GeminiApi.FALLBACK_MODEL || shown) throw e
                gemini(settings, GeminiApi.FALLBACK_MODEL, system, stable, images, page, effort, vision, onDelta)
            } catch (e: GeminiHttpException) {
                if (e.code != 503 || shown) throw e
                GeminiApi.strain(model)
                val relief = GeminiApi.relief(model) ?: throw e
                gemini(settings, relief, system, stable, images, page, effort, vision, onDelta)
            }
        }
        val anthropic = settings.provider == LlmProvider.ANTHROPIC
        val streaming = onDelta != null
        val body = if (anthropic) {
            anthropicBody(settings, system, stable, images, page, effort, vision, streaming)
        } else {
            openAiBody(settings, system, stable, images, page, effort, vision, streaming)
        }
        val builder = Request.Builder()
            .url(settings.endpoint())
            .post(body.toString().toRequestBody(JSON))
        if (anthropic) {
            keyHeader(builder, "x-api-key", settings.apiKey).header("anthropic-version", "2023-06-01")
        } else if (cleanKey(settings.apiKey).isNotEmpty()) {
            keyHeader(builder, "Authorization", "Bearer " + cleanKey(settings.apiKey))
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

    /** One request to Gemini's native API for [model], with a body built for that model. */
    private suspend fun gemini(
        settings: AppSettings,
        model: String,
        system: String,
        stable: String,
        images: List<String>,
        page: String,
        effort: String,
        vision: Boolean,
        onDelta: (suspend (String) -> Unit)?,
    ): String {
        val body = geminiBody(settings, system, stable, images, page, effort, vision, model)
        return if (onDelta != null) {
            GeminiApi.stream(settings.apiKey, model, body, onDelta = onDelta)
        } else {
            GeminiApi.text(GeminiApi.generate(settings.apiKey, model, body))
        }
    }

    private fun BufferedSource.peekStartsWith(prefix: String): Boolean =
        runCatching { request(prefix.length.toLong()) && peek().readUtf8(prefix.length.toLong()) == prefix }
            .getOrDefault(false)

    /**
     * Room for the answer. The cap covers the model's thinking as well as
     * its reply on every current API, so it is set well above what a page
     * of translations needs: a cap the thinking exhausts cuts the JSON off
     * mid-array, and the page then comes back as though the model had said
     * nothing.
     */
    internal fun outputCap(anthropic: Boolean, vision: Boolean, effort: String): Int {
        val base = if (anthropic) (if (vision) 8192 else 4096) else (if (vision) 16384 else 8192)
        return if (effort == "high") base * 2 else base
    }

    // ---- request shapes ----

    internal fun anthropicBody(
        settings: AppSettings,
        system: String,
        stable: String,
        images: List<String>,
        page: String,
        effort: String,
        vision: Boolean,
        stream: Boolean,
    ): JSONObject {
        fun cached(text: String) = JSONObject()
            .put("type", "text")
            .put("text", text)
            .put("cache_control", JSONObject().put("type", "ephemeral"))

        val content = JSONArray().put(cached(stable))
        for (image in images) {
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
            .put("max_tokens", outputCap(anthropic = true, vision = vision, effort = effort))
            .put("system", JSONArray().put(cached(system)))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        if (CLAUDE_EFFORT.containsMatchIn(settings.effectiveModel())) {
            body.put("output_config", JSONObject().put("effort", effort))
        }
        if (stream) body.put("stream", true)
        return body
    }

    internal fun openAiBody(
        settings: AppSettings,
        system: String,
        stable: String,
        images: List<String>,
        page: String,
        effort: String,
        vision: Boolean,
        stream: Boolean,
    ): JSONObject {
        // Text-only turns go as one plain string: every compatible server
        // accepts that, and some accept nothing else.
        val userContent: Any = if (images.isEmpty()) {
            stable + "\n\n" + page
        } else {
            val parts = JSONArray().put(JSONObject().put("type", "text").put("text", stable))
            for (image in images) {
                parts.put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$image")
                    )
                )
            }
            parts.put(JSONObject().put("type", "text").put("text", page))
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", userContent))
        val model = settings.effectiveModel()
        val cap = outputCap(anthropic = false, vision = vision, effort = effort)
        val body = JSONObject().put("model", model).put("messages", messages)

        // Greedy decoding wherever the model tolerates it. Re-reading a page
        // must not re-word it: the cache is keyed on OCR text, and OCR
        // varies slightly between two captures of the same page, so a
        // re-read often misses the cache and asks again. With sampling on,
        // that second answer differs from the first — the same panel worded
        // two ways depending on when you looked at it. The OpenAI reasoning
        // models are the exception: they are tuned for their default
        // temperature and reject any other.
        var greedy = true
        var tokensField = "max_tokens"
        when (settings.provider) {
            LlmProvider.OPENAI -> {
                if (OPENAI_REASONING.containsMatchIn(model)) {
                    greedy = false
                    tokensField = "max_completion_tokens"
                    body.put("reasoning_effort", effort)
                }
            }
            LlmProvider.OPENROUTER -> {
                if (OPENROUTER_REASONING.containsMatchIn(model)) {
                    body.put("reasoning", JSONObject().put("effort", effort))
                }
                if (OPENROUTER_DEFAULT_TEMPERATURE.containsMatchIn(model)) greedy = false
            }
            else -> Unit
        }
        if (greedy) body.put("temperature", 0)
        body.put(tokensField, cap)
        if (stream) body.put("stream", true)
        return body
    }

    /**
     * Google's native request: the system prompt as `systemInstruction`,
     * then one user turn laid out stable-first — series memory, images,
     * page — so Gemini's implicit prefix caching reuses the memory from one
     * page to the next. The reply is constrained to JSON, which the native
     * API guarantees rather than merely encourages.
     *
     * Greedy decoding as elsewhere, except on Gemini 3 and later: Google
     * warns that lowering their temperature sends them into loops.
     *
     * Built for [model], which is the reader's choice unless it has been
     * retired: a fallback gets the thinking configuration and temperature
     * of its own generation, not those of the model it stands in for.
     */
    internal fun geminiBody(
        settings: AppSettings,
        system: String,
        stable: String,
        images: List<String>,
        page: String,
        effort: String,
        vision: Boolean,
        model: String = settings.effectiveModel(),
    ): JSONObject {
        val parts = JSONArray().put(JSONObject().put("text", stable))
        for (image in images) {
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", image)))
        }
        parts.put(JSONObject().put("text", page))
        val config = JSONObject()
            .put("responseMimeType", "application/json")
            .put("maxOutputTokens", outputCap(anthropic = false, vision = vision, effort = effort))
        GeminiApi.thinkingConfig(model, settings.aiReasoning)?.let { config.put("thinkingConfig", it) }
        if (GeminiApi.takesTemperature(model)) config.put("temperature", 0)
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", config)
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
     * thread between events; cancelling the coroutine cancels the call
     * ([abortOnCancel]), which fails the read and ends the stream.
     */
    private suspend fun readEvents(
        call: Call,
        source: BufferedSource,
        anthropic: Boolean,
        onDelta: suspend (String) -> Unit,
    ): String = abortOnCancel(call) {
        val full = StringBuilder()
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
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> dispatch()
                line.startsWith("data:") -> data.append(line.substring(5).trim())
                // event: and comment lines carry nothing the payload does not.
            }
        }
        dispatch()
        full.toString()
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

    /**
     * Digs the response object out of prose/markdown-fenced replies. A
     * reply that is a bare array of bubbles is wrapped into the object
     * shape; it is recognised by its bracket coming before any brace, since
     * the first brace inside such an array opens its first bubble, not the
     * reply.
     */
    fun extractJsonObject(raw: String): JSONObject {
        val cleaned = raw.replace("```json", "").replace("```", "").trim()
        val objStart = cleaned.indexOf('{')
        val objEnd = cleaned.lastIndexOf('}')
        val arrStart = cleaned.indexOf('[')
        val arrEnd = cleaned.lastIndexOf(']')
        val arrayFirst = arrStart >= 0 && (objStart < 0 || arrStart < objStart)

        fun asObject(): JSONObject? = if (objStart >= 0 && objEnd > objStart) {
            runCatching { JSONObject(cleaned.substring(objStart, objEnd + 1)) }.getOrNull()
        } else {
            null
        }

        // Bare-array reply: wrap so callers always see the object shape.
        fun asArray(): JSONObject? = if (arrStart >= 0 && arrEnd > arrStart) {
            runCatching { JSONObject().put("bubbles", JSONArray(cleaned.substring(arrStart, arrEnd + 1))) }.getOrNull()
        } else {
            null
        }

        return (if (arrayFirst) asArray() ?: asObject() else asObject() ?: asArray())
            ?: throw RuntimeException("no JSON in LLM reply")
    }
}
