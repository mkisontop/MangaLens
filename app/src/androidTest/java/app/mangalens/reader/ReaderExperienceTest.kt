package app.mangalens.reader

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
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
import app.mangalens.MainActivity
import app.mangalens.capture.ScreenCaptureService
import app.mangalens.ocr.OcrEngine
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
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
            metrics.writeText("scenario,page,first_visible_ms,all_visible_ms,provider_start_ms,arrival_source_pct,remaining_source_pct,worst_balloon_source_pct,guard_error\n")
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
                            Log.i("MangaLensUX", "Provider received fixture text " + original)
                            val translated = if (original == page.startupSource) page.startupEnglish
                                else page.english.getOrNull(page.source.indexOf(original))
                                    ?: return MockResponse().setResponseCode(422).setBody("Unexpected fixture text: " + original)
                            requests[original] = SystemClock.elapsedRealtime()
                            answer.put(JSONObject().put("id", line.getInt("id")).put("kind", "dialogue").put("en", translated))
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
                    val candidate = InstrumentationRegistry.getArguments().getString("build") != "baseline"
                    ContextCompat.startForegroundService(app, captureGrant(scenario, "capture-fixture"))
                    await("screen capture consent", 10000) {
                        ScreenCaptureService.running.value || (candidate && ScreenCaptureService.captureConsentRequired.value)
                    }
                    if (!ScreenCaptureService.running.value) {
                        ContextCompat.startForegroundService(app, captureGrant(scenario, "capture-fixture-fresh"))
                        await("fresh screen capture consent", 10000) { ScreenCaptureService.running.value }
                    }
                    DisplayText().use { english ->
                        measureStartup(scenario, english)
                        for (index in page.source.indices) {
                            measurePage(scenario, index, metrics, english)
                        }
                    }
                } finally {
                    app.stopService(service)
                }
            }
        }
    }

    @Test fun liveScreenSharingReplacesUnseenJapaneseDialogue() {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = args.getString("projectionEndpoint")
        assumeTrue("Requires the private vision provider and an Android screen grant", endpoint != null)
        runBlocking {
            SettingsRepository(app).apply {
                setProvider(LlmProvider.CUSTOM)
                setCustomUrl(requireNotNull(endpoint))
                setModel(LlmProvider.CUSTOM, requireNotNull(args.getString("liveModel")))
                setSourceLang(SourceLang.JA)
                setAiVision(AiVisionMode.ALWAYS)
                setMode(CaptureMode.AUTO)
                setDataSaver(true)
                setAutoScrollButton(false)
                setNoGhosts(true)
            }
        }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var comic: JapaneseDialoguePage
            scenario.onActivity { activity ->
                activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                comic = JapaneseDialoguePage(activity)
                activity.setContentView(comic)
            }
            inst.waitForIdleSync()
            val origin = IntArray(2)
            inst.runOnMainSync { comic.getLocationOnScreen(origin) }
            val area = comic.letteringArea().apply { offset(origin[0], origin[1]) }
            var sourceFrame: Bitmap? = null
            await("original Japanese lettering on screen", 6000) {
                val shot = inst.uiAutomation.takeScreenshot() ?: return@await false
                if (comic.sourcePixels(shot, area) > 500) {
                    sourceFrame = shot
                    true
                } else {
                    shot.recycle()
                    false
                }
            }
            val before = requireNotNull(sourceFrame)
            val service = Intent(app, ScreenCaptureService::class.java)
            try {
                save(before, "projection-japanese-source.png")
                val source = comic.sourcePixels(before, area)
                assertTrue("The original screen contains colored Japanese strokes", source > 500)
                val grant = captureGrant(scenario, "live-japanese-projection")
                val start = SystemClock.elapsedRealtime()
                ContextCompat.startForegroundService(app, grant)
                await("real Android screen sharing", 15000) { ScreenCaptureService.running.value }
                var observed = ""
                var complete = false
                DisplayText().use { latin ->
                    while (!complete && SystemClock.elapsedRealtime() - start < 235000) {
                        val screen = requireNotNull(inst.uiAutomation.takeScreenshot())
                        try {
                            val remaining = comic.sourcePixels(screen, area)
                            if (remaining * 100 < source * 2) {
                                observed = latin.read(screen, area)
                                val words = observed.lowercase().replace('’', '\'').replace(Regex("""\s+"""), " ")
                                val negated = Regex("""(?:don'?t|do not|not to|can'?t|cannot|mustn'?t|shouldn'?t)\s+(?:go|leave|head|return)|not (?:going|leaving|returning)|no going home""").containsMatchIn(words)
                                val wait = Regex("""\b(wait|stay|remain)\b""").containsMatchIn(words)
                                val morning = Regex("""\b(morning|dawn|daybreak|sunrise)\b""").containsMatchIn(words)
                                if (negated && wait && morning && "here" in words) {
                                    val elapsed = SystemClock.elapsedRealtime() - start
                                    val art = comic.checkArt(before, screen, origin)
                                    assertTrue("Sample at least fifty thousand scene pixels", art.first > 50000)
                                    assertEquals("Art beyond the balloon must preserve its veiled colors", 0, art.second)
                                    save(screen, "projection-japanese-translated.png")
                                    val result = JSONObject().put("display_ocr", observed).put("visible_ms", elapsed)
                                        .put("source_ink_pixels", source).put("residual_ink_pixels", remaining)
                                        .put("art_pixels_checked", art.first).put("art_pixels_changed", art.second)
                                    File(output, "projection-japanese.json").writeText(result.toString(2) + "\n")
                                    complete = true
                                }
                            }
                        } finally {
                            screen.recycle()
                        }
                        if (!complete) SystemClock.sleep(450)
                    }
                }
                if (!complete) inst.uiAutomation.takeScreenshot()?.let { screen ->
                    try { save(screen, "projection-japanese-last.png") } finally { screen.recycle() }
                }
                assertTrue("The captured Japanese page must visibly retain the negation, place and time: " + observed, complete)
            } finally {
                app.stopService(service)
                before.recycle()
            }
        }
    }

    private fun captureGrant(scenario: ActivityScenario<ComponentActivity>, key: String): Intent {
        val granted = CompletableFuture<Intent>()
        scenario.onActivity { activity ->
            val launcher = activity.activityResultRegistry.register(key, ActivityResultContracts.StartActivityForResult()) { result ->
                val grant = result.data
                if (result.resultCode == Activity.RESULT_OK && grant != null) {
                    granted.complete(Intent(app, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_START)
                        .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                        .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, grant))
                } else {
                    granted.completeExceptionally(AssertionError("Screen-sharing consent was declined"))
                }
            }
            val manager = activity.getSystemService(MediaProjectionManager::class.java)
            val request = if (Build.VERSION.SDK_INT >= 34) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
            launcher.launch(request)
        }
        approveSharing(UiDevice.getInstance(inst))
        return granted.get(10, TimeUnit.SECONDS)
    }

    private fun approveSharing(device: UiDevice, chooseEntireScreen: Boolean = false) {
        if (chooseEntireScreen) {
            val option = device.wait(Until.findObject(By.textContains("A single app")), 2500)
                ?: device.findObject(By.clazz("android.widget.Spinner"))
            if (option != null) {
                option.click()
                val entire = device.wait(Until.findObject(By.text(Pattern.compile("(?i).*entire screen.*"))), 4000)
                requireNotNull(entire) { "The system offered an app chooser without the entire-screen option" }.click()
            }
        }
        val approve = device.wait(Until.findObject(By.textContains("Start now")), 6000)
            ?: device.wait(Until.findObject(By.res("android", "button1")), 2000)
        if (chooseEntireScreen) {
            requireNotNull(approve) { "The fresh screen-sharing confirmation was not visible" }.click()
        } else {
            approve?.click()
        }
    }

    @Test fun anExpiredScreenGrantReturnsHomeAndFreshConsentResumesCapture() {
        assumeTrue(Build.VERSION.SDK_INT >= 34 &&
            InstrumentationRegistry.getArguments().getString("build", "candidate") != "baseline")
        MockWebServer().use { provider ->
            provider.start(InetAddress.getByName("127.0.0.1"), 0)
            provider.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"choices":[{"message":{"content":"Ready"}}]}""")
            }
            runBlocking {
                SettingsRepository(app).apply {
                    setProvider(LlmProvider.CUSTOM)
                    setModel(LlmProvider.CUSTOM, "fixture")
                    setCustomUrl(provider.url("/chat").toString())
                    setMode(CaptureMode.MANUAL)
                }
            }
            verifyFreshCaptureAfterExpiry()
        }
    }

    private fun verifyFreshCaptureAfterExpiry() {
        val service = Intent(app, ScreenCaptureService::class.java)
        ActivityScenario.launch(ComponentActivity::class.java).use { host ->
            try {
                val first = captureGrant(host, "first-capture")
                ContextCompat.startForegroundService(app, first)
                await("first authorized session", 10000) { ScreenCaptureService.running.value }
                app.stopService(service)
                await("first session to end", 10000) { !ScreenCaptureService.running.value }

                ContextCompat.startForegroundService(app, Intent(first))
                await("expired grant to be rejected", 10000) { ScreenCaptureService.captureConsentRequired.value }
                assertFalse(ScreenCaptureService.running.value)
                ActivityScenario.launch(MainActivity::class.java).use {
                    val device = UiDevice.getInstance(inst)
                    val visible = device.wait(Until.hasObject(By.textContains("Tap GO again and allow screen sharing")), 10000)
                    if (!visible) {
                        device.dumpWindowHierarchy(File(output, "capture-home.xml"))
                        inst.uiAutomation.takeScreenshot()?.let { screen ->
                            try { save(screen, "capture-home.png") } finally { screen.recycle() }
                        }
                    }
                    assertTrue("Home explains how to renew screen-sharing permission", visible)
                    val go = device.wait(Until.findObject(By.desc("Start translating")), 5000)
                    requireNotNull(go) { "The reader's GO control was missing from Home" }.click()
                    approveSharing(device, chooseEntireScreen = true)
                    await("fresh authorized session from Home", 10000) { ScreenCaptureService.running.value }
                    assertFalse(ScreenCaptureService.captureConsentRequired.value)
                }
            } finally {
                app.stopService(service)
            }
        }
    }

    private fun measureStartup(scenario: ActivityScenario<ComponentActivity>, reader: DisplayText) {
        val region = page.region(false)
        val origin = IntArray(2)
        var originalInk = 0
        scenario.onActivity {
            page.getLocationOnScreen(origin)
            val raw = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            page.draw(Canvas(raw))
            originalInk = page.ink(raw, region).source
            save(raw, "startup-raw.png")
            raw.recycle()
        }
        assertTrue("The startup balloon contains Korean lettering", originalInk > 100)
        region.offset(origin[0], origin[1])
        val start = SystemClock.elapsedRealtime()
        var translated = false
        while (!translated && SystemClock.elapsedRealtime() - start < 35000) {
            if (requests.containsKey(page.startupSource)) {
                inst.uiAutomation.takeScreenshot()?.let { frame ->
                    try {
                        val ink = page.ink(frame, region)
                        if (guardError(frame, origin) <= 15 && ink.source * 10 < originalInk && ink.english > 80) {
                            translated = DisplayText.letters(reader.read(frame, region))
                                .contains(DisplayText.letters(page.startupEnglish))
                            if (translated) save(frame, "startup-screen.png")
                        }
                    } finally {
                        frame.recycle()
                    }
                }
            }
            if (!translated) SystemClock.sleep(55)
        }
        val elapsed = SystemClock.elapsedRealtime() - start
        File(output, "startup.csv").writeText("translated,elapsed_ms\n" + translated + "," + elapsed + "\n")
        Log.i("MangaLensUX", "Stationary startup page translated=" + translated + " after " + elapsed + " ms")
    }

    private fun measurePage(scenario: ActivityScenario<ComponentActivity>, index: Int, metrics: File, reader: DisplayText) {
        lateinit var raw: Bitmap
        val origin = IntArray(2)
        val start = SystemClock.elapsedRealtime()
        scenario.onActivity {
            page.startupProbe = false
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
        var firstFrame: Bitmap? = null
        fun replaceLast(frame: Bitmap) {
            if (last !== firstFrame) last?.recycle()
            last = frame
        }
        try {
            await("page " + index + " ink; provider calls=" + requests.keys, 30000) {
                val screen = inst.uiAutomation.takeScreenshot() ?: return@await false
                replaceLast(screen)
                val now = SystemClock.elapsedRealtime() - start
                guard = guardError(screen, origin)
                if (guard > 15) return@await false
                val a = page.ink(screen, screenTop)
                val b = if (index == 0) a else page.ink(screen, screenBottom)
                val topVisible = a.source * 10 < topSource && a.english > 80
                val bottomVisible = index == 0 || b.source * 10 < bottomSource && b.english > 80
                if (topVisible && first < 0) {
                    first = now
                    firstFrame = screen
                }
                val request = requests[page.source[index]]
                if (topVisible && bottomVisible && request != null && start + now >= request + 1800) {
                    full = now
                    sourcePct = 100.0 * (a.source + if (index == 0) 0 else b.source) / (topSource + bottomSource)
                    return@await true
                }
                false
            }
            // Read the settled letters independently of the first frame that was visible.
            SystemClock.sleep(180)
            inst.uiAutomation.takeScreenshot()?.let(::replaceLast)
            val result = requireNotNull(last)
            val topInk = page.ink(result, screenTop).source
            val bottomInk = if (index == 0) 0 else page.ink(result, screenBottom).source
            val settledInk = topInk + bottomInk
            val settledPct = 100.0 * settledInk / (topSource + bottomSource)
            val worstBalloon = maxOf(100.0 * topInk / topSource,
                if (index == 0) 0.0 else 100.0 * bottomInk / bottomSource)
            val targets = if (index == 0) listOf(screenTop to page.english.first())
                else listOf(screenTop to page.english.first(), screenBottom to page.english[index])
            for ((region, expected) in targets) {
                val read = reader.read(result, region)
                assertTrue("Wrong or clipped English on page " + index + ": " + read,
                    DisplayText.letters(read).contains(DisplayText.letters(expected)))
            }
            val requestMs = requests.getValue(page.source[index]) - start
            val row = listOf("media-projection", index, first, full, requestMs, sourcePct, settledPct, worstBalloon, guard).joinToString(",")
            metrics.appendText(row + "\n")
            Log.i("MangaLensUX", row)
            assertTrue("Source ink stays erased", settledPct < 10.0)
        } finally {
            save(raw, "page-" + index + "-raw.png")
            firstFrame?.let { save(it, "page-" + index + "-first.png") }
            last?.let { save(it, "page-" + index + "-screen.png") }
            if (full < 0) {
                val ocr = OcrEngine()
                try {
                    runBlocking {
                        for ((label, frame) in listOf("fixture" to raw, "display" to last)) {
                            if (frame != null) {
                                val read = ocr.recognize(frame, SourceLang.KO)
                                Log.i("MangaLensUX", label + " OCR: " + read.lines.map { it.text })
                            }
                        }
                    }
                    Log.i("MangaLensUX", "Page " + index + " first=" + first + " artwork error=" + guard)
                    UiDevice.getInstance(inst).dumpWindowHierarchy(File(output, "windows.xml"))
                } finally {
                    ocr.close()
                }
            }
            if (last !== firstFrame) last?.recycle()
            firstFrame?.recycle()
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
