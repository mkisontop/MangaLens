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
| **Free · Google** *(default)* | ★★★☆ | fast | none | Uses Google Translate's public web endpoint. |
| **AI Pro ✨** | ★★★★★ | ~1–3 s/page | API key | An LLM translates the whole page with rolling story context: natural dialogue, correct tone, honorifics, smart SFX. Claude (Anthropic) recommended; OpenAI, Gemini, OpenRouter and any OpenAI-compatible endpoint also work. Gemini currently has a free tier (aistudio.google.com). Falls back to Google automatically if the call fails. |
| **Offline** | ★★☆☆ | fast | one-time ~30 MB model per language | ML Kit on-device translation. Works with zero network. |

Only in AI Pro mode does any text leave your device — and it's just the bubble
*text*, never the screen image. Screen capture and OCR are 100% on-device.

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

- **Overlay feedback loop is impossible by design**: overlays are always cleared
  before a frame is captured for OCR, and re-OCR only triggers after real screen
  motion — so the app never translates its own English output.
- **Language auto-detect** races all three CJK recognizers and pins the winner
  after two consecutive wins, so steady-state pages pay for exactly one OCR pass.
- **Bubble grouping** clusters OCR lines by padded-box overlap (union-find), then
  reads vertical Japanese columns right-to-left like a human.
- **Patches match the page**: each patch samples the pixels around the bubble so
  white bubbles get white patches, tinted panels get tinted patches, and the text
  auto-shrinks to fit.
- **Cache**: every translated bubble is LRU-cached, so scrolling back or peeking
  never re-translates (or re-bills) anything.

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

**Which languages?** Korean, Japanese (incl. reasonable vertical text), Chinese
(simplified & traditional) → English.

## Respect the creators

MangaLens is a reading accessibility tool for content you already have access
to. When an official English release exists, buy it — translators and artists
eat too.

## License

[MIT](LICENSE)
