package app.mangalens.translate

import app.mangalens.settings.SourceLang
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * Zero-setup engine using Google Translate's public web endpoint.
 * Free, no API key, solid quality for KO/JA/ZH -> EN.
 */
class GoogleFreeEngine : TranslationEngine {

    override val label = "Google"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(items: List<String>, lang: SourceLang): List<String> = coroutineScope {
        val semaphore = Semaphore(5)
        items.map { text ->
            async { semaphore.withPermit { translateOne(text, lang) } }
        }.map { it.await() }
    }

    private suspend fun translateOne(text: String, lang: SourceLang): String = withContext(Dispatchers.IO) {
        val sl = when (lang) {
            SourceLang.KO -> "ko"
            SourceLang.JA -> "ja"
            SourceLang.ZH -> "zh-CN"
            SourceLang.AUTO -> "auto"
        }
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx" +
            "&sl=" + sl + "&tl=en&dt=t&ie=UTF-8&oe=UTF-8&q=" + URLEncoder.encode(text, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Tablet) AppleWebKit/537.36")
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
