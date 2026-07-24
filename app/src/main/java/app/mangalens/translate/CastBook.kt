package app.mangalens.translate

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Who is speaking, and how they talk.
 *
 * This is the fix for the failure mode that hurts hard panels most. Japanese
 * and Korean routinely drop the subject, so "行くよ" is *someone* going
 * somewhere and nothing in the sentence says who — the translator has to
 * supply "I'm going" or "he's going" from context, and a wrong guess flips the
 * meaning of the panel. Gendered pronouns are invented outright: nothing in
 * the source marks he/she, yet English demands one, and a character mis-sexed
 * on page 3 stays mis-sexed for the rest of the chapter.
 *
 * Vision models are strikingly bad at this on their own — they extract the
 * dialogue text far more reliably than they attribute it to a character — so
 * attribution has to be asked for explicitly and then *remembered*, rather
 * than re-derived per page from whatever happens to be visible.
 *
 * [GlossaryStore] keeps a name spelled consistently; this keeps the person
 * behind the name consistent: their pronoun, how formally they speak, and the
 * verbal habits that make dialogue sound like one individual across a chapter.
 */
class CastBook(context: Context) {

    /**
     * @param pronoun "he", "she", "they" — the English pronoun this character
     *   takes, decided once and then held.
     * @param register how they speak: "blunt, casual, masculine" /
     *   "formal, deferential" — steers word choice and contraction level.
     * @param note anything that changes a line's reading: role, relationships,
     *   a verbal tic, the honorific they use for someone else.
     */
    data class Member(val pronoun: String, val register: String, val note: String) {
        fun describe(): String = buildString {
            append(pronoun)
            if (register.isNotBlank()) append("; ").append(register)
            if (note.isNotBlank()) append("; ").append(note)
        }
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("mangalens_cast", Context.MODE_PRIVATE)

    /** Access-ordered so the characters currently on screen survive eviction. */
    private val cast = LinkedHashMap<String, Member>(32, 0.75f, true)
    private var loaded = false

    @Synchronized
    fun snapshot(): Map<String, Member> {
        ensureLoaded()
        return LinkedHashMap(cast)
    }

    /** Flat "name -> he; blunt, casual" lines, the form sent to the model. */
    @Synchronized
    fun describeAll(): Map<String, String> {
        ensureLoaded()
        return cast.entries.associate { (k, v) -> k to v.describe() }
    }

    /**
     * Records what the model established about the characters on this page.
     *
     * Existing entries are refined rather than replaced: once a character has a
     * pronoun it is kept unless the model explicitly supplies a different one,
     * because pronoun *stability* is the whole point — a character who is "he"
     * on one page and "she" on the next is worse than one consistently wrong.
     */
    @Synchronized
    fun learn(entries: Map<String, Member>) {
        if (entries.isEmpty()) return
        ensureLoaded()
        for ((rawName, incoming) in entries) {
            val name = rawName.trim()
            if (name.isEmpty() || name.length > 32) continue
            val existing = cast[name]
            val merged = if (existing == null) {
                Member(
                    pronoun = incoming.pronoun.trim().take(8),
                    register = incoming.register.trim().take(48),
                    note = incoming.note.trim().take(64),
                )
            } else {
                Member(
                    pronoun = incoming.pronoun.ifBlank { existing.pronoun }.trim().take(8),
                    register = incoming.register.ifBlank { existing.register }.trim().take(48),
                    note = incoming.note.ifBlank { existing.note }.trim().take(64),
                )
            }
            if (merged.pronoun.isBlank() && merged.register.isBlank()) continue
            cast[name] = merged
        }
        while (cast.size > MAX_CAST) {
            val eldest = cast.keys.firstOrNull() ?: break
            cast.remove(eldest)
        }
        persist()
    }

    @Synchronized
    fun clear() {
        ensureLoaded()
        cast.clear()
        persist()
    }

    @Synchronized
    fun size(): Int {
        ensureLoaded()
        return cast.size
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            val raw = prefs.getString(KEY, null) ?: return
            val obj = JSONObject(raw)
            for (name in obj.keys()) {
                val o = obj.optJSONObject(name) ?: continue
                cast[name] = Member(
                    o.optString("pronoun", ""),
                    o.optString("register", ""),
                    o.optString("note", ""),
                )
            }
        }
    }

    private fun persist() {
        val obj = JSONObject()
        for ((name, m) in cast) {
            obj.put(
                name,
                JSONObject()
                    .put("pronoun", m.pronoun)
                    .put("register", m.register)
                    .put("note", m.note),
            )
        }
        prefs.edit().putString(KEY, obj.toString()).apply()
    }

    companion object {
        private const val KEY = "cast"
        private const val MAX_CAST = 24

        /** Parses the `characters` object the translator returns. */
        fun parse(obj: JSONObject?): Map<String, Member> {
            if (obj == null) return emptyMap()
            val out = HashMap<String, Member>()
            for (name in obj.keys()) {
                val o = obj.optJSONObject(name)
                if (o != null) {
                    out[name] = Member(
                        o.optString("pronoun", ""),
                        o.optString("register", ""),
                        o.optString("note", ""),
                    )
                } else {
                    // Tolerate the terse "Name": "he; blunt, casual" form.
                    val parts = obj.optString(name, "").split(';', limit = 3)
                    if (parts.isEmpty() || parts[0].isBlank()) continue
                    out[name] = Member(
                        parts[0].trim(),
                        parts.getOrElse(1) { "" }.trim(),
                        parts.getOrElse(2) { "" }.trim(),
                    )
                }
            }
            return out
        }
    }
}
