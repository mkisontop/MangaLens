package app.mangalens.translate

import app.mangalens.ocr.BubbleKind
import app.mangalens.settings.AiReasoning
import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- Gemini ----

    @Test
    fun `gemini 3 keeps its default temperature and takes low or high thinking only`() {
        val balanced = openAi(settings(LlmProvider.GEMINI, "gemini-3.8-flash"), images = listOf("P"))
        assertFalse("Google warns lowering Gemini 3's temperature causes loops", balanced.has("temperature"))
        assertEquals("low", balanced.getString("reasoning_effort"))
        val thorough = openAi(settings(LlmProvider.GEMINI, "gemini-3.5-flash-lite", AiReasoning.THOROUGH), images = listOf("P"))
        assertEquals("high", thorough.getString("reasoning_effort"))
        assertTrue(balanced.has("max_tokens"))
    }

    @Test
    fun `gemini 2_5 is greedy and takes all three thinking levels`() {
        val body = openAi(settings(LlmProvider.GEMINI, "gemini-2.5-flash"), images = listOf("P"))
        assertEquals(0, body.getInt("temperature"))
        assertEquals("medium", body.getString("reasoning_effort"))
    }

    @Test
    fun `gemini models without thinking get no reasoning field`() {
        val body = openAi(settings(LlmProvider.GEMINI, "gemini-2.0-flash"))
        assertFalse(body.has("reasoning_effort"))
        assertEquals(0, body.getInt("temperature"))
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
        val body = openAi(settings(LlmProvider.GEMINI, "gemini-3.8-flash"), images = listOf("PAGEJPEG", "CROP2", "CROP5"))
        val parts = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals("STABLE", parts.getJSONObject(0).getString("text"))
        for ((i, expected) in listOf("PAGEJPEG", "CROP2", "CROP5").withIndex()) {
            val url = parts.getJSONObject(i + 1).getJSONObject("image_url").getString("url")
            assertEquals("data:image/jpeg;base64,$expected", url)
        }
        assertEquals("PAGE", parts.getJSONObject(4).getString("text"))
    }

    // ---- what the page carries ----

    @Test
    fun `every dialogue bubble carries the room it has for English`() {
        val items = listOf("あいつが来たのか", "え？", "내가 가는데 왜 안 와", "我知道但是已经太晚了", "¡YA NO SÉ QUÉ HACER!", "ドカッ")
        val kinds = listOf(BubbleKind.DIALOGUE, BubbleKind.DIALOGUE, BubbleKind.DIALOGUE, BubbleKind.DIALOGUE, BubbleKind.DIALOGUE, BubbleKind.SFX)
        val arr = LlmEngine.bubblesJson(items, kinds, List(items.size) { -1 }, List(items.size) { 0 })
        assertEquals(30, arr.getJSONObject(0).getInt("fit"))
        assertFalse("an interjection needs no budget", arr.getJSONObject(1).has("fit"))
        assertEquals(33, arr.getJSONObject(2).getInt("fit"))
        assertEquals(40, arr.getJSONObject(3).getInt("fit"))
        assertEquals(29, arr.getJSONObject(4).getInt("fit"))
        assertFalse("sound effects are captions, not typeset dialogue", arr.getJSONObject(5).has("fit"))
        assertTrue(LlmEngine.SYSTEM_PROMPT.contains("\"fit\""))
    }

    @Test
    fun `vision regions carry the budget where OCR read the source`() {
        val read = app.mangalens.ocr.Bubble("あいつが来たのか", android.graphics.Rect(100, 100, 300, 400), true)
        val blind = app.mangalens.ocr.Bubble("", android.graphics.Rect(400, 100, 600, 400), false)
        val arr = VisionLlmEngine.regionsJson(listOf(read, blind), 1000, 1000)
        assertEquals(30, arr.getJSONObject(0).getInt("fit"))
        assertFalse("nothing to size a blind region by", arr.getJSONObject(1).has("fit"))
        assertEquals(100, arr.getJSONObject(0).getJSONArray("box").getInt(0))
    }

    @Test
    fun `the budget leaves a natural translation room`() {
        // Real lines and their tight, natural translations sit under the budget.
        val pairs = listOf(
            "あいつが来たのか" to "So he's come, has he?",
            "大丈夫だから" to "It's fine, really.",
            "내가 가는데 왜 안 와" to "I'm going, why aren't you coming?",
            "我知道但是已经太晚了" to "I know, but it's already too late.",
            "這是在日本的土地上打拼的我" to "Here I am, making my way in an unfamiliar land.",
        )
        for ((src, en) in pairs) {
            val fit = FitBudget.chars(src)!!
            assertTrue("$src: budget $fit must hold \"$en\" (${en.length})", en.length <= fit)
        }
    }

    @Test
    fun `a bare array reply is still read as bubbles`() {
        val o: JSONObject = LlmHttp.extractJsonObject("[{\"id\":0,\"en\":\"Hi\"}]")
        assertEquals("Hi", o.getJSONArray("bubbles").getJSONObject(0).getString("en"))
    }
}
