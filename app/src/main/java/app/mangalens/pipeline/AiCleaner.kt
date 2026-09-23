package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import app.mangalens.translate.GeminiApi
import app.mangalens.translate.GeminiModelMissing
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * The art under lettering, redrawn by an image model.
 *
 * Lettering drawn straight onto detailed art — a shout over an explosion,
 * white-outlined thoughts over a figure — can only be smoothed over by a
 * local reconstruction: the art behind the strokes was never on the page.
 * An image model can draw it. It is asked for the whole page with the
 * lettering removed, which it does in a few seconds, well after the
 * English is already up; the cleaned ground then slides in under the same
 * English, in the same place.
 *
 * The model is never trusted with the page itself. Image models redraw
 * freely — measured here: one deleted a whole jagged shout balloon, another
 * added mountains to an empty sky — so its output is used only inside each
 * lettering's own mask, and only where it agrees with the page in a ring
 * just outside that mask ([refine]). A model that moved or redrew the art
 * there is caught by its own disagreement and ignored.
 */
class AiCleaner internal constructor(
    private val settings: AppSettings,
    private val transport: Transport,
) {

    constructor(settings: AppSettings) : this(settings, Transport { model, body ->
        GeminiApi.generate(settings.apiKey, model, body)
    })

    /** One image request; the network, or a test's stand-in for it. */
    internal fun interface Transport {
        suspend fun generate(model: String, body: JSONObject): JSONObject
    }

    /**
     * The page with its lettering removed by the image model, scaled back to
     * [page]'s exact size, or null when the model failed or declined.
     */
    suspend fun cleanPage(page: Bitmap): Bitmap? {
        return try {
            val body = request(encode(page))
            val reply = try {
                transport.generate(MODEL, body)
            } catch (e: GeminiModelMissing) {
                transport.generate(FALLBACK_MODEL, body)
            }
            val bytes = imageBytes(reply) ?: return null
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            if (decoded.width == page.width && decoded.height == page.height) return decoded
            // Outputs keep the page's framing, measured to within a pixel,
            // but not its resolution.
            Bitmap.createScaledBitmap(decoded, page.width, page.height, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }

    private fun request(jpegB64: String): JSONObject = JSONObject()
        .put(
            "contents",
            JSONArray().put(
                JSONObject().put(
                    "parts",
                    JSONArray()
                        .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", jpegB64)))
                        .put(JSONObject().put("text", PROMPT)),
                ),
            ),
        )
        .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("IMAGE")))

    companion object {

        /** Nano Banana 2 Lite: measured 3.8-5 s for a page. */
        internal const val MODEL = "gemini-3.1-flash-lite-image"
        internal const val FALLBACK_MODEL = "gemini-2.5-flash-image"

        /** Long side of the page sent; the model draws at about this size whatever it is given. */
        private const val MAX_DIM = 1024

        /** Ring outside the mask, in pixels, in which the redraw must agree with the page. */
        private const val RING = 4

        /** Median and upper-quantile colour distance the ring may show and still agree. */
        private const val RING_MEDIAN = 16
        private const val RING_P80 = 36

        /** Share of the mask the redraw may leave as it was before the lettering is judged still there. */
        private const val MAX_UNCHANGED = 0.35f

        private val PROMPT = """
Remove ALL lettering from this comic page: speech-balloon text, captions, sound effects and any text drawn on the art. Where a letter was, draw what would naturally be behind it, continuing the surrounding art, colour, shading and screentone.
Change nothing else. Every speech balloon, caption box and panel border stays exactly where and as it is, with the same outline — only empty. Characters, backgrounds, colours, line weight and framing are unchanged. Do not add, remove, move or restyle anything. Same size and aspect ratio.
""".trim()

        /** Whether AI clean-up is available and switched on. */
        fun supports(settings: AppSettings): Boolean =
            settings.provider == LlmProvider.GEMINI && settings.aiCleanup && settings.apiKey.isNotBlank()

        /**
         * [erasure] with its reconstructed pixels taken from [cleaned] — but
         * only when [cleaned] agrees with [page] in the ring around the
         * lettering, which is how a model that redrew the art (dropped a
         * balloon, moved a line) is caught. Null when it does not agree, or
         * when the lettering is still there in the redraw.
         */
        fun refine(page: Bitmap, cleaned: Bitmap, erasure: Erasure): Erasure? {
            if (cleaned.width != page.width || cleaned.height != page.height) return null
            val r = erasure.rect
            val w = r.width()
            val h = r.height()
            if (w <= 0 || h <= 0 || erasure.mask.size < w * h) return null
            if (r.left < 0 || r.top < 0 || r.right > page.width || r.bottom > page.height) return null
            val orig = IntArray(w * h)
            val redraw = IntArray(w * h)
            page.getPixels(orig, 0, w, r.left, r.top, w, h)
            cleaned.getPixels(redraw, 0, w, r.left, r.top, w, h)
            val mask = erasure.mask
            val ring = ring(mask, w, h, RING)

            val ringDist = ArrayList<Int>()
            for (i in 0 until w * h) if (ring[i]) ringDist.add(distance(orig[i], redraw[i]))
            if (ringDist.size < 12) return null
            ringDist.sort()
            val median = ringDist[ringDist.size / 2]
            val p80 = ringDist[(ringDist.size * 8 / 10).coerceAtMost(ringDist.size - 1)]
            if (median > RING_MEDIAN || p80 > RING_P80) return null

            var masked = 0
            var unchanged = 0
            for (i in 0 until w * h) {
                if (!mask[i]) continue
                masked++
                if (distance(orig[i], redraw[i]) < 20) unchanged++
            }
            if (masked == 0 || unchanged > masked * MAX_UNCHANGED) return null

            val px = IntArray(w * h)
            for (i in 0 until w * h) if (mask[i]) px[i] = redraw[i] or (0xFF shl 24)
            val patch = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            return Erasure(
                rect = erasure.rect,
                patch = patch,
                mask = mask,
                flat = erasure.flat,
                background = erasure.background,
                textColor = erasure.textColor,
                outlineColor = erasure.outlineColor,
                busy = 0f,
            )
        }

        /** Largest per-channel difference between two colours. */
        private fun distance(a: Int, b: Int): Int = max(
            abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)),
            max(abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)), abs((a and 0xFF) - (b and 0xFF))),
        )

        /** Cells within [radius] (Chebyshev) of the mask but outside it. */
        private fun ring(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
            // Two passes of a separable max filter: rows, then columns.
            val rows = BooleanArray(w * h)
            for (y in 0 until h) {
                var last = -radius - 1
                for (x in 0 until w) {
                    if (mask[y * w + x]) last = x
                    if (x - last <= radius) rows[y * w + x] = true
                }
                last = w + radius + 1
                for (x in w - 1 downTo 0) {
                    if (mask[y * w + x]) last = x
                    if (last - x <= radius) rows[y * w + x] = true
                }
            }
            val grown = BooleanArray(w * h)
            for (x in 0 until w) {
                var last = -radius - 1
                for (y in 0 until h) {
                    if (rows[y * w + x]) last = y
                    if (y - last <= radius) grown[y * w + x] = true
                }
                last = h + radius + 1
                for (y in h - 1 downTo 0) {
                    if (rows[y * w + x]) last = y
                    if (last - y <= radius) grown[y * w + x] = true
                }
            }
            return BooleanArray(w * h) { grown[it] && !mask[it] }
        }

        private fun encode(page: Bitmap): String {
            val scale = (MAX_DIM.toFloat() / max(page.width, page.height)).coerceAtMost(1f)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    page,
                    (page.width * scale).roundToInt().coerceAtLeast(1),
                    (page.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
            } else {
                page
            }
            val bytes = ByteArrayOutputStream().use { bos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, bos)
                bos.toByteArray()
            }
            if (scaled !== page) scaled.recycle()
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        /** The first image part of a generateContent reply. */
        private fun imageBytes(reply: JSONObject): ByteArray? {
            val candidates = reply.optJSONArray("candidates") ?: return null
            for (c in 0 until candidates.length()) {
                val parts = candidates.optJSONObject(c)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                for (p in 0 until parts.length()) {
                    val data = parts.optJSONObject(p)?.optJSONObject("inlineData")?.optString("data").orEmpty()
                    if (data.isNotEmpty()) return runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()
                }
            }
            return null
        }
    }
}
