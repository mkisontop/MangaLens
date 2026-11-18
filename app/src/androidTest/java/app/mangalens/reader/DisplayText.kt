package app.mangalens.reader

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

/** An independent Latin recognizer reads the actual composited English. */
internal class DisplayText : Closeable {
    private val reader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun read(frame: Bitmap, region: Rect, scale: Float = 1f): String {
        val crop = Bitmap.createBitmap(frame, region.left, region.top, region.width(), region.height())
        val input = if (scale == 1f) crop else Bitmap.createScaledBitmap(crop,
            (crop.width * scale).roundToInt().coerceAtLeast(1), (crop.height * scale).roundToInt().coerceAtLeast(1), true)
        return try {
            runBlocking { reader.process(InputImage.fromBitmap(input, 0)).await().text }
        } finally {
            if (input !== crop) input.recycle()
            if (crop !== frame) crop.recycle()
        }
    }

    override fun close() = reader.close()

    companion object {
        fun letters(text: String) = text.lowercase(Locale.ROOT).filter { it in 'a'..'z' }
    }
}
