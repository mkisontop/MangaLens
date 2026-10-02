package app.mangalens.ui

import android.content.Context
import android.os.Build
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mangalens.capture.ScreenCaptureService
import app.mangalens.overlay.LetteringHost
import app.mangalens.scroll.AutoScrollHost
import app.mangalens.overlay.OverlayStrength
import app.mangalens.settings.AppSettings
import app.mangalens.settings.CaptureMode
import app.mangalens.settings.SettingsRepository
import app.mangalens.translate.LlmHttp
import app.mangalens.update.UpdateChecker
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/** Everything the home screen shows, as plain values; see [HomeContent]. */
@Immutable
internal data class HomeUiState(
    /** Null until the settings store has answered; nothing but paper shows until then. */
    val settings: AppSettings?,
    val running: Boolean = false,
    val paused: Boolean = false,
    val overlayGranted: Boolean = false,
    /**
     * Whether "Make the English solid" is offered at all: only by a build
     * that declares the lettering host (see [LetteringHost.declared]), and
     * only on Android 12 and later, which hold a see-through overlay to 80%
     * opacity; before that the lettering is drawn as painted.
     */
    val solidOffered: Boolean = false,
    /** MangaLens is on in Accessibility, so the lettering is drawn at full strength. */
    val solidLettering: Boolean = false,
    /** "MangaLens auto-scroll" is on in Accessibility, so auto-scroll can move the page. */
    val autoScrollOn: Boolean = false,
    /**
     * Whether "No ghosts" is offered: Android draws the lettering below
     * full strength (see OverlayStrength), so the original would show
     * through it unless the page is veiled.
     */
    val ghostsOffered: Boolean = false,
    /** "Brave" when it is installed, else null and the copy says "your browser". */
    val browserName: String? = null,
    /** The last GO ended at the screen-capture dialog's Cancel. */
    val startRefused: Boolean = false,
    val update: UpdateChecker.Update? = null,
    val versionName: String = "",
)

/** What the home screen asks the activity to do. */
@Immutable
internal class HomeActions(
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onGrantOverlay: () -> Unit = {},
    val onTogglePause: () -> Unit = {},
    val onOpenBrowser: () -> Unit = {},
    /** Accessibility settings, where solid lettering is switched on. */
    val onTurnOnSolid: () -> Unit = {},
    /** App info, where Android 13 and later allow a restricted setting. */
    val onOpenAppInfo: () -> Unit = {},
    /** Accessibility settings, where auto-scroll is switched on. */
    val onTurnOnAutoScroll: () -> Unit = {},
)

private enum class Page { HOME, TWEAKS }

/**
 * The home screen: reads the settings store, the capture service and the
 * system, and hands plain state to [HomeContent]. It holds no UI of its
 * own, so every face of the screen can be rendered from a fixed state.
 */
