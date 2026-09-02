package app.mangalens.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import app.mangalens.ocr.Balloon
import app.mangalens.pipeline.BalloonFill
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Text set into the balloon's real shape rather than its box: the lines
 * follow the interior row by row, a tail never pulls the block toward it,
 * and a gradient balloon is cleaned with its own paper rather than a flat
 * patch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShapedTypesetTest {

    private val outputDir = File("build/render-preview").apply { mkdirs() }

    private val unit: (String) -> Float = { it.length.toFloat() }

    // ---- Shaper: per-line caps over a unit measure ----

    @Test
    fun `lines are broken to their own caps, not greedily`() {
        // "AA BB" is 5 units; the middle line could take three words (8 of
        // 9) but the last line would then be a two-letter stub. Filling the
        // shape evenly wins.
        val shaper = TypeSet.Shaper("AA BB CC DD EE FF", unit)
        val fit = shaper.fit(floatArrayOf(5f, 9f, 5f))!!
        assertEquals(listOf("AA BB", "CC DD", "EE FF"), fit.lines)
    }

    @Test
    fun `a word wider than its cap does not fit`() {
        val shaper = TypeSet.Shaper("EXTRAORDINARY YES", unit)
        assertNull(shaper.fit(floatArrayOf(6f, 6f)))
        assertEquals(13f, shaper.widestWord)
    }

    @Test
    fun `greedy line count is the fewest lines that can hold the words`() {
        val shaper = TypeSet.Shaper("ONE TWO THREE FOUR FIVE SIX", unit)
        assertEquals(3, shaper.greedyLines(10f))
        assertEquals(1, shaper.greedyLines(100f))
    }

    // ---- BalloonShape: the interior measured row by row ----

    /** An ellipse [mw] x [mh] with a tail hanging off its lower left. */
    private fun tailedMask(mw: Int, mh: Int): BooleanArray {
        val mask = BooleanArray(mw * mh)
        val rx = mw * 0.36f
        val ry = mh * 0.42f
        val cx = mw * 0.6f
        val cy = mh * 0.45f
        for (y in 0 until mh) {
            for (x in 0 until mw) {
                val nx = (x + 0.5f - cx) / rx
                val ny = (y + 0.5f - cy) / ry
                var inside = nx * nx + ny * ny <= 1f
                // Tail: a thin spike from the body's lower left down to the corner.
                val t = (y - cy) / (mh - cy)
                if (t in 0.3f..1f) {
                    val spineX = cx - rx * 0.6f - t * (mw * 0.35f)
                    val halfW = (1f - t) * mw * 0.05f + 1f
                    if (x + 0.5f in spineX - halfW..spineX + halfW) inside = true
                }
                mask[y * mw + x] = inside
            }
        }
        return mask
    }

    @Test
    fun `the body centre ignores the tail and rows narrow toward the ends`() {
        val mw = 120
        val mh = 90
        val shape = BalloonShape.of(tailedMask(mw, mh), mw, mh)!!
        // The tail hangs to the lower left; the body centre must stay on the ellipse.
        assertEquals(mw * 0.6f, shape.centerX, 3f)
        assertEquals(mh * 0.45f, shape.centerY, 3f)
        val middle = shape.capOver(shape.centerY - 2f, shape.centerY + 2f)
        val top = shape.capOver(mh * 0.08f, mh * 0.12f)
        assertTrue("the middle rows are widest ($middle vs $top)", middle > top * 1.6f)
        assertEquals("nothing above the balloon", 0f, shape.capOver(0f, 1f))
        // Symmetric about the centre: a row's span never exceeds twice the nearer side.
        assertTrue(shape.maxSpan <= mw * 0.36f * 2f + 2f)
    }

    // ---- Rendering: the block stays inside the shape ----

    private fun luminance(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    private fun writePreview(name: String, bmp: Bitmap) {
        ByteArrayOutputStream().use { bos ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            File(outputDir, name).writeBytes(bos.toByteArray())
        }
        println("wrote ${File(outputDir, name).absolutePath}")
    }

    @Test
    fun `text in a tall narrow balloon stays inside the ellipse`() {
        val pageW = 600
        val pageH = 900
        val box = Rect(200, 100, 400, 800)
        val mw = 50
        val mh = 175
        val mask = BooleanArray(mw * mh)
        for (y in 0 until mh) for (x in 0 until mw) {
            val nx = (x + 0.5f) / mw * 2f - 1f
            val ny = (y + 0.5f) / mh * 2f - 1f
            mask[y * mw + x] = nx * nx + ny * ny <= 1f
        }
        val balloon = Balloon(box, mw, mh, mask, false)
        val v = BubbleOverlayView(RuntimeEnvironment.getApplication()).apply { layout(0, 0, pageW, pageH) }
        v.setBubbles(
            listOf(
                RenderBubble(
                    box = Rect(box),
                    translated = "SO THIS IS THE PLACE YOU KEPT TALKING ABOUT ALL ALONG",
                    original = "縦長",
                    bgColor = Color.WHITE,
                    textColor = Color.BLACK,
                    vertical = true,
                    balloon = balloon,
                )
            )
        )
        val out = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawColor(Color.rgb(200, 200, 200))
        v.draw(Canvas(out))
        writePreview("tall-balloon.png", out)

        // Every dark pixel — the lettering — lies inside the ellipse, with
        // a margin: nothing pokes out of the narrow top and bottom.
        var text = 0
        var outside = 0
        val px = IntArray(pageW * pageH)
        out.getPixels(px, 0, pageW, 0, 0, pageW, pageH)
        for (y in 0 until pageH) for (x in 0 until pageW) {
            if (luminance(px[y * pageW + x]) > 90) continue
            text++
            val nx = (x + 0.5f - box.exactCenterX()) / (box.width() / 2f)
            val ny = (y + 0.5f - box.exactCenterY()) / (box.height() / 2f)
            if (nx * nx + ny * ny > 0.96f) outside++
        }
        assertTrue("lettering was painted ($text dark px)", text > 200)
        assertEquals("no lettering outside the balloon's interior", 0, outside)
    }

    // ---- Inpainting: a gradient balloon keeps its gradient ----

    @Test
    fun `a gradient balloon is cleaned with its own gradient`() {
        val pageW = 720
        val pageH = 500
        val box = Rect(110, 90, 610, 410)
        val page = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)
        // Pink on the left running to sky blue on the right.
        val grad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                box.left.toFloat(), 0f, box.right.toFloat(), 0f,
                Color.rgb(250, 190, 200), Color.rgb(180, 210, 250), android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawOval(RectF(box), grad)
        canvas.drawOval(RectF(box), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.BLACK
        })
        val ink = Paint().apply { color = Color.rgb(176, 0, 0) }
        for (r in 0 until 3) {
            val top = box.top + 100 + r * 50
            canvas.drawRect(Rect(box.left + 120, top, box.right - 120, top + 22), ink)
        }

        val mw = 100
        val mh = 64
        val mask = BooleanArray(mw * mh)
        for (y in 0 until mh) for (x in 0 until mw) {
            val nx = (x + 0.5f) / mw * 2f - 1f
            val ny = (y + 0.5f) / mh * 2f - 1f
            mask[y * mw + x] = nx * nx + ny * ny <= 1f
        }
        val balloon = Balloon(box, mw, mh, mask, false)
        val fill = BalloonFill.build(page, balloon)
        assertTrue("a gradient balloon gets an inpainted fill", fill != null)

        val v = BubbleOverlayView(RuntimeEnvironment.getApplication()).apply { layout(0, 0, pageW, pageH) }
        v.setBubbles(
            listOf(
                RenderBubble(
                    box = Rect(box),
                    translated = "OKAY",
                    original = "はい",
                    bgColor = Color.rgb(215, 200, 225),
                    textColor = Color.BLACK,
                    vertical = false,
                    balloon = balloon,
                    fill = fill,
                )
            )
        )
        v.draw(canvas)
        writePreview("gradient-balloon.png", page)

        // The lettering is gone, and where it was the paper follows the
        // gradient: pink on the left, blue on the right.
        val px = IntArray(pageW * pageH)
        page.getPixels(px, 0, pageW, 0, 0, pageW, pageH)
        var leftover = 0
        for (c in px) if (Color.red(c) >= 140 && Color.green(c) <= 60 && Color.blue(c) <= 60) leftover++
        assertEquals("no original lettering may survive", 0, leftover)
        // Sampled on the lettering row, under the wiped lettering and out
        // near each end where the gradient is unambiguous.
        val leftPx = page.getPixel(box.left + 70, box.top + 111)
        val rightPx = page.getPixel(box.right - 70, box.top + 111)
        assertTrue("left of the balloon reads pink, got ${Integer.toHexString(leftPx)}", Color.red(leftPx) > Color.blue(leftPx) + 20)
        assertTrue("right of the balloon reads blue, got ${Integer.toHexString(rightPx)}", Color.blue(rightPx) > Color.red(rightPx) + 20)
    }

    @Test
    fun `a flat white balloon gets no inpainted fill`() {
        val page = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)
        val box = Rect(50, 50, 350, 250)
        canvas.drawOval(RectF(box), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.BLACK
        })
        canvas.drawRect(Rect(120, 140, 280, 160), Paint().apply { color = Color.BLACK })
        // The flooded interior: the ellipse inside its outline.
        val mw = 60
        val mh = 40
        val mask = BooleanArray(mw * mh)
        for (y in 0 until mh) for (x in 0 until mw) {
            val nx = (x + 0.5f) / mw * 2f - 1f
            val ny = (y + 0.5f) / mh * 2f - 1f
            mask[y * mw + x] = nx * nx + ny * ny <= 0.92f
        }
        assertNull(BalloonFill.build(page, Balloon(box, mw, mh, mask, false)))
    }
}
