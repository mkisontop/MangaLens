package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import app.mangalens.ocr.BalloonFinder
import app.mangalens.ocr.Bubble
import app.mangalens.ocr.BubbleGrouper
import app.mangalens.ocr.BubbleKind
import app.mangalens.ocr.OcrEngine
import app.mangalens.ocr.OcrLine
import app.mangalens.ocr.Script
import app.mangalens.ocr.TextAnchor
import app.mangalens.overlay.RenderBubble
import app.mangalens.settings.AiVisionMode
import app.mangalens.settings.AppSettings
import app.mangalens.settings.EngineKind
import app.mangalens.settings.SourceLang
import app.mangalens.translate.CastBook
import app.mangalens.translate.GlossaryStore
import app.mangalens.translate.JunkFilter
import app.mangalens.translate.PageKey
import app.mangalens.translate.ReplayGeometry
import app.mangalens.translate.SfxDict
import app.mangalens.translate.TranslationCache
import app.mangalens.translate.TranslationService
import app.mangalens.translate.VisionLlmEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * One pass over one stable frame.
 *
 * Free/offline engines: OCR -> group -> translate -> gate junk -> render.
 *
 * AI Pro is progressive so slow networks never stall reading: the fast path
 * (cache + free Google) paints overlays in about a second via [onPartial],
 * then the AI result — vision for scripts that break on-device OCR, cheap
 * text-only requests otherwise — replaces them in place when it lands.
 */
