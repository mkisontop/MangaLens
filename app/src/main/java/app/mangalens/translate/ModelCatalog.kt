package app.mangalens.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Live model discovery, so the model picker never goes stale.
 *
 * Google ships new Gemini models far more often than this app ships updates,
 * and a hardcoded list would advertise last season's models forever. Instead
 * the picker asks the API itself what exists right now, filters the answer
 * down to the text-generation models a translator can actually use, and ranks
 * them so the first entry is always the sensible default: the newest Flash.
 */
object ModelCatalog {

    data class LiveModel(val id: String, val label: String)

    /** Families that can't translate a page: no reason to offer them. */
    private val EXCLUDE = listOf(
        "embedding", "tts", "image", "audio", "live", "veo",
        "imagen", "aqa", "robotics", "computer-use",
    )

    suspend fun gemini(apiKey: String): List<LiveModel> = withContext(Dispatchers.IO) {
        fetchGemini(apiKey)
    }

    private suspend fun fetchGemini(apiKey: String): List<LiveModel> {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
            // Header, not query parameter: keys don't belong in URLs or logs.
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        LlmHttp.await(LlmHttp.client.newCall(request)).use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw RuntimeException("Gemini HTTP " + resp.code + ": " + text.take(160))
            }
            return parseGemini(text)
        }
    }

    /**
     * Pulls the usable text models out of a ListModels response: must support
     * generateContent, must be a Gemini model, must not be a specialty family.
     */
    fun parseGemini(json: String): List<LiveModel> {
        val models = JSONObject(json).optJSONArray("models") ?: return emptyList()
        val out = ArrayList<LiveModel>()
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            val id = m.optString("name").removePrefix("models/")
            if (!id.startsWith("gemini")) continue
            if (EXCLUDE.any { id.contains(it) }) continue
            val methods = m.optJSONArray("supportedGenerationMethods")
            var generates = false
            if (methods != null) {
                for (j in 0 until methods.length()) {
                    if (methods.optString(j) == "generateContent") generates = true
                }
            }
            if (!generates) continue
            out.add(LiveModel(id, m.optString("displayName").ifBlank { id }))
        }
        return rank(out)
    }

    /**
     * Best-default-first: the `-latest` aliases (they always point at the
     * newest release, which is exactly what a reader wants), then numbered
     * versions newest-first. Within a version, Flash before Flash-Lite before
     * Pro — this workload is many small requests where speed is the feature —
     * and stable builds before previews.
     */
    fun rank(models: List<LiveModel>): List<LiveModel> {
        val seen = HashSet<String>()
        return models
            .filter { seen.add(it.id) }
            .sortedWith(
                compareByDescending<LiveModel> { it.id.endsWith("-latest") }
                    .thenByDescending { version(it.id) }
                    .thenBy { family(it.id) }
                    .thenBy { it.id.contains("preview") || it.id.contains("exp") }
                    .thenBy { it.id }
            )
            .take(12)
    }

    /**
     * Orderable version key: "gemini-2.5-flash" → 2005, "gemini-3-pro" →
     * 3000, no digits → 0. Major and minor compare as numbers, not as a
     * decimal string, so a hypothetical 3.15 (→ 3015) correctly outranks
     * 3.5 (→ 3005).
     */
    private fun version(id: String): Int {
        val m = Regex("""(\d+)(?:\.(\d+))?""").find(id) ?: return 0
        val major = m.groupValues[1].toIntOrNull() ?: 0
        val minor = m.groupValues[2].toIntOrNull() ?: 0
        return major * 1000 + minor
    }

    private fun family(id: String): Int = when {
        id.contains("flash-lite") -> 1
        id.contains("flash") -> 0
        id.contains("pro") -> 2
        else -> 3
    }
}
