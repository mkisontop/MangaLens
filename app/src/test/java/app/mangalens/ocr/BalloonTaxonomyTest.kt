package app.mangalens.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import app.mangalens.overlay.BubbleOverlayView
import app.mangalens.overlay.RenderBubble
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.sin

/**
 * A corpus of balloon shapes as they are actually drawn — every kind a
 * reader meets in a chapter of manga, manhwa or manhua — each on a
 * phone-resolution page with pseudo-CJK lettering of realistic ink density.
 * The detector is scored on every one; the shapes it misses are the
 * balloons that go untranslated, or float a card instead of being cleaned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BalloonTaxonomyTest {

    private val outputDir = File("build/taxonomy-preview").apply { mkdirs() }

    /** Phone capture. */
    private val pageW = 1080
    private val pageH = 1920

    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    private fun stroke(width: Float, color: Int = Color.BLACK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        this.color = color
    }

    /**
     * A pseudo-CJK glyph: a few strokes inside an em box, dense like a
     * kanji or a hangul block. Deterministic per seed.
     */
    private fun glyph(canvas: Canvas, x: Int, y: Int, em: Int, rnd: Random, ink: Paint) {
        val sw = (em * 0.11f).coerceAtLeast(2f)
        val p = Paint(ink).apply { strokeWidth = sw; style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
        val strokes = 3 + rnd.nextInt(4)
        for (s in 0 until strokes) {
            val horizontal = rnd.nextBoolean()
            val a = 0.1f + rnd.nextFloat() * 0.8f
            val from = 0.1f + rnd.nextFloat() * 0.3f
            val to = 0.6f + rnd.nextFloat() * 0.35f
            if (horizontal) {
                canvas.drawLine(x + from * em, y + a * em, x + to * em, y + a * em, p)
            } else {
                canvas.drawLine(x + a * em, y + from * em, x + a * em, y + to * em, p)
            }
        }
    }

    /** Vertical columns of glyphs, right to left, centred in [box]. */
    private fun verticalLettering(canvas: Canvas, box: Rect, columns: Int, perColumn: Int, em: Int, ink: Paint = black, seed: Long = 1) {
        val rnd = Random(seed)
        val colPitch = (em * 1.35f).toInt()
        val rowPitch = (em * 1.15f).toInt()
        val blockW = columns * colPitch - (colPitch - em)
        val blockH = perColumn * rowPitch - (rowPitch - em)
        val right = box.centerX() + blockW / 2
        val top = box.centerY() - blockH / 2
        for (c in 0 until columns) {
            val x = right - em - c * colPitch
            for (r in 0 until perColumn) glyph(canvas, x, top + r * rowPitch, em, rnd, ink)
        }
    }

    /** Horizontal rows of glyphs (hangul / hanzi), centred in [box]. */
    private fun horizontalLettering(canvas: Canvas, box: Rect, rows: Int, perRow: Int, em: Int, ink: Paint = black, seed: Long = 2) {
        val rnd = Random(seed)
        val colPitch = (em * 1.1f).toInt()
        val rowPitch = (em * 1.4f).toInt()
        val blockW = perRow * colPitch - (colPitch - em)
        val blockH = rows * rowPitch - (rowPitch - em)
        val left = box.centerX() - blockW / 2
        val top = box.centerY() - blockH / 2
        for (r in 0 until rows) {
            for (c in 0 until perRow) glyph(canvas, left + c * colPitch, top + r * rowPitch, em, rnd, ink)
        }
    }

    private class Case(
        val name: String,
        val truth: List<Rect>,
        val page: Bitmap,
        val note: String = "",
        /** A shape the detector is allowed to miss: nothing to translate in it anyway. */
        val optional: Boolean = false,
    )

    private fun page(bg: Int = Color.WHITE): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)
        return bmp to c
    }

    private fun ellipse(c: Canvas, box: Rect, outline: Float = 4f, fill: Int = Color.WHITE, outlineColor: Int = Color.BLACK) {
        c.drawOval(RectF(box), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        c.drawOval(RectF(box), stroke(outline, outlineColor))
    }

    private fun roundRect(c: Canvas, box: Rect, r: Float, outline: Float = 4f, fill: Int = Color.WHITE) {
        c.drawRoundRect(RectF(box), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        c.drawRoundRect(RectF(box), r, r, stroke(outline))
    }

    private fun cases(): List<Case> {
        val out = ArrayList<Case>()

        run {
            val (bmp, c) = page()
            val box = Rect(300, 400, 700, 700)
            ellipse(c, box)
            verticalLettering(c, box, 3, 5, 30)
            out += Case("ellipse-vertical", listOf(box), bmp)
        }
        run {
            val (bmp, c) = page()
            val box = Rect(280, 400, 760, 620)
            roundRect(c, box, 22f)
            horizontalLettering(c, box, 3, 8, 30)
            out += Case("rounded-rect-r22", listOf(box), bmp, "webtoon balloon, small corner radius")
        }
        run {
            val (bmp, c) = page()
            val box = Rect(280, 400, 760, 620)
            roundRect(c, box, 60f)
            horizontalLettering(c, box, 3, 8, 30)
            out += Case("rounded-rect-r60", listOf(box), bmp)
        }
        run {
            val (bmp, c) = page()
            val box = Rect(240, 400, 840, 580)
            c.drawRect(box, white)
            c.drawRect(box, stroke(4f))
            horizontalLettering(c, box, 3, 12, 30)
            out += Case("caption-rect", listOf(box), bmp, "sharp-cornered narration box")
        }
        run {
            val (bmp, c) = page()
            val panel = Rect(60, 200, 1020, 1100)
            c.drawRect(panel, stroke(5f))
            // Some art so the panel is not blank.
            c.drawOval(RectF(400f, 500f, 800f, 1000f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 150, 150) })
            val box = Rect(60, 200, 560, 340)
            c.drawRect(box, white)
            c.drawRect(box, stroke(5f))
            horizontalLettering(c, box, 2, 12, 30)
            out += Case("caption-flush-corner", listOf(box), bmp, "caption sharing the panel's corner")
        }
        run {
            val (bmp, c) = page()
            val box = Rect(300, 400, 720, 700)
            // Cloud: bumps around the ellipse, then the interior painted white.
            val path = Path()
            val cx = box.exactCenterX()
            val cy = box.exactCenterY()
            val rx = box.width() / 2f - 20f
            val ry = box.height() / 2f - 20f
            val bumps = 16
            for (i in 0 until bumps) {
                val a = i * 2.0 * Math.PI / bumps
                val bx = (cx + rx * cos(a)).toFloat()
                val by = (cy + ry * sin(a)).toFloat()
                path.addCircle(bx, by, 34f, Path.Direction.CW)
            }
            path.addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW)
            c.drawPath(path, stroke(4f))
            c.drawPath(path, white)
            // The fill is drawn after the stroke so only the outer scallops keep an outline.
            c.drawPath(path, stroke(4f).apply { style = Paint.Style.STROKE })
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            // Re-stroke only the outside: draw the union's stroke by drawing each circle
            // stroke then filling all again would erase — instead draw fill then a
            // single outer stroke via path op.
            val outer = Path(path)
            c.drawPath(outer, stroke(4f))
            c.drawPath(path, white)
            c.drawPath(path, stroke(4f))
            c.drawPath(path, white)
            // Trailing thought circles.
            c.drawCircle(cx - rx - 40, cy + ry + 10, 16f, white); c.drawCircle(cx - rx - 40, cy + ry + 10, 16f, stroke(4f))
            c.drawCircle(cx - rx - 80, cy + ry + 50, 10f, white); c.drawCircle(cx - rx - 80, cy + ry + 50, 10f, stroke(4f))
            verticalLettering(c, box, 2, 5, 30)
            out += Case("thought-cloud", listOf(box), bmp, "scalloped outline")
        }
        run {
            val (bmp, c) = page()
            val box = Rect(300, 400, 720, 700)
            c.drawOval(RectF(box), white)
            c.drawOval(RectF(box), stroke(4f).apply { pathEffect = DashPathEffect(floatArrayOf(16f, 8f), 0f) })
            verticalLettering(c, box, 2, 5, 30)
            out += Case("whisper-dashed", listOf(box), bmp, "dashed outline, 8px gaps")
        }
        run {
            val (bmp, c) = page()
            val box = Rect(300, 400, 720, 700)
            c.drawOval(RectF(box), white)
            c.drawOval(RectF(box), stroke(4f))
            c.drawOval(RectF(box).apply { inset(12f, 12f) }, stroke(3f))
            verticalLettering(c, box, 2, 5, 30)
            out += Case("double-outline", listOf(Rect(box).apply { inset(12, 12) }), bmp)
        }
        run {
            val (bmp, c) = page()
            val box = Rect(280, 400, 760, 720)
            val path = Path()
            val cx = box.exactCenterX()
            val cy = box.exactCenterY()
            val n = 26
            for (i in 0 until n) {
                val a = i * 2.0 * Math.PI / n
                val r = if (i % 2 == 0) 1f else 0.8f
                val x = (cx + r * box.width() / 2f * cos(a)).toFloat()
                val y = (cy + r * box.height() / 2f * sin(a)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            c.drawPath(path, white)
            c.drawPath(path, stroke(4f))
            horizontalLettering(c, box, 2, 6, 34)
            out += Case("jagged-enclosed", listOf(Rect(box)), bmp, "shout balloon with a drawn zigzag outline")
        }
        run {
            val (bmp, c) = page()
            c.drawRect(Rect(60, 200, 1020, 1100), black)
            val box = Rect(300, 400, 720, 700)
            ellipse(c, box)
            verticalLettering(c, box, 2, 5, 30)
            out += Case("on-black-panel", listOf(box), bmp)
        }
        run {
            val (bmp, c) = page()
            c.drawRect(Rect(60, 200, 1020, 1100), Paint().apply { color = Color.rgb(110, 110, 110) })
            val box = Rect(300, 400, 720, 700)
            ellipse(c, box)
            verticalLettering(c, box, 2, 5, 30)
            out += Case("on-grey-panel", listOf(box), bmp)
        }
        run {
            val (bmp, c) = page()
            val rear = Rect(260, 400, 660, 680)
            val front = Rect(520, 520, 900, 800)
            ellipse(c, rear)
            verticalLettering(c, Rect(rear.left, rear.top, rear.left + 250, rear.bottom), 2, 5, 30)
            ellipse(c, front)
            verticalLettering(c, Rect(front.left + 80, front.top, front.right, front.bottom), 2, 5, 30, seed = 5)
            out += Case("occluded-pair", listOf(rear, front), bmp, "front balloon covers the rear's corner")
        }
        run {
            val (bmp, c) = page()
            c.drawRect(Rect(60, 200, 1020, 1000), stroke(5f))
            c.drawRect(Rect(60, 1040, 1020, 1800), stroke(5f))
            val box = Rect(300, 880, 720, 1160)
            ellipse(c, box)
            verticalLettering(c, box, 2, 5, 30)
            out += Case("breaks-panel-border", listOf(box), bmp, "balloon straddling the gutter")
        }
        run {
            val (bmp, c) = page()
            val box = Rect(400, 500, 520, 590)
            ellipse(c, box, 3f)
            verticalLettering(c, box, 1, 2, 26)
            out += Case("tiny-interjection", listOf(box), bmp, "120x90 px")
        }
        run {
            val (bmp, c) = page()
            val box = Rect(480, 300, 600, 900)
            ellipse(c, box)
            verticalLettering(c, box, 1, 12, 30)
            out += Case("tall-thin", listOf(box), bmp)
        }
        run {
            val (bmp, c) = page()
            val box = Rect(200, 500, 880, 610)
            roundRect(c, box, 50f)
            horizontalLettering(c, box, 1, 16, 30)
            out += Case("wide-short", listOf(box), bmp)
        }
        run {
            val (bmp, c) = page()
            val box = Rect(300, 400, 720, 700)
            val tail = Path().apply {
                moveTo(380f, 660f); lineTo(430f, 690f); lineTo(250f, 900f); close()
            }
            c.drawPath(tail, stroke(4f))
            c.drawOval(RectF(box), stroke(4f))
            c.drawPath(tail, white)
            c.drawOval(RectF(box), white)
            // Re-outline the union: stroke both, then fill both again slightly inset.
            c.drawPath(tail, stroke(4f)); c.drawOval(RectF(box), stroke(4f))
            c.drawPath(Path().apply { moveTo(383f, 663f); lineTo(425f, 688f); lineTo(258f, 885f); close() }, white)
            c.drawOval(RectF(box).apply { inset(3f, 3f) }, white)
            verticalLettering(c, box, 2, 5, 30)
            out += Case("long-tail", listOf(box), bmp, "tail 200 px long; truth is the body")
        }
        run {
            val (bmp, c) = page()
            val box = Rect(300, 400, 720, 700)
            ellipse(c, box, 4f, outlineColor = Color.rgb(150, 150, 150))
            verticalLettering(c, box, 2, 5, 30)
            out += Case("grey-outline", listOf(box), bmp, "outline at luminance 150")
        }
        run {
            val (bmp, c) = page()
            val box = Rect(300, 400, 720, 700)
            ellipse(c, box, 1f)
            verticalLettering(c, box, 2, 5, 30)
            out += Case("hairline-1px-phone", listOf(box), bmp)
        }
        run {
            val (bmp, c) = page()
            val box = Rect(300, 400, 720, 700)
            ellipse(c, box)
            verticalLettering(c, box, 4, 8, 30)
            out += Case("dense-text", listOf(box), bmp, "lettering runs to the outline")
        }
        run {
            val (bmp, c) = page()
            val box = Rect(280, 400, 760, 600)
            val tail = Path().apply { moveTo(600f, 596f); lineTo(660f, 596f); lineTo(700f, 700f); close() }
            c.drawRect(box, white); c.drawRect(box, stroke(4f))
            c.drawPath(tail, white); c.drawPath(tail, stroke(4f))
            c.drawRect(Rect(box.left + 3, box.top + 3, box.right - 3, box.bottom - 3), white)
            c.drawPath(Path().apply { moveTo(604f, 592f); lineTo(656f, 592f); lineTo(694f, 690f); close() }, white)
            horizontalLettering(c, box, 3, 10, 30)
            out += Case("manhua-square-tail", listOf(box), bmp, "rectangle with a triangular tail")
        }
        run {
            val (bmp, c) = page()
            val box = Rect(300, 400, 720, 700)
            ellipse(c, box)
            for (i in 0 until 3) c.drawCircle(box.centerX() - 30f + i * 30f, box.centerY().toFloat(), 5f, black)
            out += Case("ellipsis-only", listOf(box), bmp, "just an ellipsis; nothing to translate", optional = true)
        }
        run {
            val (bmp, c) = page()
            val box = Rect(280, 400, 760, 720)
            ellipse(c, box)
            verticalLettering(c, box, 1, 2, 34)
            out += Case("sparse-two-glyphs", listOf(box), bmp, "え？ in a big balloon")
        }
        run {
            val (bmp, c) = page()
            val box = Rect(240, 400, 840, 640)
            c.drawRect(box, black)
            horizontalLettering(c, box, 3, 12, 30, ink = white)
            out += Case("black-narration", listOf(box), bmp)
        }
        run {
            val (bmp, c) = page()
            val box = Rect(280, 400, 760, 640)
            roundRect(c, box, 40f, fill = Color.rgb(240, 180, 195))
            horizontalLettering(c, box, 3, 8, 30)
            out += Case("pastel-rounded-rect", listOf(box), bmp)
        }
        run {
            val (bmp, c) = page()
            val panel = Rect(60, 200, 1020, 1100)
            c.drawRect(panel, stroke(5f))
            val box = Rect(600, 200, 1020, 480)
            ellipse(c, box)
            verticalLettering(c, box, 2, 5, 30)
            out += Case("tangent-to-panel", listOf(box), bmp, "balloon tangent to the panel's top-right corner")
        }
        run {
            val (bmp, c) = page(Color.rgb(120, 150, 200))
            val box = Rect(280, 400, 760, 620)
            c.drawRoundRect(RectF(box), 40f, 40f, white)
            horizontalLettering(c, box, 3, 8, 30)
            out += Case("webtoon-no-outline-on-colour", listOf(box), bmp, "no outline; the colour around it is the wall")
        }
        run {
            val (bmp, c) = page()
            // Noisy art all over the page, balloon on top.
            val rnd = Random(9)
            val p = Paint()
            for (i in 0 until 4000) {
                p.color = Color.rgb(60 + rnd.nextInt(160), 60 + rnd.nextInt(160), 60 + rnd.nextInt(160))
                val x = rnd.nextInt(pageW); val y = 200 + rnd.nextInt(1000)
                c.drawRect(Rect(x, y, x + 12 + rnd.nextInt(30), y + 12 + rnd.nextInt(30)), p)
            }
            val box = Rect(300, 400, 720, 700)
            ellipse(c, box)
            verticalLettering(c, box, 2, 5, 30)
            out += Case("on-busy-colour-art", listOf(box), bmp)
        }
        run {
            val (bmp, c) = page()
            // A stack of two balloons of one speaker joined by a short neck.
            val a = Rect(300, 400, 700, 640)
            val b = Rect(340, 620, 740, 860)
            c.drawOval(RectF(a), stroke(4f)); c.drawOval(RectF(b), stroke(4f))
            c.drawOval(RectF(a).apply { inset(3f, 3f) }, white); c.drawOval(RectF(b).apply { inset(3f, 3f) }, white)
            verticalLettering(c, a, 2, 4, 30)
            verticalLettering(c, b, 2, 4, 30, seed = 7)
            out += Case("joined-vertical-stack", listOf(a, b), bmp)
        }
        run {
            // Tablet capture: same balloon at 1600x2560 with a 2px outline and 40px glyphs.
            val bmp = Bitmap.createBitmap(1600, 2560, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            val box = Rect(500, 600, 1100, 1000)
            ellipse(c, box, 2f)
            verticalLettering(c, box, 3, 5, 40)
            out += Case("tablet-hairline", listOf(box), bmp)
        }
        return out
    }

    private fun iou(a: Rect, b: Rect): Float {
        val ix = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val iy = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (ix <= 0 || iy <= 0) return 0f
        val inter = ix.toLong() * iy
        val union = a.width().toLong() * a.height() + b.width().toLong() * b.height() - inter
        return inter.toFloat() / union
    }

    private fun writePreview(name: String, bmp: Bitmap, found: List<Balloon>) {
        val copy = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val mark = stroke(4f, Color.rgb(230, 0, 140))
        found.forEach { canvas.drawRect(it.box, mark) }
        // Cleaned render of every detection, to eyeball the replacement.
        val v = BubbleOverlayView(RuntimeEnvironment.getApplication()).apply { layout(0, 0, bmp.width, bmp.height) }
        v.setBubbles(found.map { b ->
            RenderBubble(
                box = Rect(b.box), translated = "THIS IS WHAT THE BALLOON SAYS NOW", original = "元",
                bgColor = if (b.inverted) Color.BLACK else Color.WHITE,
                textColor = if (b.inverted) Color.WHITE else Color.BLACK,
                vertical = true, balloon = b,
            )
        })
        v.draw(canvas)
        ByteArrayOutputStream().use { bos ->
            copy.compress(Bitmap.CompressFormat.PNG, 100, bos)
            File(outputDir, "$name.png").writeBytes(bos.toByteArray())
        }
    }

    @Test
    fun `every balloon shape in the corpus is detected`() {
        val report = StringBuilder("\nballoon taxonomy\n")
        val missed = ArrayList<String>()
        for (case in cases()) {
            val t0 = System.nanoTime()
            val found = BalloonFinder.findDetailed(case.page)
            val ms = (System.nanoTime() - t0) / 1_000_000
            writePreview(case.name, case.page, found)
            val scores = case.truth.map { t -> found.maxOfOrNull { iou(it.box, t) } ?: 0f }
            val ok = scores.all { it > 0.55f }
            val extra = found.count { f -> case.truth.none { iou(f.box, it) > 0.3f } }
            if (!ok && !case.optional) missed += case.name
            if (extra > 0) missed += "${case.name} (extra detections)"
            report.append(
                "  %-32s %s  iou=%s  found=%d extra=%d  %dms  %s\n".format(
                    case.name, if (ok) "ok " else if (case.optional) "miss (allowed)" else "MISS",
                    scores.joinToString(",") { "%.2f".format(it) }, found.size, extra, ms, case.note,
                )
            )
        }
        println(report)
        assertTrue(report.toString() + "\nmissed: $missed", missed.isEmpty())
    }
}
