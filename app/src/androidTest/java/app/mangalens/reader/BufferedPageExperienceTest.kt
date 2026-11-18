package app.mangalens.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mangalens.ocr.Bubble
import app.mangalens.ocr.BalloonFinder
import app.mangalens.ocr.OcrEngine
import app.mangalens.ocr.OcrLine
import app.mangalens.overlay.BubbleOverlayView
import app.mangalens.overlay.RenderBubble
import app.mangalens.pipeline.TranslatePipeline
import app.mangalens.settings.AiVisionMode
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.AppSettings
import app.mangalens.settings.SourceLang
import app.mangalens.translate.PageReader
import app.mangalens.translate.TranslationCache
import app.mangalens.translate.TranslationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A prebuffered provider reply isolates the time the reader waits after the translations exist. */
@RunWith(AndroidJUnit4::class)
class BufferedPageExperienceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val output = File(inst.targetContext.filesDir, "reader-ux/" + InstrumentationRegistry.getArguments().getString("build", "candidate")).apply { mkdirs() }
    private val words = listOf("Look outside.", "Open the door.", "Take the map.", "Go upstairs.",
        "Come with me.", "Wait here.", "Close the gate.", "Keep walking.", "Watch the road.",
        "We made it.", "Turn left.", "Let's go home.")
    private val scripts = listOf(SourceLang.KO to "문을 열어", SourceLang.JA to "扉を開けて", SourceLang.ZH to "打开大门")

    @Test fun densePagesReachTheDisplayWithAllLinesAndIntactArt() = measure(anchoredVision = false)

    @Test fun anchoredVisionStreamsTwelveLinesOntoTheirOwnBalloons() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("compareVision") == "1")
        measure(anchoredVision = true)
    }

    private fun measure(anchoredVision: Boolean) {
        val metrics = File(output, if (anchoredVision) "anchored.csv" else "buffered.csv").apply {
            writeText("trial,script,lines,partial_layouts,first_draw_ms,full_draw_ms,source_pixels_remaining,art_max_error\n")
        }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var host: FrameLayout
            scenario.onActivity { activity ->
                activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                host = FrameLayout(activity)
                activity.setContentView(host)
            }
            inst.waitForIdleSync()
            val screenOrigin = IntArray(2)
            inst.runOnMainSync { host.getLocationOnScreen(screenOrigin) }
            for (trial in 0 until 7) {
                val (lang, text) = scripts[trial % scripts.size]
                val pg = fixture(host.width, host.height, text)
                lateinit var overlay: BubbleOverlayView
                var count = 0
                val first = AtomicLong()
                val last = AtomicLong()
                val completed = CountDownLatch(1)
                val watch = ViewTreeObserver.OnDrawListener {
                    if (count > 0) first.compareAndSet(0, SystemClock.elapsedRealtimeNanos())
                    if (count == words.size && last.compareAndSet(0, SystemClock.elapsedRealtimeNanos())) completed.countDown()
                }
                scenario.onActivity { activity ->
                    host.removeAllViews()
                    host.addView(object : View(activity) {
                        override fun onDraw(canvas: Canvas) { canvas.drawBitmap(pg.bitmap, null, Rect(0, 0, width, height), null) }
                    })
                    overlay = BubbleOverlayView(activity)
                    host.addView(overlay)
                    host.viewTreeObserver.addOnDrawListener(watch)
                }
                inst.waitForIdleSync()
                var paints = 0
                val started = AtomicLong()
                val server = if (anchoredVision) anchoredServer(text, started) else null
                val cache = TranslationCache()
                val ocr = OcrEngine()
                try {
                    val pipeline = TranslatePipeline(ocr, TranslationService(cache), cache)
                    val scan = BalloonFinder.analyze(pg.bitmap, 0, 0, emptyList())
                    val anchors = pg.regions.map { OcrLine(text, it, false) }
                    val bubbles = if (anchoredVision) anchors.map { Bubble(it.text, it.box, it.vertical) } else emptyList()
                    val analysis = TranslatePipeline.Analysis(pg.bitmap, OcrEngine.Result(anchors, lang), scan.balloons,
                        scan.panels, bubbles, anchors, 0, 0, emptyList(), true, null)
                    val settings = if (server == null) AppSettings(apiKey = "fixture", sourceLang = lang)
                        else AppSettings(provider = LlmProvider.CUSTOM, customUrl = server.url("/chat").toString(),
                            model = "fixture", sourceLang = lang, aiVision = AiVisionMode.ALWAYS)
                    runBlocking(Dispatchers.Default) {
                        val read = if (server != null) null else {
                            val reader = PageReader(settings, null, null, pageTransport(pg, text), race = 1)
                            reader.start(this, pg.bitmap, lang).also {
                                assertEquals(words.size, it.collect(null).size)
                                started.set(SystemClock.elapsedRealtimeNanos())
                            }
                        }
                        suspend fun display(lines: List<RenderBubble>) = withContext(Dispatchers.Main.immediate) {
                            overlay.setBubbles(lines, pg.bitmap)
                            count = lines.size
                        }
                        val result = pipeline.translate(analysis, settings, onPartial = {
                            paints++
                            display(it.bubbles)
                        }, read = read)
                        assertEquals(words.toSet(), result.bubbles.map { it.translated }.toSet())
                        display(result.bubbles)
                    }
                    assertTrue("final composition drew on the device", completed.await(6, TimeUnit.SECONDS))
                    if (server != null) assertEquals(1, server.requestCount)
                    val screenshot = renderedScreenshot(pg, screenOrigin, trial, anchoredVision && trial in 1..3)
                    val metricsRow = listOf(trial, lang, words.size, paints, (first.get()-started.get())/1_000_000,
                        (last.get()-started.get())/1_000_000, screenshot.first, screenshot.second).joinToString(",")
                    metrics.appendText(metricsRow + "\n")
                } finally {
                    scenario.onActivity { host.viewTreeObserver.removeOnDrawListener(watch); host.removeAllViews() }
                    server?.shutdown()
                    ocr.close()
                    pg.bitmap.recycle()
                }
            }
        }
    }

    private fun pageTransport(pg: Fixture, text: String): PageReader.Transport {
        val wire = JSONObject().put("items", JSONArray().apply {
            pg.regions.forEachIndexed { i, box ->
                val coords = listOf(box.top * 1000 / pg.bitmap.height, box.left * 1000 / pg.bitmap.width,
                    box.bottom * 1000 / pg.bitmap.height, box.right * 1000 / pg.bitmap.width)
                put(JSONObject().put("box_2d", JSONArray(coords)).put("kind", "speech").put("who", "")
                    .put("src", text).put("en", words[i]))
            }
        }).toString()
        return object : PageReader.Transport {
            override suspend fun stream(model: String, body: JSONObject, onSent: () -> Unit,
                onFinish: (String) -> Unit, onDelta: suspend (String) -> Unit): String {
                onSent()
                onDelta(wire)
                onFinish("STOP")
                return wire
            }
        }
    }

    private fun anchoredServer(text: String, requestStarted: AtomicLong): MockWebServer {
        val deltas = mutableListOf("{\"bubbles\":[")
        for ((id, english) in words.withIndex()) {
            val bubble = JSONObject().put("id", id).put("kind", "dialogue").put("src", text).put("en", english)
            deltas += (if (id == 0) "" else ",") + bubble.toString()
        }
        deltas += "]}"
        val stream = deltas.joinToString("") { delta ->
            val choice = JSONObject().put("delta", JSONObject().put("content", delta))
            "data: " + JSONObject().put("choices", JSONArray().put(choice)).toString() + "\n\n"
        } + "data: [DONE]\n\n"
        return MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestStarted.compareAndSet(0, SystemClock.elapsedRealtimeNanos())
                    return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(stream)
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
    }

    private data class Fixture(val bitmap: Bitmap, val regions: List<Rect>, val guard: Int)

    private fun fixture(w: Int, h: Int, text: String): Fixture {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { density = Bitmap.DENSITY_NONE }
        val canvas = Canvas(bitmap)
        val guard = Color.rgb(58, 113, 133)
        canvas.drawColor(guard)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        val regions = words.indices.map { i ->
            val cx = ((i % 2 + .5f) * w / 2).toInt()
            val cy = ((i / 2 + .5f) * h / 6).toInt()
            val big = RectF(cx - w*.225f, cy-h*.072f, cx+w*.225f, cy+h*.072f)
            p.color = Color.BLACK
            canvas.drawOval(big, p)
            big.inset(3f, 3f)
            p.color = Color.WHITE
            canvas.drawOval(big, p)
            p.color = Color.rgb(96, 40, 136)
            p.textSize = w*.047f
            canvas.drawText(text, cx.toFloat(), cy - (p.ascent()+p.descent())/2f, p)
            val half = (p.measureText(text)/2).toInt()+2
            Rect(cx-half, cy-(p.textSize*.58f).toInt(), cx+half, cy+(p.textSize*.58f).toInt())
        }
        return Fixture(bitmap, regions, guard)
    }

    private fun renderedScreenshot(pg: Fixture, origin: IntArray, trial: Int, verifyWords: Boolean): Pair<Int, Int> {
        val deadline = SystemClock.elapsedRealtime() + 4000
        var remaining: Int
        var art: Int
        do {
            val screen = inst.uiAutomation.takeScreenshot() ?: error("No device screenshot")
            remaining = 0
            var legible = 0
            art = 0
            for (r in pg.regions) {
                var ink = 0
                for (y in r.top until r.bottom step 2) for (x in r.left until r.right step 2) {
                    val p = screen.getPixel(x + origin[0], y + origin[1])
                    if (Color.blue(p) - Color.green(p) > 28 && Color.red(p) - Color.green(p) > 12) remaining++
                    if (Color.red(p) < 120 && Color.green(p) < 120 && Color.blue(p) < 120) ink++
                }
                if (ink > 10) legible++
            }
            for (n in 1..11) {
                val actual = screen.getPixel(pg.bitmap.width/2 + origin[0], pg.bitmap.height*n/12 + origin[1])
                for (shift in listOf(16, 8, 0)) art = maxOf(art, kotlin.math.abs((actual shr shift and 255)-(pg.guard shr shift and 255)))
            }
            if (legible == words.size && remaining < 50 && art <= 4) {
                // Check every background pixel between the balloons, including
                // those beside their outlines where a misplaced wipe first shows.
                val width = pg.bitmap.width
                val height = pg.bitmap.height
                val before = IntArray(width * height)
                val after = IntArray(before.size)
                pg.bitmap.getPixels(before, 0, width, 0, 0, width, height)
                screen.getPixels(after, 0, width, origin[0], origin[1], width, height)
                for (i in before.indices) {
                    if (before[i] != pg.guard) continue
                    for (shift in 0..16 step 8) {
                        val diff = (after[i] shr shift and 255) - (before[i] shr shift and 255)
                        art = maxOf(art, kotlin.math.abs(diff))
                    }
                }
            }
            val done = legible == words.size && remaining < 50 && art <= 4
            if (done && trial == 1 || !done && SystemClock.elapsedRealtime() >= deadline) {
                File(output, "dense-" + trial + ".png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            if (done && verifyWords) {
                DisplayText().use { reader ->
                    words.forEachIndexed { index, word ->
                        val col = index % 2
                        val row = index / 2
                        val area = Rect(col * pg.bitmap.width / 2, row * pg.bitmap.height / 6,
                            (col + 1) * pg.bitmap.width / 2, (row + 1) * pg.bitmap.height / 6)
                        area.offset(origin[0], origin[1])
                        val expected = DisplayText.letters(word)
                        val observations = listOf(1f, .75f, 1.5f).asSequence().map { reader.read(screen, area, it) }
                        assertTrue("English in balloon " + index + " must read " + word,
                            observations.any { DisplayText.letters(it).contains(expected) })
                    }
                }
            }
            screen.recycle()
            if (done) return remaining to art
            SystemClock.sleep(35)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Incomplete display: source samples=" + remaining + ", art error=" + art)
    }
}
