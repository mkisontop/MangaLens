package app.mangalens.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Fuki's five faces; the stage picks one and a finger on the GO button squints it. */
internal enum class FukiMood { SLEEPY, AWAKE, SQUINT, HAPPY, NAPPING }

internal enum class SfxKind(val text: String, val sizeDp: Int, val rotation: Float) {
    KAPOW("KA-POW!", 34, -8f),
    BOOP("BOOP!", 28, 6f),
}

/**
 * One sound effect to play. Each shot plays once: the stage marks it
 * played, so coming back from Tweaks does not set it off again.
 */
internal class SfxShot(val kind: SfxKind) {
    var played = false
}

internal fun moodFor(stage: Stage, pressed: Boolean): FukiMood = when (stage) {
    Stage.LOADING, Stage.SETUP -> FukiMood.SLEEPY
    Stage.READY -> if (pressed) FukiMood.SQUINT else FukiMood.AWAKE
    Stage.RUNNING -> FukiMood.HAPPY
    Stage.PAUSED -> FukiMood.NAPPING
}

/**
 * Fuki on its stage: the big speech balloon that is the start button,
 * with the art behind it — a POW burst when ready, speed lines while
 * running — and the sound effect that pops over it on a change.
 *
 * Every animated value (sink, blink, bob, burst, speed lines, the z's) is
 * read inside a draw or graphicsLayer block, so a blink redraws one layer
 * and never recomposes the home screen. Fuki sits in its own layer, so
 * the halftone behind it is never redrawn by a blink either.
 */