@Composable
internal fun HomeScreen(
    repo: SettingsRepository,
    browserName: String?,
    tweaksRequests: Int,
    tweaksTarget: TweaksTarget,
    startRefused: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onGrantOverlay: () -> Unit,
    onTogglePause: () -> Unit,
    onOpenBrowser: () -> Unit,
    onTurnOnSolid: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onTurnOnAutoScroll: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings: AppSettings? by repo.flow.collectAsState(initial = null)
    val running by ScreenCaptureService.running.collectAsState()
    val paused by ScreenCaptureService.pausedState.collectAsState()

    // Android 8.x reports a fresh overlay grant late, so this polls rather
    // than trusting one check on resume. The same tick notices "Remove
    // animations" being switched while the app is open.
    var overlayGranted by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    var reducedMotion by remember { mutableStateOf(animationsOff(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            overlayGranted = android.provider.Settings.canDrawOverlays(context)
            reducedMotion = animationsOff(context)
            delay(1000)
        }
    }
    val solidOffered = remember { Build.VERSION.SDK_INT >= 31 && LetteringHost.declared(context) }
    val ghostsOffered = remember { OverlayStrength.of(context) < 1f }
    // Solid lettering is switched on in Settings, so it is checked again
    // each time the reader comes back. Only where it is offered: a switch
    // turned on in 1.0.1, the one release that declared the host, can
    // outlive the service in Accessibility's list, and taken at its word it
    // would hide "No ghosts" from a reader who cannot have solid lettering.
    var solidLettering by remember { mutableStateOf(solidOffered && LetteringHost.isOn(context)) }
    var autoScrollOn by remember { mutableStateOf(AutoScrollHost.isOn(context)) }
    LifecycleResumeEffect(Unit) {
        solidLettering = solidOffered && LetteringHost.isOn(context)
        autoScrollOn = AutoScrollHost.isOn(context)
        onPauseOrDispose { }
    }

    // One anonymous check per app open; a sticker only when a newer release exists.
    var update by remember { mutableStateOf<UpdateChecker.Update?>(null) }
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()
    }
    LaunchedEffect(Unit) {
        if (versionName.isNotEmpty()) {
            update = UpdateChecker.check(
                currentVersion = versionName,
                sdkInt = Build.VERSION.SDK_INT,
                signingTrack = UpdateChecker.installedSigningTrack(context),
            )
        }
    }

    val sink = remember(repo, scope) { RepoSink(scope, repo) }
    val drafts = rememberAiDrafts(settings ?: AppSettings(), sink)
    val sayHi = remember(scope) { SayHi(scope) }
    val actions = remember(onStart, onStop, onGrantOverlay, onTogglePause, onOpenBrowser, onTurnOnSolid, onOpenAppInfo, onTurnOnAutoScroll) {
        HomeActions(onStart, onStop, onGrantOverlay, onTogglePause, onOpenBrowser, onTurnOnSolid, onOpenAppInfo, onTurnOnAutoScroll)
    }
    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
        HomeContent(
            state = HomeUiState(
                settings = settings,
                running = running,
                paused = paused,
                overlayGranted = overlayGranted,
                solidOffered = solidOffered,
                solidLettering = solidLettering,
                autoScrollOn = autoScrollOn,
                ghostsOffered = ghostsOffered,
                browserName = browserName,
                startRefused = startRefused,
                update = update,
                versionName = versionName,
            ),
            drafts = drafts,
            sayHi = sayHi,
            sink = sink,
            actions = actions,
            tweaksRequests = tweaksRequests,
            tweaksTarget = tweaksTarget,
        )
    }
}

private fun animationsOff(context: Context): Boolean = runCatching {
    android.provider.Settings.Global.getFloat(
        context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    ) == 0f
}.getOrDefault(false)

/**
 * The whole home screen, from plain state. Owns only what the reader does
 * on it: which page is up, the setup latch, the tip on show and the sound
 * effect to play.
 */
