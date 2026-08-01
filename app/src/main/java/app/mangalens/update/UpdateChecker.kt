package app.mangalens.update

import app.mangalens.translate.LlmHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Sideloaded apps have no store to update them, so the app checks for itself:
 * one anonymous request to the repository's latest-release endpoint when the
 * home screen opens, a quiet banner if the version there is newer, and
 * nothing at all — no error, no nag — when offline or rate-limited. The
 * banner links to the release page; nothing downloads without the user.
 */
object UpdateChecker {

    const val REPO = "mkisontop/mangalens"
    const val LATEST_URL = "https://github.com/$REPO/releases/latest"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    data class Update(val version: String, val url: String)

    /** Returns the newer release, or null for "current, unreachable, or unsure". */
    suspend fun check(currentVersion: String): Update? = withContext(Dispatchers.IO) {
        checkBlocking(currentVersion)
    }

    private suspend fun checkBlocking(currentVersion: String): Update? = runCatching {
        val request = Request.Builder()
            .url(API)
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        LlmHttp.await(LlmHttp.client.newCall(request)).use { resp ->
            if (!resp.isSuccessful) return null
            val body = JSONObject(resp.body?.string() ?: return null)
            val latest = numeric(body.optString("tag_name"))
            val url = body.optString("html_url").ifBlank { LATEST_URL }
            if (latest.isNotEmpty() && isNewer(latest, currentVersion)) {
                Update(latest, url)
            } else {
                null
            }
        }
    }.getOrNull()

    /** "v0.9.1", "mangalens-v0.9.1", "0.9.1-debug" → "0.9.1". */
    fun numeric(version: String): String =
        Regex("""\d+(?:\.\d+)*""").find(version)?.value ?: ""

    /**
     * Numeric segment-by-segment comparison — "0.10.0" is newer than "0.9.1",
     * which string comparison gets wrong. Unparseable input compares as
     * not-newer: a malformed tag must never produce an update banner.
     */
    fun isNewer(latest: String, current: String): Boolean {
        val a = numeric(latest).split('.').map { it.toIntOrNull() ?: return false }
        val b = numeric(current).split('.').map { it.toIntOrNull() ?: return false }
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
