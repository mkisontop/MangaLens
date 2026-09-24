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

/**
 * The hand-drawn wobble of the burst's spikes: fixed, so the burst looks the
 * same every time, and deliberately uneven. Evenly spaced, even-length spikes
 * around a round face read as a cartoon sun; long and stubby spikes side by
 * side read as a POW.
 */
private val BURST_JITTER = floatArrayOf(1.0f, .70f, 1.20f, .80f, 1.10f, .66f, 1.24f, .84f, 1.06f, .74f, 1.16f)

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
    spikes: Int = 11,
    rotationDeg: Float = -4f,
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

private val SPEED_LENGTHS = floatArrayOf(0.46f, 0.22f, 0.34f, 0.16f)

/** Where the speed lines start and how far out they may reach, as multiples of Fuki's radius. */
private const val SPEED_FROM = 1.16f
private const val SPEED_REACH = 1.55f

/** One speed line, from [start] outwards to [end]. */
internal data class SpeedLine(val start: Offset, val end: Offset)

/**
 * Manga focus lines (集中線) radiating from a running Fuki of radius [r].
 * The lengths vary and every fifth line is dropped, so they cluster
 * unevenly: evenly pitched strokes of one length read as sun rays. The
 * sector between 105° and 155° (clockwise from +x, y down) is left bare:
 * the balloon's tail points there, and lines through it would read as a
 * crack. No line reaches past [SPEED_REACH], which keeps the top one clear
 * of the headline. [progress] grows every line from nothing to its length.
 */
internal fun speedLines(cx: Float, cy: Float, r: Float, progress: Float): List<SpeedLine> {
    val out = ArrayList<SpeedLine>(32)
    for (i in 0 until 32) {
        if (i % 5 == 4) continue
        val deg = i * 11.25f + (i % 3) * 2f
        if (deg in 105f..155f) continue
        val rad = deg * PI.toFloat() / 180f
        val dx = cos(rad)
        val dy = sin(rad)
        val from = r * SPEED_FROM
        val full = minOf(r * SPEED_LENGTHS[i % 4], r * (SPEED_REACH - SPEED_FROM))
        val to = from + full * progress.coerceIn(0f, 1f)
        out.add(SpeedLine(Offset(cx + dx * from, cy + dy * from), Offset(cx + dx * to, cy + dy * to)))
    }
    return out
}

/**
 * Speed lines as tapered wedges, thin at Fuki and [width] wide at the far
 * end: a hand-inked focus line swells as it leaves the centre.
 */
internal fun speedWedges(lines: List<SpeedLine>, width: Float): Path = Path().apply {
    val half = width / 2f
    for (l in lines) {
        val dx = l.end.x - l.start.x
        val dy = l.end.y - l.start.y
        val len = hypot(dx, dy)
        if (len < 0.5f) continue
        val nx = -dy / len * half
        val ny = dx / len * half
        moveTo(l.start.x, l.start.y)
        lineTo(l.end.x + nx, l.end.y + ny)
        lineTo(l.end.x - nx, l.end.y - ny)
        close()
    }
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
        val c1 = p(-0.74f, 0.98f)
        val e1 = p(-0.96f, 1.09f)
        quadraticTo(c1.x, c1.y, e1.x, e1.y)
        // A rounded tip about a tenth of the radius thick: a hairline tip
        // left the offset shadow showing below it as a second prong.
        val ct = p(-1.04f, 1.16f)
        val et = p(-0.90f, 1.18f)
        quadraticTo(ct.x, ct.y, et.x, et.y)
        val c2 = p(-0.50f, 1.12f)
        val e2 = p(-0.18f, 0.90f)
        quadraticTo(c2.x, c2.y, e2.x, e2.y)
        close()
    }
    return Path().apply { op(circle, tail, PathOperation.Union) }
}

/**
 * Draws Fuki's face for [mood] on a body of radius [r] at [center].
 * [blink] is the open eyes' vertical scale (1 open, 0.1 shut) and
 * [blush] is a cheek colour that shows on the body, or null for none.
 * Every size is a multiple of [r], so the face is the same at 40dp beside a
 * tip, 104dp in the checklist and 230dp on the home screen.
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

    // An open eye; [lookUp] lifts the pupil, which is most of what reads as worry.
    fun openEye(e: Offset, lookUp: Float = 0f) {
        scale(scaleX = 1f, scaleY = blink.coerceIn(0.05f, 1f), pivot = e) {
            val eyeSize = Size(0.20f * r, 0.25f * r)
            val eyeTopLeft = Offset(e.x - 0.10f * r, e.y - 0.125f * r)
            drawOval(Color.White, eyeTopLeft, eyeSize)
            drawOval(faceInk, eyeTopLeft, eyeSize, style = Stroke(0.03f * r))
            val pupil = Offset(e.x + 0.015f * r, e.y + (0.035f - lookUp) * r)
            drawCircle(faceInk, 0.065f * r, pupil)
            drawCircle(Color.White, 0.02f * r, Offset(pupil.x - 0.02f * r, pupil.y - 0.02f * r))
        }
    }

    fun shutEye(e: Offset) = drawArc(
        faceInk, 20f, 140f, false,
        topLeft = Offset(e.x - 0.10f * r, e.y - 0.07f * r), size = Size(0.20f * r, 0.14f * r),
        style = Stroke(0.05f * r, cap = StrokeCap.Round),
    )

    when (mood) {
        FukiMood.AWAKE -> for (e in eyes) openEye(e)
        FukiMood.WORRIED -> {
            for (e in eyes) openEye(e, lookUp = 0.03f)
            // Brows raised toward the middle.
            val brow = Stroke(0.04f * r, cap = StrokeCap.Round)
            for (side in floatArrayOf(-1f, 1f)) {
                drawLine(faceInk, at(side * 0.38f, -0.42f), at(side * 0.18f, -0.49f), brow.width, cap = StrokeCap.Round)
            }
        }
        FukiMood.PEEK -> {
            openEye(eyes[0])
            shutEye(eyes[1])
        }
        FukiMood.SLEEPY, FukiMood.NAPPING -> for (e in eyes) shutEye(e)
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
        FukiMood.WORRIED -> {
            val wave = Path().apply {
                val a = at(-0.10f, 0.02f)
                moveTo(a.x, a.y)
                val c1 = at(-0.05f, -0.03f)
                val m = at(0f, 0.02f)
                quadraticTo(c1.x, c1.y, m.x, m.y)
                val c2 = at(0.05f, 0.07f)
                val b = at(0.10f, 0.02f)
                quadraticTo(c2.x, c2.y, b.x, b.y)
            }
            drawPath(wave, faceInk, style = Stroke(0.045f * r, cap = StrokeCap.Round, join = StrokeJoin.Round))
            // A sweat drop at the temple.
            val c = at(0.55f, -0.52f)
            val rr = 0.07f * r
            val drop = Path().apply {
                moveTo(c.x, c.y - rr * 2.3f)
                quadraticTo(c.x + rr * 1.05f, c.y - rr * 0.9f, c.x + rr, c.y)
                arcTo(Rect(c, rr), 0f, 180f, false)
                quadraticTo(c.x - rr * 1.05f, c.y - rr * 0.9f, c.x, c.y - rr * 2.3f)
                close()
            }
            drawPath(drop, Color.White)
            drawPath(drop, faceInk, style = Stroke(0.025f * r, join = StrokeJoin.Round))
        }
        FukiMood.SLEEPY, FukiMood.NAPPING, FukiMood.PEEK ->
            drawCircle(faceInk, 0.05f * r, at(0f, 0.04f), style = Stroke(0.035f * r))
    }
}
