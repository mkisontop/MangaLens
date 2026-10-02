package app.mangalens.gaps

/**
 * A frame seen through MangaLens's own veil: black laid over the whole screen so that nothing of
 * the original shows through the lettering. Every channel is [gain] times as bright in the
 * capture as it is on the page, and the finder was written for the page, so each pixel is
 * brightened back by the gain (the veil's own `1 / level`), up to white.
 */
class LiftedPixels(private val src: PixelSource, gain: Float) : PixelSource {
    override val width: Int get() = src.width
    override val height: Int get() = src.height

    /** The gain in 8.8 fixed point: one multiply and a shift a channel. */
    private val g = (gain.coerceIn(1f, 8f) * 256f + 0.5f).toInt()

    private fun lift(p: Int): Int {
        val r = minOf(255, (((p ushr 16) and 0xFF) * g + 128) shr 8)
        val gr = minOf(255, (((p ushr 8) and 0xFF) * g + 128) shr 8)
        val b = minOf(255, ((p and 0xFF) * g + 128) shr 8)
        return (0xFF shl 24) or (r shl 16) or (gr shl 8) or b
    }

    override fun readRow(y: Int, dst: IntArray) {
        src.readRow(y, dst)
        for (i in 0 until width) dst[i] = lift(dst[i])
    }

    override fun pixel(x: Int, y: Int): Int = lift(src.pixel(x, y))
}
