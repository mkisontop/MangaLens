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
 * vision LLM which reads the original art directly (trusting it over on-device
 * OCR) and translates with the full page — layout, expressions, SFX — as
 * context. This is what rescues vertical Japanese and stylized lettering that
 * ML Kit garbles.
 *
 * Three things make the answers land on the right balloon and say the right
 * thing: the page is marked with visible region ids ([PageMarkup]) so the
 * model never has to infer which text an id refers to; every dialogue region
 * comes back attributed to a speaker, which is what resolves the subjects
 * Japanese and Korean omit; and bubbles carrying one split sentence are sent
 * as a linked run so a tail clause is never translated as a sentence.
 */
class VisionLlmEngine(
    private val settings: AppSettings,
    private val glossary: GlossaryStore? = null,
    private val cast: CastBook? = null,
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
        /** Character the line was attributed to; blank when unattributed. */
        val who: String = "",
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

            // The page the model sees carries a numbered badge per region, so
            // "region 7" is visible rather than inferred from coordinates.
            val jpegB64 = PageMarkup.encodeMarkedPage(bitmap, anchors, settings.dataSaver)
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
                val o = JSONObject()
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
                if (b.runId >= 0) o.put("run", b.runId).put("part", b.runPart)
                regions.put(o)
            }
            val user = JSONObject()
                .put("expected_source_language", langHint)
                .put("glossary", JSONObject(glossary?.snapshot() ?: emptyMap<String, String>()))
                .put("characters", JSONObject(cast?.describeAll() ?: emptyMap<String, String>()))
                .put("story_so_far", JSONArray(StoryContext.snapshot()))
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
                val who = o.optString("who", "").trim().take(24)

                val id = o.optInt("id", -1)
                if (id in anchors.indices) {
                    if (!seenIds.add(id)) continue
                    out.add(VisionBubble(id, 0, 0, 0, 0, src, en, sfx, who))
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
                        src, en, sfx, who,
                    )
                )
            }
            reply.optJSONObject("new_terms")?.let { terms ->
                val learned = HashMap<String, String>()
                for (k in terms.keys()) learned[k] = terms.optString(k, "")
                glossary?.learn(learned)
            }
            cast?.learn(CastBook.parse(reply.optJSONObject("characters")))
            out.forEach { if (!it.sfx) StoryContext.remember(it.en, it.who) }
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
You are an elite manga/manhwa/manhua localization translator looking at one raw comic page screenshot. Your output is typeset straight onto the page, so it must be correct the first time.

READING THE IMAGE
Every region is outlined in magenta and labelled with its region id on a magenta badge at the region's top-left corner. For each region, read the original lettering under that outline directly from the art. "ocr_text_maybe_garbled" is a hint only — it is frequently wrong on vertical, stylized, handwritten and overlapping text, and the image always wins. Answer each region by its badge number. Never restate or adjust the given boxes.
An outline usually marks a whole speech balloon. Everything inside it is ONE character's line, however many columns or lines it is set in — read the columns in order (vertical text runs top-to-bottom, columns right-to-left) and translate the balloon as a single utterance. Do not translate a column or a fragment as if it were a sentence on its own.
"ocr_text_maybe_garbled" is empty when on-device OCR could not read the region at all. That is normal on vertical and hand-lettered text and does NOT mean the region is empty — read it from the image. Answer with "kind":"skip" only if there is genuinely no readable text there.
Answer EVERY region. A region you leave out is left untranslated on the page.

WHO IS SPEAKING — decide this before you translate
For every dialogue region, work out which character says it, from balloon tail direction, who is drawn mid-gesture or mouth-open, eye lines, and turn-taking with "story_so_far". Return it as "who" (use the established English name, or a stable short descriptor like "tall boy" when the character is unnamed).
This matters because Japanese, Korean and Chinese omit the subject constantly. Resolve the omitted subject from the speaker, who they are addressing, and the story so far — then commit to it. If it is genuinely unresolvable, use a subjectless English phrasing ("Not going back." / "Can't do it.") rather than inventing a pronoun.
Honour "characters" exactly: once a character has a pronoun there, keep it. Never re-decide a character's gender from one page to the next — a consistent pronoun matters more than a freshly-guessed one.

SPLIT SENTENCES
Regions that share a "run" value are ONE sentence broken across balloons, in "part" order. Translate the whole sentence first, then split the English across the parts so each balloon carries its share and they read continuously in sequence. Never translate a part as though it were a complete sentence, and never repeat the whole sentence in every part.

VOICE
- Write the way real people speak: contractions, and each line's own emotion — shouting, whispering, teasing, panicking.
- Match each speaker's register from "characters" (blunt/casual/formal/deferential). A character's voice should be recognisable across pages.
- Keep honorifics that carry nuance (oppa, hyung, noona, unnie, -nim, -ssi, senpai, -san, -sama, -chan, gege, jiejie, shifu).
- Use "glossary" EXACTLY for known names/terms; romanize new names sensibly.
- Keep lines as tight as real typeset dialogue. No translator notes, no romanization in "en".

SOUND EFFECTS
Punchy comic onomatopoeia in CAPS (WHAM, BA-DUMP, KRAK) with "kind":"sfx". Japanese SFX cover states as well as sounds — silence (シーン), staring (ジー), nervousness (ドキドキ) — so translate the effect, not a literal noise. Use "kind":"skip" for UI scraps, watermarks, page numbers and decorative or unreadable SFX.

MISSED TEXT
If real comic text is visible with NO magenta outline, add an entry WITHOUT an id and WITH "box":[x,y,width,height], each value 0-1000 normalized to the FULL image (x and width against image width, y and height against image height). Never add boxes for app or browser UI.

Respond with ONLY this JSON object, no markdown fences:
{"bubbles":[{"id":<region id>,"who":"<speaker>","src":"<original>","en":"<English>","kind":"dialogue|sfx|skip"}, ...,{"box":[x,y,w,h],"who":"...","src":"...","en":"...","kind":"dialogue"}],"new_terms":{"<source name/term>":"<English>"},"characters":{"<English name>":{"pronoun":"he|she|they","register":"<how they speak>","note":"<role or relationship>"}}}
- One entry per detected region id (plus any no-id extras), in reading order.
- "new_terms": only newly established proper nouns/terms not already in the glossary.
- "characters": only characters appearing on THIS page whose pronoun or register is not already recorded, or whom you can now describe more precisely.
""".trim()
    }
}
