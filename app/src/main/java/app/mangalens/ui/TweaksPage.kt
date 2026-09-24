package app.mangalens.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mangalens.settings.AiReasoning
import app.mangalens.settings.AiVisionMode
import app.mangalens.settings.AppSettings
import app.mangalens.settings.CaptureMode
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SourceLang
import app.mangalens.translate.LlmHttp
import app.mangalens.translate.ModelCatalog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Where Tweaks opens: at the top, at the Your AI card (a missing or refused
 * key, a server to set up), or at the other providers, unfolded under More
 * options.
 */
internal enum class TweaksTarget { TOP, AI, OTHER_AI }

/**
 * Tweaks, kept to what a reader actually changes: the language, hands-free
 * or tap, the size of the lettering, and whether the AI's key works. That
 * is all the page shows. Everything else — timing, data, the thinking
 * setting, the model, other AI providers, diagnostics — has defaults that
 * are right for nearly everyone, and waits folded under More options,
 * where it can be found without being in the way.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TweaksPage(
    settings: AppSettings,
    sink: SettingsSink,
    drafts: AiDrafts,
    sayHi: SayHi,
    target: TweaksTarget,
    versionName: String,
    onClose: () -> Unit,
) {
    val pop = LocalPop.current
    var moreOpen by rememberSaveable { mutableStateOf(target == TweaksTarget.OTHER_AI) }
    val aiRequester = remember { BringIntoViewRequester() }
    val otherAiRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(target) {
        when (target) {
            TweaksTarget.TOP -> Unit
            TweaksTarget.AI -> {
                delay(250)
                aiRequester.bringIntoView()
            }
            TweaksTarget.OTHER_AI -> {
                moreOpen = true
                delay(250)
                otherAiRequester.bringIntoView()
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Tweaks",
                        style = MaterialTheme.typography.headlineSmall,
                        color = pop.ink,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Close tweaks", tint = pop.ink)
                    }
                }
                Text(
                    "The defaults are good. Most people never need more than this.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = pop.inkSoft,
                )

                ReadingSection(settings, sink, columns = if (narrow) 2 else 4)
                TextSizeSection(settings, sink)
                YourAiSection(drafts, sayHi, Modifier.bringIntoViewRequester(aiRequester))

                Spacer(Modifier.height(28.dp))
                MoreOptions(
                    settings, sink, drafts,
                    open = moreOpen,
                    onToggle = { moreOpen = !moreOpen },
                    otherAi = Modifier.bringIntoViewRequester(otherAiRequester),
                )

                Spacer(Modifier.height(32.dp))
                Text(
                    listOf("MangaLens", versionName).filter { it.isNotEmpty() }.joinToString(" ") +
                        " · Loving a raw? Support the official release when it exists.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = pop.inkSoft,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(24.dp))
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = LocalPop.current.inkSoft,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(8.dp))
}

/** A heading inside More options: smaller than a section, to keep the fold one piece. */
@Composable
private fun GroupTitle(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = LocalPop.current.ink,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Helper(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalPop.current.inkSoft, modifier = modifier)
}

@Composable
private fun ReadingSection(settings: AppSettings, sink: SettingsSink, columns: Int) {
    val pop = LocalPop.current
    SectionTitle("Reading")
    Text("Language on the page", style = MaterialTheme.typography.titleMedium, color = pop.ink)
    Spacer(Modifier.height(8.dp))
    PopTiles(
        listOf(
            PopTile(SourceLang.AUTO, "Auto", glyph = "★"),
            PopTile(SourceLang.KO, "Korean", glyph = "한"),
            PopTile(SourceLang.JA, "Japanese", glyph = "日"),
            PopTile(SourceLang.ZH, "Chinese", glyph = "中"),
        ),
        selected = settings.sourceLang,
        onSelect = sink::setSourceLang,
        columns = columns,
    )
    Spacer(Modifier.height(8.dp))
    Helper(
        when (settings.sourceLang) {
            SourceLang.AUTO -> "I spot the language on every page."
            SourceLang.KO -> "Reading Korean (manhwa)."
            SourceLang.JA -> "Reading Japanese (manga)."
            SourceLang.ZH -> "Reading Chinese (manhua)."
        }
    )
    Spacer(Modifier.height(12.dp))
    val handsFree = settings.mode == CaptureMode.AUTO
    PopToggleRow(
        "Hands-free",
        if (handsFree) "I translate each page as soon as you stop scrolling."
        else "I wait until you tap the $MARK bubble. Saves data.",
        handsFree,
        { sink.setMode(if (it) CaptureMode.AUTO else CaptureMode.MANUAL) },
    )
}

