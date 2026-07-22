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
        /** Bubble box normalized to 0..1000 of the sent image (aspect preserved). */
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

    suspend fun translatePage(bitmap: Bitmap, lang: SourceLang): List<VisionBubble> =
        withContext(Dispatchers.IO) {
            LlmHttp.requireConfig(settings)

            val jpegB64 = encodePage(bitmap, settings.dataSaver)
            val langHint = when (lang) {
                SourceLang.KO -> "Korean"
                SourceLang.JA -> "Japanese"
                SourceLang.ZH -> "Chinese"
                SourceLang.AUTO -> "Korean, Japanese or Chinese"
            }
            val user = JSONObject()
                .put("expected_source_language", langHint)
                .put("glossary", JSONObject(glossary?.snapshot() ?: emptyMap<String, String>()))
                .put("recent_lines_for_context", JSONArray(StoryContext.snapshot()))
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
            val arr = reply.optJSONArray("bubbles") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("kind") == "skip") continue
                val box = o.optJSONArray("box") ?: continue
                if (box.length() < 4) continue
                val nx = box.optInt(0, -1)
                val ny = box.optInt(1, -1)
                val nw = box.optInt(2, 0)
                val nh = box.optInt(3, 0)
                if (nx !in 0..1000 || ny !in 0..1000 || nw <= 0 || nh <= 0) continue
                val en = o.optString("en", "").trim()
                if (en.isEmpty()) continue
                val sfx = o.optString("kind") == "sfx"
                out.add(
                    VisionBubble(
                        nx, ny,
                        nw.coerceAtMost(1000 - nx),
                        nh.coerceAtMost(1000 - ny),
                        o.optString("src", "").trim(),
                        en,
                        sfx,
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
You are an elite manga/manhwa/manhua localization translator looking at one raw comic page screenshot. Find every speech bubble, thought bubble, narration box and meaningful sound effect, read the original text directly from the art, and translate it into natural English that reads like an official licensed release.

Rules:
- Reading order: manga (Japanese) right-to-left, top-to-bottom; webtoons/manhwa top-to-bottom. Vertical text reads columns right-to-left.
- Write the way real people speak: contractions, matching emotion and register per line. Keep honorifics that carry nuance (oppa, hyung, noona, -nim, senpai, -san, -sama, -chan, gege, shifu).
- Use the provided glossary EXACTLY for known names/terms; romanize new names sensibly.
- Sound effects: translate as punchy comic onomatopoeia in CAPS (WHAM, BA-DUMP, KRAK) with "kind":"sfx". Skip decorative or unreadable SFX.
- IGNORE app/browser UI, status bars, page numbers, watermarks, and anything that is not part of the comic art.
- "box" is [x,y,width,height] of the text's bounding area, each 0-1000 normalized to the image (x,width against image width; y,height against image height). Cover the text tightly.
- "src" is the original text as printed. Keep lines tight; no notes, no romanization in "en".

Respond with ONLY this JSON object, no markdown fences:
{"bubbles":[{"box":[x,y,w,h],"src":"<original>","en":"<English>","kind":"dialogue|sfx|skip"}...],"new_terms":{"<source name/term>":"<English>"}}
- Bubbles in reading order. "new_terms": only newly established proper nouns/terms not already in the glossary.
""".trim()
    }
}
