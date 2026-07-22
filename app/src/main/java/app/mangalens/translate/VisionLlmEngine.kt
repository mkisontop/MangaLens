package app.mangalens.translate

import android.graphics.Bitmap
import android.util.Base64
import app.mangalens.settings.AppSettings
import app.mangalens.settings.SourceLang
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The "read the page like a human" engine: sends a downscaled screenshot to a
 * vision LLM which finds every bubble, reads the original art directly (no
 * on-device OCR in the loop), and translates with the full page — layout,
 * expressions, SFX — as context. This is what rescues vertical Japanese and
 * stylized lettering that ML Kit garbles.
 */
class VisionLlmEngine(
    private val settings: AppSettings,
    private val glossary: GlossaryStore? = null,
) {

    data class VisionBubble(
        /** Index of the on-device OCR region this translates, or -1 for text OCR missed. */
        val id: Int,
        /** Bubble box normalized to 0..1000 of the sent image; only trusted when id == -1. */
        val nx: Int,
        val ny: Int,
        val nw: Int,
        val nh: Int,
        val src: String,
        val en: String,
        val sfx: Boolean,
    )

    val label: String get() = LlmHttp.providerLabel(settings)

    val cacheNamespace: String get() = "Vision:" + label + ":" + settings.effectiveModel()

    suspend fun translatePage(
        bitmap: Bitmap,
        lang: SourceLang,
        anchors: List<app.mangalens.ocr.Bubble>,
    ): List<VisionBubble> =
        withContext(Dispatchers.IO) {
            LlmHttp.requireConfig(settings)

            val jpegB64 = encodePage(bitmap, settings.dataSaver)
            val langHint = when (lang) {
                SourceLang.KO -> "Korean"
                SourceLang.JA -> "Japanese"
                SourceLang.ZH -> "Chinese"
                SourceLang.AUTO -> "Korean, Japanese or Chinese"
            }
            // The AI reads and translates; on-device OCR owns the geometry.
            // Each detected region is an anchor the model answers by id, so
            // overlays land pixel-perfect even when the model's own sense of
            // image coordinates drifts.
            val regions = JSONArray()
            anchors.forEachIndexed { i, b ->
                regions.put(
                    JSONObject()
                        .put("id", i)
                        .put(
                            "box",
                            JSONArray()
                                .put(b.box.left * 1000 / bitmap.width)
                                .put(b.box.top * 1000 / bitmap.height)
                                .put(b.box.width() * 1000 / bitmap.width)
                                .put(b.box.height() * 1000 / bitmap.height)
                        )
                        .put("ocr_text_maybe_garbled", b.text)
                        .put("kind_guess", if (b.kind == app.mangalens.ocr.BubbleKind.SFX) "sfx" else "dialogue")
                )
            }
            val user = JSONObject()
                .put("expected_source_language", langHint)
                .put("glossary", JSONObject(glossary?.snapshot() ?: emptyMap<String, String>()))
                .put("recent_lines_for_context", JSONArray(StoryContext.snapshot()))
                .put("detected_regions", regions)
                .toString()

            val anthropicContent = JSONArray()
                .put(
                    JSONObject().put("type", "image").put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", "image/jpeg")
                            .put("data", jpegB64)
                    )
                )
                .put(JSONObject().put("type", "text").put("text", user))
            val openAiContent = JSONArray()
                .put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$jpegB64")
                    )
                )
                .put(JSONObject().put("type", "text").put("text", user))

            val raw = LlmHttp.complete(settings, SYSTEM_PROMPT, anthropicContent, openAiContent, maxTokens = 4000)
            val reply = LlmHttp.extractJsonObject(raw)

            val out = ArrayList<VisionBubble>()
            val seenIds = HashSet<Int>()
            val arr = reply.optJSONArray("bubbles") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("kind") == "skip") continue
                val en = o.optString("en", "").trim()
                if (en.isEmpty()) continue
                val sfx = o.optString("kind") == "sfx"
                val src = o.optString("src", "").trim()

                val id = o.optInt("id", -1)
                if (id in anchors.indices) {
                    if (!seenIds.add(id)) continue
                    out.add(VisionBubble(id, 0, 0, 0, 0, src, en, sfx))
                    continue
                }
                // Extra text the on-device OCR missed — here (and only here)
                // the model's own box is used.
                val box = o.optJSONArray("box") ?: continue
                if (box.length() < 4) continue
                val nx = box.optInt(0, -1)
                val ny = box.optInt(1, -1)
                val nw = box.optInt(2, 0)
                val nh = box.optInt(3, 0)
                if (nx !in 0..1000 || ny !in 0..1000 || nw <= 0 || nh <= 0) continue
                out.add(
                    VisionBubble(
                        -1, nx, ny,
                        nw.coerceAtMost(1000 - nx),
                        nh.coerceAtMost(1000 - ny),
                        src, en, sfx,
                    )
                )
            }
            reply.optJSONObject("new_terms")?.let { terms ->
                val learned = HashMap<String, String>()
                for (k in terms.keys()) learned[k] = terms.optString(k, "")
                glossary?.learn(learned)
            }
            out.forEach { if (it.src.isNotBlank()) StoryContext.remember(it.src, it.en) }
            out
        }

    companion object {

        /** JPEG-encodes the page, downscaled so slow uplinks stay usable. */
        fun encodePage(bitmap: Bitmap, dataSaver: Boolean): String {
            val maxDim = if (dataSaver) 1000 else 1400
            val quality = if (dataSaver) 55 else 72
            val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else bitmap
            val bytes = ByteArrayOutputStream().use { bos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                bos.toByteArray()
            }
            if (scaled !== bitmap) scaled.recycle()
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        private val SYSTEM_PROMPT = """
You are an elite manga/manhwa/manhua localization translator looking at one raw comic page screenshot. You also receive "detected_regions": text areas found by on-device OCR, each with an id, a box, and the OCR's (often garbled) reading.

Your job, for EVERY detected region: look at that spot in the image, read the original text directly from the art (trust the image over ocr_text_maybe_garbled), and translate it into natural English that reads like an official licensed release. Answer by region id — never restate or adjust the given boxes.

Rules:
- Reading order: manga (Japanese) right-to-left, top-to-bottom; webtoons/manhwa top-to-bottom. Vertical text reads columns right-to-left. Use the whole page and the story context to get tone and meaning right.
- Write the way real people speak: contractions, matching emotion and register per line. Keep honorifics that carry nuance (oppa, hyung, noona, -nim, senpai, -san, -sama, -chan, gege, shifu).
- Use the provided glossary EXACTLY for known names/terms; romanize new names sensibly.
- Sound effects: punchy comic onomatopoeia in CAPS (WHAM, BA-DUMP, KRAK) with "kind":"sfx". Use "kind":"skip" for regions that are UI scraps, watermarks, page numbers, or decorative/unreadable SFX.
- If real comic text is visible that has NO detected region, add an entry WITHOUT an id and WITH "box":[x,y,width,height], each value 0-1000 normalized to the FULL image including any dark background (x and width against image width, y and height against image height). Never add boxes for app/browser UI.
- "src" is the original text as printed. Keep lines tight; no notes, no romanization in "en".

Respond with ONLY this JSON object, no markdown fences:
{"bubbles":[{"id":<region id>,"src":"<original>","en":"<English>","kind":"dialogue|sfx|skip"}, ...,{"box":[x,y,w,h],"src":"...","en":"...","kind":"dialogue"}],"new_terms":{"<source name/term>":"<English>"}}
- One entry per detected region id (plus any no-id extras), in reading order.
- "new_terms": only newly established proper nouns/terms not already in the glossary.
""".trim()
    }
}