@Composable
private fun TextSizeSection(settings: AppSettings, sink: SettingsSink) {
    val pop = LocalPop.current
    SectionTitle("Look")
    PopSlider(
        "Text size",
        settings.textScale,
        0.8f..1.5f,
        { "${(it * 100).toInt()}%" },
        sink::setTextScale,
        below = { v ->
            SpeechBalloon(pop.surface, tail = Tail.Start, tailAt = 0.5f) {
                Text(
                    "Hey! Over here!",
                    fontFamily = ComicNeue,
                    fontWeight = FontWeight.Bold,
                    fontSize = (16 * v).sp,
                    lineHeight = (20 * v).sp,
                    color = pop.ink,
                )
            }
        },
    )
    Spacer(Modifier.height(6.dp))
    Helper("How big I letter the English on the page.")
}

/**
 * "Gemini · key saved ✓", or what is still missing, as the Your AI card
 * and the home screen say it. [refused] is whether the provider turned
 * away the key held now: setup then asks for a new key, and a line still
 * ticking it off would contradict it.
 */
internal fun aiBrainSummary(s: AppSettings, refused: Boolean = false): Pair<String, Boolean> {
    val label = LlmHttp.providerLabel(s)
    val custom = s.provider == LlmProvider.CUSTOM
    return when {
        LlmHttp.setupNeeded(s) != null -> (if (custom) "$label · no server URL yet" else "$label · no key yet") to false
        refused -> (if (custom) "$label · token refused" else "$label · key refused") to false
        else -> (if (custom) "$label · server set ✓" else "$label · key saved ✓") to true
    }
}

/**
 * The AI, reduced to what a reader needs to know: is the key in, and does
 * it work. A saved key shows as a line with Test it and Change key; the
 * key box itself appears only when there is no key yet, the key was
 * refused, or the reader asked to change it. A custom server's URL is
 * asked for here too, since nothing works without it.
 */
@Composable
private fun YourAiSection(drafts: AiDrafts, sayHi: SayHi, modifier: Modifier) {
    val pop = LocalPop.current
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val pasteWiggle = rememberWiggle()
    val s = drafts.settings
    val provider = s.provider
    val (summary, ready) = aiBrainSummary(s, sayHi.rejects(s))
    var changing by remember(provider) { mutableStateOf(false) }
    var notice by remember(provider) { mutableStateOf<String?>(null) }
    val phase = sayHi.phase
    LaunchedEffect(phase) {
        when {
            // A refused key can only be fixed by a new one: open the box and point at Paste.
            phase is SayHi.Phase.Failed && phase.rejected -> {
                changing = true
                view.buzz(Buzz.REJECT)
                pasteWiggle.play(reduced)
            }
            phase is SayHi.Phase.Ok -> changing = false
        }
    }

    SectionTitle("Your AI")
    PopSurface(modifier.fillMaxWidth(), RoundedCornerShape(20.dp), color = pop.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                summary,
                style = MaterialTheme.typography.titleMedium,
                color = if (ready) pop.ink else pop.punchText,
            )
            if (provider == LlmProvider.CUSTOM) {
                Spacer(Modifier.height(12.dp))
                SecretField(
                    value = drafts.customUrl,
                    onValueChange = drafts::editCustomUrl,
                    label = "Server URL (chat completions)",
                    secret = false,
                    keyboardType = KeyboardType.Uri,
                    isError = drafts.customUrl.isBlank(),
                    placeholder = "https://host/v1/chat/completions",
                )
            }
            if (ready && !changing) {
                Spacer(Modifier.height(12.dp))
                val testing = phase == SayHi.Phase.Running
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StickerButton(
                        if (testing) "Testing…" else "Test it",
                        { if (!testing && !sayHi.runIfReady(s)) view.buzz(Buzz.REJECT) },
                        modifier = Modifier.weight(1f),
                        style = StickerStyle.Surface,
                        height = 48.dp,
                        contentDescription = if (provider == LlmProvider.CUSTOM) "Test the server on a line" else "Test the key on a line",
                    )
                    StickerButton(
                        if (provider == LlmProvider.CUSTOM) "Change token" else "Change key",
                        { changing = true },
                        modifier = Modifier.weight(1f),
                        style = StickerStyle.Surface,
                        height = 48.dp,
                    )
                }
            } else if (provider != LlmProvider.CUSTOM || drafts.customUrl.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    SecretField(
                        value = drafts.key,
                        onValueChange = {
                            notice = null
                            drafts.editKey(it)
                        },
                        label = apiKeyLabel(provider),
                        modifier = Modifier.weight(1f),
                        showLabel = false,
                        placeholder = apiKeyLabel(provider),
                    )
                    Spacer(Modifier.width(10.dp))
                    StickerButton(
                        "Paste",
                        {
                            notice = null
                            val check = drafts.submitKey(clipboard.getText()?.text.orEmpty(), sayHi)
                            pasteNotice(check)?.let {
                                notice = it
                                view.buzz(Buzz.REJECT)
                                scope.launch { pasteWiggle.play(reduced) }
                            }
                        },
                        modifier = Modifier.fillMaxHeight(),
                        style = StickerStyle.Zap,
                        height = 48.dp,
                        fillWidth = false,
                        wiggle = pasteWiggle,
                        contentDescription = "Paste key from clipboard",
                    )
                }
                notice?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = pop.punchText)
                }
                providerKeyHelp(provider).url?.let { url ->
                    Spacer(Modifier.height(4.dp))
                    TextLink(providerKeyHelp(provider).linkLabel, { uri.openUri(url) })
                }
            }
        }
    }
    SayHiResult(phase, onRetry = { sayHi.runIfReady(s) })
}

