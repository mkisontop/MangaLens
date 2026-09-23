package app.mangalens.translate

import org.json.JSONObject

/**
 * Google's native Gemini API (`generateContent` / `streamGenerateContent`).
 *
 * INTERFACE STUB: implementation pending.
 */
internal object GeminiApi {

    const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    /**
     * One streamed `streamGenerateContent` call. Hands each piece of visible
     * (non-thought) text to [onDelta] as it arrives and returns the whole
     * text. Cancelling the caller aborts the request.
     */
    suspend fun stream(
        apiKey: String,
        model: String,
        body: JSONObject,
        onDelta: (suspend (String) -> Unit)? = null,
    ): String {
        TODO("GeminiApi.stream")
    }

    /** One plain `generateContent` call; returns the response document. */
    suspend fun generate(apiKey: String, model: String, body: JSONObject): JSONObject {
        TODO("GeminiApi.generate")
    }
}
