package app.mangalens.translate

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import app.mangalens.ocr.OcrLine
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
        /** The photographed scene identifies a separate object being described. */
        val refersToObject: Boolean = false,
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
        translatePage(bitmap, lang, anchors, emptyList(), onBubble)

    internal suspend fun translatePage(
        bitmap: Bitmap,
        lang: SourceLang,
        anchors: List<app.mangalens.ocr.Bubble>,
        lines: List<OcrLine>,
        onBubble: (suspend (VisionBubble) -> Unit)?,
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
            val maxDimension = PageMarkup.zoomPageDimension(
                upload, cropped, anchors, largeReadableId, lines, settings.dataSaver
            )
            val image = if (cropped) Bitmap.createBitmap(bitmap, upload.left, upload.top, upload.width(), upload.height()) else bitmap
            val jpegB64 = try {
                PageMarkup.encodeMarkedPage(image, pageAnchors, settings.dataSaver, maxDimension)
            } finally {
                if (image !== bitmap) image.recycle()
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
            val closeups = closeupIds(anchors, settings.dataSaver)
            val omitFlatCrops = settings.dataSaver && largeReadableId != null &&
                maxOf(upload.width(), upload.height()) <= PageMarkup.maxPageDimension(settings.dataSaver)
            val crops = if (closeups.isEmpty()) emptyList() else {
                PageMarkup.encodeRegionCrops(bitmap, anchors, closeups, settings.dataSaver, omitFlatCrops)
            }
            val previousDialogue = StoryContext.snapshot()
            val history = JSONArray(previousDialogue)
            val page = JSONObject()
                .put("expected_source_language", langHint)
                .put("story_so_far", history)
                .put("detected_regions", regions)
                .put("closeups", JSONArray(crops.map { it.first }))
                .toString()

            val stream = if (onBubble == null) null else BubbleStream()
            val streamed = HashSet<Int>()
            var streamedPreviousLine = previousDialogue.lastOrNull().orEmpty()
            val raw = LlmHttp.complete(
                settings, SYSTEM_PROMPT, stable, listOf(jpegB64) + crops.map { it.second }, page,
                effort = LlmHttp.effortLevel(settings, vision = true),
                vision = true,
                onDelta = if (stream == null) null else { delta ->
                    for (o in stream.feed(delta)) {
                        val bubble = entry(o, anchors, streamed, upload, bitmap.width, bitmap.height) ?: continue
                        if (!bubble.needsAddressReview()) {
                            val ready = bubble.afterAddress(streamedPreviousLine)
                            onBubble!!(ready)
                            if (!ready.sfx) streamedPreviousLine = StoryContext.scriptLine(ready.en, ready.who)
                        }
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
            var previousLine = previousDialogue.lastOrNull().orEmpty()
            for (i in out.indices) {
                val bubble = out[i]
                if (bubble.sfx) continue
                val ready = bubble.afterAddress(previousLine)
                out[i] = ready
                previousLine = StoryContext.scriptLine(ready.en, ready.who)
            }
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

    private fun VisionBubble.afterAddress(previousLine: String): VisionBubble {
        val speaker = speakerAfterAddress(previousLine, en, who)
        return if (speaker == who) this else copy(who = speaker)
    }

    private fun VisionBubble.needsAddressReview(): Boolean =
        !sfx && !refersToObject && DETACHED_KOREAN_ADDRESS.containsMatchIn(src) &&
            (OBJECT_PHRASE.containsMatchIn(en) || GENERIC_CALLOUT.containsMatchIn(en) || POINTED_REMARK.containsMatchIn(en))

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
            items.put(JSONObject().put("id", id).put("source", b.src).put("speaker", b.who)
                .put("rewrite", !ENGLISH_ASIDE.containsMatchIn(b.en) && !GENERIC_CALLOUT.containsMatchIn(b.en)))
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
            val name = edit.optString("name", "").trim()
            val proposed = edit.optString("en", "").ifBlank { original.en }
            val en = JunkFilter.accept(original.src, spokenAddress(original.src, proposed, "listener", name)) ?: continue
            val corrected = original.copy(en = en, who = attributedSpeaker(original.src, en, original.who, name))
            if (corrected.needsAddressReview()) continue
            pending.remove(id)
            bubbles[id] = corrected
            val sourceName = DETACHED_KOREAN_ADDRESS.find(original.src)?.groupValues?.get(1)
            if (sourceName != null && validName(name) && en.contains(name, true)) terms.put(sourceName, name)
        }
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
        val draft = o.optString("en", "").trim()
        if (draft.isEmpty() || JunkFilter.isPromptPlaceholder(draft)) return null
        val sfx = o.optString("kind") == "sfx"
        val src = o.optString("src", "").trim()
        val referent = o.optString("aside_to", "")
        val listener = o.optString("listener_name", "")
        val en = if (sfx) draft else spokenAddress(src, draft, referent, listener)
        val who = attributedSpeaker(src, en, o.optString("who", ""), listener)

        val id = o.optInt("id", -1)
        if (id in anchors.indices) {
            if (!seenIds.add(id)) return null
            return VisionBubble(id, 0, 0, 0, 0, src, en, sfx, who, referent == "object")
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
            src, en, sfx, who, referent == "object",
        )
    }

    companion object {

        private val DETACHED_KOREAN_ADDRESS = Regex(
            """([가-힣]{1,24})[아야]\s*[,，!！~～]\s*(?:이런|요런|저런)\s+[^.!?！？~～\n]{1,38}(?:것|거|녀석|아이|애)\s*[~～!！]"""
        )
        private val OBJECT_PHRASE = Regex("""\b(?:this|that)\s+(?:[a-z'-]+\s+){1,3}thing\b""", RegexOption.IGNORE_CASE)
        private val GENERIC_CALLOUT = Regex("""^\s*(?:(?:hey|oh)[,\s]+)?you\s*[!！~～]""", RegexOption.IGNORE_CASE)
        private val POINTED_REMARK = Regex(
            """(?:^|[!！~～])\s*(?:this|that|it)(?:['’]s|\s+is)\s+(?:so|really|very|such)\b""", RegexOption.IGNORE_CASE)
        private val ENGLISH_ASIDE = Regex(
            """^(\s*[\p{L}'’-]+(?:[ \t]+[\p{L}'’-]+){0,3}[!！,，~～]\s*)(?:(?:this|that)\s+([a-z'’-]+(?:\s+[a-z'’-]+){0,2})\s+thing|(?:this|that|it)(?:['’]s|\s+is)\s+((?:so|really|very|such)\s+[a-z'’-]+(?:\s+[a-z'’-]+){0,2}))([!！~～.…]+)(?=\s|$)""",
            RegexOption.IGNORE_CASE)
        private val SPEAKER_OBJECT = Regex("""(?:^|\s)(?:나를|날|저를|절)(?:\s|$)""")
        private val ACTION_TOWARDS_SPEAKER = Regex(
            """^\s*(?:(?:are|were)\s+you\s+[a-z'-]+ing|you(?:['’]re|\s+are)\s+[a-z'-]+ing|(?:did|will|would|can|could)\s+you\s+[a-z'-]+)\s+me\b""",
            RegexOption.IGNORE_CASE)
        private val SEPARATE_OBJECT = Regex("""\b(?:it|this|that|them|these|those|one)\b""", RegexOption.IGNORE_CASE)
        private val ENGLISH_NAME = Regex("""\p{L}[\p{L}\p{M}'’-]*(?:[ \t]+[\p{L}\p{M}'’-]+){0,2}""")

        private val GENERIC_NAMES = setOf("you", "listener", "unknown", "unclear")

        private fun validName(name: String): Boolean = name.length <= 40 && ENGLISH_NAME.matches(name) &&
            app.mangalens.ocr.Script.cjkCount(name) == 0 && name.lowercase() !in GENERIC_NAMES

        private val KOREAN_VOCATIVE = Regex("""^\s*[가-힣]{1,24}[아야]\s*[!,，！~～]""")

        /** A name directly called by the speaker cannot identify the speaker of that line. */
        internal fun attributedSpeaker(source: String, english: String, speaker: String, listener: String): String {
            val who = speaker.trim().take(24)
            if (who.isEmpty() || !KOREAN_VOCATIVE.containsMatchIn(source)) return who
            val end = english.indexOfAny(charArrayOf('!', '！', ',', '，', '~', '～'))
            val address = if (end > 0) english.take(end).trim() else ""
            if (listOf(listener.trim(), address).any { validName(it) && who.equals(it, ignoreCase = true) }) return ""
            return who
        }

        private val SECOND_PERSON = Regex("""\byou(?:r|rs|rself)?\b""", RegexOption.IGNORE_CASE)
        private val CONTINUED_ADDRESS = Regex("""^[\s.…“"']*you\b""", RegexOption.IGNORE_CASE)
        private val FIRST_PERSON_OBJECT = Regex("""\b(?:me|my|mine|us|our|ours)\b""", RegexOption.IGNORE_CASE)
        private val SELF_IDENTIFICATION = Regex("""\b(?:i am|i['’]m|my name is|this is)\s+""", RegexOption.IGNORE_CASE)

        /** Preserve replies and questions. A continued observation about what "you" did for "me"
         * or my belongings is weak evidence that the person just addressed took over the dialogue. */
        internal fun speakerAfterAddress(previousLine: String, english: String, speaker: String): String {
            if (speaker.isBlank() || '?' in english || '？' in english ||
                !CONTINUED_ADDRESS.containsMatchIn(english) || !FIRST_PERSON_OBJECT.containsMatchIn(english)) return speaker
            val unattributed = previousLine.startsWith("— ")
            val previousSpeaker = if (unattributed) "" else previousLine.substringBefore(": ", "")
            if (previousSpeaker.equals(speaker, ignoreCase = true)) return speaker
            val previousWords = (if (unattributed) previousLine.removePrefix("— ")
                else previousLine.substringAfter(": ", previousLine)).trimStart()
            val mark = previousWords.indexOfAny(charArrayOf('!', '！', ',', '，', '~', '～'))
            if (mark <= 0 || !previousWords.take(mark).trim().equals(speaker, ignoreCase = true) ||
                !SECOND_PERSON.containsMatchIn(previousWords.substring(mark + 1))) return speaker
            val self = SELF_IDENTIFICATION.find(english)?.let { english.substring(it.range.last + 1) }
            if (self != null && self.startsWith(speaker, ignoreCase = true) &&
                (self.length == speaker.length || !self[speaker.length].isLetter())) return speaker
            return ""
        }

        /** Adjust only a detached compliment; retain the speaker's action and every following word. */
        internal fun spokenAddress(source: String, english: String, referent: String, name: String): String {
            val called = DETACHED_KOREAN_ADDRESS.find(source) ?: return english
            val aside = ENGLISH_ASIDE.find(english)
            val rest = aside?.let { english.substring(it.range.last + 1) }.orEmpty()
            val followingSource = source.substring(called.range.last + 1)
            // Some providers omit the optional label. Both sentences must then agree that
            // "you" act directly on "me", with no reference back to a separate thing.
            val supported = referent == "listener" || (referent.isBlank() && aside != null &&
                SPEAKER_OBJECT.containsMatchIn(followingSource) && ACTION_TOWARDS_SPEAKER.containsMatchIn(rest) &&
                !SEPARATE_OBJECT.containsMatchIn(rest))
            if (!supported) return english
            var spoken = english
            if (aside != null) {
                val description = aside.groupValues[2].trim()
                val wording = when {
                    description.isEmpty() -> "You're " + aside.groupValues[3]
                    ' ' in description -> "You " + description + " thing"
                    else -> "You're so " + description
                }
                spoken = aside.groupValues[1] + wording + aside.groupValues[4] + rest
            }
            val anonymous = GENERIC_CALLOUT.find(spoken)
            val localized = name.trim()
            if (anonymous != null && validName(localized)) {
                spoken = spoken.replaceRange(anonymous.range, localized + "!")
            }
            return spoken
        }

        private val ADDRESS_REVIEW_PROMPT = """
Identify whom each detached Korean remark describes. Use the called name, grammar, preceding dialogue and series glossary. Return JSON {"items":[...]} with numeric "id" and "referent" ("listener", "object" or "unclear"). For a listener also give "name", the called name in English. Render a transparent pet nickname by its meaning if the series has no established alternative. Only when the input says "rewrite":true, also give "en" containing the whole spoken translation. Otherwise omit "en". No explanation.
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

Return compact, single-line JSON with "bubbles" first. For each numbered dialogue balloon give its "id", "src" containing the words you read, then "en" containing the complete translation. Ordinary dialogue needs only those three fields. Include "who" only if a speaker label, self-identification or the artwork and an established cast name independently identify the speaker. Omit an uncertain identity. A name in direct address identifies the listener. Use "kind":"sfx" for effects and only "id" and "kind":"skip" for an unlettered or excluded region. If a name is called and followed by a separate remark, identify what the remark describes before writing the English. Put "aside_to" ("listener", "object" or "unclear") after "src". For "listener", also give "listener_name" as the called name in English. Omit these fields for ordinary lines. Use real words in the values. Give the readable balloons in reading order before any skipped ids. Answer each id once. Skip already-English lettering, interface labels, watermarks and genuinely unreadable marks. For other visible comic lettering outside every outline, return the same fields with "box" [left, top, width, height] in thousandths of the page image instead of an id. Never duplicate a numbered balloon.

Read all the way to the end of the last line before translating. Preserve the action, its actor and objects, negation, emphasis, emotion and the speaker's register. Keep the source's meaningful modifiers. When it gives the purpose of a place or the type of an object, state that purpose or type in English. When several things are named before a final verb, translate the complete clause. An ellipsis is a pause and adds no information. Use the art, balloon tails, dialogue history and series memory to resolve omitted subjects. An object's owner may be different from the person acting. Look at the preceding exchange before choosing a pronoun. If the actor remains unclear, use natural subjectless English. Introduce a recipient only when the dialogue establishes one. Honour the series glossary, pronouns, established names and meaningful honorifics. Keep ordinary personal names in their usual spelling. For an affectionate nickname or pet name that is plainly an everyday noun, use its natural English meaning unless the series has established a different rendering. When a speaker calls a name and then praises their listener, write the compliment as direct speech to that person. A demonstrative points to a separate object only when the scene establishes one. Keep a following question separate from the aside. Translate the source language visible in the image, even when the language hint differs. Translate sound effects into short English effects in capitals. For matching "run" numbers, read the whole sentence in "part" order, then divide its English across those balloons. Preserve the original tone and intensity. Add no explanations.

After "bubbles", add "new_terms" only for newly established mappings of source names to English. Add "characters" only for newly identified English names with evidenced "pronoun", "register" or "note". Omit empty sections and blank fields. Output JSON only.
""".trim()
    }
}