/**
 * Everything whose default is right for nearly everyone, folded: timing
 * and data, how the AI works, which AI, and troubleshooting.
 */
@Composable
private fun MoreOptions(
    settings: AppSettings,
    sink: SettingsSink,
    drafts: AiDrafts,
    open: Boolean,
    onToggle: () -> Unit,
    otherAi: Modifier,
) {
    val pop = LocalPop.current
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val sunk = rememberSink(interaction)
    val chevron = animateFloatAsState(if (open) 180f else 0f, if (reduced) snap() else tween(200), label = "chevron")
    Column(Modifier.fillMaxWidth()) {
        PopSurface(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), color = pop.surface, sunk = { sunk.value }) {
            Row(
                Modifier
                    .clickable(interaction, indication = null, role = Role.Button, onClick = onToggle)
                    .semantics(mergeDescendants = true) {
                        stateDescription = if (open) "Expanded" else "Collapsed"
                    }
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "More options",
                        style = MaterialTheme.typography.titleLarge,
                        color = pop.ink,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        "Timing, data, model, other AIs. Leave these alone if things work.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = pop.inkSoft,
                    )
                }
                Text(
                    "▾",
                    style = MaterialTheme.typography.titleLarge,
                    color = pop.ink,
                    modifier = Modifier
                        .clearAndSetSemantics { }
                        .graphicsLayer { rotationZ = chevron.value },
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (reduced) Modifier else Modifier.animateContentSize())
        ) {
            if (open) MoreOptionsBody(settings, sink, drafts, otherAi)
        }
    }
}

@Composable
private fun MoreOptionsBody(settings: AppSettings, sink: SettingsSink, drafts: AiDrafts, otherAi: Modifier) {
    val pop = LocalPop.current
    val provider = settings.provider

    GroupTitle("Timing and data")
    PopSlider(
        "Reaction time",
        settings.stabilityMs.toFloat(),
        200f..900f,
        { "${it.toInt()} ms" },
        { sink.setStabilityMs(it.toInt()) },
        startLabel = "Snappy",
        endLabel = "Patient",
    )
    Helper("How long a page sits still before I read it.")
    Spacer(Modifier.height(16.dp))
    PopSlider(
        "Skip the top of the screen",
        settings.ignoreTopPct,
        0f..0.15f,
        { "${(it * 100).toInt()}%" },
        sink::setIgnoreTopPct,
    )
    Helper("Keeps me off your browser's address bar.")
    Spacer(Modifier.height(16.dp))
    PopToggleRow(
        "Data saver",
        "Smaller page uploads. Tiny text may read a little less sharply.",
        settings.dataSaver,
        sink::setDataSaver,
    )

    GroupTitle("How the AI works")
    Text("Thinking time", style = MaterialTheme.typography.bodyLarge, color = pop.ink)
    Spacer(Modifier.height(8.dp))
    PopTiles(
        listOf(
            PopTile(AiReasoning.FAST, "Fast"),
            PopTile(AiReasoning.BALANCED, "Balanced"),
            PopTile(AiReasoning.THOROUGH, "Thorough"),
        ),
        selected = settings.aiReasoning,
        onSelect = sink::setAiReasoning,
        columns = 3,
        minHeight = 56.dp,
    )
    Spacer(Modifier.height(8.dp))
    Helper(
        when (settings.aiReasoning) {
            AiReasoning.FAST -> "Quickest first line. Fine for clean lettering."
            AiReasoning.BALANCED -> "The best mix of speed and care. Recommended."
            AiReasoning.THOROUGH -> "Most careful with who's speaking and wild lettering, but slower."
        }
    )
    Spacer(Modifier.height(16.dp))
    ModelChoice(drafts)
    if (provider == LlmProvider.GEMINI) {
        Spacer(Modifier.height(16.dp))
        PopToggleRow(
            "AI art redraw",
            "Asks an image model to repaint busy art under text. Often refused on explicit pages; off is fine.",
            settings.aiCleanup,
            sink::setAiCleanup,
        )
    } else {
        Spacer(Modifier.height(16.dp))
        val sees = settings.aiVision != AiVisionMode.OFF
        PopToggleRow(
            "Let the AI see the page",
            if (sees) "Sends the page image, so handwriting and wild lettering are read too."
            else "Sends only the text your phone reads. Uses less data; fancy lettering may be missed.",
            sees,
            { sink.setAiVision(if (it) AiVisionMode.AUTO else AiVisionMode.OFF) },
        )
    }

    Column(otherAi) {
        GroupTitle("Use a different AI")
        Helper("Gemini is the fastest here and its key is free. Pick another only if you already pay for it.")
    }
    Spacer(Modifier.height(8.dp))
    PopTiles(
        listOf(
            PopTile(LlmProvider.GEMINI, "Gemini", caption = "Free · fastest"),
            PopTile(LlmProvider.ANTHROPIC, "Claude", caption = "by Anthropic"),
            PopTile(LlmProvider.OPENAI, "OpenAI", caption = "Paid key"),
            PopTile(LlmProvider.OPENROUTER, "OpenRouter", caption = "Many models"),
            PopTile(LlmProvider.CUSTOM, "Custom", caption = "Your own server"),
        ),
        selected = provider,
        onSelect = sink::setProvider,
        columns = 2,
        minHeight = 56.dp,
    )
    Spacer(Modifier.height(8.dp))
    Helper("The key and, for Custom, the server URL go in Your AI above.")

    GroupTitle("Troubleshooting")
    PopToggleRow(
        "Diagnostics",
        "Outlines what I find on the page and shows a status line, to see why a balloon was missed.",
        settings.diagnostics,
        sink::setDiagnostics,
    )
}

