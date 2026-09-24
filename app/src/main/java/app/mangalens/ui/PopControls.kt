package app.mangalens.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mangalens.update.UpdateChecker
import kotlin.math.roundToInt

/*
 * The POP! kit. Every pressable thing sits on a hard offset shadow and
 * sinks into it when pressed; the sink is the press indication, so there
 * is no ripple. Animated values are handed down as lambdas and read only
 * in layout, draw or graphicsLayer blocks, so a press or a colour fade
 * redraws a sticker without recomposing the screen around it.
 */

// ---- haptics and wiggle ----

internal enum class Buzz { CONFIRM, REJECT, TICK }

/** The three haptics the app uses, on the closest constant older Android has. */
internal fun View.buzz(kind: Buzz) {
    val constant = when (kind) {
        Buzz.CONFIRM ->
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP
        Buzz.REJECT ->
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        Buzz.TICK -> HapticFeedbackConstants.CLOCK_TICK
    }
    performHapticFeedback(constant)
}

/**
 * A quick "no, over here" shake. It only turns a graphics layer, so the
 * shaken thing never re-lays out; with reduced motion the haptic that
 * comes with it carries the message alone.
 */
@Stable
internal class Wiggle {
    val angle = Animatable(0f)

    suspend fun play(reduced: Boolean) {
        if (reduced) return
        angle.snapTo(0f)
        angle.animateTo(0f, keyframes {
            durationMillis = 360
            -6f at 90
            5f at 180
            -3f at 270
        })
    }
}

@Composable
internal fun rememberWiggle(): Wiggle = remember { Wiggle() }

internal fun Modifier.wiggle(w: Wiggle): Modifier = graphicsLayer { rotationZ = w.angle.value }

// ---- the sticker surface ----

/**
 * How far a sticker's face has sunk into its shadow, 0 at rest and 1
 * pressed or [held]. It drops fast and springs back past rest a little,
 * which is what makes a press feel like a real button.
 */
@Composable
internal fun rememberSink(interaction: MutableInteractionSource, held: Boolean = false): State<Float> {
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val target = if (pressed || held) 1f else 0f
    return animateFloatAsState(
        targetValue = target,
        animationSpec = when {
            reduced -> snap()
            target == 1f -> tween(70, easing = FastOutLinearInEasing)
            else -> spring(dampingRatio = 0.45f, stiffness = 700f)
        },
        label = "sink",
    )
}

/**
 * The shape inset by half a stroke, so a stroke centred on it ends exactly
 * at the bounds. Fill and stroke both follow this one outline: a fill of
 * the full shape would peek past a stroke drawn on a smaller one at every
 * rounded corner.
 */
private fun CacheDrawScope.insetOutline(shape: Shape, size: Size, sw: Float): Outline =
    shape.createOutline(Size(size.width - sw, size.height - sw), layoutDirection, this)

/**
 * Fill behind the content, ink outline over it, both kept inside the
 * bounds. The join is round: a balloon's sharp tail would otherwise grow a
 * mitred spike well past its own tip.
 */
private fun Modifier.popFace(shape: Shape, fill: () -> Color, strokeColor: Color, stroke: Dp): Modifier =
    drawWithCache {
        val sw = stroke.toPx()
        val outline = insetOutline(shape, size, sw)
        val style = Stroke(sw, join = StrokeJoin.Round)
        onDrawWithContent {
            translate(sw / 2f, sw / 2f) { drawOutline(outline, fill()) }
            drawContent()
            if (sw > 0f) translate(sw / 2f, sw / 2f) { drawOutline(outline, strokeColor, style = style) }
        }
    }

/**
 * A die-cut sticker: a face with an ink outline on a hard shadow [depth]
 * down and right. The shadow stays put while the face moves by
 * [sunk] × [depth], so a press looks like pushing the sticker into the page.
 * In the dark the shadow gets a faint cream rim, since black on near-black
 * would not read as depth at all. [shadowShape] lets a balloon cast its
 * shadow from its body alone: a shadowed tail shows as a ghost second tail.
 */
