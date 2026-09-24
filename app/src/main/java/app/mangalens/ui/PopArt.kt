package app.mangalens.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * The app's art is plain geometry: a balloon, a POW burst, speed lines and
 * Ben-Day dots. The pure functions here return points, so the shapes are
 * tested without a canvas, and the draw code caches their paths per size;
 * nothing here is a bitmap and nothing allocates per frame.
 */

/** One Ben-Day dot: its centre and radius, in pixels. */
internal data class HalftoneDot(val x: Float, val y: Float, val r: Float)

/** Which corner the dots radiate from. */
internal enum class DotCorner { TopEnd, BottomStart }

/**
 * Ben-Day dots fading out from ([originX], [originY]) across a [width] ×
 * [height] area: a staggered grid [spacing] apart whose dots shrink from
 * [maxDot] at the origin to nothing at [reach]. Dots smaller than
 * [minDot] are left out, since they would read as dirt, not as tone.
 */
internal fun halftoneDots(
    width: Float,
    height: Float,
    originX: Float,
    originY: Float,
    reach: Float,
    spacing: Float,
    maxDot: Float,
    minDot: Float,
): List<HalftoneDot> {
    if (width <= 0f || height <= 0f || spacing <= 0f || reach <= 0f) return emptyList()
    val out = ArrayList<HalftoneDot>()
    val rowStep = spacing / 2f
    val rows = (height / rowStep).toInt() + 1
    val cols = (width / spacing).toInt() + 1
    for (j in 0..rows) {
        val y = j * rowStep
        if (y < originY - reach || y > originY + reach) continue
        val shift = if (j % 2 == 1) spacing / 2f else 0f
        for (i in 0..cols) {
            val x = i * spacing + shift
            if (x > width + spacing) continue
            val dist = hypot(x - originX, y - originY)
            if (dist >= reach) continue
            val r = maxDot * (1f - dist / reach)
            if (r < minDot) continue
            out.add(HalftoneDot(x, y, r))
        }
    }
    return out
}

/**
 * Two corners of Ben-Day dots, the newsprint texture behind every page.
 * All dots are one path drawn once per size, so the background costs a
 * single draw call and never redraws while Fuki animates in its own layer.
 */
internal fun Modifier.halftone(
    color: Color,
    corner: DotCorner,
    reach: Dp = 260.dp,
    spacing: Dp = 14.dp,
    maxDot: Dp = 4.5.dp,
): Modifier = drawWithCache {
    val originX = if (corner == DotCorner.TopEnd) size.width else 0f
    val originY = if (corner == DotCorner.TopEnd) 0f else size.height
    val dots = halftoneDots(
        size.width, size.height, originX, originY,
        reach.toPx(), spacing.toPx(), maxDot.toPx(), 0.6.dp.toPx(),
    )
    val path = Path()
    for (d in dots) path.addOval(Rect(Offset(d.x, d.y), d.r))
    onDrawBehind { drawPath(path, color) }
}

/** The hand-drawn wobble of the burst's spikes: fixed, so the burst looks the same every time. */
private val BURST_JITTER = floatArrayOf(1f, .90f, 1.06f, .94f, 1.02f, .88f, 1.04f, .96f, 1f, .90f, 1.05f, .93f, 1.03f, .91f)

/**
 * The POW burst behind a ready Fuki: [spikes] × 2 points alternating an
 * outer tip (jittered, never inside [inner]) and an inner notch exactly on
 * [inner], turned by [rotationDeg].
 */
internal fun burstPoints(
    cx: Float,
    cy: Float,
    inner: Float,
    outer: Float,
    spikes: Int = 14,
    rotationDeg: Float = -6f,
): List<Offset> {
    val out = ArrayList<Offset>(spikes * 2)
    val step = 360f / (spikes * 2)
    for (k in 0 until spikes * 2) {
        val deg = rotationDeg - 90f + k * step
        val rad = deg * PI.toFloat() / 180f
        val r = if (k % 2 == 0) {
            maxOf(outer * BURST_JITTER[(k / 2) % BURST_JITTER.size], inner * 1.02f)
        } else inner
        out.add(Offset(cx + cos(rad) * r, cy + sin(rad) * r))
    }
    return out
}

private val SPEED_LENGTHS = floatArrayOf(0.30f, 0.18f, 0.24f, 0.14f)

/** One speed line, from [start] outwards to [end]. */
internal data class SpeedLine(val start: Offset, val end: Offset)

/**
 * Speed lines radiating from a running Fuki of radius [r]. The sector
 * between 105° and 155° (clockwise from +x, y down) is left bare: the
 * balloon's tail points there, and lines through it would read as a crack.
 * [progress] grows every line from nothing to its full length.
 */
internal fun speedLines(cx: Float, cy: Float, r: Float, progress: Float): List<SpeedLine> {
    val out = ArrayList<SpeedLine>(32)
    for (i in 0 until 32) {
        val deg = i * 11.25f + (i % 3) * 2f
        if (deg in 105f..155f) continue
        val rad = deg * PI.toFloat() / 180f
        val dx = cos(rad)
        val dy = sin(rad)
        val from = r * 1.12f
        val to = from + r * SPEED_LENGTHS[i % 4] * progress.coerceIn(0f, 1f)
        out.add(SpeedLine(Offset(cx + dx * from, cy + dy * from), Offset(cx + dx * to, cy + dy * to)))
    }
    return out
}

