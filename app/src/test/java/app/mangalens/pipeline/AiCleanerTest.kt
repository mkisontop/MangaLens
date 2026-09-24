package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import app.mangalens.translate.FakeHttpServer
import app.mangalens.translate.GeminiApi
import app.mangalens.translate.GeminiBlocked
import app.mangalens.translate.GeminiModelMissing
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The image model's redraw is used only where it can be trusted: inside the
 * lettering's own mask, and only when it agrees with the page just outside
 * that mask. A redraw that moved or restyled the art there, or that left
 * the lettering standing, is refused and the local reconstruction stays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiCleanerTest {

    private val w = 400
    private val h = 300
    private val rect = Rect(100, 100, 300, 200)

    /** Striped "art" with a white bar of "lettering" across the middle of [rect]. */
    private fun art(stripes: Int = Color.rgb(40, 90, 160), lettering: Boolean = true): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(230, 170, 60))
        val p = Paint().apply { color = stripes }
        var x = 0
        while (x < w) {
            c.drawRect(x.toFloat(), 0f, x + 6f, h.toFloat(), p)
            x += 14
        }
        if (lettering) c.drawRect(140f, 140f, 260f, 160f, Paint().apply { color = Color.WHITE })
        return bmp
    }

    /** The erasure the local eraser would have made: the lettering bar, grown a pixel. */
    private fun erasure(): Erasure {
        val rw = rect.width()
        val rh = rect.height()
        val mask = BooleanArray(rw * rh) { i ->
            val x = rect.left + i % rw
            val y = rect.top + i / rw
            x in 139..260 && y in 139..160
        }
        val patch = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
        return Erasure(rect, patch, mask, flat = false, background = Color.rgb(230, 170, 60),
            textColor = Color.WHITE, outlineColor = null, busy = 0.8f)
    }

    @Test
    fun aFaithfulRedrawReplacesTheLocalReconstruction() {
        val page = art()
        val cleaned = art(lettering = false)
        val refined = AiCleaner.refine(page, cleaned, erasure())
        assertNotNull(refined)
        assertEquals(0f, refined!!.busy)
        // Inside the mask the patch now carries the redrawn art, stripes and all.
        val px = refined.patch.getPixel(150 - rect.left, 150 - rect.top)
        assertEquals(cleaned.getPixel(150, 150) or (0xFF shl 24), px)
        // Outside the mask it stays transparent: the page itself shows there.
        assertEquals(0, Color.alpha(refined.patch.getPixel(5, 5)))
    }

    @Test
    fun aRedrawThatChangedTheArtBesideTheLetteringIsRefused() {
        val page = art()
        // The model recoloured the art: the ring around the lettering disagrees.
        val cleaned = art(stripes = Color.rgb(200, 30, 30), lettering = false)
        assertNull(AiCleaner.refine(page, cleaned, erasure()))
    }

    @Test
    fun aRedrawThatLeftTheLetteringIsRefused() {
        val page = art()
        assertNull(AiCleaner.refine(page, art(), erasure()))
    }

    private fun jpegB64(bmp: Bitmap): String {
        val bytes = ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun reply(img: Bitmap) = JSONObject().put(
        "candidates",
        JSONArray().put(
            JSONObject().put(
                "content",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("inlineData", JSONObject().put("data", jpegB64(img))))),
            ),
        ),
    )

    /** The collage the request carried, decoded. */
    private fun sentCollage(body: JSONObject): Bitmap {
        val data = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
            .getJSONObject(0).getJSONObject("inlineData").getString("data")
        val bytes = Base64.decode(data, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private val settings = AppSettings(provider = LlmProvider.GEMINI, apiKey = "k", aiCleanup = true)

    @Before
    fun rested() {
        AiCleaner.resetRest()
        GeminiApi.resetLearned()
    }

    @After
    fun forget() {
        AiCleaner.resetRest()
        GeminiApi.resetLearned()
    }

    @Test
    fun onlyTheLetteringAndItsSurroundingsAreSent() {
        val plan = AiCleaner.plan(1080, 2400, listOf(Rect(100, 100, 300, 160), Rect(280, 150, 400, 210), Rect(500, 2000, 900, 2100)))!!
        // The two overlapping regions go up as one crop; the page itself never does.
        assertEquals(2, plan.crops.size)
        val areaSent = plan.crops.sumOf { it.src.width() * it.src.height() }
        assertTrue(areaSent < 1080 * 2400 / 10)
        for (c in plan.crops) {
            assertEquals(c.src.width(), c.dst.width())
            assertEquals(c.src.height(), c.dst.height())
        }
    }

    @Test
    fun theRedrawLandsOnlyWhereTheRegionsAre() = runBlocking {
        val page = art()
        // A model that returns its collage at half size, every crop whitened.
        val cleaner = AiCleaner(settings) { _, body ->
            val sent = sentCollage(body)
            val white = Bitmap.createBitmap(sent.width / 2, sent.height / 2, Bitmap.Config.ARGB_8888)
            Canvas(white).drawColor(Color.WHITE)
            reply(white)
        }
        val out = cleaner.cleanRegions(page, listOf(Rect(150, 140, 250, 160)))
        assertNotNull(out)
        assertEquals(w, out!!.width)
        assertEquals(h, out.height)
        // Inside the region: the redraw. Far outside it: the page's own pixels.
        assertEquals(Color.WHITE, out.getPixel(200, 150))
        assertEquals(page.getPixel(20, 20), out.getPixel(20, 20))
        assertEquals(page.getPixel(390, 290), out.getPixel(390, 290))
    }

    @Test
    fun aRetiredImageModelFallsBackToTheOlderOne() = runBlocking {
        val asked = ArrayList<String>()
        val cleaner = AiCleaner(settings) { model, body ->
            asked.add(model)
            if (model == AiCleaner.MODEL) throw GeminiModelMissing(model, 404, "gone")
            reply(sentCollage(body))
        }
        assertNotNull(cleaner.cleanRegions(art(), listOf(rect)))
        assertEquals(listOf(AiCleaner.MODEL, AiCleaner.FALLBACK_MODEL), asked)
    }

    @Test
    fun aRefusalOrFailureIsNoRedrawRatherThanAnError() = runBlocking {
        val empty = AiCleaner(settings) { _, _ -> JSONObject().put("candidates", JSONArray()) }
        assertNull(empty.cleanRegions(art(), listOf(rect)))
        val failing = AiCleaner(settings) { _, _ -> throw RuntimeException("Gemini HTTP 500") }
        assertNull(failing.cleanRegions(art(), listOf(rect)))
        assertTrue("a server error is not a refusal", AiCleaner.supports(settings))
    }

    @Test
    fun aWorkTheModelWillNotDrawIsNotAskedAboutEveryPage() = runBlocking {
        var calls = 0
        val refusing = AiCleaner(settings) { _, _ ->
            calls++
            throw GeminiBlocked("IMAGE_SAFETY")
        }
        assertNull(refusing.cleanRegions(art(), listOf(rect)))
        assertTrue(AiCleaner.supports(settings))
        assertNull(refusing.cleanRegions(art(), listOf(rect)))
        // Two refusals in a row: the clean-up rests instead of asking again.
        assertFalse(AiCleaner.supports(settings))
        assertNull(refusing.cleanRegions(art(), listOf(rect)))
        assertEquals(2, calls)
    }

    /** A refusal as Google really sends it: HTTP 200, no image, the reason beside it. */
    private fun blockedPrompt(reason: String) = JSONObject()
        .put("promptFeedback", JSONObject().put("blockReason", reason))

    private fun stopped(finish: String) = JSONObject().put(
        "candidates",
        JSONArray().put(
            JSONObject()
                .put("finishReason", finish)
                .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "I can't edit this image."))))
        ),
    )

    @Test
    fun aRefusalAnsweredAsAnOrdinaryReplyStillCountsTowardsTheRest() = runBlocking {
        for (answer in listOf(blockedPrompt("PROHIBITED_CONTENT"), stopped("IMAGE_SAFETY"), stopped("PROHIBITED_CONTENT"))) {
            AiCleaner.resetRest()
            var calls = 0
            val refusing = AiCleaner(settings) { _, _ ->
                calls++
                answer
            }
            assertNull(refusing.cleanRegions(art(), listOf(rect)))
            assertTrue(AiCleaner.supports(settings))
            assertNull(refusing.cleanRegions(art(), listOf(rect)))
            assertFalse("rests after two refusals: $answer", AiCleaner.supports(settings))
            assertNull(refusing.cleanRegions(art(), listOf(rect)))
            assertEquals(2, calls)
        }
    }

    @Test
    fun aReplyWithNoImageAndNoReasonIsNotARefusal() = runBlocking {
        var calls = 0
        val wordy = AiCleaner(settings) { _, _ ->
            calls++
            stopped("STOP")
        }
        repeat(3) { assertNull(wordy.cleanRegions(art(), listOf(rect))) }
        assertTrue(AiCleaner.supports(settings))
        assertEquals(3, calls)
    }

    @Test
    fun aRetiredImageModelIsNotAskedAgainOnTheNextPage() = runBlocking {
        val white = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val drawn = reply(white).toString()
        val server = FakeHttpServer { ex ->
            if (ex.target.contains("/" + AiCleaner.MODEL + ":")) {
                ex.respond(404, "{\"error\":{\"code\":404,\"message\":\"models/${AiCleaner.MODEL} is not found\"}}")
            } else {
                ex.respond(200, drawn)
            }
        }
        GeminiApi.base = server.base
        try {
            val cleaner = AiCleaner(settings)
            assertNotNull(cleaner.cleanRegions(art(), listOf(rect)))
            assertNotNull(cleaner.cleanRegions(art(), listOf(rect)))
            val asked = server.exchanges.map { it.target.substringAfterLast('/').substringBefore(':') }
            assertEquals(listOf(AiCleaner.MODEL, AiCleaner.FALLBACK_MODEL, AiCleaner.FALLBACK_MODEL), asked)
        } finally {
            server.close()
            GeminiApi.base = GeminiApi.BASE
        }
    }

    @Test
    fun cleanUpIsOnlyOfferedWithGeminiAndAKey() {
        assertTrue(AiCleaner.supports(settings))
        assertFalse(AiCleaner.supports(settings.copy(aiCleanup = false)))
        assertFalse(AiCleaner.supports(settings.copy(apiKey = "")))
        assertFalse(AiCleaner.supports(settings.copy(provider = LlmProvider.ANTHROPIC)))
    }
}
