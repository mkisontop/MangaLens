package app.mangalens.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import app.mangalens.ocr.Bubble
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders a marked page for real and writes it out, so the marks can be judged
 * the way the model sees them: after downscaling and JPEG compression, at the
 * quality Data saver actually uses.
 *
 * The failure this guards against is silent. A badge drawn over the lettering,
 * or squeezed below legibility at 1000px and quality 55, still produces a
 * perfectly valid request — the model just reads the page slightly worse, and
 * nothing in the pipeline reports it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageMarkupTest {

    private val outputDir = File("build/markup-preview").apply { mkdirs() }

    /** A stand-in manga page: panel borders, balloons, and bars for lettering. */
    private fun syntheticPage(width: Int, height: Int, balloons: List<Rect>): Bitmap {
        val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)

        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.BLACK
        }
        val tone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 205, 205) }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        // Two tiers of two panels, screentoned so white balloons stand out.
        val gut = 20
        val midY = height / 2
        val midX = width / 2
        for (ty in listOf(40 to midY - gut, midY + gut to height - 40)) {
            for (tx in listOf(40 to midX - gut, midX + gut to width - 40)) {
                val panel = Rect(tx.first, ty.first, tx.second, ty.second)
                canvas.drawRect(panel, tone)
                canvas.drawRect(panel, ink)
            }
        }
        // Balloons, each holding a few bars where the lettering would be.
        for (b in balloons) {
            canvas.drawOval(android.graphics.RectF(b), fill)
            canvas.drawOval(android.graphics.RectF(b), ink)
            val rows = 3
            val rowH = b.height() / (rows * 2 + 1)
            for (i in 0 until rows) {
                val top = b.top + rowH * (i * 2 + 1)
                canvas.drawRect(
                    Rect(b.left + b.width() / 5, top, b.right - b.width() / 5, top + rowH),
                    letter,
                )
            }
        }
        return page
    }

    @Test
    fun `marked pages render and are written out for inspection`() {
        val width = 1000
        val height = 1500
        val balloons = listOf(
            Rect(560, 120, 900, 320),
            Rect(90, 150, 430, 350),
            Rect(560, 860, 900, 1060),
            Rect(90, 890, 430, 1090),
        )
        val page = syntheticPage(width, height, balloons)
        val anchors = balloons.map { Bubble("text", it, true) }

        for ((label, dataSaver) in listOf("quality" to false, "datasaver" to true)) {
            val b64 = PageMarkup.encodeMarkedPage(page, anchors, dataSaver)
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            assertTrue("marked page for $label came back empty", bytes.size > 2000)
            // JPEG magic — proof this is a real encoded image, not a stub.
            assertTrue("not a JPEG for $label", bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())

            val out = File(outputDir, "marked-$label.jpg")
            out.writeBytes(bytes)
            println("wrote ${out.absolutePath} (${bytes.size} bytes)")
        }

        // The unmarked page, as a baseline to compare the marks against.
        val plain = Base64.decode(VisionLlmEngine.encodePage(page, false), Base64.NO_WRAP)
        File(outputDir, "unmarked.jpg").writeBytes(plain)
        println("wrote ${File(outputDir, "unmarked.jpg").absolutePath} (${plain.size} bytes)")
    }

    @Test
    fun `marking leaves the source frame untouched`() {
        // The captured frame is reused by the capture pipeline; drawing on it
        // would paint region numbers into the next OCR pass.
        val balloons = listOf(Rect(100, 100, 400, 300))
        val page = syntheticPage(600, 800, balloons)
        val before = IntArray(600 * 800)
        page.getPixels(before, 0, 600, 0, 0, 600, 800)

        PageMarkup.encodeMarkedPage(page, balloons.map { Bubble("t", it, true) }, false)

        val after = IntArray(600 * 800)
        page.getPixels(after, 0, 600, 0, 0, 600, 800)
        assertTrue("markup mutated the source frame", before.contentEquals(after))
    }

    @Test
    fun `a page with no detected regions still encodes`() {
        val page = syntheticPage(600, 800, emptyList())
        val bytes = Base64.decode(PageMarkup.encodeMarkedPage(page, emptyList(), false), Base64.NO_WRAP)
        assertTrue(bytes.size > 1000)
        assertTrue(bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
    }
}
