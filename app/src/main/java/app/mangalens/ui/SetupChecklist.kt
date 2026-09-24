package app.mangalens.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mangalens.settings.AppSettings
import app.mangalens.settings.LlmProvider
import app.mangalens.translate.LlmHttp
import kotlinx.coroutines.launch

private enum class TicketState { CURRENT, TODO, DONE }

/**
 * First run: a sleeping Fuki and two tickets, "Let me float" and "Give me
 * a brain". One ticket is open at a time — the one tapped, otherwise the
 * next undone step — so the screen only ever asks for one thing. Fuki wakes
 * a step at a time as the tickets are done. The checklist stays up after
 * the last step (the caller's latch) so the reader sees it finish and taps
 * "All set, let's read!" themselves.
 *
 * The brain step counts as done only once the test line has answered: a
 * key still being tried might yet be refused, and a checklist that ticked
 * it off and then took it back would jump under the reader's thumb.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SetupChecklist(
    settings: AppSettings,
    drafts: AiDrafts,
    sayHi: SayHi,
    overlayGranted: Boolean,
    aiReady: Boolean,
    browser: String,
    onGrantOverlay: () -> Unit,
    onOpenAiTweaks: () -> Unit,
    onAllSet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pop = LocalPop.current
    val scope = rememberCoroutineScope()
    val fontScale = LocalDensity.current.fontScale
    val stacked = fontScale > 1.3f
    val brainDone = aiReady && sayHi.phase !is SayHi.Phase.Running
    var tapped by remember { mutableStateOf<Int?>(null) }
    // A ticket opened by hand folds back once its step is done — for the
    // brain, once a test line has answered — so the next undone step
    // opens by itself.
    LaunchedEffect(overlayGranted) { if (overlayGranted && tapped == 1) tapped = null }
    LaunchedEffect(brainDone, sayHi.phase) { if (brainDone && tapped == 2) tapped = null }
    val current = when {
        !overlayGranted -> 1
        !brainDone -> 2
        else -> null
    }
    val expanded = tapped ?: current
    val requesters = remember { listOf(BringIntoViewRequester(), BringIntoViewRequester(), BringIntoViewRequester()) }
    val doneCount = (if (overlayGranted) 1 else 0) + (if (brainDone) 1 else 0)
    var cheers by remember { mutableIntStateOf(0) }
    LaunchedEffect(sayHi.phase) { if (sayHi.phase is SayHi.Phase.Ok) cheers++ }

    fun stateOf(step: Int, done: Boolean) = when {
        done -> TicketState.DONE
        step == current -> TicketState.CURRENT
        else -> TicketState.TODO
    }

    Column(modifier.fillMaxWidth()) {
        val fuki: @Composable () -> Unit = {
            FukiStage(
                Stage.SETUP, 104.dp, sfx = null,
                onClick = {
                    val target = expanded?.let { it - 1 } ?: if (doneCount == 2) 2 else 1
                    scope.launch { requesters[target].bringIntoView() }
                },
                setupDone = doneCount,
                cheerKey = cheers,
                compact = true,
            )
        }
        val headline: @Composable (Modifier) -> Unit = { m ->
            Text(
                "Hi! I'm Fuki.",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 34.sp, lineHeight = 38.sp, lineBreak = LineBreak.Heading,
                ),
                color = pop.ink,
                modifier = m.semantics { heading() },
            )
        }
        if (stacked) {
            headline(Modifier)
            Box(Modifier.align(Alignment.CenterHorizontally)) { fuki() }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                headline(Modifier.weight(1f))
                fuki()
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "I letter English over raw manga, manhwa and manhua while you read in $browser. " +
                "Two quick steps and we're off.",
            style = MaterialTheme.typography.bodyLarge,
            color = pop.inkSoft,
        )
        Spacer(Modifier.height(16.dp))
        Pips(listOf(overlayGranted, brainDone), grow = fontScale.coerceIn(1f, 1.6f))
        Spacer(Modifier.height(12.dp))

        Ticket(
            number = 1,
            title = "Let me float",
            state = stateOf(1, overlayGranted),
            expanded = expanded == 1,
            requester = requesters[0],
            onHeaderClick = { tapped = 1 },
            done = { DoneLine("Done! I can float over other apps.") },
        ) {
            Text(
                "Switch on “Display over other apps” so I can float my $MARK bubble and letter English on top of your browser.",
                style = MaterialTheme.typography.bodyLarge,
                color = pop.inkSoft,
            )
            Spacer(Modifier.height(12.dp))
            StickerButton("Allow it ↗", onGrantOverlay)
            Spacer(Modifier.height(8.dp))
            Text(
                "Find MangaLens in the list, flip the switch, then come back.",
                style = MaterialTheme.typography.bodyMedium,
                color = pop.inkSoft,
            )
        }
        Spacer(Modifier.height(16.dp))

        val label = LlmHttp.providerLabel(settings)
        val custom = settings.provider == LlmProvider.CUSTOM
        Ticket(
            number = 2,
            title = "Give me a brain",
            state = stateOf(2, brainDone),
            expanded = expanded == 2,
            requester = requesters[1],
            onHeaderClick = { tapped = 2 },
            headerTrailing = if (custom) null else ({ TextLink("Change", { tapped = 2 }) }),
            done = {
                DoneLine(if (custom) "Done! Endpoint saved." else "Done! $label key saved.")
                SayHiResult(sayHi.phase, onRetry = { sayHi.runIfReady(drafts.settings) })
            },
        ) {
            BrainStep(settings, drafts, sayHi, onOpenAiTweaks)
        }

        AnimatedVisibility(
            visible = overlayGranted && brainDone,
            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(150)),
        ) {
            Column(Modifier.bringIntoViewRequester(requesters[2])) {
                Spacer(Modifier.height(20.dp))
                StickerButton("All set, let's read!", onAllSet, height = 64.dp)
            }
        }
    }
}

/**
 * Setup progress as pips, one per step in ticket order, so a filled pip
 * always sits over a done ticket: after the overlay permission is taken
 * back, the second pip stays filled and the first goes empty, rather than
 * a count that would suggest step 1 is the one done. They grow with the
 * font so they never look lost beside large text.
 */
