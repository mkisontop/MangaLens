package app.mangalens.translate

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import app.mangalens.settings.AppSettings
import app.mangalens.settings.SourceLang
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
            val largeReadableId = pageAnchors.indices.filter { pageAnchors[it].text.isNotBlank() }
                .singleOrNull()?.takeIf { id ->
                    val box = pageAnchors[id].box
                    box.width() >= upload.width() / 3 && box.height() >= upload.height() / 6
                }
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
                if (i != largeReadableId && b.text.isNotBlank()) o.put("ocr_text_maybe_garbled", b.text)
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
            val closeups = closeupIds(anchors, settings.dataSaver, upload, largeReadableId)
            val crops = if (closeups.isEmpty()) emptyList() else {
                PageMarkup.encodeRegionCrops(bitmap, anchors, closeups, settings.dataSaver)
            }
            val history = JSONArray(StoryContext.snapshot())
            val page = JSONObject()
                .put("expected_source_language", langHint)
                .put("story_so_far", history)
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
                    for (o in stream.feed(delta)) {
                        val bubble = entry(o, anchors, streamed, upload, bitmap.width, bitmap.height) ?: continue
                        if (!bubble.needsAddressReview()) onBubble!!(bubble)
                    }
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
            val terms = reply.optJSONObject("new_terms") ?: JSONObject()
            reviewFormsOfAddress(out, stable, history, terms)
            // A reply that lands after the reader moved on to another work (a
            // long pause, "New series") still letters its page, but what it
            // would teach belongs to the work it was asked about.
            if (generation == StoryContext.generation) {
                val learned = HashMap<String, String>()
                for (k in terms.keys()) learned[k] = terms.optString(k, "")
                glossary?.learn(learned)
                cast?.learn(CastBook.parse(reply.optJSONObject("characters")))
                out.forEach { if (!it.sfx) StoryContext.remember(it.en, it.who) }
            }
            out
        }

    private fun VisionBubble.needsAddressReview(): Boolean =
        !sfx && OBJECT_PHRASE.containsMatchIn(en) && DETACHED_KOREAN_ADDRESS.containsMatchIn(src)

    /** Re-read disputed addressees from the transcribed words in one small text request. */
    internal suspend fun reviewFormsOfAddress(
        bubbles: MutableList<VisionBubble>,
        stable: String,
        history: JSONArray,
        terms: JSONObject,
    ) {
        val pending = bubbles.indices.filterTo(mutableSetOf()) { bubbles[it].needsAddressReview() }
        if (pending.isEmpty()) return
        val items = JSONArray()
        for (id in pending) {
            val b = bubbles[id]
            items.put(JSONObject().put("id", id).put("source", b.src).put("speaker", b.who))
        }
        val request = JSONObject().put("review", "forms_of_address")
            .put("story_so_far", history).put("items", items).toString()
        // The image already supplied a usable line. An unavailable editor must not
        // suppress it, and cancellation when the reader moves on still stops both calls.
        val edits = try {
            withTimeoutOrNull(30_000) {
                val raw = LlmHttp.complete(settings, ADDRESS_REVIEW_PROMPT, stable, emptyList(), request,
                    effort = "low", vision = false)
                LlmHttp.extractJsonObject(raw).optJSONArray("items")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return
        for (i in 0 until edits.length()) {
            val edit = edits.optJSONObject(i) ?: continue
            val id = edit.optInt("id", -1)
            if (id !in pending || edit.optString("referent") != "listener") continue
            val original = bubbles[id]
            val en = JunkFilter.accept(original.src, edit.optString("en")) ?: continue
            if (OBJECT_PHRASE.containsMatchIn(en)) continue
            pending.remove(id)
            bubbles[id] = original.copy(en = en)
            val names = edit.optJSONObject("names") ?: continue
            for (source in names.keys()) {
                val name = names.optString(source).trim()
                if (source.isNotBlank() && name.isNotBlank() && source in original.src && en.contains(name, true)) {
                    terms.put(source, name)
                }
            }
        }
    }

    /**
     * The regions that get a close-up: those OCR read next to nothing in,
     * largest first, capped so a page of hand-lettering does not turn into
     * a dozen uploads.
     */
    internal fun closeupIds(
        anchors: List<app.mangalens.ocr.Bubble>,
        dataSaver: Boolean,
        upload: Rect,
        largeReadableId: Int?,
    ): List<Int> {
        // A zoomed panel already uploaded at native resolution contains every source pixel.
        // Keep close-ups for downscaled pages, unresolved lettering and full-quality mode.
        if (dataSaver && largeReadableId != null &&
            maxOf(upload.width(), upload.height()) <= PageMarkup.maxPageDimension(dataSaver)) return emptyList()
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

        private val DETACHED_KOREAN_ADDRESS = Regex(
            """[가-힣]{1,24}[아야]\s*[,，!！~～]\s*(?:이런|요런|저런)\s+[^.!?！？~～\n]{1,38}(?:것|거|녀석|아이|애)\s*[~～!！]"""
        )
        private val OBJECT_PHRASE = Regex("""\b(?:this|that)\s+(?:[a-z'-]+\s+){1,3}thing\b""", RegexOption.IGNORE_CASE)
        private val ADDRESS_REVIEW_PROMPT = """
You edit Korean comic dialogue for spoken English. Each source starts with a name being called, followed by a detached remark. Decide what the remark refers to from the entire Korean line and the preceding dialogue: "listener" for the addressee, "object" for a separate thing, or "unclear". When it is spoken affectionately to the listener, translate the whole line like someone speaking to them: address them directly with "you" or an endearment. Preserve the rest of the sentence, its action and who does what to whom. Use established names from the glossary. An obvious everyday noun used as a pet name can be translated by its meaning.
Return JSON with an "items" array, one object per input id. Give "id" and "referent". For "listener" also give "en" containing the complete spoken English and "names" mapping any name you localized from source spelling to English. Output only the JSON.
""".trim()

        /** Close-ups per page; each is a small upload and a few hundred tokens. */
        private const val MAX_CLOSEUPS = 6
        private const val MAX_CLOSEUPS_DATA_SAVER = 3

        /** JPEG-encodes the page, downscaled so slow uplinks stay usable. */
        fun encodePage(bitmap: Bitmap, dataSaver: Boolean): String {
            val maxDim = PageMarkup.maxPageDimension(dataSaver)
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

Read all the way to the end of the last line before translating. Preserve the action, its actor and objects, negation, emphasis, emotion and the speaker's register. When several things are named before a final verb, translate the complete clause. An ellipsis is a pause and adds no information. Use the art, balloon tails, dialogue history and series memory to resolve omitted subjects. An object's owner may be different from the person acting. Look at the preceding exchange before choosing a pronoun. If the actor remains unclear, use natural subjectless English. Introduce a recipient only when the dialogue establishes one. Honour the series glossary, pronouns, established names and meaningful honorifics. Keep ordinary personal names in their usual spelling. For an affectionate nickname or pet name that is plainly an everyday noun, use its natural English meaning unless the series has established a different rendering. When a speaker calls a name and then praises their listener, write the compliment as direct speech to that person. A demonstrative points to a separate object only when the scene establishes one. Keep a following question separate from the aside. Translate the source language visible in the image, even when the language hint differs. Translate sound effects into short English effects in capitals. For matching "run" numbers, read the whole sentence in "part" order, then divide its English across those balloons. Preserve the original tone and intensity. Add no explanations.

After "bubbles", include "new_terms" mapping any newly established source names to English, and "characters" mapping newly identified English names to objects with "pronoun", "register" and "note". Use empty objects when there is nothing to add. Output JSON only.
""".trim()
    }
}
