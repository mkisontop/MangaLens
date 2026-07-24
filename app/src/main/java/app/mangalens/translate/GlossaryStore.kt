package app.mangalens.translate

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Persistent glossary of names, honorific choices and recurring terms the AI
 * translator has already established (강태오 → "Kang Tae-oh"). Feeding this
 * back on every request is what keeps names consistent across pages, sessions
 * and app restarts — the single biggest "official release" tell.
 */
class GlossaryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("mangalens_glossary", Context.MODE_PRIVATE)

    private val terms = LinkedHashMap<String, String>(64, 0.75f, true)
    private var loaded = false

    /**
     * Which work these terms belong to. One shared glossary across everything
     * read is actively harmful: 先生 is "Doctor" in one series and "Teacher" in
     * the next, and a glossary is obeyed exactly, so whichever was learned
     * first wins forever in the wrong story.
     */
    private var scope = DEFAULT_SCOPE

    /** Switches to another work's glossary, saving the current one first. */
    @Synchronized
    fun setScope(id: String) {
        if (id == scope) return
        if (loaded) persist()
        scope = id
        terms.clear()
        loaded = false
    }

    /**
     * Folds [from]'s terms into [to] without overwriting what [to] already
     * established — used when a session that began unidentified turns out to
     * be a work already known.
     */
    /** Forgets a work's glossary entirely, when it is evicted. */
    @Synchronized
    fun dropScope(id: String) {
        prefs.edit().remove(keyFor(id)).apply()
        if (scope == id) {
            terms.clear()
            loaded = true
        }
    }

    @Synchronized
    fun mergeScope(from: String, to: String) {
        if (from == to) return
        val source = read(from)
        if (source.isEmpty()) return
        val target = read(to)
        for ((k, v) in source) target.putIfAbsent(k, v)
        write(to, target)
        prefs.edit().remove(keyFor(from)).apply()
        if (scope == from) {
            // Already folded into [to]; dropping it here stops the next
            // scope switch from writing it straight back out again.
            terms.clear()
            loaded = true
        }
        if (scope == to) {
            terms.clear()
            loaded = false
        }
    }

    @Synchronized
    fun snapshot(): Map<String, String> {
        ensureLoaded()
        return LinkedHashMap(terms)
    }

    @Synchronized
    fun learn(newTerms: Map<String, String>) {
        if (newTerms.isEmpty()) return
        ensureLoaded()
        for ((src, en) in newTerms) {
            val s = src.trim()
            val e = en.trim()
            if (s.isEmpty() || e.isEmpty() || s.length > 24 || e.length > 40) continue
            terms[s] = e
        }
        while (terms.size > MAX_TERMS) {
            val eldest = terms.keys.firstOrNull() ?: break
            terms.remove(eldest)
        }
        persist()
    }

    @Synchronized
    fun clear() {
        ensureLoaded()
        terms.clear()
        persist()
    }

    @Synchronized
    fun size(): Int {
        ensureLoaded()
        return terms.size
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        terms.putAll(read(scope))
    }

    private fun persist() {
        write(scope, LinkedHashMap(terms))
    }

    private fun keyFor(id: String) = if (id == DEFAULT_SCOPE) KEY else "$KEY:$id"

    private fun read(id: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        runCatching {
            val raw = prefs.getString(keyFor(id), null) ?: return out
            val obj = JSONObject(raw)
            for (k in obj.keys()) {
                val v = obj.optString(k, "")
                if (v.isNotBlank()) out[k] = v
            }
        }
        return out
    }

    private fun write(id: String, map: Map<String, String>) {
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        prefs.edit().putString(keyFor(id), obj.toString()).apply()
    }

    private companion object {
        const val KEY = "terms"
        const val MAX_TERMS = 140
        const val DEFAULT_SCOPE = "default"
    }
}