@Composable
internal fun HomeContent(
    state: HomeUiState,
    drafts: AiDrafts,
    sayHi: SayHi,
    sink: SettingsSink,
    actions: HomeActions,
    tweaksRequests: Int = 0,
    tweaksTarget: TweaksTarget = TweaksTarget.TOP,
) {
    val pop = LocalPop.current
    val reduced = LocalReducedMotion.current
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    var target by rememberSaveable { mutableStateOf(TweaksTarget.TOP) }
    // Runs once for each new count, which is all the marking a request
    // needs. The count lives in the activity and starts again at 0 when it
    // is recreated; a mark saved with the page would outlive it and swallow
    // the first request after a rotation.
    LaunchedEffect(tweaksRequests) {
        if (tweaksRequests > 0) {
            target = tweaksTarget
            page = Page.TWEAKS
        }
    }
    BackHandler(page == Page.TWEAKS) { page = Page.HOME }
    val openTweaks: (TweaksTarget) -> Unit = {
        target = it
        page = Page.TWEAKS
    }

    val settings = state.settings
    val loaded = settings != null
    val aiReady = settings != null && LlmHttp.setupNeeded(settings) == null && !sayHi.rejects(settings)

    // The latch: up whenever something is missing, down only on "All set" or a start.
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    val needsSetup = loaded && !state.running && (!state.overlayGranted || !aiReady)
    LaunchedEffect(needsSetup) { if (needsSetup) setupOpen = true }
    LaunchedEffect(state.running) { if (state.running) setupOpen = false }
    val stage = homeStage(loaded, state.running, state.paused, state.overlayGranted, aiReady, setupOpen)

    // BOOP! on the edge into running — never when the screen opens on a running session.
    var sfx by remember { mutableStateOf<SfxShot?>(null) }
    var lastRunning by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(state.running, loaded) {
        if (!loaded) return@LaunchedEffect
        if (lastRunning == false && state.running) sfx = SfxShot(SfxKind.BOOP)
        lastRunning = state.running
    }
    // One tip index for every stage, so a stage change never snaps back
    // to the first tip; a new mode starts its own list from the top.
    var tipIndex by rememberSaveable(settings?.mode) { mutableIntStateOf(0) }

    // The sticker already buzzed CONFIRM for the tap.
    val onAllSet = {
        sfx = SfxShot(SfxKind.KAPOW)
        setupOpen = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(pop.paper)
            .halftone(pop.dots, DotCorner.TopEnd, maxDot = pop.dotMax)
            .halftone(pop.dots, DotCorner.BottomStart, maxDot = pop.dotMax)
    ) {
        if (settings == null) return@Box
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                when {
                    reduced -> EnterTransition.None togetherWith ExitTransition.None
                    targetState == Page.TWEAKS ->
                        (slideInVertically(tween(260)) { it } + fadeIn(tween(220))) togetherWith fadeOut(tween(180))
                    else -> fadeIn(tween(220)) togetherWith (slideOutVertically(tween(260)) { it } + fadeOut(tween(180)))
                }.apply { targetContentZIndex = if (targetState == Page.TWEAKS) 1f else 0f }
            },
            label = "page",
        ) { p ->
            when (p) {
                // Dots only at the bottom here: behind the header they sat
                // under the title and subtitle and made the small text noisy.
                Page.TWEAKS -> Box(
                    Modifier
                        .fillMaxSize()
                        .background(pop.paper)
                        .halftone(pop.dots, DotCorner.BottomStart, maxDot = pop.dotMax)
                ) {
                    TweaksPage(
                        settings = settings,
                        sink = sink,
                        drafts = drafts,
                        sayHi = sayHi,
                        target = target,
                        versionName = state.versionName,
                        solidOffered = state.solidOffered,
                        solidLettering = state.solidLettering,
                        ghostsOffered = state.ghostsOffered,
                        onTurnOnSolid = actions.onTurnOnSolid,
                        onOpenAppInfo = actions.onOpenAppInfo,
                        autoScrollOn = state.autoScrollOn,
                        onTurnOnAutoScroll = actions.onTurnOnAutoScroll,
                        onClose = { page = Page.HOME },
                    )
                }
                Page.HOME -> HomePage(
                    state, settings, stage, aiReady, drafts, sayHi, actions, sfx,
                    tip = tipIndex,
                    onNextTip = { tipIndex++ },
                    onAllSet = onAllSet,
                    openTweaks = openTweaks,
                )
            }
        }
    }
}

