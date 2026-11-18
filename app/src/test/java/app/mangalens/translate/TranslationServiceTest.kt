package app.mangalens.translate

import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SourceLang
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Translation is the AI's alone. When the AI fails, the failure is what
 * comes back — never some other translation in its place, which the reader
 * could not tell from the AI's own — and the failure keeps its type, so the
 * pill can say whether it was the key, the network or the rate limit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationServiceTest {

    private var server: FakeHttpServer? = null

    @Before
    fun setUp() {
        GeminiApi.resetLearned()
        StoryContext.reset()
    }

    @After
    fun tearDown() {
        server?.close()
        GeminiApi.base = GeminiApi.BASE
        GeminiApi.resetLearned()
        StoryContext.reset()
    }

    private fun serve(handler: (FakeHttpServer.Exchange) -> Unit): FakeHttpServer =
        FakeHttpServer(handler).also {
            server = it
            GeminiApi.base = it.base
        }

    private fun custom(srv: FakeHttpServer) =
        AppSettings(provider = LlmProvider.CUSTOM, model = "m", customUrl = srv.base + "chat", apiKey = "k")

    @Test
    fun copiedSchemaValuesAreRetriedAndNeverShownEvenFromAnOlderCache() = runBlocking {
        var answer = "<English>"
        val srv = serve { ex ->
            val bubbles = JSONArray().put(JSONObject().put("id", 0).put("en", answer))
            val message = JSONObject().put("content", JSONObject().put("bubbles", bubbles).toString())
            ex.respond(200, JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message))).toString())
        }
        val settings = custom(srv)
        val namespace = LlmEngine(settings).cacheNamespace
        val source = listOf("괜찮아요")
        val cache = TranslationCache()
        val key = TranslationCache.key(namespace, "KO", source.single())
        cache.put(key, "<English>")
        val service = TranslationService(cache)
        assertTrue(!service.fullyCached(namespace, SourceLang.KO, source))
        assertEquals(listOf(""), service.translate(source, SourceLang.KO, settings).texts)
        assertEquals(null, JunkFilter.accept(source.single(), "<English>"))
        answer = "I study English."
        assertEquals(listOf(answer), service.translate(source, SourceLang.KO, settings).texts)
        assertEquals(answer, cache.get(key))
        assertEquals(2, srv.exchanges.size)
    }

    private val lines = listOf("괜찮아.", "내가 지켜줄게.")

    @Test
    fun `an AI that fails is reported, not answered in other words`() = runBlocking {
        val srv = serve { ex -> ex.respond(500, "{\"error\":\"down\"}") }
        try {
            TranslationService(TranslationCache()).translate(lines, SourceLang.KO, custom(srv))
            fail("expected the AI's failure")
        } catch (e: RuntimeException) {
            assertTrue(e.message, e.message.orEmpty().contains("HTTP 500"))
        }
        // The AI was asked once, and nothing else was asked at all.
        assertEquals(1, srv.exchanges.size)
    }

    @Test
    fun `a gemini rate limit comes back as itself`() = runBlocking {
        val srv = serve { ex ->
            ex.respond(429, "{\"error\":{\"code\":429,\"message\":\"Resource has been exhausted\",\"status\":\"RESOURCE_EXHAUSTED\"}}")
        }
        val settings = AppSettings(provider = LlmProvider.GEMINI, apiKey = "k", model = "gemini-flash-latest")
        try {
            TranslationService(TranslationCache()).translate(lines, SourceLang.KO, settings)
            fail("expected the rate limit")
        } catch (e: GeminiRateLimited) {
            // The type the status pill names the cause by.
        }
        assertTrue(srv.exchanges.isNotEmpty())
    }

    @Test
    fun `no key fails before anything is sent`() = runBlocking {
        val srv = serve { ex -> ex.respond(500, "{}") }
        try {
            TranslationService(TranslationCache()).translate(lines, SourceLang.KO, AppSettings(apiKey = ""))
            fail("expected no key")
        } catch (e: RuntimeException) {
            assertEquals("No API key set for Gemini", e.message)
        }
        assertEquals(0, srv.exchanges.size)
    }

    @Test
    fun `the AI's answers come back in its own words and are kept for the next pass`() = runBlocking {
        val reply = JSONObject().put(
            "bubbles",
            JSONArray()
                .put(JSONObject().put("id", 0).put("en", "It's okay.").put("kind", "dialogue"))
                .put(JSONObject().put("id", 1).put("en", "I'll protect you.").put("kind", "dialogue")),
        ).toString()
        val srv = serve { ex ->
            ex.respond(200, JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", reply)))).toString())
        }
        val service = TranslationService(TranslationCache())
        val first = service.translate(lines, SourceLang.KO, custom(srv))
        assertEquals(listOf("It's okay.", "I'll protect you."), first.texts)
        assertEquals("Custom AI", first.engineLabel)
        val again = service.translate(lines, SourceLang.KO, custom(srv))
        assertEquals(first.texts, again.texts)
        assertEquals(1, srv.exchanges.size)
    }
    @Test
    fun `stable cached lines paint before the request and split sentences wait for their context`() = runBlocking {
        val srv = serve { ex ->
            val bubbles = JSONArray()
                .put(JSONObject().put("id", 0).put("en", "Stay here."))
                .put(JSONObject().put("id", 1).put("en", "I'll be back."))
            val reply = JSONObject().put("bubbles", bubbles).toString()
            ex.respond(200, JSONObject().put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject().put("content", reply))
            )).toString())
        }
        val s = custom(srv)
        val cache = TranslationCache()
        val texts = listOf("괜찮아요", "여기 있어요", "곧 올게요")
        fun key(i: Int) = TranslationCache.key(LlmEngine(s).cacheNamespace, "KO", texts[i])
        cache.put(key(0), "It's okay.")
        cache.put(key(1), "Previous wording.")
        val frames = mutableListOf<Map<Int, String>>()
        val outcome = TranslationService(cache).translate(texts, SourceLang.KO, s,
            runs = listOf(-1, 7, 7), parts = listOf(0, 0, 1), onProgress = {
                if (frames.isEmpty()) assertTrue("first paint precedes the request", srv.exchanges.isEmpty())
                frames += it
            })
        assertEquals(mapOf(0 to "It's okay."), frames.first())
        assertEquals(listOf("It's okay.", "Stay here.", "I'll be back."), outcome.texts)
        assertEquals(1, srv.exchanges.size)
    }

    @Test
    fun `Korean word boundaries distinguish meanings and repeated spaces preserve a hit`() {
        fun key(text: String) = TranslationCache.key("fixture", "KO", text)
        val enteringRoom = "아버지가 방에 들어가신다"
        val enteringBag = "아버지 가방에 들어가신다"
        val cache = TranslationCache()
        cache.put(key(enteringRoom), "Father enters the room.")
        assertEquals(null, cache.get(key(enteringBag)))
        assertEquals("Father enters the room.", cache.get(key("  아버지가   방에\n들어가신다  ")))
    }
}
