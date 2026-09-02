package app.mangalens.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The panels that defeated the first detector, each drawn as it appears on
 * a real page: two balloons joined into one shape, a hairline outline on a
 * tablet-resolution capture, a balloon the screen edge cuts through, and a
 * white panel full of art that satisfies every enclosure test a balloon
 * does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BalloonHardCasesTest {

    private val outputDir = File("build/balloon-preview").apply { mkdirs() }

    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    private fun outline(width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        color = Color.BLACK
    }

    /** Rows of lettering centred in [box], comfortably inside an ellipse of it. */
    private fun lettering(canvas: Canvas, box: Rect, rows: Int = 3, rowH: Int = 16) {
        val pitch = rowH * 2 + 6
        val blockH = rows * pitch - (pitch - rowH)
        var top = box.centerY() - blockH / 2
        for (r in 0 until rows) {
            canvas.drawRect(Rect(box.centerX() - box.width() / 5, top, box.centerX() + box.width() / 5, top + rowH), black)
            top += pitch
        }
    }

    private fun writePreview(name: String, bmp: Bitmap, found: List<Balloon>) {
        val copy = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.rgb(230, 0, 140)
        }
        found.forEach { canvas.drawRect(it.box, mark) }
        ByteArrayOutputStream().use { bos ->
            copy.compress(Bitmap.CompressFormat.PNG, 100, bos)
            File(outputDir, name).writeBytes(bos.toByteArray())
        }
        println("wrote ${File(outputDir, name).absolutePath}  (${found.size} balloons: ${found.map { it.box }})")
    }

    private fun iou(a: Rect, b: Rect): Float {
        val ix = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val iy = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (ix <= 0 || iy <= 0) return 0f
        val inter = ix.toLong() * iy
        val union = a.width().toLong() * a.height() + b.width().toLong() * b.height() - inter
        return inter.toFloat() / union
    }

    private fun matches(found: Rect, truth: Rect) = iou(found, truth) > 0.55f

    /**
     * Joined balloons: two ellipses whose outlines are drawn first and whose
     * fills are painted over both afterwards, so the arcs inside the overlap
     * vanish and the two interiors run together through a waist — the way a
     * letterer joins one character's consecutive lines.
     */
    @Test
    fun `two balloons drawn joined are found as two`() {
        val bmp = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val a = Rect(150, 200, 450, 400)
        val b = Rect(420, 200, 720, 400)
        val stroke = outline(5f)
        canvas.drawOval(RectF(a), stroke)
        canvas.drawOval(RectF(b), stroke)
        canvas.drawOval(RectF(a).apply { inset(3f, 3f) }, white)
        canvas.drawOval(RectF(b).apply { inset(3f, 3f) }, white)
        lettering(canvas, a)
        lettering(canvas, b)

        val found = BalloonFinder.findDetailed(bmp)
        writePreview("joined.png", bmp, found)

        assertTrue("left balloon must be found on its own (found ${found.map { it.box }})", found.any { matches(it.box, a) })
        assertTrue("right balloon must be found on its own (found ${found.map { it.box }})", found.any { matches(it.box, b) })
        assertTrue(
            "the joined pair must not also be reported as one balloon",
            found.none { it.box.width() > 480 },
        )

        // Each part's mask stops at the waist: the left balloon's mask holds
        // its own lettering and none of the right balloon's.
        val left = found.first { matches(it.box, a) }
        assertTrue("left mask covers its own lettering", inMask(left, a.centerX(), a.centerY()))
        assertFalse("left mask stops short of the right balloon", inMask(left, b.centerX() + 60, b.centerY()))
    }

    private fun inMask(b: Balloon, x: Int, y: Int): Boolean {
        if (!b.box.contains(x, y)) return false
        val cx = ((x - b.box.left).toLong() * b.maskW / b.box.width()).toInt().coerceIn(0, b.maskW - 1)
        val cy = ((y - b.box.top).toLong() * b.maskH / b.box.height()).toInt().coerceIn(0, b.maskH - 1)
        return b.mask[cy * b.maskW + cx]
    }

    /** One balloon with a pronounced waist is still one balloon. */
    @Test
    fun `a single balloon with a gentle waist is not split`() {
        val bmp = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        // Two ellipses overlapping by most of their width: the waist is
        // nearly as wide as the shape, which is a lumpy balloon, not two.
        val a = Rect(200, 200, 520, 400)
        val b = Rect(380, 200, 700, 400)
        val stroke = outline(5f)
        canvas.drawOval(RectF(a), stroke)
        canvas.drawOval(RectF(b), stroke)
        canvas.drawOval(RectF(a).apply { inset(3f, 3f) }, white)
        canvas.drawOval(RectF(b).apply { inset(3f, 3f) }, white)
        lettering(canvas, Rect(200, 200, 700, 400))

        val found = BalloonFinder.findDetailed(bmp)
        writePreview("waist.png", bmp, found)
        assertEquals("one lumpy balloon, got ${found.map { it.box }}", 1, found.size)
        assertTrue(matches(found[0].box, Rect(200, 200, 700, 400)))
    }

    /**
     * A tablet-resolution capture with a two-pixel outline. Averaged down to
     * the analysis grid the outline turns to mid-grey and reads as paper,
     * and the balloon's interior floods into the page; the darkest pixel in
     * each cell keeps it a wall.
     */
    @Test
    fun `a hairline outline on a tablet capture still encloses the balloon`() {
        val bmp = Bitmap.createBitmap(1600, 2560, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val box = Rect(400, 600, 1000, 1000)
        canvas.drawOval(RectF(box), white)
        canvas.drawOval(RectF(box), outline(2f))
        lettering(canvas, box, rows = 4, rowH = 30)

        val found = BalloonFinder.findDetailed(bmp)
        writePreview("hairline.png", bmp, found)

        assertTrue("the balloon at $box must be found (found ${found.map { it.box }})", found.any { matches(it.box, box) })
        assertTrue("the page must not be reported", found.none { it.box.width() > 1200 })
    }

    /**
     * A balloon sliding in from the bottom of the screen, its lower third
     * off-screen. It was rejected outright for touching the frame edge, and
     * rendered as a floating card instead of a cleaned balloon.
     */
    @Test
    fun `a balloon cut by the screen edge is found and marked partial`() {
        val bmp = Bitmap.createBitmap(900, 500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val box = Rect(300, 340, 620, 580)
        canvas.drawOval(RectF(box), white)
        canvas.drawOval(RectF(box), outline(5f))
        // Two rows of lettering in the visible upper part.
        for (r in 0 until 2) {
            val top = 400 + r * 38
            canvas.drawRect(Rect(380, top, 540, top + 16), black)
        }

        val found = BalloonFinder.findDetailed(bmp)
        writePreview("partial.png", bmp, found)

        val visible = Rect(box.left, box.top, box.right, 500)
        val hit = found.firstOrNull { iou(it.box, visible) > 0.5f }
        assertTrue("the cut balloon at $visible must be found (found ${found.map { it.box }})", hit != null)
        assertTrue("a balloon touching the frame edge is partial", hit!!.partial)
        assertTrue("the page background must not be reported", found.none { it.box.width() > 880 })
    }

    /** A white panel full of art is not a balloon, whatever its shape. */
    @Test
    fun `a white panel holding shaded art is not taken for a balloon`() {
        val bmp = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val panel = Rect(60, 60, 840, 520)
        canvas.drawRect(panel, outline(5f))
        // A figure: shaded body, dark hair, a few strokes — the interior
        // around it floods as an enclosed light region with lettering-like
        // ink in it, and only the shading gives the art away.
        val skin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(160, 160, 160) }
        canvas.drawOval(RectF(300f, 120f, 520f, 380f), skin)
        canvas.drawRect(Rect(330, 100, 500, 190), black)
        canvas.drawRect(Rect(340, 380, 480, 510), skin)
        for (i in 0 until 6) canvas.drawRect(Rect(120 + i * 30, 300, 130 + i * 30, 480), black)

        // And an honest balloon elsewhere on the page, so the fixture proves
        // the gate is selective rather than merely strict.
        val balloon = Rect(150, 600, 450, 820)
        canvas.drawOval(RectF(balloon), white)
        canvas.drawOval(RectF(balloon), outline(5f))
        lettering(canvas, balloon)

        val found = BalloonFinder.findDetailed(bmp)
        writePreview("art-panel.png", bmp, found)

        assertTrue("the real balloon must be found (found ${found.map { it.box }})", found.any { matches(it.box, balloon) })
        assertTrue(
            "the art panel must not be reported (found ${found.map { it.box }})",
            found.none { it.box.width() > 600 },
        )
    }
}
