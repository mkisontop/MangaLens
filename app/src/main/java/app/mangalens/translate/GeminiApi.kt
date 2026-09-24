package app.mangalens.translate

import app.mangalens.settings.AiReasoning
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject

/** Gemini answered with an HTTP error; the message reads `Gemini HTTP <code>: <message>`. */
open class GeminiHttpException(val code: Int, detail: String) : RuntimeException("Gemini HTTP $code: $detail")

/**
 * The model asked for does not exist, or Google has retired it. Distinct
 * because the cure is another model rather than another attempt: a model
 * picked a year ago answers this forever.
 */
class GeminiModelMissing(val model: String, code: Int, detail: String) : GeminiHttpException(code, detail)

/** Too many requests for this key: racing duplicates only makes it worse. */
class GeminiRateLimited(detail: String) : GeminiHttpException(429, detail)

/**
 * Google declined to answer — a safety filter on the page or on the reply.
 * Distinct so the caller can read the page another way instead of painting
 * a blank one: a filter that trips on the art often lets the same text
 * through as text.
 */
class GeminiBlocked(val reason: String) : RuntimeException("Gemini declined the page: $reason")

/**
 * Google's native Gemini API (`generateContent` / `streamGenerateContent`).
 *
 * The native API rather than Google's OpenAI-compatible endpoint, because
 * only the native one takes a response schema, reports thought parts apart
 * from the answer, and says *why* a reply is empty — a safety block, a
 * retired model — which the compatible endpoint flattens into an empty
 * string the caller cannot tell from a page with no text.
 *
 * The key travels in the `x-goog-api-key` header, never in the URL, where
 * it would end up in proxy and crash logs — and never in an error message
 * either ([LlmHttp.keyHeader]).
 */
internal object GeminiApi {

    const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    /** Google's alias for its newest Flash; the model a retired one falls back to. */
    const val FALLBACK_MODEL = "gemini-flash-latest"

    /** Where requests go; tests point it at a local server. */
    @Volatile
    internal var base: String = BASE

    /** The HTTP client; tests swap in one that never leaves the machine. */
    @Volatile
    internal var client: OkHttpClient = LlmHttp.client

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Finish reasons that mean Google withheld the answer rather than ran out of it. */
    private val BLOCKING_FINISH = setOf(
        "SAFETY", "RECITATION", "LANGUAGE", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII",
        "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT", "IMAGE_RECITATION", "IMAGE_OTHER", "OTHER",
    )

    /**
     * Gemini 3 and later think in levels. The `-latest` aliases point at
     * the current generation, so they count as the newest.
     */
    private val GEMINI_LEVELS = Regex("^gemini-([3-9]|[1-9]\\d)|-latest$")
    private val GEMINI_BUDGET = Regex("^gemini-2\\.5")

    /**
     * Models known to reject the "minimal" thinking level (HTTP 400 "Thinking
     * level MINIMAL is not supported for this model"). Knowing it up front
     * saves the first page a rejected round trip; anything else that rejects
     * it is learned from the 400 and remembered.
     */
    private val NO_MINIMAL = Regex("pro|^gemini-flash-latest$|^gemini-3\\.[6-9]-flash(?!-lite)")

    /** Levels (or "budget") a model answered 400 to, per model, for the life of the process. */
    private val rejected = ConcurrentHashMap<String, MutableSet<String>>()

    /** Models Google answered "not found" or "no longer available" for. */
    private val missing = ConcurrentHashMap.newKeySet<String>()

    private val lastWarm = AtomicLong(0L)
    private const val WARM_INTERVAL_MS = 60_000L

    fun isMissing(model: String): Boolean = model in missing

    /**
     * The models a request moves down to while Google turns away the one
     * asked for as overloaded (HTTP 503), each with capacity of its own,
     * best reader first. Measured on real manga pages while the newest
     * Flash answered nothing but 503s: 3.6 Flash boxed 94% of the
     * annotated lettering and painted first in 1.7 s, 3.5 Flash 92% in
     * 2.4 s; Flash-Lite answered every page but boxed only two lines in
     * three where they are, so it comes last. One Google has retired
     * answers "not found" once and is passed over after that.
     */
    private val RELIEF = listOf("gemini-3.6-flash", "gemini-3.5-flash", "gemini-flash-lite-latest")

