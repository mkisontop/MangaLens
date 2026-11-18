package app.mangalens.reader

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.mangalens.capture.ScreenCaptureService
import app.mangalens.settings.AiVisionMode
import app.mangalens.settings.CaptureMode
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SettingsRepository
import app.mangalens.settings.SourceLang
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ReaderExperienceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext
    private val output = File(app.filesDir, "reader-ux/" + InstrumentationRegistry.getArguments().getString("build", "candidate")).apply { mkdirs() }
    private val requests = ConcurrentHashMap<String, Long>()
    private lateinit var page: ReaderPage

    @Test fun pageTurnsReplaceOnlyTheLetteringAndRevealCachedEnglish() {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            runBlocking {
                SettingsRepository(app).apply {
                    setProvider(LlmProvider.CUSTOM)
                    setCustomUrl(server.url("/chat").toString())
                    setModel(LlmProvider.CUSTOM, "fixture")
                    setSourceLang(SourceLang.KO)
                    setAiVision(AiVisionMode.OFF)
                    setMode(CaptureMode.AUTO)
                    setAutoScrollButton(false)
                    setNoGhosts(true)
                }
            }
            val metrics = File(output, "metrics.csv")
            metrics.writeText("scenario,page,first_visible_ms,all_visible_ms,provider_start_ms,remaining_source_pct,guard_error\n")
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    page = ReaderPage(activity)
                    activity.setContentView(page)
                }
                inst.waitForIdleSync()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val body = JSONObject(request.body.readUtf8())
                        val content = body.getJSONArray("messages").getJSONObject(1).getString("content")
                        val lines = JSONObject(content.substringAfter("\n\n")).getJSONArray("bubbles")
                        val answer = JSONArray()
                        for (n in 0 until lines.length()) {
                            val line = lines.getJSONObject(n)
                            val original = line.getString("text").filter(Char::isLetter)
                            val index = page.source.indexOf(original)
                            if (index < 0) return MockResponse().setResponseCode(422).setBody("Unexpected fixture text: " + original)
                            requests[original] = SystemClock.elapsedRealtime()
                            answer.put(JSONObject().put("id", line.getInt("id")).put("kind", "dialogue").put("en", page.english[index]))
                        }
                        val result = JSONObject().put("bubbles", answer).toString()
                        val message = JSONObject().put("content", result)
                        return MockResponse().setHeader("Content-Type", "application/json")
                            .setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message))).toString())
                            .setBodyDelay(1800, TimeUnit.MILLISECONDS)
                    }
                }
                val service = Intent(app, ScreenCaptureService::class.java)
                try {
                    scenario.onActivity { activity ->
                        val consent = activity.activityResultRegistry.register("capture-fixture", ActivityResultContracts.StartActivityForResult()) { result ->
                            check(result.resultCode == Activity.RESULT_OK)
                            val start = Intent(service).setAction(ScreenCaptureService.ACTION_START)
                                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                                .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                            ContextCompat.startForegroundService(activity, start)
                        }
                        consent.launch(activity.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
                    }
                    val device = UiDevice.getInstance(inst)
                    val approve = device.wait(Until.findObject(By.textContains("Start now")), 6000)
                        ?: device.wait(Until.findObject(By.res("android", "button1")), 2000)
                    approve?.click()
                    await("screen capture consent", 10000) { ScreenCaptureService.running.value }
                    for (index in page.source.indices) {
                        measurePage(scenario, index, metrics)
                    }
                } finally {
                    app.stopService(service)
                }
            }
        }
    }

    private fun measurePage(scenario: ActivityScenario<ComponentActivity>, index: Int, metrics: File) {
        lateinit var raw: Bitmap
        val origin = IntArray(2)
        val start = SystemClock.elapsedRealtime()
        scenario.onActivity {
            page.page = index
            page.getLocationOnScreen(origin)
            raw = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            page.draw(Canvas(raw))
        }
        val top = page.region(false)
        val bottom = page.region(true)
        val topSource = page.ink(raw, top).source
        val bottomSource = if (index == 0) 0 else page.ink(raw, bottom).source
        assertTrue("Fixture has ink in each source balloon", topSource > 100 && (index == 0 || bottomSource > 100))
        val screenTop = Rect(top).apply { offset(origin[0], origin[1]) }
        val screenBottom = Rect(bottom).apply { offset(origin[0], origin[1]) }
        var first = -1L
        var full = -1L
        var sourcePct = 100.0
        var guard = 255
        var last: Bitmap? = null
        try {
            await("page " + index + " ink; provider calls=" + requests.keys, 30000) {
                val screen = inst.uiAutomation.takeScreenshot() ?: return@await false
                last?.recycle()
                last = screen
                val now = SystemClock.elapsedRealtime() - start
                guard = guardError(screen, origin)
                if (guard > 15) return@await false
                val a = page.ink(screen, screenTop)
                val b = if (index == 0) a else page.ink(screen, screenBottom)
                val topVisible = a.source * 10 < topSource && a.english > 80
                val bottomVisible = index == 0 || b.source * 10 < bottomSource && b.english > 80
                if (topVisible && first < 0) first = now
                if (topVisible && bottomVisible && requests.containsKey(page.source[index])) {
                    full = now
                    sourcePct = 100.0 * (a.source + if (index == 0) 0 else b.source) / (topSource + bottomSource)
                    return@await true
                }
                false
            }
            val requestMs = requests.getValue(page.source[index]) - start
            val row = listOf("media-projection", index, first, full, requestMs, sourcePct, guard).joinToString(",")
            metrics.appendText(row + "\n")
            Log.i("MangaLensUX", row)
            assertTrue("Source ink stays erased", sourcePct < 10.0)
        } finally {
            if (index <= 1 || full < 0) {
                save(raw, "page-" + index + "-raw.png")
                last?.let { save(it, "page-" + index + "-screen.png") }
            }
            last?.recycle()
            raw.recycle()
        }
    }

    /** Sample untouched art in four corners of the central panel. Android 12+ dims it through the veil. */
    private fun guardError(screen: Bitmap, origin: IntArray): Int {
        val ground = page.ground()
        var worst = 0
        for (x in listOf(.07, .93)) for (y in listOf(.51, .85)) {
            val actual = screen.getPixel(origin[0] + (page.width * x).toInt(), origin[1] + (page.height * y).toInt())
            for (shift in listOf(16, 8, 0)) {
                val want = ((ground shr shift and 255) * .8).toInt()
                worst = maxOf(worst, abs((actual shr shift and 255) - want))
            }
        }
        return worst
    }

    private fun await(label: String, timeout: Long, done: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        do {
            if (done()) return
            SystemClock.sleep(35)
        } while (SystemClock.elapsedRealtime() < end)
        fail("Timed out waiting for " + label + "; recognized=" + requests.keys)
    }

    private fun save(bitmap: Bitmap, name: String) {
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
