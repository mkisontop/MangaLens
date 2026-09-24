package app.mangalens.translate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.util.Base64
import app.mangalens.settings.AiReasoning
import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SourceLang
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The page reader without the network: a stand-in transport streams canned
 * replies in small pieces, as Gemini does, so what is tested is exactly
 * what the reader feels — the first balloon out before the reply is done,
 * nothing lost to a collector that arrives late, the slow request of a
 * race abandoned the moment the fast one delivers, and a failure that
 * reaches the pipeline as a failure rather than as an empty page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageReaderTest {

    private val settings = AppSettings(provider = LlmProvider.GEMINI, apiKey = "k", model = "gemini-3.8-flash")

    @Before
    fun setUp() {
        PageReader.resetCalm()
        GeminiApi.resetLearned()
        StoryContext.reset()
    }

    @After
    fun tearDown() {
        PageReader.resetCalm()
        GeminiApi.resetLearned()
        StoryContext.reset()
    }

    /**
     * Records every request and plays [behave] for it. The upload is
     * reported done at once unless [uploadMs] says how long it takes, or
     * never when that is negative. A reply that ends reports [finish] as
     * the model's reason for stopping.
     */
    private class FakeTransport(
        private val uploadMs: (call: Int) -> Long = { 0 },
        private val finish: (call: Int) -> String = { "STOP" },
        private val behave: suspend (call: Int, model: String, onDelta: suspend (String) -> Unit) -> String,
    ) : PageReader.Transport {
        val calls = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val startedAt = CopyOnWriteArrayList<Long>()
        val cancelled = AtomicInteger()

        override suspend fun stream(
            model: String,
            body: JSONObject,
            onSent: () -> Unit,
            onFinish: (String) -> Unit,
            onDelta: suspend (String) -> Unit,
        ): String {
            val n = synchronized(calls) {
                calls.add(model to body)
                startedAt.add(System.nanoTime())
                calls.size - 1
            }
            try {
                val upload = uploadMs(n)
                if (upload >= 0) {
                    if (upload > 0) delay(upload)
                    onSent()
                }
                return behave(n, model, onDelta).also { onFinish(finish(n)) }
            } catch (e: CancellationException) {
                cancelled.incrementAndGet()
                throw e
            }
        }
    }

    private fun item(box: List<Int>, kind: String, who: String, src: String, en: String): JSONObject =
        JSONObject().put("box_2d", JSONArray(box)).put("kind", kind).put("who", who).put("src", src).put("en", en)

    private fun reply(vararg items: JSONObject, terms: JSONArray? = null, characters: JSONArray? = null): String {
        val o = JSONObject().put("items", JSONArray(items.toList()))
        if (terms != null) o.put("new_terms", terms)
        if (characters != null) o.put("characters", characters)
        return o.toString()
    }

    /** Streams [text] in [size]-character pieces, as the model writes it. */
    private suspend fun streamOut(text: String, onDelta: suspend (String) -> Unit, size: Int = 9, pause: Long = 0): String {
        for (piece in text.chunked(size)) {
            onDelta(piece)
            if (pause > 0) delay(pause)
        }
        return text
    }

    private fun page(w: Int = 800, h: Int = 1200): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    private val hello = item(listOf(100, 200, 300, 400), "speech", "Kaito", "こんにちは", "Hello.")
    private val bye = item(listOf(500, 100, 700, 300), "speech", "Rin", "さようなら", "Goodbye.")
    private val bam = item(listOf(800, 600, 900, 900), "sfx", "", "ドン", "BAM")

    private fun reader(
        transport: FakeTransport,
        race: Int = 2,
        hedgeMs: Long = 10_000,
        s: AppSettings = settings,
        staggerMs: Long = 10_000,
        uploadGraceMs: Long = 10_000,
        retryMs: Long = 10_000,
    ) = PageReader(s, null, null, transport, race, hedgeMs, staggerMs, uploadGraceMs, retryMs)

    // ---- streaming and parsing ----

    @Test
    fun `items stream out as they close, before the reply is done, in page pixels`() = runBlocking {
        val firstSeen = CompletableDeferred<Unit>()
        val text = reply(hello, bye)
        val transport = FakeTransport { _, _, onDelta ->
            val cut = text.indexOf("}", text.indexOf("Hello.")) + 1
            streamOut(text.substring(0, cut), onDelta)
            // The rest is only written once the first item has been painted.
            withTimeout(5_000) { firstSeen.await() }
            streamOut(text.substring(cut), onDelta)
        }
        val seen = ArrayList<PageItem>()
        val items = withTimeout(10_000) {
            reader(transport, race = 1).read(page(), SourceLang.JA) {
                seen += it
                firstSeen.complete(Unit)
            }
        }
        assertEquals(listOf("Hello.", "Goodbye."), items.map { it.en })
        assertEquals(items, seen)
        val first = items[0]
        // box_2d is [ymin, xmin, ymax, xmax] of 1000 against the original 800x1200 page.
        assertEquals(Rect(160, 120, 320, 360), first.box)
        assertEquals(ItemKind.SPEECH, first.kind)
        assertEquals("Kaito", first.who)
        assertEquals("こんにちは", first.src)
        assertEquals(null, first.textColor)
        assertEquals(null, first.outlineColor)
    }

    @Test
    fun `junk is dropped - blank English, no area, repeats - and boxes are clamped to the page`() = runBlocking {
        val text = reply(
            hello,
            item(listOf(10, 10, 50, 50), "speech", "", "…", " "),
            item(listOf(300, 300, 300, 500), "speech", "", "あ", "Ah."),
            hello,
            item(listOf(101, 201, 299, 401), "speech", "Kaito", "こんにちは", "Hello."),
            item(listOf(-40, 900, 120, 1300), "art_text", "", "看板", "Sign"),
            item(listOf(700, 300, 500, 100), "narration", "", "翌日", "The next day."),
            bam,
        )
        val transport = FakeTransport { _, _, onDelta -> streamOut(text, onDelta) }
        val items = reader(transport, race = 1).read(page(), SourceLang.JA)
        assertEquals(listOf("Hello.", "Sign", "The next day.", "BAM"), items.map { it.en })
        assertEquals(Rect(720, 0, 800, 144), items[1].box)
        // Swapped corners are read the right way round.
        assertEquals(Rect(80, 600, 240, 840), items[2].box)
        assertEquals(ItemKind.ART_TEXT, items[1].kind)
        assertEquals(ItemKind.NARRATION, items[2].kind)
        assertEquals(ItemKind.SFX, items[3].kind)
    }

    @Test
    fun `a reply the stream could not split still yields its items once complete`() = runBlocking {
        // The word "items" as a value ahead of the key defeats the streaming
        // parser; the full parse of the finished reply still finds them.
        val text = "{\"note\":\"items\",\"n\":1,\"items\":[" + hello + "," + bye + "]}"
        val transport = FakeTransport { _, _, onDelta -> streamOut(text, onDelta) }
        val items = reader(transport, race = 1).read(page(), SourceLang.JA)
        assertEquals(listOf("Hello.", "Goodbye."), items.map { it.en })
    }

    @Test
    fun `a reply cut off mid-array keeps every item that closed`() = runBlocking {
        val text = reply(hello, bye)
        val cut = text.indexOf("Goodbye")
        val transport = FakeTransport { _, _, onDelta -> streamOut(text.substring(0, cut), onDelta) }
        val read = reader(transport, race = 1).start(this, page(), SourceLang.JA)
        assertEquals(listOf("Hello."), read.collect().map { it.en })
        assertTrue("broken-off JSON is a cut-off read", read.cutOff)
    }

    @Test
    fun `a reply stopped part-way is shown, and marked cut off`() = runBlocking {
        // Two items stream out, then the model is stopped before it closes the reply.
        val text = reply(hello, bye)
        val cut = text.indexOf("}", text.indexOf("Goodbye.")) + 1
        for (reason in listOf("SAFETY", "PROHIBITED_CONTENT", "RECITATION", "MAX_TOKENS", "BLOCKLIST", "SPII", "OTHER")) {
            val transport = FakeTransport(finish = { reason }) { _, _, onDelta -> streamOut(text.substring(0, cut), onDelta) }
            val read = reader(transport, race = 1).start(this, page(), SourceLang.JA)
            assertEquals(reason, listOf("Hello.", "Goodbye."), read.collect().map { it.en })
            assertTrue(reason, read.cutOff)
            assertTrue(read.summary, read.summary.contains("cut off: $reason"))
        }
    }

    @Test
    fun `a reply that closes but did not finish is cut off too`() = runBlocking {
        val transport = FakeTransport(finish = { "MAX_TOKENS" }) { _, _, onDelta -> streamOut(reply(hello, bye), onDelta) }
        val read = reader(transport, race = 1).start(this, page(), SourceLang.JA)
        assertEquals(2, read.collect().size)
        assertTrue(read.cutOff)
    }

    @Test
    fun `a finished reply is whole, whether or not the stream named its finish`() = runBlocking {
        for (reason in listOf("STOP", "")) {
            val transport = FakeTransport(finish = { reason }) { _, _, onDelta -> streamOut(reply(hello, bye), onDelta) }
            val read = reader(transport, race = 1).start(this, page(), SourceLang.JA)
            assertEquals(2, read.collect().size)
            assertFalse("finish '$reason'", read.cutOff)
            assertFalse(read.summary, read.summary.contains("cut off"))
        }
    }

    @Test
    fun `a cut-off page teaches the series memory nothing`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val glossary = GlossaryStore(context).apply { setScope("reader-test-cut-off") }
        val text = reply(hello, terms = JSONArray().put(JSONObject().put("src", "海斗").put("en", "Kaito")))
        val transport = FakeTransport(finish = { "MAX_TOKENS" }) { _, _, onDelta -> streamOut(text, onDelta) }
        val read = PageReader(settings, glossary, null, transport, 1, 10_000).start(this, page(), SourceLang.JA)
        assertEquals(listOf("Hello."), read.collect().map { it.en })
        assertTrue(read.cutOff)
        assertTrue(glossary.snapshot().isEmpty())
        assertTrue(StoryContext.snapshot().isEmpty())
        glossary.clear()
    }

    /** One `streamGenerateContent` event carrying [text], and [finish] when given. */
    private fun event(text: String, finish: String? = null): String {
        val candidate = JSONObject().put(
            "content",
            JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", text))),
        )
        if (finish != null) candidate.put("finishReason", finish)
        return JSONObject().put("candidates", JSONArray().put(candidate)).toString()
    }

    @Test
    fun `a reply Google stops part-way is cut off through the real client`() = runBlocking {
        val text = reply(hello, bye)
        val cut = text.indexOf("}", text.indexOf("Hello.")) + 1
        val server = FakeHttpServer { ex ->
            ex.startEvents()
            ex.event(event(text.substring(0, cut)))
            ex.event(event(text.substring(cut, cut + 6), finish = "PROHIBITED_CONTENT"))
        }
        GeminiApi.base = server.base
        try {
            val read = PageReader(settings).start(this, page(), SourceLang.JA)
            assertEquals(listOf("Hello."), withTimeout(10_000) { read.collect() }.map { it.en })
            assertTrue(read.summary, read.cutOff)
            assertTrue(read.summary, read.summary.contains("cut off: PROHIBITED_CONTENT"))
        } finally {
            server.close()
            GeminiApi.base = GeminiApi.BASE
        }
    }

    @Test
    fun `a reply with nothing readable is a failure, a page with no text is not`() = runBlocking {
        val garbage = FakeTransport { _, _, onDelta -> streamOut("I cannot help with that.", onDelta) }
        try {
            reader(garbage, race = 1).read(page(), SourceLang.JA)
            fail("expected a failure")
        } catch (e: RuntimeException) {
            assertTrue(e.message!!.contains("no readable items"))
        }
        val empty = FakeTransport { _, _, onDelta -> streamOut("{\"items\":[]}", onDelta) }
        assertEquals(emptyList<PageItem>(), reader(empty, race = 1).read(page(), SourceLang.JA))
    }

    // ---- replay ----

    @Test
    fun `items read before anyone collects are replayed, to every collector`() = runBlocking {
        val text = reply(hello, bye, bam)
        val transport = FakeTransport { _, _, onDelta -> streamOut(text, onDelta) }
        val read = reader(transport, race = 1).start(this, page(), SourceLang.JA)
        withTimeout(5_000) { while (read.isActive) delay(5) }
        val first = ArrayList<String>()
        val second = ArrayList<String>()
        assertEquals(3, read.collect { first += it.en }.size)
        assertEquals(3, read.collect { second += it.en }.size)
        assertEquals(listOf("Hello.", "Goodbye.", "BAM"), first)
        assertEquals(first, second)
        assertFalse(read.isActive)
    }

    @Test
    fun `a collector that joins mid-stream gets the earlier items first, then the rest`() = runBlocking {
        val text = reply(hello, bye, bam)
        val release = CompletableDeferred<Unit>()
        val transport = FakeTransport { _, _, onDelta ->
            val cut = text.indexOf("}", text.indexOf("Hello.")) + 1
            streamOut(text.substring(0, cut), onDelta)
            release.await()
            streamOut(text.substring(cut), onDelta)
        }
        val read = reader(transport, race = 1).start(this, page(), SourceLang.JA)
        withTimeout(5_000) { while (read.firstItemMs == null) delay(5) }
        assertTrue(read.isActive)
        val seen = ArrayList<String>()
        val all = withTimeout(5_000) {
            read.collect {
                seen += it.en
                release.complete(Unit)
            }
        }
        assertEquals(listOf("Hello.", "Goodbye.", "BAM"), seen)
        assertEquals(3, all.size)
    }

    // ---- racing and hedging ----

    @Test
    fun `the first request to deliver an item wins and the other is cancelled`() = runBlocking {
        val transport = FakeTransport { call, _, onDelta ->
            if (call == 0) {
                delay(3_000)
                streamOut(reply(bye), onDelta)
            } else {
                delay(50)
                streamOut(reply(hello), onDelta, pause = 2)
            }
        }
        val t0 = System.nanoTime()
        val read = reader(transport, race = 2).start(this, page(), SourceLang.JA)
        val items = read.collect()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(listOf("Hello."), items.map { it.en })
        assertEquals(2, transport.calls.size)
        assertEquals(1, transport.cancelled.get())
        assertTrue("took $ms ms", ms < 2_000)
        assertTrue(read.summary, read.summary.contains("2 requests"))
    }

    @Test
    fun `the duplicate follows once the first request's page is uploaded`() = runBlocking {
        val transport = FakeTransport(uploadMs = { if (it == 0) 300 else 0 }) { call, _, onDelta ->
            if (call == 0) awaitCancellation()
            streamOut(reply(hello), onDelta)
        }
        val t0 = System.nanoTime()
        reader(transport, race = 2).read(page(), SourceLang.JA)
        assertEquals(2, transport.calls.size)
        val lag = (transport.startedAt[1] - transport.startedAt[0]) / 1_000_000
        assertTrue("duplicate after $lag ms", lag >= 300)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }

    @Test
    fun `the duplicate goes anyway when the upload never reports back`() = runBlocking {
        val transport = FakeTransport(uploadMs = { if (it == 0) -1 else 0 }) { call, _, onDelta ->
            if (call == 0) awaitCancellation()
            streamOut(reply(hello), onDelta)
        }
        reader(transport, race = 2, staggerMs = 200).read(page(), SourceLang.JA)
        assertEquals(2, transport.calls.size)
        val lag = (transport.startedAt[1] - transport.startedAt[0]) / 1_000_000
        assertTrue("duplicate after $lag ms", lag in 200..1_500)
    }

    @Test
    fun `a first request that fails before its upload is done sends the duplicate at once`() = runBlocking {
        val transport = FakeTransport(uploadMs = { -1 }) { call, _, onDelta ->
            if (call == 0) throw IOException("connection reset")
            streamOut(reply(hello), onDelta)
        }
        val t0 = System.nanoTime()
        assertEquals(listOf("Hello."), reader(transport, race = 2).read(page(), SourceLang.JA).map { it.en })
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }

    @Test
    fun `a first request refused outright is not raced again`() = runBlocking {
        val transport = FakeTransport(uploadMs = { -1 }) { _, _, _ -> throw GeminiBlocked("SAFETY") }
        try {
            reader(transport, race = 2).read(page(), SourceLang.JA)
            fail()
        } catch (e: GeminiBlocked) {
            assertEquals(1, transport.calls.size)
        }
    }

    @Test
    fun `a race winner that fails mid-page fails the read`() = runBlocking {
        val transport = FakeTransport { call, _, onDelta ->
            if (call == 0) {
                val text = reply(hello, bye)
                delay(50)
                streamOut(text.substring(0, text.indexOf("Goodbye")), onDelta)
                throw IOException("connection reset")
            } else {
                awaitCancellation()
            }
        }
        val seen = ArrayList<String>()
        try {
            reader(transport, race = 2).read(page(), SourceLang.JA) { seen += it.en }
            fail("expected the winner's failure")
        } catch (e: IOException) {
            assertEquals("connection reset", e.message)
        }
        assertEquals(listOf("Hello."), seen)
        assertEquals(1, transport.cancelled.get())
    }

    @Test
    fun `a race is only lost when every request fails`() = runBlocking {
        val transport = FakeTransport { call, _, onDelta ->
            if (call == 0) throw GeminiHttpException(503, "The model is overloaded.")
            delay(100)
            streamOut(reply(hello), onDelta)
        }
        assertEquals(listOf("Hello."), reader(transport, race = 2).read(page(), SourceLang.JA).map { it.en })
    }

    @Test
    fun `an overloaded model is stood in for at once, and passed over for the pages after`() = runBlocking {
        val transport = FakeTransport { _, model, onDelta ->
            if (model == settings.model) throw GeminiHttpException(503, "The model is overloaded.")
            streamOut(reply(hello), onDelta)
        }
        val read = withTimeout(5_000) {
            reader(transport, race = 2, staggerMs = 0, retryMs = 5_000).start(this, page(), SourceLang.JA)
        }
        assertEquals(listOf("Hello."), read.collect().map { it.en })
        val models = transport.calls.map { it.first }
        assertEquals(listOf(settings.model, settings.model, "gemini-3.6-flash"), models)
        val lag = (transport.startedAt[2] - transport.startedAt[1]) / 1_000_000
        assertTrue("the stand-in went $lag ms after the overload, without the retry's pause", lag < 2_000)
        assertTrue(read.summary, read.summary.contains("gemini-3.6-flash"))
        // Its request is built for the stand-in: no "minimal" level it would refuse.
        val config = transport.calls[2].second.getJSONObject("generationConfig")
        assertEquals(GeminiApi.thinkingConfig("gemini-3.6-flash", settings.aiReasoning)?.toString(), config.optJSONObject("thinkingConfig")?.toString())

        val next = FakeTransport { _, _, onDelta -> streamOut(reply(bye), onDelta) }
        reader(next, race = 1).read(page(), SourceLang.JA)
        assertEquals("the next page starts on the stand-in", listOf("gemini-3.6-flash"), next.calls.map { it.first })
    }

    @Test
    fun `a stand-in Google has retired is passed over`() = runBlocking {
        val transport = FakeTransport { _, model, onDelta ->
            when (model) {
                settings.model -> throw GeminiHttpException(503, "The model is overloaded.")
                "gemini-3.6-flash" -> throw GeminiModelMissing(model, 404, "no longer available")
                else -> streamOut(reply(hello), onDelta)
            }
        }
        val items = withTimeout(5_000) { reader(transport, race = 2, staggerMs = 0, retryMs = 5_000).read(page(), SourceLang.JA) }
        assertEquals(listOf("Hello."), items.map { it.en })
        assertEquals("gemini-3.5-flash", transport.calls.last().first)
    }

    @Test
    fun `an overload that lasts walks down every stand-in, then fails the read`() = runBlocking {
        val transport = FakeTransport { _, _, _ -> throw GeminiHttpException(503, "The model is overloaded.") }
        try {
            withTimeout(5_000) { reader(transport, race = 2, staggerMs = 0, retryMs = 50).read(page(), SourceLang.JA) }
            fail("expected the overload to fail the read")
        } catch (e: GeminiHttpException) {
            assertEquals(503, e.code)
        }
        assertEquals(
            listOf(
                settings.model, settings.model,
                "gemini-3.6-flash", "gemini-3.5-flash", "gemini-flash-lite-latest", "gemini-flash-lite-latest",
            ),
            transport.calls.map { it.first },
        )
    }

    @Test
    fun `a dropped connection is tried again after a pause`() = runBlocking {
        val transport = FakeTransport { call, _, onDelta ->
            if (call < 2) throw IOException("unexpected end of stream")
            streamOut(reply(hello), onDelta)
        }
        val items = withTimeout(5_000) {
            reader(transport, race = 2, staggerMs = 0, retryMs = 150).read(page(), SourceLang.JA)
        }
        assertEquals(listOf("Hello."), items.map { it.en })
        assertEquals(3, transport.calls.size)
        assertTrue("the same model is asked again", transport.calls.all { it.first == settings.model })
        val lag = (transport.startedAt[2] - transport.startedAt[1]) / 1_000_000
        assertTrue("retried $lag ms after the last failure", lag >= 150)
    }

    @Test
    fun `a rate limit is not retried`() = runBlocking {
        val transport = FakeTransport { _, _, _ -> throw GeminiRateLimited("quota") }
        try {
            withTimeout(5_000) { reader(transport, race = 2, staggerMs = 0, retryMs = 50).read(page(), SourceLang.JA) }
            fail("expected the rate limit to fail the read")
        } catch (e: GeminiRateLimited) {
            assertEquals(429, e.code)
        }
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun `a lone request that shows nothing is hedged with a duplicate`() = runBlocking {
        val transport = FakeTransport { call, _, onDelta ->
            if (call == 0) awaitCancellation()
            streamOut(reply(hello), onDelta)
        }
        val t0 = System.nanoTime()
        val items = withTimeout(5_000) { reader(transport, race = 1, hedgeMs = 150).read(page(), SourceLang.JA) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(listOf("Hello."), items.map { it.en })
        assertEquals(2, transport.calls.size)
        assertEquals("the stalled request is abandoned", 1, transport.cancelled.get())
        assertTrue("hedged after $ms ms", ms >= 150)
    }

    @Test
    fun `a lone request's hedge is timed from the end of its upload`() = runBlocking {
        // Data saver: one request, and a slow uplink that takes 400 ms to send the page.
        val transport = FakeTransport(uploadMs = { if (it == 0) 400 else 0 }) { call, _, onDelta ->
            if (call == 0) awaitCancellation()
            streamOut(reply(hello), onDelta)
        }
        val saver = settings.copy(dataSaver = true)
        val items = withTimeout(5_000) { reader(transport, race = 2, hedgeMs = 200, s = saver).read(page(), SourceLang.JA) }
        assertEquals(listOf("Hello."), items.map { it.en })
        assertEquals(2, transport.calls.size)
        val lag = (transport.startedAt[1] - transport.startedAt[0]) / 1_000_000
        assertTrue("hedged $lag ms after the first request, upload included", lag >= 600)
    }

    @Test
    fun `a lone request's hedge goes anyway when its upload never reports back`() = runBlocking {
        val transport = FakeTransport(uploadMs = { if (it == 0) -1 else 0 }) { call, _, onDelta ->
            if (call == 0) awaitCancellation()
            streamOut(reply(hello), onDelta)
        }
        val saver = settings.copy(dataSaver = true)
        val items = withTimeout(5_000) {
            reader(transport, race = 2, hedgeMs = 150, s = saver, uploadGraceMs = 250).read(page(), SourceLang.JA)
        }
        assertEquals(listOf("Hello."), items.map { it.en })
        assertEquals(2, transport.calls.size)
        val lag = (transport.startedAt[1] - transport.startedAt[0]) / 1_000_000
        // The hedge plus the grace (400 ms), where the hedge alone would be 150.
        assertTrue("hedged after $lag ms", lag in 350..2_000)
    }

    @Test
    fun `a lone request that answers in time is not hedged`() = runBlocking {
        val transport = FakeTransport { _, _, onDelta ->
            delay(50)
            streamOut(reply(hello), onDelta)
        }
        reader(transport, race = 1, hedgeMs = 400).read(page(), SourceLang.JA)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `a lone request that fails on the network is hedged at once`() = runBlocking {
        val transport = FakeTransport { call, _, onDelta ->
            if (call == 0) throw IOException("unexpected end of stream")
            streamOut(reply(hello), onDelta)
        }
        val t0 = System.nanoTime()
        val items = reader(transport, race = 1, hedgeMs = 10_000).read(page(), SourceLang.JA)
        assertEquals(listOf("Hello."), items.map { it.en })
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }

    @Test
    fun `a rate limit stops the racing for the pages after it`() = runBlocking {
        val first = FakeTransport { call, _, onDelta ->
            if (call == 0) throw GeminiRateLimited("Resource has been exhausted")
            delay(100)
            streamOut(reply(hello), onDelta)
        }
        assertEquals(listOf("Hello."), reader(first, race = 2).read(page(), SourceLang.JA).map { it.en })
        val next = FakeTransport { _, _, onDelta -> streamOut(reply(bye), onDelta) }
        assertEquals(listOf("Goodbye."), reader(next, race = 2).read(page(), SourceLang.JA).map { it.en })
        assertEquals("one request while calming down", 1, next.calls.size)
        PageReader.resetCalm()
        val later = FakeTransport { _, _, onDelta ->
            delay(50)
            streamOut(reply(bye), onDelta)
        }
        reader(later, race = 2).read(page(), SourceLang.JA)
        assertEquals(2, later.calls.size)
    }

    @Test
    fun `a lone rate-limited request fails the read`() = runBlocking {
        val transport = FakeTransport { _, _, _ -> throw GeminiRateLimited("Resource has been exhausted") }
        try {
            reader(transport, race = 1).read(page(), SourceLang.JA)
            fail()
        } catch (e: GeminiRateLimited) {
            assertEquals(1, transport.calls.size)
        }
    }

    @Test
    fun `data saver sends one request, not a race`() = runBlocking {
        val transport = FakeTransport { _, _, onDelta -> streamOut(reply(hello), onDelta) }
        reader(transport, race = 2, s = settings.copy(dataSaver = true)).read(page(), SourceLang.JA)
        assertEquals(1, transport.calls.size)
    }

    // ---- failures ----

    @Test
    fun `a missing model falls back to the newest flash`() = runBlocking {
        val transport = FakeTransport { _, model, onDelta ->
            if (model == "gemini-2.5-flash-lite") throw GeminiModelMissing(model, 404, "no longer available")
            streamOut(reply(hello), onDelta)
        }
        val read = reader(transport, s = settings.copy(model = "gemini-2.5-flash-lite"))
            .start(this, page(), SourceLang.JA)
        assertEquals(listOf("Hello."), read.collect().map { it.en })
        val models = transport.calls.map { it.first }
        assertEquals(listOf("gemini-2.5-flash-lite", "gemini-2.5-flash-lite"), models.take(2))
        assertEquals(GeminiApi.FALLBACK_MODEL, models.last())
        // The fallback's request is built for the fallback: thinking levels, not a budget.
        val config = transport.calls.last().second.getJSONObject("generationConfig")
        assertEquals("low", config.getJSONObject("thinkingConfig").getString("thinkingLevel"))
        assertFalse(config.has("temperature"))
        assertTrue(read.summary, read.summary.contains(GeminiApi.FALLBACK_MODEL))
    }

    @Test
    fun `the fallback model failing too is the read's failure`() = runBlocking {
        val transport = FakeTransport { _, model, _ -> throw GeminiModelMissing(model, 404, "not found") }
        try {
            reader(transport, s = settings.copy(model = "")).read(page(), SourceLang.JA)
            fail()
        } catch (e: GeminiModelMissing) {
            assertTrue(transport.calls.all { it.first == GeminiApi.FALLBACK_MODEL })
        }
    }

    @Test
    fun `a refusal reaches the collector as GeminiBlocked`() = runBlocking {
        val transport = FakeTransport { call, _, _ ->
            if (call == 0) throw GeminiRateLimited("slow down")
            throw GeminiBlocked("PROHIBITED_CONTENT")
        }
        try {
            reader(transport).read(page(), SourceLang.JA)
            fail()
        } catch (e: GeminiBlocked) {
            assertEquals("PROHIBITED_CONTENT", e.reason)
        }
    }

    @Test
    fun `cancel aborts the request and ends the read`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val transport = FakeTransport { _, _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }
        val read = reader(transport, race = 1).start(this, page(), SourceLang.JA)
        withTimeout(5_000) { started.await() }
        assertTrue(read.isActive)
        read.cancel()
        assertFalse(read.isActive)
        withTimeout(5_000) { while (transport.cancelled.get() == 0) delay(5) }
        try {
            read.collect()
            fail()
        } catch (e: CancellationException) {
            // expected
        }
    }

    // ---- memory ----

    @Test
    fun `a finished page teaches the glossary, the cast and the story`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val glossary = GlossaryStore(context).apply { setScope("reader-test") }
        val cast = CastBook(context).apply { setScope("reader-test") }
        val text = reply(
            hello, bam,
            terms = JSONArray().put(JSONObject().put("src", "海斗").put("en", "Kaito")),
            characters = JSONArray().put(
                JSONObject().put("name", "Kaito").put("pronoun", "he").put("register", "blunt").put("note", "")
            ),
        )
        val transport = FakeTransport { _, _, onDelta -> streamOut(text, onDelta) }
        PageReader(settings, glossary, cast, transport, 1, 10_000).read(page(), SourceLang.JA)
        assertEquals("Kaito", glossary.snapshot()["海斗"])
        assertEquals("he", cast.snapshot()["Kaito"]?.pronoun)
        assertEquals(listOf("Kaito: Hello."), StoryContext.snapshot())

        // What the model knew goes up with the next page, stable part first.
        val next = FakeTransport { _, _, onDelta -> streamOut(reply(bye), onDelta) }
        PageReader(settings, glossary, cast, next, 1, 10_000).read(page(), SourceLang.JA)
        val parts = next.calls.single().second.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        val stable = JSONObject(parts.getJSONObject(0).getString("text"))
        assertEquals("Kaito", stable.getJSONObject("glossary").getString("海斗"))
        assertTrue(stable.getJSONObject("characters").getString("Kaito").startsWith("he"))
        val pageText = JSONObject(parts.getJSONObject(2).getString("text"))
        assertEquals("Kaito: Hello.", pageText.getJSONArray("story_so_far").getString(0))
        assertEquals("Japanese", pageText.getString("expected_source_language"))
        glossary.clear()
        cast.clear()
    }

    @Test
    fun `a failed page teaches nothing`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val glossary = GlossaryStore(context).apply { setScope("reader-test-failed") }
        val transport = FakeTransport { _, _, onDelta ->
            streamOut(reply(hello), onDelta)
            throw IOException("reset")
        }
        try {
            PageReader(settings, glossary, null, transport, 1, 10_000).read(page(), SourceLang.JA)
            fail()
        } catch (e: IOException) {
            assertTrue(glossary.snapshot().isEmpty())
            assertTrue(StoryContext.snapshot().isEmpty())
        }
    }

    // ---- the request ----

    @Test
    fun `the request is stable-first with a lean schema and the page as a JPEG`() = runBlocking {
        val transport = FakeTransport { _, _, onDelta -> streamOut(reply(hello), onDelta) }
        reader(transport, race = 1).read(page(1000, 3000), SourceLang.KO)
        val body = transport.calls.single().second
        val system = body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(system.contains("ONE item per balloon"))
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(3, parts.length())
        assertTrue(JSONObject(parts.getJSONObject(0).getString("text")).has("glossary"))
        assertEquals("image/jpeg", parts.getJSONObject(1).getJSONObject("inlineData").getString("mimeType"))
        assertEquals("Korean", JSONObject(parts.getJSONObject(2).getString("text")).getString("expected_source_language"))

        val config = body.getJSONObject("generationConfig")
        assertEquals("application/json", config.getString("responseMimeType"))
        val schema = config.getJSONObject("responseSchema")
        assertEquals(JSONArray(listOf("items", "new_terms", "characters")).toString(), schema.getJSONArray("propertyOrdering").toString())
        val itemProps = schema.getJSONObject("properties").getJSONObject("items").getJSONObject("items")
        assertEquals(
            JSONArray(listOf("box_2d", "kind", "who", "src", "en")).toString(),
            itemProps.getJSONArray("propertyOrdering").toString(),
        )
        assertEquals(setOf("box_2d", "kind", "who", "src", "en"), itemProps.getJSONObject("properties").keys().asSequence().toSet())
        assertFalse(body.has("safetySettings"))
        assertFalse(body.has("serviceTier"))
        assertEquals("low", config.getJSONObject("thinkingConfig").getString("thinkingLevel"))
    }

    @Test
    fun `a strip names the rows it newly shows, and a page names none`() = runBlocking {
        val transport = FakeTransport { _, _, onDelta -> streamOut(reply(hello), onDelta) }
        coroutineScope { reader(transport, race = 1).start(this, page(800, 600), SourceLang.JA, intArrayOf(380, 1000)).collect(null) }
        reader(transport, race = 1).read(page(), SourceLang.JA)
        fun pageText(n: Int) = JSONObject(
            transport.calls[n].second.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(2).getString("text"),
        )
        assertEquals(JSONArray(listOf(380, 1000)).toString(), pageText(0).getJSONArray("unread_rows").toString())
        assertFalse(pageText(1).has("unread_rows"))
        val system = transport.calls[0].second.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue("the prompt says what the rows mean", system.contains("\"unread_rows\": [top, bottom]"))
    }

    private fun sentSize(transport: FakeTransport): Pair<Int, Int> {
        val data = transport.calls.last().second.getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(1).getJSONObject("inlineData").getString("data")
        val bytes = Base64.decode(data, Base64.DEFAULT)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        assertEquals("image/jpeg", opts.outMimeType)
        return opts.outWidth to opts.outHeight
    }

    @Test
    fun `the page goes up at most 1280 px long, 1024 with data saver, never enlarged`() = runBlocking {
        val transport = FakeTransport { _, _, onDelta -> streamOut(reply(hello), onDelta) }
        reader(transport, race = 1).read(page(1000, 3000), SourceLang.JA)
        assertEquals(426 to 1280, sentSize(transport))
        reader(transport, race = 1, s = settings.copy(dataSaver = true)).read(page(1000, 3000), SourceLang.JA)
        assertEquals(341 to 1024, sentSize(transport))
        reader(transport, race = 1).read(page(400, 600), SourceLang.JA)
        assertEquals(400 to 600, sentSize(transport))
    }

    @Test
    fun `the bitmap may be recycled as soon as start returns`() = runBlocking {
        val transport = FakeTransport { _, _, onDelta ->
            delay(50)
            streamOut(reply(hello), onDelta)
        }
        val bitmap = page()
        val read = reader(transport, race = 1).start(this, bitmap, SourceLang.JA)
        bitmap.recycle()
        val items = read.collect()
        assertEquals(Rect(160, 120, 320, 360), items.single().box)
    }

    @Test
    fun `thorough reading asks for high thinking and room for it`() = runBlocking {
        val transport = FakeTransport { _, _, onDelta -> streamOut(reply(hello), onDelta) }
        reader(transport, race = 1, s = settings.copy(aiReasoning = AiReasoning.THOROUGH)).read(page(), SourceLang.JA)
        val config = transport.calls.single().second.getJSONObject("generationConfig")
        assertEquals("high", config.getJSONObject("thinkingConfig").getString("thinkingLevel"))
        assertTrue(config.getInt("maxOutputTokens") >= 32768)
    }

    @Test
    fun `timings are recorded for diagnostics`() = runBlocking {
        val transport = FakeTransport { _, _, onDelta ->
            delay(30)
            streamOut(reply(hello, bye), onDelta, pause = 5)
        }
        val read = reader(transport, race = 1).start(this, page(), SourceLang.JA)
        read.collect()
        val first = assertNotNullLong(read.firstItemMs)
        val done = assertNotNullLong(read.doneMs)
        assertTrue("first $first, done $done", first in 30..done)
        assertTrue(read.summary, read.summary.contains("1st") && read.summary.contains("done"))
    }

    private fun assertNotNullLong(v: Long?): Long {
        assertNotNull(v)
        return v!!
    }

    // ---- geometry ----

    private fun toItem(box: List<Int>, kind: String, src: String, en: String, w: Int = 1000, h: Int = 1000) =
        PageReader.toItem(item(box, kind, "", src, en), w, h)!!

    @Test
    fun `vertical is read from the box against the shape of the text`() {
        // One tall column of Japanese.
        assertTrue(toItem(listOf(100, 500, 400, 540), "speech", "じゃあどうすんだよ", "So?").vertical)
        // Five short columns: wide because it is vertical.
        assertTrue(toItem(listOf(100, 300, 160, 500), "speech", "えへへ\nオムラ\nイスで\nおねが\nいね", "Hehe").vertical)
        // The same five lines set horizontally stack tall and narrow.
        assertFalse(toItem(listOf(100, 300, 400, 360), "speech", "えへへ\nオムラ\nイスで\nおねが\nいね", "Hehe").vertical)
        // A Korean line set horizontally.
        assertFalse(toItem(listOf(100, 100, 150, 600), "speech", "괜찮아. 내가 지켜줄게.", "It's okay.").vertical)
        // Hangul needs a clear vertical shape before it counts as vertical.
        assertFalse(toItem(listOf(100, 100, 220, 200), "speech", "괜찮아", "Okay.").vertical)
        // Latin script never runs vertically.
        assertFalse(toItem(listOf(100, 100, 900, 150), "speech", "HOLA", "Hi").vertical)
    }

    @Test
    fun `a line break copied from the source's columns is set as a space`() {
        val item = toItem(
            listOf(100, 400, 250, 460), "narration", "放課後の屋上。\n二人だけの衝突。",
            "On the rooftop after school.\nA clash between just the two of them.",
        )
        assertEquals("On the rooftop after school. A clash between just the two of them.", item.en)
        // The source keeps its columns: they say how it is set.
        assertEquals("放課後の屋上。\n二人だけの衝突。", item.src)
    }

    @Test
    fun `shouting is read from the English of dialogue only`() {
        assertTrue(toItem(listOf(1, 1, 90, 90), "speech", "やめろ‼", "Stop it!!").loud)
        assertTrue(toItem(listOf(1, 1, 90, 90), "thought", "なに", "WHAT ARE YOU DOING?").loud)
        assertFalse(toItem(listOf(1, 1, 90, 90), "speech", "なに", "What?").loud)
        assertFalse(toItem(listOf(1, 1, 90, 90), "sfx", "ドン", "BAM!!").loud)
        assertFalse(toItem(listOf(1, 1, 90, 90), "speech", "OK", "OK!").loud)
    }

    // ---- recorded answers from the real model ----

    /** Recorded model answers and a pages/ directory, from MANGALENS_RECORDED; skipped without it. */
    private val scratch = File(System.getenv("MANGALENS_RECORDED").orEmpty())

    /**
     * Replays Gemini's recorded answers for the hard test pages through the
     * reader, streamed in small pieces, against the real page images: every
     * item must come out on the page, and the orientation judged from the
     * geometry must agree with the model's own verdict where it gave one.
     */
    @Test
    fun `recorded answers for the hard pages parse onto the page`() = runBlocking {
        val bench = File(scratch, "bench3.json")
        assumeTrue("recorded answers not present", bench.isFile && File(scratch, "pages").isDirectory)
        val runs = JSONArray(bench.readText())
        var judged = 0
        var agreed = 0
        var pages = 0
        for (i in 0 until runs.length()) {
            val run = runs.getJSONObject(i)
            if (run.optString("model") != "gemini-3.8-flash") continue
            val file = File(scratch, "pages/" + run.getString("page"))
            val recorded = run.optJSONArray("items") ?: continue
            if (!file.isFile) continue
            val bitmap = BitmapFactory.decodeFile(file.path) ?: continue
            pages++
            val lean = JSONArray()
            for (j in 0 until recorded.length()) {
                val o = recorded.getJSONObject(j)
                lean.put(item(List(4) { o.getJSONArray("box_2d").getInt(it) }, o.optString("kind"), o.optString("who"), o.optString("src"), o.optString("en")))
            }
            val text = JSONObject().put("items", lean).toString()
            val transport = FakeTransport { _, _, onDelta -> streamOut(text, onDelta, size = 11) }
            val streamed = ArrayList<PageItem>()
            val items = reader(transport, race = 1).read(bitmap, SourceLang.AUTO) { streamed += it }
            println("${file.name} ${bitmap.width}x${bitmap.height}: ${items.size} items")
            assertEquals(file.name, recorded.length(), items.size)
            assertEquals(items, streamed)
            for ((j, it) in items.withIndex()) {
                assertTrue(it.box.left >= 0 && it.box.top >= 0 && it.box.right <= bitmap.width && it.box.bottom <= bitmap.height)
                val o = recorded.getJSONObject(j)
                if (o.has("vertical") && Regex("[\\u3040-\\u30ff\\u4e00-\\u9fff]").containsMatchIn(it.src)) {
                    judged++
                    if (o.getBoolean("vertical") == it.vertical) agreed++
                }
                println("  ${it.kind} ${it.box.toShortString()} v=${it.vertical}/${o.opt("vertical")} loud=${it.loud} ${it.src.replace('\n', '/')} => ${it.en.replace('\n', '/')}")
            }
            bitmap.recycle()
        }
        assumeTrue("no recorded pages found", pages > 0)
        println("orientation agrees with the model on $agreed of $judged CJK items")
        assertTrue("orientation agreed on only $agreed of $judged", agreed * 10 >= judged * 8)
    }

    @Test
    fun `kana carried over from a stammer never reaches the English`() {
        assertEquals("I-I'll do my best♡", PageReader.englishOnly("I-I'll do my bestっ♡"))
        assertEquals("S-Sorry to keep you waiting...!?", PageReader.englishOnly("S-Sorry to keep you waiting...ぁ!?"))
        assertEquals("Hah... hah♡", PageReader.englishOnly("Hah... hah♡"))
        // A line with no English in it is left as the model wrote it.
        assertEquals("はぁ♡", PageReader.englishOnly("はぁ♡"))
    }

    @Test
    fun `a sound set between asterisks like a stage direction is lettered without them`() {
        assertEquals("Yaaawn...", PageReader.englishOnly("*Yaaawn*..."))
        assertEquals("sigh Fine, fine.", PageReader.englishOnly("*sigh* Fine, fine."))
        // Asterisks standing in for letters stay.
        assertEquals("What the f***?!", PageReader.englishOnly("What the f***?!"))
    }
}