@Composable
internal fun FukiStage(
    stage: Stage,
    diameter: Dp,
    sfx: SfxShot?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pop = LocalPop.current
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink = rememberSink(interaction)
    val mood = moodFor(stage, pressed)
    val wiggle = rememberWiggle()

    val body = animateColorAsState(
        when (mood) {
            FukiMood.SLEEPY -> pop.sleepBody
            FukiMood.AWAKE, FukiMood.SQUINT -> pop.zap
            FukiMood.HAPPY, FukiMood.NAPPING -> pop.punch
        },
        if (reduced) snap() else tween(300, easing = FastOutSlowInEasing),
        label = "body",
    )

    // A celebration is decided once, by the shot this stage was born with.
    val celebrate = remember { sfx?.kind == SfxKind.KAPOW && !sfx.played && !reduced }
    val popIn = remember { Animatable(if (celebrate) 0.4f else 1f) }
    val burst = remember { Animatable(if (stage == Stage.READY && !celebrate) 1f else 0f) }
    val lines = remember { Animatable(if (stage == Stage.RUNNING) 1f else 0f) }
    LaunchedEffect(stage) {
        val burstTo = if (stage == Stage.READY) 1f else 0f
        val linesTo = if (stage == Stage.RUNNING) 1f else 0f
        if (reduced) {
            burst.snapTo(burstTo)
            lines.snapTo(linesTo)
            popIn.snapTo(1f)
            return@LaunchedEffect
        }
        if (popIn.value < 1f) {
            launch { popIn.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f)) }
            delay(120)
        }
        launch { lines.animateTo(linesTo, tween(320, easing = FastOutSlowInEasing)) }
        burst.animateTo(burstTo, spring(dampingRatio = 0.5f, stiffness = 400f))
    }

    val blink = remember { Animatable(1f) }
    if (mood == FukiMood.AWAKE && !reduced) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(Random.nextLong(3500, 6500))
                blink.animateTo(0.1f, tween(80))
                blink.animateTo(1f, tween(100))
            }
        }
    }

    val sleeping = mood == FukiMood.SLEEPY || mood == FukiMood.NAPPING
    val drift: State<Float>? = if (sleeping && !reduced) {
        rememberInfiniteTransition(label = "zzz").animateFloat(
            0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "drift",
        )
    } else null
    val bob: State<Float>? = if (mood == FukiMood.HAPPY && !reduced) {
        val px = with(density) { 3.dp.toPx() }
        rememberInfiniteTransition(label = "bob").animateFloat(
            -px, px, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob",
        )
    } else null

    // The sound effect: pops, overshoots, holds, fades.
    var showing by remember { mutableStateOf<SfxShot?>(null) }
    val sfxScale = remember { Animatable(1f) }
    val sfxAlpha = remember { Animatable(1f) }
    LaunchedEffect(sfx) {
        val shot = sfx ?: return@LaunchedEffect
        if (shot.played) return@LaunchedEffect
        shot.played = true
        showing = shot
        if (reduced) {
            sfxScale.snapTo(1f)
            sfxAlpha.snapTo(1f)
            delay(900)
        } else {
            sfxAlpha.snapTo(1f)
            sfxScale.snapTo(0.4f)
            sfxScale.animateTo(1.12f, tween(160, easing = FastOutSlowInEasing))
            sfxScale.animateTo(1f, tween(100))
            delay(700)
            sfxAlpha.animateTo(0f, tween(200))
        }
        showing = null
    }

    val r = diameter / 2
    val zzz = rememberTextMeasurer()
    val word = when (mood) {
        FukiMood.SLEEPY -> ""
        FukiMood.AWAKE, FukiMood.SQUINT -> "GO!"
        FukiMood.HAPPY, FukiMood.NAPPING -> "STOP"
    }
    val wordColor = if (word == "GO!") pop.onZap else pop.onPunch
    val wordSize = with(density) { (diameter * 0.23f).toSp() }

    Box(
        modifier.size(diameter * 1.5f, diameter * 1.34f + 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Background art: burst or speed lines, never bobbing with Fuki.
        Spacer(
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics { }
                .drawWithCache {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val rp = r.toPx()
                    val sw = 3.dp.toPx()
                    val burstPath = Path().apply {
                        val pts = burstPoints(c.x, c.y, 1.06f * rp, 1.34f * rp)
                        moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                        close()
                    }
                    val full = speedLines(c.x, c.y, rp, 1f)
                    onDrawBehind {
                        val b = burst.value
                        if (b > 0.001f) scale(b, pivot = c) {
                            drawPath(burstPath, pop.punch)
                            drawPath(burstPath, pop.stroke, style = Stroke(sw, join = StrokeJoin.Round))
                        }
                        val p = lines.value
                        if (p > 0.001f) for (l in full) {
                            val end = Offset(l.start.x + (l.end.x - l.start.x) * p, l.start.y + (l.end.y - l.start.y) * p)
                            drawLine(pop.stroke, l.start, end, sw, cap = StrokeCap.Round)
                        }
                    }
                }
        )

        // Fuki: the pressable balloon.
        Box(
            Modifier
                .size(diameter)
                .graphicsLayer {
                    val s = popIn.value
                    scaleX = s
                    scaleY = s
                    rotationZ = wiggle.angle.value
                    translationY = bob?.value ?: 0f
                }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = if (stage == Stage.SETUP) "Show the next step" else null,
                ) {
                    if (stage == Stage.SETUP) {
                        view.buzz(Buzz.REJECT)
                        scope.launch { wiggle.play(reduced) }
                    } else {
                        view.buzz(Buzz.CONFIRM)
                    }
                    onClick()
                }
                .semantics(mergeDescendants = true) {
                    when (stage) {
                        Stage.READY -> {
                            contentDescription = "Start translating"
                            stateDescription = "Off"
                        }
                        Stage.RUNNING -> {
                            contentDescription = "Stop translating"
                            stateDescription = "On"
                        }
                        Stage.PAUSED -> {
                            contentDescription = "Stop translating"
                            stateDescription = "Paused"
                        }
                        Stage.SETUP, Stage.LOADING ->
                            contentDescription = "Fuki is asleep. Finish the steps below to start."
                    }
                }
        ) {
            Spacer(
                Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics { }
                    .drawWithCache {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val rp = size.minDimension / 2f
                        val path = balloonPath(c, rp)
                        val shadowOffset = Offset(6.dp.toPx(), 7.dp.toPx())
                        val strokeW = 4.dp.toPx()
                        val rimW = 2.dp.toPx()
                        // Big enough to read on the 104dp checklist Fuki, and clear of the rim.
                        val zSizes = listOf(0.25f to 15.dp, 0.20f to 12.dp, 0.15f to 10.dp)
                        val zStyle = zSizes.map { (k, floor) ->
                            zzz.measure(
                                "z",
                                TextStyle(
                                    fontFamily = ComicNeue,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = maxOf(k * rp, floor.toPx()).toSp(),
                                ),
                            )
                        }
                        val zAnchors = listOf(Offset(0.84f, -0.90f), Offset(1.08f, -1.14f), Offset(1.28f, -1.36f))
                        onDrawBehind {
                            translate(shadowOffset.x, shadowOffset.y) {
                                drawPath(path, pop.shadow)
                                pop.shadowStroke?.let { drawPath(path, it, style = Stroke(rimW)) }
                            }
                            val s = sink.value
                            translate(shadowOffset.x * s, shadowOffset.y * s) {
                                drawPath(path, body.value)
                                drawPath(path, pop.stroke, style = Stroke(strokeW, join = StrokeJoin.Round))
                                drawArc(
                                    Color.White.copy(alpha = 0.7f), 200f, 40f, false,
                                    topLeft = Offset(c.x - 0.82f * rp, c.y - 0.82f * rp),
                                    size = Size(1.64f * rp, 1.64f * rp),
                                    style = Stroke(0.07f * rp, cap = StrokeCap.Round),
                                )
                                val blush = when (mood) {
                                    FukiMood.AWAKE -> pop.punch.copy(alpha = 0.45f)
                                    FukiMood.HAPPY -> Color.White.copy(alpha = 0.30f)
                                    FukiMood.SLEEPY -> pop.punch.copy(alpha = 0.30f)
                                    FukiMood.SQUINT, FukiMood.NAPPING -> null
                                }
                                drawFukiFace(mood, c, rp, blink.value, pop.faceInk, blush)
                            }
                            if (sleeping) {
                                val phase = drift?.value
                                for (i in zStyle.indices) {
                                    val layout = zStyle[i]
                                    val a = zAnchors[i]
                                    var alpha = 1f
                                    var lift = 0f
                                    if (phase != null) {
                                        val p = (phase + i / 3f) % 1f
                                        lift = 0.25f * rp * p
                                        alpha = when {
                                            p < 0.2f -> p / 0.2f
                                            p > 0.7f -> (1f - p) / 0.3f
                                            else -> 1f
                                        }
                                    }
                                    drawText(
                                        layout,
                                        color = pop.inkSoft,
                                        topLeft = Offset(
                                            c.x + a.x * rp - layout.size.width / 2f,
                                            c.y + a.y * rp - layout.size.height / 2f - lift,
                                        ),
                                        alpha = alpha.coerceIn(0f, 1f),
                                    )
                                }
                            }
                        }
                    }
            )
            // The word rides on the face, so it sinks with it.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val s = sink.value
                        translationX = 6.dp.toPx() * s
                        translationY = 7.dp.toPx() * s
                    }
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = word,
                    transitionSpec = {
                        if (reduced) EnterTransition.None togetherWith ExitTransition.None
                        else (scaleIn(spring(dampingRatio = 0.5f, stiffness = 500f), initialScale = 0.6f) + fadeIn(tween(120)))
                            .togetherWith(scaleOut(tween(120), targetScale = 1.3f) + fadeOut(tween(120)))
                    },
                    modifier = Modifier.offset(y = r * 0.36f),
                    label = "word",
                ) { w ->
                    if (w.isNotEmpty()) {
                        Text(
                            w,
                            style = TextStyle(
                                fontFamily = ComicNeue,
                                fontWeight = FontWeight.Bold,
                                fontSize = wordSize,
                                lineHeight = wordSize,
                                letterSpacing = (-1).sp,
                                color = wordColor,
                            ),
                        )
                    }
                }
            }
        }

        // The sound effect, anchored above and right of Fuki's centre.
        showing?.let { shot ->
            Sfx(
                shot.kind.text,
                shot.kind.sizeDp,
                pop.zap,
                shot.kind.rotation,
                Modifier
                    .wrapContentSize(unbounded = true)
                    .centreAt(r * 0.9f, -(r * 1.05f))
                    .graphicsLayer {
                        scaleX = sfxScale.value
                        scaleY = sfxScale.value
                        alpha = sfxAlpha.value
                    },
            )
        }
    }
}

/** Places the element's centre [dx], [dy] from the centre of where the parent would put it. */
private fun Modifier.centreAt(dx: Dp, dy: Dp): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    layout(p.width, p.height) {
        p.place(IntOffset(dx.roundToPx(), dy.roundToPx()))
    }
}
