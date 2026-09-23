package app.mangalens.translate

import android.graphics.BitmapFactory
import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SourceLang
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reads the hard test pages through the real Gemini API and prints what
 * the reader would see: time to the first item, time to the whole page,
 * and every item. Skipped unless GEMINI_API_KEY and MANGALENS_PAGES are set and the pages are
 * on disk, so CI stays hermetic; GEMINI_MODEL picks another model, GEMINI_REASONING (FAST, BALANCED, THOROUGH) its thinking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageReaderLiveTest {

    /** A directory of raw pages, from MANGALENS_PAGES. */
    private val pages = File(System.getenv("MANGALENS_PAGES").orEmpty())

    @Test
    fun `every hard page is read with its lettering found and translated`() = runBlocking {
        val key = System.getenv("GEMINI_API_KEY").orEmpty()
        assumeTrue("GEMINI_API_KEY not set", key.isNotBlank())
        val files = pages.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            ?.sortedBy { it.name }
            .orEmpty()
        assumeTrue("no test pages in $pages", files.isNotEmpty())
        val settings = AppSettings(
            provider = LlmProvider.GEMINI,
            apiKey = key,
            model = System.getenv("GEMINI_MODEL").orEmpty(),
            aiReasoning = System.getenv("GEMINI_REASONING")
                ?.let { runCatching { app.mangalens.settings.AiReasoning.valueOf(it) }.getOrNull() }
                ?: app.mangalens.settings.AiReasoning.BALANCED,
        )
        GeminiApi.warm(key, settings.effectiveModel())
        delay(1_500)
        val reader = PageReader(settings)
        for (file in files) {
            StoryContext.reset()
            val bitmap = BitmapFactory.decodeFile(file.path) ?: continue
            val t0 = System.nanoTime()
            var firstMs = -1L
            val read = reader.start(this, bitmap, SourceLang.AUTO)
            bitmap.recycle()
            val items = read.collect {
                if (firstMs < 0) firstMs = (System.nanoTime() - t0) / 1_000_000
            }
            val totalMs = (System.nanoTime() - t0) / 1_000_000
            println("${file.name}: first item $firstMs ms, total $totalMs ms, ${items.size} items [${read.summary}]")
            for (it in items) {
                println("  ${it.kind} ${it.box.toShortString()} v=${it.vertical} who=${it.who}: ${it.src.replace('\n', '/')} => ${it.en.replace('\n', '/')}")
            }
            assertTrue("${file.name}: only ${items.size} items", items.size >= 3)
        }
    }
}
