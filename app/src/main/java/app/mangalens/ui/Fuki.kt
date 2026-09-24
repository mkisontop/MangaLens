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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fuki's faces. The stage picks one, setup progress wakes Fuki up a step at
 * a time, a finger on the button squints it, and a refused screen share
 * worries it.
 */
internal enum class FukiMood { SLEEPY, PEEK, AWAKE, SQUINT, WORRIED, HAPPY, NAPPING }

/** Fuki's body: cream while asleep in setup, yellow when a tap is the next thing to do, red while reading. */
internal enum class FukiBody { SLEEP, ZAP, PUNCH }

/** Everything that decides how Fuki looks at one moment: face, body and the word on it. */
internal data class FukiLook(val mood: FukiMood, val body: FukiBody, val word: String)

/**
 * How Fuki looks for [stage]. In setup, each finished step wakes Fuki a
 * little more: asleep, then one eye open, then wide awake and yellow. Paused,
 * Fuki naps on a yellow body with WAKE on it, because tapping the napping
 * Fuki is how a reader wakes it — the natural poke must never stop the
 * session. [cheering] is the short grin after a first lettered line or
 * "All set".
 */
internal fun fukiLook(
    stage: Stage,
    pressed: Boolean = false,
    setupDone: Int = 0,
    worried: Boolean = false,
    cheering: Boolean = false,
): FukiLook = when (stage) {
    Stage.LOADING, Stage.SETUP -> {
        val body = if (setupDone >= 2) FukiBody.ZAP else FukiBody.SLEEP
        when {
            cheering -> FukiLook(FukiMood.HAPPY, body, "")
            setupDone >= 2 -> FukiLook(FukiMood.AWAKE, body, "")
            setupDone == 1 -> FukiLook(FukiMood.PEEK, body, "")
            else -> FukiLook(FukiMood.SLEEPY, body, "")
        }
    }
    Stage.READY -> FukiLook(
        when {
            pressed -> FukiMood.SQUINT
            cheering -> FukiMood.HAPPY
            worried -> FukiMood.WORRIED
            else -> FukiMood.AWAKE
        },
        FukiBody.ZAP,
        "GO!",
    )
    Stage.RUNNING -> FukiLook(FukiMood.HAPPY, FukiBody.PUNCH, "STOP")
    Stage.PAUSED -> FukiLook(if (pressed) FukiMood.PEEK else FukiMood.NAPPING, FukiBody.ZAP, "WAKE")
}

internal fun PopColors.bodyColor(body: FukiBody): Color = when (body) {
    FukiBody.SLEEP -> sleepBody
    FukiBody.ZAP -> zap
    FukiBody.PUNCH -> punch
}

/** Cheeks that show on [look]'s body; none while squinting or worried, which would read as calm. */
internal fun PopColors.blushFor(look: FukiLook): Color? = when {
    look.mood == FukiMood.SQUINT || look.mood == FukiMood.WORRIED -> null
    look.body == FukiBody.PUNCH -> Color.White.copy(alpha = 0.30f)
    look.body == FukiBody.ZAP -> punch.copy(alpha = 0.45f)
    else -> punch.copy(alpha = 0.30f)
}

internal enum class SfxKind(val text: String, val sizeDp: Int, val rotation: Float) {
    KAPOW("KA-POW!", 48, -10f),
    BOOP("BOOP!", 40, -6f),
}

/**
 * One sound effect to play. Each shot plays once: the stage marks it
 * played, so coming back from Tweaks does not set it off again.
 */
internal class SfxShot(val kind: SfxKind) {
    var played = false
}

/**
 * Fuki on its stage: the big speech balloon that is the start button,
 * with the art behind it — a POW burst when ready, speed lines while
 * running — and the sound effect that pops over it on a change.
 *
 * Every animated value (sink, blink, bob, hop, burst, speed lines, the
 * z's) is read inside a draw or graphicsLayer block, so a blink redraws one
 * layer and never recomposes the home screen. Fuki sits in its own layer,
 * so the halftone behind it is never redrawn by a blink either.
 *
 * [setupDone] is how many setup steps are finished, which wakes Fuki up in
 * the checklist; [worried] is a refused screen share; each new [cheerKey]
 * above zero grins for a moment. [compact] is the checklist's small stage,
 * which has no burst or speed lines to make room for.
 */
