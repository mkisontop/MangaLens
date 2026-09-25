package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import app.mangalens.ocr.Balloon
import app.mangalens.ocr.BalloonFinder
import app.mangalens.ocr.Bubble
import app.mangalens.ocr.BubbleGrouper
import app.mangalens.ocr.BubbleKind
import app.mangalens.ocr.OcrEngine
import app.mangalens.ocr.OcrLine
import app.mangalens.ocr.PageScan
import app.mangalens.ocr.Script
import app.mangalens.ocr.TextAnchor
import app.mangalens.overlay.RenderBubble
import app.mangalens.pipeline.ReadResolver.Companion.containedShare
import app.mangalens.settings.AiVisionMode
import app.mangalens.settings.AppSettings
import app.mangalens.settings.SourceLang
import app.mangalens.translate.CastBook
import app.mangalens.translate.GlossaryStore
import app.mangalens.translate.JunkFilter
import app.mangalens.translate.ItemKind
import app.mangalens.translate.PageItem
import app.mangalens.translate.PageKey
import app.mangalens.translate.PageReader
import app.mangalens.translate.PendingRead
import app.mangalens.translate.ReplayGeometry
import app.mangalens.translate.TranslationCache
import app.mangalens.translate.TranslationService
import app.mangalens.translate.VisionLlmEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * One pass over one stable frame.
 *
 * Every line on the page is the AI's. There is no machine draft painted
 * first and re-worded when the AI lands: each line appears once, in the
 * words it keeps, streamed onto the page via [onPartial] as the model
 * writes it. When the AI fails, the page says why ([PageResult.failure])
 * rather than showing some other translation in its place.
 *
 * With Gemini the page is read AI-first ([PageReader]): the screen goes to
 * the model the moment it is still, the model finds and translates every
 * piece of lettering itself, and on-device analysis only decides where each
 * answer is lettered ([ReadResolver]). Lettering already translated on an
 * earlier stop is repainted from [ItemMemory] before any request returns.
 * Other providers read on-device OCR's regions — with the page image, or as
 * text only — and answer them by id.
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
        /** Stage-by-stage counts, when diagnostics are on. */
        val diag: String? = null,
        /** Balloons found in the page, outlined on screen when diagnostics are on. */
        val balloons: List<Rect> = emptyList(),
        /** Panels read off the page, likewise. */
        val panels: List<Rect> = emptyList(),
        /**
         * A problem the reader has to act on, such as an API key the
         * provider rejected: shown even when the status pill is otherwise
         * hidden, since nothing on the page would say why it stays untranslated.
         */
        val alert: String? = null,
        /**
         * Why the AI could not read this page — "network", "rate limited" —
         * when it could not. Nothing else translates the page in its place,
         * so this is the only thing that tells the reader why it stays raw.
         */
        val failure: String? = null,
    )

    /**
     * Everything read off one frame before any translation happens: the
     * OCR lines, the balloons and panels found in the pixels, and the
     * regions they resolve into. Analysis has no side effects beyond the
     * OCR engine's language pin, so it can run ahead of the moment the
     * reader is known to have stopped — and be thrown away if they had not.
     */
    class Analysis internal constructor(
        val bitmap: Bitmap,
        internal val ocr: OcrEngine.Result,
        internal val detected: List<Balloon>,
        val panels: List<Rect>,
        internal val bubbles: List<Bubble>,
        internal val anchorLines: List<OcrLine>,
        internal val ignoreTop: Int,
        internal val ignoreBottom: Int,
        internal val exclusions: List<Rect>,
        internal val useVision: Boolean,
        internal val diag: String?,
        /** The page's balloons and panels as found, for filling OCR in later. */
        internal val scan: PageScan = PageScan(emptyList(), panels),
        /** On-device OCR was put off: the model reads the page, and [withOcr] adds it after. */
        internal val ocrPending: Boolean = false,
    ) {
        val balloons: List<Rect> get() = detected.map { it.box }
    }

    private companion object {
        /** Vision cache entry schema; older shapes are dropped, never guessed at. */
        const val VISION_CACHE_VERSION = 2

        /** Balloons OCR read nothing in that get a second, enlarged look. */
        const val MAX_REREAD = 6

        /** Short side, in pixels, a re-read crop is enlarged toward. */
        const val REREAD_SHORT_SIDE = 320f

        /** AI-first read cache entry schema; older shapes are dropped. */
        const val READ_CACHE_VERSION = 4

        /** Frames read in full that a page turned back to is looked for among. */
        const val RECENT_FRAMES = 6

        /** A scroll revealing less than this share of the screen reveals nothing worth a request. */
        const val NOTHING_NEW = 0.04f

        /** Margin above a revealed strip, for a balloon the last stop showed only half of. */
        const val STRIP_MARGIN = 0.25f

        /**
         * How far into the margin, as a share of the screen, the rows the
         * model is told are unread reach past the rows a scroll revealed:
         * more than a model's box is ever off by.
         */
        const val UNREAD_SLACK = 0.04f

        /** Background busier than this is worth an AI redraw under its lettering. */
        const val BUSY_FOR_AI = 0.3f

        /** The longest the finished page waits for the art clean-up. */
        const val CLEANUP_TIMEOUT_MS = 12_000L
    }

    private val memory = ItemMemory()

    /** The last frame whose lettering was read in full, for telling how far the next one scrolled. */
    @Volatile
    private var seen: Seen? = null
        set(value) {
            field = value
            if (value != null) synchronized(recentSeen) {
                recentSeen.addFirst(value)
                while (recentSeen.size > RECENT_FRAMES) recentSeen.removeLast()
            }
        }

    /** The last few frames read in full, newest first: a page turned back to is one of them. */
    private val recentSeen = ArrayDeque<Seen>()

    /** The scroll signature measured when [startRead] last ran, and the frame it was measured on. */
    @Volatile
    private var lastMatch: Pair<Bitmap, ScrollMatch>? = null

    /**
     * [bitmap]'s scroll signature, when the read started for it measured
     * one (AI-first reads do, before they send); null otherwise. Never
     * measured here: this is for the main thread.
     */
    internal fun signatureOf(bitmap: Bitmap): ScrollMatch? = lastMatch?.takeIf { it.first === bitmap }?.second

    /** Whether pages are read AI-first under [settings]. */
    fun readsAiFirst(settings: AppSettings): Boolean =
        settings.aiVision != AiVisionMode.OFF && settings.apiKey.isNotBlank() && PageReader.supports(settings)

    /**
     * Starts the AI read of [bitmap] in [scope] ahead of analysis — the
     * moment the screen goes still — or returns null when pages are not
     * read AI-first. The page is encoded before this returns; the caller
     * owns cancelling the read if the frame is abandoned.
     */
    fun startRead(bitmap: Bitmap, settings: AppSettings, scope: CoroutineScope): PendingRead? {
        if (!readsAiFirst(settings)) return null
        return runCatching {
            val reader = PageReader(settings, glossary, cast)
            stripRead(reader, bitmap, settings, scope) ?: reader.start(scope, bitmap, settings.sourceLang)
        }.getOrNull()
    }

    /**
     * A stop that only nudged the page since the last frame read in full
     * is read as the strip it revealed ([StripRead]); null when this frame
     * has to be read whole — a new page, a long scroll, nothing read yet.
     */
    private fun stripRead(reader: PageReader, bitmap: Bitmap, settings: AppSettings, scope: CoroutineScope): StripRead? {
        val match = ScrollMatch.of(bitmap)
        lastMatch = bitmap to match
        val last = seen ?: return null
        val d = match.scrolledFrom(last.match) ?: return earlierPage(match)
        val h = bitmap.height
        val revealed = kotlin.math.abs(d)
        if (revealed < h * NOTHING_NEW) return StripRead(null, Rect(), d, last, match)
        val margin = (h * STRIP_MARGIN).toInt()
        // However tall, the strip is read as a strip: told the rows it newly
        // shows, the model reads a tall strip's new lines first all the same,
        // and a strip up to the whole screen is still shorter to answer than
        // a screen read afresh.
        val strip = if (d > 0) {
            Rect(0, (h - revealed - margin).coerceAtLeast(0), bitmap.width, h)
        } else {
            Rect(0, 0, bitmap.width, (revealed + margin).coerceAtMost(h))
        }
        // The rows the scroll revealed. The margin above them is shown for
        // context and for a balloon the last stop's edge cut, not to be read
        // again: its lines were all read at that stop and come back from
        // memory. The model is told the revealed rows, reaching a little
        // into the margin, so a line that reaches them at all is its to read
        // however loosely it boxes it.
        val revealedRows = if (d > 0) Rect(0, h - revealed, bitmap.width, h) else Rect(0, 0, bitmap.width, revealed)
        val told = StripRead.told(
            h, d, strip, revealedRows, (h * UNREAD_SLACK).toInt(), last.items,
            (h * settings.ignoreTopPct).toInt(), (h * settings.ignoreBottomPct).toInt(),
        )
        val unread = intArrayOf(
            (told.first - strip.top) * 1000 / strip.height(),
            (told.second - strip.top) * 1000 / strip.height(),
        )
        val crop = Bitmap.createBitmap(bitmap, strip.left, strip.top, strip.width(), strip.height())
        // The reader encodes the strip before it returns; the crop is not needed after.
        val inner = try {
            reader.start(scope, crop, settings.sourceLang, unread)
        } finally {
            if (crop !== bitmap) crop.recycle()
        }
        return StripRead(inner, strip, d, last, match)
    }

    /** [strip]'s rows read again whole, the margin's lines included, as the strip read before it. */
    private fun wholeStrip(reader: PageReader, bitmap: Bitmap, strip: StripRead, lang: SourceLang, scope: CoroutineScope): StripRead {
        val rows = strip.strip
        val crop = Bitmap.createBitmap(bitmap, rows.left, rows.top, rows.width(), rows.height())
        val inner = try {
            reader.start(scope, crop, lang)
        } finally {
            if (crop !== bitmap) crop.recycle()
        }
        return StripRead(inner, rows, strip.scrolled, strip.since, strip.match)
    }

    /**
     * A frame read in full a little earlier, shown again exactly as it was
     * — the reader turned back a page — needs no request: memory finds its
     * lines again, and it is read afresh only when memory lost one.
     */
    private fun earlierPage(match: ScrollMatch): StripRead? {
        val again = synchronized(recentSeen) { recentSeen.drop(1).firstOrNull { match.unmovedFrom(it.match) } } ?: return null
        return StripRead(null, Rect(), 0, again, match)
    }

    /** Forgets lettering remembered from earlier stops. */
    fun forgetRecent() {
        memory.clear()
        seen = null
        synchronized(recentSeen) { recentSeen.clear() }
    }

    /**
     * Opens the connection the next page will use, so the TLS handshake is
     * paid while the reader is still scrolling rather than after they stop.
     * Cheap to call often: it goes out at most once a minute, or, [afterIdle]
     * — the first scroll after a long look, when the radio has gone to
     * sleep — at most every few seconds.
     */
    fun warm(settings: AppSettings, afterIdle: Boolean = false) {
        if (readsAiFirst(settings)) {
            app.mangalens.translate.GeminiApi.warm(settings.apiKey, settings.effectiveModel(), afterIdle)
        }
    }

    /**
     * Reads the page: balloons and panels from the pixels, lines from OCR,
     * both at once since neither needs the other, then a second enlarged
     * look at any balloon OCR read nothing in.
     *
     * Read AI-first, the model reads the page itself and OCR only backs it
     * up once the stream has ended: it is left out here, so nothing
     * painted waits on it, and [withOcr] adds it when it is wanted.
     */
    suspend fun analyze(
        bitmap: Bitmap,
        settings: AppSettings,
        exclusions: List<Rect> = emptyList(),
    ): Analysis = coroutineScope {
        val ignoreTop = (bitmap.height * settings.ignoreTopPct).toInt()
        val ignoreBottom = (bitmap.height * settings.ignoreBottomPct).toInt()

        // Anchored vision is the quality path for every script — manhwa's
        // stylized/handwritten lettering needs it as much as vertical
        // Japanese does, and it degrades to the text path automatically.
        val useVision = settings.aiVision != AiVisionMode.OFF

        // Balloons come from the page pixels, so a region exists because the
        // page shows one — not because OCR happened to read something in it.
        // The detailed detections carry each balloon's interior mask, which
        // is what lets a card wipe the balloon clean instead of floating a
        // patch over it. Detection and OCR read the same frame and neither
        // waits on the other, so they run side by side.
        val scanJob = async(Dispatchers.Default) {
            BalloonFinder.analyze(bitmap, ignoreTop, ignoreBottom, exclusions)
        }
        if (readsAiFirst(settings) && useVision) {
            val none = OcrEngine.Result(emptyList(), settings.sourceLang)
            return@coroutineScope assemble(bitmap, settings, exclusions, scanJob.await(), none, emptyList(), useVision, pending = true)
        }
        val firstPass = ocr.recognize(bitmap, settings.sourceLang)
        val scan = scanJob.await()

        // ML Kit misses small and stylized lettering it would read fine at
        // twice the size. A balloon it read nothing in is cropped from the
        // full-resolution frame, enlarged, and read again on its own.
        val rereads = reread(bitmap, scan.balloons, firstPass, settings)
        assemble(bitmap, settings, exclusions, scan, firstPass, rereads, useVision, pending = false)
    }

    /**
     * [analysis] with the on-device OCR a read AI-first put off, as it
     * would have been had none been. OCR that fails reads nothing: it only
     * backs the model up, and never fails a read the model made.
     */
    internal suspend fun withOcr(analysis: Analysis, settings: AppSettings): Analysis {
        if (!analysis.ocrPending) return analysis
        val firstPass = try {
            ocr.recognize(analysis.bitmap, settings.sourceLang)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            OcrEngine.Result(emptyList(), settings.sourceLang)
        }
        return assemble(analysis.bitmap, settings, analysis.exclusions, analysis.scan, firstPass, emptyList(), analysis.useVision, pending = false)
    }

    private fun assemble(
        bitmap: Bitmap,
        settings: AppSettings,
        exclusions: List<Rect>,
        scan: PageScan,
        firstPass: OcrEngine.Result,
        rereads: List<OcrLine>,
        useVision: Boolean,
        pending: Boolean,
    ): Analysis {
        val ignoreTop = (bitmap.height * settings.ignoreTopPct).toInt()
        val ignoreBottom = (bitmap.height * settings.ignoreBottomPct).toInt()
        val lines = if (rereads.isEmpty()) firstPass.lines else firstPass.lines + rereads
        val ocrResult = OcrEngine.Result(lines, firstPass.lang)

        // A balloon the frame edge cuts through is only trusted where OCR
        // actually read lettering inside it: the visible part of a panel
        // can pass every shape test, and an empty partial region would be
        // handed to the vision model as a balloon to read. With OCR put
        // off, the model's own lines are that evidence: the resolver only
        // letters into a balloon an item lies in, and only one BalloonTrust
        // finds holding nothing but that lettering.
        val detected = scan.balloons.filter { b ->
            !b.partial || pending || lines.any { l ->
                Script.clean(l.text).length >= 2 && b.box.contains(l.box.centerX(), l.box.centerY())
            }
        }
        val balloons = detected.map { it.box }
        val bubbles = BubbleGrouper.group(
            ocrResult.lines, bitmap.height, ignoreTop, ignoreBottom, ocrResult.lang, exclusions,
            balloons, includeEmptyBalloons = useVision, panels = scan.panels,
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
            (if (pending) "ocr after" else "ocr ${firstPass.lines.size}+${rereads.size}") +
                " · balloons ${balloons.size} · panels ${scan.panels.size} · regions ${bubbles.size}"

        return Analysis(
            bitmap, ocrResult, detected, scan.panels, bubbles, anchorLines,
            ignoreTop, ignoreBottom, exclusions, useVision, diag, scan, pending,
        )
    }

    /**
     * OCRs the balloons the page pass read nothing in, each cropped from the
     * full-resolution frame and enlarged. Concurrent and capped: a page is
     * rarely more than a few balloons short, and the enlarged crops are
     * small.
     */
    private suspend fun reread(
        bitmap: Bitmap,
        balloons: List<Balloon>,
        first: OcrEngine.Result,
        settings: AppSettings,
    ): List<OcrLine> = coroutineScope {
        val unread = balloons.filter { b ->
            first.lines.none { l ->
                Script.clean(l.text).length >= 2 && b.box.contains(l.box.centerX(), l.box.centerY())
            }
        }.sortedByDescending { it.box.width().toLong() * it.box.height() }.take(MAX_REREAD)
        if (unread.isEmpty()) return@coroutineScope emptyList()
        val lang: SourceLang? = when {
            settings.sourceLang != SourceLang.AUTO -> settings.sourceLang
            first.lines.any { Script.cjkCount(it.text) > 0 } -> first.lang
            else -> null
        }
        unread.map { b -> async(Dispatchers.Default) { rereadOne(bitmap, b, lang) } }.awaitAll().flatten()
    }

    private suspend fun rereadOne(bitmap: Bitmap, b: Balloon, lang: SourceLang?): List<OcrLine> {
        val pad = (maxOf(b.box.width(), b.box.height()) * 0.06f).toInt().coerceAtLeast(4)
        val crop = Rect(
            (b.box.left - pad).coerceAtLeast(0),
            (b.box.top - pad).coerceAtLeast(0),
            (b.box.right + pad).coerceAtMost(bitmap.width),
            (b.box.bottom + pad).coerceAtMost(bitmap.height),
        )
        if (crop.width() < 16 || crop.height() < 16) return emptyList()
        val scale = (REREAD_SHORT_SIDE / minOf(crop.width(), crop.height())).coerceIn(1.5f, 3f)
        val w = (crop.width() * scale).toInt()
        val h = (crop.height() * scale).toInt()
        val src = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height())
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        if (scaled !== src) src.recycle()
        // Light lettering on a dark box reads far better with the polarity
        // flipped back to what the recognizers expect.
        val input = if (b.inverted) invert(scaled) else scaled
        return try {
            ocr.recognizeRegion(input, lang).mapNotNull { l ->
                val box = Rect(
                    crop.left + (l.box.left / scale).toInt(),
                    crop.top + (l.box.top / scale).toInt(),
                    crop.left + (l.box.right / scale).toInt(),
                    crop.top + (l.box.bottom / scale).toInt(),
                )
                if (box.width() < 2 || box.height() < 2) null else OcrLine(l.text, box, l.vertical)
            }
        } finally {
            if (input !== scaled) input.recycle()
            scaled.recycle()
        }
    }

    private fun invert(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) px[i] = px[i] xor 0x00FFFFFF
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Translates an analysed page and resolves it into cards.
     *
     * @param read the AI read of this very frame, when [startRead] began it
     *   ahead of analysis; otherwise one is started here if needed.
     */
    suspend fun translate(
        analysis: Analysis,
        settings: AppSettings,
        onPartial: (suspend (PageResult) -> Unit)? = null,
        read: PendingRead? = null,
    ): PageResult {
        if (readsAiFirst(settings) && analysis.useVision) {
            val wrapped = onPartial?.let { emit ->
                val f: suspend (PageResult) -> Unit = { pr -> emit(pr.copy(bubbles = soleClaimants(pr.bubbles))) }
                f
            }
            val raw = readPath(analysis, settings, wrapped, read)
            val result = raw.copy(bubbles = soleClaimants(raw.bubbles))
            val diag = analysis.diag
            // The read's own timings are diagnostics too: with them off the
            // status pill says what it always says.
            return if (diag == null) result.copy(diag = null) else result.copy(
                diag = "$diag · cards ${result.bubbles.size}" + (raw.diag?.let { " · $it" } ?: ""),
                balloons = analysis.balloons,
                panels = analysis.panels,
            )
        }
        read?.cancel()
        val bitmap = analysis.bitmap
        val ocrResult = analysis.ocr
        val detected = analysis.detected
        val balloons = analysis.balloons
        val bubbles = analysis.bubbles
        val diag = analysis.diag
        val useVision = analysis.useVision

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
                return PageResult(
                    emptyList(), "", null, diag = diag?.plus(" · cards 0"),
                    balloons = balloons, panels = analysis.panels,
                )
            }
        }

        // One balloon, one cleaning: two cards bound to the same detection
        // would each stamp it opaque, and whichever draws later erases the
        // other's text. The better-contained card keeps the balloon; the
        // rest fall back to plain cards over their own boxes.
        val wrapped = onPartial?.let { emit ->
            val f: suspend (PageResult) -> Unit = { pr -> emit(pr.copy(bubbles = soleClaimants(pr.bubbles))) }
            f
        }
        val raw = dispatch(
            bitmap, settings, analysis.exclusions, wrapped,
            ocrResult, bubbles, detected, analysis.anchorLines, analysis.ignoreTop, analysis.ignoreBottom, useVision,
        )
        val result = raw.copy(bubbles = soleClaimants(raw.bubbles))
        return if (diag == null) result else result.copy(
            diag = "$diag · cards ${result.bubbles.size}",
            balloons = balloons,
            panels = analysis.panels,
        )
    }

    /**
     * Translates one page of on-device regions: the providers that do not
     * read pages AI-first, and pages read with the image turned off.
     */
    private suspend fun dispatch(
        bitmap: Bitmap,
        settings: AppSettings,
        exclusions: List<Rect>,
        onPartial: (suspend (PageResult) -> Unit)?,
        ocrResult: OcrEngine.Result,
        bubbles: List<Bubble>,
        detected: List<Balloon>,
        anchorLines: List<OcrLine>,
        ignoreTop: Int,
        ignoreBottom: Int,
        useVision: Boolean,
    ): PageResult {
        val vision = VisionLlmEngine(settings, glossary, cast)

        // Straight to the answer when it is already cached (re-reads,
        // scroll-backs, peeks): no network. The key is the page's content —
        // OCR text, or the balloons' own pixels where OCR read nothing — so
        // a hit can only replay text onto the balloons it was written for,
        // never onto whatever now sits at the same spot.
        val pageKey = if (useVision) visionKey(vision.cacheNamespace, ocrResult.lang, bubbles, bitmap) else null
        if (pageKey != null) {
            visionCacheGet(pageKey, bubbles, bitmap.width, bitmap.height)?.let { cached ->
                return PageResult(
                    toRender(bitmap, cached, bubbles, anchorLines, ignoreTop, ignoreBottom, exclusions, detected),
                    vision.label, null,
                )
            }
        } else {
            val ns = vision.cacheNamespace.removePrefix("Vision:")
            val dialogue = bubbles.filter { it.kind == BubbleKind.DIALOGUE }
            if (dialogue.isNotEmpty() && translation.fullyCached(ns, ocrResult.lang, dialogue.map { it.text })) {
                return aiTextTranslate(bitmap, bubbles, ocrResult.lang, settings, detected)
            }
        }

        // The answer streams, and every balloon it finishes is painted as it
        // lands, so the page fills in balloon by balloon in reading order
        // instead of arriving all at once when the last one closes.
        var fromImage: List<RenderBubble> = emptyList()
        var imageFailure: Exception? = null
        if (useVision) {
            try {
                val streamed = ArrayList<VisionLlmEngine.VisionBubble>()
                val onBubble: (suspend (VisionLlmEngine.VisionBubble) -> Unit)? = onPartial?.let { emit ->
                    { vb ->
                        streamed.add(vb)
                        fromImage = toRender(
                            bitmap, streamed.toList(), bubbles, anchorLines, ignoreTop, ignoreBottom, exclusions, detected,
                        )
                        emit(PageResult(fromImage, vision.label, null))
                    }
                }
                val pageBubbles = vision.translatePage(bitmap, ocrResult.lang, bubbles, onBubble)
                // Accept the vision result only if it covered most of the
                // dialogue regions we know exist. Otherwise fall back to the
                // text path, which renders AI text on exact OCR geometry.
                val dialogueIds = bubbles.indices.filter { bubbles[it].kind == BubbleKind.DIALOGUE }
                val covered = pageBubbles.count { it.id in dialogueIds }
                val goodCoverage = dialogueIds.isEmpty() || covered * 2 >= dialogueIds.size
                if (pageBubbles.isNotEmpty() && goodCoverage) {
                    // A region the model skipped used to render nothing at
                    // all, leaving raw balloons scattered through an
                    // otherwise translated page. Anything it passed over
                    // that OCR *could* read is filled in from the text
                    // engine instead.
                    val gap = gapFill(bubbles, pageBubbles, ocrResult.lang, settings)
                    val complete = pageBubbles + gap.items
                    if (gap.failure == null) {
                        pageKey?.let { visionCachePut(it, bubbles, complete, bitmap.width, bitmap.height) }
                    }
                    return PageResult(
                        toRender(bitmap, complete, bubbles, anchorLines, ignoreTop, ignoreBottom, exclusions, detected),
                        vision.label, null,
                        failure = gap.failure?.let(AiFailure::cause),
                        alert = gap.failure?.takeIf(AiFailure::keyRejected)?.let { vision.label + " rejected the API key — check it in MangaLens" },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The text path still answers what OCR read; the failure is
                // reported alongside, since the page image's lettering is
                // left unread.
                imageFailure = e
            }
        }
        // Lines the image read already put on the page stay unless the text
        // path answers the same balloon: a line losing to no line at all is
        // strictly a downgrade.
        val onProgress: (suspend (List<RenderBubble>) -> Unit)? = onPartial?.let { emit ->
            { rendered -> emit(PageResult(UpgradeMerge.merge(fromImage, rendered), vision.label, null)) }
        }
        val text = aiTextTranslate(bitmap, bubbles, ocrResult.lang, settings, detected, onProgress)
        val shown = UpgradeMerge.merge(fromImage, text.bubbles)
        val failure = imageFailure ?: return text.copy(bubbles = shown)
        return text.copy(
            bubbles = shown,
            failure = AiFailure.cause(failure),
            alert = failure.takeIf(AiFailure::keyRejected)?.let { vision.label + " rejected the API key — check it in MangaLens" },
        )
    }

    // ---- AI-first reading (Gemini) ----

    /**
     * One page read AI-first, painted as it streams.
     *
     * The order things reach the screen is the whole point: lettering
     * remembered from an earlier stop repaints at once; the model's lines
     * then land one by one in reading order, each lettered once, in the
     * words it keeps. Where lettering sits on detailed art, an image
     * model's redraw of that art replaces the local reconstruction when it
     * arrives — same English, same place, cleaner ground under it.
     */
    private suspend fun readPath(
        analysis: Analysis,
        settings: AppSettings,
        onPartial: (suspend (PageResult) -> Unit)?,
        pending: PendingRead?,
    ): PageResult = coroutineScope {
        val bitmap = analysis.bitmap
        val bubbles = analysis.bubbles
        val lang = analysis.ocr.lang
        val reader = PageReader(settings, glossary, cast)
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        val resolver = ReadResolver(
            bitmap, analysis.detected, analysis.anchorLines,
            analysis.ignoreTop, analysis.ignoreBottom, analysis.exclusions, analysis.panels,
        )

        // On-device OCR, when it was put off: read while the request is in
        // flight, after lettering remembered from the last stop is back on
        // screen, and never waited for until the stream has ended. A line
        // the eraser found nothing under is backed by it as soon as it lands.
        var ocrJob: Deferred<Analysis>? = null
        val ocrRead = AtomicReference<Analysis?>()
        var anchored = !analysis.ocrPending
        fun ocrStart() {
            if (ocrJob == null && analysis.ocrPending) {
                ocrJob = async(Dispatchers.Default) { withOcr(analysis, settings).also(ocrRead::set) }
            }
        }
        fun anchorIfRead() {
            if (anchored) return
            val lined = ocrRead.get() ?: return
            resolver.anchor(lined.anchorLines)
            anchored = true
        }
        suspend fun withLines(): Analysis = (ocrJob?.await() ?: withOcr(analysis, settings)).also {
            if (!anchored) resolver.anchor(it.anchorLines)
            anchored = true
        }

        // Lettering remembered from earlier stops, found again on this frame.
        var recalled: List<PageItem>? = null
        suspend fun recall(): List<PageItem> = recalled ?: runCatching {
            withContext(Dispatchers.Default) { memory.recall(bitmap, analysis.ignoreTop, analysis.ignoreBottom) }
        }.getOrDefault(emptyList()).also { recalled = it }

        // Scroll-backs and re-reads: the whole page from cache, no network —
        // once the page is shown to be the one the answer was for.
        val key = if (bubbles.isNotEmpty()) visionKey(reader.cacheNamespace, lang, bubbles, bitmap) else null
        key?.let { readCacheGet(it, bubbles, bitmap.width, bitmap.height) }?.let { cached ->
            if (!replayable(cached, recall())) return@let
            pending?.cancel()
            // The few rows a nudge revealed are no part of the answer
            // replayed: as when nothing replays, the next stop still
            // measures from the frame last read in full.
            if ((pending as? StripRead)?.unreadNudge != true) {
                memory.remember(bitmap, cached)
                seen = Seen(matchOf(bitmap), cached)
            }
            return@coroutineScope PageResult(resolver.resolve(cached), reader.label, null, diag = "cached")
        }
        var read = pending ?: reader.start(this, bitmap, lang)

        val streamed = ArrayList<PageItem>()
        var firstAt = -1L
        val remembered: List<PageItem> = if (onPartial != null || read is StripRead) {
            // A strip's read leaves the rest of the screen to memory, the
            // lines its edge now cuts included: the page moved by a measured
            // scroll, and those are looked for where it put them.
            val cut = (read as? StripRead)?.let { s ->
                runCatching {
                    withContext(Dispatchers.Default) {
                        memory.recallCut(bitmap, s.since.items, -s.scrolled, analysis.ignoreTop, analysis.ignoreBottom)
                    }
                }.getOrDefault(emptyList())
            }.orEmpty()
            val whole = recall()
            whole + cut.filter { c -> whole.none { Rect.intersects(it.box, c.box) } }
        } else {
            recalled.orEmpty()
        }

        // A strip read's answers, less the halves of balloons its edge cut.
        fun fresh(items: List<PageItem>): List<PageItem> = (read as? StripRead)?.trim(items, remembered) ?: items

        // Lines the last stop's edge cut were read only as far as they
        // showed; recalled, they keep no wording over this stop's reading
        // of them whole.
        val partial = (read as? StripRead)?.cutByEdge(remembered, bitmap.height, analysis.ignoreTop, analysis.ignoreBottom).orEmpty()
        fun keep(items: List<PageItem>): List<PageItem> = Wording.keep(remembered, items, partial)

        // What streamed, as it is lettered however the read ends: every
        // place that letters it trims the strip's halves first, or a failed
        // read would put a half-balloon's English over the whole line.
        fun lettered(items: List<PageItem>): List<PageItem> = keep(fresh(items))

        suspend fun paint() {
            val emit = onPartial ?: return
            val shown = resolver.resolve(lettered(streamed))
            if (shown.isNotEmpty()) emit(PageResult(shown, reader.label, null))
        }

        // A strip read counts on memory for the rest of the screen; when
        // memory lost a line there, the whole screen is read.
        val strip = read as? StripRead
        if (strip != null && !strip.covered(remembered, bitmap.height, analysis.ignoreTop, analysis.ignoreBottom)) {
            strip.cancel()
            // A line memory lost in the strip's margin is read again with the
            // strip, margin and all; only one lost above the strip needs the
            // whole screen, the new lines last in its reading order.
            read = if (strip.coveredByStrip(remembered, bitmap.height, analysis.ignoreTop, analysis.ignoreBottom)) {
                wholeStrip(reader, bitmap, strip, lang, this)
            } else {
                reader.start(this, bitmap, lang)
            }
        }
        if (remembered.isNotEmpty()) paint()
        ocrStart()

        var failure: Exception? = null
        val items: List<PageItem>? = try {
            read.collect { item ->
                if (firstAt < 0) firstAt = elapsed()
                streamed.add(item)
                anchorIfRead()
                paint()
            }
        } catch (e: CancellationException) {
            // The reader moved on mid-read, usually having read what was
            // shown. That much is remembered, so the next stop repaints it
            // rather than waiting for the model to read it again. The
            // collector has stopped: nothing adds to the list any more.
            val shown = ArrayList(streamed)
            if (shown.isNotEmpty()) runCatching { memory.remember(bitmap, lettered(shown)) }
            throw e
        } catch (e: Exception) {
            failure = e
            null
        }

        if (items == null) {
            // Refused or failed. Whatever streamed stays, and whatever
            // on-device OCR read goes to the AI as text. Nothing else
            // translates the page: the failure is reported, so the reader
            // knows why lettering stays raw rather than seeing some other
            // translation in the AI's place.
            val lined = withLines()
            val partialShown = resolver.resolve(lettered(streamed))
            val text = try {
                aiTextTranslate(bitmap, lined.bubbles, lined.ocr.lang, settings, lined.detected)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val shown = if (text == null) partialShown else UpgradeMerge.merge(text.bubbles, partialShown)
            val cause = failure?.let(AiFailure::cause) ?: "unexpected error"
            return@coroutineScope PageResult(
                shown, reader.label, "AI read failed: $cause",
                diag = "read failed: $cause at ${elapsed()} ms",
                alert = failure?.takeIf(AiFailure::keyRejected)?.let { "Gemini rejected the API key — check it in MangaLens" },
                failure = cause,
            )
        }

        val answered = fresh(items)
        // What OCR read backs up the model from here: lines it left out,
        // and cards for lettering the eraser could not find under its box.
        val lined = withLines()
        val gap = gapItems(lined.bubbles, lined.detected, answered + remembered, lined.ocr.lang, settings)
        val complete = answered + gap.items
        val finalItems = keep(complete)
        // What is stored is what the reader sees, so the next stop and a
        // scroll-back say it in the same words. A reply that broke off is
        // shown, and remembered line by line, but never kept as the page's
        // whole answer.
        //
        // A nudge that sent nothing is no frame read in full: the rows it
        // revealed were never read, so the next stop measures its scroll,
        // and the rows it tells the model are new, from the frame that was.
        // Memory is left as that frame left it too: remembering this one
        // would put copies in place of the lines that frame's answer holds,
        // and those of them the next stop's edge cuts would not be found.
        val nudge = (read as? StripRead)?.unreadNudge == true
        if (!read.cutOff && !nudge) {
            // Balloons a failed gap fill left in the original are not part
            // of the page's answer either: a scroll-back reads them again.
            if (gap.failure == null) key?.let { readCachePut(it, bubbles, finalItems, bitmap.width, bitmap.height) }
            seen = Seen((read as? StripRead)?.match ?: matchOf(bitmap), finalItems)
        }
        if (!nudge) memory.remember(bitmap, finalItems)
        lastItems = finalItems
        var rendered = resolver.resolve(finalItems)

        // Lettering on detailed art has only been smoothed over locally.
        // Its ground is redrawn by the image model once the whole page is
        // known, so every region that needs it goes up in one request —
        // sound effects never: those stay part of the drawing.
        var note: String? = null
        val targets = if (!AiCleaner.supports(settings)) emptyList() else {
            resolver.freeItems
                .filter { it.kind != ItemKind.SFX }
                .mapNotNull { item ->
                    resolver.erasureOf(item)?.takeIf { !it.flat && it.busy >= BUSY_FOR_AI }?.let { item to it }
                }
                .sortedByDescending { it.second.busy }
        }
        if (targets.isNotEmpty()) {
            onPartial?.invoke(PageResult(rendered, reader.label, "cleaning art…"))
            val cleaned = withTimeoutOrNull(CLEANUP_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { AiCleaner(settings).cleanRegions(bitmap, targets.map { it.second.rect }) }
            }
            if (cleaned != null) {
                val redrawn = withContext(Dispatchers.Default) {
                    var any = 0
                    for ((item, e) in targets) {
                        AiCleaner.refine(bitmap, cleaned, e)?.let {
                            resolver.upgrade(item, it)
                            any++
                        }
                    }
                    if (any > 0) rendered = resolver.resolve(finalItems)
                    any
                }
                if (redrawn > 0) note = "art cleaned"
                cleaned.recycle()
            }
        }
        PageResult(
            rendered, reader.label, note,
            diag = "first ${if (firstAt >= 0) "$firstAt ms" else "—"} · done ${elapsed()} ms" +
                read.summary.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
            failure = gap.failure?.let(AiFailure::cause),
            alert = gap.failure?.takeIf(AiFailure::keyRejected)?.let { reader.label + " rejected the API key — check it in MangaLens" },
        )
    }

    /**
     * Whether a cached answer may be replayed onto this frame. Read
     * AI-first, OCR has read nothing yet, so the key is no more than each
     * balloon's layout — an 8x8 hash of its pixels — and one short line
     * ("뭐?") hashes like another of its shape ("왜?"): a balloon found
     * where a cached line sits says nothing of what it holds. Every line of
     * the answer must be found again, stroke by stroke, by scroll memory,
     * and in the same words: a page read since under the same key has left
     * its own answer there, and memory's word for the lettering is what
     * tells the two apart. Otherwise the page is read afresh. A page
     * scrolled back to from beyond memory's reach then costs a request, a
     * smaller cost than English on the wrong balloon.
     */
    private fun replayable(cached: List<PageItem>, recalled: List<PageItem>): Boolean =
        cached.all { item ->
            recalled.any { r -> r.en == item.en && Rect.intersects(r.box, item.box) && iou(r.box, item.box) > 0.5f }
        }

    /** [bitmap]'s scroll signature: the one [startRead] measured when it was this frame's. */
    private fun matchOf(bitmap: Bitmap): ScrollMatch =
        lastMatch?.takeIf { it.first === bitmap }?.second ?: ScrollMatch.of(bitmap)

    /**
     * Dialogue on-device analysis found in a detected balloon that the model
     * passed over, translated through the text engine so the page is never
     * left with a raw balloon in it. Only balloon-held regions qualify: OCR
     * also reads watermarks, credits and the browser's own chrome, and the
     * model leaving those out is deliberate.
     */
    private suspend fun gapItems(
        bubbles: List<Bubble>,
        detected: List<Balloon>,
        answered: List<PageItem>,
        lang: SourceLang,
        settings: AppSettings,
    ): Gap<PageItem> {
        val missing = bubbles.filter { b ->
            b.kind == BubbleKind.DIALOGUE && b.text.isNotBlank() &&
                detected.any { containedShare(b.box, it.box) > 0.8f } &&
                answered.none { a ->
                    Rect.intersects(a.box, b.box) &&
                        (containedShare(a.box, b.box) > 0.3f || containedShare(b.box, a.box) > 0.3f)
                }
        }
        if (missing.isEmpty()) return Gap(emptyList())
        val outcome = try {
            translation.translate(
                missing.map { it.text }, lang, settings,
                kinds = missing.map { it.kind },
                runs = missing.map { it.runId },
                parts = missing.map { it.runPart },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Gap(emptyList(), e)
        }
        return Gap(missing.mapIndexedNotNull { k, b ->
            val en = JunkFilter.accept(b.text, outcome.texts.getOrElse(k) { "" }) ?: return@mapIndexedNotNull null
            PageItem(Rect(b.box), ItemKind.SPEECH, b.text, en, vertical = b.vertical)
        })
    }

    /**
     * Lines filled in for balloons the model passed over, and why the fill
     * failed when it did: those balloons stay in the original, and the
     * reader is told so rather than left to wonder.
     */
    private class Gap<T>(val items: List<T>, val failure: Exception? = null)

    /** The items the last completed pass resolved, for the page harness to record and replay. */
    @Volatile
    internal var lastItems: List<PageItem> = emptyList()
        private set

    private fun readCacheGet(key: String, bubbles: List<Bubble>, w: Int, h: Int): List<PageItem>? {
        if (bubbles.isEmpty()) return null
        val raw = cache.get(key) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            if (obj.optInt("v") != READ_CACHE_VERSION) throw IllegalStateException("stale read cache entry")
            val regs = obj.getJSONArray("regions")
            if (regs.length() != bubbles.size) throw IllegalStateException("region count changed")
            val stored = (0 until regs.length()).map { i ->
                val a = regs.getJSONArray(i)
                Rect(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3))
            }
            // Same content, possibly at a new scroll offset: one consistent
            // displacement or no replay at all.
            val shift = ReplayGeometry.shift(stored, bubbles.map { normalized(it.box, w, h) })
                ?: throw IllegalStateException("layout no longer matches")
            val arr = obj.getJSONArray("items")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONArray(i)
                PageItem(
                    box = Rect(
                        (o.getInt(0) + shift.dx) * w / 1000,
                        (o.getInt(1) + shift.dy) * h / 1000,
                        (o.getInt(2) + shift.dx) * w / 1000,
                        (o.getInt(3) + shift.dy) * h / 1000,
                    ),
                    kind = ItemKind.valueOf(o.getString(4)),
                    src = o.getString(5),
                    en = o.getString(6),
                    who = o.getString(7),
                    vertical = o.getInt(8) == 1,
                    loud = o.getInt(9) == 1,
                    textColor = if (o.isNull(10)) null else o.getInt(10),
                    outlineColor = if (o.isNull(11)) null else o.getInt(11),
                )
            }
        }.getOrNull()
    }

    private fun readCachePut(key: String, bubbles: List<Bubble>, items: List<PageItem>, w: Int, h: Int) {
        if (bubbles.isEmpty() || items.isEmpty()) return
        val regs = JSONArray()
        for (b in bubbles) {
            val n = normalized(b.box, w, h)
            regs.put(JSONArray().put(n.left).put(n.top).put(n.right).put(n.bottom))
        }
        val arr = JSONArray()
        for (it in items) {
            val n = normalized(it.box, w, h)
            arr.put(
                JSONArray().put(n.left).put(n.top).put(n.right).put(n.bottom)
                    .put(it.kind.name).put(it.src).put(it.en).put(it.who)
                    .put(if (it.vertical) 1 else 0).put(if (it.loud) 1 else 0)
                    .put(it.textColor ?: JSONObject.NULL).put(it.outlineColor ?: JSONObject.NULL)
            )
        }
        cache.put(key, JSONObject().put("v", READ_CACHE_VERSION).put("regions", regs).put("items", arr).toString())
    }

    // ---- AI text path (small payloads — slow-internet friendly) ----

    private suspend fun aiTextTranslate(
        bitmap: Bitmap,
        bubbles: List<Bubble>,
        lang: SourceLang,
        settings: AppSettings,
        detected: List<Balloon>,
        onProgress: (suspend (List<RenderBubble>) -> Unit)? = null,
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
            onProgress = onProgress?.let { emit ->
                { texts ->
                    emit(
                        texts.entries.sortedBy { it.key }.mapNotNull { (k, en) ->
                            val b = bubbles[idx[k]]
                            val gated = JunkFilter.accept(b.text, en) ?: return@mapNotNull null
                            renderBubble(bitmap, b.box, gated, b.text, b.vertical, b.kind, detected)
                        }
                    )
                }
            },
        )
        val rendered = idx.mapIndexedNotNull { k, i ->
            val b = bubbles[i]
            val gated = JunkFilter.accept(b.text, outcome.texts.getOrElse(k) { "" })
                ?: return@mapIndexedNotNull null
            renderBubble(bitmap, b.box, gated, b.text, b.vertical, b.kind, detected)
        }
        return PageResult(rendered, outcome.engineLabel, null)
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
    ): Gap<VisionLlmEngine.VisionBubble> {
        val done = answered.filter { it.id >= 0 }.mapTo(HashSet()) { it.id }
        val missing = bubbles.indices.filter {
            it !in done && bubbles[it].kind == BubbleKind.DIALOGUE && bubbles[it].text.isNotBlank()
        }
        if (missing.isEmpty()) return Gap(emptyList())

        val outcome = try {
            translation.translate(
                missing.map { bubbles[it].text }, lang, settings,
                kinds = missing.map { bubbles[it].kind },
                runs = missing.map { bubbles[it].runId },
                parts = missing.map { bubbles[it].runPart },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Gap(emptyList(), e)
        }

        return Gap(missing.mapIndexedNotNull { k, i ->
            val gated = JunkFilter.accept(bubbles[i].text, outcome.texts.getOrElse(k) { "" })
                ?: return@mapIndexedNotNull null
            VisionLlmEngine.VisionBubble(i, 0, 0, 0, 0, bubbles[i].text, gated, sfx = false)
        })
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
        detected: List<Balloon>,
    ): List<RenderBubble> {
        val w = bitmap.width
        val h = bitmap.height
        val balloons = detected.map { it.box }
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
                if (v.sfx) BubbleKind.SFX else BubbleKind.DIALOGUE, detected,
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
                if (v.sfx) BubbleKind.SFX else BubbleKind.DIALOGUE, detected,
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
        detected: List<Balloon>,
    ): RenderBubble {
        // Art that passed for a balloon — a face, a highlight — is never
        // wiped: only a detection holding nothing but this lettering is.
        // OCR often reads only part of a balloon, so a balloon whose ink is
        // one compact block of lettering around the part it did read is
        // lettering too; a face's ink is spread thin across it.
        val balloon = balloonFor(box, detected)?.takeIf {
            BalloonTrust.holdsOnly(bitmap, it, listOf(box)) ||
                BalloonTrust.letteringBlock(bitmap, it)?.let { block -> Rect.intersects(block, box) } == true
        }
        val bg = if (balloon != null) PageColors.interiorColor(bitmap, balloon) else PageColors.sampleBackground(bitmap, box)
        val textColor = when {
            balloon?.inverted == true -> 0xFFF2F3F7.toInt()
            PageColors.luminance(bg) < 140 -> Color.WHITE
            else -> 0xFF17181C.toInt()
        }
        // A gradient or textured balloon is cleaned with its own paper
        // continued under the lettering, not with a flat patch of the average.
        val fill = balloon?.let { BalloonFill.build(bitmap, it) }
        return RenderBubble(
            box = Rect(box),
            translated = translated,
            original = original,
            bgColor = bg,
            textColor = textColor,
            vertical = vertical,
            kind = kind,
            balloon = balloon,
            fill = fill,
        )
    }

    /**
     * Resolves double claims on one balloon. Association is per-card and can
     * legitimately bind two cards to one detection — an anchored region plus
     * the empty balloon region it was not welded into, or an extra snapped
     * nearby. Only the best-contained card may clean the balloon.
     */
    private fun soleClaimants(bubbles: List<RenderBubble>): List<RenderBubble> {
        val claims = java.util.IdentityHashMap<Balloon, MutableList<Int>>()
        bubbles.forEachIndexed { i, b ->
            b.balloon?.let { claims.getOrPut(it) { mutableListOf() }.add(i) }
        }
        if (claims.values.none { it.size > 1 }) return bubbles
        val out = bubbles.toMutableList()
        for ((balloon, idxs) in claims) {
            if (idxs.size < 2) continue
            val keep = idxs.maxBy { containedShare(bubbles[it].box, balloon.box) }
            for (i in idxs) {
                if (i != keep) out[i] = out[i].copy(balloon = null)
            }
        }
        return out
    }

    /**
     * The detected balloon this region belongs to, so its card can wipe the
     * whole balloon clean rather than float a patch over part of it. A welded
     * region carries the balloon's own box; an OCR-tight region sits inside
     * one; anything else — SFX on the art, captions, a drifted extra — has no
     * balloon and keeps the plain card.
     */
    private fun balloonFor(box: Rect, detected: List<Balloon>): Balloon? {
        detected.firstOrNull { it.box == box }?.let { return it }
        return detected.firstOrNull { b ->
            b.box.contains(box.centerX(), box.centerY()) &&
                (iou(b.box, box) > 0.2f || containedShare(box, b.box) > 0.8f)
        }
    }
}
