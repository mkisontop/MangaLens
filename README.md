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
        ▼                               vertical-column ordering, furigana drop)
 overlays cleared                                │ bubbles
                                                 ▼
                                      Reading order (recursive X-Y cut over
                                       panel gutters, right-to-left for manga)
                                                 │
                                                 ▼
                                      Utterance linker (sentences split
                                       across balloons rejoined)
                                                 │
                                                 ▼
                                    Translation engine (+ LRU cache, fallback chain,
                                     glossary + cast + story context)
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
- **Balloons are found in the pixels, not inferred from OCR**: a speech
  balloon is an enclosed light region bounded by ink and holding lettering,
  and it is detected as one. Clustering OCR lines by proximity infers a
  balloon from its contents and inherits every OCR mistake — a wide balloon
  with generously spaced columns, or one with a furigana column wedged between
  two kanji columns, splits into fragments, and each fragment is then handed
  to the translator as if it were a whole utterance. A model given 「よ」 alone
  does not decline to answer; it invents a line that fits. Detecting the
  balloon itself fixes both halves of that: fragments inside one balloon are
  welded into the single line it holds, and a balloon whose vertical lettering
  OCR could not read *at all* still becomes a region for the vision model to
  read off the image.
- **Nothing is silently left untranslated**: a region the vision model skips
  used to render nothing, so a page came back with translated balloons
  interleaved with raw ones and no sign anything was missing. Whatever it
  passes over now falls through to the text engine — a weaker translation for
  those balloons, but a finished page.
- **Panel-aware reading order**: the page is split recursively on the
  whitespace gutters between panels — tiers first, then panels within a tier,
  right-to-left on a manga page and left-to-right in a webtoon. Order is not
  cosmetic: it is what the translator is told the page's reading order *is*,
  the sequence story context accumulates in, and the adjacency used to rejoin
  split sentences. Vertical lettering is the tell for right-to-left, so a
  Japanese webtoon still reads top-down. Scored against a corpus of standard
  page layouts (`ReadingOrderBenchmarkTest`), this reads **13/13** correctly
  where the previous top-to-bottom, left-to-right sort managed 6/13.

  One layout family is genuinely undecidable and is marked as such in the
  corpus: a full-height panel down one side, with a balloon near its top,
  produces balloon geometry identical to a two-panel tier above a single
  panel — and the two read in different orders. Only the panel borders
  distinguish them, and the grouper sees balloon boxes alone. Detecting panel
  borders from the frame is what would close it.
- **Split sentences are rejoined**: one line of dialogue broken over two or
  three balloons ("あいつが……" / "……来たのか") is detected from the dangling
  particle and translated as a single sentence, then divided back across the
  balloons. Japanese and Korean drop the subject *and* the verb mid-sentence,
  so a tail balloon read alone is genuinely ambiguous rather than merely
  flavourless. Detection is tuned against false positives — welding two
  characters' lines together invents a sentence that was never on the page,
  which is worse than translating a tail clause alone — and scores 10/10
  linked and 11/11 kept apart on the labelled corpus in
  `UtteranceAccuracyTest`. That corpus is what caught の and な being treated
  as connectives when in dialogue they are overwhelmingly sentence-final:
  「そうなの」 is a complete line, not the front half of one.
- **The page is marked before it is sent**: in AI Vision each detected region
  is outlined and numbered directly on the uploaded image, so the model reads
  "region 7" off the page instead of matching coordinates to positions — the
  thing vision models are least reliable at. Answers stop landing on the wrong
  balloon.
- **Speaker attribution and a persistent cast**: every line comes back
  attributed to a character, and each character's pronoun and speech register
  are remembered across pages and restarts. This is what resolves the subjects
  CJK omits — and it stops the pronoun coin-flip that leaves a character "he"
  on one page and "she" on the next.
- **Persistent glossary**: the AI registers every name/term it establishes
  (강태오 → "Kang Tae-oh") and reuses it across pages, chapters and restarts.
- **Tap-to-turn readers are noticed too**: while a translated page is on
  screen, frames are compared against *that page* rather than against the
  frame before them. Frame-to-frame differencing sees scrolling easily but is
  structurally blind to an animated page turn — each step of a cross-fade
  moves the screen only slightly, and since the reference is the previous
  frame, it follows the animation onto the new page without ever registering
  motion. Measured over a nine-frame turn, no step exceeds a quarter of the
  motion threshold while the accumulated change is twice the page-change
  threshold. Cards are masked out of the comparison, since they are captured
  along with the page and sit exactly where it changes.
- **One balloon, one card**: a balloon that already has a card never gets a
  second one, and a free-floating entry is only trusted where the page
  actually shows text. The model sometimes answers a region by id *and*
  repeats it as an unanchored entry — usually when a long line tempts it to
  continue in a second one — and the repeat carries its own drifting box that
  lands beside or below the balloon it belongs to.
- **Overlay feedback loop is impossible by design**: overlays are cleared
  before every capture, re-OCR only triggers after real screen motion, and the
  app's own floating button region is excluded from OCR.
- **Junk gates**: stray border pipes, furigana, one-character crumbs and
  romanized-gibberish translations are filtered — a bubble renders correctly or
  not at all.
- **SFX intelligence**: oversized katakana bursts are classified as sound
  effects and rendered as compact comic captions (WHAM, BA-DUMP) — or left as
  untouched art — never word-for-word translated. Japanese sound effects name
  states as often as noises, so シーン becomes "…SILENCE…" and ジー becomes
  "…STARE…" rather than an invented crash.
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
