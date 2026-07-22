package app.mangalens.translate

import app.mangalens.ocr.BubbleKind
import app.mangalens.settings.AppSettings
import app.mangalens.settings.SourceLang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The "master translator" text engine: sends the whole page of bubbles in one
 * request together with a rolling window of recent lines and the persistent
 * glossary, so pronouns, tone, honorifics and names stay consistent across
 * pages and sessions. Understands bubble kinds (dialogue vs SFX) and returns
 * any new names/terms it established for the glossary.
 */
class LlmEngine(
    private val settings: AppSettings,
    private val glossary: GlossaryStore? = null,
) : TranslationEngine {

    override val label: String get() = LlmHttp.providerLabel(settings)

    override val cacheNamespace: String get() = label + ":" + settings.effectiveModel()

    override suspend fun translate(items: List<String>, lang: SourceLang): List<String> =
        translateWithKinds(items, List(items.size) { BubbleKind.DIALOGUE }, lang)

    suspend fun translateWithKinds(
        items: List<String>,
        kinds: List<BubbleKind>,
        lang: SourceLang,
    ): List<String> = withContext(Dispatchers.IO) {
        LlmHttp.requireConfig(settings)

        val bubbles = JSONArray()
        items.forEachIndexed { i, t ->
            bubbles.put(
                JSONObject()
                    .put("id", i)
                    .put("text", t)
                    .put("kind", if (kinds.getOrNull(i) == BubbleKind.SFX) "sfx" else "dialogue")
            )
        }
        val user = JSONObject()
            .put("source_language", lang.name.lowercase())
            .put("glossary", JSONObject(glossary?.snapshot() ?: emptyMap<String, String>()))
            .put("recent_lines_for_context", JSONArray(StoryContext.snapshot()))
            .put("bubbles", bubbles)
            .toString()

        val raw = LlmHttp.complete(
            settings,
            SYSTEM_PROMPT,
            JSONArray().put(JSONObject().put("type", "text").put("text", user)),
            user,
            maxTokens = 2600,
        )

        val reply = LlmHttp.extractJsonObject(raw)
        val out = MutableList(items.size) { "" }
        val arr = reply.optJSONArray("bubbles") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("id", -1)
            if (id !in items.indices) continue
            if (o.optString("kind") == "skip") continue
            val en = o.optString("en", "")
            if (en.isNotBlank()) out[id] = en
        }
        reply.optJSONObject("new_terms")?.let { terms ->
            val learned = HashMap<String, String>()
            for (k in terms.keys()) learned[k] = terms.optString(k, "")
            glossary?.learn(learned)
        }
        items.forEachIndexed { i, src -> if (out[i].isNotBlank()) StoryContext.remember(src, out[i]) }
        out
    }

    companion object {
        internal val SYSTEM_PROMPT = """
You are an elite manga/manhwa/manhua localization translator producing text for typeset speech bubbles. You receive one comic page as JSON: bubbles in reading order, a glossary of established names/terms, and recent earlier lines for story context.

Translation rules:
- Write the way real people speak. Contractions, slang where the source is slangy, formal where it is formal. Match each line's emotion: shouting, whispering, teasing, panic.
- Use the glossary EXACTLY for any name or term it contains. Romanize new names sensibly and keep them consistent within the page.
- Keep honorifics that carry nuance (oppa, hyung, noona, unnie, -nim, -ssi, senpai, -san, -sama, -chan, shifu, gege, jiejie).
- The OCR text may contain recognition errors, scrambled column order, or stray characters. Reconstruct the intended sentence from context and the story so far — never translate garbage literally, never romanize the source.
- Bubbles with "kind":"sfx" are sound effects: render as punchy comic onomatopoeia in CAPS (WHAM, BA-DUMP, KRAK). If an sfx fragment is meaningless, skip it.
- Keep lines as tight as real typeset dialogue. No translator notes, no explanations.
- If a bubble is pure noise (UI scraps, page numbers, unreadable fragments), skip it rather than guessing wildly.

Respond with ONLY this JSON object, no markdown fences:
{"bubbles":[{"id":0,"en":"<English>","kind":"dialogue|sfx|skip"}...],"new_terms":{"<source name/term>":"<English>"}}
- One entry per input bubble, same ids. Use "kind":"skip" (en may be empty) to drop a bubble.
- "new_terms": ONLY newly established proper nouns / recurring terms not already in the glossary. Empty object if none.
""".trim()
    }
}
