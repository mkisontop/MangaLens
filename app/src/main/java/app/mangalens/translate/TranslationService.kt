package app.mangalens.translate

import app.mangalens.ocr.BubbleKind
import app.mangalens.settings.AppSettings
import app.mangalens.settings.SourceLang

/**
 * Runs text translations through the AI with caching.
 *
 * There is no machine translation behind the AI. A page that fell back to
 * one came back in words the reader had not asked for, and with no sign
 * that anything had gone wrong; so when the AI fails, the failure is what
 * comes back — the call throws, and the caller tells the reader why.
 * A blank result means "render nothing for this bubble" and is never
 * cached, so transient misses retry on the next pass.
 */
class TranslationService(
    private val cache: TranslationCache,
    private val glossary: GlossaryStore? = null,
    private val cast: CastBook? = null,
) {

    data class Outcome(val texts: List<String>, val engineLabel: String)

    /**
     * @param onProgress receives the texts resolved so far — cache hits and
     *   streamed answers, by input index — each time another answer lands,
     *   so the page can be painted as it is written.
     * @throws Exception whatever stopped the AI from answering: a missing
     *   or rejected key, the network, a rate limit. Nothing is translated
     *   any other way instead.
     */
    suspend fun translate(
        items: List<String>,
        lang: SourceLang,
        settings: AppSettings,
        kinds: List<BubbleKind>? = null,
        runs: List<Int>? = null,
        parts: List<Int>? = null,
        onProgress: (suspend (Map<Int, String>) -> Unit)? = null,
    ): Outcome {
        val engine = LlmEngine(settings, glossary, cast)
        val resolved = arrayOfNulls<String>(items.size)
        val missingIdx = LinkedHashSet<Int>()
        for ((i, t) in items.withIndex()) {
            val hit = cache.get(TranslationCache.key(engine.cacheNamespace, lang.name, t))
            if (hit != null) resolved[i] = hit else missingIdx.add(i)
        }
        // A sentence split across balloons only translates correctly as a
        // whole. If any part missed the cache, pull its siblings back in
        // so the engine sees the complete run rather than a stray clause.
        if (runs != null && missingIdx.isNotEmpty()) {
            val broken = missingIdx.mapNotNull { runs.getOrNull(it) }.filterTo(HashSet()) { it >= 0 }
            if (broken.isNotEmpty()) {
                for (i in items.indices) {
                    if ((runs.getOrNull(i) ?: -1) in broken) missingIdx.add(i)
                }
            }
        }
        if (missingIdx.isEmpty()) {
            return Outcome(resolved.map { it ?: "" }, engine.label)
        }
        val missing = missingIdx.sorted().map { it to items[it] }
        val partial = HashMap<Int, String>()
        for (i in items.indices) resolved[i]?.let { partial[i] = it }
        val fresh = engine.translateWithKinds(
            missing.map { it.second },
            missing.map { kinds?.getOrNull(it.first) ?: BubbleKind.DIALOGUE },
            missing.map { runs?.getOrNull(it.first) ?: -1 },
            missing.map { parts?.getOrNull(it.first) ?: 0 },
            lang,
            onEntry = onProgress?.let { emit ->
                { k, en ->
                    missing.getOrNull(k)?.let { (idx, _) ->
                        partial[idx] = en
                        emit(HashMap(partial))
                    }
                }
            },
        )
        for ((k, pair) in missing.withIndex()) {
            val (idx, src) = pair
            val translated = fresh.getOrNull(k).orEmpty()
            if (translated.isNotBlank()) {
                resolved[idx] = translated
                cache.put(TranslationCache.key(engine.cacheNamespace, lang.name, src), translated)
            } else if (resolved[idx] == null) {
                // A blank is "render nothing", and is never cached so
                // the next pass retries. Only a genuine cache miss may
                // be blanked: a run member pulled back in to rebuild
                // its sentence still has its cached text, and losing it
                // to a skipped answer would blank a bubble that was
                // rendering fine a moment ago.
                resolved[idx] = ""
            }
        }
        return Outcome(resolved.map { it ?: "" }, engine.label)
    }

    /** True when every item is already cached under [ns] — no network needed. */
    fun fullyCached(ns: String, lang: SourceLang, items: List<String>): Boolean =
        items.all { cache.get(TranslationCache.key(ns, lang.name, it)) != null }
}
