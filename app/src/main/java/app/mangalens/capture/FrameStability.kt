package app.mangalens.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs

/**
 * Cheap frame-difference detector: frames are downscaled to a tiny grayscale
 * thumbnail and compared by mean absolute difference. Used to know when the
 * reader has stopped scrolling (stable page -> OCR) and when they start again
 * (motion -> clear overlays).
 *
 * Not thread-safe — call only from the capture thread. The 96x96 scratch
 * bitmap is reused across frames so steady-state motion detection allocates
 * nothing but the returned thumb array.
 */
object FrameStability {

    const val SIZE = 96

    private var thumbBmp: Bitmap? = null
    private var thumbCanvas: Canvas? = null
    private val srcRect = Rect()
    private val dstRect = Rect(0, 0, SIZE, SIZE)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * [srcW]/[srcH] bound the logical frame inside a possibly stride-padded
     * source bitmap, so padding columns never leak into the comparison.
     */
    fun grayThumb(src: Bitmap, srcW: Int = src.width, srcH: Int = src.height): IntArray {
        val bmp = thumbBmp ?: Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also {
            thumbBmp = it
            thumbCanvas = Canvas(it)
        }
        srcRect.set(0, 0, srcW.coerceAtMost(src.width), srcH.coerceAtMost(src.height))
        thumbCanvas?.drawBitmap(src, srcRect, dstRect, paint)
        val px = IntArray(SIZE * SIZE)
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
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
