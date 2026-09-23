package app.mangalens.translate

import app.mangalens.settings.AiReasoning
import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The request each provider actually receives. A wrong field here is not a
 * visible failure: the provider answers 400, the engine falls back to
 * Google, and the reader sees a slightly worse page with "fallback" in the
 * pill — every benefit of the model they chose silently discarded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LlmRequestTest {

    private fun settings(provider: LlmProvider, model: String, reasoning: AiReasoning = AiReasoning.BALANCED) =
        AppSettings(provider = provider, model = model, apiKey = "k", aiReasoning = reasoning)

    private fun openAi(settings: AppSettings, images: List<String> = emptyList(), vision: Boolean = images.isNotEmpty()) =
        LlmHttp.openAiBody(
            settings, "SYS", "STABLE", images, "PAGE",
            effort = LlmHttp.effortLevel(settings, vision), vision = vision, stream = true,
        )

    private fun anthropic(settings: AppSettings, images: List<String> = emptyList(), vision: Boolean = images.isNotEmpty()) =
        LlmHttp.anthropicBody(
            settings, "SYS", "STABLE", images, "PAGE",
            effort = LlmHttp.effortLevel(settings, vision), vision = vision, stream = true,
        )

    // ---- Anthropic ----

    @Test
    fun `anthropic requests cache the system prompt and the series memory, page last`() {
        val body = anthropic(settings(LlmProvider.ANTHROPIC, "claude-sonnet-5"), images = listOf("PAGEJPEG", "CROP7"))
        val system = body.getJSONArray("system").getJSONObject(0)
        assertEquals("ephemeral", system.getJSONObject("cache_control").getString("type"))
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("STABLE", content.getJSONObject(0).getString("text"))
        assertEquals("ephemeral", content.getJSONObject(0).getJSONObject("cache_control").getString("type"))
        assertEquals("image", content.getJSONObject(1).getString("type"))
        assertEquals("PAGEJPEG", content.getJSONObject(1).getJSONObject("source").getString("data"))
        assertEquals("CROP7", content.getJSONObject(2).getJSONObject("source").getString("data"))
        assertEquals("PAGE", content.getJSONObject(3).getString("text"))
        assertEquals("medium", body.getJSONObject("output_config").getString("effort"))
        assertTrue(body.getBoolean("stream"))
        assertFalse("Claude rejects sampling parameters", body.has("temperature"))
    }

    @Test
    fun `older claude models get no effort field`() {
        val body = anthropic(settings(LlmProvider.ANTHROPIC, "claude-3-5-haiku-latest"))
        assertFalse(body.has("output_config"))
    }

    @Test
    fun `the reasoning setting maps to low, medium and high`() {
        assertEquals("low", LlmHttp.effortLevel(settings(LlmProvider.ANTHROPIC, "claude-sonnet-5", AiReasoning.FAST), vision = true))
        assertEquals("medium", LlmHttp.effortLevel(settings(LlmProvider.ANTHROPIC, "claude-sonnet-5", AiReasoning.BALANCED), vision = true))
        assertEquals("low", LlmHttp.effortLevel(settings(LlmProvider.ANTHROPIC, "claude-sonnet-5", AiReasoning.BALANCED), vision = false))
        assertEquals("high", LlmHttp.effortLevel(settings(LlmProvider.ANTHROPIC, "claude-sonnet-5", AiReasoning.THOROUGH), vision = false))
    }

    @Test
    fun `the output cap leaves room for thinking and doubles when thorough`() {
        val balanced = anthropic(settings(LlmProvider.ANTHROPIC, "claude-sonnet-5"), images = listOf("P"))
        val thorough = anthropic(settings(LlmProvider.ANTHROPIC, "claude-sonnet-5", AiReasoning.THOROUGH), images = listOf("P"))
        assertTrue(balanced.getInt("max_tokens") >= 8192)
        assertEquals(balanced.getInt("max_tokens") * 2, thorough.getInt("max_tokens"))
    }

    // ---- Gemini (native API) ----

    private fun gemini(settings: AppSettings, images: List<String> = emptyList(), vision: Boolean = images.isNotEmpty()) =
        LlmHttp.geminiBody(
            settings, "SYS", "STABLE", images, "PAGE",
            effort = LlmHttp.effortLevel(settings, vision), vision = vision,
        )

    private fun thinking(body: JSONObject): JSONObject? =
        body.getJSONObject("generationConfig").optJSONObject("thinkingConfig")

    @Test
    fun `gemini gets the native shape, system prompt apart and the page last`() {
        val body = gemini(settings(LlmProvider.GEMINI, "gemini-3.8-flash"), images = listOf("PAGEJPEG", "CROP2"))
        assertEquals("SYS", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals("STABLE", parts.getJSONObject(0).getString("text"))
        assertEquals("PAGEJPEG", parts.getJSONObject(1).getJSONObject("inlineData").getString("data"))
        assertEquals("image/jpeg", parts.getJSONObject(1).getJSONObject("inlineData").getString("mimeType"))
        assertEquals("CROP2", parts.getJSONObject(2).getJSONObject("inlineData").getString("data"))
        assertEquals("PAGE", parts.getJSONObject(3).getString("text"))
        val config = body.getJSONObject("generationConfig")
        assertEquals("application/json", config.getString("responseMimeType"))
        assertTrue(config.getInt("maxOutputTokens") >= 16384)
        assertFalse("the key goes in a header, the model in the URL", body.has("model"))
        assertFalse(body.has("safetySettings"))
    }

    @Test
    fun `gemini 3 keeps its default temperature and thinks in levels`() {
        val balanced = gemini(settings(LlmProvider.GEMINI, "gemini-3.8-flash"), images = listOf("P"))
        assertFalse("Google warns lowering Gemini 3's temperature causes loops", balanced.getJSONObject("generationConfig").has("temperature"))
        assertEquals("low", thinking(balanced)!!.getString("thinkingLevel"))
        val thorough = gemini(settings(LlmProvider.GEMINI, "gemini-3.5-flash-lite", AiReasoning.THOROUGH), images = listOf("P"))
        assertEquals("high", thinking(thorough)!!.getString("thinkingLevel"))
        assertEquals(
            thorough.getJSONObject("generationConfig").getInt("maxOutputTokens"),
            balanced.getJSONObject("generationConfig").getInt("maxOutputTokens") * 2,
        )
    }

    @Test
    fun `fast asks for minimal thinking only where the model takes it`() {
        fun fast(model: String) = thinking(gemini(settings(LlmProvider.GEMINI, model, AiReasoning.FAST)))!!.getString("thinkingLevel")
        assertEquals("minimal", fast("gemini-3.5-flash"))
        assertEquals("minimal", fast("gemini-3.5-flash-lite"))
        assertEquals("minimal", fast("gemini-3.1-flash-lite"))
        // 3.7 and 3.8 Flash answer 400 to "minimal"; the default alias points at 3.8.
        assertEquals("low", fast("gemini-3.8-flash"))
        assertEquals("low", fast("gemini-3.7-flash"))
        assertEquals("low", fast("gemini-flash-latest"))
    }

    @Test
    fun `the default model alias counts as gemini 3`() {
        val body = gemini(settings(LlmProvider.GEMINI, ""))
        assertEquals("low", thinking(body)!!.getString("thinkingLevel"))
        assertFalse(body.getJSONObject("generationConfig").has("temperature"))
    }

    @Test
    fun `gemini 2_5 is greedy and takes a thinking budget`() {
        val body = gemini(settings(LlmProvider.GEMINI, "gemini-2.5-flash"), images = listOf("P"))
        assertEquals(0, body.getJSONObject("generationConfig").getInt("temperature"))
        assertTrue(thinking(body)!!.getInt("thinkingBudget") > 0)
        assertEquals(0, thinking(gemini(settings(LlmProvider.GEMINI, "gemini-2.5-flash", AiReasoning.FAST)))!!.getInt("thinkingBudget"))
        assertEquals(-1, thinking(gemini(settings(LlmProvider.GEMINI, "gemini-2.5-flash", AiReasoning.THOROUGH)))!!.getInt("thinkingBudget"))
        // 2.5 Pro cannot switch thinking off.
        assertEquals(128, thinking(gemini(settings(LlmProvider.GEMINI, "gemini-2.5-pro", AiReasoning.FAST)))!!.getInt("thinkingBudget"))
    }

    @Test
    fun `gemini models without thinking get no thinking config`() {
        val body = gemini(settings(LlmProvider.GEMINI, "gemini-2.0-flash"))
        assertNull(thinking(body))
        assertEquals(0, body.getJSONObject("generationConfig").getInt("temperature"))
    }

    // ---- OpenAI ----

    @Test
    fun `openai reasoning models get max_completion_tokens, an effort, and no temperature`() {
        val body = openAi(settings(LlmProvider.OPENAI, "gpt-5-mini"), images = listOf("P"))
        assertTrue(body.has("max_completion_tokens"))
        assertFalse("reasoning models reject max_tokens", body.has("max_tokens"))
        assertFalse("reasoning models reject temperature", body.has("temperature"))
        assertEquals("medium", body.getString("reasoning_effort"))
    }

    @Test
    fun `openai chat models keep the classic shape`() {
        val body = openAi(settings(LlmProvider.OPENAI, "gpt-4o-mini"))
        assertTrue(body.has("max_tokens"))
        assertEquals(0, body.getInt("temperature"))
        assertFalse(body.has("reasoning_effort"))
    }

    // ---- OpenRouter and custom ----

    @Test
    fun `openrouter gets the unified reasoning object for models that reason`() {
        val claude = openAi(settings(LlmProvider.OPENROUTER, "anthropic/claude-sonnet-4.5"), images = listOf("P"))
        assertEquals("medium", claude.getJSONObject("reasoning").getString("effort"))
        assertEquals(0, claude.getInt("temperature"))
        val gemini3 = openAi(settings(LlmProvider.OPENROUTER, "google/gemini-3.8-flash"))
        assertTrue(gemini3.has("reasoning"))
        assertFalse(gemini3.has("temperature"))
        val plain = openAi(settings(LlmProvider.OPENROUTER, "meta-llama/llama-3.3-70b-instruct"))
        assertFalse(plain.has("reasoning"))
    }

    @Test
    fun `custom endpoints get the plainest request`() {
        val body = openAi(settings(LlmProvider.CUSTOM, "whatever").copy(customUrl = "http://x/v1/chat/completions"))
        assertFalse(body.has("reasoning_effort"))
        assertFalse(body.has("reasoning"))
        assertEquals(0, body.getInt("temperature"))
        assertTrue(body.has("max_tokens"))
        // Text-only turns are one plain string, which every server accepts.
        val user = body.getJSONArray("messages").getJSONObject(1)
        assertEquals("STABLE\n\nPAGE", user.getString("content"))
    }

    @Test
    fun `images are sent page first, close-ups after, page text last`() {
        val body = openAi(settings(LlmProvider.OPENROUTER, "google/gemini-3.8-flash"), images = listOf("PAGEJPEG", "CROP2", "CROP5"))
        val parts = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals("STABLE", parts.getJSONObject(0).getString("text"))
        for ((i, expected) in listOf("PAGEJPEG", "CROP2", "CROP5").withIndex()) {
            val url = parts.getJSONObject(i + 1).getJSONObject("image_url").getString("url")
            assertEquals("data:image/jpeg;base64,$expected", url)
        }
        assertEquals("PAGE", parts.getJSONObject(4).getString("text"))
    }

    @Test
    fun `a bare array reply is still read as bubbles`() {
        val o: JSONObject = LlmHttp.extractJsonObject("[{\"id\":0,\"en\":\"Hi\"}]")
        assertEquals("Hi", o.getJSONArray("bubbles").getJSONObject(0).getString("en"))
    }
}