@Composable
private fun HomePage(
    state: HomeUiState,
    settings: AppSettings,
    stage: Stage,
    aiReady: Boolean,
    drafts: AiDrafts,
    sayHi: SayHi,
    actions: HomeActions,
    sfx: SfxShot?,
    tip: Int,
    onNextTip: () -> Unit,
    onAllSet: () -> Unit,
    openTweaks: (TweaksTarget) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val uri = LocalUriHandler.current
    var dialog by remember { mutableStateOf(false) }
    val browser = state.browserName ?: "your browser"
    val summary = tweaksSummary(settings)

    val tips = tipsFor(settings.mode)
    val tipBalloon: @Composable () -> Unit = {
        TipBalloon(tips, tip, onNextTip, fukiLook(stage))
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val content = minOf(maxWidth, 520.dp) - 40.dp
        val landscape = maxWidth > maxHeight * 1.2f && maxHeight < 600.dp
        // The stage is 1.7 Fuki wide, room for the burst's longest spikes.
        val diameter = if (landscape) minOf(maxHeight * 0.5f, 190.dp)
        else minOf(content / 1.7f, maxHeight * 0.28f).coerceIn(150.dp, 230.dp)

        val setup = stage == Stage.SETUP
        AnimatedContent(
            targetState = setup,
            transitionSpec = {
                if (reduced) EnterTransition.None togetherWith ExitTransition.None
                else fadeIn(tween(220)) togetherWith fadeOut(tween(150))
            },
            label = "setup",
        ) { isSetup ->
            if (isSetup) {
                ScrollColumn {
                    TopRow(state.update) { dialog = true }
                    Spacer(Modifier.height(8.dp))
                    SetupChecklist(
                        settings = settings,
                        drafts = drafts,
                        sayHi = sayHi,
                        overlayGranted = state.overlayGranted,
                        aiReady = aiReady,
                        solidOffered = state.solidOffered,
                        solidLettering = state.solidLettering,
                        browser = browser,
                        onGrantOverlay = actions.onGrantOverlay,
                        onOpenAiTweaks = { openTweaks(TweaksTarget.AI) },
                        onOpenOtherAi = { openTweaks(TweaksTarget.OTHER_AI) },
                        onTurnOnSolid = actions.onTurnOnSolid,
                        onOpenAppInfo = actions.onOpenAppInfo,
                        onAllSet = onAllSet,
                    )
                    Spacer(Modifier.height(20.dp))
                    TweaksSticker(summary) { openTweaks(TweaksTarget.TOP) }
                    Spacer(Modifier.height(24.dp))
                }
            } else if (landscape) {
                Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        PlayFuki(stage, diameter, sfx, actions, state.startRefused)
                    }
                    Column(
                        Modifier
                            .weight(1.2f)
                            .fillMaxHeight()
                            .imePadding()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp)
                    ) {
                        TopRow(state.update) { dialog = true }
                        PlayHeadline(stage, state.startRefused)
                        Spacer(Modifier.height(8.dp))
                        PlayBody(stage, state, settings, aiReady, browser, actions, openTweaks, tipBalloon)
                        Spacer(Modifier.height(20.dp))
                        TweaksSticker(summary) { openTweaks(TweaksTarget.TOP) }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            } else {
                FillingColumn {
                    TopRow(state.update) { dialog = true }
                    // The spare height goes a third above the headline and
                    // the rest above Tweaks, so the stage sits mid-screen and
                    // Tweaks sits low, where a thumb reaches it.
                    Spacer(Modifier.weight(0.35f))
                    PlayHeadline(stage, state.startRefused)
                    Spacer(Modifier.height(8.dp))
                    PlayFuki(stage, diameter, sfx, actions, state.startRefused, Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(8.dp))
                    PlayBody(stage, state, settings, aiReady, browser, actions, openTweaks, tipBalloon)
                    Spacer(Modifier.weight(0.65f))
                    Spacer(Modifier.height(20.dp))
                    TweaksSticker(summary) { openTweaks(TweaksTarget.TOP) }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    val update = state.update
    if (dialog && update != null) {
        UpdateDialog(update, onDownload = {
            dialog = false
            uri.openUri(update.url)
        }, onDismiss = { dialog = false })
    }
}

@Composable
private fun ScrollColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), content = content)
    }
}

/**
 * A scrolling column at least as tall as the screen, so weighted spacers
 * inside it share out the spare height on a tall phone and shrink to
 * nothing when the content (or a large font) needs every pixel.
 */
@Composable
private fun FillingColumn(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val viewport = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(min = viewport), content = content)
        }
    }
}

@Composable
private fun TopRow(update: UpdateChecker.Update?, onUpdate: () -> Unit) {
    val pop = LocalPop.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        MiniMark()
        Spacer(Modifier.width(8.dp))
        Text(
            "MangaLens",
            style = MaterialTheme.typography.titleLarge,
            color = pop.ink,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.weight(1f))
        if (update != null) UpdateSticker(update, onUpdate)
    }
}

@Composable
private fun PlayHeadline(stage: Stage, startRefused: Boolean) {
    val pop = LocalPop.current
    val reduced = LocalReducedMotion.current
    val text = when (stage) {
        Stage.RUNNING -> "Reading along!"
        Stage.PAUSED -> "Taking a nap."
        else -> if (startRefused) "I can't read what I can't see!" else "Ready when you are!"
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val small = maxWidth < 360.dp
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                if (reduced) EnterTransition.None togetherWith ExitTransition.None
                else fadeIn(tween(220)) togetherWith fadeOut(tween(150))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "headline",
        ) { t ->
            val base = MaterialTheme.typography.headlineLarge.copy(lineBreak = LineBreak.Heading)
            Text(
                t,
                style = if (small) base.copy(fontSize = 34.sp, lineHeight = 38.sp) else base,
                color = pop.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
        }
    }
}

/**
 * The one big button. Paused, a tap wakes Fuki up rather than stopping: a
 * napping face invites a poke, and a poke that ended the session would
 * also throw away the screen-share grant. Stop has its own sticker below.
 */
@Composable
private fun PlayFuki(
    stage: Stage,
    diameter: Dp,
    sfx: SfxShot?,
    actions: HomeActions,
    refused: Boolean,
    modifier: Modifier = Modifier,
) {
    FukiStage(
        stage = stage,
        diameter = diameter,
        sfx = sfx,
        onClick = {
            when (stage) {
                Stage.READY -> actions.onStart()
                Stage.RUNNING -> actions.onStop()
                Stage.PAUSED -> actions.onTogglePause()
                else -> Unit
            }
        },
        modifier = modifier,
        worried = stage == Stage.READY && refused,
    )
}