/**
 * Fuki's body: a round balloon with a curled tail at the lower left. Both
 * tail base points lie inside the circle, so the union is one clean
 * outline and a single stroke draws it.
 */
internal fun balloonPath(center: Offset, r: Float): Path {
    fun p(x: Float, y: Float) = Offset(center.x + x * r, center.y + y * r)
    val circle = Path().apply { addOval(Rect(center, r)) }
    val tail = Path().apply {
        val a = p(-0.52f, 0.72f)
        moveTo(a.x, a.y)
        val c1 = p(-0.78f, 1.02f)
        val e1 = p(-0.98f, 1.14f)
        quadraticTo(c1.x, c1.y, e1.x, e1.y)
        val c2 = p(-0.46f, 1.04f)
        val e2 = p(-0.18f, 0.90f)
        quadraticTo(c2.x, c2.y, e2.x, e2.y)
        close()
    }
    return Path().apply { op(circle, tail, PathOperation.Union) }
}

/**
 * Draws Fuki's face for [mood] on a body of radius [r] at [center].
 * [blink] is the open eyes' vertical scale (1 open, 0.1 shut) and
 * [bodyColor] picks a blush that shows on it. Every size is a multiple of
 * [r], so the face is the same at 104dp in the checklist and 240dp home.
 */
internal fun DrawScope.drawFukiFace(
    mood: FukiMood,
    center: Offset,
    r: Float,
    blink: Float,
    faceInk: Color,
    blush: Color?,
) {
    fun at(x: Float, y: Float) = Offset(center.x + x * r, center.y + y * r)
    val eyes = listOf(at(-0.27f, -0.24f), at(0.27f, -0.24f))

    blush?.let { c ->
        for (sx in floatArrayOf(-0.46f, 0.46f)) {
            val o = at(sx, -0.06f)
            drawOval(c, topLeft = Offset(o.x - 0.075f * r, o.y - 0.0375f * r), size = Size(0.15f * r, 0.075f * r))
        }
    }

    when (mood) {
        FukiMood.AWAKE -> for (e in eyes) {
            scale(scaleX = 1f, scaleY = blink.coerceIn(0.05f, 1f), pivot = e) {
                val eyeSize = Size(0.20f * r, 0.25f * r)
                val eyeTopLeft = Offset(e.x - 0.10f * r, e.y - 0.125f * r)
                drawOval(Color.White, eyeTopLeft, eyeSize)
                drawOval(faceInk, eyeTopLeft, eyeSize, style = Stroke(0.03f * r))
                val pupil = Offset(e.x + 0.015f * r, e.y + 0.035f * r)
                drawCircle(faceInk, 0.065f * r, pupil)
                drawCircle(Color.White, 0.02f * r, Offset(pupil.x - 0.02f * r, pupil.y - 0.02f * r))
            }
        }
        FukiMood.SLEEPY, FukiMood.NAPPING -> for (e in eyes) {
            drawArc(
                faceInk, 20f, 140f, false,
                topLeft = Offset(e.x - 0.10f * r, e.y - 0.07f * r), size = Size(0.20f * r, 0.14f * r),
                style = Stroke(0.05f * r, cap = StrokeCap.Round),
            )
        }
        FukiMood.HAPPY -> for (e in eyes) {
            drawArc(
                faceInk, 200f, 140f, false,
                topLeft = Offset(e.x - 0.10f * r, e.y - 0.08f * r), size = Size(0.20f * r, 0.16f * r),
                style = Stroke(0.055f * r, cap = StrokeCap.Round),
            )
        }
        FukiMood.SQUINT -> {
            val stroke = Stroke(0.05f * r, cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (side in floatArrayOf(-1f, 1f)) {
                val path = Path().apply {
                    val a = at(side * 0.36f, -0.33f)
                    val b = at(side * 0.20f, -0.24f)
                    val c = at(side * 0.36f, -0.15f)
                    moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y)
                }
                drawPath(path, faceInk, style = stroke)
            }
        }
    }

    when (mood) {
        FukiMood.AWAKE, FukiMood.SQUINT -> {
            val k = if (mood == FukiMood.SQUINT) 0.7f else 1f
            val w = 0.22f * r * k
            val h = 0.14f * r * k
            val c = at(0f, -0.02f)
            drawArc(
                faceInk, 15f, 150f, false,
                topLeft = Offset(c.x - w / 2f, c.y - h / 2f), size = Size(w, h),
                style = Stroke(0.045f * r, cap = StrokeCap.Round),
            )
        }
        FukiMood.HAPPY -> {
            val c = at(0f, -0.02f)
            drawArc(
                faceInk, 0f, 180f, true,
                topLeft = Offset(c.x - 0.12f * r, c.y - 0.12f * r), size = Size(0.24f * r, 0.24f * r),
            )
        }
        FukiMood.SLEEPY, FukiMood.NAPPING ->
            drawCircle(faceInk, 0.05f * r, at(0f, 0.04f), style = Stroke(0.035f * r))
    }
}
