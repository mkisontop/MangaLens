package app.mangalens.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import app.mangalens.ocr.BubbleKind
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Letters real raw pages from recorded model answers, for looking at: every
 * item as free lettering (no balloons), over a crude stand-in erasure. Runs
 * only where the pages and recordings exist (a developer's scratch
 * directory, or MANGALENS_PAGES); CI skips it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RealPageLetteringTest {

    /** Recorded model answers and a pages/ directory, from MANGALENS_RECORDED; skipped without it. */
    private val scratch = File(System.getenv("MANGALENS_RECORDED").orEmpty())
    private val outputDir = File("build/render-preview").apply { mkdirs() }

    /** Pages wider than this are halved, like a phone showing a print-resolution scan. */
    private val maxWidth = 900

    private class Item(
        val box: IntArray,
        val src: String,
        val en: String,
        val kind: String,
        val vertical: Boolean,
        val textColor: Int?,
        val outlineColor: Int?,
    )

    private fun color(s: String?): Int? =
        s?.takeIf { it.startsWith("#") && it.length == 7 }?.let { Color.parseColor(it) }

    private fun items(arr: JSONArray): List<Item> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val b = o.getJSONArray("box_2d")
        val src = o.optString("src")
        Item(
            box = IntArray(4) { b.getInt(it) },
            src = src,
            en = o.optString("en"),
            kind = o.optString("kind"),
            vertical = if (o.has("vertical")) o.optBoolean("vertical") else {
                // Recordings without the flag: tall boxes of CJK text are vertical.
                (b.getInt(2) - b.getInt(0)) > (b.getInt(3) - b.getInt(1)) * 1.3
            },
            textColor = color(o.optString("text_color", "")),
            outlineColor = color(o.optString("outline_color", "")),
        )
    }

    private fun recordings(): Map<String, List<Item>> {
        val out = LinkedHashMap<String, List<Item>>()
        val bench = File(scratch, "bench3.json")
        if (bench.exists()) {
            val arr = JSONArray(bench.readText())
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                if (e.optString("model") != "gemini-3.8-flash") continue
                out[e.getString("page")] = items(e.getJSONArray("items"))
            }
        }
        val split = File(scratch, "split.json")
        if (split.exists()) {
            val obj = JSONObject(split.readText())
            for (page in listOf("ja_romcom_hearts.jpg", "ja_bl_cafeteria.jpg")) {
                val arr = obj.optJSONArray("gemini-3.8-flash|$page|4") ?: continue
                out[page] = dedupe(items(arr))
            }
        }
        return out
    }

    /** The split recordings list some lettering twice from overlapping tiles; keep the first. */
    private fun dedupe(items: List<Item>): List<Item> {
        val kept = ArrayList<Item>()
        for (it in items) {
            if (kept.none { k -> iou(k.box, it.box) > 0.4f }) kept.add(it)
        }
        return kept
    }

    private fun iou(a: IntArray, b: IntArray): Float {
        val iy = minOf(a[2], b[2]) - maxOf(a[0], b[0])
        val ix = minOf(a[3], b[3]) - maxOf(a[1], b[1])
        if (ix <= 0 || iy <= 0) return 0f
        val inter = ix.toFloat() * iy
        val area = { r: IntArray -> (r[2] - r[0]).toFloat() * (r[3] - r[1]) }
        return inter / (area(a) + area(b) - inter)
    }

    private fun style(kind: String) = when (kind) {
        "sfx" -> LetterStyle.SFX
        "thought" -> LetterStyle.THOUGHT
        "narration" -> LetterStyle.NARRATION
        "art_text" -> LetterStyle.ART
        "shout" -> LetterStyle.SHOUT
        else -> LetterStyle.DIALOGUE
    }

    /**
     * A crude erasure: the median colour of a ring just outside the box,
     * painted over every box pixel far from it (and a pixel around each, for
     * anti-aliasing). The real eraser does far better; this is only enough
     * to judge the lettering.
     */
    private fun standInPatch(page: Bitmap, box: Rect): Pair<Bitmap, Int> {
        val ring = ArrayList<Int>()
        val grow = 6
        val outer = Rect(box).apply { inset(-grow, -grow) }
        outer.intersect(0, 0, page.width, page.height)
        for (y in outer.top until outer.bottom step 2) for (x in outer.left until outer.right step 2) {
            if (box.contains(x, y)) continue
            ring.add(page.getPixel(x, y))
        }
        fun med(ch: (Int) -> Int) = ring.map(ch).sorted().let { if (it.isEmpty()) 255 else it[it.size / 2] }
        val bg = Color.rgb(med(Color::red), med(Color::green), med(Color::blue))
        val w = box.width()
        val h = box.height()
        val px = IntArray(w * h)
        page.getPixels(px, 0, w, box.left, box.top, w, h)
        val far = BooleanArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val d = Math.abs(Color.red(c) - Color.red(bg)) + Math.abs(Color.green(c) - Color.green(bg)) +
                Math.abs(Color.blue(c) - Color.blue(bg))
            far[i] = d > 90
        }
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var hit = false
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy
                val xx = x + dx
                if (yy in 0 until h && xx in 0 until w && far[yy * w + xx]) hit = true
            }
            if (hit) out[y * w + x] = bg
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888) to bg
    }

    private fun load(name: String): Bitmap? {
        val f = File(scratch, "pages/$name")
        if (!f.exists()) return null
        val raw = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        val scaled = if (raw.width > maxWidth) {
            Bitmap.createScaledBitmap(raw, raw.width / 2, raw.height / 2, true)
        } else {
            raw
        }
        return scaled.copy(Bitmap.Config.ARGB_8888, true)
    }

    @Test
    fun `real pages lettered from recorded answers`() {
        val recorded = recordings()
        assumeTrue("recorded pages not present", recorded.isNotEmpty())
        var rendered = 0
        for ((name, items) in recorded) {
            val page = load(name) ?: continue
            val bubbles = items.mapNotNull { it ->
                val box = Rect(
                    it.box[1] * page.width / 1000,
                    it.box[0] * page.height / 1000,
                    it.box[3] * page.width / 1000,
                    it.box[2] * page.height / 1000,
                )
                box.intersect(0, 0, page.width, page.height)
                if (box.width() < 4 || box.height() < 4 || it.en.isBlank()) return@mapNotNull null
                val (patch, bg) = standInPatch(page, box)
                val st = style(it.kind)
                RenderBubble(
                    box = box,
                    translated = it.en,
                    original = it.src,
                    bgColor = bg,
                    textColor = it.textColor ?: Color.BLACK,
                    vertical = it.vertical,
                    kind = if (st == LetterStyle.SFX) BubbleKind.SFX else BubbleKind.DIALOGUE,
                    style = st,
                    patch = patch,
                    patchRect = Rect(box),
                    outlineColor = it.outlineColor,
                )
            }
            fun fresh() = BubbleOverlayView(RuntimeEnvironment.getApplication()).apply { layout(0, 0, page.width, page.height) }
            // Best of a few cold placements, after a warm-up: the machine is shared.
            repeat(2) { fresh().setBubbles(bubbles) }
            val ms = (0 until 3).minOf {
                val t0 = System.nanoTime()
                fresh().setBubbles(bubbles)
                System.nanoTime() - t0
            } / 1e6
            val v = fresh()
            v.setBubbles(bubbles)
            v.draw(Canvas(page))
            val out = File(outputDir, "real-${name.substringBeforeLast('.')}.png")
            ByteArrayOutputStream().use { bos ->
                page.compress(Bitmap.CompressFormat.PNG, 100, bos)
                out.writeBytes(bos.toByteArray())
            }
            println("wrote ${out.absolutePath} (${bubbles.size} items placed in %.1f ms)".format(ms))
            rendered++
        }
        assumeTrue("no recorded page could be loaded", rendered > 0)
    }
}
