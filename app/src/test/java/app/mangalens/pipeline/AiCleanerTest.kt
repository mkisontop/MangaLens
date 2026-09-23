package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import app.mangalens.translate.GeminiModelMissing
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private val settings = AppSettings(provider = LlmProvider.GEMINI, apiKey = "k", aiCleanup = true)

    @Test
    fun theRedrawComesBackAtThePagesOwnSize() = runBlocking {
        val half = Bitmap.createScaledBitmap(art(lettering = false), w / 2, h / 2, true)
        val cleaner = AiCleaner(settings) { _, _ -> reply(half) }
        val out = cleaner.cleanPage(art())
        assertNotNull(out)
        assertEquals(w, out!!.width)
        assertEquals(h, out.height)
    }

    @Test
    fun aRetiredImageModelFallsBackToTheOlderOne() = runBlocking {
        val asked = ArrayList<String>()
        val cleaner = AiCleaner(settings) { model, _ ->
            asked.add(model)
            if (model == AiCleaner.MODEL) throw GeminiModelMissing(model, 404, "gone")
            reply(art(lettering = false))
        }
        assertNotNull(cleaner.cleanPage(art()))
        assertEquals(listOf(AiCleaner.MODEL, AiCleaner.FALLBACK_MODEL), asked)
    }

    @Test
    fun aRefusalOrFailureIsNoRedrawRatherThanAnError() = runBlocking {
        val empty = AiCleaner(settings) { _, _ -> JSONObject().put("candidates", JSONArray()) }
        assertNull(empty.cleanPage(art()))
        val failing = AiCleaner(settings) { _, _ -> throw RuntimeException("Gemini HTTP 500") }
        assertNull(failing.cleanPage(art()))
    }

    @Test
    fun cleanUpIsOnlyOfferedWithGeminiAndAKey() {
        assertTrue(AiCleaner.supports(settings))
        assertFalse(AiCleaner.supports(settings.copy(aiCleanup = false)))
        assertFalse(AiCleaner.supports(settings.copy(apiKey = "")))
        assertFalse(AiCleaner.supports(settings.copy(provider = LlmProvider.ANTHROPIC)))
    }
}
