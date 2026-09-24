package app.mangalens.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import app.mangalens.translate.ItemKind
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Erases every item a model found on real test pages and writes the result
 * to build/eraser-preview/ for judging by eye, with per-item timings. The
 * pages and the recorded model answers live outside the repository (set
 * MANGALENS_PAGES to their directory); without them the test is skipped.
 *
 * Balloon-held text is erased here too, although the balloon path usually
 * cleans it in the app: it is a useful stress test either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextEraserPagesTest {

    /** Recorded answers and a pages/ directory, from MANGALENS_ERASER_DATA; the test is skipped without it. */
    private val dataDir = File(System.getenv("MANGALENS_ERASER_DATA").orEmpty())
    private val outputDir = File("build/eraser-preview").apply { mkdirs() }

    private class Item(val box: Rect, val kind: ItemKind, val src: String)

    private fun items(page: String, w: Int, h: Int): List<Item> {
        val raw: JSONArray = if (page == "ja_romcom_hearts.jpg" || page == "ja_bl_cafeteria.jpg") {
            JSONObject(File(dataDir, "split.json").readText()).getJSONArray("gemini-3.8-flash|$page|3")
        } else {
            val bench = JSONArray(File(dataDir, "bench3.json").readText())
            (0 until bench.length()).map { bench.getJSONObject(it) }
                .first { it.optString("model") == "gemini-3.8-flash" && it.optString("page") == page }
                .getJSONArray("items")
        }
        return (0 until raw.length()).map { k ->
            val o = raw.getJSONObject(k)
            val b = o.getJSONArray("box_2d")
            Item(
                Rect(b.getInt(1) * w / 1000, b.getInt(0) * h / 1000, b.getInt(3) * w / 1000, b.getInt(2) * h / 1000),
                ItemKind.parse(o.optString("kind")),
                o.optString("src"),
            )
        }
    }

    @Test
    fun `erase every item on the test pages`() {
        val pagesDir = File(dataDir, "pages")
        assumeTrue(File(dataDir, "bench3.json").isFile && File(dataDir, "split.json").isFile && pagesDir.isDirectory)
        val pages = pagesDir.listFiles { f -> f.name.endsWith(".jpg") }!!.sortedBy { it.name }
        assumeTrue(pages.isNotEmpty())
        val all = ArrayList<Double>()
        for (file in pages) {
            val page = BitmapFactory.decodeFile(file.path).copy(Bitmap.Config.ARGB_8888, false)
            val items = items(file.name, page.width, page.height)
            val after = page.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(after)
            val erased = ArrayList<Pair<Item, Erasure>>()
            // Warm up once so the first item's time is not the JIT's.
            items.forEach { TextEraser.erase(page, it.box, it.kind) }
            for (item in items) {
                val t = System.nanoTime()
                val e = TextEraser.erase(page, item.box, item.kind)
                val ms = (System.nanoTime() - t) / 1e6
                all += ms
                val how = if (e == null) "nothing" else
                    "flat=${e.flat} busy=${"%.2f".format(e.busy)} text=#${hex(e.textColor)} outline=${e.outlineColor?.let { "#" + hex(it) }} " +
                        "rect=${e.rect.width()}x${e.rect.height()} masked=${e.mask.count { it }}"
                println("${file.name} ${item.kind} ${item.src.replace('\n', ' ')}: ${"%.2f".format(ms)} ms $how")
                if (e != null) {
                    canvas.drawBitmap(e.patch, e.rect.left.toFloat(), e.rect.top.toFloat(), null)
                    erased += item to e
                }
            }
            save(after, file.nameWithoutExtension + ".png")
            save(sheet(page, after, erased), file.nameWithoutExtension + "_items.png")
        }
        all.sort()
        println("erase() per item: median ${"%.2f".format(all[all.size / 2])} ms, p90 ${"%.2f".format(all[all.size * 9 / 10])} ms, max ${"%.2f".format(all.last())} ms over ${all.size} items")
    }

    /**
     * Real answers of the image models for two of the pages, run through
     * [AiCleaner.refine]: each item is either accepted (the model's pixels
     * replace the local erasure) or rejected, and the page is written with
     * the accepted items taken from the model. The lite model repainted one
     * panel's background as a night sky and left one burst's text in place;
     * the items there must be rejected.
     */
    @Test
    fun `refine against real image-model answers`() {
        val pagesDir = File(dataDir, "pages")
        assumeTrue(File(dataDir, "bench3.json").isFile && pagesDir.isDirectory)
        val answers = dataDir.listFiles { f -> f.name.startsWith("clean_") && f.name.endsWith("_full.png") }
            ?.sortedBy { it.name }.orEmpty()
        assumeTrue(answers.isNotEmpty())
        for (answer in answers) {
            val key = answer.name.removeSuffix("_full.png").substringAfterLast('_')
            val file = File(pagesDir, if (key == "ko") "ko_webtoon_color.jpg" else "zh_manhua_color.jpg")
            if (!file.isFile) continue
            val page = BitmapFactory.decodeFile(file.path).copy(Bitmap.Config.ARGB_8888, false)
            val raw = BitmapFactory.decodeFile(answer.path)
            val cleaned = Bitmap.createScaledBitmap(raw, page.width, page.height, true).copy(Bitmap.Config.ARGB_8888, false)
            val out = page.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(out)
            for (item in items(file.name, page.width, page.height)) {
                val e = TextEraser.erase(page, item.box, item.kind) ?: continue
                val t = System.nanoTime()
                val refined = AiCleaner.refine(page, cleaned, e)
                val ms = (System.nanoTime() - t) / 1e6
                println("${answer.name} ${item.src.replace('\n', ' ')}: ${if (refined != null) "ACCEPTED" else "rejected"} (${"%.2f".format(ms)} ms, local busy=${"%.2f".format(e.busy)})")
                val use = refined ?: e
                canvas.drawBitmap(use.patch, use.rect.left.toFloat(), use.rect.top.toFloat(), null)
            }
            save(out, answer.name.removeSuffix(".png") + "_refined.png")
        }
    }

    private fun hex(c: Int) = Integer.toHexString(c and 0xFFFFFF).padStart(6, '0')

    /** Each erased item before, after and with its mask in red, side by side. */
    private fun sheet(before: Bitmap, after: Bitmap, erased: List<Pair<Item, Erasure>>): Bitmap {
        val rows = erased.map { it.second.rect }
        val w = rows.maxOfOrNull { it.width() * 3 + 16 } ?: 1
        val h = rows.sumOf { it.height() + 8 }.coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.MAGENTA)
        var y = 0
        val box = Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(0, 160, 255) }
        for ((item, e) in erased) {
            val r = e.rect
            val dst = Rect(0, y, r.width(), y + r.height())
            c.drawBitmap(before, r, dst, null)
            c.drawRect(Rect(item.box).apply { offset(-r.left, y - r.top) }, box)
            dst.offset(r.width() + 8, 0)
            c.drawBitmap(after, r, dst, null)
            dst.offset(r.width() + 8, 0)
            c.drawBitmap(before, r, dst, null)
            val red = IntArray(e.mask.size) { if (e.mask[it]) Color.RED else Color.TRANSPARENT }
            c.drawBitmap(Bitmap.createBitmap(red, r.width(), r.height(), Bitmap.Config.ARGB_8888), null, dst, null)
            y += r.height() + 8
        }
        return out
    }

    private fun save(b: Bitmap, name: String) {
        FileOutputStream(File(outputDir, name)).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
