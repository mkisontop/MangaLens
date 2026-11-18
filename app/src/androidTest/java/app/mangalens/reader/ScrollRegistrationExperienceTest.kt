package app.mangalens.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mangalens.ocr.BalloonFinder
import app.mangalens.ocr.OcrEngine
import app.mangalens.ocr.OcrLine
import app.mangalens.pipeline.TranslatePipeline
import app.mangalens.settings.AppSettings
import app.mangalens.settings.SourceLang
import app.mangalens.translate.GeminiApi
import app.mangalens.translate.ItemKind
import app.mangalens.translate.PageItem
import app.mangalens.translate.PendingRead
import app.mangalens.translate.TranslationCache
import app.mangalens.translate.TranslationService
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ScrollRegistrationExperienceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val output = File(inst.targetContext.filesDir, "reader-ux/" +
        InstrumentationRegistry.getArguments().getString("build", "candidate")).apply { mkdirs() }

    @Test fun distinguishScrollingFromNewAndAmbiguousPages() {
        File(output, "scroll.csv").bufferedWriter().use { out ->
            out.appendLine("trial,case,expected,observed,ms")
            for (trial in 0 until 5) for (case in ScrollFixture.cases) {
                val wanted = ScrollFixture.expected(case)
                val (actual, ns) = ScrollFixture.measure(case, trial+61, 720, 1440)
                // A missed alignment costs an upload. A wrong one can show the wrong line.
                if (actual != null) assertEquals(case.name, wanted, actual)
                out.appendLine(listOf(trial,case.name,wanted,actual,ns / 1_000_000.0).joinToString(","))
            }
        }
    }

    private data class Page(val bitmap: Bitmap, val items: List<PageItem>)
    private data class Upload(val bytes: Int, val height: Int, val strip: Boolean)

    private fun page(delta: Int, direction: Int): Page {
        val original = ScrollFixture.page(720, 1440, delta, 1, 417)
        val bitmap = original.copy(Bitmap.Config.ARGB_8888, true)
        original.recycle()
        val canvas = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f; textAlign = Paint.Align.CENTER }
        val items = ArrayList<PageItem>()
        val rows = listOf(720 to ("안녕하세요" to "Hello there."),
            (if (direction > 0) 1476 else -44) to ("여기예요" to "Over here."))
        for ((sceneY, words) in rows) {
            val y = sceneY - delta
            if (y - 26 < 0 || y + 26 > bitmap.height) continue
            val cx = 360
            p.color = Color.BLACK
            canvas.drawOval(218f, y-47f, 502f, y+47f, p)
            p.color = Color.WHITE
            canvas.drawOval(220f, y-45f, 500f, y+45f, p)
            p.color = Color.rgb(75,38,95)
            canvas.drawText(words.first,cx.toFloat(),y - (p.ascent()+p.descent())/2f,p)
            val half = (p.measureText(words.first)/2).toInt()+2
            items += PageItem(Rect(cx-half,y-21,cx+half,y+21), ItemKind.SPEECH, words.first, words.second)
        }
        return Page(bitmap,items)
    }

    private fun analysis(page: Page): TranslatePipeline.Analysis {
        val scan = BalloonFinder.analyze(page.bitmap, 0, 0, emptyList())
        val lines = page.items.map { OcrLine(it.src, it.box, false) }
        return TranslatePipeline.Analysis(page.bitmap,OcrEngine.Result(lines,SourceLang.KO),scan.balloons,scan.panels,
            emptyList(),lines,0,0,emptyList(),true,null)
    }

    @Test fun captureTheRealGeminiUploadAfterScrollingInEachDirection() {
        val file = File(output, "uploads.csv")
        file.writeText("scroll_px,requests,jpeg_bytes,total_jpeg_bytes,encoded_height,strip,final_lines\n")
        for (d in listOf(121,-121)) {
            val before = page(0,d)
            val after = page(d,d)
            val ocr = OcrEngine()
            val cache = TranslationCache()
            val pipeline = TranslatePipeline(ocr,TranslationService(cache),cache)
            val settings = AppSettings(apiKey="fixture", sourceLang=SourceLang.KO, ignoreTopPct=0f, ignoreBottomPct=0f)
            val uploads = CopyOnWriteArrayList<Upload>()
            try {
                MockWebServer().use { server ->
                    server.start(InetAddress.getByName("127.0.0.1"),0)
                    GeminiApi.base = server.url("/models/").toString()
                    GeminiApi.resetLearned()
                    server.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val raw = request.body.readByteArray()
                            val text = if (request.getHeader("Content-Encoding") == "gzip") {
                                GZIPInputStream(raw.inputStream()).bufferedReader().use { it.readText() }
                            } else raw.toString(Charsets.UTF_8)
                            val parts = JSONObject(text).getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
                            val image = (0 until parts.length()).firstNotNullOf { i -> parts.getJSONObject(i).optJSONObject("inlineData") }
                            val jpeg = Base64.decode(image.getString("data"), Base64.DEFAULT)
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(jpeg,0,jpeg.size,options)
                            val strip = (0 until parts.length()).any { i -> parts.getJSONObject(i).optString("text").contains("\"unread_rows\"") }
                            uploads += Upload(jpeg.size,options.outHeight,strip)
                            val height = if (strip) abs(d)+(after.bitmap.height*.25f).toInt() else after.bitmap.height
                            val top = if (strip && d>0) after.bitmap.height-height else 0
                            val items = JSONArray()
                            for (item in after.items) {
                                if (strip && item.src==before.items.first().src) continue
                                val box = item.box
                                val coords = listOf((box.top-top)*1000/height,box.left*1000/720,
                                    (box.bottom-top)*1000/height,box.right*1000/720)
                                items.put(JSONObject().put("box_2d",JSONArray(coords)).put("kind","speech")
                                    .put("who","").put("src",item.src).put("en",item.en))
                            }
                            val payload = JSONObject().put("items",items).toString()
                            val part = JSONObject().put("text",payload)
                            val candidate = JSONObject().put("finishReason","STOP").put("content",JSONObject().put("parts",JSONArray().put(part)))
                            val event = JSONObject().put("candidates",JSONArray().put(candidate))
                            return MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: " + event + "\n\n")
                        }
                    }
                    runBlocking {
                        val first = object : PendingRead {
                            override val isActive = false
                            override fun cancel() {}
                            override suspend fun collect(onItem: (suspend (PageItem) -> Unit)?): List<PageItem> {
                                for (item in before.items) onItem?.invoke(item)
                                return before.items
                            }
                        }
                        val warm = pipeline.translate(analysis(before),settings,read=first)
                        assertEquals(listOf("Hello there."),warm.bubbles.map { it.translated })
                        val read = requireNotNull(pipeline.startRead(after.bitmap, settings, this))
                        val result = pipeline.translate(analysis(after), settings, read=read, onPartial={})
                        assertEquals(after.items.map { it.en }.toSet(), result.bubbles.map { it.translated }.toSet())
                        for (item in after.items) {
                            val placed = result.bubbles.single { it.translated == item.en }
                            assertTrue("translated line stays at its source", Rect.intersects(placed.box,item.box))
                        }
                        assertTrue("the new line reached the provider", uploads.isNotEmpty())
                        file.appendText(listOf(d,uploads.size,uploads.maxOf { it.bytes }, uploads.sumOf { it.bytes },
                            uploads.maxOf { it.height },uploads.all { it.strip },result.bubbles.size).joinToString(",") + "\n")
                    }
                }
            } finally {
                GeminiApi.base=GeminiApi.BASE
                GeminiApi.resetLearned()
                ocr.close()
                before.bitmap.recycle()
                after.bitmap.recycle()
            }
        }
    }
}