@Composable
internal fun PopSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    color: Color = LocalPop.current.surface,
    fill: (() -> Color)? = null,
    sunk: () -> Float = { 0f },
    depth: Dp = 5.dp,
    stroke: Dp = 3.dp,
    contentColor: Color = LocalPop.current.ink,
    strokeColor: Color = LocalPop.current.stroke,
    shadowShape: Shape = shape,
    content: @Composable BoxScope.() -> Unit,
) {
    val pop = LocalPop.current
    val faceFill = fill ?: { color }
    Box(
        modifier
            .drawWithCache {
                val d = depth.toPx()
                val sw = stroke.toPx()
                val outline = insetOutline(shadowShape, Size(size.width - d, size.height - d), sw)
                val ringW = 1.5.dp.toPx()
                val rim = pop.shadowStroke
                onDrawBehind {
                    if (d > 0f) translate(d + sw / 2f, d + sw / 2f) {
                        if (rim != null) drawOutline(outline, rim, style = Stroke(sw + 2f * ringW, join = StrokeJoin.Round))
                        drawOutline(outline, pop.shadow)
                        if (sw > 0f) drawOutline(outline, pop.shadow, style = Stroke(sw, join = StrokeJoin.Round))
                    }
                }
            }
            .padding(end = depth, bottom = depth),
        propagateMinConstraints = true,
    ) {
        Box(
            Modifier
                .offset {
                    val o = (depth.toPx() * sunk()).roundToInt()
                    IntOffset(o, o)
                }
                .popFace(shape, faceFill, strokeColor, stroke),
            propagateMinConstraints = true,
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
        }
    }
}

// ---- buttons and links ----

/** Zap is the next thing to do, Surface everything else, Warn a problem to fix. */
internal enum class StickerStyle { Zap, Surface, Warn }

/**
 * The app's button: a sticker that says literally what it does. Zap is
 * the next thing to do; Surface is everything else. Full width by default,
 * capped so it never becomes a banner on a tablet.
 */
@Composable
internal fun StickerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: StickerStyle = StickerStyle.Zap,
    height: Dp = 56.dp,
    leadingIcon: ImageVector? = null,
    trailing: String? = null,
    enabled: Boolean = true,
    fillWidth: Boolean = true,
    wiggle: Wiggle? = null,
    contentDescription: String? = null,
) {
    val pop = LocalPop.current
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val sink = rememberSink(interaction)
    val fill = when {
        !enabled -> pop.surfaceHi
        style == StickerStyle.Zap -> pop.zap
        style == StickerStyle.Warn -> pop.zapSoft
        else -> pop.surface
    }
    val fg = when {
        !enabled -> pop.inkSoft
        style == StickerStyle.Zap -> pop.onZap
        style == StickerStyle.Warn -> pop.punchText
        else -> pop.ink
    }
    val corner = when {
        height >= 64.dp -> 20.dp
        height >= 56.dp -> 18.dp
        else -> 16.dp
    }
    PopSurface(
        modifier = modifier
            .then(if (fillWidth) Modifier.widthIn(max = 420.dp).fillMaxWidth() else Modifier)
            .then(if (wiggle != null) Modifier.wiggle(wiggle) else Modifier),
        shape = RoundedCornerShape(corner),
        color = fill,
        sunk = { sink.value },
        depth = if (enabled) 4.dp else 0.dp,
        stroke = 3.dp,
        contentColor = fg,
        strokeColor = if (enabled && style == StickerStyle.Warn) pop.zapSoftStroke else pop.stroke,
    ) {
        Row(
            Modifier
                .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) {
                    view.buzz(Buzz.CONFIRM)
                    onClick()
                }
                .then(
                    if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription }
                    else Modifier
                )
                .heightIn(min = height)
                .padding(horizontal = if (fillWidth) 20.dp else 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, tint = fg, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = MaterialTheme.typography.titleMedium, color = fg, textAlign = TextAlign.Center)
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                Text(trailing, style = MaterialTheme.typography.titleMedium, color = fg)
            }
        }
    }
}