class TranslatePipeline(
    private val ocr: OcrEngine,
    private val translation: TranslationService,
    private val cache: TranslationCache,
    private val glossary: GlossaryStore? = null,
    private val cast: CastBook? = null,
) {

    private companion object {
        /** Vision cache entry schema; older shapes are dropped, never guessed at. */
        const val VISION_CACHE_VERSION = 2
    }

    data class PageResult(
        val bubbles: List<RenderBubble>,
        val engineLabel: String,
        val note: String?,
        val polished: Boolean = false,
        /** Stage-by-stage counts, when diagnostics are on. */
        val diag: String? = null,
        /** Balloons found in the page, outlined on screen when diagnostics are on. */
        val balloons: List<Rect> = emptyList(),
    )

    suspend fun process(
        bitmap: Bitmap,
        settings: AppSettings,
        exclusions: List<Rect> = emptyList(),
        onPartial: (suspend (PageResult) -> Unit)? = null,
    ): PageResult {
        val ocrResult = ocr.recognize(bitmap, settings.sourceLang)
        val ignoreTop = (bitmap.height * settings.ignoreTopPct).toInt()
        val ignoreBottom = (bitmap.height * settings.ignoreBottomPct).toInt()

        // Anchored vision is the quality path for every script — manhwa's
        // stylized/handwritten lettering needs it as much as vertical
        // Japanese does, and it degrades to the text path automatically.
        val useVision = settings.engine == EngineKind.LLM && settings.aiVision != AiVisionMode.OFF

        // Balloons come from the page pixels, so a region exists because the
        // page shows one — not because OCR happened to read something in it.
        val balloons = BalloonFinder.find(bitmap, ignoreTop, ignoreBottom, exclusions)
        val bubbles = BubbleGrouper.group(
            ocrResult.lines, bitmap.height, ignoreTop, ignoreBottom, ocrResult.lang, exclusions,
            balloons, includeEmptyBalloons = useVision,
        )
        // Raw OCR lines, kept for anchoring the vision model's unanchored
        // answers by their text — the lines know where the text physically
        // is even when they never survived into a region.
        val anchorLines = ocrResult.lines.mapNotNull { l ->
            val cleaned = Script.clean(l.text)
            if (cleaned.length < 2) return@mapNotNull null
            if (l.box.bottom <= ignoreTop || l.box.top >= bitmap.height - ignoreBottom) return@mapNotNull null
            if (exclusions.any { Rect.intersects(it, l.box) }) return@mapNotNull null
            OcrLine(cleaned, l.box, l.vertical)
        }
        // Each count answers a different question when a balloon comes back
        // untranslated: whether OCR read anything, whether the balloon was seen
        // at all, whether it survived into a region, and whether the translator
        // answered for it.
        val diag = if (!settings.diagnostics) null else
            "ocr ${ocrResult.lines.size} · balloons ${balloons.size} · regions ${bubbles.size}"

        if (bubbles.isEmpty()) {
            // Nothing was resolved into a region — but a page can plainly carry
            // text that neither OCR nor balloon detection can get hold of:
            // jagged shout balloons, lettering drawn straight onto a screentone,
            // balloons that touch and flood together. When OCR saw *something*,
            // or a balloon was spotted, the page is not blank, and the vision
            // model can still read it with no anchors at all rather than the
            // page coming back untouched.
            val worthALook = useVision && (ocrResult.lines.isNotEmpty() || balloons.isNotEmpty())
            if (!worthALook) {
                return PageResult(emptyList(), "", null, diag = diag?.plus(" · cards 0"), balloons = balloons)
            }
        }

        val result = dispatch(
            bitmap, settings, exclusions, onPartial,
            ocrResult, bubbles, balloons, anchorLines, ignoreTop, ignoreBottom, useVision,
        )
        return if (diag == null) result else result.copy(
            diag = "$diag · cards ${result.bubbles.size}",
            balloons = balloons,
        )
    }

    /** Routes one page of regions to the configured engine. */
    private suspend fun dispatch(
        bitmap: Bitmap,
        settings: AppSettings,
        exclusions: List<Rect>,
        onPartial: (suspend (PageResult) -> Unit)?,
        ocrResult: OcrEngine.Result,
        bubbles: List<Bubble>,
        balloons: List<Rect>,
        anchorLines: List<OcrLine>,
        ignoreTop: Int,
        ignoreBottom: Int,
        useVision: Boolean,
    ): PageResult {
        if (settings.engine != EngineKind.LLM) {
            val result = machineTranslate(bitmap, bubbles, ocrResult.lang, settings)
            // The free and offline engines only ever see text on-device OCR
            // managed to read, and stylized vertical lettering routinely
            // defeats it. Balloon detection can still see those balloons, so
            // say how many were found and left alone — silently skipping them
            // reads as the app being broken rather than as the engine's limit.
            val unread = balloons.count { balloon ->
                bubbles.none { it.text.isNotBlank() && Rect.intersects(it.box, balloon) }
            }
            return if (unread > 0) {
                result.copy(note = "$unread unread · AI Pro reads these")
            } else {
                result
            }
        }

        val vision = VisionLlmEngine(settings, glossary, cast)

        // Straight-to-final when the AI answer is already cached (re-reads,
        // scroll-backs, peeks): no fast flash, no network. The key is the
        // page's content — OCR text, or the balloons' own pixels where OCR
        // read nothing — so a hit can only replay text onto the balloons it
        // was written for, never onto whatever now sits at the same spot.
        val pageKey = if (useVision) visionKey(vision.cacheNamespace, ocrResult.lang, bubbles, bitmap) else null
        if (pageKey != null) {
            visionCacheGet(pageKey, bubbles, bitmap.width, bitmap.height)?.let { cached ->
                return PageResult(
                    toRender(bitmap, cached, bubbles, anchorLines, ignoreTop, ignoreBottom, exclusions, balloons),
                    vision.label, null, polished = true,
                )
            }
        } else {
            val ns = vision.cacheNamespace.removePrefix("Vision:")
            val dialogue = bubbles.filter { it.kind == BubbleKind.DIALOGUE }
            if (dialogue.isNotEmpty() && translation.fullyCached(ns, ocrResult.lang, dialogue.map { it.text })) {
                return aiTextTranslate(bitmap, bubbles, ocrResult.lang, settings)
            }
        }

        // Fast draft and AI request race concurrently: the draft paints in
        // ~1 s, and the AI round-trip starts immediately rather than queuing
        // behind it. If the AI finishes first the draft is skipped entirely.
        return coroutineScope {
            var aiFinished = false
            val fastJob = onPartial?.let { emit ->
                launch {
                    val fast = runCatching {
                        machineTranslate(bitmap, bubbles, ocrResult.lang, settings, forceGoogle = true)
                    }.getOrNull()
                    if (fast != null && fast.bubbles.isNotEmpty() && !aiFinished) {
                        emit(fast.copy(note = "upgrading"))
                    }
                }
            }
            try {
                if (useVision) {
                    try {
                        val pageBubbles = vision.translatePage(bitmap, ocrResult.lang, bubbles)
                        // The upgrade must never look worse than the draft:
                        // accept the vision result only if it covered most of
                        // the dialogue regions we know exist. Otherwise fall
                        // back to the text path, which renders AI text on
                        // exact OCR geometry.
                        val dialogueIds = bubbles.indices.filter { bubbles[it].kind == BubbleKind.DIALOGUE }
                        val covered = pageBubbles.count { it.id in dialogueIds }
                        val goodCoverage = dialogueIds.isEmpty() || covered * 2 >= dialogueIds.size
                        if (pageBubbles.isNotEmpty() && goodCoverage) {
                            // A region the model skipped used to render
                            // nothing at all, leaving raw balloons scattered
                            // through an otherwise translated page. Anything
                            // it passed over that OCR *could* read is filled
                            // in from the text engine instead.
                            val complete = pageBubbles + gapFill(
                                bubbles, pageBubbles, ocrResult.lang, settings,
                            )
                            pageKey?.let {
                                visionCachePut(it, bubbles, complete, bitmap.width, bitmap.height)
                            }
                            return@coroutineScope PageResult(
                                toRender(bitmap, complete, bubbles, anchorLines, ignoreTop, ignoreBottom, exclusions, balloons),
                                vision.label, null, polished = true,
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // fall through to the text path
                    }
                }
                aiTextTranslate(bitmap, bubbles, ocrResult.lang, settings)
            } finally {
                aiFinished = true
                fastJob?.cancel()
            }
        }
    }

    // ---- machine engines (Google / on-device), also the AI fast path ----

    private suspend fun machineTranslate(
        bitmap: Bitmap,
        bubbles: List<Bubble>,
        lang: SourceLang,
        settings: AppSettings,
        forceGoogle: Boolean = false,
    ): PageResult {
        // Balloons detected in the pixels but unread by OCR carry no text; the
        // machine engines have nothing to work from and would render blanks.
        val dialogue = bubbles.filter { it.kind == BubbleKind.DIALOGUE && it.text.isNotBlank() }
        val outcome = if (dialogue.isEmpty()) {
            TranslationService.Outcome(emptyList(), "Google")
        } else {
            translation.translate(dialogue.map { it.text }, lang, settings, forceGoogle = forceGoogle)
        }
        val texts = HashMap<Bubble, String>()
        dialogue.forEachIndexed { i, b ->
            val gated = JunkFilter.accept(b.text, outcome.texts.getOrElse(i) { "" }, lang)
            if (gated != null) texts[b] = gated
        }
        // Machine engines never see SFX — the dictionary stylizes known ones,
        // unknown ones stay untouched art instead of becoming "death".
        for (b in bubbles) {
            if (b.kind == BubbleKind.SFX) SfxDict.lookup(b.text)?.let { texts[b] = it }
        }
        val rendered = bubbles.mapNotNull { b ->
            val t = texts[b] ?: return@mapNotNull null
            renderBubble(bitmap, b.box, t, b.text, b.vertical, b.kind)
        }
        return PageResult(rendered, outcome.engineLabel, outcome.note)
    }

    // ---- AI text path (small payloads — slow-internet friendly) ----

    private suspend fun aiTextTranslate(
        bitmap: Bitmap,
        bubbles: List<Bubble>,
        lang: SourceLang,
        settings: AppSettings,
    ): PageResult {
        // Text-only requests can say nothing about a balloon OCR could not
        // read, so those regions are left out rather than sent as blanks.
        val idx = bubbles.indices.filter { bubbles[it].text.isNotBlank() }
        if (idx.isEmpty()) return PageResult(emptyList(), "", null)

        val outcome = translation.translate(
            idx.map { bubbles[it].text }, lang, settings,
            kinds = idx.map { bubbles[it].kind },
            runs = idx.map { bubbles[it].runId },
            parts = idx.map { bubbles[it].runPart },
        )
        val fromAi = outcome.engineLabel != "Google"
        val rendered = idx.mapIndexedNotNull { k, i ->
            val b = bubbles[i]
            val gated = JunkFilter.accept(b.text, outcome.texts.getOrElse(k) { "" }, lang, fromAi)
                ?: return@mapIndexedNotNull null
            renderBubble(bitmap, b.box, gated, b.text, b.vertical, b.kind)
        }
        return PageResult(rendered, outcome.engineLabel, outcome.note, fromAi)
    }

    /**
     * Translates the dialogue the vision model passed over.
     *
     * A region it declines to answer for used to render nothing, so a page
     * came back with translated balloons interleaved with raw ones and no
     * indication anything was missing. Whatever it skipped that OCR could read
     * is put through the text engine instead — a worse translation for those
     * balloons, but a translated page.
     */
    private suspend fun gapFill(
        bubbles: List<Bubble>,
        answered: List<VisionLlmEngine.VisionBubble>,
        lang: SourceLang,
        settings: AppSettings,
    ): List<VisionLlmEngine.VisionBubble> {
        val done = answered.filter { it.id >= 0 }.mapTo(HashSet()) { it.id }
        val missing = bubbles.indices.filter {
            it !in done && bubbles[it].kind == BubbleKind.DIALOGUE && bubbles[it].text.isNotBlank()
        }
        if (missing.isEmpty()) return emptyList()

        val outcome = runCatching {
            translation.translate(
                missing.map { bubbles[it].text }, lang, settings,
                kinds = missing.map { bubbles[it].kind },
                runs = missing.map { bubbles[it].runId },
                parts = missing.map { bubbles[it].runPart },
            )
        }.getOrNull() ?: return emptyList()

        val fromAi = outcome.engineLabel != "Google"
        return missing.mapIndexedNotNull { k, i ->
            val gated = JunkFilter.accept(
                bubbles[i].text, outcome.texts.getOrElse(k) { "" }, lang, fromAi,
            ) ?: return@mapIndexedNotNull null
            VisionLlmEngine.VisionBubble(i, 0, 0, 0, 0, bubbles[i].text, gated, sfx = false)
        }
    }

    // ---- vision result mapping ----

    private fun toRender(
        bitmap: Bitmap,
        pageBubbles: List<VisionLlmEngine.VisionBubble>,
        ocrBubbles: List<Bubble>,
        ocrLines: List<OcrLine>,
        ignoreTop: Int,
        ignoreBottom: Int,
        exclusions: List<Rect>,
        balloons: List<Rect>,
    ): List<RenderBubble> {
        val w = bitmap.width
        val h = bitmap.height
        val unclaimed = ocrBubbles.toMutableList()

        // Anchored entries first: exact OCR geometry, AI text.
        val takenBoxes = ArrayList<Rect>()
        val takenText = HashSet<String>()
        val anchored = pageBubbles.filter { it.id in ocrBubbles.indices }.mapNotNull { v ->
            val anchor = ocrBubbles[v.id]
            if (!unclaimed.remove(anchor)) return@mapNotNull null
            takenBoxes.add(anchor.box)
            takenText.add(fingerprint(v.en))
            renderBubble(
                bitmap, anchor.box, v.en, v.src.ifBlank { anchor.text }, anchor.vertical,
                if (v.sfx) BubbleKind.SFX else BubbleKind.DIALOGUE,
            )
        }

        // Extras — answers with no region id. The model's own box is the
        // least trustworthy thing about them, so the position is recovered
        // from the page instead: the entry's source text matched back to the
        // OCR lines first, a leftover OCR region second. Only then does the
        // model's box count, and only where a detected balloon or region
        // backs it — a drifting box with no support paints text over art
        // nowhere near the balloon it belongs to, and is dropped.
        val extras = pageBubbles.filter { it.id < 0 }.mapNotNull { v ->
            var box = Rect(
                v.nx * w / 1000,
                v.ny * h / 1000,
                (v.nx + v.nw) * w / 1000,
                (v.ny + v.nh) * h / 1000,
            )
            var vertical = false
            var original = v.src
            var supported = false
            val textBox = TextAnchor.locate(v.src, ocrLines)
            if (textBox != null) {
                box = textBox
                supported = true
            } else {
                val match = unclaimed.maxByOrNull { iou(it.box, box) }
                if (match != null && iou(match.box, box) > 0.18f) {
                    box = Rect(match.box)
                    vertical = match.vertical
                    if (original.isBlank()) original = match.text
                    unclaimed.remove(match)
                    supported = true
                }
            }
            if (box.bottom <= ignoreTop || box.top >= h - ignoreBottom) return@mapNotNull null
            if (exclusions.any { Rect.intersects(it, box) }) return@mapNotNull null
            if (box.width() < 8 || box.height() < 8) return@mapNotNull null

            // A balloon that already has a card never gets a second one. The
            // model sometimes answers a region by id *and* repeats it as a
            // free-floating entry — usually when a long line tempts it to
            // continue in a second entry — and the repeat carries its own
            // drifting box, which lands beside or below the balloon it
            // belongs to.
            if (takenBoxes.any { overlapping(it, box) }) return@mapNotNull null
            if (!takenText.add(fingerprint(v.en))) return@mapNotNull null

            if (!supported &&
                balloons.none { overlapping(it, box) } &&
                unclaimed.none { overlapping(it.box, box) }
            ) {
                return@mapNotNull null
            }
            takenBoxes.add(box)
            renderBubble(
                bitmap, box, v.en, original, vertical,
                if (v.sfx) BubbleKind.SFX else BubbleKind.DIALOGUE,
            )
        }
        return anchored + extras
    }

    /** True when [b] sits on [a] closely enough to be the same balloon. */
    private fun overlapping(a: Rect, b: Rect): Boolean =
        a.contains(b.centerX(), b.centerY()) || b.contains(a.centerX(), a.centerY()) || iou(a, b) > 0.2f

    /** Collapses a translation to a form that catches near-repeats. */
    private fun fingerprint(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    private fun iou(a: Rect, b: Rect): Float {
        val ix = maxOf(0, minOf(a.right, b.right) - maxOf(a.left, b.left))
        val iy = maxOf(0, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        val inter = ix.toLong() * iy
        if (inter == 0L) return 0f
        val union = a.width().toLong() * a.height() + b.width().toLong() * b.height() - inter
        return inter.toFloat() / union
    }

    // ---- vision page cache (scroll-backs cost nothing) ----

    /**
     * Identity of this page under this engine: per-region content tokens —
     * OCR text, or a hash of the region's own pixels where OCR read nothing.
     * Never position alone. The old key joined OCR texts, and manhwa
     * lettering routinely defeats OCR — every stop with the same number of
     * unreadable balloons then shared one key, and the previous stop's answer
     * was replayed onto whichever balloons now sat at those positions.
     */
    private fun visionKey(ns: String, lang: SourceLang, bubbles: List<Bubble>, bitmap: Bitmap): String =
        TranslationCache.key(
            ns, lang.name,
            PageKey.of(bubbles.map { b -> PageKey.token(b.text) { PageKey.regionHash(bitmap, b.box) } }),
        )

    private fun normalized(box: Rect, w: Int, h: Int) = Rect(
        box.left * 1000 / w, box.top * 1000 / h,
        box.right * 1000 / w, box.bottom * 1000 / h,
    )

    private fun visionCacheGet(
        key: String,
        bubbles: List<Bubble>,
        w: Int,
        h: Int,
    ): List<VisionLlmEngine.VisionBubble>? {
        if (bubbles.isEmpty()) return null
        val raw = cache.get(key) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            // Entries from before the layout check existed are dropped rather
            // than replayed unverified.
            if (obj.optInt("v") != VISION_CACHE_VERSION) throw IllegalStateException("stale vision cache entry")
            val regs = obj.getJSONArray("regions")
            if (regs.length() != bubbles.size) throw IllegalStateException("region count changed")
            val stored = (0 until regs.length()).map { i ->
                val a = regs.getJSONArray(i)
                Rect(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3))
            }
            // The same content can sit at a new scroll offset. Find the one
            // consistent displacement between then and now — or refuse the
            // replay, because a page that cannot be aligned cannot be
            // repainted honestly either.
            val current = bubbles.map { normalized(it.box, w, h) }
            val shift = ReplayGeometry.shift(stored, current)
                ?: throw IllegalStateException("layout no longer matches")
            val arr = obj.getJSONArray("bubbles")
            val entries = (0 until arr.length()).map { i ->
                val o = arr.getJSONArray(i)
                // Rows written before the speaker field existed are dropped
                // rather than read short.
                if (o.length() != 9) throw IllegalStateException("stale vision cache entry")
                VisionLlmEngine.VisionBubble(
                    o.getInt(0), o.getInt(1), o.getInt(2), o.getInt(3), o.getInt(4),
                    o.getString(5), o.getString(6), o.getInt(7) == 1, o.getString(8),
                )
            }
            ReplayGeometry.shiftExtras(entries, shift)
        }.getOrNull()
    }

    private fun visionCachePut(
        key: String,
        bubbles: List<Bubble>,
        result: List<VisionLlmEngine.VisionBubble>,
        w: Int,
        h: Int,
    ) {
        if (bubbles.isEmpty()) return
        // The layout the answer was written against, kept so a later replay
        // can align (or refuse to align) itself with the frame it meets.
        val regs = JSONArray()
        for (b in bubbles) {
            val n = normalized(b.box, w, h)
            regs.put(JSONArray().put(n.left).put(n.top).put(n.right).put(n.bottom))
        }
        val arr = JSONArray()
        for (v in result) {
            arr.put(
                JSONArray().put(v.id).put(v.nx).put(v.ny).put(v.nw).put(v.nh)
                    .put(v.src).put(v.en).put(if (v.sfx) 1 else 0).put(v.who)
            )
        }
        cache.put(
            key,
            JSONObject().put("v", VISION_CACHE_VERSION).put("regions", regs).put("bubbles", arr).toString(),
        )
    }

    // ---- shared rendering ----

    private fun renderBubble(
        bitmap: Bitmap,
        box: Rect,
        translated: String,
        original: String,
        vertical: Boolean,
        kind: BubbleKind,
    ): RenderBubble {
        val bg = sampleBackground(bitmap, box)
        return RenderBubble(
            box = Rect(box),
            translated = translated,
            original = original,
            bgColor = bg,
            textColor = if (luminance(bg) < 140) Color.WHITE else 0xFF17181C.toInt(),
            vertical = vertical,
            kind = kind,
        )
    }

    private fun luminance(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    /** Averages the pixels in a thin ring just outside the text box. */
    private fun sampleBackground(bmp: Bitmap, box: Rect): Int {
        val left = (box.left - 8).coerceIn(0, bmp.width - 1)
        val top = (box.top - 8).coerceIn(0, bmp.height - 1)
        val right = (box.right + 8).coerceIn(0, bmp.width - 1)
        val bottom = (box.bottom + 8).coerceIn(0, bmp.height - 1)
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0

        fun sample(x: Int, y: Int) {
            val p = bmp.getPixel(x, y)
            r += Color.red(p)
            g += Color.green(p)
            b += Color.blue(p)
            count++
        }

        var x = left
        while (x <= right) {
            sample(x, top)
            sample(x, bottom)
            x += 4
        }
        var y = top
        while (y <= bottom) {
            sample(left, y)
            sample(right, y)
            y += 4
        }
        if (count == 0) return Color.WHITE
        val avg = Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
        // Most bubbles are white; snap near-white samples to pure white for a clean look.
        return if (luminance(avg) > 190) Color.WHITE else avg
    }
}
