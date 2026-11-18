package app.mangalens.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mangalens.ocr.OcrEngine
import app.mangalens.overlay.BubbleOverlayView
import app.mangalens.pipeline.ScrollMatch
import app.mangalens.pipeline.TranslatePipeline
import app.mangalens.settings.AiVisionMode
import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SourceLang
import app.mangalens.translate.TranslationCache
import app.mangalens.translate.TranslationService
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses published art and Korean lettering. The fixed replies isolate OCR, placement and cleaning. */
@RunWith(AndroidJUnit4::class)
class PublishedComicExperienceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val build = InstrumentationRegistry.getArguments().getString("build", "candidate")
    private val output = File(inst.targetContext.filesDir, "reader-ux/" + build).apply { mkdirs() }

    private data class Scene(
        val name: String,
        val crop: RectF,
        val balloon: RectF,
        val markers: List<String>,
        val english: String,
    )

    // Fractions of the published page. Each crop simulates zooming a panel in a comic reader.
    private val scenes = listOf(
        Scene("contest", RectF(.12f, .477f, .499f, .659f), RectF(.273f, .488f, .494f, .605f),
            listOf("당근", "대회"), "Carrot, you sweetheart! Are you taking me to the contest?"),
        Scene("outfit", RectF(.604f, .747f, .965f, .855f), RectF(.688f, .751f, .947f, .849f),
            listOf("모자", "포션"), "You brought my clothes, my hat, and even the potion!"),
        Scene("potion", RectF(.584f, .855f, .967f, .970f), RectF(.725f, .857f, .934f, .942f),
            listOf("어떤", "볼까"), "Let's see which potion you brought."),
    )

    @Test
    fun followRealArtworkByTheExactNumberOfScrolledPixels() {
        val source = requireNotNull(BitmapFactory.decodeFile(File(inst.targetContext.filesDir, "potion-contest-page.jpg").path))
        val strip = Bitmap.createScaledBitmap(source, 720, (720f * source.height / source.width).roundToInt(), true)
        val height = strip.height / 2
        val start = (strip.height - height) / 2
        fun viewport(offset: Int): Bitmap {
            val view = Bitmap.createBitmap(720, height, Bitmap.Config.ARGB_8888)
            Canvas(view).apply {
                drawBitmap(strip, Rect(0, start + offset, 720, start + offset + height), Rect(0, 0, 720, height), null)
                val bar = Paint().apply { color = Color.rgb(43, 47, 53) }
                drawRect(0f, 0f, 720f, 26f, bar)
            }
            return view
        }
        try {
            val first = viewport(0)
            val before = try { ScrollMatch.of(first) } finally { first.recycle() }
            File(output, "published-scroll.csv").bufferedWriter().use { csv ->
                csv.appendLine("expected,observed")
                for (offset in listOf(-183, -121, -53, -17, -3, -1, 0, 2, 19, 78, 123, 181)) {
                    val view = viewport(offset)
                    val found = try { ScrollMatch.of(view).scrolledFrom(before) } finally { view.recycle() }
                    if (found != null) assertEquals("A remembered line must land on the same artwork", offset, found)
                    csv.appendLine(listOf(offset, found).joinToString(","))
                }
            }
        } finally {
            if (strip !== source) strip.recycle()
            source.recycle()
        }
    }

    @Test
    fun publishedKoreanBalloonsShowReadableEnglishWithinTheirOwnPanels() {
        readScenes(scenes, "published.csv")
    }

    @Test
    fun realModelLettersThePublishedKoreanPanelOnTheDevice() {
        val endpoint = InstrumentationRegistry.getArguments().getString("liveEndpoint")
        assumeTrue("Run the live Android reader job with a model endpoint", !endpoint.isNullOrBlank())
        val scene = scenes.single { it.name == "outfit" }
        readScenes(listOf(scene), "live-published.csv", requireNotNull(endpoint))
    }

    private fun readScenes(selected: List<Scene>, csvName: String, liveEndpoint: String? = null) {
        val page = requireNotNull(BitmapFactory.decodeFile(File(inst.targetContext.filesDir, "potion-contest-page.jpg").path))
        val csv = File(output, csvName)
        csv.writeText("scene,matched_source,english_read_back,original_marker_visible,art_pixels_checked,art_pixels_changed\n")
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { activity ->
                lateinit var host: FrameLayout
                activity.onActivity {
                    it.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    host = FrameLayout(it)
                    it.setContentView(host)
                }
                inst.waitForIdleSync()
                DisplayText().use { english ->
                    for (scene in selected) read(page, scene, host, csv, english, liveEndpoint)
                }
            }
        } finally {
            page.recycle()
        }
    }

    private fun read(page: Bitmap, scene: Scene, host: FrameLayout, csv: File, english: DisplayText, liveEndpoint: String?) {
        fun source(r: RectF) = Rect((r.left * page.width).roundToInt(), (r.top * page.height).roundToInt(),
            (r.right * page.width).roundToInt(), (r.bottom * page.height).roundToInt())
        val crop = source(scene.crop)
        val scale = minOf(host.width.toFloat() / crop.width(), host.height.toFloat() / crop.height())
        val dx = (host.width - crop.width() * scale) / 2f
        val dy = (host.height - crop.height() * scale) / 2f
        val dest = RectF(dx, dy, dx + crop.width() * scale, dy + crop.height() * scale)
        val lettering = source(scene.balloon)
        val expected = Rect(((lettering.left - crop.left) * scale + dx).toInt(),
            ((lettering.top - crop.top) * scale + dy).toInt(),
            ((lettering.right - crop.left) * scale + dx).roundToInt(),
            ((lettering.bottom - crop.top) * scale + dy).roundToInt()).apply {
            check(intersect(0, 0, host.width, host.height))
        }
        val frame = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888).apply {
            density = Bitmap.DENSITY_NONE
            Canvas(this).apply {
                drawColor(Color.rgb(39, 42, 47))
                drawBitmap(page, crop, dest, Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
        lateinit var overlay: BubbleOverlayView
        val origin = IntArray(2)
        inst.runOnMainSync {
            host.removeAllViews()
            host.addView(object : View(host.context) {
                override fun onDraw(canvas: Canvas) { canvas.drawBitmap(frame, 0f, 0f, null) }
            })
            overlay = BubbleOverlayView(host.context)
            host.addView(overlay)
            host.getLocationOnScreen(origin)
        }
        inst.waitForIdleSync()
        SystemClock.sleep(120)
        val original = requireNotNull(inst.uiAutomation.takeScreenshot())
        val screenBox = Rect(expected).apply { offset(origin[0], origin[1]) }
        saveBalloonComparison(scene.name, original, original, screenBox)
        val ocr = OcrEngine()
        try {
            MockWebServer().use { server ->
                server.start(InetAddress.getByName("127.0.0.1"), 0)
                val seen = CopyOnWriteArrayList<String>()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val message = JSONObject(request.body.readUtf8()).getJSONArray("messages").getJSONObject(1)
                        val bubbles = JSONObject(message.getString("content").substringAfter("\n\n")).getJSONArray("bubbles")
                        val response = JSONArray()
                        for (i in 0 until bubbles.length()) {
                            val item = bubbles.getJSONObject(i)
                            val text = item.getString("text")
                            val match = scene.markers.any { it in text.filter(Char::isLetter) }
                            seen += text
                            response.put(JSONObject().put("id", item.getInt("id")).put("kind", if (match) "dialogue" else "skip")
                                .put("en", if (match) scene.english else ""))
                        }
                        val body = JSONObject().put("bubbles", response)
                        val reply = JSONObject().put("message", JSONObject().put("content", body.toString()))
                        return MockResponse().setHeader("Content-Type", "application/json")
                            .setBody(JSONObject().put("choices", JSONArray().put(reply)).toString())
                    }
                }
                val settings = AppSettings(provider = LlmProvider.CUSTOM,
                    model = if (liveEndpoint == null) "fixture" else "qwen2.5-7b-instruct-q4_k_m",
                    customUrl = liveEndpoint ?: server.url("/chat").toString(), sourceLang = SourceLang.KO,
                    aiVision = AiVisionMode.OFF, ignoreTopPct = 0f, ignoreBottomPct = 0f)
                val cache = TranslationCache()
                val pipeline = TranslatePipeline(ocr, TranslationService(cache), cache)
                val start = SystemClock.elapsedRealtime()
                val (sourceLines, result) = runBlocking(Dispatchers.Default) {
                    val analysis = pipeline.analyze(frame, settings)
                    Log.i("MangaLensUX", scene.name + " image=" + page.width + "x" + page.height +
                        " viewport=" + frame.width + "x" + frame.height + " balloon=" + expected +
                        " OCR=" + analysis.ocr.lines.map { it.text } + " regions=" + analysis.bubbles.map { it.text })
                    analysis.bubbles.map { it.text } to pipeline.translate(analysis, settings)
                }
                val elapsed = SystemClock.elapsedRealtime() - start
                val cards = result.bubbles.map { it.translated }
                Log.i("MangaLensUX", scene.name + " source=" + sourceLines + " cards=" + cards)
                val expectedEnglish = if (liveEndpoint == null) {
                    assertEquals("the published balloon was recognized: " + seen, listOf(scene.english), cards)
                    scene.english
                } else {
                    assertTrue("The published source mentions a hat and a potion: " + sourceLines,
                        scene.markers.all { marker -> sourceLines.any { marker in it.filter(Char::isLetter) } })
                    val answer = cards.single().lowercase()
                    assertTrue("The real translation must preserve the clothing, hat and potion: " + answer,
                        Regex("potion|elixir|concoction").containsMatchIn(answer) &&
                            Regex("clothes|clothing|outfit|garments|dress|costume").containsMatchIn(answer) &&
                            Regex("\\b(hat|cap)s?\\b").containsMatchIn(answer))
                    cards.single()
                }
                inst.runOnMainSync { overlay.setBubbles(result.bubbles, frame) }
                inst.waitForIdleSync()
                SystemClock.sleep(250)
                val display = requireNotNull(inst.uiAutomation.takeScreenshot())
                try {
                    for ((name, ref) in listOf("original" to original, "translated" to display)) {
                        File(output, "published-" + scene.name + "-" + name + ".png").outputStream().use {
                            ref.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                    val box = Bitmap.createBitmap(display, origin[0] + expected.left, origin[1] + expected.top,
                        expected.width(), expected.height())
                    val korean = try {
                        runBlocking { ocr.recognize(box, SourceLang.KO).lines.joinToString(" ") { it.text.lowercase() } }
                    } finally {
                        if (box !== display) box.recycle()
                    }
                    saveBalloonComparison(scene.name, original, display, screenBox)
                    val recognized = english.read(display, screenBox)
                    val markerVisible = scene.markers.any { it in korean.filter(Char::isLetter) }
                    val art = compareArt(original, display, origin, host.width, host.height, expected, dest)
                    Log.i("MangaLensUX", scene.name + " English=" + recognized + " art=" + art)
                    assertTrue("Complete English read back from the balloon: " + recognized,
                        DisplayText.letters(recognized).contains(DisplayText.letters(expectedEnglish)))
                    assertTrue("Korean still visible in the translated balloon: " + korean, !markerVisible)
                    assertTrue("checked painted panel artwork", art.first > 80_000)
                    assertEquals("pixels changed outside the balloon", 0, art.second)
                    csv.appendText(listOf(scene.name, true, true, markerVisible, art.first, art.second).joinToString(",") + "\n")
                    if (liveEndpoint != null) {
                        val record = JSONObject().put("model", settings.model).put("source_ocr", JSONArray(sourceLines))
                            .put("english", expectedEnglish).put("display_ocr", recognized).put("ocr_and_translation_ms", elapsed)
                            .put("art_pixels_checked", art.first).put("art_pixels_changed", art.second)
                        File(output, "live-panel.json").writeText(record.toString(2) + "\n")
                    }
                } finally {
                    display.recycle()
                }
            }
        } finally {
            inst.runOnMainSync { host.removeAllViews() }
            original.recycle()
            ocr.close()
            frame.recycle()
        }
    }

    /** The left and right halves are actual screenshots from before and after lettering. */
    private fun saveBalloonComparison(scene: String, original: Bitmap, display: Bitmap, balloon: Rect) {
        val bounds = Rect(balloon).apply {
            inset(-28, -28)
            check(intersect(0, 0, display.width, display.height))
        }
        val w = minOf(480, bounds.width())
        val h = (w.toFloat() * bounds.height() / bounds.width()).roundToInt()
        val pair = Bitmap.createBitmap(w * 2 + 6, h, Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            Canvas(pair).apply {
                drawColor(Color.DKGRAY)
                drawBitmap(original, bounds, Rect(0, 0, w, h), paint)
                drawBitmap(display, bounds, Rect(w + 6, 0, 2 * w + 6, h), paint)
            }
            File(output, "published-" + scene + "-detail.jpg").outputStream().use {
                pair.compress(Bitmap.CompressFormat.JPEG, 86, it)
            }
        } finally {
            pair.recycle()
        }
    }

    private fun compareArt(before: Bitmap, after: Bitmap, origin: IntArray, w: Int, h: Int,
        balloon: Rect, art: RectF): Pair<Int, Int> {
        val old = IntArray(w * h)
        val new = IntArray(old.size)
        before.getPixels(old, 0, w, origin[0], origin[1], w, h)
        after.getPixels(new, 0, w, origin[0], origin[1], w, h)
        var checked = 0
        var altered = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (!art.contains(x.toFloat(), y.toFloat()) || balloon.contains(x, y)) continue
            checked++
            val i = y * w + x
            if ((0..16 step 8).any { abs((old[i] shr it and 255) - (new[i] shr it and 255)) > 3 }) altered++
        }
        return checked to altered
    }
}