/**
 * A plain link for secondary actions: ink with a dotted underline, and
 * still a full 48dp to hit. Red is kept for errors, so a link never reads
 * as a warning and a warning never reads as a link.
 */
@Composable
internal fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pop = LocalPop.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = pop.ink,
            onTextLayout = { layout = it },
            modifier = Modifier.drawBehind {
                val l = layout ?: return@drawBehind
                val w = 1.5.dp.toPx()
                val dots = PathEffect.dashPathEffect(floatArrayOf(0.01f, w * 2.4f), 0f)
                for (i in 0 until l.lineCount) {
                    val y = l.getLineBaseline(i) + 3.dp.toPx()
                    drawLine(
                        pop.inkSoft, Offset(l.getLineLeft(i) + w, y), Offset(l.getLineRight(i), y),
                        strokeWidth = w, cap = StrokeCap.Round, pathEffect = dots,
                    )
                }
            },
        )
    }
}

// ---- toggles ----

/**
 * A whole sticker row that toggles, so the target is the row, not a thumb
 * the size of a fingertip. The title says what it does; the description
 * says what that costs or gets you.
 */
@Composable
internal fun PopToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pop = LocalPop.current
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val sink = rememberSink(interaction)
    PopSurface(modifier.fillMaxWidth(), RoundedCornerShape(20.dp), color = pop.surface, sunk = { sink.value }) {
        Row(
            Modifier
                .toggleable(checked, interaction, indication = null, role = Role.Switch) {
                    view.buzz(Buzz.TICK)
                    onCheckedChange(it)
                }
                .heightIn(min = 72.dp)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = pop.ink)
                Spacer(Modifier.height(2.dp))
                Text(description, style = MaterialTheme.typography.bodyMedium, color = pop.inkSoft)
            }
            Spacer(Modifier.width(12.dp))
            PopSwitch(checked)
        }
    }
}

/**
 * The switch thumb has a face: awake when on, asleep when off. Colour is
 * never the only cue, and the face swaps at the thumb's midpoint so it
 * reads as the thumb waking up on its way across.
 */
@Composable
internal fun PopSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val pop = LocalPop.current
    val reduced = LocalReducedMotion.current
    val travel = animateFloatAsState(
        if (checked) 1f else 0f,
        if (reduced) snap() else spring(dampingRatio = 0.6f, stiffness = 800f),
        label = "thumb",
    )
    val track = animateColorAsState(
        if (checked) pop.punch else pop.surfaceHi,
        if (reduced) snap() else tween(150),
        label = "track",
    )
    Canvas(modifier.size(64.dp, 36.dp).clearAndSetSemantics { }) {
        val sw = 2.5.dp.toPx()
        drawRoundRect(track.value, cornerRadius = CornerRadius(size.height / 2f))
        drawRoundRect(
            pop.stroke,
            topLeft = Offset(sw / 2f, sw / 2f),
            size = Size(size.width - sw, size.height - sw),
            cornerRadius = CornerRadius((size.height - sw) / 2f),
            style = Stroke(sw),
        )
        val t = travel.value
        val thumbR = 14.dp.toPx()
        val cx = 4.dp.toPx() + thumbR + (28.dp.toPx() * t)
        val cy = size.height / 2f
        drawCircle(Color.White, thumbR, Offset(cx, cy))
        drawCircle(pop.stroke, thumbR - sw / 2f, Offset(cx, cy), style = Stroke(sw))
        val eyeDx = 5.dp.toPx()
        if (t >= 0.5f) {
            drawCircle(pop.faceInk, 2.5.dp.toPx(), Offset(cx - eyeDx, cy - 1.dp.toPx()))
            drawCircle(pop.faceInk, 2.5.dp.toPx(), Offset(cx + eyeDx, cy - 1.dp.toPx()))
        } else {
            val half = 2.5.dp.toPx()
            for (sx in floatArrayOf(-eyeDx, eyeDx)) {
                drawLine(
                    pop.faceInk, Offset(cx + sx - half, cy), Offset(cx + sx + half, cy),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                )
            }
        }
    }
}

