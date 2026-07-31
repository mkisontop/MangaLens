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
 * request together with the story so far, the persistent glossary and the cast
 * of characters, so pronouns, tone, honorifics and names stay consistent
 * across pages and sessions. Understands bubble kinds (dialogue vs SFX) and
 * sentences split across balloons, and returns any new names, terms and
 * character details it established.
 *
 * Without the page image this engine cannot see who is drawn where, so speaker
 * attribution rests on turn-taking and the established cast rather than on
 * balloon tails — which is exactly why it asks for the speaker explicitly and
 * banks what it learns. [VisionLlmEngine] is the stronger path when the
 * lettering itself is hard.
 */
class LlmEngine(
    private val settings: AppSettings,
    private val glossary: GlossaryStore? = null,
    private val cast: CastBook? = null,
) : TranslationEngine {

    override val label: String get() = LlmHttp.providerLabel(settings)

    override val cacheNamespace: String get() = label + ":" + settings.effectiveModel()

    override suspend fun translate(items: List<String>, lang: SourceLang): List<String> =
        translateWithKinds(
            items,
            List(items.size) { BubbleKind.DIALOGUE },
            List(items.size) { -1 },
            List(items.size) { 0 },
            lang,
        )

    suspend fun translateWithKinds(
        items: List<String>,
        kinds: List<BubbleKind>,
        runs: List<Int>,
        parts: List<Int>,
        lang: SourceLang,
    ): List<String> = withContext(Dispatchers.IO) {
        LlmHttp.requireConfig(settings)

        val bubbles = JSONArray()
        items.forEachIndexed { i, t ->
            val o = JSONObject()
                .put("id", i)
                .put("text", t)
                .put("kind", if (kinds.getOrNull(i) == BubbleKind.SFX) "sfx" else "dialogue")
            val run = runs.getOrNull(i) ?: -1
            if (run >= 0) o.put("run", run).put("part", parts.getOrNull(i) ?: 0)
            bubbles.put(o)
        }
        val user = JSONObject()
            .put("source_language", lang.name.lowercase())
            .put("glossary", JSONObject(glossary?.snapshot() ?: emptyMap<String, String>()))
            .put("characters", JSONObject(cast?.describeAll() ?: emptyMap<String, String>()))
            .put("story_so_far", JSONArray(StoryContext.snapshot()))
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
        val who = MutableList(items.size) { "" }
        val arr = reply.optJSONArray("bubbles") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("id", -1)
            if (id !in items.indices) continue
            if (o.optString("kind") == "skip") continue
            val en = o.optString("en", "")
            if (en.isNotBlank()) {
                out[id] = en
                who[id] = o.optString("who", "").trim().take(24)
            }
        }
        reply.optJSONObject("new_terms")?.let { terms ->
            val learned = HashMap<String, String>()
            for (k in terms.keys()) learned[k] = terms.optString(k, "")
            glossary?.learn(learned)
        }
        cast?.learn(CastBook.parse(reply.optJSONObject("characters")))
        out.forEachIndexed { i, en ->
            if (en.isNotBlank() && kinds.getOrNull(i) != BubbleKind.SFX) {
                StoryContext.remember(en, who[i])
            }
        }
        out
    }

    companion object {
        internal val SYSTEM_PROMPT = """
You are an elite manga/manhwa/manhua localization translator producing text for typeset speech bubbles. You receive one comic page as JSON: bubbles in reading order, a glossary of established names/terms, the cast of characters met so far, and the story up to this page.
"source_language" is a guess from settings. Aggregator sites often serve raws already translated once (Spanish is common) — translate whatever language the text actually is into the same natural English. If a bubble is already English, answer it with "kind":"skip".

WHO IS SPEAKING — decide this before you translate
Japanese, Korean and Chinese omit the subject constantly, so a line's meaning depends on who is saying it and to whom. Work out the speaker of each dialogue bubble from turn-taking against "story_so_far", forms of address, and each character's register in "characters". Return it as "who".
Resolve the omitted subject from that speaker and commit to it. If it is genuinely unresolvable, use a subjectless English phrasing ("Not going back." / "Can't do it.") rather than inventing a pronoun.
Honour "characters" exactly: once a character has a pronoun there, keep it. Never re-decide a character's gender between pages — a consistent pronoun matters more than a freshly-guessed one.

SPLIT SENTENCES
Bubbles sharing a "run" value are ONE sentence broken across balloons, in "part" order. Translate the whole sentence first, then split the English across the parts so each balloon carries its share and they read continuously. Never translate a part as though it were a complete sentence, and never repeat the whole sentence in every part.

VOICE
- Write the way real people speak. Contractions, slang where the source is slangy, formal where it is formal. Match each line's emotion: shouting, whispering, teasing, panic.
- Match each speaker's register from "characters" so a character sounds like themselves across pages.
- Use the glossary EXACTLY for any name or term it contains. Romanize new names sensibly and keep them consistent within the page.
- Keep honorifics that carry nuance (oppa, hyung, noona, unnie, -nim, -ssi, senpai, -san, -sama, -chan, shifu, gege, jiejie).
- Keep lines as tight as real typeset dialogue. No translator notes, no explanations.

DAMAGED INPUT
This text came from on-device OCR and may contain recognition errors, scrambled column order, or stray characters. Reconstruct the intended sentence from context and the story so far — never translate garbage literally, never romanize the source. If a bubble is pure noise (UI scraps, page numbers, unreadable fragments), skip it rather than guessing wildly.

SOUND EFFECTS
Bubbles with "kind":"sfx" are sound effects: render as punchy comic onomatopoeia in CAPS (WHAM, BA-DUMP, KRAK). Japanese SFX cover states as well as sounds — silence (シーン), staring (ジー), nervousness (ドキドキ) — so translate the effect, not a literal noise. If an sfx fragment is meaningless, skip it.

Respond with ONLY this JSON object, no markdown fences:
{"bubbles":[{"id":0,"who":"<speaker>","en":"<English>","kind":"dialogue|sfx|skip"}...],"new_terms":{"<source name/term>":"<English>"},"characters":{"<English name>":{"pronoun":"he|she|they","register":"<how they speak>","note":"<role or relationship>"}}}
- One entry per input bubble, same ids. Use "kind":"skip" (en may be empty) to drop a bubble.
- "new_terms": ONLY newly established proper nouns / recurring terms not already in the glossary. Empty object if none.
- "characters": ONLY characters on this page whose pronoun or register is not already recorded, or whom you can now describe more precisely. Empty object if none.
""".trim()
    }
}
