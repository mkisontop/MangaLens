package app.mangalens.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import app.mangalens.pipeline.ReadResolver
import app.mangalens.translate.ItemKind
import app.mangalens.translate.PageItem
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Balloon detection on real pages, offline: every page in MANGALENS_PAGES
 * with its detected balloons outlined (magenta, inverted ones cyan) and
 * their interior masks tinted, written to build/harness/balloons-*.png.
 * Skipped when the directory is not given.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BalloonHarnessTest {

    @Test
    fun outlineBalloonsOnRealPages() {
        val dir = System.getenv("MANGALENS_PAGES")?.let(::File)
        assumeTrue("MANGALENS_PAGES not set", dir != null && dir.isDirectory)
        val out = File("build/harness").apply { mkdirs() }
        val pages = dir!!.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }!!.sortedBy { it.name }
        for (file in pages) {
            val page = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
            val t0 = System.nanoTime()
            val scan = BalloonFinder.analyze(page, 0, 0, emptyList())
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("[balloons] ${file.name} ${page.width}x${page.height}: ${scan.balloons.size} balloons, ${scan.panels.size} panels in $ms ms")
            val marked = page.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(marked)
            val tint = Paint().apply { color = Color.argb(70, 255, 0, 160) }
            for (b in scan.balloons) {
                val cw = b.box.width().toFloat() / b.maskW
                val ch = b.box.height().toFloat() / b.maskH
                for (y in 0 until b.maskH) for (x in 0 until b.maskW) {
                    if (b.mask[y * b.maskW + x]) {
                        c.drawRect(b.box.left + x * cw, b.box.top + y * ch, b.box.left + (x + 1) * cw, b.box.top + (y + 1) * ch, tint)
                    }
                }
                val stroke = Paint().apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                    color = if (b.inverted) Color.CYAN else Color.MAGENTA
                }
                c.drawRect(b.box, stroke)
            }
            FileOutputStream(File(out, "balloons-${file.nameWithoutExtension}.png")).use {
                marked.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    /**
     * Recorded model answers (MANGALENS_ITEMS: {page: [{box_2d, src, en,
     * kind}]}) resolved against each page's detections: which balloons the
     * lettering claimed and kept (green), which were refused as art (red),
     * and each item's box (blue). Needs no network; skipped without the files.
     */
    @Test
    fun resolveRecordedItemsOnRealPages() {
        val dir = System.getenv("MANGALENS_PAGES")?.let(::File)
        val itemsFile = System.getenv("MANGALENS_ITEMS")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory && itemsFile != null && itemsFile.isFile)
        val all = JSONObject(itemsFile!!.readText())
        val out = File("build/harness").apply { mkdirs() }
        for (name in all.keys()) {
            val file = File(dir, name)
            if (!file.isFile) continue
            val page = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
            val arr = all.getJSONArray(name)
            val items = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val b = o.getJSONArray("box_2d")
                PageItem(
                    Rect(
                        b.getInt(1) * page.width / 1000, b.getInt(0) * page.height / 1000,
                        b.getInt(3) * page.width / 1000, b.getInt(2) * page.height / 1000,
                    ),
                    ItemKind.parse(o.getString("kind")), o.getString("src"), o.getString("en"),
                )
            }
            val scan = BalloonFinder.analyze(page, 0, 0, emptyList())
            val t0 = System.nanoTime()
            val rendered = ReadResolver(page, scan.balloons, emptyList(), 0, 0, emptyList()).resolve(items)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val kept = rendered.mapNotNull { it.balloon }
            println("[resolve] $name: ${items.size} items -> ${rendered.size} cards, ${kept.size} in balloons, ${rendered.count { it.patch != null }} erased ($ms ms)")
            for (r in rendered) println("    [${r.style} ${if (r.balloon != null) "balloon" else if (r.patch != null) "erased" else "card"}] ${r.original.take(24)} => ${r.translated}")
            val marked = page.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(marked)
            fun stroke(col: Int, w: Float) = Paint().apply { style = Paint.Style.STROKE; strokeWidth = w; color = col }
            for (b in scan.balloons) {
                val claimed = kept.any { k -> Rect.intersects(k.box, b.box) && b.box.contains(k.box.centerX(), k.box.centerY()) }
                val touched = items.any { b.box.contains(it.box.centerX(), it.box.centerY()) }
                if (claimed) c.drawRect(b.box, stroke(Color.GREEN, 5f))
                else if (touched) c.drawRect(b.box, stroke(Color.RED, 5f))
            }
            for (it in items) c.drawRect(it.box, stroke(Color.BLUE, 2f))
            FileOutputStream(File(out, "resolve-${file.nameWithoutExtension}.png")).use {
                marked.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
