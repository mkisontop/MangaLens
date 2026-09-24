package app.mangalens.translate

import app.mangalens.settings.AiReasoning
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Google's native API, against canned event streams and a loopback server.
 * What matters is what the reader sees when something goes wrong: a
 * refusal, a retired model and a rate limit must each surface as their own
 * failure, so the pipeline can do the right thing instead of painting a
 * blank page — and a request nobody wants any more must stop at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiApiTest {

    private var server: FakeHttpServer? = null

    @Before
    fun setUp() {
        GeminiApi.resetLearned()
        GeminiApi.resetWarm()
    }

    @After
    fun tearDown() {
        server?.close()
        GeminiApi.base = GeminiApi.BASE
        GeminiApi.resetLearned()
        GeminiApi.resetWarm()
    }

    private fun serve(handler: (FakeHttpServer.Exchange) -> Unit): FakeHttpServer =
        FakeHttpServer(handler).also {
            server = it
            GeminiApi.base = it.base
        }

    private fun chunk(vararg parts: Pair<String, Boolean>, finish: String? = null): String {
        val arr = JSONArray()
        for ((text, thought) in parts) {
            val p = JSONObject().put("text", text)
            if (thought) p.put("thought", true)
            arr.put(p)
        }
        val candidate = JSONObject().put("content", JSONObject().put("role", "model").put("parts", arr))
        if (finish != null) candidate.put("finishReason", finish)
        return JSONObject().put("candidates", JSONArray().put(candidate)).toString()
    }

    private fun text(s: String) = s to false
    private fun thought(s: String) = s to true

    private fun parse(events: String, deltas: MutableList<String> = ArrayList()): String = runBlocking {
        GeminiApi.readEvents(Buffer().writeUtf8(events), { deltas += it })
    }

    private fun body(level: String? = null): JSONObject {
        val config = JSONObject().put("responseMimeType", "application/json")
        if (level != null) config.put("thinkingConfig", JSONObject().put("thinkingLevel", level))
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "hi")))))
            .put("generationConfig", config)
    }

    // ---- event parsing ----

    @Test
    fun `visible text streams out and thought parts are skipped`() {
        val deltas = ArrayList<String>()
        val events = "data: " + chunk(thought("Let me look at the page...")) + "\n\n" +
            "data: " + chunk(text("{\"items\":[")) + "\n\n" +
            "data: " + chunk(text("{\"en\":\"Hi\"}]}"), finish = "STOP") + "\n\n"
        assertEquals("{\"items\":[{\"en\":\"Hi\"}]}", parse(events, deltas))
        assertEquals(listOf("{\"items\":[", "{\"en\":\"Hi\"}]}"), deltas)
    }

    @Test
    fun `a chunk with several parts hands its visible parts over together`() {
        val deltas = ArrayList<String>()
        val events = "data: " + chunk(thought("hmm"), text("ab"), text("cd")) + "\n\n"
        assertEquals("abcd", parse(events, deltas))
        assertEquals(listOf("abcd"), deltas)
    }

    @Test
    fun `CRLF endings, comments and multi-line data are read as whole events`() {
        val payload = chunk(text("{\"a\":1}"))
        // Split between two JSON tokens: the lines rejoin with a newline,
        // which JSON reads as whitespace.
        val cut = payload.indexOf("\"candidates\"")
        val events = ": keep-alive\r\n" +
            "event: message\r\n" +
            "data: " + payload.substring(0, cut) + "\r\n" +
            "data: " + payload.substring(cut) + "\r\n\r\n" +
            "data: " + chunk(text("!")) + "\r\n\r\n" +
            "data: [DONE]\r\n\r\n"
        assertEquals("{\"a\":1}!", parse(events))
    }

    @Test
    fun `a stream without a trailing blank line still delivers its last event`() {
        assertEquals("tail", parse("data: " + chunk(text("tail"))))
    }

    @Test
    fun `a blocked prompt is a GeminiBlocked with its reason`() {
        val events = "data: {\"promptFeedback\":{\"blockReason\":\"PROHIBITED_CONTENT\"}}\n\n"
        try {
            parse(events)
            fail("expected GeminiBlocked")
        } catch (e: GeminiBlocked) {
            assertEquals("PROHIBITED_CONTENT", e.reason)
        }
    }

    @Test
    fun `a safety stop with no text is a refusal, with text it is a cut-off answer`() {
        for (reason in listOf("SAFETY", "IMAGE_SAFETY", "OTHER", "BLOCKLIST", "SPII", "PROHIBITED_CONTENT")) {
            try {
                parse("data: " + chunk(finish = reason) + "\n\n")
                fail("expected GeminiBlocked for $reason")
            } catch (e: GeminiBlocked) {
                assertEquals(reason, e.reason)
            }
        }
        val partial = "data: " + chunk(text("{\"items\":[{\"en\":\"a\"}")) + "\n\n" +
            "data: " + chunk(finish = "SAFETY") + "\n\n"
        assertEquals("{\"items\":[{\"en\":\"a\"}", parse(partial))
        // Running out of room is not a refusal.
        assertEquals("", parse("data: " + chunk(finish = "MAX_TOKENS") + "\n\n"))
    }

    @Test
    fun `the reason the model stopped is handed on, empty when the stream gave none`() {
        fun finishOf(events: String): String? {
            var finish: String? = null
            runBlocking { GeminiApi.readEvents(Buffer().writeUtf8(events), null, onFinish = { finish = it }) }
            return finish
        }
        assertEquals("STOP", finishOf("data: " + chunk(text("{}"), finish = "STOP") + "\n\n"))
        val begun = "data: " + chunk(text("{\"items\":[{\"en\":\"a\"}")) + "\n\n"
        for (reason in listOf("SAFETY", "PROHIBITED_CONTENT", "RECITATION", "MAX_TOKENS", "OTHER")) {
            assertEquals(reason, finishOf(begun + "data: " + chunk(finish = reason) + "\n\n"))
        }
        assertEquals("", finishOf(begun))
    }

    @Test
    fun `stream tells its caller why the reply ended`() = runBlocking {
        serve { ex ->
            ex.startEvents()
            ex.event(chunk(text("{\"items\":[{\"en\":\"a\"}")))
            ex.event(chunk(finish = "SAFETY"))
        }
        var finish: String? = null
        val out = GeminiApi.stream("K", "gemini-test-flash", body(), onSent = {}, onFinish = { finish = it }) { }
        assertEquals("{\"items\":[{\"en\":\"a\"}", out)
        assertEquals("SAFETY", finish)
    }

    @Test
    fun `a refusal is found in a reply that is not text`() {
        assertEquals("PROHIBITED_CONTENT", GeminiApi.refusal(JSONObject("{\"promptFeedback\":{\"blockReason\":\"PROHIBITED_CONTENT\"}}")))
        assertEquals("IMAGE_SAFETY", GeminiApi.refusal(JSONObject(chunk(text("I can't help with that."), finish = "IMAGE_SAFETY"))))
        assertEquals(null, GeminiApi.refusal(JSONObject(chunk(text("Here you go."), finish = "STOP"))))
        assertEquals(null, GeminiApi.refusal(JSONObject(chunk(finish = "MAX_TOKENS"))))
        assertEquals(null, GeminiApi.refusal(JSONObject("{}")))
    }

    @Test
    fun `an error event mid-stream surfaces as an HTTP error`() {
        val events = "data: " + chunk(text("{")) + "\n\n" +
            "data: {\"error\":{\"code\":503,\"message\":\"The model is overloaded.\"}}\n\n"
        try {
            parse(events)
            fail("expected an error")
        } catch (e: GeminiHttpException) {
            assertEquals(503, e.code)
            assertEquals("Gemini HTTP 503: The model is overloaded.", e.message)
        }
    }

    @Test
    fun `a plain response gives its visible text, and a refusal is not an empty page`() {
        val ok = JSONObject(chunk(thought("thinking"), text("{\"bubbles\":[]}"), finish = "STOP"))
        assertEquals("{\"bubbles\":[]}", GeminiApi.text(ok))
        try {
            GeminiApi.text(JSONObject("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}"))
            fail("expected GeminiBlocked")
        } catch (e: GeminiBlocked) {
            assertEquals("SAFETY", e.reason)
        }
        try {
            GeminiApi.text(JSONObject(chunk(finish = "RECITATION")))
            fail("expected GeminiBlocked")
        } catch (e: GeminiBlocked) {
            assertEquals("RECITATION", e.reason)
        }
    }

    // ---- over HTTP ----

    @Test
    fun `stream posts to the model's SSE endpoint with the key in a header only`() = runBlocking {
        val srv = serve { ex ->
            ex.startEvents()
            ex.event(chunk(thought("..."), text("{\"items\":")))
            ex.event(chunk(text("[]}"), finish = "STOP"))
        }
        val deltas = ArrayList<String>()
        val out = GeminiApi.stream("SECRET", "gemini-test-flash", body("low")) { deltas += it }
        assertEquals("{\"items\":[]}", out)
        assertEquals(listOf("{\"items\":", "[]}"), deltas)
        val ex = srv.exchanges.single()
        assertEquals("POST", ex.method)
        assertEquals("/v1beta/models/gemini-test-flash:streamGenerateContent?alt=sse", ex.target)
        assertEquals("SECRET", ex.headers["x-goog-api-key"])
        assertFalse("the key never goes in the URL", ex.target.contains("SECRET") || ex.target.contains("key="))
        assertFalse(JSONObject(ex.body).has("safetySettings"))
    }

    @Test
    fun `a stream silent past its limit is given up on, not waited out`() = runBlocking {
        // A connection that died without saying so: the request goes out and
        // nothing ever comes back. Both racers would share it for 90 s.
        serve { ex ->
            ex.startEvents()
            Thread.sleep(4_000)
        }
        val started = System.nanoTime()
        try {
            GeminiApi.stream("K", "gemini-test-flash", body("low"), 500L, {}, {}, null)
            fail("expected the silence to end the stream")
        } catch (e: IOException) {
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue("gave up after $ms ms", ms < 2_500)
        }
    }

    /** Every message on [e] and on its causes, since any of them can reach the screen or a log. */
    private fun messages(e: Throwable): String =
        generateSequence(e) { it.cause }.joinToString(" | ") { it.toString() }

    @Test
    fun `a key pasted with invisible characters is cleaned, and never quoted in an error`() = runBlocking {
        // A zero-width space and a no-break space ride along with a key copied from a web page.
        val pasted = "\u00a0SECRETKEY\u200b123 "
        val srv = serve { ex ->
            if (ex.target.contains("refusing-model")) {
                ex.respond(400, "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\"}}")
            } else {
                ex.startEvents()
                ex.event(chunk(text("{}"), finish = "STOP"))
            }
        }
        assertEquals("{}", GeminiApi.stream(pasted, "gemini-test-flash", body()))
        assertEquals("SECRETKEY123", srv.exchanges.single().headers["x-goog-api-key"])
        for (attempt in listOf<suspend () -> Unit>(
            { GeminiApi.stream(pasted, "refusing-model", body()) },
            { GeminiApi.generate(pasted, "refusing-model", body()) },
        )) {
            try {
                attempt()
                fail("expected the server's refusal")
            } catch (e: Exception) {
                assertFalse(messages(e), messages(e).contains("SECRETKEY"))
            }
        }
    }

    @Test
    fun `a key OkHttp would refuse is never quoted back`() {
        val raw = "SECRETKEY\u200b123"
        // Unguarded, OkHttp names the whole key: x-goog-api-key is not a header it keeps secret.
        val bare = runCatching { Request.Builder().header("x-goog-api-key", raw) }.exceptionOrNull()
        assertTrue(bare is IllegalArgumentException && bare.message!!.contains("SECRETKEY"))
        try {
            LlmHttp.withoutKeyInErrors { Request.Builder().header("x-goog-api-key", raw) }
            fail("expected the header to be refused")
        } catch (e: IllegalArgumentException) {
            assertEquals("API key contains characters that are not allowed", e.message)
            assertFalse(messages(e), messages(e).contains("SECRETKEY"))
        }
        // Through keyHeader the key is cleaned first and goes through.
        val request = LlmHttp.keyHeader(Request.Builder().url("http://127.0.0.1/"), "x-goog-api-key", raw).build()
        assertEquals("SECRETKEY123", request.header("x-goog-api-key"))
        assertEquals("", LlmHttp.cleanKey(" \u200b\u00a0\t"))
    }

    @Test
    fun `the upload is reported once, before the answer streams`() = runBlocking {
        serve { ex ->
            ex.startEvents()
            ex.event(chunk(text("{}"), finish = "STOP"))
        }
        val order = ArrayList<String>()
        GeminiApi.stream("K", "gemini-test-flash", body(), onSent = { synchronized(order) { order += "sent" } }) {
            synchronized(order) { order += "delta" }
        }
        assertEquals(listOf("sent", "delta"), order)
    }

    @Test
    fun `generate posts to generateContent and returns the document`() = runBlocking {
        val srv = serve { ex -> ex.respond(200, chunk(text("{\"a\":1}"), finish = "STOP")) }
        val doc = GeminiApi.generate("K", "gemini-test-flash", body())
        assertEquals("{\"a\":1}", GeminiApi.text(doc))
        assertEquals("/v1beta/models/gemini-test-flash:generateContent", srv.exchanges.single().target)
    }

    @Test
    fun `http failures are told apart - missing model, rate limit, anything else`() = runBlocking {
        serve { ex ->
            when {
                ex.target.contains("gone-model") ->
                    ex.respond(404, "{\"error\":{\"code\":404,\"message\":\"models/gone-model is not found\",\"status\":\"NOT_FOUND\"}}")
                ex.target.contains("retired-model") ->
                    ex.respond(400, "[{\"error\":{\"code\":400,\"message\":\"This model is no longer available to new users.\"}}]")
                ex.target.contains("busy-model") ->
                    ex.respond(429, "{\"error\":{\"code\":429,\"message\":\"Resource has been exhausted\"}}")
                else -> ex.respond(500, "{\"error\":{\"code\":500,\"message\":\"Internal error\"}}")
            }
        }
        try {
            GeminiApi.stream("K", "gone-model", body())
            fail()
        } catch (e: GeminiModelMissing) {
            assertEquals("gone-model", e.model)
            assertTrue(e.message!!.startsWith("Gemini HTTP 404: "))
        }
        assertTrue(GeminiApi.isMissing("gone-model"))
        try {
            GeminiApi.stream("K", "retired-model", body())
            fail()
        } catch (e: GeminiModelMissing) {
            assertEquals("Gemini HTTP 400: This model is no longer available to new users.", e.message)
        }
        try {
            GeminiApi.generate("K", "busy-model", body())
            fail()
        } catch (e: GeminiRateLimited) {
            assertEquals(429, e.code)
        }
        try {
            GeminiApi.stream("K", "other-model", body())
            fail()
        } catch (e: GeminiHttpException) {
            assertFalse(e is GeminiModelMissing || e is GeminiRateLimited)
            assertEquals("Gemini HTTP 500: Internal error", e.message)
        }
    }

    @Test
    fun `a rejected thinking level is retried once at low and remembered for the model`() = runBlocking {
        val model = "gemini-3.4-flash-test"
        val srv = serve { ex ->
            if (ex.body.contains("\"minimal\"")) {
                ex.respond(400, "{\"error\":{\"code\":400,\"message\":\"Thinking level MINIMAL is not supported for this model.\"}}")
            } else {
                ex.startEvents()
                ex.event(chunk(text("{}"), finish = "STOP"))
            }
        }
        val fast = GeminiApi.thinkingConfig(model, AiReasoning.FAST)!!
        assertEquals("minimal", fast.getString("thinkingLevel"))
        val first = body().apply { getJSONObject("generationConfig").put("thinkingConfig", fast) }
        assertEquals("{}", GeminiApi.stream("K", model, first))
        assertEquals(2, srv.exchanges.size)
        assertEquals(
            "low",
            JSONObject(srv.exchanges[1].body).getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel"),
        )
        assertEquals("the caller's body is left alone", "minimal", first.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel"))
        // Learned: the next page asks for low straight away.
        assertEquals("low", GeminiApi.thinkingConfig(model, AiReasoning.FAST)!!.getString("thinkingLevel"))
        assertEquals("high", GeminiApi.thinkingConfig(model, AiReasoning.THOROUGH)!!.getString("thinkingLevel"))
    }

    @Test
    fun `a 400 that is not about thinking is not retried`() = runBlocking {
        val srv = serve { ex -> ex.respond(400, "{\"error\":{\"code\":400,\"message\":\"Request payload size exceeds the limit\"}}") }
        try {
            GeminiApi.stream("K", "gemini-3.4-flash-test", body("minimal"))
            fail()
        } catch (e: GeminiHttpException) {
            assertEquals(400, e.code)
        }
        assertEquals(1, srv.exchanges.size)
    }

    @Test
    fun `cancelling the caller aborts a request still waiting for its first token`() = runBlocking {
        val srv = serve { ex ->
            ex.startEvents()
            ex.awaitHangUp()
        }
        val call = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
            GeminiApi.stream("K", "gemini-test-flash", body())
        }
        withTimeout(5_000) { while (srv.exchanges.isEmpty()) delay(10) }
        delay(100)
        val t0 = System.nanoTime()
        call.cancel()
        withTimeout(2_000) { call.join() }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("cancel took $ms ms", ms < 1_000)
        assertTrue(call.isCancelled)
    }

    @Test
    fun `a cancel reaches the request even with every thread taken by the read`() = runBlocking {
        val srv = serve { ex ->
            ex.startEvents()
            ex.awaitHangUp()
        }
        val call = GeminiApi.client.newCall(Request.Builder().url(srv.base + "gemini-test-flash").build())
        val response = call.execute()
        val one = Executors.newSingleThreadExecutor()
        try {
            // One thread, and the read holds it: nothing that waits to be
            // scheduled there runs until the read ends.
            val reading = CoroutineScope(one.asCoroutineDispatcher()).launch {
                LlmHttp.abortOnCancel(call) { response.body!!.source().readUtf8Line() }
            }
            delay(200)
            val t0 = System.nanoTime()
            reading.cancel()
            withTimeout(3_000) { reading.join() }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("cancel took $ms ms", ms < 1_000)
            assertTrue(call.isCanceled())
        } finally {
            response.close()
            one.shutdownNow()
        }
    }

    @Test
    fun `a read that ends on its own leaves its call alone`() = runBlocking {
        val srv = serve { ex ->
            ex.startEvents()
            ex.event(chunk(text("{}"), finish = "STOP"))
        }
        val call = GeminiApi.client.newCall(Request.Builder().url(srv.base + "gemini-test-flash").build())
        call.execute().use { response ->
            assertTrue(LlmHttp.abortOnCancel(call) { response.body!!.source().readUtf8Line() }!!.startsWith("data:"))
        }
        assertFalse(call.isCanceled())
    }

    @Test
    fun `warm looks the model up at most once a minute and never throws`() = runBlocking {
        val srv = serve { ex -> ex.respond(200, "{\"name\":\"models/gemini-test-flash\"}") }
        GeminiApi.warm("K", "gemini-test-flash")
        GeminiApi.warm("K", "gemini-test-flash")
        GeminiApi.warm("K", "gemini-test-flash")
        withTimeout(5_000) { while (srv.exchanges.isEmpty()) delay(10) }
        delay(200)
        val ex = srv.exchanges.single()
        assertEquals("GET", ex.method)
        assertEquals("/v1beta/models/gemini-test-flash", ex.target)
        assertEquals("K", ex.headers["x-goog-api-key"])

        GeminiApi.resetWarm()
        GeminiApi.base = "http://127.0.0.1:1/nothing-listens-here/"
        GeminiApi.warm("K", "gemini-test-flash")
        GeminiApi.warm("", "gemini-test-flash")
        GeminiApi.base = "not a url at all"
        GeminiApi.resetWarm()
        GeminiApi.warm("K", "gemini-test-flash")
    }

    @Test
    fun `warm learns that a model is gone before a page asks for it`() = runBlocking {
        serve { ex -> ex.respond(404, "{\"error\":{\"code\":404,\"message\":\"not found\"}}") }
        GeminiApi.warm("K", "gemini-gone")
        withTimeout(5_000) { while (!GeminiApi.isMissing("gemini-gone")) delay(10) }
    }

    @Test
    fun `a connection failure is an IOException, not a hang`() = runBlocking {
        GeminiApi.base = "http://127.0.0.1:1/v1beta/models/"
        try {
            withTimeout(15_000) { GeminiApi.stream("K", "gemini-test-flash", body()) }
            fail()
        } catch (e: IOException) {
            // expected
        }
    }
}
