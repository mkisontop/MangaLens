package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import app.mangalens.ocr.OcrEngine
import app.mangalens.ocr.OcrLine
import app.mangalens.overlay.BubbleOverlayView
import app.mangalens.settings.AiVisionMode
import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SourceLang
import app.mangalens.translate.CastBook
import app.mangalens.translate.GlossaryStore
import app.mangalens.translate.StoryContext
import app.mangalens.translate.TranslationCache
import app.mangalens.translate.TranslationService
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * End-to-end on real pages, against the live API: balloon detection, the
 * AI-first read, the resolver, erasure and the overlay, exactly as the
 * service runs them — except on-device OCR, which ML Kit cannot do on a
 * desktop JVM and the AI-first path does not wait for.
 *
 * Opt-in: needs GEMINI_API_KEY and MANGALENS_PAGES (a directory of page
 * images) in the environment, and is skipped otherwise so CI stays
 * hermetic. Writes the translated pages, side by side with the originals,
 * to build/harness/ and prints the timings a reader would feel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageHarnessTest {

    /** OCR that reads nothing, standing in for ML Kit. */
    private class NoOcr : OcrEngine() {
        override suspend fun recognize(bitmap: Bitmap, setting: SourceLang) = Result(emptyList(), setting)
        override suspend fun recognizeRegion(bitmap: Bitmap, lang: SourceLang?): List<OcrLine> = emptyList()
    }

    @Test
    fun translateRealPages() {
        val key = System.getenv("GEMINI_API_KEY").orEmpty()
        val dir = System.getenv("MANGALENS_PAGES")?.let(::File)
        assumeTrue("GEMINI_API_KEY not set", key.isNotBlank())
        assumeTrue("MANGALENS_PAGES not set", dir != null && dir.isDirectory)
        val only = System.getenv("MANGALENS_ONLY").orEmpty()
        val pages = dir!!.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }!!
            .filter { only.isBlank() || it.name.contains(only) }
            .sortedBy { it.name }
        val out = File("build/harness").apply { mkdirs() }
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(
            provider = LlmProvider.GEMINI,
            apiKey = key,
            model = System.getenv("MANGALENS_MODEL").orEmpty(),
            aiVision = AiVisionMode.AUTO,
            aiCleanup = System.getenv("MANGALENS_CLEANUP") == "1",
            diagnostics = true,
        )
        for (file in pages) {
            StoryContext.reset()
            val cache = TranslationCache()
            val glossary = GlossaryStore(app)
            val cast = CastBook(app)
            val pipeline = TranslatePipeline(NoOcr(), TranslationService(cache, glossary, cast), cache, glossary, cast)
            val page = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }).apply { density = Bitmap.DENSITY_NONE }
            val t0 = System.nanoTime()
            fun ms() = (System.nanoTime() - t0) / 1_000_000
            val result = runBlocking {
                val read = pipeline.startRead(page, settings, this)
                val analysis = pipeline.analyze(page, settings)
                val analysed = ms()
                var first = -1L
                var partials = 0
                val r = pipeline.translate(analysis, settings, read = read, onPartial = { p ->
                    partials++
                    if (first < 0 && p.bubbles.isNotEmpty()) first = ms()
                })
                println(
                    "[harness] ${file.name}: analysed ${analysed} ms · first paint $first ms · " +
                        "final ${ms()} ms · $partials partials · ${r.bubbles.size} cards · ${r.engineLabel} · " +
                        "${r.note ?: ""} · ${r.diag ?: ""}",
                )
                r
            }
            for (b in result.bubbles) {
                val where = when {
                    b.balloon != null -> "balloon"
                    b.patch != null -> "erased"
                    else -> "card"
                }
                println("    [${b.style} $where] ${b.original.replace('\n', ' ').take(40)} => ${b.translated}")
            }
            // Letter at the density a phone showing this page would have:
            // about 400 dp across, as on a real screen.
            RuntimeEnvironment.setQualifiers("+" + densityFor(page.width))
            val view = BubbleOverlayView(RuntimeEnvironment.getApplication()).apply { layout(0, 0, page.width, page.height) }
            view.setBubbles(result.bubbles)
            // Pixels, not density-scaled: the page and the overlay share the screen's coordinates.
            val translated = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                .apply { density = Bitmap.DENSITY_NONE }
            Canvas(translated).apply {
                drawBitmap(page, 0f, 0f, null)
                // A view with no window draws its final, fully faded-in state.
                view.draw(this)
            }
            val pair = Bitmap.createBitmap(page.width * 2 + 16, page.height, Bitmap.Config.ARGB_8888)
                .apply { density = Bitmap.DENSITY_NONE }
            Canvas(pair).apply {
                drawColor(Color.DKGRAY)
                drawBitmap(page, 0f, 0f, null)
                drawBitmap(translated, page.width + 16f, 0f, null)
            }
            val stem = file.nameWithoutExtension
            save(translated, File(out, "$stem.png"))
            save(pair, File(out, "$stem-pair.png"))
            // Balloons found on-device, for judging what the resolver had to work with.
            val marked = page.copy(Bitmap.Config.ARGB_8888, true).apply { density = Bitmap.DENSITY_NONE }
            val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.MAGENTA }
            Canvas(marked).apply { for (r in result.balloons) drawRect(Rect(r), stroke) }
            save(marked, File(out, "$stem-balloons.png"))
        }
    }

    /**
     * A webtoon read as a run of scroll stops: frames in MANGALENS_SCROLL,
     * in name order, each the same strip scrolled further. From the second
     * frame on, lettering seen on the first should paint from memory before
     * the model has answered.
     */
    @Test
    fun scrollStopsRepaintFromMemory() {
        val key = System.getenv("GEMINI_API_KEY").orEmpty()
        val dir = System.getenv("MANGALENS_SCROLL")?.let(::File)
        assumeTrue("GEMINI_API_KEY not set", key.isNotBlank())
        assumeTrue("MANGALENS_SCROLL not set", dir != null && dir.isDirectory)
        val frames = dir!!.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }!!.sortedBy { it.name }
        val out = File("build/harness").apply { mkdirs() }
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(
            provider = LlmProvider.GEMINI, apiKey = key,
            aiVision = AiVisionMode.AUTO, aiCleanup = false, diagnostics = true,
        )
        StoryContext.reset()
        val cache = TranslationCache()
        val glossary = GlossaryStore(app)
        val cast = CastBook(app)
        val pipeline = TranslatePipeline(NoOcr(), TranslationService(cache, glossary, cast), cache, glossary, cast)
        for (file in frames) {
            val page = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }).apply { density = Bitmap.DENSITY_NONE }
            val t0 = System.nanoTime()
            fun ms() = (System.nanoTime() - t0) / 1_000_000
            val result = runBlocking {
                val read = pipeline.startRead(page, settings, this)
                val analysis = pipeline.analyze(page, settings)
                var first = -1L
                var firstCount = 0
                val r = pipeline.translate(analysis, settings, read = read, onPartial = { p ->
                    if (first < 0 && p.bubbles.isNotEmpty()) {
                        first = ms()
                        firstCount = p.bubbles.size
                    }
                })
                println(
                    "[scroll] ${file.name}: first paint $first ms ($firstCount cards) · final ${ms()} ms · " +
                        "${r.bubbles.size} cards · ${r.diag ?: ""}",
                )
                r
            }
            for (b in result.bubbles) {
                val where = when {
                    b.balloon != null -> "balloon"
                    b.patch != null -> "erased"
                    else -> "card"
                }
                println("    [${b.style} $where ${b.box.toShortString()}] ${b.original.replace('\n', ' ').take(30)} => ${b.translated}")
            }
            RuntimeEnvironment.setQualifiers("+" + densityFor(page.width))
            val view = BubbleOverlayView(RuntimeEnvironment.getApplication()).apply { layout(0, 0, page.width, page.height) }
            view.setBubbles(result.bubbles)
            // Pixels, not density-scaled: the page and the overlay share the screen's coordinates.
            val translated = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                .apply { density = Bitmap.DENSITY_NONE }
            Canvas(translated).apply {
                drawBitmap(page, 0f, 0f, null)
                view.draw(this)
            }
            save(translated, File(out, "scroll-${file.nameWithoutExtension}.png"))
        }
    }

    /** The density bucket that makes a page [widthPx] wide about 400 dp across. */
    private fun densityFor(widthPx: Int): String {
        val want = widthPx / 400f
        return listOf(1f to "mdpi", 1.5f to "hdpi", 2f to "xhdpi", 3f to "xxhdpi", 4f to "xxxhdpi")
            .minBy { kotlin.math.abs(it.first - want) }.second
    }

    private fun save(bmp: Bitmap, file: File) {
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
