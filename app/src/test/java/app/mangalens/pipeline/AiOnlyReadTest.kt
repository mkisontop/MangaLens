package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
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
import app.mangalens.translate.PendingRead
import app.mangalens.translate.StoryContext
import app.mangalens.translate.TranslationCache
import app.mangalens.translate.TranslationService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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

    private val box = Rect(220, 400, 500, 450)
    private val line = OcrLine("괜찮아. 내가 지켜줄게.", box, false)
    private val item = PageItem(Rect(box), ItemKind.SPEECH, "괜찮아. 내가 지켜줄게.", "It's okay. I've got you.")

    private val page: Bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).drawColor(Color.WHITE)
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

    private fun pass(read: PendingRead): Pair<List<TranslatePipeline.PageResult>, TranslatePipeline.PageResult> = runBlocking {
        val cache = TranslationCache()
        val pipeline = TranslatePipeline(OneLineOcr(line), TranslationService(cache), cache)
        val analysis = pipeline.analyze(page, settings)
        val partials = ArrayList<TranslatePipeline.PageResult>()
        val result = pipeline.translate(analysis, settings, onPartial = { partials += it }, read = read)
        partials to result
    }

    @Test
    fun `a page read AI-first letters only the AI's lines, each once`() {
        val ai = failingAi()
        val (partials, result) = pass(CannedRead(listOf(item)))

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

    @Test
    fun `a rejected key is an alert as well as the reason`() {
        failingAi()
        val (_, result) = pass(CannedRead(emptyList(), GeminiHttpException(401, "API key not valid")))

        assertEquals("key rejected", result.failure)
        assertEquals("Gemini rejected the API key — check it in MangaLens", result.alert)
        assertTrue(result.bubbles.isEmpty())
    }
}