/** The caption, the stage's buttons and the tip: everything under Fuki that changes with the stage. */
@Composable
private fun PlayBody(
    stage: Stage,
    state: HomeUiState,
    settings: AppSettings,
    aiReady: Boolean,
    browser: String,
    actions: HomeActions,
    openTweaks: (TweaksTarget) -> Unit,
    tipBalloon: @Composable () -> Unit,
) {
    val pop = LocalPop.current
    val reduced = LocalReducedMotion.current
    val handsFree = settings.mode == CaptureMode.AUTO
    AnimatedContent(
        targetState = stage,
        transitionSpec = {
            if (reduced) EnterTransition.None togetherWith ExitTransition.None
            else fadeIn(tween(220)) togetherWith fadeOut(tween(150))
        },
        label = "body",
    ) { s ->
        Column(Modifier.fillMaxWidth()) {
            val caption = when (s) {
                Stage.RUNNING ->
                    if (handsFree) "Scroll in $browser. When you stop, I translate. Tap the $MARK bubble to pause; hold it for more."
                    else "Open a page in $browser, then tap the $MARK bubble to translate it. Hold it for more."
                Stage.PAUSED -> "I'm napping. Tap me to wake up, or tap the $MARK bubble in $browser."
                else ->
                    if (state.startRefused) "⚠ Tap GO again and allow screen sharing. If Android offers a choice, pick “Entire screen”."
                    else "Tap GO and allow screen sharing, then hop into $browser. When you stop scrolling, I letter the English right over the bubbles."
            }
            val refused = s == Stage.READY && state.startRefused
            Text(
                caption,
                style = MaterialTheme.typography.bodyLarge,
                color = if (refused) pop.punchText else pop.inkSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            val openLabel = if (state.browserName != null) "Open ${state.browserName} ↗" else "Open my browser ↗"
            when (s) {
                Stage.RUNNING -> {
                    if (!aiReady) {
                        Spacer(Modifier.height(16.dp))
                        StickerButton(
                            "⚠ No AI key, so I can't translate. Add one →",
                            { openTweaks(TweaksTarget.AI) },
                            style = StickerStyle.Warn,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    StickerButton(openLabel, actions.onOpenBrowser, height = 64.dp)
                }
                // Napping Fuki is the wake button, so nothing here is zap.
                Stage.PAUSED -> {
                    Spacer(Modifier.height(16.dp))
                    StickerButton(openLabel, actions.onOpenBrowser, style = StickerStyle.Surface)
                    Spacer(Modifier.height(12.dp))
                    StickerButton("Stop translating", actions.onStop, style = StickerStyle.Surface)
                }
                else -> Unit
            }
            Spacer(Modifier.height(16.dp))
            tipBalloon()
        }
    }
}

/**
 * Fuki's tips, one at a time; a tap shows the next. A small Fuki sits at
 * the balloon's tail so the tip is plainly Fuki talking, and the balloon
 * sinks into its shadow when tapped, like any other sticker.
 */
@Composable
private fun TipBalloon(tips: List<String>, index: Int, onNext: () -> Unit, look: FukiLook) {
    val pop = LocalPop.current
    val reduced = LocalReducedMotion.current
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val sink = rememberSink(interaction)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = "Next tip") {
                view.buzz(Buzz.TICK)
                onNext()
            }
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FukiAvatar(look)
        Spacer(Modifier.width(2.dp))
        SpeechBalloon(
            pop.zapSoft,
            Modifier.weight(1f),
            tail = Tail.Start,
            tailAt = 0.5f,
            strokeColor = pop.zapSoftStroke,
            sunk = { sink.value },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(
                    targetState = index,
                    transitionSpec = {
                        if (reduced) EnterTransition.None togetherWith ExitTransition.None
                        else (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 4 }) togetherWith fadeOut(tween(120))
                    },
                    modifier = Modifier.weight(1f),
                    label = "tip",
                ) { i ->
                    Text(tips[i % tips.size], style = MaterialTheme.typography.bodyLarge, color = pop.ink)
                }
                Spacer(Modifier.width(8.dp))
                Text("›", style = MaterialTheme.typography.titleLarge, color = pop.punchText, modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}

/** The door to every setting, with the three that matter most summed up on it. */
@Composable
internal fun TweaksSticker(summary: String, onClick: () -> Unit) {
    val pop = LocalPop.current
    val interaction = remember { MutableInteractionSource() }
    val sink = rememberSink(interaction)
    PopSurface(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), color = pop.surface, sunk = { sink.value }) {
        Row(
            Modifier
                .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
                .clearAndSetSemantics { contentDescription = "Tweaks: $summary" }
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null, tint = pop.ink, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Tweaks", style = MaterialTheme.typography.titleMedium, color = pop.ink)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = pop.inkSoft)
            }
            Spacer(Modifier.width(8.dp))
            Text("›", style = MaterialTheme.typography.titleLarge, color = pop.ink)
        }
    }
}

/** The body of the update dialog, a sticker card like everything else, with a NEW! burst slapped on its corner. */
@Composable
internal fun UpdateDialogCard(update: UpdateChecker.Update, onDownload: () -> Unit, onLater: () -> Unit) {
    val pop = LocalPop.current
    Box {
        PopSurface(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), color = pop.surface) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp)) {
                Text(
                    "MangaLens ${update.version} is out!",
                    style = MaterialTheme.typography.titleLarge.copy(lineBreak = LineBreak.Heading),
                    color = pop.ink,
                    modifier = Modifier.padding(end = 40.dp).semantics { heading() },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        update.requiresReinstall ->
                            "This install can't update in place. Write down your API key, download the APK, uninstall MangaLens, then install the new one. Your API key will be cleared."
                        update.legacyBridge ->
                            "This one-time bridge APK keeps your data while moving 0.9.1 to the private release key."
                        else -> "A fresh version is ready. Tap Download; Android asks before installing anything."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = pop.ink,
                )
                Spacer(Modifier.height(20.dp))
                StickerButton("Download", onDownload)
                TextLink("Later", onLater, Modifier.align(Alignment.CenterHorizontally))
            }
        }
        NewBurst(Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-26).dp))
    }
}

