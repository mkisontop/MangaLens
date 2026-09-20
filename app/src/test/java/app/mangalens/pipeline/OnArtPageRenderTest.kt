package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import app.mangalens.ocr.BalloonFinder
import app.mangalens.ocr.Bubble
import app.mangalens.ocr.BubbleGrouper
import app.mangalens.ocr.OcrLine
import app.mangalens.overlay.BubbleOverlayView
import app.mangalens.overlay.RenderBubble
import app.mangalens.settings.SourceLang
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The page that showed the cards up: a night sky full of stars, balloons
 * on it, and a monologue set in columns straight onto the sky with no
 * balloon around it — plus a shout across the top and a caption row.
 *
 * Drawn here as a page and run through detection, grouping (with the OCR
 * lines a recognizer would return for the columns) and rendering, then
 * read back: every original glyph must be gone, the English must sit
 * where the original block sat rather than off to one side or over a
 * face, the sky between two columns must survive untouched, and nothing
 * may run off the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnArtPageRenderTest {

    private val outputDir = File("build/render-preview").apply { mkdirs() }

    private val pageW = 1080
    private val pageH = 1920

    /** Balloon lettering: a dark red nothing rendered emits. */
    private val darkInk = Color.rgb(176, 0, 0)

    /** On-art lettering: a pale yellow nothing rendered emits, haloed in near-black as artists do. */
    private val lightInk = Color.rgb(236, 232, 150)
    private val halo = Color.rgb(12, 12, 14)

    private fun isDarkInk(c: Int) = Color.red(c) >= 140 && Color.green(c) <= 60 && Color.blue(c) <= 60
    private fun isLightInk(c: Int) = Color.red(c) >= 205 && Color.green(c) >= 200 && Color.blue(c) <= 175 && Color.red(c) - Color.blue(c) >= 45

    private class Block(val name: String, val lines: List<OcrLine>, val english: String) {
        val box: Rect = Rect(lines[0].box).also { u -> lines.drop(1).forEach { u.union(it.box) } }
    }

    private class Page(val bitmap: Bitmap, val blocks: List<Block>, val balloons: List<Rect>)

    /** Strokes of one pseudo-CJK glyph, in em-relative units. */
    private fun glyphStrokes(rnd: Random): List<FloatArray> {
        val n = 3 + rnd.nextInt(4)
        return List(n) {
            val horizontal = rnd.nextBoolean()
            val a = 0.1f + rnd.nextFloat() * 0.8f
            val from = 0.1f + rnd.nextFloat() * 0.3f
            val to = 0.6f + rnd.nextFloat() * 0.35f
            if (horizontal) floatArrayOf(from, a, to, a) else floatArrayOf(a, from, a, to)
        }
    }

    private fun drawGlyph(c: Canvas, x: Int, y: Int, em: Int, strokes: List<FloatArray>, ink: Int, haloed: Boolean) {
        val sw = (em * 0.11f).coerceAtLeast(2f)
        if (haloed) {
            val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = halo; strokeWidth = sw + 4f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
            for (s in strokes) c.drawLine(x + s[0] * em, y + s[1] * em, x + s[2] * em, y + s[3] * em, hp)
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; strokeWidth = sw; style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
        for (s in strokes) c.drawLine(x + s[0] * em, y + s[1] * em, x + s[2] * em, y + s[3] * em, p)
    }

    /**
     * Vertical columns of glyphs, right to left, the way the page carries
     * them, returning the OCR lines a recognizer would report: one tight
     * box per column.
     */
    private fun columns(c: Canvas, right: Int, top: Int, columns: Int, perColumn: Int, em: Int, ink: Int, haloed: Boolean, rnd: Random): List<OcrLine> {
        val colPitch = (em * 1.35f).toInt()
        val rowPitch = (em * 1.15f).toInt()
        val out = ArrayList<OcrLine>()
        for (k in 0 until columns) {
            val x = right - em - k * colPitch
            for (r in 0 until perColumn) drawGlyph(c, x, top + r * rowPitch, em, glyphStrokes(rnd), ink, haloed)
            val h = perColumn * rowPitch - (rowPitch - em)
            out += OcrLine("這是文字".repeat((perColumn + 3) / 4).take(perColumn), Rect(x, top, x + em, top + h), true)
        }
        return out
    }

    private fun row(c: Canvas, left: Int, top: Int, count: Int, em: Int, ink: Int, haloed: Boolean, rnd: Random): OcrLine {
        val pitch = (em * 1.1f).toInt()
        for (k in 0 until count) drawGlyph(c, left + k * pitch, top, em, glyphStrokes(rnd), ink, haloed)
        val w = count * pitch - (pitch - em)
        return OcrLine("這裡沒有販賣機".repeat((count + 6) / 7).take(count), Rect(left, top, left + w, top + em), false)
    }

    private fun sky(c: Canvas, r: Rect, rnd: Random) {
        c.drawRect(r, Paint().apply {
            shader = LinearGradient(0f, r.top.toFloat(), 0f, r.bottom.toFloat(), Color.rgb(26, 28, 40), Color.rgb(84, 86, 102), Shader.TileMode.CLAMP)
        })
        val star = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 700) {
            val x = r.left + rnd.nextInt(r.width())
            val y = r.top + rnd.nextInt(r.height())
            val s = 1f + rnd.nextFloat() * 2f
            star.color = Color.rgb(200 + rnd.nextInt(56), 200 + rnd.nextInt(56), 210 + rnd.nextInt(46))
            c.drawCircle(x.toFloat(), y.toFloat(), s, star)
        }
        // A few sparkles: thin crosses.
        star.strokeWidth = 2f
        for (i in 0 until 12) {
            val x = (r.left + rnd.nextInt(r.width())).toFloat()
            val y = (r.top + rnd.nextInt(r.height())).toFloat()
            val len = 10f + rnd.nextFloat() * 20f
            c.drawLine(x - len, y, x + len, y, star)
            c.drawLine(x, y - len, x, y + len, star)
        }
    }

    private fun balloon(c: Canvas, box: Rect, columnsN: Int, perColumn: Int, rnd: Random): List<OcrLine> {
        c.drawOval(RectF(box), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        c.drawOval(RectF(box), Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.BLACK })
        val em = 28
        val colPitch = (em * 1.35f).toInt()
        val blockW = columnsN * colPitch - (colPitch - em)
        val rowPitch = (em * 1.15f).toInt()
        val blockH = perColumn * rowPitch - (rowPitch - em)
        return columns(c, box.centerX() + blockW / 2, box.centerY() - blockH / 2, columnsN, perColumn, em, darkInk, false, rnd)
    }

    private fun starryPage(): Page {
        val bmp = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val rnd = Random(42)
        val top = Rect(40, 40, 1040, 1000)
        val middle = Rect(40, 1030, 1040, 1560)
        val bottom = Rect(40, 1590, 1040, 1880)
        for (panel in listOf(top, middle, bottom)) sky(c, panel, rnd)

        val blocks = ArrayList<Block>()
        // The monologue: six columns straight onto the sky, top left.
        blocks += Block(
            "monologue",
            columns(c, 420, 120, 6, 10, 30, lightInk, true, rnd),
            "If I finish this, do I get to just go home? Thanks a lot! I can see my whole life flashing before my eyes right here under the stars. What on earth is going on?!",
        )
        // A second, shorter block well clear of it.
        blocks += Block(
            "aside",
            columns(c, 620, 120, 2, 6, 30, lightInk, true, rnd),
            "Here I am, making my way in an unfamiliar land.",
        )
        // The shout at the top right, large.
        blocks += Block(
            "shout",
            columns(c, 1000, 100, 2, 3, 52, lightInk, true, rnd),
            "Ahhh~ A transfer!??",
        )
        // Three columns in the middle of the top panel.
        blocks += Block(
            "middle",
            columns(c, 690, 560, 3, 8, 28, lightInk, true, rnd),
            "It might not work out... my client lives out here and I missed the last bus.",
        )
        // A horizontal caption row across the middle panel.
        blocks += Block(
            "caption",
            listOf(row(c, 300, 1400, 14, 26, lightInk, true, rnd)),
            "There isn't even a vending machine out here.",
        )
        // Balloons on the sky.
        val a = Rect(560, 1080, 880, 1340)
        val b = Rect(80, 1120, 330, 1330)
        val d = Rect(620, 1620, 980, 1860)
        blocks += Block("balloon-a", balloon(c, a, 3, 6, rnd), "Waaaah, even though I expected it to turn out like this, I'm so exhausted...")
        blocks += Block("balloon-b", balloon(c, b, 2, 5, rnd), "This is... this is practically the end of the world.")
        blocks += Block("balloon-c", balloon(c, d, 3, 6, rnd), "Could there happen to be an oden stand around here?")
        // A small black box with light lettering — 隱約 — beside the last balloon.
        val box = Rect(300, 1600, 390, 1740)
        c.drawRect(box, Paint().apply { color = Color.BLACK })
        c.drawRect(box, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.WHITE })
        blocks += Block("faintly", columns(c, box.centerX() + 14, box.top + 30, 1, 2, 28, lightInk, false, rnd), "Faintly...")
        return Page(bmp, blocks, listOf(a, b, d, box))
    }

    private fun render(page: Page, name: String): Triple<Bitmap, BubbleOverlayView, List<RenderBubble>> {
        val scan = BalloonFinder.analyze(page.bitmap)
        val lines = page.blocks.flatMap { it.lines }
        val bubbles = BubbleGrouper.group(
            lines, pageH, 0, 0, SourceLang.ZH, balloons = scan.balloons.map { it.box }, panels = scan.panels,
        )
        val rendered = bubbles.map { bub ->
            val block = page.blocks.maxBy { blk -> bub.lines.count { l -> blk.lines.any { it.box == l } } }
            RenderPrep.bubble(page.bitmap, bub.box, block.english, bub.text, bub.vertical, bub.kind, scan.balloons, bub.lines)
        }
        val v = BubbleOverlayView(RuntimeEnvironment.getApplication()).apply { layout(0, 0, pageW, pageH) }
        v.setBubbles(rendered)
        val out = page.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        v.draw(Canvas(out))
        ByteArrayOutputStream().use { bos ->
            out.compress(Bitmap.CompressFormat.PNG, 100, bos)
            File(outputDir, name).writeBytes(bos.toByteArray())
        }
        println("wrote ${File(outputDir, name).absolutePath}  (${bubbles.size} regions, ${scan.balloons.size} balloons)")
        return Triple(out, v, rendered)
    }

    private fun luminance(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    @Test
    fun `lettering on a starry sky is wiped into the sky and reset where it was`() {
        val page = starryPage()
        val (out, view, rendered) = render(page, "starry-page.png")
        val px = IntArray(pageW * pageH)
        out.getPixels(px, 0, pageW, 0, 0, pageW, pageH)

        var light = 0
        var dark = 0
        for (c in px) {
            if (isLightInk(c)) light++
            if (isDarkInk(c)) dark++
        }
        assertEquals("no on-art glyph may survive (found $light px)", 0, light)
        assertEquals("no balloon glyph may survive (found $dark px)", 0, dark)

        // Every block was rendered, and each block's English sits on the
        // block it replaces: the text's centre inside the original box
        // (grown a little), not off beside it.
        val rects = view.placedRects()
        assertEquals("one placement per region", rendered.size, rects.size)
        for (block in page.blocks) {
            val rb = rendered.firstOrNull { r -> r.original.isNotEmpty() && r.translated == block.english }
                ?: error("block ${block.name} was not rendered")
            val idx = rendered.indexOf(rb)
            val placed = rects[idx]
            val grown = Rect(block.box).apply { inset(-block.box.width() / 4 - 20, -block.box.height() / 4 - 20) }
            assertTrue(
                "${block.name}: English centred at (${placed.centerX()},${placed.centerY()}) is not on the block ${block.box}",
                grown.contains(placed.centerX(), placed.centerY()),
            )
            assertTrue("${block.name}: placement runs off the screen: $placed", placed.left >= 0 && placed.top >= 0 && placed.right <= pageW && placed.bottom <= pageH)
        }

        // The sky between two columns of the monologue is untouched where
        // no English lies over it.
        val monologue = page.blocks.first { it.name == "monologue" }
        val cols = monologue.lines.map { it.box }.sortedBy { it.left }
        val idx = rendered.indexOfFirst { it.translated == monologue.english }
        val textRect = Rect(view.textRects()[idx]).apply { inset(-6, -6) }
        var checked = 0
        var changed = 0
        for (k in 0 until cols.size - 1) {
            val gapX = (cols[k].right + cols[k + 1].left) / 2
            for (y in cols[k].top + 10 until cols[k].bottom - 10 step 7) {
                if (textRect.contains(gapX, y)) continue
                checked++
                if (out.getPixel(gapX, y) != page.bitmap.getPixel(gapX, y)) changed++
            }
        }
        assertTrue("gap pixels were sampled", checked > 40)
        assertEquals("the sky between columns must survive (changed $changed of $checked)", 0, changed)

        // And the English is there to read: light lettering on the dark sky.
        var lit = 0
        for (y in textRect.top until textRect.bottom) for (x in textRect.left until textRect.right) {
            if (luminance(out.getPixel(x, y)) > 200) lit++
        }
        assertTrue("light lettering must be painted over the monologue (found $lit px)", lit > 300)

        // No two placements pile onto each other.
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            val inter = Rect()
            if (!inter.setIntersect(rects[i], rects[j])) continue
            val overlap = inter.width().toLong() * inter.height()
            val smaller = minOf(rects[i].width().toLong() * rects[i].height(), rects[j].width().toLong() * rects[j].height())
            assertTrue("placements $i and $j majority-overlap ($overlap of $smaller)", overlap * 100 < smaller * 40)
        }
    }

    /** Dark lettering on a light page takes dark type with a light outline, and the same wipe. */
    @Test
    fun `dark lettering on a light gradient is wiped into the gradient`() {
        val bmp = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(Rect(0, 0, pageW, pageH), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, pageH.toFloat(), Color.rgb(250, 240, 225), Color.rgb(200, 215, 240), Shader.TileMode.CLAMP)
        })
        val rnd = Random(7)
        val block = Block("caption", columns(c, 700, 300, 4, 9, 30, darkInk, false, rnd), "The morning after, the town was as quiet as if nothing had happened at all.")
        val page = Page(bmp, listOf(block), emptyList())
        val (out, view, rendered) = render(page, "light-page.png")
        assertEquals(1, rendered.size)
        val px = IntArray(pageW * pageH)
        out.getPixels(px, 0, pageW, 0, 0, pageW, pageH)
        assertEquals("no glyph may survive", 0, px.count { isDarkInk(it) })
        val rect = view.textRects().single()
        var dark = 0
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) if (luminance(out.getPixel(x, y)) < 60) dark++
        assertTrue("dark lettering must be painted on the light page (found $dark px)", dark > 300)
        // The gradient runs through the wiped columns: a wiped pixel near
        // the top of the block is lighter than one near the bottom.
        val col = block.lines[1].box
        val topPx = out.getPixel(col.centerX(), col.top + 6)
        val botPx = out.getPixel(col.centerX(), col.bottom - 6)
        assertTrue("wipe follows the gradient (${Integer.toHexString(topPx)} vs ${Integer.toHexString(botPx)})", Color.red(topPx) > Color.red(botPx) + 4)
    }
}
