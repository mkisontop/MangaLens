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
import app.mangalens.translate.GeminiApi
import app.mangalens.translate.GeminiBlocked
import app.mangalens.translate.GeminiModelMissing
import java.io.ByteArrayOutputStream
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
 * An image model can draw it, in a few seconds, well after the English is
 * already up; the cleaned ground then slides in under the same English, in
 * the same place.
 *
 * The model is shown only the lettering that needs it — crops around those
 * pieces, packed into one collage — never the page. Image models screen
 * what they are shown, and much of what is read with this app is explicit:
 * a whole page is refused far more often than the corner of it a caption
 * sits on, and a crop goes up at the page's own resolution, where the page
 * would have been scaled down to the model's size. Refusals are expected
 * and cost nothing but the redraw: the local reconstruction simply stays,
 * and after a couple in a row the clean-up rests for a while rather than
 * asking again for every page of a work it will not draw.
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
     * A copy of [page] in which each of [regions] — with a margin of the
     * art around it — has been redrawn by the image model without its
     * lettering, or null when the model failed, declined or is resting.
     * Everything outside the regions is the page's own pixels.
     *
     * Every region given is sent: the caller keeps to [MAX_REGIONS], the
     * busiest first, and refines only those. A region left out here and
     * refined anyway, its erasure half inside a neighbour's crop, passed
     * [refine] and painted its own lettering back where the crop ended.
     */
    suspend fun cleanRegions(page: Bitmap, regions: List<Rect>): Bitmap? {
        if (resting()) return null
        val model = model() ?: return null
        val plan = plan(page.width, page.height, regions) ?: return null
        var collage: Bitmap? = null
        return try {
            collage = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888).also { c ->
                val canvas = Canvas(c)
                canvas.drawColor(Color.WHITE)
                for (crop in plan.crops) canvas.drawBitmap(page, crop.src, crop.dst, FILTER)
            }
            val body = request(encode(collage))
            val reply = try {
                transport.generate(model, body)
            } catch (e: GeminiModelMissing) {
                if (model == FALLBACK_MODEL) throw e
                transport.generate(FALLBACK_MODEL, body)
            }
            val bytes = imageBytes(reply)
            if (bytes == null) {
                // A refusal comes back as an ordinary reply, with no image
                // and the reason beside it; it counts as much as a thrown one.
                GeminiApi.refusal(reply)?.let { throw GeminiBlocked(it) }
                return null
            }
            val drawn = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            // The model keeps the collage's framing, measured to within a
            // pixel, but not its resolution: its crops are found by scale.
            val sx = drawn.width.toFloat() / plan.width
            val sy = drawn.height.toFloat() / plan.height
            val out = page.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(out)
            for (crop in plan.crops) {
                val from = Rect(
                    (crop.dst.left * sx).roundToInt(), (crop.dst.top * sy).roundToInt(),
                    (crop.dst.right * sx).roundToInt(), (crop.dst.bottom * sy).roundToInt(),
                )
                canvas.drawBitmap(drawn, from, crop.src, FILTER)
            }
            drawn.recycle()
            refusals = 0
            out
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeminiBlocked) {
            refused()
            null
        } catch (e: Throwable) {
            null
        } finally {
            collage?.recycle()
        }
    }

    /** One region of the page and where it sits in the collage. */
    internal class Crop(val src: Rect, val dst: Rect)

    internal class Plan(val width: Int, val height: Int, val crops: List<Crop>)

    private fun request(jpegB64: String): JSONObject = JSONObject()
        .put(
            "contents",
            JSONArray().put(
                JSONObject().put(
                    "parts",
                    JSONArray()
                        .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", jpegB64)))
                        .put(JSONObject().put("text", COLLAGE_PROMPT)),
                ),
            ),
        )
        .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("IMAGE")))

    companion object {

        /** Nano Banana 2 Lite: measured 3.8-5 s for a page. */
        internal const val MODEL = "gemini-3.1-flash-lite-image"
        internal const val FALLBACK_MODEL = "gemini-2.5-flash-image"

        /**
         * Art kept around each region, so the model sees what continues
         * under the lettering: measured, a thin 24 px frame round a line
         * of text left the model returning the crop untouched, where 60 px
         * had it removed the lettering and redrawn the ground.
         */
        private fun margin(r: Rect): Int = (56 + minOf(r.width(), r.height()) / 4).coerceAtMost(120)

        /** White gutter between crops in the collage. */
        private const val GUTTER = 24

        /** Most regions to send in one collage, the busiest first; the caller keeps to it. */
        internal const val MAX_REGIONS = 8

        /** Longest side of the collage; larger ones are scaled down to it. */
        private const val MAX_COLLAGE = 1280

        /** Refusals in a row after which the clean-up rests, and for how long. */
        private const val REFUSALS_TO_REST = 2
        private const val REST_MS = 15 * 60_000L

        private val FILTER = Paint(Paint.FILTER_BITMAP_FLAG)

        @Volatile
        private var refusals = 0

        @Volatile
        private var restUntil = 0L

        private fun now() = System.nanoTime() / 1_000_000

        private fun resting(): Boolean = now() < restUntil

        private fun refused() {
            if (++refusals >= REFUSALS_TO_REST) {
                refusals = 0
                restUntil = now() + REST_MS
            }
        }

        /** Lets the clean-up run again at once; for tests. */
        internal fun resetRest() {
            refusals = 0
            restUntil = 0L
        }

        /**
         * Where each region goes in the collage: regions grown by their
         * margin and merged where they overlap, packed in rows under a
         * white gutter, the whole scaled down only when it outgrows
         * [MAX_COLLAGE]. Never padded out to a squarer shape: given empty
         * white space, the model paints new art into it and redraws the
         * crops at a new size. Null when nothing is left to send.
         */
        internal fun plan(pageW: Int, pageH: Int, regions: List<Rect>): Plan? {
            val grown = ArrayList<Rect>()
            for (r in regions) {
                val m = margin(r)
                val g = Rect(
                    (r.left - m).coerceAtLeast(0), (r.top - m).coerceAtLeast(0),
                    (r.right + m).coerceAtMost(pageW), (r.bottom + m).coerceAtMost(pageH),
                )
                if (g.width() < 8 || g.height() < 8) continue
                // Overlapping regions go up as one, so no strip of art is
                // redrawn twice by two crops that disagree.
                var merged = g
                var again = true
                while (again) {
                    again = false
                    val it = grown.iterator()
                    while (it.hasNext()) {
                        val o = it.next()
                        if (Rect.intersects(o, merged)) {
                            merged = Rect(merged).apply { union(o) }
                            it.remove()
                            again = true
                        }
                    }
                }
                grown.add(merged)
            }
            if (grown.isEmpty()) return null
            val sorted = grown.sortedByDescending { it.height() }
            val rowWidth = maxOf(MAX_COLLAGE, sorted.maxOf { it.width() } + 2 * GUTTER)
            val placed = ArrayList<Crop>()
            var x = GUTTER
            var y = GUTTER
            var rowH = 0
            var width = 0
            for (src in sorted) {
                if (x + src.width() + GUTTER > rowWidth && x > GUTTER) {
                    x = GUTTER
                    y += rowH + GUTTER
                    rowH = 0
                }
                placed.add(Crop(src, Rect(x, y, x + src.width(), y + src.height())))
                x += src.width() + GUTTER
                width = maxOf(width, x)
                rowH = maxOf(rowH, src.height())
            }
            val height = y + rowH + GUTTER
            val scale = (MAX_COLLAGE.toFloat() / maxOf(width, height)).coerceAtMost(1f)
            if (scale >= 1f) return Plan(width, height, placed)
            fun s(v: Int) = (v * scale).roundToInt()
            return Plan(
                s(width).coerceAtLeast(1), s(height).coerceAtLeast(1),
                placed.map { c -> Crop(c.src, Rect(s(c.dst.left), s(c.dst.top), s(c.dst.right), s(c.dst.bottom))) },
            )
        }

        /** Ring outside the mask, in pixels, in which the redraw must agree with the page. */
        private const val RING = 4

        /** Median and upper-quantile colour distance the ring may show and still agree. */
        private const val RING_MEDIAN = 16
        private const val RING_P80 = 36

        /** Share of the mask the redraw may leave as it was before the lettering is judged still there. */
        private const val MAX_UNCHANGED = 0.35f

        private val COLLAGE_PROMPT = """
This image is a collage of separate crops cut from one comic page, laid out on a white background. In every crop, remove ALL lettering — words, captions, sound effects, symbols drawn as text — and where a letter was, draw what would naturally be behind it, continuing the surrounding art, colour, shading and screentone.
Change nothing else. Every crop stays exactly where it is, at the same size, with the same art, line weight and colours; balloon and box outlines stay as drawn, only empty; the white background stays white. Do not add, remove, move or restyle anything.
""".trim()

        /**
         * The image model to ask: the newest one Google has not said is
         * gone, or null when both are. A model that is gone is not asked
         * again: the answer would not change, and every page would wait for it.
         */
        private fun model(): String? = listOf(MODEL, FALLBACK_MODEL).firstOrNull { !GeminiApi.isMissing(it) }

        /** Whether AI clean-up is available, with an image model left to ask, and switched on. */
        fun supports(settings: AppSettings): Boolean =
            settings.provider == LlmProvider.GEMINI && settings.aiCleanup && settings.apiKey.isNotBlank() && !resting() &&
                model() != null

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
            // Cells within [RING] of the mask (Chebyshev) but outside it.
            val grown = TextEraser.dilate(mask, w, h, RING)
            val ring = BooleanArray(w * h) { grown[it] && !mask[it] }

            val ringDist = ArrayList<Int>()
            for (i in 0 until w * h) if (ring[i]) ringDist.add(TextEraser.dist(orig[i], redraw[i]))
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
                if (TextEraser.dist(orig[i], redraw[i]) < 20) unchanged++
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

        private fun encode(collage: Bitmap): String {
            val bytes = ByteArrayOutputStream().use { bos ->
                collage.compress(Bitmap.CompressFormat.JPEG, 90, bos)
                bos.toByteArray()
            }
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
