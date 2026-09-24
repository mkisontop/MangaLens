package app.mangalens.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Translation is the AI's alone, so nothing a reader sees may offer a
 * Google, offline or draft translation, or say a line is "upgrading". This
 * reads the UI's sources and checks their string literals only, so an
 * identifier such as a draft settings value does not trip it.
 */
class UiCopyTest {

    private val literal = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

    private val banned = listOf("offline", "upgrading", "draft", "AI Pro", "Free · Google", "Google Translate")

    private fun sources(): List<File> {
        val root = File("src/main/java/app/mangalens")
        val ui = root.resolve("ui").listFiles { f -> f.extension == "kt" }.orEmpty().toList()
        assertTrue("found the ui sources", ui.size >= 5)
        return ui + listOf(
            root.resolve("overlay/OverlayController.kt"),
            root.resolve("overlay/OverlayStyle.kt"),
            root.resolve("MainActivity.kt"),
            root.resolve("capture/ScreenCaptureService.kt"),
        ).onEach { assertTrue("${it.path} exists", it.isFile) }
    }

    @Test
    fun `no visible copy mentions a machine, offline or draft translation`() {
        for (file in sources()) {
            for (m in literal.findAll(file.readText())) {
                val text = m.groupValues[1]
                for (word in banned) {
                    assertTrue("${file.name}: \"$text\" mentions \"$word\"", !text.contains(word, ignoreCase = true))
                }
            }
        }
    }

    @Test
    fun `the ui never names the removed engines`() {
        val ui = File("src/main/java/app/mangalens/ui").listFiles { f -> f.extension == "kt" }.orEmpty()
        for (file in ui) {
            val code = file.readText()
            for (name in listOf("EngineKind", "GoogleFreeEngine", "MlKitEngine", "setEngine")) {
                assertTrue("${file.name} names $name", name !in code)
            }
        }
    }
}
