package app.mangalens.reader

import android.graphics.Bitmap
import app.mangalens.pipeline.ScrollMatch

/** Smooth panels, fine horizontal textures, repeated stripes, and a fixed reader toolbar. */
internal object ScrollFixture {
    data class Case(val name: String, val pattern: Int, val offset: Int, val newPage: Boolean = false)
    val cases = listOf(
        Case("smooth-down", 0, 81), Case("smooth-up", 0, -147),
        Case("texture-down", 1, 53), Case("texture-up", 1, -121),
        Case("unchanged", 1, 0), Case("new-page", 1, 0, true),
        Case("repeating", 2, 82), Case("blank", 3, 0)
    )
    fun expected(c: Case): Int? = if (c.newPage || c.pattern >= 2) null else c.offset

    private fun noise(y: Int, band: Int, seed: Int): Int {
        var v = y * 739393 + band * 19349663 + seed * 83492791
        v = (v xor (v ushr 16)) * 0x45d9f3b
        return (v xor (v ushr 16)) ushr 24
    }

    fun page(w: Int, h: Int, shift: Int, pattern: Int, seed: Int): Bitmap {
        val pixels = IntArray(w*h)
        for (y in 0 until h) {
            val sceneY = y + shift + h
            for (band in 0 until 6) {
                val level = when {
                    y < 42 -> 102 + band * 9
                    pattern == 3 -> 250
                    pattern == 2 -> if (sceneY % 16 < 8) 70 + band * 12 else 220 - band * 4
                    pattern == 1 -> 20 + noise(sceneY, band, seed) * 210 / 255
                    sceneY % 181 < 17 -> 248
                    else -> {
                        val k = sceneY / 13
                        val mix = sceneY % 13
                        25 + (noise(k, band, seed) * (13-mix) + noise(k+1, band, seed) * mix) * 200 / (13*255)
                    }
                }
                val color = -0x1000000 or (level shl 16) or (level shl 8) or level
                pixels.fill(color, y*w + band*w/6, y*w + (band+1)*w/6)
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    fun measure(c: Case, seed: Int, w: Int, h: Int): Pair<Int?, Long> {
        val before = page(w, h, 0, c.pattern, seed)
        val after = page(w, h, c.offset, c.pattern, if (c.newPage) seed+11 else seed)
        try {
            val previous = ScrollMatch.of(before)
            val start = System.nanoTime()
            val current = ScrollMatch.of(after)
            val offset = current.scrolledFrom(previous)
            return offset to (System.nanoTime() - start)
        } finally {
            before.recycle()
            after.recycle()
        }
    }
}
