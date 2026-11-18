package app.mangalens.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mangalens.pipeline.TextEraser
import app.mangalens.translate.ItemKind
import java.io.File
import java.util.zip.CRC32
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The clean illustration is known before adding the source ink. Judge every output pixel against it. */
@RunWith(AndroidJUnit4::class)
class ErasureExperienceTest {
    private data class Illustration(val paper: String, val text: String, val background: Int)

    @Test
    fun reconstructThePaperUnderRealGlyphsWithoutChangingPanelArt() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val build = InstrumentationRegistry.getArguments().getString("build", "candidate")
        val output = File(inst.targetContext.filesDir, "reader-ux/" + build).apply { mkdirs() }
        val examples = listOf(
            Illustration("white", "문을 열어봐요", Color.WHITE),
            Illustration("colour", "关上那扇门", Color.rgb(152, 181, 200)),
            Illustration("gradient", "窓を開けないで", Color.rgb(175, 202, 235)),
        )
        File(output, "erasure.csv").bufferedWriter().use { csv ->
            csv.appendLine("paper,trial,ms,masked_pixels,max_channel_error,mean_channel_error,pixels_changed_outside_mask,crc")
            for (example in examples) {
                measure(example, output) { csv.appendLine(it) }
            }
        }
    }

    private fun measure(example: Illustration, output: File, row: (String) -> Unit) {
        val width = 440
        val height = 164
        val ground = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        ground.density = Bitmap.DENSITY_NONE
        val canvas = Canvas(ground)
        canvas.drawColor(example.background)
        if (example.paper == "gradient") {
            val fill = Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, height.toFloat(), example.background,
                    Color.rgb(111, 155, 173), Shader.TileMode.CLAMP)
            }
            canvas.drawPaint(fill)
        }
        // A dark panel edge and two strokes remain present in the reference and dirty page.
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 50, 64); strokeWidth = 3f }
        canvas.drawLine(12f, 15f, width - 11f, 15f, line)
        canvas.drawLine(14f, 26f, 26f, 111f, line)
        canvas.drawLine(width - 28f, 42f, width - 15f, 133f, line)
        val dirty = ground.copy(Bitmap.Config.ARGB_8888, true)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(50, 23, 64)
            textSize = 44f
            typeface = Typeface.DEFAULT_BOLD
        }
        val x = 53
        val y = 107
        Canvas(dirty).drawText(example.text, x.toFloat(), y.toFloat(), text)
        val box = Rect().apply { text.getTextBounds(example.text, 0, example.text.length, this); offset(x, y) }
        val original = IntArray(width * height)
        val reference = IntArray(width * height)
        dirty.getPixels(original, 0, width, 0, 0, width, height)
        ground.getPixels(reference, 0, width, 0, 0, width, height)
        try {
            for (trial in 0 until 12) {
                val start = System.nanoTime()
                val cleaned = requireNotNull(TextEraser.erase(dirty, box, ItemKind.NARRATION))
                val ms = (System.nanoTime() - start) / 1_000_000.0
                val result = dirty.copy(Bitmap.Config.ARGB_8888, true)
                try {
                    Canvas(result).drawBitmap(cleaned.patch, cleaned.rect.left.toFloat(), cleaned.rect.top.toFloat(), null)
                    val pixels = IntArray(width * height)
                    result.getPixels(pixels, 0, width, 0, 0, width, height)
                    val digest = CRC32()
                    var beyondMask = 0
                    var worst = 0
                    var sum = 0L
                    for (i in pixels.indices) {
                        val px = i % width
                        val py = i / width
                        val marked = cleaned.rect.contains(px, py) && cleaned.mask[
                            (py - cleaned.rect.top) * cleaned.rect.width() + px - cleaned.rect.left]
                        if (!marked && pixels[i] != original[i]) beyondMask++
                        for (shift in 0..16 step 8) {
                            val channel = pixels[i] shr shift and 255
                            val error = abs(channel - (reference[i] shr shift and 255))
                            digest.update(channel)
                            worst = maxOf(worst, error)
                            sum += error
                        }
                    }
                    val maskSize = cleaned.mask.count { it }
                    val mean = sum.toDouble() / (pixels.size * 3)
                    row(listOf(example.paper, trial, ms, maskSize, worst, mean, beyondMask, digest.value).joinToString(","))
                    assertTrue("the source contained visible lettering", maskSize > 300)
                    assertTrue("art outside the erasure changed", beyondMask == 0)
                    assertTrue("source lettering or a damaged panel remains: " + worst, worst <= 16 && mean < 0.6)
                    if (trial == 0) {
                        File(output, "erased-" + example.paper + ".png").outputStream().use {
                            result.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                } finally {
                    result.recycle()
                    cleaned.patch.recycle()
                }
            }
        } finally {
            ground.recycle()
            dirty.recycle()
        }
    }
}
