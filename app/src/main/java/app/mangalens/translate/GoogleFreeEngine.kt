package app.mangalens.translate

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
 * with cross-line context instead of bubble-by-bubble — noticeably less
 * stilted, and a single round-trip instead of one per bubble. Falls back to
 * per-item requests if the newline alignment ever fails.
 */
class GoogleFreeEngine : TranslationEngine {

    override val label = "Google"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(items: List<String>, lang: SourceLang): List<String> {
        if (items.isEmpty()) return emptyList()
        // Bubble text never contains newlines (whitespace is collapsed upstream),
        // so "\n" is a safe join/split delimiter that Google preserves.
        if (items.size > 1) {
            runCatching { translateBatch(items, lang) }.getOrNull()?.let { return it }
        }
        return coroutineScope {
            val semaphore = Semaphore(5)
            items.map { text ->
                async { semaphore.withPermit { call(text, lang) } }
            }.map { it.await() }
        }
    }

    private suspend fun translateBatch(items: List<String>, lang: SourceLang): List<String>? {
        val joined = items.joinToString("\n")
        val full = call(joined, lang)
        val parts = full.split("\n").map { it.trim() }
        if (parts.size != items.size) return null
        return parts.mapIndexed { i, p -> p.ifEmpty { items[i] } }
    }

    private suspend fun call(text: String, lang: SourceLang): String = withContext(Dispatchers.IO) {
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
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Google translate HTTP " + resp.code)
            val body = resp.body?.string() ?: throw RuntimeException("empty translate response")
            val rows = JSONArray(body).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONArray(i) ?: continue
                sb.append(row.optString(0, ""))
            }
            sb.toString().trim().ifEmpty { text }
        }
    }
}
