package app.mangalens.capture

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Cheap frame-difference detector: frames are downscaled to a tiny grayscale
 * thumbnail and compared by mean absolute difference. Used to know when the
 * reader has stopped scrolling (stable page -> OCR) and when they start again
 * (motion -> clear overlays).
 */
object FrameStability {

    const val SIZE = 96

    fun grayThumb(src: Bitmap): IntArray {
        val scaled = Bitmap.createScaledBitmap(src, SIZE, SIZE, false)
        val px = IntArray(SIZE * SIZE)
        scaled.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== src) scaled.recycle()
        for (i in px.indices) {
            val p = px[i]
            px[i] = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        return px
    }

    fun meanDiff(a: IntArray?, b: IntArray?): Double {
        if (a == null || b == null || a.size != b.size) return 255.0
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum.toDouble() / a.size
    }
}
