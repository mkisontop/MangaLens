package app.mangalens.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import app.mangalens.ocr.Balloon
import app.mangalens.ocr.BubbleKind
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Free lettering and letter styles, drawn against synthetic pages and read
 * back: the English lands where the original was, in its colours and
 * outline, with no card behind it, and everything painted is inside the
 * rectangles the capture loop masks.
 *
 * Original lettering is drawn in a dark red nothing rendered uses, so a
 * surviving pixel of it proves the erasure failed; patches here restore
 * exactly the art the test drew, standing in for the real eraser.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LetteringRenderTest {

    private val outputDir = File("build/render-preview").apply { mkdirs() }

    private val pageW = 720
    private val pageH = 1000
    private val letteringInk = Color.rgb(176, 0, 0)

    private fun view(): BubbleOverlayView =
        BubbleOverlayView(RuntimeEnvironment.getApplication()).apply { layout(0, 0, pageW, pageH) }

    private fun luminance(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    private fun isLetteringInk(c: Int) = Color.red(c) >= 140 && Color.green(c) <= 60 && Color.blue(c) <= 60

    private fun pixels(bmp: Bitmap): IntArray {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return px
    }

    private fun writePreview(name: String, bmp: Bitmap) {
        ByteArrayOutputStream().use { bos ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            File(outputDir, name).writeBytes(bos.toByteArray())
        }
        println("wrote ${File(outputDir, name).absolutePath}")
    }

    /** Colourful blotchy art: blocks of random hue, the worst case for plain text. */
    private fun noiseArt(canvas: Canvas, area: Rect, seed: Int) {
        val rnd = Random(seed)
        val p = Paint()
        val cell = 14
        for (y in area.top until area.bottom step cell) {
            for (x in area.left until area.right step cell) {
                p.color = Color.rgb(rnd.nextInt(40, 220), rnd.nextInt(40, 220), rnd.nextInt(40, 220))
                canvas.drawRect(
                    x.toFloat(), y.toFloat(),
                    minOf(x + cell, area.right).toFloat(), minOf(y + cell, area.bottom).toFloat(), p,
                )
            }
        }
    }

    /**
     * A patch for [box] that restores [clean] wherever [dirty] differs from
     * it: opaque over the original lettering, transparent everywhere else,
     * exactly what the eraser hands over.
     */
    private fun patchFor(clean: Bitmap, dirty: Bitmap, box: Rect): Bitmap {
        val w = box.width()
        val h = box.height()
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        clean.getPixels(a, 0, w, box.left, box.top, w, h)
        dirty.getPixels(b, 0, w, box.left, box.top, w, h)
        val out = IntArray(w * h)
        for (i in out.indices) if (a[i] != b[i]) out[i] = a[i] or (0xFF shl 24)
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun copy(bmp: Bitmap): Bitmap = bmp.copy(Bitmap.Config.ARGB_8888, true)

    /** Lettering stand-in: rows of blocks, like glyphs, across or down [box]. */
    private fun drawLettering(canvas: Canvas, box: Rect, vertical: Boolean, fill: Int, outline: Int? = null) {
        val ink = Paint().apply { color = fill }
        val edge = outline?.let { c -> Paint().apply { color = c } }
        val g = 18
        val rows = mutableListOf<Rect>()
        if (vertical) {
            var x = box.right - g - 4
            while (x >= box.left + 4) {
                var y = box.top + 4
                while (y + g <= box.bottom - 4) {
                    rows.add(Rect(x + 2, y + 2, x + g - 2, y + g - 2))
                    y += g + 2
                }
                x -= g + 6
            }
        } else {
            var y = box.top + 4
            while (y + g <= box.bottom - 4) {
                var x = box.left + 4
                while (x + g <= box.right - 4) {
                    rows.add(Rect(x + 2, y + 2, x + g - 2, y + g - 2))
                    x += g + 2
                }
                y += g + 6
            }
        }
        for (r in rows) {
            edge?.let { canvas.drawRect(Rect(r.left - 3, r.top - 3, r.right + 3, r.bottom + 3), it) }
            canvas.drawRect(r, ink)
        }
    }

    /** The bounding box of pixels matching [pred] in [area], or null. */
    private fun bbox(bmp: Bitmap, area: Rect, pred: (Int) -> Boolean): Rect? {
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = Int.MIN_VALUE
        var b = Int.MIN_VALUE
        for (y in maxOf(0, area.top) until minOf(bmp.height, area.bottom)) {
            for (x in maxOf(0, area.left) until minOf(bmp.width, area.right)) {
                if (!pred(bmp.getPixel(x, y))) continue
                l = minOf(l, x)
                t = minOf(t, y)
                r = maxOf(r, x + 1)
                b = maxOf(b, y + 1)
            }
        }
        return if (l == Int.MAX_VALUE) null else Rect(l, t, r, b)
    }

    private fun count(bmp: Bitmap, area: Rect, pred: (Int) -> Boolean): Int {
        var n = 0
        for (y in maxOf(0, area.top) until minOf(bmp.height, area.bottom)) {
            for (x in maxOf(0, area.left) until minOf(bmp.width, area.right)) {
                if (pred(bmp.getPixel(x, y))) n++
            }
        }
        return n
    }

    /** The overlay alone, on a transparent canvas. */
    private fun overlayOnly(v: BubbleOverlayView): Bitmap {
        val out = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(out))
        return out
    }

    private fun assertRectsCoverOverlay(v: BubbleOverlayView, what: String) {
        val overlay = overlayOnly(v)
        val rects = v.placedRects()
        var uncovered = 0
        var first: String? = null
        val px = pixels(overlay)
        for (y in 0 until pageH) {
            for (x in 0 until pageW) {
                if (Color.alpha(px[y * pageW + x]) == 0) continue
                if (rects.none { it.contains(x, y) }) {
                    uncovered++
                    if (first == null) first = "($x,$y)"
                }
            }
        }
        assertEquals("$what: every painted pixel must be inside placedRects (first miss $first)", 0, uncovered)
    }

    private fun ellipseBalloon(box: Rect, mw: Int = 100, mh: Int = 70): Balloon {
        val mask = BooleanArray(mw * mh)
        for (y in 0 until mh) for (x in 0 until mw) {
            val nx = (x + 0.5f) / mw * 2f - 1f
            val ny = (y + 0.5f) / mh * 2f - 1f
            mask[y * mw + x] = nx * nx + ny * ny <= 1f
        }
        return Balloon(box, mw, mh, mask, false)
    }

    private fun rectBalloon(box: Rect, mw: Int = 80, mh: Int = 50): Balloon =
        Balloon(box, mw, mh, BooleanArray(mw * mh) { true }, false)

    private fun drawBalloon(canvas: Canvas, box: Rect, oval: Boolean = true) {
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.BLACK
        }
        if (oval) {
            canvas.drawOval(RectF(box), white)
            canvas.drawOval(RectF(box), outline)
        } else {
            canvas.drawRect(box, white)
            canvas.drawRect(box, outline)
        }
    }

    // ---- free lettering ----

    @Test
    fun `free text on flat paper is erased and lettered with no card`() {
        val clean = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val box = Rect(220, 300, 500, 360)
        val page = copy(clean)
        drawLettering(Canvas(page), box, vertical = false, fill = letteringInk)
        val patch = patchFor(clean, page, box)

        val v = view()
        v.setBubbles(
            listOf(
                RenderBubble(
                    box = Rect(box),
                    translated = "Three years later, at the summit of the mountain.",
                    original = "三年後、山の頂にて。",
                    bgColor = Color.WHITE,
                    textColor = Color.BLACK,
                    vertical = false,
                    style = LetterStyle.NARRATION,
                    patch = patch,
                    patchRect = Rect(box),
                )
            )
        )
        v.draw(Canvas(page))
        writePreview("free-flat.png", page)

        assertEquals("no original lettering survives", 0, pixels(page).count(::isLetteringInk))
        val text = bbox(page, Rect(0, 0, pageW, pageH)) { luminance(it) < 90 }
        assertTrue("English was lettered", text != null)
        text!!
        assertEquals("centred on the original", box.exactCenterX(), text.exactCenterX(), 14f)
        assertEquals("centred on the original", box.exactCenterY(), text.exactCenterY(), 14f)
        assertTrue("kept near the original's measure: $text vs $box", text.width() <= box.width() * 1.35f)
        assertTrue("not blown up past the original: $text vs $box", text.height() <= box.height() * 1.8f)

        // No card: the overlay paints nothing light outside the patch's own pixels.
        val overlay = overlayOnly(v)
        var cardPixels = 0
        for (y in 0 until pageH) for (x in 0 until pageW) {
            val c = overlay.getPixel(x, y)
            if (Color.alpha(c) == 0 || luminance(c) < 200) continue
            val inPatch = box.contains(x, y) && Color.alpha(patch.getPixel(x - box.left, y - box.top)) != 0
            if (!inPatch) cardPixels++
        }
        assertEquals("no card fill around free lettering", 0, cardPixels)
        assertRectsCoverOverlay(v, "flat free text")
    }

    @Test
    fun `outlined lettering on busy art keeps its colours and outline`() {
        val clean = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        noiseArt(Canvas(clean), Rect(0, 0, pageW, pageH), 7)
        val box = Rect(140, 420, 580, 500)
        val page = copy(clean)
        drawLettering(Canvas(page), box, vertical = false, fill = Color.WHITE, outline = Color.BLACK)
        val patch = patchFor(clean, page, box)

        val v = view()
        v.setBubbles(
            listOf(
                RenderBubble(
                    box = Rect(box),
                    translated = "The heavens shift, and mountains quake!",
                    original = "天地変色、山河震動！",
                    bgColor = Color.rgb(140, 140, 140),
                    textColor = Color.WHITE,
                    vertical = false,
                    style = LetterStyle.ART,
                    patch = patch,
                    patchRect = Rect(box),
                    outlineColor = Color.BLACK,
                )
            )
        )
        v.draw(Canvas(page))
        writePreview("free-outlined-art.png", page)

        val area = Rect(box).apply { inset(-40, -40) }
        val white = count(page, area) { Color.red(it) > 235 && Color.green(it) > 235 && Color.blue(it) > 235 }
        val black = count(page, area) { luminance(it) < 30 }
        assertTrue("white fill lettered ($white px)", white > 400)
        assertTrue("black outline around it ($black px)", black > white / 3)
        // The noise art never goes near white or black, so every such pixel is
        // ours, and each white one must sit inside a black ring: walk right
        // from a white pixel and meet black before any art.
        var ringed = 0
        var probes = 0
        for (y in area.top until area.bottom step 3) for (x in area.left until area.right step 3) {
            val c = page.getPixel(x, y)
            if (!(Color.red(c) > 235 && Color.green(c) > 235 && Color.blue(c) > 235)) continue
            probes++
            var xx = x
            while (xx < area.right && luminance(page.getPixel(xx, y)) > 200) xx++
            // Past the anti-aliased edge, the outline.
            if ((xx until minOf(xx + 3, area.right)).any { luminance(page.getPixel(it, y)) < 60 }) ringed++
        }
        assertTrue("white letters are ringed by their outline ($ringed of $probes)", ringed * 10 >= probes * 8)
        assertRectsCoverOverlay(v, "outlined art text")
    }

    @Test
    fun `a vertical narration column becomes a horizontal block centred on it`() {
        val clean = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val c = Canvas(clean)
        c.drawColor(Color.rgb(226, 232, 238))
        // Soft art: a few big shapes, light enough that black lettering reads.
        val shape = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 212, 225) }
        c.drawCircle(300f, 300f, 180f, shape)
        c.drawCircle(520f, 620f, 140f, shape)
        val column = Rect(470, 140, 520, 560)
        val page = copy(clean)
        drawLettering(Canvas(page), column, vertical = true, fill = letteringInk)
        val patch = patchFor(clean, page, column)

        val v = view()
        v.setBubbles(
            listOf(
                RenderBubble(
                    box = Rect(column),
                    translated = "As the years went by, he grew into a figure whose name everyone knew.",
                    original = "年月が経ち、彼は誰もが名を知る人物へと成長した。",
                    bgColor = Color.rgb(222, 229, 236),
                    textColor = Color.BLACK,
                    vertical = true,
                    style = LetterStyle.NARRATION,
                    patch = patch,
                    patchRect = Rect(column),
                )
            )
        )
        v.draw(Canvas(page))
        writePreview("free-vertical-column.png", page)

        assertEquals("the column's lettering is erased", 0, pixels(page).count(::isLetteringInk))
        val text = bbox(page, Rect(0, 0, pageW, pageH)) { luminance(it) < 90 }!!
        assertEquals("centred on the column", column.exactCenterX(), text.exactCenterX(), 16f)
        assertTrue("wider than the column to read across: $text", text.width() > column.width() * 2)
        assertTrue("a readable measure, not a banner: $text", text.width() < pageW * 0.65f)
        assertTrue("no taller than the column: $text", text.height() <= column.height() * 1.1f)
        assertRectsCoverOverlay(v, "vertical column")
    }

    @Test
    fun `a sound effect over art fills its box, outlined, with no box behind it`() {
        val clean = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        noiseArt(Canvas(clean), Rect(0, 0, pageW, pageH), 11)
        val box = Rect(120, 600, 520, 760)
        val page = copy(clean)
        drawLettering(Canvas(page), box, vertical = false, fill = letteringInk)
        val patch = patchFor(clean, page, box)
        val orange = Color.rgb(248, 119, 41)

        val v = view()
        v.setBubbles(
            listOf(
                RenderBubble(
                    box = Rect(box),
                    translated = "*boom*",
                    original = "쾅",
                    bgColor = Color.rgb(140, 140, 140),
                    textColor = orange,
                    vertical = false,
                    kind = BubbleKind.SFX,
                    patch = patch,
                    patchRect = Rect(box),
                    outlineColor = Color.WHITE,
                )
            )
        )
        v.draw(Canvas(page))
        writePreview("free-sfx.png", page)

        val overlay = overlayOnly(v)
        val isOrange = { c: Int ->
            Color.alpha(c) > 200 && Math.abs(Color.red(c) - 248) < 20 &&
                Math.abs(Color.green(c) - 119) < 25 && Math.abs(Color.blue(c) - 41) < 25
        }
        val ink = bbox(overlay, Rect(0, 0, pageW, pageH), isOrange)!!
        assertTrue(
            "the effect fills its box ($ink in $box)",
            ink.width() >= box.width() * 0.7f || ink.height() >= box.height() * 0.7f,
        )
        val outline = count(overlay, Rect(ink).apply { inset(-30, -30) }) {
            Color.alpha(it) > 200 && luminance(it) > 245
        }
        assertTrue("white outline drawn ($outline px)", outline > 300)
        // Nothing dark and translucent anywhere: the old caption box is gone.
        val caption = count(overlay, Rect(0, 0, pageW, pageH)) { Color.alpha(it) in 1..230 && luminance(it) < 60 }
        assertTrue("no dark caption box ($caption px)", caption < 50)
        assertRectsCoverOverlay(v, "sfx")
    }

    @Test
    fun `a sound drawn down a long column is lettered down it, inside the erased strip`() {
        val clean = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        noiseArt(Canvas(clean), Rect(0, 0, pageW, pageH), 11)
        val column = Rect(500, 200, 560, 620)
        val page = copy(clean)
        drawLettering(Canvas(page), column, vertical = true, fill = letteringInk)
        val patch = patchFor(clean, page, column)
        val v = view()
        v.setBubbles(
            listOf(
                RenderBubble(
                    box = Rect(column),
                    translated = "*ba-dump ba-dump ba-dump ba-dump*",
                    original = "ドキドキドキドキ",
                    bgColor = Color.rgb(140, 140, 140),
                    textColor = Color.rgb(20, 20, 24),
                    vertical = true,
                    kind = BubbleKind.SFX,
                    patch = patch,
                    patchRect = Rect(column),
                    outlineColor = Color.WHITE,
                )
            )
        )
        v.draw(Canvas(page))
        writePreview("free-sfx-column.png", page)
        val text = v.placedRects().single()
        val ink = bbox(overlayOnly(v), Rect(0, 0, pageW, pageH)) { Color.alpha(it) > 200 && luminance(it) < 60 }!!
        assertTrue("the English runs down the column ($ink)", ink.height() > ink.width() * 2)
        assertTrue(
            "and stays on the erased strip, not the art beside it ($ink vs $column)",
            ink.left >= column.left - column.width() / 4 && ink.right <= column.right + column.width() / 4,
        )
        assertTrue("within the column's length ($ink vs $column)", ink.top >= column.top - 8 && ink.bottom <= column.bottom + 8)
        assertTrue("and fills it (${ink.height()} of ${column.height()})", ink.height() >= column.height() * 0.6f)
        assertTrue(text.contains(ink))
        assertRectsCoverOverlay(v, "sfx column")
    }

    @Test
    fun `a sound effect without a patch is lettered over its box, not captioned`() {
        val box = Rect(200, 300, 460, 400)
        val v = view()
        v.setBubbles(
            listOf(
                RenderBubble(
                    box = Rect(box),
                    translated = "BAM",
                    original = "ドン",
                    bgColor = Color.WHITE,
                    textColor = Color.BLACK,
                    vertical = false,
                    kind = BubbleKind.SFX,
                )
            )
        )
        val page = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        Canvas(page).drawColor(Color.rgb(128, 128, 128))
        v.draw(Canvas(page))
        writePreview("sfx-no-patch.png", page)

        val dark = bbox(page, Rect(0, 0, pageW, pageH)) { luminance(it) < 40 }!!
        assertTrue("lettered big over its box: $dark", dark.height() >= box.height() * 0.6f)
        // Around the letters the grey page shows: no box behind them.
        val corner = page.getPixel(box.left + 2, box.top + 2)
        assertEquals("the page shows at the box corner", Color.rgb(128, 128, 128), corner)
        assertRectsCoverOverlay(v, "sfx without patch")
    }

    // ---- styles inside balloons ----

    private fun balloonBubble(
        box: Rect,
        text: String,
        style: LetterStyle,
        balloon: Balloon = ellipseBalloon(box),
        textColor: Int = 0xFF17181C.toInt(),
    ) = RenderBubble(
        box = Rect(box),
        translated = text,
        original = "元のセリフ",
        bgColor = Color.WHITE,
        textColor = textColor,
        vertical = true,
        style = style,
        balloon = balloon,
    )

    private fun darkInsideEllipse(bmp: Bitmap, box: Rect): Pair<Int, Int> {
        var inside = 0
        var outside = 0
        // Near enough to catch overflow, far enough to miss the neighbours' outlines.
        val area = Rect(box).apply { inset(-14, -14) }
        for (y in maxOf(0, area.top) until minOf(pageH, area.bottom)) for (x in maxOf(0, area.left) until minOf(pageW, area.right)) {
            if (luminance(bmp.getPixel(x, y)) > 90) continue
            val nx = (x + 0.5f - box.exactCenterX()) / (box.width() / 2f)
            val ny = (y + 0.5f - box.exactCenterY()) / (box.height() / 2f)
            // The balloon's own outline stroke sits at the rim; count only text.
            val d = nx * nx + ny * ny
            if (d < 0.8f) inside++ else if (d > 1.15f) outside++
        }
        return inside to outside
    }

    @Test
    fun `balloon styles each get their own treatment`() {
        val page = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.rgb(236, 236, 236))
        val shout = Rect(40, 40, 340, 260)
        val dialogue = Rect(380, 40, 680, 260)
        val thought = Rect(40, 300, 340, 520)
        val narration = Rect(380, 320, 680, 480)
        val coloured = Rect(40, 560, 340, 780)
        val german = Rect(420, 560, 620, 700)
        for (r in listOf(shout, dialogue, thought, coloured, german)) drawBalloon(canvas, r)
        drawBalloon(canvas, narration, oval = false)
        val red = Color.rgb(200, 24, 40)
        val v = view()
        v.setBubbles(
            listOf(
                balloonBubble(shout, "GET OUT OF MY WAY!!", LetterStyle.SHOUT),
                balloonBubble(dialogue, "GET OUT OF MY WAY!!", LetterStyle.DIALOGUE),
                balloonBubble(thought, "Why does he always look at me like that...?", LetterStyle.THOUGHT),
                balloonBubble(
                    narration,
                    "Meanwhile, on the other side of the city.",
                    LetterStyle.NARRATION,
                    balloon = rectBalloon(narration),
                ),
                balloonBubble(coloured, "Welcome, dear guests!", LetterStyle.DIALOGUE, textColor = red),
                balloonBubble(german, "Donaudampfschifffahrtsgesellschaftskapitän!", LetterStyle.DIALOGUE),
            )
        )
        v.draw(canvas)
        writePreview("balloon-styles.png", page)

        val (shoutInk, shoutOut) = darkInsideEllipse(page, shout)
        val (dialogueInk, dialogueOut) = darkInsideEllipse(page, dialogue)
        assertEquals("shout stays inside its balloon", 0, shoutOut)
        assertEquals("dialogue stays inside its balloon", 0, dialogueOut)
        assertTrue("a shout is heavier than dialogue ($shoutInk vs $dialogueInk px)", shoutInk > dialogueInk * 1.15f)

        val (thoughtInk, thoughtOut) = darkInsideEllipse(page, thought)
        assertTrue("thought lettered", thoughtInk > 200)
        assertEquals("thought stays inside its balloon", 0, thoughtOut)

        val narrationText = bbox(page, Rect(narration).apply { inset(8, 8) }) { luminance(it) < 90 }!!
        assertTrue("caption text keeps off the box edges: $narrationText", Rect(narration).apply { inset(10, 10) }.contains(narrationText))

        val reds = count(page, coloured) { Color.red(it) > 150 && Color.green(it) < 90 && Color.blue(it) < 110 }
        val blacks = count(page, Rect(coloured).apply { inset(40, 40) }) { luminance(it) < 40 }
        assertTrue("coloured lettering keeps its colour ($reds red px)", reds > 150)
        assertTrue("and is not repainted black ($blacks black px)", blacks < 20)

        val (germanInk, germanOut) = darkInsideEllipse(page, german)
        assertTrue("the long word is lettered ($germanInk px)", germanInk > 150)
        assertEquals("the long word is hyphenated rather than overflowing", 0, germanOut)
        assertRectsCoverOverlay(v, "balloon styles")
    }

    @Test
    fun `two columns of thoughts side by side keep a lane each`() {
        // izumi: two vertical thoughts a gap apart over the art. Set wider
        // than its column, as free English is, each ran onto the other.
        val clean = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        noiseArt(Canvas(clean), Rect(0, 0, pageW, pageH), 5)
        val page = copy(clean)
        val right = Rect(400, 220, 460, 600)
        val left = Rect(320, 220, 385, 640)
        for (b in listOf(right, left)) drawLettering(Canvas(page), b, vertical = true, fill = Color.BLACK, outline = Color.WHITE)
        val texts = listOf("Should I say \"hello\"? Would that work?", "Since it's my first day, maybe \"nice to meet you\"?")
        val v = view()
        v.setBubbles(listOf(right, left).mapIndexed { i, b ->
            RenderBubble(
                box = Rect(b), translated = texts[i], original = "説「你好」？行嗎？", bgColor = Color.GRAY,
                textColor = Color.BLACK, vertical = true, style = LetterStyle.THOUGHT,
                patch = patchFor(clean, page, b), patchRect = Rect(b), outlineColor = Color.WHITE,
            )
        })
        v.draw(Canvas(page))
        writePreview("columns-side-by-side.png", page)
        val (a, b) = v.placements().map { it.second }
        val overlap = maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left)) * maxOf(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        assertTrue("the two blocks stay apart: $a / $b", overlap < minOf(a.width() * a.height(), b.width() * b.height()) * 0.02f)
        assertTrue("each still over its own column", a.centerX() > b.centerX())
    }

    @Test
    fun `placed rects cover every painted pixel of a mixed page`() {
        val clean = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        noiseArt(Canvas(clean), Rect(0, 0, pageW, pageH), 3)
        val page = copy(clean)
        val boxes = listOf(Rect(20, 20, 300, 80), Rect(640, 100, 700, 420), Rect(100, 500, 600, 640), Rect(500, 800, 715, 995))
        for ((i, b) in boxes.withIndex()) drawLettering(Canvas(page), b, vertical = i == 1, fill = Color.WHITE, outline = Color.BLACK)
        val styles = listOf(LetterStyle.NARRATION, LetterStyle.DIALOGUE, LetterStyle.SFX, LetterStyle.ART)
        val texts = listOf("Meanwhile, far away.", "You're seriously pissing me off...", "ZWAAASH", "Edge of the world")
        val v = view()
        v.setBubbles(boxes.mapIndexed { i, b ->
            RenderBubble(
                box = Rect(b),
                translated = texts[i],
                original = "原文のテキスト",
                bgColor = Color.GRAY,
                textColor = Color.WHITE,
                vertical = i == 1,
                style = styles[i],
                patch = patchFor(clean, page, b),
                patchRect = Rect(b),
                outlineColor = if (i % 2 == 0) Color.BLACK else null,
            )
        })
        v.draw(Canvas(page))
        writePreview("mixed-edges.png", page)
        assertRectsCoverOverlay(v, "mixed page against the screen edges")
        for (r in v.placedRects()) {
            assertTrue("lettering stays on screen: $r", r.left >= -1 && r.top >= -1 && r.right <= pageW + 1 && r.bottom <= pageH + 1)
        }
    }

    // ---- smoothness and speed ----

    @Test
    fun `new lettering fades in over opaque cleaning`() {
        val clean = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val box = Rect(200, 300, 520, 360)
        val dirty = copy(clean)
        drawLettering(Canvas(dirty), box, vertical = false, fill = letteringInk)
        val patch = patchFor(clean, dirty, box)
        var now = 1_000L
        val v = view()
        v.animates = { true }
        v.clock = { now }
        fun bubble(text: String) = RenderBubble(
            box = Rect(box), translated = text, original = "原文", bgColor = Color.WHITE, textColor = Color.BLACK,
            vertical = false, patch = patch, patchRect = Rect(box),
        )
        fun render(): Bitmap = copy(dirty).also { v.draw(Canvas(it)) }
        fun darkest(bmp: Bitmap): Int {
            var d = 255
            for (y in box.top - 20 until box.bottom + 20) for (x in box.left - 60 until box.right + 60) {
                d = minOf(d, luminance(bmp.getPixel(x, y)))
            }
            return d
        }

        v.setBubbles(listOf(bubble("A machine draft")))
        val first = render()
        assertEquals("the original is erased from the first frame", 0, pixels(first).count(::isLetteringInk))
        assertTrue("the English has not popped in yet", darkest(first) > 240)
        now += 60
        val mid = darkest(render())
        assertTrue("half way through it is half strong ($mid)", mid in 60..200)
        now += 80
        assertTrue("then fully drawn", darkest(render()) < 40)

        // The same words again stay put; new words fade in afresh.
        v.setBubbles(listOf(bubble("A machine draft")))
        assertTrue("unchanged lettering does not fade again", darkest(render()) < 40)
        // Nor does the same line re-read a few pixels off, as a model's box
        // for a line recalled from memory lands.
        v.setBubbles(listOf(bubble("A machine draft").copy(box = Rect(box).apply { offset(3, 4) })))
        assertTrue("the same words nudged a little do not fade again", darkest(render()) < 40)
        v.setBubbles(listOf(bubble("The AI's polished line")))
        val swapped = render()
        assertEquals("still no original under the swap", 0, pixels(swapped).count(::isLetteringInk))
        assertTrue("the new words start faded", darkest(swapped) > 240)
        v.finishFades()
        assertTrue("finishFades shows them at once", darkest(render()) < 40)
    }

    @Test
    fun `streaming a page re-places unchanged bubbles for free`() {
        val clean = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        noiseArt(Canvas(clean), Rect(0, 0, pageW, pageH), 5)
        val dirty = copy(clean)
        val texts = listOf(
            "Then what do you want to do? Just say it clearly.",
            "Stop deciding things on your own!!",
            "You're seriously pissing me off...",
            "BAM",
            "Shut up, I'm done.",
        )
        val bubbles = (0 until 20).map { i ->
            val col = i % 4
            val row = i / 4
            val box = Rect(20 + col * 175, 20 + row * 195, 20 + col * 175 + (if (i % 3 == 0) 60 else 150), 20 + row * 195 + 150)
            drawLettering(Canvas(dirty), box, vertical = i % 3 == 0, fill = Color.BLACK)
            val style = LetterStyle.values()[i % LetterStyle.values().size]
            val balloon = if (i % 5 == 4) ellipseBalloon(box) else null
            RenderBubble(
                box = box,
                translated = texts[i % texts.size],
                original = "じゃあどうすんだよ、はっきり言えよ。",
                bgColor = Color.WHITE,
                textColor = Color.BLACK,
                vertical = i % 3 == 0,
                style = style,
                balloon = balloon,
                patch = if (balloon == null) patchFor(clean, dirty, box) else null,
                patchRect = if (balloon == null) Rect(box) else null,
                outlineColor = if (i % 2 == 0) Color.WHITE else null,
            )
        }
        // Warm the JIT and font caches on throwaway views so the timings are
        // about placement; each figure is the best of a few runs, since the
        // machine running the tests is shared.
        repeat(3) { view().setBubbles(bubbles) }
        fun best(runs: Int, block: () -> Long): Long = (0 until runs).minOf { block() }

        val cold = best(5) {
            val fresh = view()
            val t0 = System.nanoTime()
            fresh.setBubbles(bubbles)
            System.nanoTime() - t0
        }
        val streamed = best(3) {
            val fresh = view()
            var total = 0L
            for (n in 1..bubbles.size) {
                val t0 = System.nanoTime()
                fresh.setBubbles(bubbles.subList(0, n))
                total += System.nanoTime() - t0
            }
            total
        }
        val v2 = view()
        v2.setBubbles(bubbles)
        val warm = best(5) {
            // Equal but not identical bubbles: the pipeline rebuilds its list on every item.
            val copies = bubbles.map { it.copy(box = Rect(it.box)) }
            val t0 = System.nanoTime()
            v2.setBubbles(copies)
            System.nanoTime() - t0
        }
        println(
            "placement, 20 items: cold full page %.2f ms; streamed one by one (20 calls) %.2f ms total; unchanged re-place %.3f ms"
                .format(cold / 1e6, streamed / 1e6, warm / 1e6)
        )
        assertTrue("re-placing an unchanged page must be far cheaper than placing it", warm * 5 < cold)
        assertTrue("streaming costs about one full placement, not twenty", streamed < cold * 4)
        val v = view()
        v.setBubbles(bubbles)
        assertEquals(v.placedRects(), v2.placedRects())

        val out = copy(dirty)
        v2.draw(Canvas(out))
        writePreview("twenty-items.png", out)
    }

    // ---- hyphenation ----

    private val unit: (String) -> Float = { it.length.toFloat() }

    @Test
    fun `a word too wide is hyphenated into balanced pieces that fit`() {
        val out = TypeSet.hyphenate("the Donaudampfschifffahrtsgesellschaft sails", unit, 12f)
        val words = out.split(" ")
        assertEquals("the", words.first())
        assertEquals("sails", words.last())
        val pieces = words.subList(1, words.size - 1)
        assertTrue("split into pieces: $pieces", pieces.size >= 3)
        for (p in pieces) assertTrue("'$p' fits", p.length <= 12)
        for (p in pieces.dropLast(1)) assertTrue("'$p' ends in a hyphen", p.endsWith("-"))
        assertEquals("Donaudampfschifffahrtsgesellschaft", pieces.joinToString("") { it.removeSuffix("-") })
        for (i in 0 until pieces.size - 1) {
            assertTrue("two pieces never share a line", unit(pieces[i] + " " + pieces[i + 1]) > 12f)
        }
    }

    @Test
    fun `words that fit are left alone and own hyphens are preferred`() {
        assertEquals("short words stay", TypeSet.hyphenate("short words stay", unit, 12f))
        assertEquals("self- confidence", TypeSet.hyphenate("self-confidence", unit, 11f))
    }

    @Test
    fun `stammers, digraphs, short words and punctuation are never cut through`() {
        assertEquals("a stammer keeps its halves", "S-Sorry", TypeSet.hyphenate("S-Sorry", unit, 4f))
        assertEquals("between two consonants, not inside 'ck'", "back- packing", TypeSet.hyphenate("backpacking", unit, 7f))
        assertEquals("a short word is never cut", "whatever", TypeSet.hyphenate("whatever", unit, 5f))
        val trailing = TypeSet.hyphenate("waiting...aah", unit, 8f).split(" ")
        for (p in trailing) assertTrue("'$p' does not cut 'aah'", !p.startsWith("ah") && !p.startsWith("h"))
    }

    @Test
    fun `a word is cut where a dictionary would hyphenate it`() {
        // Words from real translations, cut before as "dest- ructive", "Reque- sting".
        assertEquals("destruc- tive", TypeSet.hyphenate("destructive", unit, 8f))
        assertEquals("trans- forming", TypeSet.hyphenate("transforming", unit, 9f))
        assertEquals("infor- mation", TypeSet.hyphenate("information", unit, 9f))
        assertEquals("measure- ments", TypeSet.hyphenate("measurements", unit, 9f))
        assertEquals("Request- ing", TypeSet.hyphenate("Requesting", unit, 8f, eager = true))
        assertEquals("every- thing", TypeSet.hyphenate("everything", unit, 8f, eager = true))
    }

    @Test
    fun `the hyphenation patterns load and know their exceptions`() {
        fun cut(word: String) = Hyphenation.points(word).withIndex()
            .filter { it.value }.joinToString("-") { "${it.index}" }
        assertEquals("2-7", cut("destructive"))
        assertEquals("2-4", cut("associate"))
        assertEquals("", cut("présent"))
    }

    @Test
    fun `names and ten-letter words are set whole unless nothing else fits`() {
        // Smaller type reads better than "Tortil- lano" or "Under- stood".
        assertEquals("part of the Tortillano family", TypeSet.hyphenate("part of the Tortillano family", unit, 6f))
        assertEquals("Understood.", TypeSet.hyphenate("Understood.", unit, 6f))
        // A balloon no type fits them in: a hyphen beats a word over the outline.
        assertEquals("Under- stood.", TypeSet.hyphenate("Understood.", unit, 6f, eager = true))
        assertEquals("part of the Tor- tillano family", TypeSet.hyphenate("part of the Tortillano family", unit, 7f, eager = true))
        // At the start of a sentence a capital says nothing about a name.
        assertEquals("Wait. Everywhere- else", TypeSet.hyphenate("Wait. Everywhere-else", unit, 11f))
    }
}