    /** How long a model that answered 503 is passed over for the next one down. */
    private const val STRAIN_MS = 2 * 60_000L

    private val strained = ConcurrentHashMap<String, Long>()

    private fun now() = System.nanoTime() / 1_000_000

    /** Google turned [model] away as overloaded: pass it over for a while. */
    fun strain(model: String) {
        strained[model] = now() + STRAIN_MS
    }

    private fun isStrained(model: String): Boolean = (strained[model] ?: 0L) > now()

    /**
     * The model to ask instead of [model] while it is overloaded: the next
     * one down that is neither retired nor overloaded itself, or null when
     * none is left.
     */
    fun relief(model: String): String? =
        RELIEF.drop(RELIEF.indexOf(model) + 1).firstOrNull { it != model && !isMissing(it) && !isStrained(it) }

    /** [model], or the one standing in for it while Google turns it away as overloaded. */
    fun available(model: String): String = if (isStrained(model)) relief(model) ?: model else model

    /**
     * The thinking configuration for [model] at [reasoning], or null for a
     * model that takes none.
     *
     * Every level of thinking is paid for in waiting before the first
     * balloon, so the fast setting asks for the least a model accepts:
     * "minimal" where it is taken, otherwise "low" — which on the current
     * Flash yields next to no thought tokens anyway.
     */
    fun thinkingConfig(model: String, reasoning: AiReasoning): JSONObject? {
        val refused = rejected[model].orEmpty()
        if (GEMINI_LEVELS.containsMatchIn(model)) {
            var level = when (reasoning) {
                AiReasoning.FAST -> if (NO_MINIMAL.containsMatchIn(model)) "low" else "minimal"
                AiReasoning.BALANCED -> "low"
                AiReasoning.THOROUGH -> "high"
            }
            if (level in refused) level = "low"
            return JSONObject().put("thinkingLevel", level)
        }
        if (GEMINI_BUDGET.containsMatchIn(model) && "budget" !in refused) {
            val pro = model.contains("pro")
            val budget = when (reasoning) {
                // 2.5 Pro cannot switch thinking off; 128 is its floor.
                AiReasoning.FAST -> if (pro) 128 else 0
                AiReasoning.BALANCED -> 512
                AiReasoning.THOROUGH -> -1
            }
            return JSONObject().put("thinkingBudget", budget)
        }
        return null
    }

    /** Whether [model] predates thinking levels and still honours a greedy temperature. */
    fun takesTemperature(model: String): Boolean = !GEMINI_LEVELS.containsMatchIn(model)

    /**
     * One streamed `streamGenerateContent` call. Hands each piece of visible
     * (non-thought) text to [onDelta] as it arrives and returns the whole
     * text. Cancelling the caller aborts the request.
     */
    suspend fun stream(
        apiKey: String,
        model: String,
        body: JSONObject,
        onDelta: (suspend (String) -> Unit)? = null,
    ): String = stream(apiKey, model, body, onSent = {}, onDelta = onDelta)

    /**
     * [stream], calling [onSent] once the request body has been handed to
     * the connection: the moment a duplicate can follow without halving
     * this one's upload.
     */
    suspend fun stream(
        apiKey: String,
        model: String,
        body: JSONObject,
        onSent: () -> Unit,
        onDelta: (suspend (String) -> Unit)?,
    ): String = stream(apiKey, model, body, onSent, onFinish = {}, onDelta = onDelta)

    /**
     * [stream], also handing [onFinish] the reason the model gave for
     * stopping, once the reply has ended: "STOP" when it said all it meant
     * to; anything else — a filter tripping part-way, the token cap — when
     * the text it returns is only the start of an answer; empty when the
     * stream never said. A reply withheld before any text at all is still
     * a [GeminiBlocked].
     */
    suspend fun stream(
        apiKey: String,
        model: String,
        body: JSONObject,
        onSent: () -> Unit,
        onFinish: (String) -> Unit,
        onDelta: (suspend (String) -> Unit)?,
    ): String = withContext(Dispatchers.IO) {
        val url = base + model + ":streamGenerateContent?alt=sse"
        withThinkingRetry(model, body) { b ->
            val call = client.newCall(post(url, apiKey, b, onSent))
            LlmHttp.await(call).use { resp ->
                if (!resp.isSuccessful) throw httpError(model, resp)
                val source = resp.body?.source() ?: return@use ""
                LlmHttp.abortOnCancel(call) { readEvents(source, onDelta, model, onFinish) }
            }
        }
    }

