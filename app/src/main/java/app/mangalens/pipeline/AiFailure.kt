package app.mangalens.pipeline

import app.mangalens.translate.GeminiBlocked
import app.mangalens.translate.GeminiHttpException
import app.mangalens.translate.GeminiModelMissing
import app.mangalens.translate.GeminiRateLimited
import java.io.IOException

/**
 * Why the AI could not translate a page, in the few words the status pill
 * has room for. Nothing else translates the page when the AI fails, so this
 * is all the reader learns about it: it names what they can act on — the
 * network, the rate limit, the key — and never the key or the page.
 */
internal object AiFailure {

    /** The status in the errors providers other than Gemini raise: "Claude HTTP 429: …". */
    private val HTTP_STATUS = Regex("""\bHTTP (\d{3})\b""")

    fun cause(e: Throwable): String = when {
        keyRejected(e) -> "key rejected"
        e is GeminiRateLimited -> "rate limited"
        e is GeminiBlocked -> "declined"
        e is GeminiModelMissing -> "model unavailable"
        e is GeminiHttpException -> "HTTP ${e.code}"
        e is IOException || e.cause is IOException -> "network"
        else -> when (val code = statusOf(e)) {
            null -> "unexpected error"
            429 -> "rate limited"
            else -> "HTTP $code"
        }
    }

    /**
     * The provider turned the key itself away — wrong, revoked or not
     * allowed this API — as opposed to a busy server or a declined page.
     * Every page will fail the same way until the reader fixes it.
     */
    fun keyRejected(e: Throwable): Boolean {
        if (e is GeminiModelMissing) return false
        if (e is GeminiHttpException) {
            val m = e.message.orEmpty()
            return e.code == 401 || (e.code == 403 && "PERMISSION_DENIED" in m) ||
                (e.code == 400 && ("API_KEY_INVALID" in m || "API key not valid" in m || "API key expired" in m))
        }
        val code = statusOf(e)
        return code == 401 || code == 403
    }

    private fun statusOf(e: Throwable): Int? =
        e.message?.let { HTTP_STATUS.find(it) }?.groupValues?.get(1)?.toIntOrNull()
}