/**
 * The model: automatic — the newest Flash, which follows Google's releases
 * with no app update — unless the reader picked one. For Gemini it is
 * chosen from the account's live list, never typed; other providers take
 * a model id, blank for their default.
 */
@Composable
private fun ModelChoice(drafts: AiDrafts) {
    val pop = LocalPop.current
    val s = drafts.settings
    Text("Model", style = MaterialTheme.typography.bodyLarge, color = pop.ink)
    Spacer(Modifier.height(6.dp))
    if (s.provider == LlmProvider.GEMINI) {
        val pinned = drafts.model.isNotBlank()
        Helper(if (pinned) drafts.model else "Automatic: the newest Gemini Flash. Best for almost everyone.")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GeminiModelPicker(apiKey = drafts.key.trim(), onPick = drafts::editModel, modifier = Modifier.weight(1f))
            if (pinned) {
                StickerButton(
                    "Automatic",
                    { drafts.editModel("") },
                    modifier = Modifier.weight(1f),
                    style = StickerStyle.Surface,
                    height = 48.dp,
                    contentDescription = "Use the automatic model",
                )
            }
        }
    } else {
        SecretField(
            value = drafts.model,
            onValueChange = drafts::editModel,
            label = "Model",
            secret = false,
            showLabel = false,
            placeholder = "Blank = " + s.copy(model = "").effectiveModel().ifEmpty { "your server's default" },
        )
    }
}

/**
 * Fetches Gemini's live model list on demand and offers it as a menu — the
 * newest Flash first — so the picker shows models released long after this
 * build shipped. Typing in the Model field always stays available. The copy
 * names Gemini, never its maker: to a reader who found the old machine
 * translation useless, the maker's name reads as that engine coming back.
 */
@Composable
private fun GeminiModelPicker(apiKey: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val pop = LocalPop.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<ModelCatalog.LiveModel>>(emptyList()) }
    Box(modifier) {
        StickerButton(
            if (loading) "Asking Gemini…" else "Choose a model ▾",
            {
                if (loading) return@StickerButton
                if (apiKey.isBlank()) {
                    error = "Add your key first. The list comes from your account."
                    return@StickerButton
                }
                error = null
                if (models.isNotEmpty()) {
                    open = true
                    return@StickerButton
                }
                loading = true
                scope.launch {
                    try {
                        models = ModelCatalog.gemini(apiKey)
                        open = models.isNotEmpty()
                        if (models.isEmpty()) error = "Gemini returned no usable models."
                    } catch (e: Exception) {
                        error = "Couldn't fetch models: " + (e.message ?: "network error")
                    } finally {
                        loading = false
                    }
                }
            },
            style = StickerStyle.Surface,
            height = 48.dp,
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = pop.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(2.dp, pop.stroke),
        ) {
            models.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(m.label, style = MaterialTheme.typography.titleMedium, color = pop.ink)
                            Text(m.id, style = MonoStyle.copy(fontSize = 14.sp), color = pop.inkSoft)
                        }
                    },
                    onClick = {
                        open = false
                        onPick(m.id)
                    },
                )
            }
        }
    }
    error?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = pop.punchText)
    }
}
