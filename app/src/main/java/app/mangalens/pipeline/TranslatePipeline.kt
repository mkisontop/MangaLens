package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import app.mangalens.ocr.Bubble
import app.mangalens.ocr.BubbleGrouper
import app.mangalens.ocr.BubbleKind
import app.mangalens.ocr.OcrEngine
import app.mangalens.overlay.RenderBubble
import app.mangalens.settings.AiVisionMode
import app.mangalens.settings.AppSettings
import app.mangalens.settings.EngineKind
import app.mangalens.settings.SourceLang
import app.mangalens.translate.CastBook
import app.mangalens.translate.GlossaryStore
import app.mangalens.translate.JunkFilter
import app.mangalens.translate.SfxDict
import app.mangalens.translate.TranslationCache
import app.mangalens.translate.TranslationService
import app.mangalens.translate.VisionLlmEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray

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

    data class PageResult(
        val bubbles: List<RenderBubble>,
        val engineLabel: String,
        val note: String?,
        val polished: Boolean = false,
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
        val bubbles = BubbleGrouper.group(
            ocrResult.lines, bitmap.height, ignoreTop, ignoreBottom, ocrResult.lang, exclusions,
        )
        if (bubbles.isEmpty()) return PageResult(emptyList(), "", null)

        if (settings.engine != EngineKind.LLM) {
            return machineTranslate(bitmap, bubbles, ocrResult.lang, settings)
        }

        val vision = VisionLlmEngine(settings, glossary, cast)
        // Anchored vision is the quality path for every script — manhwa's
        // stylized/handwritten lettering needs it as much as vertical
        // Japanese does, and it degrades to the text path automatically.
        val useVision = settings.aiVision != AiVisionMode.OFF

        // Straight-to-final when the AI answer is already cached (re-reads,
        // scroll-backs, peeks): no fast flash, no network.
        if (useVision) {
            visionCacheGet(vision.cacheNamespace, ocrResult.lang, bubbles)?.let { cached ->
                return PageResult(
                    toRender(bitmap, cached, bubbles, ignoreTop, ignoreBottom, exclusions),
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
                            visionCachePut(vision.cacheNamespace, ocrResult.lang, bubbles, pageBubbles)
                            return@coroutineScope PageResult(
                                toRender(bitmap, pageBubbles, bubbles, ignoreTop, ignoreBottom, exclusions),
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
        val dialogue = bubbles.filter { it.kind == BubbleKind.DIALOGUE }
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
        val outcome = translation.translate(
            bubbles.map { it.text }, lang, settings,
            kinds = bubbles.map { it.kind },
            runs = bubbles.map { it.runId },
            parts = bubbles.map { it.runPart },
        )
        val fromAi = outcome.engineLabel != "Google"
        val rendered = bubbles.mapIndexed { i, b ->
            val gated = JunkFilter.accept(b.text, outcome.texts.getOrElse(i) { "" }, lang, fromAi)
                ?: return@mapIndexed null
            renderBubble(bitmap, b.box, gated, b.text, b.vertical, b.kind)
        }.filterNotNull()
        val polished = outcome.engineLabel != "Google"
        return PageResult(rendered, outcome.engineLabel, outcome.note, polished)
    }

    // ---- vision result mapping ----

    private fun toRender(
        bitmap: Bitmap,
        pageBubbles: List<VisionLlmEngine.VisionBubble>,
        ocrBubbles: List<Bubble>,
        ignoreTop: Int,
        ignoreBottom: Int,
        exclusions: List<Rect>,
    ): List<RenderBubble> {
        val w = bitmap.width
        val h = bitmap.height
        val unclaimed = ocrBubbles.toMutableList()

        // Anchored entries first: exact OCR geometry, AI text.
        val anchored = pageBubbles.filter { it.id in ocrBubbles.indices }.mapNotNull { v ->
            val anchor = ocrBubbles[v.id]
            if (!unclaimed.remove(anchor)) return@mapNotNull null
            renderBubble(
                bitmap, anchor.box, v.en, v.src.ifBlank { anchor.text }, anchor.vertical,
                if (v.sfx) BubbleKind.SFX else BubbleKind.DIALOGUE,
            )
        }

        // Extras (text OCR missed) use the model's box — snapped to a leftover
        // OCR region when one clearly matches, dropped when it lands nowhere
        // sane. The model's geometry is never allowed to cover the page.
        val extras = pageBubbles.filter { it.id < 0 }.mapNotNull { v ->
            var box = Rect(
                v.nx * w / 1000,
                v.ny * h / 1000,
                (v.nx + v.nw) * w / 1000,
                (v.ny + v.nh) * h / 1000,
            )
            var vertical = false
            var original = v.src
            val match = unclaimed.maxByOrNull { iou(it.box, box) }
            if (match != null && iou(match.box, box) > 0.18f) {
                box = Rect(match.box)
                vertical = match.vertical
                if (original.isBlank()) original = match.text
                unclaimed.remove(match)
            }
            if (box.bottom <= ignoreTop || box.top >= h - ignoreBottom) return@mapNotNull null
            if (exclusions.any { Rect.intersects(it, box) }) return@mapNotNull null
            if (box.width() < 8 || box.height() < 8) return@mapNotNull null
            renderBubble(
                bitmap, box, v.en, original, vertical,
                if (v.sfx) BubbleKind.SFX else BubbleKind.DIALOGUE,
            )
        }
        return anchored + extras
    }

    private fun iou(a: Rect, b: Rect): Float {
        val ix = maxOf(0, minOf(a.right, b.right) - maxOf(a.left, b.left))
        val iy = maxOf(0, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        val inter = ix.toLong() * iy
        if (inter == 0L) return 0f
        val union = a.width().toLong() * a.height() + b.width().toLong() * b.height() - inter
        return inter.toFloat() / union
    }

    // ---- vision page cache (scroll-backs cost nothing) ----

    private fun visionKey(ns: String, lang: SourceLang, bubbles: List<Bubble>) =
        TranslationCache.key(ns, lang.name, bubbles.joinToString("") { it.text })

    private fun visionCacheGet(
        ns: String,
        lang: SourceLang,
        bubbles: List<Bubble>,
    ): List<VisionLlmEngine.VisionBubble>? {
        if (bubbles.isEmpty()) return null
        val raw = cache.get(visionKey(ns, lang, bubbles)) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONArray(i)
                // Entries written before the speaker field existed are dropped
                // rather than read short.
                if (o.length() != 9) throw IllegalStateException("stale vision cache entry")
                VisionLlmEngine.VisionBubble(
                    o.getInt(0), o.getInt(1), o.getInt(2), o.getInt(3), o.getInt(4),
                    o.getString(5), o.getString(6), o.getInt(7) == 1, o.getString(8),
                )
            }
        }.getOrNull()
    }

    private fun visionCachePut(
        ns: String,
        lang: SourceLang,
        bubbles: List<Bubble>,
        result: List<VisionLlmEngine.VisionBubble>,
    ) {
        if (bubbles.isEmpty()) return
        val arr = JSONArray()
        for (v in result) {
            arr.put(
                JSONArray().put(v.id).put(v.nx).put(v.ny).put(v.nw).put(v.nh)
                    .put(v.src).put(v.en).put(if (v.sfx) 1 else 0).put(v.who)
            )
        }
        cache.put(visionKey(ns, lang, bubbles), arr.toString())
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
