package app.mangalens.gaps

/**
 * Takes our own shade back off a captured frame.
 *
 * The rest of MangaLens reads the page — OCR, the balloon finder, the change detectors that
 * decide whether the reader has scrolled or turned the page — and was written for a page
 * with white gutters. The shade is in every capture, so before the page is read it is
 * undone, and the reading code never learns that it was there.
 */
object Unshade {

    /**
     * Restores paper under the rectangles of a shade that was drawn [shift] rows below where
     * they were found. Only pixels that look like paper seen through the shade are touched:
     * if the overlay was not where we believed — a frame caught mid-change — whatever else is
     * there stays as it is, instead of being whitened by mistake.
     *
     * @param pixels the frame, [stride] ints per row, any channel order (only symmetric tests are made).
     */
    fun restore(
        pixels: IntArray,
        stride: Int,
        width: Int,
        height: Int,
        rects: IntArray,
        count: Int,
        shift: Int,
        style: ShadeStyle,
    ) {
        for (i in 0 until count) {
            val x0 = rects[i * 4].coerceIn(0, width)
            val x1 = rects[i * 4 + 2].coerceIn(0, width)
            val y0 = (rects[i * 4 + 1] + shift).coerceIn(0, height)
            val y1 = (rects[i * 4 + 3] + shift).coerceIn(0, height)
            for (y in y0 until y1) {
                val row = y * stride
                for (x in x0 until x1) {
                    if (style.isShadedPaper(pixels[row + x])) pixels[row + x] = WHITE
                }
            }
        }
    }

    const val WHITE = -1 // 0xFFFFFFFF

    /**
     * For each cell of a [size] x [size] thumbnail, the share of the cell the shade covers.
     *
     * The change detectors compare thumbnails of successive frames; a thumbnail of a shaded
     * frame is darker in the gutters than the page is. Adding back, per cell, what the shade
     * took away — covered share times the difference between paper and shaded paper — gives
     * the thumbnail of the unshaded page without touching a pixel of the frame.
     */
    fun thumbCoverage(
        rects: IntArray,
        count: Int,
        shift: Int,
        frameW: Int,
        frameH: Int,
        size: Int,
    ): FloatArray {
        val out = FloatArray(size * size)
        if (count == 0 || frameW <= 0 || frameH <= 0) return out
        val cw = frameW.toFloat() / size
        val ch = frameH.toFloat() / size
        for (i in 0 until count) {
            val x0 = rects[i * 4].coerceIn(0, frameW)
            val x1 = rects[i * 4 + 2].coerceIn(0, frameW)
            val y0 = (rects[i * 4 + 1] + shift).coerceIn(0, frameH)
            val y1 = (rects[i * 4 + 3] + shift).coerceIn(0, frameH)
            if (x1 <= x0 || y1 <= y0) continue
            val cx0 = (x0 / cw).toInt().coerceIn(0, size - 1)
            val cx1 = ((x1 - 1) / cw).toInt().coerceIn(0, size - 1)
            val cy0 = (y0 / ch).toInt().coerceIn(0, size - 1)
            val cy1 = ((y1 - 1) / ch).toInt().coerceIn(0, size - 1)
            for (cy in cy0..cy1) {
                val oy = (minOf(y1.toFloat(), (cy + 1) * ch) - maxOf(y0.toFloat(), cy * ch)) / ch
                if (oy <= 0f) continue
                for (cx in cx0..cx1) {
                    val ox = (minOf(x1.toFloat(), (cx + 1) * cw) - maxOf(x0.toFloat(), cx * cw)) / cw
                    if (ox > 0f) out[cy * size + cx] += ox * oy
                }
            }
        }
        for (k in out.indices) if (out[k] > 1f) out[k] = 1f
        return out
    }

    /** Corrects a grey thumbnail in place for the shade [coverage] describes. */
    fun correctThumb(thumb: IntArray, coverage: FloatArray, style: ShadeStyle) {
        val gain = 255 - style.whiteComposite
        for (i in thumb.indices) {
            val f = if (i < coverage.size) coverage[i] else 0f
            if (f > 0f) thumb[i] = minOf(255, thumb[i] + Math.round(f * gain))
        }
    }
}