@Composable
private fun Pips(steps: List<Boolean>, grow: Float) {
    val pop = LocalPop.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { },
    ) {
        for (filled in steps) {
            Box(
                Modifier
                    .size(12.dp * grow)
                    .clearAndSetSemantics { }
                    .drawWithCache {
                        val sw = 2.dp.toPx()
                        onDrawBehind {
                            drawCircle(if (filled) pop.zap else pop.surface)
                            drawCircle(pop.stroke, size.minDimension / 2f - sw / 2f, style = Stroke(sw))
                        }
                    }
            )
            Spacer(Modifier.width(6.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text("${steps.count { it }} of ${steps.size} done", style = MaterialTheme.typography.labelMedium, color = pop.inkSoft)
    }
}

@Composable
private fun DoneLine(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalPop.current.ink, modifier = modifier)
}

/**
 * One step: a numbered badge, a title, and a body while open. A done
 * step folds down to a single line on a pale yellow card, so what is
 * left to do stands out at a glance. A folded ticket's header is a
 * button, and the whole ticket sinks into its shadow while pressed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Ticket(
    number: Int,
    title: String,
    state: TicketState,
    expanded: Boolean,
    requester: BringIntoViewRequester,
    onHeaderClick: () -> Unit,
    headerTrailing: (@Composable () -> Unit)? = null,
    done: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val pop = LocalPop.current
    val reduced = LocalReducedMotion.current
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val sink = rememberSink(interaction)
    val folded = state == TicketState.DONE && !expanded
    PopSurface(
        Modifier.fillMaxWidth().bringIntoViewRequester(requester),
        RoundedCornerShape(20.dp),
        color = if (folded) pop.zapSoft else pop.surface,
        strokeColor = if (folded) pop.zapSoftStroke else pop.stroke,
        sunk = { sink.value },
    ) {
        Column(
            Modifier
                .then(
                    if (reduced) Modifier
                    else Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
                )
                .padding(16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .clickable(interaction, indication = null, enabled = !expanded, role = Role.Button) {
                            view.buzz(Buzz.TICK)
                            onHeaderClick()
                        }
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Step $number, $title"
                            stateDescription = when (state) {
                                TicketState.DONE -> "Done"
                                TicketState.CURRENT -> "Next"
                                TicketState.TODO -> "To do"
                            }
                            heading()
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StepBadge(number, state)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        color = pop.ink,
                        modifier = Modifier.weight(1f).clearAndSetSemantics { },
                    )
                }
                if (folded && headerTrailing != null) {
                    Spacer(Modifier.width(8.dp))
                    headerTrailing()
                }
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                body()
            } else if (state == TicketState.DONE) {
                Spacer(Modifier.height(4.dp))
                done()
            }
        }
    }
}

/**
 * The step number, which flips over to an ink check when the step is
 * done. Both faces are always composed and the flip only changes a layer,
 * so it costs no recomposition while it turns. It grows with the font (up
 * to a point), so a large numeral never fills the disc edge to edge.
 */
@Composable
private fun StepBadge(number: Int, state: TicketState) {
    val pop = LocalPop.current
    val reduced = LocalReducedMotion.current
    val grow = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    val flip = animateFloatAsState(
        if (state == TicketState.DONE) 180f else 0f,
        if (reduced) snap() else tween(320),
        label = "flip",
    )
    val front = if (state == TicketState.CURRENT) pop.zap else pop.surface
    val frontInk = if (state == TicketState.CURRENT) pop.onZap else pop.ink
    Box(
        Modifier
            .size(40.dp * grow)
            .clearAndSetSemantics { }
            .graphicsLayer {
                rotationY = flip.value
                cameraDistance = 12f * density
            }
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = if (flip.value < 90f) 1f else 0f }
                .badgeDisc(front, pop.stroke),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.titleLarge, color = frontInk)
        }
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    rotationY = 180f
                    alpha = if (flip.value >= 90f) 1f else 0f
                }
                .badgeDisc(pop.faceInk, pop.stroke),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = pop.zap, modifier = Modifier.size(24.dp * grow))
        }
    }
}

