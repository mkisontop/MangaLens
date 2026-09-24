package app.mangalens.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import java.time.Duration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The button actually draws its states: a yellow disc when live, a dark one when paused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingButtonDrawTest {

    private fun render(setup: FloatingButtonView.() -> Unit): Bitmap {
        val v = FloatingButtonView(RuntimeEnvironment.getApplication())
        v.setup()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        val size = (52 * v.resources.displayMetrics.density).toInt()
        v.measure(
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp
    }

    /** A point inside the disc, below the glyph. */
    private fun discPixel(bmp: Bitmap): Int = bmp.getPixel(bmp.width / 2, (bmp.height * 0.72f).toInt())

    @Test
    fun `live is a yellow disc`() {
        val c = discPixel(render { })
        assertTrue("r=${Color.red(c)} g=${Color.green(c)} b=${Color.blue(c)}", Color.red(c) > 200 && Color.green(c) > 170 && Color.blue(c) < 90)
    }

    @Test
    fun `paused is a dark disc`() {
        val c = discPixel(render { setPaused(true) })
        assertTrue("r=${Color.red(c)} g=${Color.green(c)} b=${Color.blue(c)}", Color.red(c) < 80 && Color.green(c) < 80 && Color.blue(c) < 80)
    }

    @Test
    fun `busy draws its ring without trouble in either state`() {
        for (paused in listOf(false, true)) {
            val bmp = render {
                setPaused(paused)
                setBusy(true)
            }
            // The ring runs just inside the view's edge; some red must show there.
            var red = 0
            for (x in 0 until bmp.width) for (y in 0 until bmp.height) {
                val c = bmp.getPixel(x, y)
                if (Color.alpha(c) > 200 && Color.red(c) > 180 && Color.green(c) < 90 && Color.blue(c) < 90) red++
            }
            assertTrue("red ring pixels: $red", red > 50)
        }
    }
}
