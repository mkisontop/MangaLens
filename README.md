# MangaLens 文A

[![build](https://github.com/mkisontop/mangalens/actions/workflows/build.yml/badge.svg)](https://github.com/mkisontop/mangalens/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/mkisontop/mangalens?label=release)](https://github.com/mkisontop/mangalens/releases/latest)
[![license](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

**Live on-screen translation for raw manhwa, manga and manhua on Android.**

Read raws in Brave (or any app). MangaLens watches your screen, finds the speech
bubbles, OCRs the Korean / Japanese / Chinese text on-device, translates it to
natural English, and paints clean patches right over the bubbles — hands-free.
Scroll and they vanish; stop and the next page translates itself.

**[⤓ Download the latest APK](https://github.com/mkisontop/mangalens/releases/latest/download/MangaLens.apk)**
· [website](https://mkisontop.github.io/MangaLens/)
· [all releases](https://github.com/mkisontop/mangalens/releases)
· [changelog](CHANGELOG.md)

Current release: **0.10.1**. Still on 0.9.1? Follow the
[one-time update instructions](#one-time-update-from-091) instead of using
the normal APK.

## How it feels

1. Tap **Start translating** → allow screen capture.
2. Switch to Brave and read your manhwa like normal.
3. Every time you stop scrolling (~⅓ s), English appears **in** the bubbles —
   the balloon is wiped clean and re-lettered in a comic face, the way a
   scanlation typesets it.
4. Scroll on — the overlays clear instantly so the page underneath is never
   obscured mid-motion, and the next stop translates itself. A balloon you
   scroll back to re-paints from cache: no re-translation, no extra cost.

A floating **文A** toggle is always available: **tap** = translation on/off
(with a busy ring while a pass runs), **long-press** = quick menu (translate
now, pause, peek at the original art, tap-to-translate mode, settings, stop).

## Install

1. Download **[MangaLens.apk](https://github.com/mkisontop/mangalens/releases/latest/download/MangaLens.apk)**
   — that link always serves the newest release. Every release ships a
   `checksums.txt` if you want to verify the download.
2. Open it on your phone or tablet → allow installing from unknown sources
   (Android's standard prompt for apps outside the Play Store).
3. Open MangaLens → grant "Display over other apps" → Start.

Updating is automatic-ish: the app makes one anonymous check against this
repository's latest release when you open it, and shows a small banner when a
newer version exists. Nothing downloads without your tap.

### One-time update from 0.9.1

The published 0.9.1 APK was accidentally signed with the project's public
debug key; 0.9.2 and normal releases use the private release key. Android
correctly refuses to replace an installed app with one carrying an unrelated
signature.

- **Android 9 or newer, with 0.9.1 installed:** install
  **[MangaLens-legacy-update.apk](https://github.com/mkisontop/mangalens/releases/latest/download/MangaLens-legacy-update.apk)**
  once. It carries Android's signed debug→release key lineage, updates in
  place without clearing MangaLens data, and moves the installation onto the
  private release key. Use the normal `MangaLens.apk` for every update after
  that.
- **Already on 0.9.2 or newer:** use the normal `MangaLens.apk`; do not use the
  legacy bridge.
- **Android 8 or 8.1:** key rotation is unavailable. Record any API key or
  settings you need, uninstall MangaLens, then install the normal
  `MangaLens.apk`. Do not use the legacy bridge: Android 8 may accept its old
  signature, but cannot move the installation onto the private key. This one
  reinstall is required to join the private-key update line.

## Translation engines

| Engine | Quality | Speed | Setup | Notes |
|---|---|---|---|---|
| **Free · Google** *(default)* | ★★★☆ | fast | none | Whole page in one batched request for cross-line context; junk-gated so OCR noise is never rendered. |
| **AI Pro ✨** | ★★★★★ | instant draft, polish in ~2–5 s | API key | The scanlation-grade mode. A fast draft paints immediately, then the AI result replaces it in place — slow internet never blocks reading. **AI Vision** sends the raw page image so the model reads vertical Japanese and stylized lettering itself (Auto: only where on-device OCR struggles; Korean webtoons use tiny text-only requests). Rolling story context + a **persistent glossary** keep names, honorifics and running jokes consistent forever. Claude (Anthropic) recommended; OpenAI, Gemini, OpenRouter and any OpenAI-compatible endpoint work. **Gemini has a free tier** (aistudio.google.com/apikey — the app links you there), and a one-tap picker fetches Google's **live model list** so the newest Flash models are always offered, no app update needed. Falls back to Google automatically. |
| **Offline** | ★★☆☆ | fast | one-time ~30 MB model per language | ML Kit on-device translation. Works with zero network. |

Privacy: in AI **text** mode only bubble text leaves the device; in AI
**Vision** mode the page image goes to the provider you chose — and nowhere
else. The free and offline engines never send an image anywhere. Screen
capture and OCR always run on-device. Each AI provider has its own API-key and
model setting; switching providers never reuses one provider's credential with
another. OpenRouter has a dedicated key field and an in-app link to
`openrouter.ai/settings/keys`. API keys live in Android's no-backup storage;
ordinary preferences can transfer to a new device, but credentials must be
entered again.

## How it works

```
MediaProjection (screen capture)
        │  frames
        ▼
Frame differ ──"screen went quiet"──▶ Page analysis, started ~150 ms after the
        │                              last motion and thrown away if it resumes:
   scroll detected                       ML Kit OCR (KO/JA/ZH race, winner pinned)
        │                             ∥ Balloon + panel finder (page pixels)
        ▼                                then a 2x re-read of any balloon OCR read nothing in
 overlays cleared                                │ lines + balloons + panels
                                                 ▼
                                      Bubble grouper (union-find clustering bounded
                                       by balloons, vertical-column ordering, furigana drop)
                                                 │ bubbles
                                                 ▼
                                      Reading order (panel by panel where the page
                                       shows a grid; recursive X-Y cut otherwise)
                                                 │
                                                 ▼
                                      Utterance linker (sentences split
                                       across balloons rejoined)
                                                 │
                     "reader has stopped" ──▶    ▼
                                    Translation engine (+ LRU cache, fallback chain,
                                     glossary + cast + story context; AI replies
                                     stream and paint balloon by balloon)
                                                 │ English
                                                 ▼
                                    Overlay renderer (balloons wiped through their
                                     own mask, text set to the balloon's shape)
```

Key details:

- **The page is read before the reader has provably stopped**: OCR and
  balloon detection are the slow half of a pass and need nothing but the
  frame, so they start about 150 ms after the last motion, while the loop is
  still waiting out the stability window that keeps a brief pause from
  flashing cards. Any motion in between throws the reading away; otherwise,
  by the time the window closes the page is usually already read and only
  translation remains. OCR and balloon detection run side by side rather
  than one after the other, and a balloon OCR read nothing in is cropped
  from the full-resolution frame, enlarged, and read again on its own —
  ML Kit misses small and stylized lettering it reads fine at twice the
  size.
- **Progressive AI rendering**: in AI Pro the free draft paints in ~1 s and the
  AI polish swaps in when it lands — the reading loop never waits on a slow
  connection. The status pill shows ✓ for drafts and ✨ once polished. The AI
  reply is streamed and parsed as it arrives, so each balloon is painted the
  moment the model finishes writing it, in reading order, over whatever the
  draft still covers — the page fills in balloon by balloon instead of all at
  once when the last one closes. Requests are laid out stable-first (system
  prompt, then glossary and cast, then the page), so providers that cache a
  request prefix reuse the series memory page after page; on the Anthropic
  API those blocks carry explicit cache breakpoints.
- **A stronger model is allowed to be stronger, without paying for it in
  waiting**: every current model reasons before it writes, and that
  reasoning is both where a better model earns its keep and the whole of the
  wait before the first balloon streams in. The **AI reasoning** setting
  (fast / balanced / thorough) is translated into each provider's own
  control — Claude's effort level, Gemini's thinking level, OpenAI's
  reasoning effort, OpenRouter's unified reasoning parameter — and only for
  the models that take one; balanced spends a little on the page image and
  the least on text. Requests are also shaped per model where it matters:
  Gemini 3 keeps its default temperature (Google warns that lowering it
  causes loops), OpenAI reasoning models get the token field they accept,
  and the output cap leaves room for the thinking that current APIs count
  against it — a cap the thinking exhausts cuts the JSON off mid-page, and
  the page falls back to the draft as though the model had said nothing.
  The regions on-device OCR could not read go up a second time as enlarged
  close-ups cut from the full-resolution frame, each badged with its
  region id: on a tablet capture the page image reduces balloon lettering
  to glyphs a dozen pixels tall, and a model that could read the stylised
  or vertical text at full size was reduced to guessing at it.
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

  The page is analysed on a coarse grid, but every cell of it is summarised
  three ways from the full-resolution pixels — mean, darkest and lightest
  luminance — rather than by averaging alone. Averaging is what made the
  detector blind on tablets: at a quarter scale a two-pixel outline averages
  to mid-grey, reads as paper, and the balloon's interior leaks into the
  page. The darkest pixel in a cell survives any downscale, so a hairline
  outline is still a wall the flood cannot cross. Fill is judged on the
  interior with its lettering holes filled back in, so a balloon packed with
  text is as blobby as an empty one; and a region whose interior holds flat
  mid-tones — shading, colour, the texture of drawn art — is a panel with a
  figure in it, not a balloon, however well it satisfies the enclosure test.

  Burst (shout) balloons get sealed passes: their border is a ring of
  radiating ticks rather than a drawn curve, the flood leaks out through
  the gaps, and the plain pass can never enclose them. Thickened ink seals
  the gaps — at two radii, for the tick spacing of phone and of tablet
  captures — and the shouted line becomes an ordinary region instead of a
  balloon the app pretends not to see. A polarity-flipped pass finds black
  narration and flashback boxes — enclosed dark regions carrying light
  lettering — whose cards then render light-on-dark to match. A tinted pass
  relaxes the interior threshold for pastel balloons — the pink and
  lavender fills manhwa colorists reach for — which read as neither light
  nor dark and slipped between the others. A big balloon holding a short
  line — 「え？」 across half a panel — has far less lettering than the
  ordinary floor asks for; the plain pass admits it on a lower floor when
  the little lettering there is sits where lettering sits, centred in the
  shape. `BalloonTaxonomyTest` scores the detector on thirty balloon shapes
  drawn at phone resolution — rounded rectangles, caption boxes flush in a
  panel corner, thought clouds, dashed whisper balloons, double outlines,
  zigzag shouts, occluded pairs, balloons on black and on busy colour art,
  hairline outlines, tails, tablet captures — and all of them are found.

  Two balloons drawn joined — one character's consecutive lines, or two
  speakers' balloons touching — flood as one shape. The shape is eroded
  until it falls into separate cores, each core grows back over its own
  share, and each balloon keeps its own region, its own text and its own
  card; a single balloon with a waist or a tail erodes to a single core and
  stays whole. A balloon the screen edge cuts through — every scroll stop on
  a webtoon has one — is found and cleaned too, under strict gates (one
  edge, a modest share of the screen, lettering OCR actually read inside it)
  so the visible part of a panel never passes for one.
- **Balloons are cleaned, not covered**: every detection carries its interior
  mask — the actual flooded shape, tails and curves included — and the card
  paints an opaque fill through it, sampled from the balloon's own paper, with
  the English typeset over it in Comic Neue. The original lettering is gone,
  not peeking around a floating patch. A gradient or textured balloon — the
  coloured fills manhwa uses — is not patched with a flat average: its own
  paper is continued under the lettering, cell by cell through the mask, so
  the gradient runs through unbroken (`ShapedTypesetTest` previews).
- **Text is set to the balloon's shape, not its box**: the mask is measured
  row by row — how wide the interior is at every row, about the body's own
  centre, with a tail off to one side ignored — and the block is fitted into
  that: at each type size a few line counts and vertical positions are
  tried, every line capped by the room the balloon has at the rows it would
  sit on, and the first size that fits wins with the placement that fills the
  shape best and sits on the body. A round balloon gets the taper a letterer
  gives it (short first and last lines, widest in the middle) because that is
  its shape; a tall thin one gets short lines all the way down; a tailed one
  keeps its text out of the tail. Type shrinks only when the words genuinely
  do not fit the shape.
- **Lettering on open art is retouched, not boxed**: a monologue set in
  columns straight onto a starry sky, a caption across a landscape, a shout
  drawn over the top of a page — none of it has a balloon interior to clean,
  and the old answer was a card floated over the art, which hid the panel
  and left the original lettering in view beside it. Each such region now
  carries the OCR boxes of its lines, and every line is wiped back into the
  art around it: the fill runs the colour just outside one edge across to
  the colour just outside the other, row by row, from median samples so a
  star or a neighbouring stroke cannot streak across it, and the art between
  two columns is never touched. The English is then set over the spot the
  original occupied — where the artist left room — in stroked lettering
  (light on dark art, dark on light, outlined in the opposite tone) in
  balanced lines with no one-word widow, sized from the original glyphs so
  a shout stays a shout and an aside stays small. `OnArtPageRenderTest`
  draws the page that motivated this — balloons and column monologues on a
  night sky — and checks that no original glyph survives, that each block's
  English sits on the block it replaces, and that the sky between columns is
  pixel-identical to the page.
- **Long words are hyphenated before the type shrinks**: one word wider than
  a tall thin balloon used to force every line down to the smallest type.
  Below a modest reduction the offending word is now broken with a hyphen at
  a morpheme or syllable boundary (UNBELIEV-ABLE, CONGRATU-LATIONS,
  OVER-LOOKED), with at least three letters either side, and whichever
  reading fits at the larger size wins.
- **The translator is told how much room each balloon has**: the artist sized
  the balloon for the source, and English needs two and a half to three and a
  half characters per glyph to say the same thing. Every region goes up with a
  `fit` — the English characters that sit in it at full size, derived from the
  source text per script — and the model is asked to write to it the way a
  translator writes for a letterer: tighten, cut filler, prefer the short
  word, and go over only when meaning would be lost. The vision prompt also
  reads the balloon's shape for its voice: cloud for thought, rectangle for
  narration, burst for a shout, handwriting for an aside.
- **Motion clears, stillness translates**: the moment real motion is seen the
  overlays vanish, so a translation is never left hovering over content it no
  longer matches — a stale card painted confidently in the wrong place reads
  as true, and no card at all is strictly better. When the screen settles the
  loop re-detects and re-paints from scratch; anything seen before comes
  straight out of the cache, so scrolling back and forth costs nothing and
  never re-bills.
- **A drifting box is never trusted over the page**: an answer the vision
  model returns without a region id carries its own geometry, which drifts —
  far enough to land a shout balloon's line over the chapter title art. Its
  position is recovered from the page instead: the entry's source text is
  matched back to the OCR lines (case- and accent-blind, clustered so a word
  repeated elsewhere cannot stretch the anchor), then leftover OCR regions,
  and only then the model's box — and only where a detected balloon or region
  backs it. No support, no card: a missing translation is recoverable, one
  painted in the wrong place is read as true.
- **Raws that were already translated once still work**: aggregator sites
  routinely serve Spanish or English uploads under a "raw" label. Those lines
  carry no CJK, and used to be discarded at the OCR layer — leaving nothing
  to anchor to. Latin-script lines are now kept as regions with real
  geometry, Google is asked to auto-detect the source when a payload has no
  CJK, and the AI engines are told the language setting is a guess to be
  overridden by what the page actually says.
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

  One layout family is undecidable from balloon boxes alone: a full-height
  panel down one side, with a balloon near its top, produces balloon
  geometry identical to a two-panel tier above a single panel — and the two
  read in different orders. Only the panel borders distinguish them, so the
  panel grid is now read off the page itself: a run of rows or columns that
  holds nothing but paper (or nothing but black) across a region is a
  gutter, cutting on gutters recursively yields the panels, and a leaf only
  counts as a panel when it has a drawn border on all four sides — so
  balloons floating on a blank webtoon strip never form a grid. Where a
  grid exists the page is read panel by panel: balloons are assigned to the
  panel holding them, the panels are ordered by the same cut, and each
  panel's balloons are ordered within it. Both layouts of that family are
  drawn as pages in `PageLayoutTest` and read correctly.
- **Split sentences are rejoined**: one line of dialogue broken over two or
  three balloons ("あいつが……" / "……来たのか") is detected from the dangling
  particle and translated as a single sentence, then divided back across the
  balloons. Japanese and Korean drop the subject *and* the verb mid-sentence,
  so a tail balloon read alone is genuinely ambiguous rather than merely
  flavourless. Detection is tuned against false positives — welding two
  characters' lines together invents a sentence that was never on the page,
  which is worse than translating a tail clause alone — and scores 14/14
  linked and 25/25 kept apart on the labelled corpus in
  `UtteranceAccuracyTest`. That corpus is what caught の and な being treated
  as connectives when in dialogue they are overwhelmingly sentence-final:
  「そうなの」 is a complete line, not the front half of one. It has since
  caught the same pattern in the other tables — 的 and 了 close Chinese
  lines that carry no final punctuation, 야 and 라 close Korean ones — and
  three constructions that only look like chains: a bare particle followed
  by a two-character reply (「大丈夫だから」「うん」 is an exchange), a short
  te-form request (「待って」 is complete), and a Korean quotative ending
  (「알았다고」 is "I said I get it").
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
- **One series never contaminates the next**: the glossary, the cast and the
  story context are all scoped per work. Pooled, they invert their own
  purpose — 先生 fixed as "Doctor" by a medical series is then obeyed exactly
  in a school one, a pronoun deliberately held steady for one "Yuu" fixes an
  unrelated character of the same name, and the last thirty lines of one story
  arrive as context for the first page of another, which is precisely the
  input that decides who an elided subject refers to.

  A screen-capture app cannot ask what is being read, so a work is identified
  by its own content: once a session establishes a couple of proper nouns,
  those name it, and if a stored work shares them it is resumed with
  everything it had. A work ends on a long enough reading gap or a run of
  pages with no dialogue — leaving a series always crosses an index page, a
  cover or a pause. **New series** in the long-press menu forces it, for going
  straight from one work to the next with neither.
- **Tap-to-turn readers are noticed too**: while a translated page is on
  screen, frames are compared against *that page* rather than against the
  frame before them. Frame-to-frame differencing sees scrolling easily but is
  structurally blind to an animated page turn — each step of a cross-fade
  moves the screen only slightly, and since the reference is the previous
  frame, it follows the animation onto the new page without ever registering
  motion. Measured over a nine-frame turn, no step exceeds a quarter of the
  motion threshold while the accumulated change is twice the page-change
  threshold. Cards are masked out of the comparison, since they are captured
  along with the page and sit exactly where it changes. The reference is
  taken from the very bitmap that was translated — never from a later
  "settled" frame that a fast fling may already have moved. The comparison
  runs from the moment the page is grabbed until its cards come down —
  through the streamed polish and the moments after each paint — so a
  reader who turns the page mid-stream is noticed at once rather than when
  the stream ends. And the frame a tap produces is never dropped: a capture
  delivers a frame only when the screen changes, a tap changes it exactly
  once, and the loop's rate limit (about twelve frames a second) holds a
  frame that arrives too soon and looks at it when the interval is up,
  where it used to skip it — which is how a swap landing within 80 ms of
  the tap's own ripple went unseen until the next tap. The floating button,
  its busy ring and the status pill are masked out of every comparison
  along with the cards, and cells a card or pill has just left stay masked
  until the screen has caught up.
- **Slow scrolls can't smuggle a new balloon under an old card**: a gentle
  manhwa scroll is invisible to both detectors above. Consecutive frames
  barely differ when mostly-white strip slides over mostly-white strip, and
  the balloon gliding in beneath a still-displayed card changes only cells
  the cumulative comparison must mask out — which is exactly how a card ends
  up showing the previous balloon's line over a new balloon. Scrolled content
  is still self-similar under translation, though, so the loop also aligns
  row brightness profiles of the unmasked cells — between frames a few
  hundred milliseconds apart, and against the translated page itself. Two
  thumbnail rows of vertical drift count as motion and clear the cards,
  while still pages, page swaps and brightness changes align nowhere and
  stay put (`SlowScrollDetectionTest`).
- **One balloon, one card**: a balloon that already has a card never gets a
  second one, and a free-floating entry is only trusted where the page
  actually shows text. The model sometimes answers a region by id *and*
  repeats it as an unanchored entry — usually when a long line tempts it to
  continue in a second one — and the repeat carries its own drifting box that
  lands beside or below the balloon it belongs to.
- **Overlay feedback loop is impossible by design**: overlays are cleared
  before every capture, re-OCR only triggers after real screen motion, a
  frame is never read ahead while our own cards are on it, and the app's own
  floating button region is excluded from OCR.
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
  (union-find), then reads vertical columns right-to-left like a human. When
  the recognizer hands back one square box per glyph — as it does on large
  vertical lettering — no box calls itself vertical, so the arrangement
  decides: glyphs stacked into fewer columns than rows are a column, and two
  such columns read right-to-left instead of interleaved.
- **Sound effects are matched script-blind**: ぎゅ and ギュ are one effect,
  and OCR returns a long-vowel bar as 一 as often as ー, so lookups fold
  hiragana onto katakana and the lookalikes onto the bar first. The
  dictionary covers a few hundred Japanese, Korean and Chinese effects,
  traditional forms included.
- **Patches match the page**: each patch samples the pixels around the bubble so
  white bubbles get white patches, tinted panels get tinted patches, and the text
  auto-shrinks to fit.
- **Cache**: bubbles and whole vision pages are LRU-cached, so scrolling back
  or peeking never re-translates (or re-bills) anything. A cached page is
  identified by what is on it — each region's OCR text, or a fingerprint of
  the balloon's own pixels where OCR could read nothing, as stylized manhwa
  lettering routinely ensures — never by position. A replay realigns its
  unanchored entries to where the balloons sit now and refuses entirely when
  the stored layout no longer matches the frame (`PageKeyTest`,
  `ReplayGeometryTest`). Earlier builds keyed vision pages on OCR text alone,
  which collapsed to a single key for every OCR-blind scroll stop — so the
  previous stop's lines were stamped, instantly and confidently, onto
  whichever balloons had scrolled into those screen positions.

## Building it yourself

```bash
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17+, Android SDK 35. The committed `signing/debug.keystore`
is intentional — it keeps *debug* sideloads signature-compatible between
builds. It signs nothing official; don't reuse it for anything real.

Official releases are built by [the release workflow](.github/workflows/release.yml)
when a `v*` tag is pushed, and signed with a private key that lives only in
the repository's Actions secrets (`RELEASE_KEYSTORE_B64`,
`RELEASE_KEYSTORE_PASSWORD`). The workflow refuses to publish if the secrets,
keystore password, key alias, or pinned release certificate do not match.

A local `./gradlew :app:assembleRelease` deliberately creates
`app-release-unsigned.apk`. Use `assembleDebug` for a directly installable
development build; official APKs can only be signed in CI. The separately
named `MangaLens-legacy-update.apk` is a one-time Android 9+ migration artifact,
not the normal download.

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
(simplified & traditional) → English. Raws that were already translated once
(Spanish and English uploads are common on aggregator sites) are handled too —
the pipeline reads whatever is actually on the page.

## Respect the creators

MangaLens is a reading accessibility tool for content you already have access
to. When an official English release exists, buy it — translators and artists
eat too.

## License

[MIT](LICENSE). The bundled Comic Neue fonts are under the
[SIL Open Font License](FONTS-LICENSE-OFL.txt).
