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
import androidx.compose.ui.semantics.contentDescription
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

/** Where Tweaks opens: at the top, or scrolled to an unfolded AI brain. */
internal enum class TweaksTarget { TOP, AI }

/**
 * Every knob in one place. A full page rather than a sheet: it holds text
 * fields, the keyboard and dropdowns, and the overlay's quick menu opens
 * it directly. Reading, Look and Timing come first because they are what a
 * reader adjusts; the AI brain is folded, since its defaults rarely need
 * touching once a key is in.
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
    var aiOpen by rememberSaveable { mutableStateOf(target == TweaksTarget.AI) }
    val aiRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(target) {
        if (target == TweaksTarget.AI) {
            aiOpen = true
            delay(250)
            aiRequester.bringIntoView()
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
                    "Every knob in one place. The defaults are good!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = pop.inkSoft,
                )

                ReadingSection(settings, sink, columns = if (narrow) 2 else 4)
                LookSection(settings, sink)
                TimingSection(settings, sink)

                Spacer(Modifier.height(24.dp))
                AiBrain(settings, sink, drafts, sayHi, aiOpen, { aiOpen = !aiOpen }, Modifier.bringIntoViewRequester(aiRequester))

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
private fun LookSection(settings: AppSettings, sink: SettingsSink) {
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
    Spacer(Modifier.height(16.dp))
    PopSlider(
        "Skip the top of the screen",
        settings.ignoreTopPct,
        0f..0.15f,
        { "${(it * 100).toInt()}%" },
        sink::setIgnoreTopPct,
    )
    Helper("Keeps me off your browser's address bar.")
}

@Composable
private fun TimingSection(settings: AppSettings, sink: SettingsSink) {
    SectionTitle("Timing")
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
}

/** "Gemini · key saved ✓", or what is still missing, in the header of the folded AI brain. */
internal fun aiBrainSummary(s: AppSettings): Pair<String, Boolean> {
    val label = LlmHttp.providerLabel(s)
    val ready = LlmHttp.setupNeeded(s) == null
    return if (s.provider == LlmProvider.CUSTOM) {
        (if (ready) "$label · endpoint set ✓" else "$label · no endpoint yet") to ready
    } else {
        (if (ready) "$label · key saved ✓" else "$label · no key yet") to ready
    }
}

@Composable
private fun AiBrain(
    settings: AppSettings,
    sink: SettingsSink,
    drafts: AiDrafts,
    sayHi: SayHi,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier,
) {
    val pop = LocalPop.current
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val sink2 = rememberSink(interaction)
    val chevron = animateFloatAsState(if (open) 180f else 0f, if (reduced) snap() else tween(200), label = "chevron")
    val (summary, ready) = aiBrainSummary(drafts.settings)
    Column(modifier.fillMaxWidth()) {
        PopSurface(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), color = pop.surface, sunk = { sink2.value }) {
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
                        "AI brain",
                        style = MaterialTheme.typography.titleLarge,
                        color = pop.ink,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (ready) pop.ink else pop.punchText,
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
            if (open) AiBrainBody(settings, sink, drafts, sayHi)
        }
    }
}

