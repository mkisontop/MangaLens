package app.mangalens.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import app.mangalens.settings.SourceLang
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The layout family balloon geometry alone cannot decide, drawn as pages:
 * a full-height panel down one side with a balloon near its top, and a
 * two-panel tier over a single panel below. The balloons sit at identical
 * positions on both; only the panel borders differ, and only the borders
 * decide the reading order. The grid must be read off the pixels and the
 * order must follow it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageLayoutTest {

    private val outputDir = File("build/layout-preview").apply { mkdirs() }

    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.BLACK
    }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val tone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 150, 150) }

    /** A panel: ruled border, light grey wash so the interior is not blank paper. */
    private fun panel(canvas: Canvas, r: Rect) {
        canvas.drawRect(r, Paint().apply { color = Color.rgb(228, 228, 228) })
        // Some art: a few shaded shapes so rows through the panel are never blank.
        val inset = Rect(r.left + 30, r.top + 30, r.right - 30, r.bottom - 30)
        canvas.drawOval(RectF(inset.left.toFloat(), inset.centerY().toFloat(), inset.centerX().toFloat(), inset.bottom.toFloat()), tone)
        canvas.drawRect(Rect(inset.centerX(), inset.top, inset.right, inset.centerY()), tone)
        canvas.drawRect(r, border)
    }

    /** A white balloon with three rows of lettering. */
    private fun balloon(canvas: Canvas, r: Rect) {
        canvas.drawOval(RectF(r), white)
        canvas.drawOval(RectF(r), border)
        val rows = 3
        for (i in 0 until rows) {
            val top = r.top + r.height() * (i + 1) / (rows + 1) - 8
            canvas.drawRect(Rect(r.left + r.width() / 4, top, r.right - r.width() / 4, top + 16), black)
        }
    }

    private val pageW = 1000
    private val pageH = 1500

    /** Balloon boxes shared by both layouts: the ambiguous geometry itself. */
    private val left = Rect(90, 90, 430, 300)
    private val rightTop = Rect(560, 90, 900, 260)
    private val rightBottom = Rect(560, 820, 900, 990)

    private fun tallLeftPanelPage(): Bitmap {
        val bmp = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        panel(c, Rect(40, 40, 480, 1460))
        panel(c, Rect(520, 40, 960, 740))
        panel(c, Rect(520, 780, 960, 1460))
        balloon(c, left)
        balloon(c, rightTop)
        balloon(c, rightBottom)
        return bmp
    }

    private fun tierOverRightPanelPage(): Bitmap {
        val bmp = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        panel(c, Rect(40, 40, 480, 740))
        panel(c, Rect(520, 40, 960, 740))
        panel(c, Rect(520, 780, 960, 1460))
        balloon(c, left)
        balloon(c, rightTop)
        balloon(c, rightBottom)
        return bmp
    }

    private fun writePreview(name: String, bmp: Bitmap, panels: List<Rect>, balloons: List<Rect>) {
        val copy = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.rgb(0, 140, 230)
        }
        panels.forEach { canvas.drawRect(it, p) }
        p.color = Color.rgb(230, 0, 140)
        balloons.forEach { canvas.drawRect(it, p) }
        ByteArrayOutputStream().use { bos ->
            copy.compress(Bitmap.CompressFormat.PNG, 100, bos)
            File(outputDir, name).writeBytes(bos.toByteArray())
        }
        println("wrote ${File(outputDir, name).absolutePath}  (${panels.size} panels, ${balloons.size} balloons)")
    }

    private fun covers(found: Rect, truth: Rect): Boolean {
        val ix = minOf(found.right, truth.right) - maxOf(found.left, truth.left)
        val iy = minOf(found.bottom, truth.bottom) - maxOf(found.top, truth.top)
        if (ix <= 0 || iy <= 0) return false
        val inter = ix.toLong() * iy
        val union = found.width().toLong() * found.height() + truth.width().toLong() * truth.height() - inter
        return inter.toFloat() / union > 0.7f
    }

    @Test
    fun `the panel grid is read off the gutters`() {
        val scan = BalloonFinder.analyze(tallLeftPanelPage())
        writePreview("tall-left.png", tallLeftPanelPage(), scan.panels, scan.balloons.map { it.box })

        val truth = listOf(Rect(40, 40, 480, 1460), Rect(520, 40, 960, 740), Rect(520, 780, 960, 1460))
        for (t in truth) {
            assertTrue("no panel found for $t (found ${scan.panels})", scan.panels.any { covers(it, t) })
        }
        assertEquals("exactly the three panels, got ${scan.panels}", 3, scan.panels.size)
        assertTrue(
            "no panel may be reported as a balloon (found ${scan.balloons.map { it.box }})",
            scan.balloons.none { it.box.width() > 400 },
        )
    }

    private fun readingOrder(bmp: Bitmap, name: String): List<String> {
        val scan = BalloonFinder.analyze(bmp)
        writePreview(name, bmp, scan.panels, scan.balloons.map { it.box })
        val labelled = listOf("L" to left, "Rtop" to rightTop, "Rbot" to rightBottom)
        // The balloons as the grouper would hand them over: one region per
        // detected balloon, labelled by the truth box it landed on.
        val bubbles = scan.balloons.mapNotNull { b ->
            val label = labelled.firstOrNull { covers(b.box, it.second) }?.first ?: return@mapNotNull null
            Bubble(label, b.box, true)
        }
        assertEquals("every balloon must be detected, got ${scan.balloons.map { it.box }}", 3, bubbles.size)
        return ReadingOrder.order(bubbles, rtl = true, panels = scan.panels).map { it.text }
    }

    @Test
    fun `a full-height side panel is read after the tier beside it`() {
        assertEquals(listOf("Rtop", "Rbot", "L"), readingOrder(tallLeftPanelPage(), "order-tall-left.png"))
    }

    @Test
    fun `a two-panel tier over a single panel is read tier by tier`() {
        assertEquals(listOf("Rtop", "L", "Rbot"), readingOrder(tierOverRightPanelPage(), "order-tier.png"))
    }

    @Test
    fun `black gutters separate panels too`() {
        val bmp = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        panel(c, Rect(40, 40, 960, 700))
        panel(c, Rect(40, 780, 960, 1460))
        val scan = BalloonFinder.analyze(bmp)
        writePreview("black-gutter.png", bmp, scan.panels, scan.balloons.map { it.box })
        assertEquals("two panels split by a black gutter, got ${scan.panels}", 2, scan.panels.size)
    }

    @Test
    fun `a page without gutters yields no grid and order still works`() {
        val bmp = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(200, 200, 200))
        balloon(c, left)
        balloon(c, rightTop)
        val scan = BalloonFinder.analyze(bmp)
        assertTrue("a borderless page has no panel grid, got ${scan.panels}", scan.panels.isEmpty())
        val bubbles = listOf(Bubble("L", left, true), Bubble("R", rightTop, true))
        assertEquals(listOf("R", "L"), ReadingOrder.order(bubbles, rtl = true, panels = scan.panels).map { it.text })
        // And grouping still lands each balloon in its own region.
        val lines = listOf(
            OcrLine("こんにちは", Rect(left.centerX() - 20, left.top + 40, left.centerX() + 20, left.bottom - 40), true),
            OcrLine("さようなら", Rect(rightTop.centerX() - 20, rightTop.top + 40, rightTop.centerX() + 20, rightTop.bottom - 40), true),
        )
        val grouped = BubbleGrouper.group(lines, pageH, 0, 0, SourceLang.JA, balloons = scan.balloons.map { it.box })
        assertEquals(2, grouped.size)
    }
}