/** A small yellow starburst with "NEW!" on it, slapped on the dialog's corner like a price sticker. */
@Composable
private fun NewBurst(modifier: Modifier = Modifier) {
    val pop = LocalPop.current
    val glyph = with(LocalDensity.current) { 15.dp.toSp() }
    Box(
        modifier
            .size(72.dp)
            .graphicsLayer { rotationZ = 8f }
            .clearAndSetSemantics { }
            .drawWithCache {
                val c = Offset(size.width / 2f, size.height / 2f)
                val rp = size.minDimension / 2f
                // Even spikes here: at badge size the big burst's wobble
                // reads as a crumpled scrap rather than a POW.
                val path = Path().apply {
                    for (k in 0 until 24) {
                        val a = (k * 15f - 90f) * PI.toFloat() / 180f
                        val r = if (k % 2 == 0) 0.96f * rp else 0.72f * rp
                        val x = c.x + cos(a) * r
                        val y = c.y + sin(a) * r
                        if (k == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                val drop = Offset(2.dp.toPx(), 3.dp.toPx())
                val stroke = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round)
                onDrawBehind {
                    translate(drop.x, drop.y) { drawPath(path, pop.shadow) }
                    drawPath(path, pop.zap)
                    drawPath(path, pop.faceInk, style = stroke)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "NEW!",
            fontFamily = ComicNeue,
            fontWeight = FontWeight.Bold,
            fontSize = glyph,
            lineHeight = glyph,
            color = pop.onZap,
        )
    }
}

/**
 * The update dialog. It draws its own scrim: the platform's black dim
 * turned yellow Fuki and the red burst a muddy brown behind the card, so in
 * the light the page washes out to paper instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateDialog(update: UpdateChecker.Update, onDownload: () -> Unit, onDismiss: () -> Unit) {
    val pop = LocalPop.current
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setDimAmount(0f)
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val scrim = if (pop.dark) Color.Black.copy(alpha = 0.55f) else pop.paper.copy(alpha = 0.8f)
        Box(
            Modifier
                .fillMaxSize()
                .background(scrim)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = onDismiss)
                .safeDrawingPadding()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Taps on the card itself must not fall through to the scrim.
            Box(Modifier.widthIn(max = 480.dp).pointerInput(Unit) { detectTapGestures { } }) {
                UpdateDialogCard(update, onDownload, onDismiss)
            }
        }
    }
}
