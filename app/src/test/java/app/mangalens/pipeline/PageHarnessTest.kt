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
import app.mangalens.translate.ItemKind
import app.mangalens.translate.PageItem
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
            val stem = file.nameWithoutExtension
            File(out, "$stem-items.json").writeText(itemsJson(pipeline.lastItems))
            render(page, result.bubbles, result.balloons, stem, out)
        }
    }

    /**
     * The live run's recorded items, replayed offline: every page in
     * MANGALENS_PAGES with a `<page>-items.json` in MANGALENS_ITEMS (as
     * [translateRealPages] writes them) goes through analysis, the resolver,
     * erasure and the overlay exactly as a live pass would, with no network
     * and no key. The same answer every time, so a change to erasure or
     * placement can be judged on its own.
     */
    @Test
    fun replayRecordedPages() {
        val dir = System.getenv("MANGALENS_PAGES")?.let(::File)
        val itemsDir = System.getenv("MANGALENS_ITEMS")?.let(::File)
        assumeTrue("MANGALENS_PAGES not set", dir != null && dir.isDirectory)
        assumeTrue("MANGALENS_ITEMS not set", itemsDir != null && itemsDir.isDirectory)
        val only = System.getenv("MANGALENS_ONLY").orEmpty()
        val out = File("build/harness").apply { mkdirs() }
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(provider = LlmProvider.GEMINI, apiKey = "replay", aiVision = AiVisionMode.AUTO, diagnostics = true)
        val pages = dir!!.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }!!
            .filter { only.isBlank() || it.name.contains(only) }
            .sortedBy { it.name }
        for (file in pages) {
            val recorded = File(itemsDir, file.nameWithoutExtension + "-items.json")
            if (!recorded.isFile) continue
            val cache = TranslationCache()
            val glossary = GlossaryStore(app)
            val cast = CastBook(app)
            val pipeline = TranslatePipeline(NoOcr(), TranslationService(cache, glossary, cast), cache, glossary, cast)
            val page = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }).apply { density = Bitmap.DENSITY_NONE }
            val items = parseItems(recorded.readText())
            val t0 = System.nanoTime()
            val analysis = runBlocking { pipeline.analyze(page, settings) }
            val resolver = ReadResolver(
                page, analysis.detected, analysis.anchorLines,
                analysis.ignoreTop, analysis.ignoreBottom, analysis.exclusions, analysis.panels,
            )
            ReadResolver.trace = { println("    [resolve] $it") }
            BalloonSeed.trace = { println("    [seed] $it") }
            val bubbles = resolver.resolve(items)
            ReadResolver.trace = null
            BalloonSeed.trace = null
            println("[replay] ${file.name}: ${items.size} items -> ${bubbles.size} cards in ${(System.nanoTime() - t0) / 1_000_000} ms")
            render(page, bubbles, analysis.detected.map { it.box }, file.nameWithoutExtension, out)
        }
    }

    /** Prints how each line was replaced and writes the translated page, the erasure alone and the placements. */
    private fun render(page: Bitmap, bubbles: List<app.mangalens.overlay.RenderBubble>, balloons: List<Rect>, stem: String, out: File) {
        for (b in bubbles) {
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
        view.setBubbles(bubbles)
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
        save(translated, File(out, "$stem.png"))
        save(pair, File(out, "$stem-pair.png"))
        // What erasure alone left of the page, and where each line was
        // lettered, for measuring against a page's ground truth.
        val cleaned = page.copy(Bitmap.Config.ARGB_8888, true).apply { density = Bitmap.DENSITY_NONE }
        view.drawCleanings(Canvas(cleaned))
        save(cleaned, File(out, "$stem-clean.png"))
        File(out, "$stem-letters.json").writeText(placementsJson(view))
        // The screen of a phone that holds the overlay to 80% (Android 12+),
        // as it was and under the veil; the cleanings alone, brought back to
        // the page's own level, for measuring the same way.
        view.setBubbles(bubbles, page)
        view.windowAlpha = 0.8f
        for (veiled in listOf(false, true)) {
            view.veiled = veiled
            val tag = if (veiled) "veil" else "80"
            view.cleaningsOnly = false
            save(screen(view, page, 0.8f, 1f), File(out, "$stem-screen$tag.png"))
            view.cleaningsOnly = true
            save(screen(view, page, 0.8f, view.screenLevel), File(out, "$stem-clean$tag.png"))
        }
        view.cleaningsOnly = false
        // Balloons found on-device, for judging what the resolver had to work with.
        val marked = page.copy(Bitmap.Config.ARGB_8888, true).apply { density = Bitmap.DENSITY_NONE }
        val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.MAGENTA }
        Canvas(marked).apply { for (r in balloons) drawRect(Rect(r), stroke) }
        save(marked, File(out, "$stem-balloons.png"))
    }

    private fun itemsJson(items: List<PageItem>): String {
        val arr = org.json.JSONArray()
        for (it in items) {
            arr.put(
                org.json.JSONObject()
                    .put("box", org.json.JSONArray(listOf(it.box.left, it.box.top, it.box.right, it.box.bottom)))
                    .put("kind", it.kind.name)
                    .put("src", it.src)
                    .put("en", it.en)
                    .put("who", it.who)
                    .put("vertical", it.vertical)
                    .put("loud", it.loud)
                    .put("text_color", it.textColor ?: org.json.JSONObject.NULL)
                    .put("outline_color", it.outlineColor ?: org.json.JSONObject.NULL),
            )
        }
        return arr.toString(1)
    }

    private fun parseItems(json: String): List<PageItem> {
        val arr = org.json.JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val b = o.getJSONArray("box")
            PageItem(
                box = Rect(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3)),
                kind = ItemKind.valueOf(o.getString("kind")),
                src = o.getString("src"),
                en = o.getString("en"),
                who = o.optString("who"),
                vertical = o.optBoolean("vertical"),
                textColor = if (o.isNull("text_color")) null else o.getInt("text_color"),
                outlineColor = if (o.isNull("outline_color")) null else o.getInt("outline_color"),
                loud = o.optBoolean("loud"),
            )
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
        // Frames named ..._y<offset>: the rows a stop newly reveals are the
        // bottom (offset - previous offset) of the frame.
        var lastY: Int? = null
        for (file in frames) {
            val page = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }).apply { density = Bitmap.DENSITY_NONE }
            val y = Regex("_y(\\d+)").find(file.nameWithoutExtension)?.groupValues?.get(1)?.toInt()
            val revealedFrom = if (y != null && lastY != null) page.height - (y - lastY!!) else 0
            lastY = y
            val t0 = System.nanoTime()
            fun ms() = (System.nanoTime() - t0) / 1_000_000
            val result = runBlocking {
                val read = pipeline.startRead(page, settings, this)
                val analysis = pipeline.analyze(page, settings)
                var first = -1L
                var firstCount = 0
                var firstNew = -1L
                val r = pipeline.translate(analysis, settings, read = read, onPartial = { p ->
                    if (first < 0 && p.bubbles.isNotEmpty()) {
                        first = ms()
                        firstCount = p.bubbles.size
                    }
                    if (firstNew < 0 && p.bubbles.any { it.box.centerY() >= revealedFrom }) firstNew = ms()
                })
                if (firstNew < 0 && r.bubbles.any { it.box.centerY() >= revealedFrom }) firstNew = ms()
                val newCards = r.bubbles.count { it.box.centerY() >= revealedFrom }
                println(
                    "[scroll] ${file.name}: first paint $first ms ($firstCount cards) · first new $firstNew ms · final ${ms()} ms · " +
                        "${r.bubbles.size} cards ($newCards new) · ${r.diag ?: ""}",
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
    /** Each placed line as JSON: how it was replaced, its boxes, and where its lettering landed. */
    private fun placementsJson(view: BubbleOverlayView): String {
        val arr = org.json.JSONArray()
        fun box(r: Rect?) = r?.let { org.json.JSONArray(listOf(it.left, it.top, it.right, it.bottom)) }
        for ((b, ink) in view.placements()) {
            arr.put(
                org.json.JSONObject()
                    .put("path", when {
                        b.balloon != null -> "balloon"
                        b.patch != null -> "erased"
                        b.style == app.mangalens.overlay.LetterStyle.SFX_NOTE -> "note"
                        else -> "card"
                    })
                    .put("style", b.style.name)
                    .put("box", box(b.box))
                    .put("balloon", box(b.balloon?.box))
                    .put("patch", box(b.patchRect))
                    .put("ink", org.json.JSONArray(listOf(ink.left, ink.top, ink.right, ink.bottom)))
                    .put("src", b.original)
                    .put("en", b.translated),
            )
        }
        return arr.toString(1)
    }

    private fun densityFor(widthPx: Int): String {
        val want = widthPx / 400f
        return listOf(1f to "mdpi", 1.5f to "hdpi", 2f to "xhdpi", 3f to "xxhdpi", 4f to "xxxhdpi")
            .minBy { kotlin.math.abs(it.first - want) }.second
    }

    /**
     * What the screen shows with [view] drawn at [alpha] over [page], channel
     * by channel as the compositor blends (a·layer + (1 − a·alpha)·page),
     * divided by [level] to bring a veiled screen back to the page's own.
     */
    private fun screen(view: BubbleOverlayView, page: Bitmap, alpha: Float, level: Float): Bitmap {
        val w = page.width
        val h = page.height
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { density = Bitmap.DENSITY_NONE }
        view.draw(Canvas(layer))
        val l = IntArray(w * h).also { layer.getPixels(it, 0, w, 0, 0, w, h) }
        val u = IntArray(w * h).also { page.getPixels(it, 0, w, 0, 0, w, h) }
        val out = IntArray(w * h) { i ->
            val a = (l[i] ushr 24) / 255f
            fun ch(s: Int): Int {
                val shown = alpha * (l[i] shr s and 0xFF) * a + (1f - alpha * a) * (u[i] shr s and 0xFF)
                return (shown / level + 0.5f).toInt().coerceIn(0, 255)
            }
            (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888).apply { density = Bitmap.DENSITY_NONE }
    }

    private fun save(bmp: Bitmap, file: File) {
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
