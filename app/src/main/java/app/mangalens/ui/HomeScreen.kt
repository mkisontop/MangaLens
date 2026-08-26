package app.mangalens.ui

import android.os.Build

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mangalens.capture.ScreenCaptureService
import app.mangalens.settings.AiVisionMode
import app.mangalens.settings.AppSettings
import app.mangalens.settings.CaptureMode
import app.mangalens.settings.EngineKind
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SettingsRepository
import app.mangalens.settings.SourceLang
import app.mangalens.translate.GoogleFreeEngine
import app.mangalens.translate.LlmEngine
import app.mangalens.translate.MlKitEngine
import app.mangalens.translate.ModelCatalog
import app.mangalens.update.UpdateChecker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    repo: SettingsRepository,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onGrantOverlay: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repo.flow.collectAsState(initial = AppSettings())
    val running by ScreenCaptureService.running.collectAsState()

    var overlayGranted by remember {
        mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
    LaunchedEffect(Unit) {
        while (true) {
            overlayGranted = android.provider.Settings.canDrawOverlays(context)
            delay(1000)
        }
    }

    var update by remember { mutableStateOf<UpdateChecker.Update?>(null) }
    LaunchedEffect(Unit) {
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        if (installed != null) {
            update = UpdateChecker.check(
                currentVersion = installed,
                sdkInt = Build.VERSION.SDK_INT,
                signingTrack = UpdateChecker.installedSigningTrack(context),
            )
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Header()
            Spacer(Modifier.height(18.dp))
            StatusCard(running, overlayGranted, onStart, onStop, onGrantOverlay)
            Spacer(Modifier.height(14.dp))
            update?.let {
                UpdateCard(it)
                Spacer(Modifier.height(14.dp))
            }
            EngineCard(settings, repo)
            Spacer(Modifier.height(14.dp))
            ReadingCard(settings, repo)
            Spacer(Modifier.height(14.dp))
            TipsCard()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("文A", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("MangaLens", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Live manhwa · manga · manhua translation over any app",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusCard(
    running: Boolean,
    overlayGranted: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onGrantOverlay: () -> Unit,
) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(
                            if (running) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.outline,
                            CircleShape
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (running) "Translating your screen" else "Not running",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(Modifier.height(10.dp))
            if (!overlayGranted) {
                Text(
                    "Step 1 · Allow MangaLens to draw over other apps",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onGrantOverlay) { Text("Grant overlay permission") }
                Spacer(Modifier.height(10.dp))
            }
            if (running) {
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
            } else {
                Button(
                    onClick = onStart,
                    enabled = overlayGranted,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (overlayGranted) "Start translating" else "Grant permission first") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Then open Brave and read. When you stop scrolling, bubbles are translated in place. " +
                    "Tap the floating 文A button to switch translation on or off; long-press it for the quick menu (translate now, peek, settings).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun EngineCard(settings: AppSettings, repo: SettingsRepository) {
    val scope = rememberCoroutineScope()
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var keyDraft by remember(settings.provider) { mutableStateOf(settings.apiKey) }
    var keyEdited by remember(settings.provider) { mutableStateOf(false) }
    var modelDraft by remember(settings.provider) { mutableStateOf(settings.model) }
    var modelEdited by remember(settings.provider) { mutableStateOf(false) }
    var customUrlDraft by remember { mutableStateOf(settings.customUrl) }
    var customUrlEdited by remember { mutableStateOf(false) }

    LaunchedEffect(settings.provider, settings.apiKey) {
        // collectAsState starts with defaults. Accept the first real DataStore
        // value, but never echo an older write over text being typed.
        if (!keyEdited) keyDraft = settings.apiKey
    }
    LaunchedEffect(settings.provider, settings.model) {
        if (!modelEdited) modelDraft = settings.model
    }
    LaunchedEffect(settings.customUrl) {
        if (!customUrlEdited) customUrlDraft = settings.customUrl
    }
    val draftSettings = settings.copy(
        apiKey = keyDraft.trim(),
        model = modelDraft.trim(),
        customUrl = customUrlDraft.trim(),
    )

    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Translation engine")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Free · Google", settings.engine == EngineKind.GOOGLE) {
                    scope.launch { repo.setEngine(EngineKind.GOOGLE) }
                }
                Chip("AI Pro ✨", settings.engine == EngineKind.LLM) {
                    scope.launch { repo.setEngine(EngineKind.LLM) }
                }
                Chip("Offline", settings.engine == EngineKind.MLKIT) {
                    scope.launch { repo.setEngine(EngineKind.MLKIT) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when (settings.engine) {
                    EngineKind.GOOGLE -> "Works instantly, no setup. Solid everyday quality."
                    EngineKind.LLM -> "Feels like an official release: the AI reads whole pages (even the raw image) with story memory, a name glossary, natural tone and honorifics. A fast draft appears instantly; the AI polish replaces it seconds later. Needs an API key — Gemini's is free."
                    EngineKind.MLKIT -> "100% offline after a one-time ~30 MB model download per language. Roughest quality of the three."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (settings.engine == EngineKind.LLM) {
                Spacer(Modifier.height(12.dp))
                ProviderPicker(settings, repo)
                Spacer(Modifier.height(10.dp))
                var showKey by remember(settings.provider) { mutableStateOf(false) }
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = {
                        keyDraft = it
                        keyEdited = true
                        val provider = settings.provider
                        scope.launch { repo.setApiKey(provider, it.trim()) }
                    },
                    label = { Text(apiKeyLabel(settings.provider)) },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        Text(
                            if (showKey) "hide" else "show",
                            modifier = Modifier
                                .clickable { showKey = !showKey }
                                .padding(end = 10.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = modelDraft,
                    onValueChange = {
                        modelDraft = it
                        modelEdited = true
                        val provider = settings.provider
                        scope.launch { repo.setModel(provider, it.trim()) }
                    },
                    label = { Text("Model (blank = ${draftSettings.copy(model = "").effectiveModel()})") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (settings.provider == LlmProvider.GEMINI) {
                    Spacer(Modifier.height(6.dp))
                    GeminiModelRow(apiKey = keyDraft.trim()) { picked ->
                        modelDraft = picked
                        modelEdited = true
                        val provider = settings.provider
                        scope.launch { repo.setModel(provider, picked) }
                    }
                }
                if (settings.provider == LlmProvider.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customUrlDraft,
                        onValueChange = {
                            customUrlDraft = it
                            customUrlEdited = true
                            scope.launch { repo.setCustomUrl(it.trim()) }
                        },
                        label = { Text("Chat-completions endpoint URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("AI Vision — let the AI read the raw page image", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("AI Vision (recommended)", settings.aiVision != AiVisionMode.OFF) {
                        scope.launch { repo.setAiVision(AiVisionMode.AUTO) }
                    }
                    Chip("Text only", settings.aiVision == AiVisionMode.OFF) {
                        scope.launch { repo.setAiVision(AiVisionMode.OFF) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (settings.aiVision != AiVisionMode.OFF)
                        "The AI reads the page image itself — catches handwriting, stylized lettering and anything OCR misses, in manhwa and manga alike (~150–300 KB per page, less with Data saver). Falls back to text-only, then Google, automatically."
                    else
                        "Only OCR'd text is sent (a few KB). Best for very slow internet; stylized lettering depends on on-device OCR.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Data saver — smaller page uploads", settings.dataSaver) {
                        scope.launch { repo.setDataSaver(!settings.dataSaver) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Diagnostics — show what was detected", settings.diagnostics) {
                        scope.launch { repo.setDiagnostics(!settings.diagnostics) }
                    }
                }
                Text(
                    "Outlines every balloon found and keeps a status line up: " +
                        "ocr (text lines read) · balloons (found in the page) · " +
                        "regions (sent to translate) · cards (painted). " +
                        "If a balloon is untranslated, this says which step lost it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                val uriHandler = LocalUriHandler.current
                val keyHelp = providerKeyHelp(settings.provider)
                Text(
                    keyHelp.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                keyHelp.url?.let { url ->
                    Text(
                        keyHelp.linkLabel,
                        modifier = Modifier
                            .clickable { uriHandler.openUri(url) }
                            .padding(vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = !testing,
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            testResult = try {
                                val sample = listOf("괜찮아. 내가 지켜줄게.")
                                val out = when (settings.engine) {
                                    EngineKind.LLM -> LlmEngine(draftSettings).translate(sample, SourceLang.KO)
                                    EngineKind.MLKIT -> MlKitEngine().translate(sample, SourceLang.KO)
                                    EngineKind.GOOGLE -> GoogleFreeEngine().translate(sample, SourceLang.KO)
                                }
                                "“괜찮아. 내가 지켜줄게.” → “" + out.first() + "”"
                            } catch (e: Exception) {
                                "⚠ " + (e.message ?: "failed")
                            } finally {
                                testing = false
                            }
                        }
                    }
                ) { Text(if (testing) "Testing…" else "Test translation") }
            }
            testResult?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ProviderPicker(settings: AppSettings, repo: SettingsRepository) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(
                "Provider: " + when (settings.provider) {
                    LlmProvider.ANTHROPIC -> "Anthropic Claude (recommended)"
                    LlmProvider.OPENAI -> "OpenAI"
                    LlmProvider.GEMINI -> "Google Gemini"
                    LlmProvider.OPENROUTER -> "OpenRouter"
                    LlmProvider.CUSTOM -> "Custom endpoint"
                }
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LlmProvider.entries.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (p) {
                                LlmProvider.ANTHROPIC -> "Anthropic Claude (recommended)"
                                LlmProvider.OPENAI -> "OpenAI"
                                LlmProvider.GEMINI -> "Google Gemini (free tier)"
                                LlmProvider.OPENROUTER -> "OpenRouter"
                                LlmProvider.CUSTOM -> "Custom OpenAI-compatible endpoint"
                            }
                        )
                    },
                    onClick = {
                        open = false
                        scope.launch { repo.setProvider(p) }
                    }
                )
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                display(v),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = v,
            onValueChange = { v = it },
            valueRange = range,
            onValueChangeFinished = { onCommit(v) }
        )
    }
}

@Composable
private fun ReadingCard(settings: AppSettings, repo: SettingsRepository) {
    val scope = rememberCoroutineScope()
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Reading")
            Spacer(Modifier.height(10.dp))
            Text("Source language", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Auto", settings.sourceLang == SourceLang.AUTO) {
                    scope.launch { repo.setSourceLang(SourceLang.AUTO) }
                }
                Chip("한국어", settings.sourceLang == SourceLang.KO) {
                    scope.launch { repo.setSourceLang(SourceLang.KO) }
                }
                Chip("日本語", settings.sourceLang == SourceLang.JA) {
                    scope.launch { repo.setSourceLang(SourceLang.JA) }
                }
                Chip("中文", settings.sourceLang == SourceLang.ZH) {
                    scope.launch { repo.setSourceLang(SourceLang.ZH) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Mode", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Auto-live (hands-free)", settings.mode == CaptureMode.AUTO) {
                    scope.launch { repo.setMode(CaptureMode.AUTO) }
                }
                Chip("Tap to translate", settings.mode == CaptureMode.MANUAL) {
                    scope.launch { repo.setMode(CaptureMode.MANUAL) }
                }
            }
            Spacer(Modifier.height(14.dp))
            LabeledSlider(
                "Reaction time",
                settings.stabilityMs.toFloat(),
                200f..900f,
                { "${it.toInt()} ms" },
            ) { scope.launch { repo.setStabilityMs(it.toInt()) } }
            LabeledSlider(
                "Text size",
                settings.textScale,
                0.8f..1.5f,
                { "${(it * 100).toInt()}%" },
            ) { scope.launch { repo.setTextScale(it) } }
            LabeledSlider(
                "Ignore top of screen (browser bar)",
                settings.ignoreTopPct,
                0f..0.15f,
                { "${(it * 100).toInt()}%" },
            ) { scope.launch { repo.setIgnoreTopPct(it) } }
        }
    }
}

/**
 * Quiet update banner for a sideloaded app: one anonymous check per app open,
 * a card only when a newer release exists, and a button that opens the exact
 * APK asset for this install's signing history. The browser still owns the
 * download and Android still asks the user before installing it.
 */
@Composable
private fun UpdateCard(update: UpdateChecker.Update) {
    val uriHandler = LocalUriHandler.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Update available", fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        update.requiresReinstall ->
                            "MangaLens ${update.version} is out, but this installation cannot join the official signing key in place. Record any API keys, download the APK, uninstall MangaLens, then install it. API keys will be cleared."
                        update.legacyBridge ->
                            "MangaLens ${update.version} is out. This one-time compatible APK keeps your data while moving 0.9.1 to the private release key."
                        else -> "MangaLens ${update.version} is out."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            Button(onClick = { uriHandler.openUri(update.url) }) {
                Text(if (update.requiresReinstall) "Download" else "Download APK")
            }
        }
    }
}

private data class ProviderKeyHelp(val message: String, val linkLabel: String = "", val url: String? = null)

private fun apiKeyLabel(provider: LlmProvider): String = when (provider) {
    LlmProvider.ANTHROPIC -> "Anthropic API key"
    LlmProvider.OPENAI -> "OpenAI API key"
    LlmProvider.GEMINI -> "Gemini API key"
    LlmProvider.OPENROUTER -> "OpenRouter API key"
    LlmProvider.CUSTOM -> "Bearer token (optional)"
}

private fun providerKeyHelp(provider: LlmProvider): ProviderKeyHelp = when (provider) {
    LlmProvider.ANTHROPIC -> ProviderKeyHelp(
        "This key is saved only for Anthropic.",
        "Create an Anthropic key →",
        "https://console.anthropic.com/settings/keys",
    )
    LlmProvider.OPENAI -> ProviderKeyHelp(
        "This key is saved only for OpenAI.",
        "Create an OpenAI key →",
        "https://platform.openai.com/api-keys",
    )
    LlmProvider.GEMINI -> ProviderKeyHelp(
        "Gemini has a free tier (no card needed). This key is saved only for Gemini.",
        "Create a Gemini key →",
        "https://aistudio.google.com/apikey",
    )
    LlmProvider.OPENROUTER -> ProviderKeyHelp(
        "Paste your OpenRouter key here. It stays separate from your Anthropic, OpenAI and Gemini keys.",
        "Create or choose an OpenRouter key →",
        "https://openrouter.ai/settings/keys",
    )
    LlmProvider.CUSTOM -> ProviderKeyHelp(
        "Optional bearer token for this custom endpoint. For safety, old shared keys are not migrated here; enter the token intended for this URL.",
    )
}

/**
 * Fetches Google's live model list on demand and offers it as a menu — the
 * newest Flash first — so the picker shows models released long after this
 * build shipped. Manual typing in the field above always stays available.
 */
@Composable
private fun GeminiModelRow(apiKey: String, onPick: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<ModelCatalog.LiveModel>>(emptyList()) }
    Box {
        OutlinedButton(
            enabled = !loading,
            onClick = {
                if (apiKey.isBlank()) {
                    error = "Paste your API key first — the list comes from your account."
                    return@OutlinedButton
                }
                error = null
                if (models.isNotEmpty()) {
                    open = true
                    return@OutlinedButton
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
            }
        ) { Text(if (loading) "Fetching live model list…" else "Choose from Google's live model list ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            models.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(m.label)
                            Text(
                                m.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onPick(m.id)
                    }
                )
            }
        }
    }
    error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun TipsCard() {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Good to know")
            Spacer(Modifier.height(8.dp))
            Text(
                "• Brave private tabs block screen capture (they render black). Use a normal tab.\n" +
                    "• Overlays never block touches — scroll right through them.\n" +
                    "• Scrolling instantly hides overlays; stopping re-translates. That's the live loop.\n" +
                    "• AI Pro shows a fast draft instantly, then the AI polish replaces it — slow internet never blocks reading.\n" +
                    "• Text mode sends only bubble text; AI Vision sends the page image — only ever to the provider you chose.\n" +
                    "• Names stay consistent: the AI keeps a glossary of characters and terms as you read.\n" +
                    "• Reading raws you love? Support the official release when it exists.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
    }
}
