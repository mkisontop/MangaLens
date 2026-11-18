package app.mangalens.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import app.mangalens.ocr.Bubble
import app.mangalens.ocr.OcrLine
import java.io.File
import org.junit.Assert.assertEquals
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

    @Test
    fun zoomedDialoguesKeepEnoughPixelsForTheirSmallestLettering() {
        val upload = Rect(0, 380, 720, 896)
        val dialogue = Bubble("한글", Rect(145, 447, 692, 780), false)
        val title = Bubble("", Rect(10, 450, 82, 544), false)
        val normal = listOf(OcrLine("한글", Rect(222, 500, 370, 550), false))
        fun dimension(lines: List<OcrLine> = normal, cropped: Boolean = true,
                      dataSaver: Boolean = true, anchors: List<Bubble> = listOf(dialogue, title), id: Int? = 0) =
            PageMarkup.zoomPageDimension(upload, cropped, anchors, id, lines, dataSaver)
        assertEquals(576, dimension())
        assertEquals(512, dimension(anchors = listOf(dialogue)))
        assertEquals(630, dimension(lines = normal + normal[0].copy(box = Rect(220, 601, 363, 633))))
        assertEquals(1000, dimension(lines = normal + normal[0].copy(box = Rect(220, 601, 363, 611))))
        assertEquals(1000, dimension(lines = normal + normal[0].copy(box = Rect(10, 450, 72, 490))))
        assertEquals(1000, dimension(lines = listOf(normal[0].copy(vertical = true))))
        assertEquals(1000, dimension(lines = emptyList()))
        assertEquals(1000, dimension(cropped = false))
        assertEquals(1000, dimension(id = null))
        assertEquals(1400, dimension(dataSaver = false))
        assertEquals(1000, dimension(anchors = listOf(dialogue.copy(vertical = true))))
    }

    @Test
    fun aNativeZoomSkipsOnlyCompletelyFlatCloseups() {
        val bitmap = Bitmap.createBitmap(360, 240, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val anchors = listOf(Bubble("", Rect(20, 36, 94, 145), false),
                Bubble("", Rect(194, 42, 286, 157), false))
            val ids = listOf(0, 1)
            assertEquals(ids, PageMarkup.encodeRegionCrops(bitmap, anchors, ids, true).map { it.first })
            assertTrue(PageMarkup.encodeRegionCrops(bitmap, anchors, ids, true, omitFlatCrops = true).isEmpty())
            // Even one off-white pixel near the far corner can carry panel context.
            bitmap.setPixel(283, 154, Color.rgb(254, 255, 255))
            assertEquals(listOf(1),
                PageMarkup.encodeRegionCrops(bitmap, anchors, ids, true, omitFlatCrops = true).map { it.first })
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun aWholeFrameCloseupKeepsTheReadersImmutableCaptureUsable() {
        for (size in listOf(176, 900)) {
            val page = Bitmap.createBitmap(IntArray(size * size) { Color.WHITE }, size, size, Bitmap.Config.ARGB_8888)
            try {
                assertTrue("The fixture models an immutable captured page", !page.isMutable)
                val anchor = Bubble("", Rect(0, 0, size, size), false)
                val closeup = PageMarkup.encodeRegionCrops(page, listOf(anchor), listOf(0), dataSaver = true)
                assertEquals(1, closeup.size)
                assertTrue("The capture is still available to OCR and lettering", !page.isRecycled)
                assertEquals(Color.WHITE, page.getPixel(0, 0))
            } finally {
                page.recycle()
            }
        }
    }

    @Test
    fun darkReaderMarginsCanBeTrimmedWithoutDroppingAnIsolatedPixelOrAnchor() {
        val bitmap = Bitmap.createBitmap(180, 420, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val border = Color.rgb(39, 42, 47)
            canvas.drawColor(border)
            val ink = Paint().apply { color = Color.WHITE }
            canvas.drawRect(Rect(0, 160, 180, 260), ink)
            val panel = PageMarkup.visiblePageBounds(bitmap, emptyList())
            assertEquals(Rect(0, 152, 180, 268), panel)
            assertEquals(Rect(100, 389, 600, 527),
                PageMarkup.fullFrameBox(panel, 180, 420, Rect(100, 100, 600, 600)))
            assertEquals(Rect(0, 36, 180, 268),
                PageMarkup.visiblePageBounds(bitmap, listOf(Bubble("word", Rect(12, 36, 49, 51), false))))
            bitmap.setPixel(19, 72, Color.RED)
            assertEquals(Rect(0, 64, 180, 356), PageMarkup.visiblePageBounds(bitmap, emptyList()))
            bitmap.setPixel(177, 416, Color.WHITE)
            assertEquals(Rect(0, 0, 180, 420), PageMarkup.visiblePageBounds(bitmap, emptyList()))
            canvas.drawColor(Color.WHITE)
            assertEquals(Rect(0, 0, 180, 420), PageMarkup.visiblePageBounds(bitmap, emptyList()))
            canvas.drawColor(border)
            assertEquals(Rect(0, 0, 180, 420), PageMarkup.visiblePageBounds(bitmap, emptyList()))
        } finally {
            bitmap.recycle()
        }
    }

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
    fun `close-ups are cut from the full-resolution frame and badged`() {
        val balloons = listOf(Rect(560, 120, 900, 320), Rect(90, 150, 430, 350))
        val page = syntheticPage(1000, 1500, balloons)
        val anchors = balloons.map { Bubble("", it, true) }

        val crops = PageMarkup.encodeRegionCrops(page, anchors, listOf(1, 0), dataSaver = false)
        assertEquals("one close-up per requested region, each with its id", listOf(1, 0), crops.map { it.first })
        for ((i, b64) in crops.map { it.second }.withIndex()) {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            assertTrue("close-up $i is a JPEG", bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
            val img = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            // The balloon is 340 px wide: the crop keeps full resolution, with its padding.
            assertTrue("close-up keeps the source resolution (got ${img.width})", img.width in 340..420)
            // The badge sits in the top-left corner in mark magenta.
            val corner = img.getPixel(4, 4)
            assertTrue("badge at the corner", Color.red(corner) > 180 && Color.green(corner) < 80)
            val out = File(outputDir, "closeup-$i.jpg")
            out.writeBytes(bytes)
            println("wrote ${out.absolutePath} (${bytes.size} bytes)")
        }

        // Data saver shrinks them but keeps every one.
        val small = PageMarkup.encodeRegionCrops(page, anchors, listOf(0), dataSaver = true)
        assertEquals(listOf(0), small.map { it.first })
        val img = android.graphics.BitmapFactory.decodeByteArray(Base64.decode(small[0].second, Base64.NO_WRAP), 0, Base64.decode(small[0].second, Base64.NO_WRAP).size)
        assertTrue("data saver close-ups are at most 384 px on the long side (got ${img.width})", img.width <= 384)

        // An id off the end is skipped, never a crash, and the ids that
        // come back name only the close-ups actually made.
        assertTrue(PageMarkup.encodeRegionCrops(page, anchors, listOf(7), false).isEmpty())
        assertEquals(listOf(1, 0), PageMarkup.encodeRegionCrops(page, anchors, listOf(1, 7, 0), false).map { it.first })
    }

    @Test
    fun `a page with no detected regions still encodes`() {
        val page = syntheticPage(600, 800, emptyList())
        val bytes = Base64.decode(PageMarkup.encodeMarkedPage(page, emptyList(), false), Base64.NO_WRAP)
        assertTrue(bytes.size > 1000)
        assertTrue(bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
    }
}
