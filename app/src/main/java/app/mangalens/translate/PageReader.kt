package app.mangalens.translate

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import app.mangalens.ocr.Script
import app.mangalens.settings.AiReasoning
import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SourceLang
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The AI-first page reader: the raw screen goes to the model the moment it
 * is still, and the model finds every piece of lettering on it — balloons,
 * captions, text on the art, sound effects — and translates it, streaming
 * each piece back as it finishes.
 *
 * Everything here is shaped by the wait the reader feels. The model is
 * asked for as little output as a typeset page needs — box, kind, speaker,
 * source, English — because output tokens are most of the wait; colours
 * and orientation are measured from the pixels instead. The items stream
 * first and the series memory after them, so the first balloon is painted
 * while the model is still writing the rest. And because the time before
 * Gemini's first token varies from under two seconds to six, the same
 * request is sent twice and the first to answer wins: the tail is cut off
 * at the price of a request nobody reads. The duplicate follows as soon as
 * the first request's page is uploaded rather than alongside it, so on a
 * phone's uplink the two never split the bandwidth the first one needs.
 */
class PageReader internal constructor(
    private val settings: AppSettings,
    private val glossary: GlossaryStore?,
    private val cast: CastBook?,
    private val transport: Transport,
    private val race: Int = RACE,
    private val hedgeMs: Long = HEDGE_MS,
    private val staggerMs: Long = STAGGER_MS,
    private val uploadGraceMs: Long = UPLOAD_GRACE_MS,
) {

    constructor(
        settings: AppSettings,
        glossary: GlossaryStore? = null,
        cast: CastBook? = null,
    ) : this(settings, glossary, cast, geminiTransport(settings))

    /**
     * One streamed request; the network, or a test's stand-in for it.
     * [onSent] reports that the request has been uploaded, [onFinish] the
     * model's reason for stopping once the reply has ended.
     */
    internal fun interface Transport {
        suspend fun stream(
            model: String,
            body: JSONObject,
            onSent: () -> Unit,
            onFinish: (String) -> Unit,
            onDelta: suspend (String) -> Unit,
        ): String
    }

    val label: String get() = LlmHttp.providerLabel(settings)

    val cacheNamespace: String get() = "Read:" + label + ":" + settings.effectiveModel()

    /**
     * Starts reading [bitmap] in [scope] and returns at once. The page is
     * encoded before this returns, so the caller may recycle [bitmap] as
     * soon as it likes. Cancelling [scope] or the returned read aborts the
     * request.
     */
    fun start(scope: CoroutineScope, bitmap: Bitmap, lang: SourceLang): PendingRead {
        val started = System.nanoTime()
        val page = PageRequest(bitmap.width, bitmap.height, encode(bitmap, settings.dataSaver), lang)
        val read = Read(started, elapsedMs(started))
        val job = scope.launch(Dispatchers.Default) {
            try {
                perform(read, page)
            } catch (e: CancellationException) {
                read.finish(e)
                throw e
            } catch (e: Throwable) {
                read.finish(e)
            }
        }
        job.invokeOnCompletion { cause -> read.finish(cause ?: IllegalStateException("page read ended without an answer")) }
        read.job = job
        return read
    }

    /** Reads [bitmap] start to finish; [onItem] sees each item as it lands. */
    suspend fun read(
        bitmap: Bitmap,
        lang: SourceLang,
        onItem: (suspend (PageItem) -> Unit)? = null,
    ): List<PageItem> = coroutineScope { start(this, bitmap, lang).collect(onItem) }

    private suspend fun perform(read: Read, page: PageRequest) {
        var model = settings.effectiveModel()
        if (model != GeminiApi.FALLBACK_MODEL && GeminiApi.isMissing(model)) model = GeminiApi.FALLBACK_MODEL
        val answer = try {
            race(read, page, model)
        } catch (e: GeminiModelMissing) {
            // A model picked months ago may since have been retired; the
            // newest Flash reads the page rather than nothing at all.
            if (model == GeminiApi.FALLBACK_MODEL || read.hasItems) throw e
            model = GeminiApi.FALLBACK_MODEL
            race(read, page, model)
        }
        read.model = model
        val reply = runCatching { LlmHttp.extractJsonObject(answer.text) }.getOrNull()
        // The streamed items are the answer. The full parse only adds any
        // the stream could not split out, and a reply cut off mid-array
        // still keeps every item that closed.
        val items = reply?.optJSONArray("items")
        if (items != null) {
            for (i in 0 until items.length()) items.optJSONObject(i)?.let { o -> page.item(o)?.let { read.emit(it) } }
        } else if (!read.hasItems) {
            throw RuntimeException("Gemini reply had no readable items")
        }
        // Whole only when the model said it was done and its JSON closed.
        // A filter that trips part-way, or the token cap, leaves items that
        // are real but not the page: shown, never kept as its answer.
        read.cutBy = when {
            answer.finish.isNotEmpty() && answer.finish != "STOP" -> answer.finish
            items == null -> "incomplete JSON"
            else -> null
        }
        if (reply != null && !read.cutOff) learn(reply, read.items)
        read.finish(null)
    }

    /**
     * Sends the page [race] times and keeps whichever request delivers a
     * complete item first; the rest are cancelled that moment. The
     * duplicates go out once the first request reports its page uploaded,
     * or after [staggerMs] if it has not said so. After a rate limit (and
     * with data saver, where the upload is what costs) one request goes
     * out, with a duplicate hedged in only if it has shown nothing [hedgeMs]
     * after its page was uploaded: on a slow uplink the upload alone can
     * outlast the hedge, and a duplicate sent then would only split the
     * bandwidth. If the upload never reports back, the hedge goes
     * [uploadGraceMs] later than it would have. Either way a spare still
     * held back is sent at once when everything already sent has failed in
     * a way a second try could survive.
     *
     * @return the winning request's full reply and why it ended.
     */
    private suspend fun race(read: Read, page: PageRequest, model: String): Reply = coroutineScope {
        val body = page.body(model)
        val events = Channel<Event>(Channel.UNLIMITED)
        val contenders = ArrayList<Job>()
        fun launchContender() {
            val id = contenders.size
            read.requests++
            contenders += launch {
                val stream = BubbleStream("items")
                var finish = ""
                try {
                    val text = transport.stream(model, body, { events.trySend(Event.Sent(id)) }, { finish = it }) { delta ->
                        for (o in stream.feed(delta)) page.item(o)?.let { events.trySend(Event.Item(id, it)) }
                    }
                    events.trySend(Event.Done(id, Reply(text, finish)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    events.trySend(Event.Failed(id, e))
                }
            }
        }

        val single = race <= 1 || settings.dataSaver || calm()
        var spares = if (!single) race - 1 else if (hedgeMs > 0) 1 else 0
        var timer: Job? = null
        fun sparesIn(ms: Long) {
            timer?.cancel()
            timer = launch {
                delay(ms)
                events.trySend(Event.SparesDue)
            }
        }
        if (spares > 0) sparesIn(if (single) hedgeMs + uploadGraceMs else staggerMs)
        fun releaseSpares() {
            timer?.cancel()
            repeat(spares) { launchContender() }
            spares = 0
        }
        launchContender()

        var winner = -1
        val failures = ArrayList<Throwable>()
        fun crown(id: Int) {
            winner = id
            spares = 0
            timer?.cancel()
            contenders.forEachIndexed { i, job -> if (i != id) job.cancel() }
        }
        suspend fun winnersReply(): Reply {
            while (true) {
                when (val ev = events.receive()) {
                    is Event.Item -> {
                        if (winner < 0) crown(ev.id)
                        if (ev.id == winner) read.emit(ev.item)
                    }
                    is Event.Done -> {
                        if (winner < 0) crown(ev.id)
                        if (ev.id == winner) return ev.reply
                    }
                    // Spares are still held only while the first request is
                    // the one out, so this is its upload reporting done.
                    is Event.Sent -> if (winner < 0 && spares > 0) {
                        if (single) sparesIn(hedgeMs) else releaseSpares()
                    }
                    Event.SparesDue -> if (winner < 0) releaseSpares()
                    is Event.Failed -> {
                        if (ev.error is GeminiRateLimited) calmDown()
                        if (ev.id == winner) throw ev.error
                        if (winner >= 0) continue
                        failures += ev.error
                        if (failures.size == contenders.size) {
                            if (spares > 0 && transient(ev.error)) releaseSpares() else throw worst(failures)
                        }
                    }
                }
            }
        }
        try {
            winnersReply()
        } finally {
            timer?.cancel()
            contenders.forEachIndexed { i, job -> if (i != winner) job.cancel() }
        }
    }

    /** A request's whole reply text, and the model's reason for stopping ("" when it gave none). */
    private class Reply(val text: String, val finish: String)

    private sealed interface Event {
        class Sent(val id: Int) : Event
        class Item(val id: Int, val item: PageItem) : Event
        class Done(val id: Int, val reply: Reply) : Event
        class Failed(val id: Int, val error: Throwable) : Event
        object SparesDue : Event
    }

    /** Only on a finished reply: a half-read page teaches the series memory nothing reliable. */
    private fun learn(reply: JSONObject, items: List<PageItem>) {
        val terms = HashMap<String, String>()
        reply.optJSONArray("new_terms")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val src = o.optString("src", "").trim()
                val en = o.optString("en", "").trim()
                if (src.isNotEmpty() && en.isNotEmpty()) terms[src] = en
            }
        }
        if (terms.isNotEmpty()) glossary?.learn(terms)
        val members = HashMap<String, CastBook.Member>()
        reply.optJSONArray("characters")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "").trim()
                if (name.isEmpty()) continue
                members[name] = CastBook.Member(
                    o.optString("pronoun", "").trim(),
                    o.optString("register", "").trim(),
                    o.optString("note", "").trim(),
                )
            }
        }
        if (members.isNotEmpty()) cast?.learn(members)
        items.forEach { if (it.kind != ItemKind.SFX) StoryContext.remember(it.en, it.who) }
    }

    /**
     * One page's request, built once and sent as often as the race needs:
     * the encoded image, the series memory and the story as they stood
     * when the page was captured, and the page's own size for mapping the
     * model's boxes back.
     */
    private inner class PageRequest(val width: Int, val height: Int, val jpeg: String, lang: SourceLang) {

        private val stable = JSONObject()
            .put("glossary", JSONObject(glossary?.snapshot() ?: emptyMap<String, String>()))
            .put("characters", JSONObject(cast?.describeAll() ?: emptyMap<String, String>()))
            .toString()

        private val pageText = JSONObject()
            .put("expected_source_language", languageHint(lang))
            .put("story_so_far", JSONArray(StoryContext.snapshot()))
            .toString()

        fun body(model: String): JSONObject {
            val effort = if (settings.aiReasoning == AiReasoning.THOROUGH) "high" else "low"
            val config = JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", JSONObject(RESPONSE_SCHEMA))
                .put("maxOutputTokens", LlmHttp.outputCap(anthropic = false, vision = true, effort = effort))
            GeminiApi.thinkingConfig(model, settings.aiReasoning)?.let { config.put("thinkingConfig", it) }
            if (GeminiApi.takesTemperature(model)) config.put("temperature", 0)
            // Stable first, page last, so Gemini's implicit prefix cache
            // covers the prompt and the series memory from page to page.
            val parts = JSONArray()
                .put(JSONObject().put("text", stable))
                .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", jpeg)))
                .put(JSONObject().put("text", pageText))
            return JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", config)
        }

        fun item(o: JSONObject): PageItem? = toItem(o, width, height)
    }

    /** A read in flight: the items so far, and how it ended once it has. */
    private class Read(private val started: Long, private val encodeMs: Long) : PendingRead {

        private class State(
            val items: List<PageItem> = emptyList(),
            val finished: Boolean = false,
            val error: Throwable? = null,
            /** Kept with the ending itself, so no collector sees one without the other. */
            val endMs: Long? = null,
        )

        private val state = MutableStateFlow(State())

        @Volatile
        var job: Job? = null

        @Volatile
        var requests = 0

        @Volatile
        var model = ""

        /**
         * Why the reply ended early — the model's finish reason, or its
         * JSON breaking off — or null when it ended whole. Set before the
         * read finishes, so a collector sees it once [collect] returns.
         */
        @Volatile
        var cutBy: String? = null

        override val cutOff: Boolean get() = cutBy != null

        @Volatile
        private var firstMs: Long? = null

        override val firstItemMs: Long? get() = firstMs

        override val doneMs: Long? get() = state.value.endMs

        val items: List<PageItem> get() = state.value.items

        val hasItems: Boolean get() = state.value.items.isNotEmpty()

        override val isActive: Boolean get() = !state.value.finished

        override val summary: String
            get() = buildString {
                append("enc ").append(encodeMs).append(" ms")
                firstItemMs?.let { append(" · 1st ").append(it).append(" ms") }
                doneMs?.let { append(" · done ").append(it).append(" ms") }
                append(" · ").append(requests).append(if (requests == 1) " request" else " requests")
                if (model.isNotEmpty()) append(" · ").append(model)
                cutBy?.let { append(" · cut off: ").append(it) }
            }

        /** Adds [item] unless it repeats one already read. */
        fun emit(item: PageItem) {
            var added = false
            state.update { s ->
                added = !s.finished && s.items.none { same(it, item) }
                if (added) State(s.items + item) else s
            }
            if (added && firstMs == null) firstMs = elapsedMs(started)
        }

        fun finish(error: Throwable?) {
            val at = elapsedMs(started)
            state.update { s -> if (!s.finished) State(s.items, finished = true, error = error, endMs = at) else s }
        }

        override suspend fun collect(onItem: (suspend (PageItem) -> Unit)?): List<PageItem> {
            var sent = 0
            while (true) {
                val s = state.first { it.items.size > sent || it.finished }
                while (sent < s.items.size) {
                    onItem?.invoke(s.items[sent])
                    sent++
                }
                if (s.finished) {
                    s.error?.let { throw it }
                    return s.items
                }
            }
        }

        override fun cancel() {
            job?.cancel()
            finish(CancellationException("page read cancelled"))
        }
    }

    companion object {

        /** Identical requests sent at once; the first to deliver an item wins. */
        const val RACE = 2

        /** How long a lone request may show nothing before a duplicate joins it. */
        const val HEDGE_MS = 2500L

        /**
         * How long the race's duplicates wait for the first request to report
         * its page uploaded before they go anyway.
         */
        const val STAGGER_MS = 1000L

        /**
         * How much longer than [HEDGE_MS] a lone request's hedge waits when
         * its upload never reports done. Generous, since a duplicate that
         * joins mid-upload halves the bandwidth the first one still needs,
         * and a phone's uplink can take seconds over a page; but finite,
         * since an upload that never reports back may be stuck, and a fresh
         * request is then the cure.
         */
        const val UPLOAD_GRACE_MS = 8000L

        /** How long a rate limit stops the racing, process-wide. */
        private const val CALM_MS = 5 * 60_000L

        @Volatile
        private var calmUntil = 0L

        private fun calm(): Boolean = System.nanoTime() / 1_000_000 < calmUntil

        private fun calmDown() {
            calmUntil = System.nanoTime() / 1_000_000 + CALM_MS
        }

        /** Lets the race resume at once; for tests. */
        internal fun resetCalm() {
            calmUntil = 0L
        }

        /** Whether the configured provider reads pages AI-first. */
        fun supports(settings: AppSettings): Boolean = settings.provider == LlmProvider.GEMINI

        private fun geminiTransport(settings: AppSettings) = Transport { model, body, onSent, onFinish, onDelta ->
            LlmHttp.requireConfig(settings)
            GeminiApi.stream(settings.apiKey, model, body, onSent, onFinish, onDelta)
        }

        private fun elapsedMs(since: Long): Long = (System.nanoTime() - since) / 1_000_000

        private fun languageHint(lang: SourceLang): String = when (lang) {
            SourceLang.KO -> "Korean"
            SourceLang.JA -> "Japanese"
            SourceLang.ZH -> "Chinese"
            SourceLang.AUTO -> "Japanese, Korean or Chinese"
        }

        /** A failure a second, identical request could get past: the network, or Google overloaded. */
        private fun transient(e: Throwable): Boolean =
            e is IOException || (e is GeminiHttpException && e !is GeminiRateLimited && e.code >= 500)

        /** The failure that says most about why every request failed. */
        private fun worst(failures: List<Throwable>): Throwable =
            failures.firstOrNull { it is GeminiModelMissing }
                ?: failures.firstOrNull { it is GeminiBlocked }
                ?: failures.firstOrNull { it !is GeminiRateLimited }
                ?: failures.first()

        private fun same(a: PageItem, b: PageItem): Boolean {
            if (a.src != b.src || a.en != b.en) return false
            if (a.box == b.box) return true
            val inter = Rect()
            if (!inter.setIntersect(a.box, b.box)) return false
            val i = inter.width().toLong() * inter.height()
            val u = a.box.width().toLong() * a.box.height() + b.box.width().toLong() * b.box.height() - i
            return u > 0 && i * 10 >= u * 8
        }

        /**
         * JPEG of the page, long side at most 1280 px (1024 with data
         * saver) and never enlarged. Base64 once, since the race and a
         * model fallback send the same bytes again.
         *
         * Gemini spends a fixed budget of tokens on an image whatever its
         * size, so pixels beyond what that budget resolves buy nothing and
         * cost upload time — on a phone's uplink the one delay that grows
         * with the file. Measured on dense pages of small hand lettering,
         * 1280 px reads every item a 1600 px upload does, at 40% fewer bytes.
         */
        internal fun encode(bitmap: Bitmap, dataSaver: Boolean): String {
            val maxDim = if (dataSaver) 1024 else 1280
            val quality = if (dataSaver) 62 else 72
            val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            val bytes = ByteArrayOutputStream().use { bos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                bos.toByteArray()
            }
            if (scaled !== bitmap) scaled.recycle()
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        /**
         * One reply entry as a page item in page pixels, or null when it is
         * nothing paintable. The box is the model's own `box_2d`, normalised
         * to 0-1000 of the image it saw — which, being the whole page
         * scaled, maps straight onto the original page size.
         */
        internal fun toItem(o: JSONObject, width: Int, height: Int): PageItem? {
            val en = o.optString("en", "").trim()
            if (en.isEmpty()) return null
            val b = o.optJSONArray("box_2d") ?: return null
            if (b.length() < 4) return null
            val v = DoubleArray(4) { b.optDouble(it) }
            if (v.any { it.isNaN() }) return null
            val top = minOf(v[0], v[2]).coerceIn(0.0, 1000.0)
            val bottom = maxOf(v[0], v[2]).coerceIn(0.0, 1000.0)
            val left = minOf(v[1], v[3]).coerceIn(0.0, 1000.0)
            val right = maxOf(v[1], v[3]).coerceIn(0.0, 1000.0)
            val box = Rect(
                floor(left * width / 1000).toInt(),
                floor(top * height / 1000).toInt(),
                ceil(right * width / 1000).toInt().coerceAtMost(width),
                ceil(bottom * height / 1000).toInt().coerceAtMost(height),
            )
            if (box.width() <= 0 || box.height() <= 0) return null
            val kind = ItemKind.parse(o.optString("kind", ""))
            val src = o.optString("src", "").trim()
            return PageItem(
                box = box,
                kind = kind,
                src = src,
                en = englishOnly(en),
                who = o.optString("who", "").trim().take(24),
                vertical = isVertical(src, box.width(), box.height()),
                loud = (kind == ItemKind.SPEECH || kind == ItemKind.THOUGHT) && isLoud(en),
            )
        }

        /**
         * Whether [src] is set in vertical columns, judged from the shape of
         * its box against the shape of the text. The model writes one line
         * per column or row, so a text of n lines, the longest m glyphs, is
         * about m by n glyphs whichever way it runs: tall when its lines are
         * long and it is vertical, wide when its lines are long and it is
         * horizontal. A balloon of many short columns is wide *because* it
         * is vertical, which a bare taller-than-wide test gets backwards.
         * Only CJK lettering runs vertically; Hangul almost never does, so
         * it needs the shape to say so clearly.
         */
        internal fun isVertical(src: String, w: Int, h: Int): Boolean {
            if (w <= 0 || h <= 0 || Script.cjkCount(src) == 0) return false
            val lines = src.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return false
            val longest = lines.maxOf { it.codePointCount(0, it.length) }
            // Glyphs sit about 1.2 glyphs apart across lines or columns.
            val textShape = ln(longest / (lines.size * 1.2))
            val boxShape = ln(h.toDouble() / w)
            val hangul = Script.hangulCount(src) * 2 > Script.cjkCount(src)
            if (abs(textShape) < 0.15) {
                // A square block of text says nothing by its shape; a single
                // tall glyph stack still does.
                return !hangul && boxShape > 0.4
            }
            val margin = if (hangul) 0.7 else 0.0
            return if (textShape > 0) boxShape > margin else boxShape < -margin
        }

        /**
         * [en] without the kana or hangul the model sometimes carries over
         * from the source — a trailing っ or ぁ copied from a stammer, which
         * would be typeset as a stray glyph in the English. Only when the
         * line really is English: a line with no Latin letters is left as
         * the model wrote it.
         */
        internal fun englishOnly(en: String): String {
            if (en.none { it in 'A'..'Z' || it in 'a'..'z' }) return en
            val kept = en.filter { !(Script.isKana(it) || Script.isHangul(it)) }
            return kept.replace(Regex("[ \t]{2,}"), " ").trim().ifEmpty { en }
        }

        /** Shouted: ends in "!!", or is set mostly in capitals. */
        private fun isLoud(en: String): Boolean {
            if (en.trimEnd().endsWith("!!")) return true
            val letters = en.filter { it.isLetter() }
            return letters.length >= 4 && letters.count { it.isUpperCase() } * 10 >= letters.length * 7
        }

        private val SYSTEM_PROMPT = """
You are an elite manga/manhwa/manhua scanlation translator. You see one raw comic screenshot; your English is typeset straight over its lettering, so it must be right the first time.
The request carries the series memory ("glossary", "characters"), then the page image, then "expected_source_language" and "story_so_far".

FIND THE LETTERING
Find EVERY piece of non-English lettering yourself: balloons, captions, text on the art, signs, sound effects.
- ONE item per balloon or caption box: all its lines or columns are one item. Separate balloons and separate pieces of lettering (a small mutter beside a big SFX) are separate items, even when they touch.
- "box_2d": [ymin, xmin, ymax, xmax] normalised 0-1000 to the full image, tight around the lettering.
- "src": the original lettering, one line per printed line or column.
- Ignore phone and browser UI, watermarks, page numbers and credits. Omit lettering already in English.
- ORDER: all speech, thought and narration first, in reading order, then all sfx and art_text; never interleave. Webtoon: top to bottom. Manga: tiers top to bottom, panels in a tier right to left, columns right to left.
- "expected_source_language" is a guess: read whatever language the lettering really is.
- "kind": thought only for cloud balloons or inner monologue (a spiky burst is a shout); narration only for caption boxes; other lettering on the art (side comments, signs, unboxed captions) is art_text.

WHO IS SPEAKING — decide before translating
"who": the speaker in at most two words — the English name from "characters"/"glossary", or a stable descriptor ("tall boy"); "" for narration and sound effects. Decide from balloon tails, who is drawn speaking, eye lines, and turn-taking continuing "story_so_far". Laughs and pants drawn around a character are theirs, not a "crowd", unless a crowd is drawn.
Supply omitted subjects from speaker, listener and story; if truly unresolvable, write an idiomatic subjectless line ("So annoying..."), never a broken fragment or an invented pronoun.
Keep every pronoun already recorded in "characters"; never re-decide a character's gender.

TRANSLATE
- Faithful and complete: translate exactly what is written, with the original tone and emotion. Never omit, summarise or soften; match crudeness (ムカつく = pisses me off).
- Each character keeps their voice and register from "characters"; rough speech stays rough (gonna, spit it out), never textbook English.
- Tight like typeset dialogue: contractions, no padding, no translator notes. Tight means fewer words, never less content: paired phrases and idioms keep every image. Never add a ? or ?! the source lacks.
- "en" is one line (never copy the source's line breaks) and keeps ♡, ♪ and similar symbols. Never write asterisks, brackets or stage directions (*thump*, (sighs)), kana, hangul, hanzi, or romanized cries ("Uooh", "Wah").
- Keep honorifics (-san, -kun, -chan, -sama, senpai, oppa, hyung, noona, unnie, -nim/-ssi on names, gege/jiejie). Translate titles (师父 Master, 殿下 Your Highness). Drop the vocative -아/-야.
- Use "glossary" EXACTLY for names and terms. New names: family name first; named techniques and realms: Title Case English.
- A sentence split across balloons: translate it whole, then divide the English across them in order.
- Sound effects are selective, as in expert scanlations: give an sfx item only when it tells the reader something the art does not already show — a sound or state that carries meaning or mood (a knock, a phone buzzing, footsteps, SILENCE, a STARE, a nervous BA-DUMP). Omit decorative ones: impact, slash and whoosh lettering that is part of an action drawing, rumbles and background noise, and any sfx repeating one already given nearby.
- Sound effects you do give: punchy English in CAPS chosen by meaning, states and motions included: ドキ/두근 BA-DUMP (one beat per repeat), ド/ドン DOOM, ドドン BA-DOOM, ゴゴゴ RUMBLE, ズバッ SHRAK, ガタッ CLATTER, シーン SILENCE, ジー STARE, コク/끄덕 NOD. A drawn-out vowel stretches it (ズバアッ SHRAAAK). The same sound gets the same English every time.
- Voiced sounds — breaths, moans, laughs, sobs, cries (はぁ, んっ, えへへ, クスクス, 하아, ㅋㅋ, 哼) — are "speech" (in a balloon) or "art_text", never "sfx". Letter the sound, never a verb, in normal case, keeping length, stammer and ♡: "Hah... hah♡", "Nngh♡", "Ehehe♡", "Heh heh". A scream in a burst balloon is CAPS ("RAAAH!").
- A stammer repeats the first sound of the first English word ("Y-Your order..."); a drawn-out final vowel stretches the last word ("waaait"), never a tacked-on "aaah".

AFTER THE ITEMS
"new_terms": only names or recurring terms (techniques, realms, sects too) first established on this page and missing from the glossary. "characters": only characters on this page whose pronoun or register is not recorded yet. Keep both brief; leave them empty when nothing is new.
""".trim()

        /**
         * The reply's shape. Only what typesetting needs, because every
         * output token is waited for; items come first so they stream
         * first, and the series memory follows once the page is done.
         */
        private val RESPONSE_SCHEMA = """
{"type":"OBJECT","properties":{
"items":{"type":"ARRAY","items":{"type":"OBJECT","properties":{
"box_2d":{"type":"ARRAY","items":{"type":"INTEGER"}},
"kind":{"type":"STRING","enum":["speech","thought","narration","sfx","art_text"]},
"who":{"type":"STRING"},"src":{"type":"STRING"},"en":{"type":"STRING"}},
"required":["box_2d","kind","who","src","en"],"propertyOrdering":["box_2d","kind","who","src","en"]}},
"new_terms":{"type":"ARRAY","items":{"type":"OBJECT","properties":{"src":{"type":"STRING"},"en":{"type":"STRING"}},
"required":["src","en"],"propertyOrdering":["src","en"]}},
"characters":{"type":"ARRAY","items":{"type":"OBJECT","properties":{
"name":{"type":"STRING"},"pronoun":{"type":"STRING"},"register":{"type":"STRING"},"note":{"type":"STRING"}},
"required":["name","pronoun"],"propertyOrdering":["name","pronoun","register","note"]}}},
"required":["items"],"propertyOrdering":["items","new_terms","characters"]}
""".trim()
    }
}

/**
 * A page read in flight. Items arrive while nobody is collecting yet — a
 * read started while the reader might still scroll — and are replayed to
 * whoever collects later.
 */
interface PendingRead {
    /** True until the read has finished, failed or been cancelled. */
    val isActive: Boolean

    /** Milliseconds from start to the first item, once one has landed. */
    val firstItemMs: Long? get() = null

    /** Milliseconds from start to the end of the read, once it has ended. */
    val doneMs: Long? get() = null

    /** One line of timings and requests for the diagnostics status line. */
    val summary: String get() = ""

    /**
     * True once a finished read is known to have ended early: the reply
     * stopped before the model was done (an output filter tripping part-way,
     * the token cap) or its JSON broke off. Its items are real but not the
     * whole page, so they are shown but never kept as the page's answer.
     */
    val cutOff: Boolean get() = false

    /**
     * Hands every item already received to [onItem], then each new one as
     * it lands, and returns the complete list once the reply has finished.
     * Throws when the read failed — a [CancellationException] when it was
     * cancelled. May be called more than once, by more than one collector.
     */
    suspend fun collect(onItem: (suspend (PageItem) -> Unit)? = null): List<PageItem>

    fun cancel()
}