// ---- tiles ----

/** One choice in a [PopTiles] grid. [glyph] is decoration; [title] carries the meaning. */
internal data class PopTile<T>(val value: T, val title: String, val caption: String? = null, val glyph: String? = null)

/**
 * A radio group of big tiles. The selected tile stays pressed in, turns
 * red and wears a check, so the choice shows three ways at once. A short
 * last row stretches across the width rather than leaving a hole, so five
 * choices in two columns end on one wide tile, not a lopsided single.
 */
@Composable
internal fun <T> PopTiles(
    options: List<PopTile<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    columns: Int,
    modifier: Modifier = Modifier,
    minHeight: Dp = 72.dp,
) {
    Column(modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        options.chunked(columns.coerceAtLeast(1)).forEach { row ->
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { option ->
                    PopTileBox(
                        option, option.value == selected, { onSelect(option.value) }, minHeight,
                        Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> PopTileBox(option: PopTile<T>, selected: Boolean, onClick: () -> Unit, minHeight: Dp, modifier: Modifier) {
    val pop = LocalPop.current
    val view = LocalView.current
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val sink = rememberSink(interaction, held = selected)
    val fill = animateColorAsState(
        if (selected) pop.punch else pop.surface,
        if (reduced) snap() else tween(150),
        label = "tile",
    )
    val fg = if (selected) pop.onPunch else pop.ink
    Box(modifier) {
        PopSurface(
            Modifier.fillMaxSize(),
            RoundedCornerShape(18.dp),
            fill = { fill.value },
            sunk = { sink.value },
            depth = TILE_DEPTH,
            stroke = 2.5.dp,
            contentColor = fg,
        ) {
            Box(
                Modifier.selectable(selected, interaction, indication = null, role = Role.RadioButton) {
                    view.buzz(Buzz.TICK)
                    onClick()
                }
            ) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .heightIn(min = minHeight)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (option.glyph != null) {
                        // A fixed box, so ★, 한, 日 and 中 — each from its own
                        // fallback font — put the captions under them on one line.
                        val box = with(LocalDensity.current) { 36.sp.toDp() }
                        Box(Modifier.height(box), contentAlignment = Alignment.Center) {
                            Text(
                                option.glyph,
                                fontFamily = FontFamily.Default,
                                fontSize = 28.sp,
                                lineHeight = 32.sp,
                                color = fg,
                                modifier = Modifier
                                    .wrapContentHeight(unbounded = true)
                                    .clearAndSetSemantics { },
                            )
                        }
                    }
                    Text(
                        option.title,
                        style = if (option.glyph != null) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,
                        color = fg,
                        textAlign = TextAlign.Center,
                    )
                    if (option.caption != null) {
                        Text(
                            option.caption,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) pop.onPunch else pop.inkSoft,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        // Half off the corner, like a sticker slapped on a sticker, and
        // clear of the tile's own words. It sits beside the tile rather than
        // inside it because the face draws its outline over its content: a
        // badge inside would wear the tile's edge across its check. A
        // selected tile is always fully sunk, so the badge is placed against
        // the sunk face, [TILE_DEPTH] down from the top of this box.
        AnimatedVisibility(
            visible = selected,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = TILE_DEPTH - 6.dp),
            enter = if (reduced) scaleIn(snap()) else scaleIn(spring(dampingRatio = 0.5f, stiffness = 600f)),
            exit = fadeOut(snap()),
        ) {
            Box(
                Modifier
                    .size(18.dp)
                    .drawWithCache {
                        val sw = 2.dp.toPx()
                        onDrawBehind {
                            drawCircle(pop.zap)
                            drawCircle(pop.stroke, radius = size.minDimension / 2f - sw / 2f, style = Stroke(sw))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = pop.onZap, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/** How far a tile stands off its shadow; a selected tile sinks this far. */
private val TILE_DEPTH = 4.dp

// ---- slider ----

/**
 * A slider that keeps its value local while dragging and commits once on
 * release, so a drag is one settings write, not dozens. [below] sees the
 * live value, which is how the text-size preview grows under the finger.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PopSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    startLabel: String? = null,
    endLabel: String? = null,
    below: @Composable (Float) -> Unit = {},
) {
    val pop = LocalPop.current
    var v by remember(value) { mutableFloatStateOf(value) }
    val colors = SliderDefaults.colors(
        thumbColor = pop.zap,
        activeTrackColor = pop.punch,
        inactiveTrackColor = pop.surfaceHi,
    )
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = pop.ink, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(display(v), style = MaterialTheme.typography.titleMedium, color = pop.punchText)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (startLabel != null) {
                Text(startLabel, style = MaterialTheme.typography.bodyMedium, color = pop.inkSoft)
                Spacer(Modifier.width(8.dp))
            }
            Slider(
                value = v,
                onValueChange = { v = it },
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = title
                        stateDescription = display(v)
                    },
                onValueChangeFinished = { onCommit(v) },
                colors = colors,
                thumb = {
                    Box(
                        Modifier
                            .size(28.dp)
                            .drawWithCache {
                                val sw = 3.dp.toPx()
                                onDrawBehind {
                                    drawCircle(pop.zap)
                                    drawCircle(pop.stroke, radius = size.minDimension / 2f - sw / 2f, style = Stroke(sw))
                                }
                            }
                    )
                },
                track = { state -> PopTrack(state) },
                valueRange = range,
            )
            if (endLabel != null) {
                Spacer(Modifier.width(8.dp))
                Text(endLabel, style = MaterialTheme.typography.bodyMedium, color = pop.inkSoft)
            }
        }
        below(v)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PopTrack(state: SliderState) {
    val pop = LocalPop.current
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        val span = state.valueRange.endInclusive - state.valueRange.start
        val f = if (span > 0f) ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f) else 0f
        val sw = 2.dp.toPx()
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(pop.surfaceHi, cornerRadius = radius)
        if (f > 0f) drawRoundRect(pop.punch, size = Size(maxOf(size.height, size.width * f), size.height), cornerRadius = radius)
        drawRoundRect(
            pop.stroke,
            topLeft = Offset(sw / 2f, sw / 2f),
            size = Size(size.width - sw, size.height - sw),
            cornerRadius = CornerRadius((size.height - sw) / 2f),
            style = Stroke(sw),
        )
    }
}

// ---- text fields ----

/** The small caption over a field. It sits above, never in the outline, so nothing notches the box. */
@Composable
internal fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = LocalPop.current.inkSoft,
        modifier = modifier.padding(bottom = 4.dp).clearAndSetSemantics { },
    )
}

/**
 * A text field for keys, model ids and URLs: monospace, never
 * autocorrected, and masked unless [secret] is off or the reader taps
 * Show. The key itself is never echoed anywhere else on screen.
 *
 * The [label] sits above the box (see [FieldLabel]) rather than floating
 * in its outline, where the notch it cut showed as a paper-coloured bite
 * out of the field. [showLabel] is off when the caller places the label
 * itself, over a row that holds more than the field. [isError] marks a
 * field that must be filled in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    secret: Boolean = true,
    placeholder: String? = null,
    keyboardType: KeyboardType = if (secret) KeyboardType.Password else KeyboardType.Ascii,
    showLabel: Boolean = true,
    isError: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    val pop = LocalPop.current
    var shown by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    val border = if (isError) pop.punchText else pop.stroke
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = pop.ink,
        unfocusedTextColor = pop.ink,
        focusedContainerColor = pop.surface,
        unfocusedContainerColor = pop.surface,
        focusedBorderColor = border,
        unfocusedBorderColor = if (isError) border else border.copy(alpha = 0.6f),
        focusedPlaceholderColor = pop.inkSoft,
        unfocusedPlaceholderColor = pop.inkSoft,
        cursorColor = pop.ink,
    )
    val transformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None
    Column(modifier.fillMaxWidth()) {
        if (showLabel) FieldLabel(label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label },
            singleLine = true,
            textStyle = MonoStyle.copy(color = pop.ink),
            cursorBrush = SolidColor(pop.ink),
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            interactionSource = interaction,
            decorationBox = { inner ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value,
                    innerTextField = inner,
                    enabled = true,
                    singleLine = true,
                    visualTransformation = transformation,
                    interactionSource = interaction,
                    placeholder = placeholder?.let { p -> { Text(p, style = MonoStyle, color = pop.inkSoft) } },
                    trailingIcon = if (secret) {
                        {
                            Box(
                                Modifier
                                    .minimumInteractiveComponentSize()
                                    .clickable(role = Role.Button) { shown = !shown }
                                    .semantics { contentDescription = if (shown) "Hide key" else "Show key" }
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (shown) "Hide" else "Show",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = pop.ink,
                                    modifier = Modifier.clearAndSetSemantics { },
                                )
                            }
                        }
                    } else null,
                    colors = colors,
                    contentPadding = OutlinedTextFieldDefaults.contentPadding(),
                    container = {
                        OutlinedTextFieldDefaults.Container(
                            enabled = true,
                            isError = false,
                            interactionSource = interaction,
                            colors = colors,
                            shape = shape,
                            focusedBorderThickness = 2.5.dp,
                            unfocusedBorderThickness = if (isError) 2.5.dp else 2.dp,
                        )
                    },
                )
            },
        )
    }
}

// ---- speech balloons ----

internal enum class Tail { Top, Start, End }

/**
 * A rounded balloon with a triangular tail, unioned into one outline so a
 * single stroke draws both and there is no seam where they meet.
 */
private class BalloonShape(
    private val tail: Tail,
    private val tailAt: Float,
    private val corner: Dp,
    private val tailSize: Dp,
    private val withTail: Boolean = true,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val t = with(density) { tailSize.toPx() }
        val c = with(density) { corner.toPx() }
        val side = when {
            layoutDirection == LayoutDirection.Rtl && tail == Tail.Start -> Tail.End
            layoutDirection == LayoutDirection.Rtl && tail == Tail.End -> Tail.Start
            else -> tail
        }
        val rect = when (side) {
            Tail.Top -> Rect(0f, t, size.width, size.height)
            Tail.Start -> Rect(t, 0f, size.width, size.height)
            Tail.End -> Rect(0f, 0f, size.width - t, size.height)
        }
        val body = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(minOf(c, rect.height / 2f)))) }
        if (!withTail) return Outline.Generic(body)
        val half = t * 0.62f
        val overlap = t * 0.5f
        val tri = Path().apply {
            when (side) {
                Tail.Top -> {
                    val x = (rect.left + rect.width * tailAt).coerceIn(rect.left + c + half, rect.right - c - half)
                    moveTo(x - half, rect.top + overlap)
                    lineTo(x + t * 0.15f, 0f)
                    lineTo(x + half, rect.top + overlap)
                }
                Tail.Start -> {
                    val y = (rect.top + rect.height * tailAt).coerceIn(rect.top + half, rect.bottom - half)
                    moveTo(rect.left + overlap, y - half)
                    lineTo(0f, y + t * 0.15f)
                    lineTo(rect.left + overlap, y + half)
                }
                Tail.End -> {
                    val y = (rect.top + rect.height * tailAt).coerceIn(rect.top + half, rect.bottom - half)
                    moveTo(rect.right - overlap, y - half)
                    lineTo(size.width, y + t * 0.15f)
                    lineTo(rect.right - overlap, y + half)
                }
            }
            close()
        }
        return Outline.Generic(Path.combine(PathOperation.Union, body, tri))
    }
}

/**
 * Fuki's voice on the page: a speech balloon with a tail, a hard shadow and
 * room to grow. The shadow falls from the body only, so the tail stays a
 * single clean point. [sunk] presses the balloon into its shadow when the
 * balloon itself is the button.
 */
@Composable
internal fun SpeechBalloon(
    fill: Color,
    modifier: Modifier = Modifier,
    tail: Tail = Tail.Top,
    tailAt: Float = 0.5f,
    strokeColor: Color = LocalPop.current.stroke,
    sunk: () -> Float = { 0f },
    content: @Composable () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val tailSize = 14.dp
    val shape = remember(tail, tailAt) { BalloonShape(tail, tailAt, 20.dp, tailSize) }
    val shadow = remember(tail, tailAt) { BalloonShape(tail, tailAt, 20.dp, tailSize, withTail = false) }
    val tailPad = when (tail) {
        Tail.Top -> PaddingValues(top = tailSize)
        Tail.Start -> PaddingValues(start = tailSize)
        Tail.End -> PaddingValues(end = tailSize)
    }
    PopSurface(
        modifier, shape, color = fill, sunk = sunk, depth = 3.dp, stroke = 2.5.dp,
        strokeColor = strokeColor, shadowShape = shadow,
    ) {
        Box(
            Modifier
                .padding(tailPad)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .then(if (reduced) Modifier else Modifier.animateContentSize(spring(dampingRatio = 0.8f, stiffness = 500f)))
        ) { content() }
    }
}

// ---- decoration ----

/**
 * Comic sound-effect lettering: the word in [fill] with a fat ink outline,
 * a red drop, and a wide paper halo under it all. The halo is the manga
 * knockout: the burst spikes and speed lines behind the word break around
 * it instead of running through the letters. The outline is always Fuki's
 * fixed ink, never the theme's stroke, which is cream in the dark and would
 * melt into Fuki's own cream rim. Pure decoration, hidden from
 * accessibility services.
 */
@Composable
internal fun Sfx(text: String, sizeDp: Int, fill: Color, rotation: Float, modifier: Modifier = Modifier) {
    val pop = LocalPop.current
    val density = LocalDensity.current
    val fontSize = with(density) { sizeDp.dp.toSp() }
    val (inkW, haloW) = with(density) { 7.dp.toPx() to 17.dp.toPx() }
    val base = TextStyle(
        fontFamily = ComicNeue,
        fontWeight = FontWeight.Bold,
        fontStyle = FontStyle.Italic,
        fontSize = fontSize,
        lineHeight = fontSize,
    )
    val outline = base.copy(drawStyle = Stroke(width = inkW, join = StrokeJoin.Round))
    val halo = base.copy(drawStyle = Stroke(width = haloW, join = StrokeJoin.Round))
    Box(
        modifier
            .graphicsLayer { rotationZ = rotation }
            .clearAndSetSemantics { }
            .padding(10.dp)
    ) {
        Text(text, style = halo, color = pop.paper)
        Text(text, style = halo, color = pop.paper, modifier = Modifier.offset(3.dp, 3.dp))
        Text(text, style = outline, color = pop.punch, modifier = Modifier.offset(3.dp, 3.dp))
        Text(text, style = base, color = pop.punch, modifier = Modifier.offset(3.dp, 3.dp))
        Text(text, style = outline, color = pop.faceInk)
        Text(text, style = base, color = fill)
    }
}

/** Three bouncing dots while Fuki waits on the AI; a plain ellipsis with reduced motion. */
@Composable
internal fun WaitingDots(modifier: Modifier = Modifier) {
    val pop = LocalPop.current
    if (LocalReducedMotion.current) {
        Text("…", style = MaterialTheme.typography.titleMedium, color = pop.ink, modifier = modifier)
        return
    }
    val transition = rememberInfiniteTransition(label = "dots")
    val lift = with(LocalDensity.current) { 5.dp.toPx() }
    Row(modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        for (i in 0 until 3) {
            val y = transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    keyframes {
                        durationMillis = 900
                        0f at (i * 150)
                        -lift at (i * 150 + 150)
                        0f at (i * 150 + 300)
                    },
                    RepeatMode.Restart,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(6.dp)
                    .graphicsLayer { translationY = y.value }
                    .drawWithCache { onDrawBehind { drawCircle(pop.ink) } }
            )
        }
    }
}

/**
 * The update notice: a tilted "NEW!" sticker by the wordmark instead of a
 * banner, because an update is good news but never the next thing to do.
 * It sinks into its shadow when pressed, like every other sticker.
 */
@Composable
internal fun UpdateSticker(update: UpdateChecker.Update, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pop = LocalPop.current
    val view = LocalView.current
    val reduced = LocalReducedMotion.current
    val wiggle = rememberWiggle()
    LaunchedEffect(update.version) { wiggle.play(reduced) }
    val interaction = remember { MutableInteractionSource() }
    val sink = rememberSink(interaction)
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .wiggle(wiggle)
            .rotate(-3f)
            .semantics(mergeDescendants = true) {
                contentDescription = "Update available: MangaLens ${update.version}"
                role = Role.Button
            }
            .clickable(interaction, indication = null, role = Role.Button) {
                view.buzz(Buzz.CONFIRM)
                onClick()
            }
            .drawWithCache {
                val sw = 2.5.dp.toPx()
                val drop = Offset(2.dp.toPx(), 3.dp.toPx())
                val inner = insetOutline(shape, Size(size.width - drop.x, size.height - drop.y), sw)
                val style = Stroke(sw)
                onDrawBehind {
                    translate(drop.x + sw / 2f, drop.y + sw / 2f) {
                        drawOutline(inner, pop.shadow)
                        drawOutline(inner, pop.shadow, style = style)
                    }
                    val s = sink.value
                    translate(drop.x * s + sw / 2f, drop.y * s + sw / 2f) {
                        drawOutline(inner, pop.zapSoft)
                        drawOutline(inner, pop.zapSoftStroke, style = style)
                    }
                }
            }
            .heightIn(min = 48.dp)
            .padding(start = 12.dp, end = 14.dp, top = 4.dp, bottom = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clearAndSetSemantics { }
                .graphicsLayer {
                    translationX = 2.dp.toPx() * sink.value
                    translationY = 3.dp.toPx() * sink.value
                },
        ) {
            Text("NEW!", style = MaterialTheme.typography.labelMedium, color = pop.punchText)
            Text("v${update.version} ↗", style = MaterialTheme.typography.labelMedium, color = pop.ink)
        }
    }
}

/** The small 文A disc beside the wordmark: the same mark the floating button wears. */
@Composable
internal fun MiniMark(modifier: Modifier = Modifier) {
    val pop = LocalPop.current
    // A fixed size: the mark is a logo, and at large font scales it would outgrow its disc.
    val glyph = with(LocalDensity.current) { 12.dp.toSp() }
    Box(
        modifier
            .size(36.dp)
            .clearAndSetSemantics { }
            .drawWithCache {
                val sw = 2.dp.toPx()
                onDrawBehind {
                    drawCircle(pop.zap)
                    drawCircle(pop.faceInk, radius = size.minDimension / 2f - sw / 2f, style = Stroke(sw))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("文A", fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = glyph, color = pop.faceInk)
    }
}
