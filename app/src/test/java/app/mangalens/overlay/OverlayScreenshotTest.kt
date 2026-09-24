package app.mangalens.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the overlay controls — the 文A button in each state, the status
 * pill with short, long and alert messages, and the open quick menu — over
 * a white page and a black page, to build/ui-preview. They float over any
 * reader content, so both extremes have to read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayScreenshotTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val outDir = File("build/ui-preview").apply { mkdirs() }
    private val density = context.resources.displayMetrics.density

    private fun dp(v: Float) = v * density

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

    private class FakeListener(private val paused: Boolean, private val auto: Boolean) : OverlayController.Listener {
        override fun onTranslateNow() = Unit
        override fun onTogglePause() = Unit
        override fun onToggleMode() = Unit
        override fun onPeek() = Unit
        override fun onNewSeries() = Unit
        override fun onOpenSettings() = Unit
        override fun onStopRequested() = Unit
        override fun isPaused() = paused
        override fun isAutoMode() = auto
    }

    /** A stand-in manga page: panel borders, a balloon with "lettering", and a screentone patch. */
    private fun drawPage(canvas: Canvas, area: RectF, dark: Boolean) {
        val bg = if (dark) Color.rgb(12, 12, 14) else Color.WHITE
        val ink = if (dark) Color.rgb(235, 235, 235) else Color.rgb(20, 20, 20)
        canvas.drawRect(area, Paint().apply { color = bg })
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.STROKE
            strokeWidth = dp(3f)
        }
        val m = dp(14f)
        val mid = area.top + area.height() * 0.55f
        canvas.drawRect(area.left + m, area.top + m, area.right - m, mid - m / 2, line)
        canvas.drawRect(area.left + m, mid + m / 2, area.centerX() - m / 2, area.bottom - m, line)
        canvas.drawRect(area.centerX() + m / 2, mid + m / 2, area.right - m, area.bottom - m, line)
        val tone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.rgb(90, 90, 90) else Color.rgb(170, 170, 170) }
        var y = mid + m + dp(6f)
        while (y < area.bottom - m - dp(4f)) {
            var x = area.centerX() + m + dp(6f)
            while (x < area.right - m - dp(4f)) {
                canvas.drawCircle(x, y, dp(2f), tone)
                x += dp(8f)
            }
            y += dp(8f)
        }
        val balloon = RectF(area.right - dp(190f), area.top + dp(40f), area.right - dp(40f), area.top + dp(130f))
        canvas.drawOval(balloon, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.BLACK else Color.WHITE })
        canvas.drawOval(balloon, line)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            strokeWidth = dp(5f)
            strokeCap = Paint.Cap.ROUND
        }
        for (i in 0 until 3) {
            val ly = balloon.top + dp(30f) + i * dp(15f)
            canvas.drawLine(balloon.left + dp(38f), ly, balloon.right - dp(38f + (i % 2) * 18f), ly, text)
        }
    }

    private fun measureWrap(v: View) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(dp(400f).toInt(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun drawAt(canvas: Canvas, v: View, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        v.draw(canvas)
        canvas.restore()
    }

    /** The controls row exactly as the overlay builds it: the button, then the pill. */
    private fun controlsRow(message: String?, setup: FloatingButtonView.() -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val btn = FloatingButtonView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52f).toInt(), dp(52f).toInt())
            setup()
        }
        row.addView(btn)
        if (message != null) {
            row.addView(OverlayStyle.statusPill(context).apply { OverlayStyle.showStatus(this, message) })
        }
        settle()
        measureWrap(row)
        return row
    }

    private fun write(bmp: Bitmap, name: String) {
        val file = File(outDir, "$name.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${file.absolutePath}")
        assertTrue(file.length() > 0)
    }

    @Test
    fun `button and pill states over a white page and a black page`() {
        val w = dp(411f).toInt()
        val half = dp(420f)
        val bmp = Bitmap.createBitmap(w, (half * 2).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        for ((i, dark) in listOf(false, true).withIndex()) {
            val top = half * i
            drawPage(canvas, RectF(0f, top, w.toFloat(), top + half), dark)
            val rows = listOf(
                controlsRow("awake!") { },
                controlsRow("I'm on! Stop scrolling and I'll translate") { setBusy(true) },
                controlsRow("napping · tap 文⁠A to wake me") { setPaused(true) },
                controlsRow("⚠ AI couldn't read this page — rate limited") { setManual(true) },
                controlsRow(null) { setBusy(true); setPaused(true) },
            )
            var y = top + dp(40f)
            for (row in rows) {
                drawAt(canvas, row, dp(10f), y)
                y += dp(72f)
            }
        }
        write(bmp, "30-overlay-controls")
    }

    @Test
    fun `quick menu open over a white page and a black page`() {
        val w = dp(411f).toInt()
        val h = dp(520f).toInt()
        for (dark in listOf(false, true)) {
            for (paused in listOf(false, true)) {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawPage(canvas, RectF(0f, 0f, w.toFloat(), h.toFloat()), dark)
                val row = controlsRow(null) { if (paused) setPaused(true) }
                drawAt(canvas, row, dp(8f), dp(24f))
                val menu = OverlayController.buildQuickMenu(context, FakeListener(paused, auto = !paused)) { }
                measureWrap(menu)
                // It hugs its longest item, as the overlay's wrap-content window lays it out.
                assertTrue(menu.measuredWidth < dp(320f))
                // Where the overlay places it: the controls' corner, 58dp down.
                drawAt(canvas, menu, dp(8f), dp(24f + 58f))
                write(bmp, "31-overlay-menu" + (if (paused) "-paused" else "") + (if (dark) "-black-page" else "-white-page"))
            }
        }
    }
}