@Composable
private fun AiBrainBody(settings: AppSettings, sink: SettingsSink, drafts: AiDrafts, sayHi: SayHi) {
    val pop = LocalPop.current
    val provider = settings.provider
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val pasteWiggle = rememberWiggle()
    var notice by remember(provider) { mutableStateOf<String?>(null) }

    Spacer(Modifier.height(16.dp))
    Text("Who translates", style = MaterialTheme.typography.titleMedium, color = pop.ink)
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

    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        SecretField(
            value = drafts.key,
            onValueChange = {
                notice = null
                drafts.editKey(it)
            },
            label = apiKeyLabel(provider),
            modifier = Modifier.weight(1f),
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
            style = StickerStyle.Surface,
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
    val help = providerKeyHelp(provider)
    Spacer(Modifier.height(6.dp))
    Helper(help.message)
    help.url?.let { url -> TextLink(help.linkLabel, { uri.openUri(url) }) }

    Spacer(Modifier.height(12.dp))
    SecretField(
        value = drafts.model,
        onValueChange = drafts::editModel,
        label = "Model",
        secret = false,
        placeholder = "Blank = " + drafts.settings.copy(model = "").effectiveModel(),
    )
    // The placeholder only shows while typing, so the default is spelled out here too.
    drafts.settings.copy(model = "").effectiveModel().takeIf { it.isNotEmpty() && drafts.model.isBlank() }?.let {
        Spacer(Modifier.height(4.dp))
        Helper("Blank uses $it.")
    }
    if (provider == LlmProvider.GEMINI) {
        Spacer(Modifier.height(10.dp))
        GeminiModelPicker(apiKey = drafts.key.trim(), onPick = drafts::editModel)
    }
    if (provider == LlmProvider.CUSTOM) {
        Spacer(Modifier.height(12.dp))
        SecretField(
            value = drafts.customUrl,
            onValueChange = drafts::editCustomUrl,
            label = "Chat-completions endpoint URL",
            secret = false,
            keyboardType = KeyboardType.Uri,
        )
    }

    Spacer(Modifier.height(16.dp))
    val sees = settings.aiVision != AiVisionMode.OFF
    PopToggleRow(
        "Let the AI see the page",
        if (sees) "Sends the page image so I catch handwriting and wild lettering (about 150–300 KB a page, less with Data saver)."
        else "Sends only the text I read on your phone (a few KB). Best on very slow internet; fancy lettering may be missed.",
        sees,
        { sink.setAiVision(if (it) AiVisionMode.AUTO else AiVisionMode.OFF) },
    )

    Spacer(Modifier.height(16.dp))
    Text("Thinking time", style = MaterialTheme.typography.titleMedium, color = pop.ink)
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
            AiReasoning.FAST -> "Least thinking: the first lines land soonest. Fine for clean lettering."
            AiReasoning.BALANCED -> "A little thinking on the page image, the least on text. Lines stream in one by one."
            AiReasoning.THOROUGH ->
                "Full thinking: best at who's speaking and wild lettering, with a longer wait for the first line."
        }
    )

    Spacer(Modifier.height(16.dp))
    PopToggleRow(
        "Data saver",
        "Smaller page uploads. Tiny text may read a little less sharply.",
        settings.dataSaver,
        sink::setDataSaver,
    )
    if (provider == LlmProvider.GEMINI) {
        Spacer(Modifier.height(12.dp))
        PopToggleRow(
            "AI art redraw",
            "Cleaning always happens on your phone. This asks an image model to redraw detailed art under text " +
                "drawn on it: one extra image request per page that needs it, often refused on explicit art " +
                "(the local cleaning then stays).",
            settings.aiCleanup,
            sink::setAiCleanup,
        )
    }
    Spacer(Modifier.height(12.dp))
    PopToggleRow(
        "Diagnostics",
        "Outlines every balloon I find and keeps a status line up: ocr · balloons · regions · cards. " +
            "If a balloon stays untranslated, it shows which step lost it.",
        settings.diagnostics,
        sink::setDiagnostics,
    )

    Spacer(Modifier.height(16.dp))
    val testing = sayHi.phase == SayHi.Phase.Running
    StickerButton(
        if (testing) "Testing…" else "Say hi (test translation)",
        { if (!testing) sayHi.run(drafts.settings) },
        style = StickerStyle.Surface,
    )
    SayHiResult(sayHi.phase, onRetry = { sayHi.run(drafts.settings) })
}

/**
 * Fetches Google's live model list on demand and offers it as a menu — the
 * newest Flash first — so the picker shows models released long after this
 * build shipped. Typing in the Model field always stays available.
 */
@Composable
private fun GeminiModelPicker(apiKey: String, onPick: (String) -> Unit) {
    val pop = LocalPop.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<ModelCatalog.LiveModel>>(emptyList()) }
    Box {
        StickerButton(
            if (loading) "Asking Google…" else "Pick from Google's live list ▾",
            {
                if (loading) return@StickerButton
                if (apiKey.isBlank()) {
                    error = "Paste your API key first. The list comes from your account."
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
                        if (models.isEmpty()) error = "Google returned no usable models."
                    } catch (e: Exception) {
                        error = "Couldn't fetch models: " + (e.message ?: "network error")
                    } finally {
                        loading = false
                    }
                }
            },
            style = StickerStyle.Surface,
            height = 48.dp,
            fillWidth = false,
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