@Composable
internal fun FukiStage(
    stage: Stage,
    diameter: Dp,
    sfx: SfxShot?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    setupDone: Int = 0,
    worried: Boolean = false,
    cheerKey: Int = 0,
    compact: Boolean = false,
) {
    val pop = LocalPop.current
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink = rememberSink(interaction)
    val wiggle = rememberWiggle()

    // A celebration is decided once, by the shot this stage was born with.
    val kapow = remember { sfx?.kind == SfxKind.KAPOW && !sfx.played }
    val celebrate = kapow && !reduced
    var cheering by remember { mutableStateOf(kapow) }
    LaunchedEffect(Unit) {
        if (kapow) {
            delay(900)
            cheering = false
        }
    }
    LaunchedEffect(cheerKey) {
        if (cheerKey > 0) {
            cheering = true
            delay(1200)
            cheering = false
        }
    }
    LaunchedEffect(worried) { if (worried) wiggle.play(reduced) }

    val look = fukiLook(stage, pressed, setupDone, worried, cheering)
    val mood = look.mood
    val body = animateColorAsState(
        pop.bodyColor(look.body),
        if (reduced) snap() else tween(300, easing = FastOutSlowInEasing),
        label = "body",
    )

    val popIn = remember { Animatable(if (celebrate) 0.4f else 1f) }
    val burst = remember { Animatable(if (stage == Stage.READY && !celebrate) 1f else 0f) }
    val lines = remember { Animatable(if (stage == Stage.RUNNING) 1f else 0f) }
    LaunchedEffect(stage) {
        val burstTo = if (stage == Stage.READY && !compact) 1f else 0f
        val linesTo = if (stage == Stage.RUNNING && !compact) 1f else 0f
        if (reduced) {
            burst.snapTo(burstTo)
            lines.snapTo(linesTo)
            popIn.snapTo(1f)
            return@LaunchedEffect
        }
        if (popIn.value < 1f) {
            launch {
                // Springs in, bounces once past full size, and settles.
                popIn.animateTo(1.08f, tween(260, easing = FastOutSlowInEasing))
                popIn.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f))
            }
            delay(120)
        }
        launch { lines.animateTo(linesTo, tween(320, easing = FastOutSlowInEasing)) }
        burst.animateTo(burstTo, spring(dampingRatio = 0.5f, stiffness = 400f))
    }

    // The last setup step done: Fuki opens both eyes and hops.
    val hop = remember { Animatable(0f) }
    var lastDone by remember { mutableIntStateOf(setupDone) }
    LaunchedEffect(setupDone) {
        val woke = setupDone >= 2 && lastDone < 2
        lastDone = setupDone
        if (woke && !reduced) {
            val up = with(density) { -14.dp.toPx() }
            hop.animateTo(up, tween(150, easing = FastOutSlowInEasing))
            hop.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 600f))
        }
    }

    val blink = remember { Animatable(1f) }
    if ((mood == FukiMood.AWAKE || mood == FukiMood.WORRIED) && !reduced) {
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
    val bob: State<Float>? = if (stage == Stage.RUNNING && !reduced) {
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
    val word = look.word
    val wordColor = if (look.body == FukiBody.PUNCH) pop.onPunch else pop.onZap
    val wordSize = with(density) { (diameter * 0.23f).toSp() }
    val stageSize = if (compact) DpSize(diameter * 1.5f, diameter * 1.34f + 16.dp)
    else DpSize(diameter * 1.7f, diameter * 1.62f)

    Box(modifier.size(stageSize), contentAlignment = Alignment.Center) {
        // Background art: burst or speed lines, never bobbing with Fuki.
        if (!compact) Spacer(
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics { }
                .drawWithCache {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val rp = r.toPx()
                    val sw = 3.dp.toPx()
                    // Off-centre up and right, so the tail breaks out of the
                    // burst at the lower left instead of hiding among spikes.
                    val bc = Offset(c.x + 0.12f * rp, c.y - 0.08f * rp)
                    val outer = Path().apply {
                        val pts = burstPoints(bc.x, bc.y, 0.98f * rp, 1.36f * rp)
                        moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                        close()
                    }
                    val wedgeW = 3.5.dp.toPx()
                    val fullLines = speedWedges(speedLines(c.x, c.y, rp, 1f), wedgeW)
                    val join = Stroke(sw, join = StrokeJoin.Round)
                    onDrawBehind {
                        val b = burst.value
                        if (b > 0.001f) scale(b, pivot = bc) {
                            drawPath(outer, pop.punch)
                            drawPath(outer, pop.stroke, style = join)
                            // The double rim of a POW: a paper copy of the
                            // burst inside it, so only the longest spikes
                            // show a light core past Fuki's edge.
                            scale(0.72f, pivot = bc) {
                                drawPath(outer, pop.paper)
                                drawPath(outer, pop.stroke, style = join)
                            }
                        }
                        val p = lines.value
                        if (p >= 0.999f) drawPath(fullLines, pop.stroke)
                        else if (p > 0.001f) drawPath(speedWedges(speedLines(c.x, c.y, rp, p), wedgeW), pop.stroke)
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
                    translationY = (bob?.value ?: 0f) + hop.value
                }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = if (stage == Stage.SETUP) "Show the next step" else null,
                ) {
                    when {
                        stage == Stage.SETUP && setupDone < 2 -> {
                            view.buzz(Buzz.REJECT)
                            scope.launch { wiggle.play(reduced) }
                        }
                        stage == Stage.PAUSED -> {
                            view.buzz(Buzz.CONFIRM)
                            scope.launch { wiggle.play(reduced) }
                        }
                        else -> view.buzz(Buzz.CONFIRM)
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
                            contentDescription = "Wake up"
                            stateDescription = "Paused"
                        }
                        Stage.SETUP, Stage.LOADING ->
                            contentDescription = if (setupDone >= 2) "Fuki is awake. Tap “All set, let's read!” below."
                            else "Fuki is asleep. Finish the steps below to start."
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
                        val rimW = 1.5.dp.toPx()
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
                        val blush = pop.blushFor(look)
                        onDrawBehind {
                            translate(shadowOffset.x, shadowOffset.y) {
                                pop.shadowStroke?.let { drawPath(path, it, style = Stroke(rimW * 2f, join = StrokeJoin.Round)) }
                                drawPath(path, pop.shadow)
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

        // The sound effect, bursting out above and right of Fuki.
        showing?.let { shot ->
            Sfx(
                shot.kind.text,
                shot.kind.sizeDp,
                pop.zap,
                shot.kind.rotation,
                Modifier
                    .wrapContentSize(unbounded = true)
                    .centreAt(r * 0.55f, -(r * 1.32f))
                    .graphicsLayer {
                        scaleX = sfxScale.value
                        scaleY = sfxScale.value
                        alpha = sfxAlpha.value
                    },
            )
        }
    }
}

/**
 * A small, still Fuki beside a tip, so the tip balloon's tail points at
 * whoever is talking. It never animates: it sits next to text a reader is
 * reading.
 */
@Composable
internal fun FukiAvatar(look: FukiLook, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val pop = LocalPop.current
    Spacer(
        modifier
            .size(size)
            .clearAndSetSemantics { }
            .drawWithCache {
                // The tail reaches below and left of the circle; this radius
                // keeps the whole balloon and its shadow inside the box.
                val rp = this.size.minDimension / 2.45f
                val c = Offset(this.size.width / 2f + 0.03f * rp, this.size.height / 2f - 0.14f * rp)
                val path = balloonPath(c, rp)
                val drop = Offset(2.dp.toPx(), 2.5.dp.toPx())
                val stroke = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round)
                val fill = pop.bodyColor(look.body)
                val blush = pop.blushFor(look)
                onDrawBehind {
                    translate(drop.x, drop.y) { drawPath(path, pop.shadow) }
                    drawPath(path, fill)
                    drawPath(path, pop.stroke, style = stroke)
                    drawFukiFace(look.mood, c, rp, 1f, pop.faceInk, blush)
                }
            }
    )
}

/** Places the element's centre [dx], [dy] from the centre of where the parent would put it. */
private fun Modifier.centreAt(dx: Dp, dy: Dp): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    layout(p.width, p.height) {
        p.place(IntOffset(dx.roundToPx(), dy.roundToPx()))
    }
}
