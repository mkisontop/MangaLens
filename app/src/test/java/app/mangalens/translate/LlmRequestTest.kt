package app.mangalens.translate

import app.mangalens.settings.AiReasoning
import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The request each provider actually receives. A wrong field here fails
 * every page: the provider answers 400, nothing else translates the page,
 * and the reader is left with raw lettering and an error in the pill — the
 * model they chose lost to one field it would not take.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LlmRequestTest {

    private fun settings(provider: LlmProvider, model: String, reasoning: AiReasoning = AiReasoning.BALANCED) =
        AppSettings(provider = provider, model = model, apiKey = "k", aiReasoning = reasoning)

    private fun openAi(settings: AppSettings, images: List<String> = emptyList(), vision: Boolean = images.isNotEmpty()) =
        LlmHttp.openAiBody(
            settings, "SYS", "STABLE", images, "PAGE",
            effort = LlmHttp.effortLevel(settings, vision), vision = vision, stream = true,
        )

    private fun anthropic(settings: AppSettings, images: List<String> = emptyList(), vision: Boolean = images.isNotEmpty()) =
        LlmHttp.anthropicBody(
            settings, "SYS", "STABLE", images, "PAGE",
            effort = LlmHttp.effortLevel(settings, vision), vision = vision, stream = true,
        )

    // ---- Anthropic ----

    @Test
    fun `anthropic requests cache the system prompt and the series memory, page last`() {
        val body = anthropic(settings(LlmProvider.ANTHROPIC, "claude-sonnet-5"), images = listOf("PAGEJPEG", "CROP7"))
        val system = body.getJSONArray("system").getJSONObject(0)
        assertEquals("ephemeral", system.getJSONObject("cache_control").getString("type"))
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("STABLE", content.getJSONObject(0).getString("text"))
        assertEquals("ephemeral", content.getJSONObject(0).getJSONObject("cache_control").getString("type"))
        assertEquals("image", content.getJSONObject(1).getString("type"))
        assertEquals("PAGEJPEG", content.getJSONObject(1).getJSONObject("source").getString("data"))
        assertEquals("CROP7", content.getJSONObject(2).getJSONObject("source").getString("data"))
        assertEquals("PAGE", content.getJSONObject(3).getString("text"))
        assertEquals("medium", body.getJSONObject("output_config").getString("effort"))
        assertTrue(body.getBoolean("stream"))
        assertFalse("Claude rejects sampling parameters", body.has("temperature"))
    }

    @Test
    fun `older claude models get no effort field`() {
        val body = anthropic(settings(LlmProvider.ANTHROPIC, "claude-3-5-haiku-latest"))
        assertFalse(body.has("output_config"))
    }

    @Test
    fun `the reasoning setting maps to low, medium and high`() {
        assertEquals("low", LlmHttp.effortLevel(settings(LlmProvider.ANTHROPIC, "claude-sonnet-5", AiReasoning.FAST), vision = true))
        assertEquals("medium", LlmHttp.effortLevel(settings(LlmProvider.ANTHROPIC, "claude-sonnet-5", AiReasoning.BALANCED), vision = true))
        assertEquals("low", LlmHttp.effortLevel(settings(LlmProvider.ANTHROPIC, "claude-sonnet-5", AiReasoning.BALANCED), vision = false))
        assertEquals("high", LlmHttp.effortLevel(settings(LlmProvider.ANTHROPIC, "claude-sonnet-5", AiReasoning.THOROUGH), vision = false))
    }

    @Test
    fun `the output cap leaves room for thinking and doubles when thorough`() {
        val balanced = anthropic(settings(LlmProvider.ANTHROPIC, "claude-sonnet-5"), images = listOf("P"))
        val thorough = anthropic(settings(LlmProvider.ANTHROPIC, "claude-sonnet-5", AiReasoning.THOROUGH), images = listOf("P"))
        assertTrue(balanced.getInt("max_tokens") >= 8192)
        assertEquals(balanced.getInt("max_tokens") * 2, thorough.getInt("max_tokens"))
    }

    // ---- Gemini (native API) ----

    private fun gemini(settings: AppSettings, images: List<String> = emptyList(), vision: Boolean = images.isNotEmpty()) =
        LlmHttp.geminiBody(
            settings, "SYS", "STABLE", images, "PAGE",
            effort = LlmHttp.effortLevel(settings, vision), vision = vision,
        )

    private fun thinking(body: JSONObject): JSONObject? =
        body.getJSONObject("generationConfig").optJSONObject("thinkingConfig")

    @Test
    fun `gemini gets the native shape, system prompt apart and the page last`() {
        val body = gemini(settings(LlmProvider.GEMINI, "gemini-3.8-flash"), images = listOf("PAGEJPEG", "CROP2"))
        assertEquals("SYS", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals("STABLE", parts.getJSONObject(0).getString("text"))
        assertEquals("PAGEJPEG", parts.getJSONObject(1).getJSONObject("inlineData").getString("data"))
        assertEquals("image/jpeg", parts.getJSONObject(1).getJSONObject("inlineData").getString("mimeType"))
        assertEquals("CROP2", parts.getJSONObject(2).getJSONObject("inlineData").getString("data"))
        assertEquals("PAGE", parts.getJSONObject(3).getString("text"))
        val config = body.getJSONObject("generationConfig")
        assertEquals("application/json", config.getString("responseMimeType"))
        assertTrue(config.getInt("maxOutputTokens") >= 16384)
        assertFalse("the key goes in a header, the model in the URL", body.has("model"))
        assertFalse(body.has("safetySettings"))
    }

    @Test
    fun `gemini 3 keeps its default temperature and thinks in levels`() {
        val balanced = gemini(settings(LlmProvider.GEMINI, "gemini-3.8-flash"), images = listOf("P"))
        assertFalse("Google warns lowering Gemini 3's temperature causes loops", balanced.getJSONObject("generationConfig").has("temperature"))
        assertEquals("low", thinking(balanced)!!.getString("thinkingLevel"))
        val thorough = gemini(settings(LlmProvider.GEMINI, "gemini-3.5-flash-lite", AiReasoning.THOROUGH), images = listOf("P"))
        assertEquals("high", thinking(thorough)!!.getString("thinkingLevel"))
        assertEquals(
            thorough.getJSONObject("generationConfig").getInt("maxOutputTokens"),
            balanced.getJSONObject("generationConfig").getInt("maxOutputTokens") * 2,
        )
    }

    @Test
    fun `fast asks for minimal thinking only where the model takes it`() {
        fun fast(model: String) = thinking(gemini(settings(LlmProvider.GEMINI, model, AiReasoning.FAST)))!!.getString("thinkingLevel")
        assertEquals("minimal", fast("gemini-3.5-flash"))
        assertEquals("minimal", fast("gemini-3.5-flash-lite"))
        assertEquals("minimal", fast("gemini-3.1-flash-lite"))
        // 3.6 Flash, the stand-in, takes it (checked live).
        assertEquals("minimal", fast("gemini-3.6-flash"))
        // 3.7 and 3.8 Flash answer 400 to "minimal"; the default alias points at 3.8.
        assertEquals("low", fast("gemini-3.8-flash"))
        assertEquals("low", fast("gemini-3.7-flash"))
        assertEquals("low", fast("gemini-flash-latest"))
    }

    @Test
    fun `the default model alias counts as gemini 3`() {
        val body = gemini(settings(LlmProvider.GEMINI, ""))
        assertEquals("low", thinking(body)!!.getString("thinkingLevel"))
        assertFalse(body.getJSONObject("generationConfig").has("temperature"))
    }

    @Test
    fun `gemini 2_5 is greedy and takes a thinking budget`() {
        val body = gemini(settings(LlmProvider.GEMINI, "gemini-2.5-flash"), images = listOf("P"))
        assertEquals(0, body.getJSONObject("generationConfig").getInt("temperature"))
        assertTrue(thinking(body)!!.getInt("thinkingBudget") > 0)
        assertEquals(0, thinking(gemini(settings(LlmProvider.GEMINI, "gemini-2.5-flash", AiReasoning.FAST)))!!.getInt("thinkingBudget"))
        assertEquals(-1, thinking(gemini(settings(LlmProvider.GEMINI, "gemini-2.5-flash", AiReasoning.THOROUGH)))!!.getInt("thinkingBudget"))
        // 2.5 Pro cannot switch thinking off.
        assertEquals(128, thinking(gemini(settings(LlmProvider.GEMINI, "gemini-2.5-pro", AiReasoning.FAST)))!!.getInt("thinkingBudget"))
    }

    @Test
    fun `gemini models without thinking get no thinking config`() {
        val body = gemini(settings(LlmProvider.GEMINI, "gemini-2.0-flash"))
        assertNull(thinking(body))
        assertEquals(0, body.getJSONObject("generationConfig").getInt("temperature"))
    }

    // ---- OpenAI ----

    @Test
    fun `openai reasoning models get max_completion_tokens, an effort, and no temperature`() {
        val body = openAi(settings(LlmProvider.OPENAI, "gpt-5-mini"), images = listOf("P"))
        assertTrue(body.has("max_completion_tokens"))
        assertFalse("reasoning models reject max_tokens", body.has("max_tokens"))
        assertFalse("reasoning models reject temperature", body.has("temperature"))
        assertEquals("medium", body.getString("reasoning_effort"))
    }

    @Test
    fun `openai chat models keep the classic shape`() {
        val body = openAi(settings(LlmProvider.OPENAI, "gpt-4o-mini"))
        assertTrue(body.has("max_tokens"))
        assertEquals(0, body.getInt("temperature"))
        assertFalse(body.has("reasoning_effort"))
    }

    // ---- OpenRouter and custom ----

    @Test
    fun `openrouter gets the unified reasoning object for models that reason`() {
        val claude = openAi(settings(LlmProvider.OPENROUTER, "anthropic/claude-sonnet-4.5"), images = listOf("P"))
        assertEquals("medium", claude.getJSONObject("reasoning").getString("effort"))
        assertEquals(0, claude.getInt("temperature"))
        val gemini3 = openAi(settings(LlmProvider.OPENROUTER, "google/gemini-3.8-flash"))
        assertTrue(gemini3.has("reasoning"))
        assertFalse(gemini3.has("temperature"))
        val plain = openAi(settings(LlmProvider.OPENROUTER, "meta-llama/llama-3.3-70b-instruct"))
        assertFalse(plain.has("reasoning"))
    }

    @Test
    fun `custom endpoints get the plainest request`() {
        val body = openAi(settings(LlmProvider.CUSTOM, "whatever").copy(customUrl = "http://x/v1/chat/completions"))
        assertFalse(body.has("reasoning_effort"))
        assertFalse(body.has("reasoning"))
        assertEquals(0, body.getInt("temperature"))
        assertTrue(body.has("max_tokens"))
        // Text-only turns are one plain string, which every server accepts.
        val user = body.getJSONArray("messages").getJSONObject(1)
        assertEquals("STABLE\n\nPAGE", user.getString("content"))
    }

    @Test
    fun `images are sent page first, close-ups after, page text last`() {
        val body = openAi(settings(LlmProvider.OPENROUTER, "google/gemini-3.8-flash"), images = listOf("PAGEJPEG", "CROP2", "CROP5"))
        val parts = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals("STABLE", parts.getJSONObject(0).getString("text"))
        for ((i, expected) in listOf("PAGEJPEG", "CROP2", "CROP5").withIndex()) {
            val url = parts.getJSONObject(i + 1).getJSONObject("image_url").getString("url")
            assertEquals("data:image/jpeg;base64,$expected", url)
        }
        assertEquals("PAGE", parts.getJSONObject(4).getString("text"))
    }

    @Test
    fun `a bare array reply is still read as bubbles`() {
        val o: JSONObject = LlmHttp.extractJsonObject("[{\"id\":0,\"en\":\"Hi\"}]")
        assertEquals("Hi", o.getJSONArray("bubbles").getJSONObject(0).getString("en"))
    }

    // ---- over HTTP ----

    private var server: FakeHttpServer? = null

    @Before
    fun setUp() = GeminiApi.resetLearned()

    @After
    fun tearDown() {
        server?.close()
        GeminiApi.base = GeminiApi.BASE
        GeminiApi.resetLearned()
    }

    private fun serve(handler: (FakeHttpServer.Exchange) -> Unit): FakeHttpServer =
        FakeHttpServer(handler).also {
            server = it
            GeminiApi.base = it.base
        }

    private fun answer(text: String): String = JSONObject().put(
        "candidates",
        JSONArray().put(
            JSONObject()
                .put("content", JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", text))))
                .put("finishReason", "STOP")
        ),
    ).toString()

    private fun modelOf(target: String) = target.substringAfterLast('/').substringBefore(':')

    @Test
    fun `a retired gemini model falls back to the newest flash on the text path too`() = runBlocking {
        val retired = "gemini-2.5-retired-test"
        val srv = serve { ex ->
            when {
                ex.target.contains(retired) ->
                    ex.respond(404, "{\"error\":{\"code\":404,\"message\":\"models/$retired is not found\"}}")
                ex.target.contains("alt=sse") -> {
                    ex.startEvents()
                    ex.event(answer("{\"bubbles\":[]}"))
                }
                else -> ex.respond(200, answer("{\"bubbles\":[]}"))
            }
        }
        val s = settings(LlmProvider.GEMINI, retired)
        assertEquals("{\"bubbles\":[]}", LlmHttp.complete(s, "SYS", "STABLE", emptyList(), "PAGE", "low", vision = false))
        val deltas = ArrayList<String>()
        val streamed = LlmHttp.complete(s, "SYS", "STABLE", emptyList(), "PAGE", "low", vision = false) { deltas += it }
        assertEquals("{\"bubbles\":[]}", streamed)
        assertEquals(listOf(streamed), deltas)
        // Asked once; after that the retired model is skipped outright.
        assertEquals(listOf(retired, GeminiApi.FALLBACK_MODEL, GeminiApi.FALLBACK_MODEL), srv.exchanges.map { modelOf(it.target) })
        // The fallback's request is built for the fallback: a thinking level, not the old model's budget.
        val config = JSONObject(srv.exchanges[1].body).getJSONObject("generationConfig")
        assertTrue(config.getJSONObject("thinkingConfig").has("thinkingLevel"))
        assertFalse(config.has("temperature"))
    }

    @Test
    fun `a pasted key is cleaned on the compatible path too, and one of nothing but invisibles is no key`() = runBlocking {
        val srv = FakeHttpServer { ex -> ex.respond(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}") }.also { server = it }
        val s = AppSettings(provider = LlmProvider.CUSTOM, model = "m", customUrl = srv.base + "chat", apiKey = "\u200bSECRETKEY ")
        assertEquals("ok", LlmHttp.complete(s, "SYS", "STABLE", emptyList(), "PAGE", "low", vision = false))
        assertEquals("Bearer SECRETKEY", srv.exchanges.single().headers["authorization"])
        try {
            LlmHttp.requireConfig(settings(LlmProvider.GEMINI, "").copy(apiKey = "\u200b\u00a0"))
            fail("expected no key")
        } catch (e: RuntimeException) {
            assertEquals("No API key set for Gemini", e.message)
        }
    }

    @Test
    fun `what is missing before the AI can be asked is said as what to add`() {
        assertEquals("Add your Gemini key in MangaLens", LlmHttp.setupNeeded(AppSettings()))
        assertEquals(
            "Add your Gemini key in MangaLens",
            LlmHttp.setupNeeded(settings(LlmProvider.GEMINI, "").copy(apiKey = "​ ")),
        )
        assertEquals("Add your Claude key in MangaLens", LlmHttp.setupNeeded(settings(LlmProvider.ANTHROPIC, "").copy(apiKey = "")))
        assertNull(LlmHttp.setupNeeded(settings(LlmProvider.GEMINI, "")))
        // A local server may take no key at all; it only needs its address.
        assertEquals("Add your AI endpoint in MangaLens", LlmHttp.setupNeeded(AppSettings(provider = LlmProvider.CUSTOM)))
        assertNull(LlmHttp.setupNeeded(AppSettings(provider = LlmProvider.CUSTOM, customUrl = "http://127.0.0.1/v1/chat")))
    }

    /** A call that holds on to its callback, so a test decides when the response lands. */
    private class HeldCall(private val request: Request) : Call {
        @Volatile
        var callback: Callback? = null

        override fun request(): Request = request
        override fun execute(): Response = throw UnsupportedOperationException()
        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
        }
        override fun cancel() = Unit
        override fun isExecuted(): Boolean = callback != null
        override fun isCanceled(): Boolean = false
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = HeldCall(request)
    }

    @Test
    fun `a response that lands just as its caller is cancelled is closed, not leaked`() = runBlocking {
        val request = Request.Builder().url("http://127.0.0.1/").build()
        val call = HeldCall(request)
        var closed = false
        val source = object : ForwardingSource(Buffer().writeUtf8("{}")) {
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = source
        }
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        val waiting = launch { LlmHttp.await(call).close() }
        yield()
        // The response is handed over, and the caller is cancelled before it
        // gets to run again: the response never reaches it.
        call.callback!!.onResponse(call, response)
        waiting.cancel()
        waiting.join()
        assertTrue("the dropped response was closed", closed)
    }

    @Test
    fun `cancelling a streamed reply from a compatible server aborts it at once`() = runBlocking {
        val srv = FakeHttpServer { ex ->
            ex.startEvents()
            ex.awaitHangUp()
        }.also { server = it }
        val s = AppSettings(provider = LlmProvider.CUSTOM, model = "m", customUrl = srv.base + "chat")
        // Its own scope: were the cancel lost, the stuck read must not hold the test up.
        val reading = CoroutineScope(Dispatchers.IO).async {
            LlmHttp.complete(s, "SYS", "STABLE", emptyList(), "PAGE", "low", vision = false) { }
        }
        withTimeout(5_000) { while (srv.exchanges.isEmpty()) delay(10) }
        delay(200)
        val t0 = System.nanoTime()
        reading.cancel()
        withTimeout(3_000) { reading.join() }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("cancel took $ms ms", ms < 1_000)
    }
}
