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
 * next undone step — so the screen only ever asks for one thing. The
 * checklist stays up after the last step (the caller's latch) so the
 * reader sees it finish and taps "All set" themselves.
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
    val stacked = LocalDensity.current.fontScale > 1.3f
    var tapped by remember { mutableStateOf<Int?>(null) }
    val current = when {
        !overlayGranted -> 1
        !aiReady -> 2
        else -> null
    }
    val expanded = tapped ?: current
    val requesters = remember { listOf(BringIntoViewRequester(), BringIntoViewRequester()) }
    val doneCount = (if (overlayGranted) 1 else 0) + (if (aiReady) 1 else 0)

    fun stateOf(step: Int, done: Boolean) = when {
        done -> TicketState.DONE
        step == current -> TicketState.CURRENT
        else -> TicketState.TODO
    }

    Column(modifier.fillMaxWidth()) {
        val fuki: @Composable () -> Unit = {
            FukiStage(Stage.SETUP, 104.dp, sfx = null, onClick = {
                val step = expanded ?: 2
                scope.launch { requesters[step - 1].bringIntoView() }
            })
        }
        val headline: @Composable (Modifier) -> Unit = { m ->
            Text(
                "Hi! I'm Fuki.",
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, lineHeight = 38.sp),
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
        Pips(doneCount)
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
        Ticket(
            number = 2,
            title = "Give me a brain",
            state = stateOf(2, aiReady),
            expanded = expanded == 2,
            requester = requesters[1],
            onHeaderClick = { tapped = 2 },
            done = {
                if (settings.provider == LlmProvider.CUSTOM) {
                    DoneLine("Done! Endpoint saved.")
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DoneLine("Done! $label key saved.", Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(12.dp))
                        TextLink("Change", { tapped = 2 })
                    }
                }
                SayHiResult(sayHi.phase, onRetry = { sayHi.run(drafts.settings) })
            },
        ) {
            BrainStep(settings, drafts, sayHi, onOpenAiTweaks)
        }

        AnimatedVisibility(
            visible = overlayGranted && aiReady,
            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(150)),
        ) {
            Column {
                Spacer(Modifier.height(20.dp))
                StickerButton("All set, let's read!", onAllSet, height = 64.dp)
            }
        }
    }
}

@Composable
private fun Pips(done: Int) {
    val pop = LocalPop.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { },
    ) {
        for (i in 0 until 2) {
            val filled = i < done
            Box(
                Modifier
                    .size(12.dp)
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
        Text("$done of 2 done", style = MaterialTheme.typography.labelMedium, color = pop.inkSoft)
    }
}

@Composable
private fun DoneLine(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalPop.current.ink, modifier = modifier)
}

/**
 * One step: a numbered badge, a title, and a body while open. A done
 * step folds down to a single line on a pale yellow card, so what is
 * left to do stands out at a glance.
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
    done: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val pop = LocalPop.current
    val reduced = LocalReducedMotion.current
    PopSurface(
        Modifier.fillMaxWidth().bringIntoViewRequester(requester),
        RoundedCornerShape(20.dp),
        color = if (state == TicketState.DONE && !expanded) pop.zapSoft else pop.surface,
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
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !expanded, onClick = onHeaderClick)
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
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                body()
            } else if (state == TicketState.DONE) {
                Spacer(Modifier.height(8.dp))
                done()
            }
        }
    }
}

/**
 * The step number, which flips over to an ink check when the step is
 * done. Both faces are always composed and the flip only changes a layer,
 * so it costs no recomposition while it turns.
 */
@Composable
private fun StepBadge(number: Int, state: TicketState) {
    val pop = LocalPop.current
    val reduced = LocalReducedMotion.current
    val flip = animateFloatAsState(
        if (state == TicketState.DONE) 180f else 0f,
        if (reduced) snap() else tween(320),
        label = "flip",
    )
    val front = if (state == TicketState.CURRENT) pop.zap else pop.surface
    val frontInk = if (state == TicketState.CURRENT) pop.onZap else pop.ink
    Box(
        Modifier
            .size(40.dp)
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
            Icon(Icons.Filled.Check, contentDescription = null, tint = pop.zap, modifier = Modifier.size(24.dp))
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
 * Step 2. For Gemini, the default, it is two taps: open Google's key
 * page, then paste. The clipboard is read only when Paste is tapped —
 * never on resume — so nothing else the reader copied is ever looked at.
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
        StickerButton("Open AI brain", onOpenAiTweaks)
        return
    }

    val help = providerKeyHelp(provider)
    Text(
        if (provider == LlmProvider.GEMINI) "I translate with Google's Gemini AI. Its key is free, no card needed. Two taps:"
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
    Spacer(Modifier.height(4.dp))
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
            Spacer(Modifier.height(10.dp))
            StickerButton(
                "Save key", { submit(typed) },
                style = StickerStyle.Surface, height = 48.dp, fillWidth = false,
            )
        }
    }
    if (provider == LlmProvider.GEMINI) {
        TextLink("Using Claude, OpenAI or another AI instead? →", onOpenAiTweaks)
    }
    SayHiResult(sayHi.phase, onRetry = { sayHi.run(drafts.settings) })
}