private fun Modifier.badgeDisc(fill: Color, stroke: Color): Modifier = drawWithCache {
    val sw = 2.5.dp.toPx()
    onDrawBehind {
        drawCircle(fill)
        drawCircle(stroke, size.minDimension / 2f - sw / 2f, style = Stroke(sw))
    }
}

/**
 * Step 2. For Gemini, the default, it is two taps: open the key page, then
 * paste. The clipboard is read only when Paste is tapped — never on resume
 * — so nothing else the reader copied is ever looked at. What the test
 * line says lands right under the Paste button that set it off.
 */
@Composable
private fun ColumnScope.BrainStep(
    settings: AppSettings,
    drafts: AiDrafts,
    sayHi: SayHi,
    onOpenAiTweaks: () -> Unit,
) {
    val pop = LocalPop.current
    val provider = settings.provider
    val label = LlmHttp.providerLabel(settings)
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val pasteWiggle = rememberWiggle()
    var notice by remember { mutableStateOf<String?>(null) }
    var geminiHint by remember { mutableStateOf(false) }
    var typeOpen by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var askedForKey by remember { mutableStateOf(false) }

    // Back from the browser with a key on the clipboard: nudge the Paste button once.
    LifecycleResumeEffect(Unit) {
        if (askedForKey) {
            askedForKey = false
            scope.launch { pasteWiggle.play(reduced) }
        }
        onPauseOrDispose { }
    }
    // A refused key can only be fixed by a new one, so point at Paste, not at a retry.
    val phase = sayHi.phase
    LaunchedEffect(phase) {
        if (phase is SayHi.Phase.Failed && phase.rejected) {
            view.buzz(Buzz.REJECT)
            pasteWiggle.play(reduced)
        }
    }

    fun submit(raw: String) {
        notice = null
        geminiHint = false
        val check = drafts.submitKey(raw, sayHi)
        val refusal = pasteNotice(check)
        if (refusal != null) {
            notice = refusal
            view.buzz(Buzz.REJECT)
            scope.launch { pasteWiggle.play(reduced) }
        } else {
            geminiHint = provider == LlmProvider.GEMINI && !looksLikeGeminiKey(raw.trim())
            typed = ""
        }
    }

    if (provider == LlmProvider.CUSTOM) {
        Text(
            "Add your endpoint URL. I'll talk to any OpenAI-compatible server.",
            style = MaterialTheme.typography.bodyLarge,
            color = pop.inkSoft,
        )
        Spacer(Modifier.height(12.dp))
        StickerButton("Set up my server ›", onOpenAiTweaks)
        return
    }

    val help = providerKeyHelp(provider)
    Text(
        if (provider == LlmProvider.GEMINI) "I translate with Gemini AI. Its key is free, no card needed. Two taps:"
        else "Paste your $label key and I'm ready.",
        style = MaterialTheme.typography.bodyLarge,
        color = pop.inkSoft,
    )
    Spacer(Modifier.height(12.dp))
    help.url?.let { url ->
        StickerButton(
            if (provider == LlmProvider.GEMINI) "Get a free key ↗" else help.linkLabel,
            {
                notice = null
                askedForKey = true
                uri.openUri(url)
            },
        )
        if (provider == LlmProvider.GEMINI) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Sign in, tap “Create API key”, copy it, come back.",
                style = MaterialTheme.typography.bodyMedium,
                color = pop.inkSoft,
            )
        }
        Spacer(Modifier.height(12.dp))
    }
    StickerButton(
        "Paste my key",
        { submit(clipboard.getText()?.text.orEmpty()) },
        style = StickerStyle.Surface,
        wiggle = pasteWiggle,
    )
    notice?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = pop.punchText)
    }
    if (geminiHint) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Gemini keys usually start with “AIza”. I'll try it anyway.",
            style = MaterialTheme.typography.bodyMedium,
            color = pop.inkSoft,
        )
    }
    SayHiResult(phase, onRetry = { sayHi.runIfReady(drafts.settings) })
    Spacer(Modifier.height(8.dp))
    TextLink("Type it instead", {
        notice = null
        typeOpen = !typeOpen
    })
    AnimatedVisibility(
        visible = typeOpen,
        enter = if (reduced) fadeIn(snap()) else expandVertically(tween(200)) + fadeIn(tween(200)),
        exit = if (reduced) fadeOut(snap()) else shrinkVertically(tween(200)) + fadeOut(tween(200)),
    ) {
        Column {
            SecretField(
                value = typed,
                onValueChange = {
                    typed = it
                    notice = null
                },
                label = apiKeyLabel(provider),
                onDone = { submit(typed) },
            )
            Spacer(Modifier.height(12.dp))
            StickerButton("Save key", { submit(typed) }, style = StickerStyle.Surface, height = 48.dp)
            Spacer(Modifier.height(4.dp))
        }
    }
    if (provider == LlmProvider.GEMINI) {
        TextLink("Use Claude, OpenAI or another AI →", onOpenAiTweaks)
    }
}
