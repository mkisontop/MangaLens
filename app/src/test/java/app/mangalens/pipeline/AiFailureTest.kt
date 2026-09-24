package app.mangalens.pipeline

import app.mangalens.translate.GeminiBlocked
import app.mangalens.translate.GeminiHttpException
import app.mangalens.translate.GeminiModelMissing
import app.mangalens.translate.GeminiRateLimited
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * With no machine translation behind the AI, the pill's few words are all
 * the reader learns about a failed page. They must name what the reader can
 * act on, for Gemini's own errors and for every other provider's.
 */
class AiFailureTest {

    @Test
    fun `gemini's failures are named by what the reader can do about them`() {
        assertEquals("rate limited", AiFailure.cause(GeminiRateLimited("quota")))
        assertEquals("declined", AiFailure.cause(GeminiBlocked("PROHIBITED_CONTENT")))
        assertEquals("model unavailable", AiFailure.cause(GeminiModelMissing("gemini-old", 404, "not found")))
        assertEquals("HTTP 503", AiFailure.cause(GeminiHttpException(503, "overloaded")))
        assertEquals("key rejected", AiFailure.cause(GeminiHttpException(400, "API key not valid. Please pass a valid API key.")))
        assertEquals("key rejected", AiFailure.cause(GeminiHttpException(403, "PERMISSION_DENIED")))
    }

    @Test
    fun `other providers' failures read the same`() {
        assertEquals("rate limited", AiFailure.cause(RuntimeException("Claude HTTP 429: {\"type\":\"error\"}")))
        assertEquals("key rejected", AiFailure.cause(RuntimeException("OpenAI HTTP 401: invalid_api_key")))
        assertEquals("HTTP 500", AiFailure.cause(RuntimeException("OpenRouter HTTP 500: upstream")))
        assertEquals("network", AiFailure.cause(SocketTimeoutException("timeout")))
        assertEquals("network", AiFailure.cause(RuntimeException("wrapped", IOException("reset"))))
        assertEquals("unexpected error", AiFailure.cause(RuntimeException("no JSON in LLM reply")))
    }

    @Test
    fun `only a turned-away key counts as a rejected key`() {
        assertTrue(AiFailure.keyRejected(GeminiHttpException(401, "unauthenticated")))
        assertTrue(AiFailure.keyRejected(RuntimeException("Claude HTTP 401: invalid x-api-key")))
        assertFalse(AiFailure.keyRejected(GeminiRateLimited("quota")))
        assertFalse(AiFailure.keyRejected(GeminiModelMissing("gemini-old", 403, "PERMISSION_DENIED")))
        assertFalse(AiFailure.keyRejected(GeminiHttpException(403, "location not supported")))
        assertFalse(AiFailure.keyRejected(IOException("offline")))
    }
}
