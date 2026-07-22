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
        runCatching {
            val raw = prefs.getString(KEY, null) ?: return
            val obj = JSONObject(raw)
            for (k in obj.keys()) terms[k] = obj.optString(k, "")
            terms.entries.removeAll { it.value.isBlank() }
        }
    }

    private fun persist() {
        val obj = JSONObject()
        for ((k, v) in terms) obj.put(k, v)
        prefs.edit().putString(KEY, obj.toString()).apply()
    }

    private companion object {
        const val KEY = "terms"
        const val MAX_TERMS = 140
    }
}
