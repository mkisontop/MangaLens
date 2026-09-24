package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import app.mangalens.translate.ItemKind
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Lettering drawn onto synthetic backgrounds, erased, and the patch drawn
 * back over the page: the strokes must be gone, the background they sat on
 * continued, and every pixel the mask leaves out untouched. Before/after
 * previews go to build/eraser-preview/ for judging by eye.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextEraserTest {

    private val outputDir = File("build/eraser-preview").apply { mkdirs() }

    private fun blank(w: Int = 480, h: Int = 320, color: Int = Color.WHITE): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(color) }

    private fun textPaint(size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.DEFAULT_BOLD
    }

    /** Draws [text] with its baseline at [x], [y] and returns the ink's tight bounds. */
    private fun letter(page: Bitmap, text: String, x: Float, y: Float, paint: Paint, outline: Int? = null, stroke: Float = 0f): Rect {
        val canvas = Canvas(page)
        if (outline != null) {
            val o = Paint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeJoin = Paint.Join.ROUND
                color = outline
            }
            canvas.drawText(text, x, y, o)
        }
        canvas.drawText(text, x, y, paint)
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        bounds.offset(x.toInt(), y.toInt())
        val grow = (stroke / 2).toInt() + 1
        bounds.inset(-grow, -grow)
        return bounds
    }

    private fun applied(page: Bitmap, e: Erasure): Bitmap {
        val out = page.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(e.patch, e.rect.left.toFloat(), e.rect.top.toFloat(), null)
        return out
    }

    private fun pixels(b: Bitmap, r: Rect): IntArray {
        val out = IntArray(r.width() * r.height())
        b.getPixels(out, 0, r.width(), r.left, r.top, r.width(), r.height())
        return out
    }

    private fun lum(p: Int) = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000

    private fun preview(name: String, before: Bitmap, after: Bitmap, e: Erasure?) {
        val w = before.width
        val sheet = Bitmap.createBitmap(w * 3 + 16, before.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.MAGENTA)
        c.drawBitmap(before, 0f, 0f, null)
        c.drawBitmap(after, (w + 8).toFloat(), 0f, null)
        val masked = before.copy(Bitmap.Config.ARGB_8888, true)
        if (e != null) {
            val rw = e.rect.width()
            for (i in e.mask.indices) if (e.mask[i]) masked.setPixel(e.rect.left + i % rw, e.rect.top + i / rw, Color.RED)
            val p = Paint().apply { style = Paint.Style.STROKE; color = Color.RED }
            c.drawRect(e.rect, p)
        }
        c.drawBitmap(masked, (2 * w + 16).toFloat(), 0f, null)
        FileOutputStream(File(outputDir, "$name.png")).use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Every pixel outside the mask is exactly what the page had. */
    private fun assertOutsideMaskUntouched(before: Bitmap, after: Bitmap, e: Erasure) {
        val a = pixels(before, e.rect)
        val b = pixels(after, e.rect)
        for (i in a.indices) if (!e.mask[i]) assertEquals("pixel $i outside the mask changed", a[i], b[i])
    }

    private fun Bitmap.copyOf() = copy(Bitmap.Config.ARGB_8888, true)

    @Test
    fun `black text on white paper leaves nothing dark behind`() {
        val page = blank()
        val box = letter(page, "SIDE NOTE", 60f, 170f, textPaint(48f, Color.BLACK))
        val e = TextEraser.erase(page, box, ItemKind.ART_TEXT)
        assertNotNull(e)
        e!!
        val after = applied(page, e)
        preview("flat_black_on_white", page, after, e)
        assertTrue(e.flat)
        assertTrue("busy ${e.busy}", e.busy < 0.1f)
        assertTrue(lum(e.textColor) < 60)
        assertNull(e.outlineColor)
        assertTrue(lum(e.background) > 245)
        val inBox = pixels(after, box)
        assertTrue("dark pixel left", inBox.all { lum(it) > 235 })
        assertOutsideMaskUntouched(page, after, e)
    }

    @Test
    fun `text on a vertical gradient is filled with the gradient`() {
        val page = blank()
        val top = Color.rgb(60, 90, 160)
        val bottom = Color.rgb(230, 200, 120)
        Canvas(page).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, 320f, top, bottom, Shader.TileMode.CLAMP) })
        val clean = page.copyOf()
        val box = letter(page, "Meanwhile", 70f, 190f, textPaint(56f, Color.rgb(20, 20, 20)))
        val e = TextEraser.erase(page, box, ItemKind.NARRATION)!!
        val after = applied(page, e)
        preview("gradient", page, after, e)
        assertTrue(e.flat)
        val want = pixels(clean, box)
        val got = pixels(after, box)
        var worst = 0
        for (i in want.indices) worst = max(worst, TextEraser.dist(want[i], got[i]))
        assertTrue("fill strays $worst from the gradient", worst <= 12)
        assertOutsideMaskUntouched(page, after, e)
    }

    /** Smooth, saturated, never near white or black. */
    private fun colourfulArt(page: Bitmap, seed: Int) {
        val c = Canvas(page)
        c.drawColor(Color.rgb(90, 140, 200))
        val rnd = Random(seed)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(60) {
            p.color = Color.rgb(60 + rnd.nextInt(150), 60 + rnd.nextInt(150), 60 + rnd.nextInt(150))
            c.drawCircle(rnd.nextFloat() * page.width, rnd.nextFloat() * page.height, 10f + rnd.nextFloat() * 50f, p)
        }
    }

    @Test
    fun `white text with a black outline comes off colourful art`() {
        val page = blank()
        colourfulArt(page, 7)
        val box = letter(page, "CRASH", 90f, 200f, textPaint(90f, Color.WHITE), outline = Color.BLACK, stroke = 9f)
        val e = TextEraser.erase(page, box, ItemKind.SFX)!!
        val after = applied(page, e)
        preview("outlined_on_art", page, after, e)
        assertFalse(e.flat)
        assertTrue("fill ${Integer.toHexString(e.textColor)}", lum(e.textColor) > 220)
        assertNotNull(e.outlineColor)
        assertTrue(lum(e.outlineColor!!) < 50)
        val inBox = pixels(after, box)
        val white = inBox.count { minOf(Color.red(it), Color.green(it), Color.blue(it)) > 225 }
        val black = inBox.count { maxOf(Color.red(it), Color.green(it), Color.blue(it)) < 50 }
        assertTrue("white left: $white", white < inBox.size / 200)
        assertTrue("black left: $black", black < inBox.size / 200)
        assertTrue("busy ${e.busy}", e.busy > 0.3f)
        assertOutsideMaskUntouched(page, after, e)
    }

    /** A grey panel with black speed lines converging on a white glow at ([cx], [cy]). */
    private fun radialBurst(page: Bitmap, cx: Float, cy: Float) {
        val c = Canvas(page)
        c.drawColor(Color.rgb(170, 170, 170))
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 2f }
        val rnd = Random(3)
        repeat(90) {
            val a = it * (Math.PI * 2 / 90) + rnd.nextDouble() * 0.03
            val r0 = 120f + rnd.nextFloat() * 30f
            c.drawLine(cx + (cos(a) * r0).toFloat(), cy + (sin(a) * r0 * 0.6f).toFloat(), cx + (cos(a) * 400).toFloat(), cy + (sin(a) * 240).toFloat(), line)
        }
        val glow = Paint().apply {
            shader = RadialGradient(cx, cy, 150f, intArrayOf(Color.WHITE, Color.WHITE, Color.argb(0, 255, 255, 255)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawCircle(cx, cy, 150f, glow)
    }

    @Test
    fun `lettering in a radial glow goes, the speed lines stay`() {
        val page = blank()
        radialBurst(page, 240f, 160f)
        val box = letter(page, "WHAT?!", 150f, 180f, textPaint(52f, Color.BLACK))
        val e = TextEraser.erase(page, box, ItemKind.THOUGHT)!!
        val after = applied(page, e)
        preview("radial_glow", page, after, e)
        val inBox = pixels(after, box)
        assertTrue("dark left in the glow", inBox.count { lum(it) < 150 } < inBox.size / 200)
        // The lines around the glow are art: the ring must keep them all.
        val before = pixels(page, e.rect)
        val now = pixels(after, e.rect)
        val w = e.rect.width()
        val inner = Rect(box).apply { offset(-e.rect.left, -e.rect.top); inset(-6, -6) }
        var lines = 0
        var kept = 0
        for (i in before.indices) {
            if (inner.contains(i % w, i / w) || lum(before[i]) > 80) continue
            lines++
            if (now[i] == before[i]) kept++
        }
        assertTrue("speed lines eaten: kept $kept of $lines", kept >= lines * 0.95)
        assertOutsideMaskUntouched(page, after, e)
    }

    /** White paper under a square dot screen: [period] px apart, radius [r]. */
    private fun screentone(page: Bitmap, period: Float, r: Float, color: Int = Color.rgb(40, 40, 40)) {
        val c = Canvas(page)
        c.drawColor(Color.WHITE)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        var y = 0f
        var row = 0
        while (y < page.height + period) {
            var x = if (row % 2 == 0) 0f else period / 2
            while (x < page.width + period) {
                c.drawCircle(x, y, r, dot)
                x += period
            }
            y += period / 2
            row++
        }
    }

    @Test
    fun `text on screentone is replaced by more screentone, not a smear`() {
        val page = blank()
        screentone(page, 8f, 1.6f)
        val clean = page.copyOf()
        val box = letter(page, "ahh", 150f, 190f, textPaint(80f, Color.BLACK))
        val e = TextEraser.erase(page, box, ItemKind.SFX)!!
        val after = applied(page, e)
        preview("screentone", page, after, e)
        // No stroke survives: nothing as solidly dark as a 4x4 run of ink.
        val w = box.width()
        val inBox = pixels(after, box)
        var solid = 0
        for (y in 0 until box.height() - 4) for (x in 0 until w - 4) {
            var all = true
            for (dy in 0..3) for (dx in 0..3) if (lum(inBox[(y + dy) * w + x + dx]) > 110) all = false
            if (all) solid++
        }
        assertEquals("ink left", 0, solid)
        // The filled area carries the tone's texture and level, not a flat grey.
        val want = pixels(clean, e.rect)
        val got = pixels(after, e.rect)
        val a = ArrayList<Int>()
        val b = ArrayList<Int>()
        for (i in want.indices) if (e.mask[i]) { a += lum(want[i]); b += lum(got[i]) }
        fun spread(v: List<Int>): Double {
            val m = v.average()
            return Math.sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
        }
        assertTrue("tone level ${b.average()} vs ${a.average()}", abs(a.average() - b.average()) < 20)
        assertTrue("tone flattened: spread ${spread(b)} vs ${spread(a)}", spread(b) > spread(a) * 0.5)
        assertOutsideMaskUntouched(page, after, e)
    }

    @Test
    fun `art in the lettering's own colour is not swallowed`() {
        val page = blank()
        val c = Canvas(page)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 5f }
        c.drawLine(20f, 300f, 460f, 200f, ink)
        c.drawCircle(345f, 110f, 34f, ink)
        val art = page.copyOf()
        val box = letter(page, "whisper", 100f, 160f, textPaint(46f, Color.BLACK))
        box.right = max(box.right, 330)
        TextEraser.trace = { println("TR $it") }
        TextEraser.erase(page, box, ItemKind.ART_TEXT)
        TextEraser.trace = null
        val e = TextEraser.erase(page, box, ItemKind.ART_TEXT)!!
        val after = applied(page, e)
        preview("art_same_colour", page, after, e)
        val w = e.rect.width()
        val artPx = pixels(art, e.rect)
        var eaten = 0
        for (i in artPx.indices) if (lum(artPx[i]) < 100 && e.mask[i]) { eaten++; if (eaten < 5) println("eaten at ${e.rect.left + i % w},${e.rect.top + i / w} box=$box rect=${e.rect}") }
        assertEquals("art pixels erased", 0, eaten)
        val inBox = pixels(after, box)
        val dark = inBox.count { lum(it) < 120 }
        val artInBox = pixels(art, box).count { lum(it) < 120 }
        assertTrue("text left: $dark dark vs $artInBox art", dark <= artInBox + inBox.size / 200)
        assertOutsideMaskUntouched(page, after, e)
    }

    @Test
    fun `nothing to erase gives null`() {
        val page = blank()
        assertNull(TextEraser.erase(page, Rect(100, 100, 220, 160), ItemKind.ART_TEXT))
        assertNull(TextEraser.erase(page, Rect(-50, -50, -10, -10), ItemKind.ART_TEXT))
    }

    @Test
    fun `a model colour the pixels do not bear out is measured instead`() {
        val page = blank()
        colourfulArt(page, 11)
        val box = letter(page, "BANG", 110f, 200f, textPaint(90f, Color.rgb(250, 230, 40)), outline = Color.rgb(120, 0, 0), stroke = 8f)
        val e = TextEraser.erase(page, box, ItemKind.SFX, textColor = Color.rgb(0, 200, 255))!!
        preview("wrong_model_colour", page, applied(page, e), e)
        assertTrue("fill ${Integer.toHexString(e.textColor)}", TextEraser.dist(e.textColor, Color.rgb(250, 230, 40)) < 40)
    }

    @Test
    fun `erasing a region is fast`() {
        val page = blank(900, 600)
        colourfulArt(page, 5)
        val box = letter(page, "RUMBLE", 300f, 330f, textPaint(80f, Color.WHITE), outline = Color.BLACK, stroke = 8f)
        val flatPage = blank(900, 600)
        val flatBox = letter(flatPage, "Later that day", 300f, 330f, textPaint(48f, Color.BLACK))
        val tone = blank(900, 600)
        screentone(tone, 7f, 1.5f)
        val toneBox = letter(tone, "doki doki", 300f, 330f, textPaint(54f, Color.BLACK))
        // Thread CPU time: the build machine is shared, and wall time
        // there measures the neighbours as much as the eraser. The
        // management API is not in android.jar, but it is in the JVM.
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val cpuTime = Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime")
        val cpu = object { val currentThreadCpuTime: Long get() = cpuTime.invoke(bean) as Long }
        fun time(p: Bitmap, b: Rect, kind: ItemKind): Double {
            repeat(10) { TextEraser.erase(p, b, kind) }
            val t = LongArray(21)
            for (k in t.indices) {
                val s = cpu.currentThreadCpuTime
                TextEraser.erase(p, b, kind)
                t[k] = cpu.currentThreadCpuTime - s
            }
            t.sort()
            return t[t.size / 2] / 1e6
        }
        val busy = time(page, box, ItemKind.SFX)
        val flat = time(flatPage, flatBox, ItemKind.NARRATION)
        val toned = time(tone, toneBox, ItemKind.SFX)
        println("erase() median CPU: busy art ${"%.2f".format(busy)} ms (box ${box.width()}x${box.height()}), flat ${"%.2f".format(flat)} ms (${flatBox.width()}x${flatBox.height()}), screentone ${"%.2f".format(toned)} ms (${toneBox.width()}x${toneBox.height()})")
        assertTrue("busy $busy ms", busy < 60)
        assertTrue("flat $flat ms", flat < 60)
        assertTrue("screentone $toned ms", toned < 60)
    }
}
