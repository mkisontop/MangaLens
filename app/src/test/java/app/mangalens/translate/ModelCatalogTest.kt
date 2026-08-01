package app.mangalens.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The catalog is what keeps the model picker honest a year from now: models
 * that don't exist yet must rank ahead of today's when Google ships them, and
 * the families a translator can't use (embeddings, TTS, image generation)
 * must never appear no matter what the API starts returning.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelCatalogTest {

    private fun model(id: String, display: String = "", methods: List<String> = listOf("generateContent")) =
        """{"name":"models/$id","displayName":"$display","supportedGenerationMethods":[${
            methods.joinToString(",") { "\"$it\"" }
        }]}"""

    private fun payload(vararg models: String) = """{"models":[${models.joinToString(",")}]}"""

    @Test
    fun `specialty families are filtered out whatever the API returns`() {
        val out = ModelCatalog.parseGemini(
            payload(
                model("gemini-2.5-flash", "Gemini 2.5 Flash"),
                model("gemini-embedding-001"),
                model("gemini-2.5-flash-preview-tts"),
                model("gemini-2.5-flash-image"),
                model("gemini-2.5-flash-preview-native-audio-dialog"),
                model("gemini-2.0-flash-live-001"),
                model("veo-3.0-generate"),
                model("imagen-4.0-generate"),
                model("aqa"),
            )
        )
        assertEquals(listOf("gemini-2.5-flash"), out.map { it.id })
    }

    @Test
    fun `a model that cannot generateContent is dropped`() {
        val out = ModelCatalog.parseGemini(
            payload(
                model("gemini-2.5-flash"),
                model("gemini-2.5-pro", methods = listOf("countTokens")),
            )
        )
        assertEquals(listOf("gemini-2.5-flash"), out.map { it.id })
    }

    @Test
    fun `newer versions outrank older and flash outranks pro within a version`() {
        val out = ModelCatalog.parseGemini(
            payload(
                model("gemini-1.5-flash"),
                model("gemini-2.5-pro"),
                model("gemini-2.5-flash"),
                model("gemini-2.5-flash-lite"),
                // A generation newer than anything this code knew when written:
                model("gemini-3.5-flash", "Gemini 3.5 Flash"),
                model("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite"),
            )
        )
        assertEquals(
            listOf(
                "gemini-3.5-flash",
                "gemini-3.5-flash-lite",
                "gemini-2.5-flash",
                "gemini-2.5-flash-lite",
                "gemini-2.5-pro",
                "gemini-1.5-flash",
            ),
            out.map { it.id },
        )
    }

    @Test
    fun `latest aliases rank first because they always point at the newest release`() {
        val out = ModelCatalog.parseGemini(
            payload(
                model("gemini-2.5-flash"),
                model("gemini-flash-latest", "Gemini Flash Latest"),
                model("gemini-flash-lite-latest", "Gemini Flash-Lite Latest"),
            )
        )
        assertEquals(
            listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash"),
            out.map { it.id },
        )
    }

    @Test
    fun `stable builds rank ahead of previews of the same version`() {
        val out = ModelCatalog.parseGemini(
            payload(
                model("gemini-2.5-flash-preview-05-20"),
                model("gemini-2.5-flash"),
            )
        )
        assertEquals(listOf("gemini-2.5-flash", "gemini-2.5-flash-preview-05-20"), out.map { it.id })
    }

    @Test
    fun `the list is capped so the picker stays scannable`() {
        val many = (1..20).map { model("gemini-2.$it-flash") }
        val out = ModelCatalog.parseGemini(payload(*many.toTypedArray()))
        assertTrue("expected at most 12, got ${out.size}", out.size <= 12)
    }

    @Test
    fun `duplicates collapse to one entry and display names surface`() {
        val out = ModelCatalog.parseGemini(
            payload(
                model("gemini-2.5-flash", "Gemini 2.5 Flash"),
                model("gemini-2.5-flash", "Gemini 2.5 Flash (again)"),
            )
        )
        assertEquals(1, out.size)
        assertEquals("Gemini 2.5 Flash", out.single().label)
    }

    @Test
    fun `garbage in, empty list out`() {
        assertEquals(emptyList<ModelCatalog.LiveModel>(), ModelCatalog.parseGemini("""{"error":"nope"}"""))
    }
}
