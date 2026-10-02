package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import app.mangalens.ocr.OcrEngine
import app.mangalens.ocr.OcrLine
import app.mangalens.settings.AiVisionMode
import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SourceLang
import app.mangalens.translate.FakeHttpServer
import app.mangalens.translate.GeminiApi
import app.mangalens.translate.GeminiHttpException
import app.mangalens.translate.GeminiRateLimited
import app.mangalens.translate.ItemKind
import app.mangalens.translate.PageItem
import app.mangalens.translate.PageKey
import app.mangalens.translate.PendingRead
import app.mangalens.translate.StoryContext
import app.mangalens.translate.TranslationCache
import app.mangalens.translate.TranslationService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * Every line on the page is the AI's, and each is lettered once. A page read
 * AI-first used to paint a machine draft first and re-word it when the AI
 * landed; now the only lines that reach the screen are the ones the model
 * wrote, and when it fails the page says why instead of showing some other
 * translation in its place.
 *
 * The read is canned and on-device OCR is a stand-in, so nothing leaves the
 * machine: any other translation the pass asked for would reach the
 * loopback server standing in for the AI, and be counted there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiOnlyReadTest {

    /** OCR that reads one line, as ML Kit would off a real balloon. */
    private class OneLineOcr(private val line: OcrLine) : OcrEngine() {
        override suspend fun recognize(bitmap: Bitmap, setting: SourceLang) = Result(listOf(line), SourceLang.KO)
        override suspend fun recognizeRegion(bitmap: Bitmap, lang: SourceLang?): List<OcrLine> = emptyList()
    }

    /** OCR that reads nothing, as ML Kit does on lettering it cannot make out. */
    private class NoOcr : OcrEngine() {
        override suspend fun recognize(bitmap: Bitmap, setting: SourceLang) = Result(emptyList(), setting)
        override suspend fun recognizeRegion(bitmap: Bitmap, lang: SourceLang?): List<OcrLine> = emptyList()
    }

    /** A page read that hands over [items] and ends — or then fails with [failure]. */
    private class CannedRead(private val items: List<PageItem>, private val failure: Exception? = null) : PendingRead {
        override val isActive: Boolean get() = false

        override suspend fun collect(onItem: (suspend (PageItem) -> Unit)?): List<PageItem> {
            for (item in items) onItem?.invoke(item)
            failure?.let { throw it }
            return items
        }

        override fun cancel() = Unit
    }

    private companion object {
        const val OCR_MS = 2500L
    }

    private val box = Rect(220, 400, 500, 450)
    private val line = OcrLine("괜찮아. 내가 지켜줄게.", box, false)
    private val item = PageItem(Rect(box), ItemKind.SPEECH, "괜찮아. 내가 지켜줄게.", "It's okay. I've got you.")

    /** A page with nothing drawn where the model says the line is: only OCR can vouch for it. */
    private val page: Bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).drawColor(Color.WHITE)
    }

    /** The same page with the line lettered in black, as a real page would show it. */
    private val lettered: Bitmap = page.copy(Bitmap.Config.ARGB_8888, true).apply {
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        val c = Canvas(this)
        for (i in 0 until 8) {
            val x = box.left + 12f + i * 32f
            val y = box.top + 10f
            c.drawLine(x, y, x + 22f, y, ink)
            c.drawLine(x + 11f, y, x + 11f, y + 30f, ink)
            c.drawLine(x, y + 30f, x + 22f, y + 18f, ink)
        }
    }

    private val settings = AppSettings(provider = LlmProvider.GEMINI, apiKey = "k")

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

    /** The stand-in for the AI's text path: every request fails, and is counted. */
    private fun failingAi(code: Int = 500): FakeHttpServer =
        FakeHttpServer { ex -> ex.respond(code, "{\"error\":{\"code\":$code,\"message\":\"unavailable\"}}") }.also {
            server = it
            GeminiApi.base = it.base
        }

    private fun pass(read: PendingRead, on: Bitmap = page): Pair<List<TranslatePipeline.PageResult>, TranslatePipeline.PageResult> = runBlocking {
        val cache = TranslationCache()
        val pipeline = TranslatePipeline(OneLineOcr(line), TranslationService(cache), cache)
        val analysis = pipeline.analyze(on, settings)
        val partials = ArrayList<TranslatePipeline.PageResult>()
        val result = pipeline.translate(analysis, settings, onPartial = { partials += it }, read = read)
        partials to result
    }

    @Test
    fun `a page read AI-first letters only the AI's lines, each once`() {
        val ai = failingAi()
        val (partials, result) = pass(CannedRead(listOf(item)), on = lettered)

        assertEquals(listOf(item.en), result.bubbles.map { it.translated })
        assertTrue("the line streamed onto the page", partials.isNotEmpty())
        for (p in partials) {
            assertEquals(listOf(item.en), p.bubbles.map { it.translated })
            assertNull("no pass announces an upgrade", p.note)
        }
        assertNull(result.failure)
        assertNull(result.alert)
        // Nothing but the read was asked to translate the page.
        assertTrue(ai.exchanges.isEmpty())
    }

    @Test
    fun `a line with nothing to erase under it is lettered once OCR has read it there`() {
        // OCR is read beside the request, not before it: the line may reach
        // the screen only once OCR lands, but it is there when the read ends.
        val ai = failingAi()
        val (partials, result) = pass(CannedRead(listOf(item)))

        assertEquals(listOf(item.en), result.bubbles.map { it.translated })
        for (p in partials) assertTrue(p.bubbles.all { it.translated == item.en })
        assertNull(result.failure)
        assertTrue(ai.exchanges.isEmpty())
    }

    @Test
    fun `a slow OCR never holds back the first line`() {
        // ML Kit takes from a fraction of a second to a few on a phone.
        // Read AI-first it only backs the model up, so the first line is
        // painted the moment it streams, not once OCR is done.
        failingAi()
        val slow = object : OcrEngine() {
            override suspend fun recognize(bitmap: Bitmap, setting: SourceLang): Result {
                delay(OCR_MS)
                return Result(listOf(line), SourceLang.KO)
            }
            override suspend fun recognizeRegion(bitmap: Bitmap, lang: SourceLang?): List<OcrLine> = emptyList()
        }
        runBlocking {
            val cache = TranslationCache()
            val pipeline = TranslatePipeline(slow, TranslationService(cache), cache)
            val started = System.nanoTime()
            val analysis = pipeline.analyze(lettered, settings)
            var firstAt = -1L
            val result = pipeline.translate(analysis, settings, read = CannedRead(listOf(item)), onPartial = {
                if (firstAt < 0) firstAt = (System.nanoTime() - started) / 1_000_000
            })
            assertTrue("first line painted at $firstAt ms, with OCR taking $OCR_MS", firstAt in 0 until OCR_MS / 2)
            assertEquals(listOf(item.en), result.bubbles.map { it.translated })
        }
    }

    @Test
    fun `a read that fails keeps the AI's lines and says why, with no other translation`() {
        val ai = failingAi()
        val (partials, result) = pass(CannedRead(listOf(item), GeminiRateLimited("quota exceeded")))

        assertEquals("rate limited", result.failure)
        assertNull(result.alert)
        // The line the AI wrote before it failed stays; nothing else appears.
        assertEquals(listOf(item.en), result.bubbles.map { it.translated })
        for (p in partials) assertTrue(p.bubbles.all { it.translated == item.en })
        // What OCR read went to the AI as text, and only to the AI.
        assertTrue(ai.exchanges.isNotEmpty())
        assertTrue(ai.exchanges.all { it.target.contains(":generateContent") || it.target.contains(":streamGenerateContent") })
    }

    @Test
    fun `on the classic path an AI that fails is thrown, and nothing is painted in its place`() = runBlocking {
        val ai = FakeHttpServer { ex -> ex.respond(503, "{\"error\":\"overloaded\"}") }.also { server = it }
        val custom = AppSettings(
            provider = LlmProvider.CUSTOM, model = "m", customUrl = ai.base + "chat", aiVision = AiVisionMode.OFF,
        )
        val cache = TranslationCache()
        val pipeline = TranslatePipeline(OneLineOcr(line), TranslationService(cache), cache)
        val analysis = pipeline.analyze(page, custom)
        val partials = ArrayList<TranslatePipeline.PageResult>()
        try {
            pipeline.translate(analysis, custom, onPartial = { partials += it })
            fail("expected the AI's failure")
        } catch (e: RuntimeException) {
            assertEquals("HTTP 503", AiFailure.cause(e))
        }
        assertTrue(partials.isEmpty())
        assertEquals(1, ai.exchanges.size)
    }

    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /**
     * A page of paper holding, at each of [lines]' rows, a balloon with that
     * text in one line, and the items a model reports for them, boxes tight
     * on the lettering. Glyphs are seeded stroke patterns, as in
     * [ItemMemoryTest]: every character distinct on any JVM, whatever fonts
     * it has.
     */
    private fun balloons(vararg lines: Pair<Float, String>): Pair<Bitmap, List<PageItem>> {
        val bmp = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(236, 236, 232))
        val items = lines.map { (cy, text) ->
            val size = 26f
            val oval = RectF(210f, cy - 90, 510f, cy + 90)
            c.drawOval(oval, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            c.drawOval(oval, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.BLACK })
            val left = 360f - text.length * size / 2
            text.forEachIndexed { i, ch ->
                val rnd = Random(ch.code * 7919)
                val x = left + i * size
                val y = cy - size / 2
                repeat(4) {
                    c.drawLine(x + rnd.nextFloat() * 22, y + rnd.nextFloat() * 22, x + rnd.nextFloat() * 22, y + rnd.nextFloat() * 22, pen)
                }
            }
            val box = Rect((left - 4).toInt(), (cy - size / 2 - 4).toInt(), (left + text.length * size + 4).toInt(), (cy + size / 2 + 4).toInt())
            PageItem(box, ItemKind.SPEECH, text, "")
        }
        return bmp to items
    }

    @Test
    fun `a cached answer is replayed only onto the lettering it was written for`() = runBlocking {
        failingAi()
        val diagnosed = settings.copy(diagnostics = true)
        val cache = TranslationCache()
        val pipeline = TranslatePipeline(NoOcr(), TranslationService(cache), cache)
        suspend fun stop(on: Bitmap, answer: PageItem) =
            pipeline.translate(pipeline.analyze(on, diagnosed), diagnosed, read = CannedRead(listOf(answer)))
        fun TranslatePipeline.PageResult.replayed() = diag.orEmpty().contains("cached")
        val (first, a) = balloons(640f to "ABCD")
        val (other, b) = balloons(640f to "WXYZ")
        val why = a.single().copy(en = "Why?")
        val what = b.single().copy(en = "What?")
        // Read AI-first, OCR has read neither balloon: the key is only the
        // layout of their lettering, and it is the same.
        suspend fun key(page: Bitmap) = PageKey.regionHash(page, pipeline.analyze(page, diagnosed).bubbles.single().box)
        assertEquals(key(first), key(other))

        assertEquals(listOf("Why?"), stop(first, why).bubbles.map { it.translated })
        val again = stop(first.copy(Bitmap.Config.ARGB_8888, false), why.copy(en = "Hm?"))
        assertTrue("memory finds the lettering again: the answer is replayed", again.replayed())
        assertEquals(listOf("Why?"), again.bubbles.map { it.translated })

        val next = stop(other, what)
        assertFalse("other lettering is read, whatever the key says", next.replayed())
        assertEquals(listOf("What?"), next.bubbles.map { it.translated })
        // The key now holds the other page's answer, which memory's own
        // word for the first page's lettering tells apart.
        val back = stop(first.copy(Bitmap.Config.ARGB_8888, false), why.copy(en = "Hm?"))
        assertFalse("nor is it replayed onto the first page", back.replayed())
        assertEquals(listOf("Why?"), back.bubbles.map { it.translated })
    }

    @Test
    fun `a strip read that fails letters no half of a balloon its edge cut`() = runBlocking {
        failingAi()
        val cache = TranslationCache()
        val pipeline = TranslatePipeline(NoOcr(), TranslationService(cache), cache)
        val (before, lines) = balloons(900f to "ABCD")
        val whole = lines.single().copy(en = "Where were you? I looked everywhere.")
        pipeline.translate(pipeline.analyze(before, settings), settings, read = CannedRead(listOf(whole)))

        // Scrolled 300 rows, bringing a new balloon into view. The strip read
        // for it begins partway down the old one, and the model reads the
        // half it was shown as a line of its own before the read fails.
        val (after, now) = balloons(600f to "ABCD", 1000f to "WXYZ")
        val old = now[0].box
        val strip = Rect(0, old.centerY(), 720, 1280)
        // Boxed on the strip, as the model boxes what it is shown.
        val half = PageItem(Rect(old.left, 1, old.right, old.bottom - strip.top), ItemKind.SPEECH, "CD", "I looked everywhere.")
        val match = ScrollMatch.of(after)
        val read = StripRead(CannedRead(listOf(half), GeminiRateLimited("quota exceeded")), strip, 300, Seen(match, emptyList()), match)
        val partials = ArrayList<TranslatePipeline.PageResult>()
        val result = pipeline.translate(pipeline.analyze(after, settings), settings, onPartial = { partials += it }, read = read)

        assertEquals("rate limited", result.failure)
        // Memory holds the balloon whole, and it stays lettered whole, as
        // it was while the read streamed.
        assertEquals(listOf(whole.en), result.bubbles.map { it.translated })
        for (p in partials) assertEquals(listOf(whole.en), p.bubbles.map { it.translated })
    }

    @Test
    fun `a rejected key is an alert as well as the reason`() {
        failingAi()
        val (_, result) = pass(CannedRead(emptyList(), GeminiHttpException(401, "API key not valid")))

        assertEquals("key rejected", result.failure)
        assertEquals("Gemini rejected the API key — check it in MangaLens", result.alert)
        assertTrue(result.bubbles.isEmpty())
    }
}