    /** One plain `generateContent` call; returns the response document. */
    suspend fun generate(apiKey: String, model: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val url = base + model + ":generateContent"
        withThinkingRetry(model, body) { b ->
            LlmHttp.await(client.newCall(post(url, apiKey, b))).use { resp ->
                if (!resp.isSuccessful) throw httpError(model, resp)
                JSONObject(resp.body?.string() ?: "{}")
            }
        }
    }

    /**
     * Opens the connection to Google before the first page needs it: a
     * cheap `models/{model}` lookup, answered or not, leaves a TLS/HTTP2
     * connection in the pool that the page request then reuses — the
     * handshake is otherwise paid on the first page, the one the reader is
     * watching. Fire-and-forget, at most once a minute, never throws.
     */
    fun warm(apiKey: String, model: String) {
        if (LlmHttp.cleanKey(apiKey).isEmpty() || model.isBlank()) return
        val now = System.currentTimeMillis()
        val last = lastWarm.get()
        if (now - last < WARM_INTERVAL_MS || !lastWarm.compareAndSet(last, now)) return
        runCatching {
            val request = LlmHttp.keyHeader(Request.Builder().url(base + model), "x-goog-api-key", apiKey)
                .get()
                .build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = Unit

                override fun onResponse(call: Call, response: Response) {
                    // A model that is gone is worth knowing before the page asks for it.
                    if (response.code == 404) missing.add(model)
                    response.close()
                }
            })
        }
    }

    /** Lets the next [warm] go out at once; for tests. */
    internal fun resetWarm() = lastWarm.set(0L)

    /** Forgets what was learned about models, and which are overloaded; for tests. */
    internal fun resetLearned() {
        rejected.clear()
        missing.clear()
        strained.clear()
    }

    private fun post(url: String, apiKey: String, body: JSONObject, onSent: () -> Unit = {}): Request {
        val payload = body.toString().toRequestBody(JSON)
        val signalling = object : RequestBody() {
            override fun contentType() = payload.contentType()
            override fun contentLength() = payload.contentLength()
            override fun writeTo(sink: BufferedSink) {
                payload.writeTo(sink)
                onSent()
            }
        }
        return LlmHttp.keyHeader(Request.Builder().url(url), "x-goog-api-key", apiKey)
            .post(signalling)
            .build()
    }

    /**
     * Runs [attempt], and if the model rejects the thinking configuration
     * the request carried, runs it once more with "low" (or with no budget)
     * and remembers the refusal for the rest of the process, so only the
     * first page ever pays for the lesson.
     */
    private suspend fun <T> withThinkingRetry(model: String, body: JSONObject, attempt: suspend (JSONObject) -> T): T {
        try {
            return attempt(body)
        } catch (e: GeminiHttpException) {
            if (e.code != 400 || e.message?.contains("thinking", ignoreCase = true) != true) throw e
            val config = body.optJSONObject("generationConfig")?.optJSONObject("thinkingConfig") ?: throw e
            val level = config.optString("thinkingLevel", "")
            val refusedKey = when {
                level.isNotEmpty() && level != "low" -> level
                level.isEmpty() && config.has("thinkingBudget") -> "budget"
                else -> throw e
            }
            rejected.getOrPut(model) { ConcurrentHashMap.newKeySet() }.add(refusedKey)
            val retry = JSONObject(body.toString())
            val gen = retry.getJSONObject("generationConfig")
            if (refusedKey == "budget") {
                gen.remove("thinkingConfig")
            } else {
                gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            }
            return attempt(retry)
        }
    }

    // ---- responses ----

    /**
     * Reads a `streamGenerateContent?alt=sse` event stream to its end,
     * handing each visible text part to [onDelta] and returning the whole
     * text. Thought parts are dropped: they are the model's reasoning, not
     * its answer. [onFinish] hears the last finish reason the stream gave,
     * or an empty one when it gave none.
     */
    internal suspend fun readEvents(
        source: BufferedSource,
        onDelta: (suspend (String) -> Unit)?,
        model: String = "",
        onFinish: (String) -> Unit = {},
    ): String {
        val full = StringBuilder()
        val data = StringBuilder()
        var finish = ""

        suspend fun dispatch() {
            if (data.isEmpty()) return
            val payload = data.toString()
            data.setLength(0)
            if (payload == "[DONE]") return
            val o = runCatching { JSONObject(payload) }.getOrNull() ?: return
            o.optJSONObject("error")?.let { throw errorFor(model, it.optInt("code", 500), it.optString("message")) }
            blockReason(o)?.let { throw GeminiBlocked(it) }
            val candidate = o.optJSONArray("candidates")?.optJSONObject(0) ?: return
            candidate.optString("finishReason", "").let { if (it.isNotEmpty()) finish = it }
            val text = visibleText(candidate)
            if (text.isNotEmpty()) {
                full.append(text)
                onDelta?.invoke(text)
            }
        }

        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> dispatch()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).removePrefix(" "))
                }
                // event:, id: and comment lines carry nothing the payload does not.
            }
        }
        dispatch()
        if (full.isEmpty() && finish in BLOCKING_FINISH) throw GeminiBlocked(finish)
        onFinish(finish)
        return full.toString()
    }

    /**
     * The visible text of a `generateContent` response. Throws
     * [GeminiBlocked] when Google withheld the answer, so a refusal is
     * never mistaken for a page with nothing on it.
     */
    fun text(response: JSONObject): String {
        blockReason(response)?.let { throw GeminiBlocked(it) }
        val candidate = response.optJSONArray("candidates")?.optJSONObject(0) ?: return ""
        val text = visibleText(candidate)
        val finish = candidate.optString("finishReason", "")
        if (text.isEmpty() && finish in BLOCKING_FINISH) throw GeminiBlocked(finish)
        return text
    }

    /**
     * Why Google withheld [response], or null when nothing in it says so:
     * a block on the prompt, or a candidate stopped by a filter. For
     * answers that are not text — an image model's refusal is a 200 like
     * any other, with no image in it and the reason beside it.
     */
    fun refusal(response: JSONObject): String? {
        blockReason(response)?.let { return it }
        val candidates = response.optJSONArray("candidates") ?: return null
        for (i in 0 until candidates.length()) {
            val finish = candidates.optJSONObject(i)?.optString("finishReason", "").orEmpty()
            if (finish in BLOCKING_FINISH) return finish
        }
        return null
    }

    private fun blockReason(o: JSONObject): String? =
        o.optJSONObject("promptFeedback")?.optString("blockReason", "")?.takeIf { it.isNotEmpty() }

    private fun visibleText(candidate: JSONObject): String {
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            sb.append(part.optString("text", ""))
        }
        return sb.toString()
    }

    private fun httpError(model: String, resp: Response): GeminiHttpException {
        val raw = runCatching { resp.body?.string() }.getOrNull().orEmpty()
        // Errors come as {"error":{...}}, or wrapped in an array on the streaming endpoint.
        val error = runCatching { JSONObject(raw).optJSONObject("error") }.getOrNull()
            ?: runCatching { JSONArray(raw).optJSONObject(0)?.optJSONObject("error") }.getOrNull()
        val message = error?.optString("message")?.takeIf { it.isNotBlank() } ?: raw.take(200)
        return errorFor(model, resp.code, message)
    }

    private fun errorFor(model: String, code: Int, message: String): GeminiHttpException {
        val detail = message.take(300)
        return when {
            code == 429 -> GeminiRateLimited(detail)
            code == 404 || message.contains("no longer available", ignoreCase = true) -> {
                if (model.isNotEmpty()) missing.add(model)
                GeminiModelMissing(model, code, detail)
            }
            else -> GeminiHttpException(code, detail)
        }
    }
}
