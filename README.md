# MangaLens 文A

**Live on-screen translation for raw manhwa, manga and manhua on Android.**

Read raws in Brave (or any app). MangaLens watches your screen, finds the speech
bubbles, OCRs the Korean / Japanese / Chinese text on-device, translates it to
natural English, and paints clean patches right over the bubbles — hands-free.
Scroll and they vanish; stop and the next page translates itself.

## How it feels

1. Tap **Start translating** → allow screen capture.
2. Switch to Brave and read your manhwa like normal.
3. Every time you stop scrolling (~⅓ s), English appears over the bubbles.
4. Scroll on — overlays clear instantly. That's the whole loop.

A floating **文A** button is always available: **tap** = translate right now,
**long-press** = quick menu (pause, peek at the original art, tap-to-translate
mode, settings, stop).

## Install

1. Grab the APK: from the repo's **Releases** page, from the newest **build**
   run under the Actions tab, or from `dist/MangaLens-debug.apk` if committed.
2. Open it on your tablet → allow installing from unknown sources.
3. Open MangaLens → grant "Display over other apps" → Start.

## Translation engines

| Engine | Quality | Speed | Setup | Notes |
|---|---|---|---|---|
| **Free · Google** *(default)* | ★★★☆ | fast | none | Whole page in one batched request for cross-line context; junk-gated so OCR noise is never rendered. |
| **AI Pro ✨** | ★★★★★ | instant draft, polish in ~2–5 s | API key | The scanlation-grade mode. A fast draft paints immediately, then the AI result replaces it in place — slow internet never blocks reading. **AI Vision** sends the raw page image so the model reads vertical Japanese and stylized lettering itself (Auto: only where on-device OCR struggles; Korean webtoons use tiny text-only requests). Rolling story context + a **persistent glossary** keep names, honorifics and running jokes consistent forever. Claude (Anthropic) recommended; OpenAI, Gemini, OpenRouter and any OpenAI-compatible endpoint work. **Gemini has a free tier** (aistudio.google.com/apikey — the app links you there). Falls back to Google automatically. |
| **Offline** | ★★☆☆ | fast | one-time ~30 MB model per language | ML Kit on-device translation. Works with zero network. |

Privacy: in AI **text** mode only bubble text leaves the device; in AI
**Vision** mode the page image goes to the provider you chose — and nowhere
else. The free and offline engines never send an image anywhere. Screen
capture and OCR always run on-device.

## How it works

```
MediaProjection (screen capture)
        │  frames
        ▼
Frame differ ──"user stopped scrolling"──▶ ML Kit OCR (KO/JA/ZH race, winner pinned)
        │                                        │ lines + boxes
   scroll detected                               ▼
        │                              Bubble grouper (union-find clustering,
        ▼                               vertical-column reading order)
 overlays cleared                                │ bubbles
                                                 ▼
                                    Translation engine (+ LRU cache, fallback chain)
                                                 │ English
                                                 ▼
                                    Overlay renderer (color-sampled patches,
                                     auto-fitting text, untouchable window)
```

Key details:

- **Progressive AI rendering**: in AI Pro the free draft paints in ~1 s and the
  AI polish swaps in when it lands — the reading loop never waits on a slow
  connection. The status pill shows ✓ for drafts and ✨ once polished.
- **AI Vision routing (Auto)**: pages routed by script — vertical Japanese and
  manhua go to the vision model as a compressed image (~150–300 KB, less with
  Data saver); horizontal Korean webtoons use text-only requests a few KB big.
- **Persistent glossary**: the AI registers every name/term it establishes
  (강태오 → "Kang Tae-oh") and reuses it across pages, chapters and restarts.
- **Overlay feedback loop is impossible by design**: overlays are cleared
  before every capture, re-OCR only triggers after real screen motion, and the
  app's own floating button region is excluded from OCR.
- **Junk gates**: stray border pipes, furigana, one-character crumbs and
  romanized-gibberish translations are filtered — a bubble renders correctly or
  not at all.
- **SFX intelligence**: oversized katakana bursts are classified as sound
  effects and rendered as compact comic captions (WHAM, BA-DUMP) — or left as
  untouched art — never word-for-word translated.
- **Language auto-detect** races all three CJK recognizers and pins the winner
  after two consecutive wins, so steady-state pages pay for exactly one OCR pass.
- **Bubble grouping** clusters OCR lines with direction-aware padding
  (union-find), then reads vertical columns right-to-left like a human.
- **Patches match the page**: each patch samples the pixels around the bubble so
  white bubbles get white patches, tinted panels get tinted patches, and the text
  auto-shrinks to fit.
- **Cache**: bubbles and whole vision pages are LRU-cached, so scrolling back
  or peeking never re-translates (or re-bills) anything.

## Building it yourself

```bash
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17+, Android SDK 35. The committed `signing/debug.keystore`
is intentional — it keeps sideloaded updates signature-compatible. It signs
nothing distributed through any store; don't reuse it for anything real.

## FAQ

**Overlays don't appear?** Check "Display over other apps" is granted, and that
you're not in a Brave *private* tab — private tabs set `FLAG_SECURE`, which
makes the captured screen black.

**The browser bar gets translated?** Raise the "Ignore top of screen" slider in
Reading settings.

**Battery?** Use "Tap to translate" mode — capture idles until you tap.

**Slow internet?** You still read at full speed: the free draft is instant and
the AI polish arrives whenever it arrives. Turn on **Data saver** to shrink
vision uploads, or set AI Vision to **Text only** for requests a few KB big.

**Which languages?** Korean, Japanese (incl. reasonable vertical text), Chinese
(simplified & traditional) → English.

## Respect the creators

MangaLens is a reading accessibility tool for content you already have access
to. When an official English release exists, buy it — translators and artists
eat too.

## License

[MIT](LICENSE)
