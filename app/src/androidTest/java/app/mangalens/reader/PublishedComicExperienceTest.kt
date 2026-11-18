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
        val checkWords: List<String>,
    )

    // Fractions of the published page. Each crop simulates zooming a panel in a comic reader.
    private val scenes = listOf(
        Scene("contest", RectF(.045f, .009f, .31f, .277f), RectF(.074f, .029f, .311f, .205f),
            listOf("코리앤더", "열망"), "Coriander wants to win the potion contest.", listOf("potion", "contest")),
        Scene("podium", RectF(.30f, .012f, .613f, .296f), RectF(.303f, .032f, .565f, .241f),
            listOf("지난번", "시상대"), "Last time she missed the podium.", listOf("last", "podium")),
        Scene("device", RectF(.495f, .29f, .794f, .557f), RectF(.507f, .327f, .733f, .492f),
            listOf("장치", "물약"), "This device can catch different potions!", listOf("device", "potions")),
    )

    @Test
    fun publishedKoreanBalloonsShowReadableEnglishWithinTheirOwnPanels() {
        val page = requireNotNull(BitmapFactory.decodeFile(File(inst.targetContext.filesDir, "potion-contest-page.jpg").path))
        val csv = File(output, "published.csv")
        csv.writeText("scene,matched_source,english_read_back,art_pixels_checked,art_pixels_changed\n")
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
                for (scene in scenes) read(page, scene, host, csv)
            }
        } finally {
            page.recycle()
        }
    }

    private fun read(page: Bitmap, scene: Scene, host: FrameLayout, csv: File) {
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
                val settings = AppSettings(provider = LlmProvider.CUSTOM, model = "fixture",
                    customUrl = server.url("/chat").toString(), sourceLang = SourceLang.KO,
                    aiVision = AiVisionMode.OFF, ignoreTopPct = 0f, ignoreBottomPct = 0f)
                val cache = TranslationCache()
                val pipeline = TranslatePipeline(ocr, TranslationService(cache), cache)
                val result = runBlocking(Dispatchers.Default) {
                    pipeline.translate(pipeline.analyze(frame, settings), settings)
                }
                Log.i("MangaLensUX", scene.name + " source=" + seen + " cards=" + result.bubbles.map { it.translated })
                assertEquals("the published balloon was recognized: " + seen, listOf(scene.english),
                    result.bubbles.map { it.translated })
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
                    val recognized = try {
                        runBlocking { ocr.recognize(box, SourceLang.KO).lines.joinToString(" ") { it.text.lowercase() } }
                    } finally {
                        if (box !== display) box.recycle()
                    }
                    val art = compareArt(original, display, origin, host.width, host.height, expected, dest)
                    Log.i("MangaLensUX", scene.name + " English=" + recognized + " art=" + art)
                    assertTrue("English read back from the balloon: " + recognized, scene.checkWords.all { it in recognized })
                    assertTrue("checked painted panel artwork", art.first > 80_000)
                    assertEquals("pixels changed outside the balloon", 0, art.second)
                    csv.appendText(listOf(scene.name, true, true, art.first, art.second).joinToString(",") + "\n")
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
