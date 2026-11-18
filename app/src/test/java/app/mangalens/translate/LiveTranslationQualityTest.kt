package app.mangalens.translate

import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SourceLang
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the same prompt, SSE parser and cache as a reader using a real model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveTranslationQualityTest {
    private data class Line(val text: String, val means: (String) -> Boolean)

    private val negation = Regex("""\b(no|not|never|don't|cannot|can't|avoid|stop|refrain)\b""")
    private fun words(text: String, pattern: String) = Regex("\\b(" + pattern + ")\\b").containsMatchIn(text)
    private fun closeDoor(text: String) =
        words(text, "close|shut") && words(text, "door") && !negation.containsMatchIn(text)

    private fun keepWindowClosed(text: String): Boolean {
        if (!words(text, "windows?")) return false
        return (words(text, "open|opening") && negation.containsMatchIn(text)) ||
            (words(text, "keep|leave|stay|remains?") && words(text, "closed|shut") && !negation.containsMatchIn(text))
    }

    private val pages = mapOf(
        SourceLang.KO to listOf(
            Line("문을 닫아.", ::closeDoor),
            Line("지금 창문을 열지 마.", ::keepWindowClosed),
            Line("열쇠가 세 개 있어.") { words(it, "three|3") && words(it, "keys") && !negation.containsMatchIn(it) },
        ),
        SourceLang.JA to listOf(
            Line("電気を消して。") { words(it, "lights?") && words(it, "off|out|extinguish") && !negation.containsMatchIn(it) },
            Line("窓を開けないで。", ::keepWindowClosed),
            Line("猫は二匹いる。") { words(it, "two|2") && words(it, "cats?") && !negation.containsMatchIn(it) },
        ),
        SourceLang.ZH to listOf(
            Line("关上门。", ::closeDoor),
            Line("别开窗。", ::keepWindowClosed),
            Line("桌上有三本书。") { words(it, "three|3") && words(it, "books") && words(it, "table|desk") && !negation.containsMatchIn(it) },
        ),
    )

    @Test
    fun realModelPreservesActionsNegationAndQuantitiesInReadingOrder() = runBlocking {
        val endpoint = System.getenv("MANGALENS_LIVE_ENDPOINT").orEmpty()
        val key = System.getenv("GEMINI_API_KEY").orEmpty()
        val reportPath = System.getenv("MANGALENS_LIVE_REPORT").orEmpty()
        assumeTrue("Run the live translation workflow to measure an actual model", reportPath.isNotBlank())
        val settings = if (endpoint.isBlank()) {
            require(key.isNotBlank()) { "Live translation needs a model endpoint or a Gemini key" }
            AppSettings(apiKey = key)
        } else {
            AppSettings(provider = LlmProvider.CUSTOM, model = "reader-fixture", customUrl = endpoint)
        }
        val report = File(reportPath)
        report.parentFile.mkdirs()
        val pagesRead = JSONArray()
        val results = JSONObject().put("provider", settings.provider.name).put("model", settings.effectiveModel())
            .put("pages", pagesRead)
        val failures = mutableListOf<String>()
        for ((lang, lines) in pages) {
            StoryContext.reset()
            val service = TranslationService(TranslationCache())
            val inputs = lines.map { it.text }
            val start = System.nanoTime()
            fun elapsed() = (System.nanoTime() - start) / 1_000_000
            var firstMs: Long? = null
            val outcome = service.translate(inputs, lang, settings, onProgress = { partial ->
                if (firstMs == null && partial.values.any { it.isNotBlank() }) firstMs = elapsed()
            })
            val fullMs = elapsed()
            val rows = JSONArray()
            for ((index, line) in lines.withIndex()) {
                val answer = outcome.texts[index]
                val passing = line.means(answer.lowercase().replace('’', '\''))
                if (!passing) failures += lang.name + ": " + line.text + " => " + answer
                rows.put(JSONObject().put("source", line.text).put("english", answer).put("passed", passing))
            }
            assertTrue(service.fullyCached(LlmEngine(settings).cacheNamespace, lang, inputs))
            val cachedStart = System.nanoTime()
            val revisit = service.translate(inputs, lang, settings)
            val cachedMicros = (System.nanoTime() - cachedStart) / 1_000
            assertEquals(outcome.texts, revisit.texts)
            assertTrue("a streamed translation reached the reader", firstMs != null)
            pagesRead.put(JSONObject().put("language", lang.name).put("first_ms", firstMs)
                .put("full_ms", fullMs).put("revisit_us", cachedMicros).put("lines", rows))
            report.writeText(results.toString(2) + "\n")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
