package app.mangalens.translate

import app.mangalens.ocr.Script
import app.mangalens.settings.SourceLang
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * Zero-setup engine using Google Translate's public web endpoint.
 *
 * The whole page is sent as ONE newline-joined request so Google translates
 * with cross-line context. Responses are re-aligned to bubbles using Google's
 * own echo of the source text (not by counting newlines, which Google
 * restructures — especially for Korean); any bubble whose slot cannot be
 * verified makes the batch bail out to per-bubble requests, so a bad split
 * can never paint the wrong text on a bubble.
 */
class GoogleFreeEngine : TranslationEngine {

    override val label = "Google"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(items: List<String>, lang: SourceLang): List<String> {
        if (items.isEmpty()) return emptyList()
        if (items.size > 1) {
            runCatching { translateBatch(items, lang) }.getOrNull()?.let { return it }
        }
        return coroutineScope {
            val semaphore = Semaphore(5)
            items.map { text ->
                async { semaphore.withPermit { translateOne(text, lang) } }
            }.map { it.await() }
        }
    }

    /**
     * Walks Google's (translated, original) segment pairs and assigns each
     * translated segment to the bubble whose source text it consumed. Returns
     * null whenever the mapping is not airtight.
     */
    private suspend fun translateBatch(items: List<String>, lang: SourceLang): List<String>? {
        val rows = fetchRows(items.joinToString("\n"), lang)
        if (rows.isEmpty()) return null

        val boundaries = IntArray(items.size)
        var accLen = 0
        items.forEachIndexed { i, item ->
            accLen += squash(item).length
            boundaries[i] = accLen
        }

        val builders = Array(items.size) { StringBuilder() }
        var consumed = 0
        var slot = 0
        for ((trans, orig) in rows) {
            if (slot >= items.size) break
            builders[slot].append(trans)
            consumed += squash(orig).length
            while (slot < items.size && consumed >= boundaries[slot]) slot++
        }
        // Uncovered bubbles, or one Google segment spanning two bubbles
        // (leaves a later builder empty) -> not trustworthy, retry per-item.
        if (slot < items.size || consumed < boundaries.last()) return null
        val out = builders.map { it.toString().replace(Regex("\\s+"), " ").trim() }
        if (out.any { it.isEmpty() }) return null
        // A slot still reading as CJK means Google echoed it untranslated.
        if (out.any { Script.cjkCount(it) > it.length * 0.4f }) return null
        return out
    }

    private suspend fun translateOne(text: String, lang: SourceLang): String {
        val sb = StringBuilder()
        for ((trans, _) in fetchRows(text, lang)) sb.append(trans)
        return sb.toString().trim().ifEmpty { text }
    }

    /** One request; returns Google's (translated, original) segment pairs. */
    private suspend fun fetchRows(text: String, lang: SourceLang): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val sl = when (lang) {
                SourceLang.KO -> "ko"
                SourceLang.JA -> "ja"
                SourceLang.ZH -> "zh-CN"
                SourceLang.AUTO -> "auto"
            }
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx" +
                "&sl=" + sl + "&tl=en&dt=t&ie=UTF-8&oe=UTF-8"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Tablet) AppleWebKit/537.36")
                .post(FormBody.Builder().add("q", text).build())
                .build()
            LlmHttp.await(client.newCall(request)).use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("Google translate HTTP " + resp.code)
                val body = resp.body?.string() ?: throw RuntimeException("empty translate response")
                val rows = JSONArray(body).getJSONArray(0)
                val out = ArrayList<Pair<String, String>>(rows.length())
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONArray(i) ?: continue
                    out.add(row.optString(0, "") to row.optString(1, ""))
                }
                out
            }
        }

    private fun squash(s: String) = s.filterNot { it.isWhitespace() }
}
