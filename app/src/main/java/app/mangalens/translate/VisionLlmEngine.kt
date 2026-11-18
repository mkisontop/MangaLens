package app.mangalens.translate

import android.graphics.Bitmap
import android.graphics.Rect
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

    /**
     * @param onBubble receives each answered region the moment the model
     *   finishes writing it, so the page can be painted while the rest of
     *   the reply is still streaming. The returned list is the complete,
     *   final answer regardless.
     */
    suspend fun translatePage(
        bitmap: Bitmap,
        lang: SourceLang,
        anchors: List<app.mangalens.ocr.Bubble>,
        onBubble: (suspend (VisionBubble) -> Unit)? = null,
    ): List<VisionBubble> =
        withContext(Dispatchers.IO) {
            LlmHttp.requireConfig(settings)
            // Noted before the memory is taken: see the learning below.
            val generation = StoryContext.generation

            // The page the model sees carries a numbered badge per region, so
            // "region 7" is visible rather than inferred from coordinates.
            val upload = PageMarkup.visiblePageBounds(bitmap, anchors)
            val cropped = upload.height() != bitmap.height
            val pageAnchors = if (cropped) anchors.map { bubble ->
                bubble.copy(box = Rect(bubble.box).apply { offset(-upload.left, -upload.top) })
            } else anchors
            val image = if (cropped) Bitmap.createBitmap(bitmap, upload.left, upload.top, upload.width(), upload.height()) else bitmap
            val jpegB64 = try {
                PageMarkup.encodeMarkedPage(image, pageAnchors, settings.dataSaver)
            } finally {
                if (image !== bitmap) image.recycle()
            }
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
            // On a zoomed, single dialogue balloon the lettering is already
            // large enough to read. A damaged OCR hint can override those pixels.
            val largeSingleRegion = pageAnchors.singleOrNull()?.box?.let {
                it.width() >= upload.width() / 3 && it.height() >= upload.height() / 6
            } == true
            val regions = JSONArray()
            pageAnchors.forEachIndexed { i, b ->
                val o = JSONObject()
                    .put("id", i)
                    .put(
                        "box",
                        JSONArray()
                            .put(b.box.left * 1000 / upload.width())
                            .put(b.box.top * 1000 / upload.height())
                            .put(b.box.width() * 1000 / upload.width())
                            .put(b.box.height() * 1000 / upload.height())
                    )
                    .put("kind_guess", if (b.kind == app.mangalens.ocr.BubbleKind.SFX) "sfx" else "dialogue")
                if (!largeSingleRegion) o.put("ocr_text_maybe_garbled", b.text)
                if (b.runId >= 0) o.put("run", b.runId).put("part", b.runPart)
                regions.put(o)
            }
            // What changes rarely goes first and what changes every page
            // goes last, so a provider that caches request prefixes reuses
            // the series memory from one page to the next.
            val stable = JSONObject()
                .put("glossary", JSONObject(glossary?.snapshot() ?: emptyMap<String, String>()))
                .put("characters", JSONObject(cast?.describeAll() ?: emptyMap<String, String>()))
                .toString()
            // Regions on-device OCR could not read go up a second time as
            // enlarged close-ups cut from the full-resolution frame, so the
            // lettering the model must read itself reaches it legible.
            val closeups = closeupIds(anchors, settings.dataSaver)
            val crops = if (closeups.isEmpty()) emptyList() else {
                PageMarkup.encodeRegionCrops(bitmap, anchors, closeups, settings.dataSaver)
            }
            val page = JSONObject()
                .put("expected_source_language", langHint)
                .put("story_so_far", JSONArray(StoryContext.snapshot()))
                .put("detected_regions", regions)
                .put("closeups", JSONArray(crops.map { it.first }))
                .toString()

            val stream = if (onBubble == null) null else BubbleStream()
            val streamed = HashSet<Int>()
            val raw = LlmHttp.complete(
                settings, SYSTEM_PROMPT, stable, listOf(jpegB64) + crops.map { it.second }, page,
                effort = LlmHttp.effortLevel(settings, vision = true),
                vision = true,
                onDelta = if (stream == null) null else { delta ->
                    for (o in stream.feed(delta)) entry(o, anchors, streamed, upload, bitmap.width, bitmap.height)?.let { onBubble!!(it) }
                },
            )
            val reply = LlmHttp.extractJsonObject(raw)

            val out = ArrayList<VisionBubble>()
            val seenIds = HashSet<Int>()
            val arr = reply.optJSONArray("bubbles") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                entry(o, anchors, seenIds, upload, bitmap.width, bitmap.height)?.let { out.add(it) }
            }
            // A reply that lands after the reader moved on to another work (a
            // long pause, "New series") still letters its page, but what it
            // would teach belongs to the work it was asked about.
            if (generation == StoryContext.generation) {
                reply.optJSONObject("new_terms")?.let { terms ->
                    val learned = HashMap<String, String>()
                    for (k in terms.keys()) learned[k] = terms.optString(k, "")
                    glossary?.learn(learned)
                }
                cast?.learn(CastBook.parse(reply.optJSONObject("characters")))
                out.forEach { if (!it.sfx) StoryContext.remember(it.en, it.who) }
            }
            out
        }

    /**
     * The regions that get a close-up: those OCR read next to nothing in,
     * largest first, capped so a page of hand-lettering does not turn into
     * a dozen uploads.
     */
    private fun closeupIds(anchors: List<app.mangalens.ocr.Bubble>, dataSaver: Boolean): List<Int> {
        val cap = if (dataSaver) MAX_CLOSEUPS_DATA_SAVER else MAX_CLOSEUPS
        return anchors.indices
            .filter { i ->
                val t = anchors[i].text
                t.count { it.isLetter() || app.mangalens.ocr.Script.isCjk(it) } < 3
            }
            .sortedByDescending { anchors[it].box.width().toLong() * anchors[it].box.height() }
            .take(cap)
    }

    /** One reply entry as a bubble, or null when it answers nothing paintable. */
    private fun entry(
        o: JSONObject,
        anchors: List<app.mangalens.ocr.Bubble>,
        seenIds: MutableSet<Int>,
        upload: Rect,
        width: Int,
        height: Int,
    ): VisionBubble? {
        if (o.optString("kind") == "skip") return null
        val en = o.optString("en", "").trim()
        if (en.isEmpty() || JunkFilter.isPromptPlaceholder(en)) return null
        val sfx = o.optString("kind") == "sfx"
        val src = o.optString("src", "").trim()
        val who = o.optString("who", "").trim().take(24)

        val id = o.optInt("id", -1)
        if (id in anchors.indices) {
            if (!seenIds.add(id)) return null
            return VisionBubble(id, 0, 0, 0, 0, src, en, sfx, who)
        }
        // Extra text the on-device OCR missed — here (and only here)
        // the model's own box is used.
        val box = o.optJSONArray("box") ?: return null
        if (box.length() < 4) return null
        val nx = box.optInt(0, -1)
        val ny = box.optInt(1, -1)
        val nw = box.optInt(2, 0)
        val nh = box.optInt(3, 0)
        if (nx !in 0..1000 || ny !in 0..1000 || nw <= 0 || nh <= 0) return null
        val relative = Rect(nx, ny, nx + nw.coerceAtMost(1000 - nx), ny + nh.coerceAtMost(1000 - ny))
        val full = PageMarkup.fullFrameBox(upload, width, height, relative)
        if (full.isEmpty) return null
        return VisionBubble(
            -1, full.left, full.top, full.width(), full.height(),
            src, en, sfx, who,
        )
    }

    companion object {

        /** Close-ups per page; each is a small upload and a few hundred tokens. */
        private const val MAX_CLOSEUPS = 6
        private const val MAX_CLOSEUPS_DATA_SAVER = 3

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
Translate the lettering in this comic image into natural, concise English that can be printed in each balloon. Read the image first. The detected_regions OCR is only a rough hint and can be wrong. The magenta badge is the region id. If a numbered close-up follows the page, read its sharper lettering. Read vertical columns from right to left, top to bottom within each column. Keep a balloon's lines together.

Return a JSON object with an array named "bubbles". For each numbered region give its numeric "id", "src" containing the words you actually read, "en" containing their English translation, "who" identifying the speaker when known, and "kind" set to "dialogue", "sfx" or "skip". Use real words in the values. Answer each id once. Skip already-English lettering, interface labels, watermarks and genuinely unreadable marks. For other visible comic lettering outside every outline, return the same fields with "box" [left, top, width, height] in thousandths of the page image instead of an id. Never duplicate a numbered balloon.

Read all the way to the end of the last line before translating. Preserve the action, its actor and objects, negation, emphasis, emotion and the speaker's register. When several things are named before a final verb, translate the complete clause. An ellipsis is a pause and adds no information. Use the art, balloon tails, dialogue history and series memory to resolve omitted subjects. If ambiguous, use a natural subjectless line. Honour established glossary, pronouns, names and meaningful honorifics. Translate the source language visible in the image, even when the language hint differs. Translate sound effects into short English effects in capitals. For matching "run" numbers, read the whole sentence in "part" order, then divide its English across those balloons. Preserve the original tone and intensity. Add no explanations.

After "bubbles", include "new_terms" mapping any newly established source names to English, and "characters" mapping newly identified English names to objects with "pronoun", "register" and "note". Use empty objects when there is nothing to add. Output JSON only.
""".trim()
    }
}
