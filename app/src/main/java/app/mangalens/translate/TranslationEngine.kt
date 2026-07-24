package app.mangalens.translate

import app.mangalens.ocr.BubbleKind
import app.mangalens.settings.AppSettings
import app.mangalens.settings.EngineKind
import app.mangalens.settings.SourceLang

interface TranslationEngine {
    /** Short name shown in the status pill, e.g. "Claude" or "Google". */
    val label: String

    /** Cache namespace; includes the model for LLM engines. */
    val cacheNamespace: String get() = label

    /** Translates every item; result list must match [items] in size and order. */
    suspend fun translate(items: List<String>, lang: SourceLang): List<String>
}

/**
 * Runs translations through the configured engine with caching, falling back to
 * the free Google engine when a fancier engine fails (bad key, offline, etc).
 * A blank result means "render nothing for this bubble" and is never cached, so
 * transient misses retry on the next pass.
 */
class TranslationService(
    private val cache: TranslationCache,
    private val glossary: GlossaryStore? = null,
    private val cast: CastBook? = null,
) {

    private val google = GoogleFreeEngine()
    private val mlkit = MlKitEngine()

    data class Outcome(val texts: List<String>, val engineLabel: String, val note: String? = null)

    suspend fun translate(
        items: List<String>,
        lang: SourceLang,
        settings: AppSettings,
        kinds: List<BubbleKind>? = null,
        runs: List<Int>? = null,
        parts: List<Int>? = null,
        forceGoogle: Boolean = false,
    ): Outcome {
        val chain: List<TranslationEngine> = when {
            forceGoogle -> listOf(google)
            settings.engine == EngineKind.LLM -> listOf(LlmEngine(settings, glossary, cast), google)
            settings.engine == EngineKind.GOOGLE -> listOf(google)
            else -> listOf(mlkit, google)
        }
        var lastError: Exception? = null
        for (engine in chain) {
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
            try {
                val fresh = if (engine is LlmEngine && kinds != null) {
                    engine.translateWithKinds(
                        missing.map { it.second },
                        missing.map { kinds.getOrNull(it.first) ?: BubbleKind.DIALOGUE },
                        missing.map { runs?.getOrNull(it.first) ?: -1 },
                        missing.map { parts?.getOrNull(it.first) ?: 0 },
                        lang,
                    )
                } else {
                    engine.translate(missing.map { it.second }, lang)
                }
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
                val note = if (engine !== chain.first()) "fallback" else null
                return Outcome(resolved.map { it ?: "" }, engine.label, note)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = e
            }
        }
        throw RuntimeException(lastError?.message ?: "translation failed", lastError)
    }

    /** True when every item is already cached under [ns] — no network needed. */
    fun fullyCached(ns: String, lang: SourceLang, items: List<String>): Boolean =
        items.all { cache.get(TranslationCache.key(ns, lang.name, it)) != null }
}
