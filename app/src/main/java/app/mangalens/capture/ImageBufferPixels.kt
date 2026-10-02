package app.mangalens.capture

import app.mangalens.gaps.PixelSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/**
 * Pixels of a captured frame, read straight out of the capture buffer — no copy of the
 * frame, which at a phone's resolution is ten megabytes.
 *
 * The buffer is RGBA, four bytes a pixel, rows [rowInts] pixels apart (the stride may be
 * wider than the screen). It is viewed through a private duplicate, rewound: the frame loop
 * reads and repositions the same buffer for its own copy, and must not be disturbed — nor
 * disturb this.
 */
class ImageBufferPixels(
    buffer: ByteBuffer,
    private val rowInts: Int,
    override val width: Int,
    override val height: Int,
) : PixelSource {

    private val ints: IntBuffer = buffer.duplicate().also { it.clear() }.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()

    override fun readRow(y: Int, dst: IntArray) {
        ints.position(y * rowInts)
        ints.get(dst, 0, width)
    }

    override fun pixel(x: Int, y: Int): Int = ints.get(y * rowInts + x)
}
