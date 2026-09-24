# MangaLens 文A

[![build](https://github.com/mkisontop/mangalens/actions/workflows/build.yml/badge.svg)](https://github.com/mkisontop/mangalens/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/mkisontop/mangalens?label=release)](https://github.com/mkisontop/mangalens/releases/latest)
[![license](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

**Live on-screen translation for raw manhwa, manga and manhua on Android.**

Read raws in Brave (or any app). MangaLens watches your screen, finds the
lettering — speech balloons, narration, text drawn on the art, sound
effects — translates it to natural English, and letters it back in the way a
scanlation team would: balloons wiped and re-typeset, text on the art erased
and re-lettered in its own colours. Hands-free: scroll and it clears, stop
and the next page translates itself. With Gemini the first line appears
about 1.5–2 s after you stop.

**[⤓ Download the latest APK](https://github.com/mkisontop/mangalens/releases/latest/download/MangaLens.apk)**
· [website](https://mkisontop.github.io/MangaLens/)
· [all releases](https://github.com/mkisontop/mangalens/releases)
· [changelog](CHANGELOG.md)

Current release: **0.11.0**. Still on 0.9.1? Follow the
[one-time update instructions](#one-time-update-from-091) instead of using
the normal APK.

## How it feels

1. Tap **Start translating** → allow screen capture.
2. Switch to Brave and read your manhwa like normal.
3. Every time you stop scrolling, English appears **in** the page, line by
   line as it is translated — each balloon wiped clean and re-lettered in a
   comic face, narration and text on the art erased and re-lettered in their
   own colours, the way a scanlation typesets it.
4. Scroll on — the overlays clear instantly so the page underneath is never
   obscured mid-motion, and the next stop translates itself. Lettering you
   already read repaints straight away wherever it has scrolled to, and a
   page you scroll back to re-paints from cache: no re-translation, no extra
   cost.

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

## Translation

MangaLens translates with AI, and only with AI: there is no free machine
engine or offline engine, and no machine draft painted before the AI answers.
Each line appears once, in the AI's words.

| Provider | Speed | Setup | Notes |
|---|---|---|---|
| **Gemini** *(default, recommended)* | first line ~1.5–2 s after you stop | API key — **free tier** | Reads the page image itself the moment you stop scrolling, finds every piece of lettering (balloons, captions, text on the art, sound effects), and streams each line as it is translated — see *AI-first reading* below. Get a key at aistudio.google.com/apikey (the app links you there); a one-tap picker fetches Google's **live model list**, and the default, `gemini-flash-latest`, was chosen by measurement on hard pages. |
| **Claude, OpenAI, OpenRouter, any OpenAI-compatible endpoint** | depends on provider and model | API key (a custom endpoint may need only its URL) | The classic path: on-device OCR finds the regions and the AI translates them, streamed balloon by balloon (**AI Vision** sends the page where on-device OCR struggles). |

With every provider, rolling story context, a **persistent glossary** and a
cast list keep names, honorifics and running jokes consistent. Without a key
nothing is translated, and the status pill says what to add. When the AI
fails — no network, a rate limit, a rejected key — the page stays as it is and
the pill says why; nothing else translates it in the AI's place.

Privacy: in AI **text** mode only bubble text leaves the device; in AI
**Vision** mode the page image goes to the provider you chose — and nowhere
else. With Gemini, each page you stop on goes to Google as a 1280 px JPEG
(1024 px with Data saver). The optional **AI redraw** sends only crops around
lettering drawn on detailed art to Google's image model, and is off unless
you turn it on. Screen capture and OCR always run on-device. Each AI provider has its own API-key and
model setting; switching providers never reuses one provider's credential with
another. OpenRouter has a dedicated key field and an in-app link to
`openrouter.ai/settings/keys`. API keys live in Android's no-backup storage;
ordinary preferences can transfer to a new device, but credentials must be
entered again.

## How it works

### AI-first reading (Gemini)

```
Frame differ ──"screen went quiet" (~150 ms)──┬──▶ Gemini reads the page image
                                              │     (streamed; 2 racing requests,
                                              │      the slower one cancelled)
                                              │            │ items, one by one:
                                              │            │ box · kind · speaker · source · English
                                              ├──▶ Scroll memory: lettering translated at an
                                              │     earlier stop found again by its own pixels
                                              │     and repainted at once
                                              └──▶ On-device OCR + balloon/panel finder
                                                          │
                                                          ▼
                                  Resolver — for each item, what a letterer would do:
                                    balloon  → wiped through its own shape, text set to it
                                    text on the art → erased stroke by stroke, re-lettered
                                              in its own colours and outline
                                    small sound on plain ground → erased and re-lettered
                                    big sound drawn into the art → kept, with a small note
                                              on empty ground beside it or on the sound itself
                                                          │
                                                          ▼
                                  Overlay: each line fades in as it streams
```

- **Nothing waits for anything else.** The request goes out about 150 ms
  after the screen stops moving, before on-device analysis has finished, and
  the model finds the lettering itself — vertical Japanese, stylised
  sound effects and handwriting that OCR cannot read at all. Each line is
  painted the moment the model has written it, dialogue first in reading
  order. On hard test pages the first line lands about 1.1–2.3 s after the
  request; lettering seen at an earlier scroll stop repaints in about 0.3 s.
  Two identical requests race (the second goes out once the first has
  finished uploading, so it never slows it) and the slower is cancelled,
  which cuts Google's occasional multi-second stalls; after a rate limit
  only one request is sent.
- **Faithful by instruction, checked by experts.** The prompt carries the
  rules a panel of reviewers (Japanese manga, Korean and Chinese, lettering)
  set after critiquing live output: every line translated in full, nothing
  softened or skipped; speaker voice, honorifics and names kept consistent
  through the glossary and cast list; sound effects chosen by meaning.
- **Balloons are cleaned only when they are balloons.** A detection is
  wiped only if its interior holds nothing but the lettering the model
  found there; a face, a highlight or screentone that passed for a balloon
  is left alone and its lettering erased on its own. Two balloons drawn
  joined keep a line each, in their own lobes.
- **Text on the art is erased, not covered.** The eraser finds the strokes
  from the pixels — on paper, on screentone (re-toned from the measured dot
  lattice), on colour art by the lettering's own fill and outline — and
  paints what was behind them; the English goes back in the original's
  colours and outline. A long vertical column is lettered down the column,
  so the English stays on the erased strip.
- **Sound effects the way a scanlation handles them.** Only sounds that tell
  the reader something are translated. A small one on plain ground is erased
  and re-lettered. A big one drawn into the art is part of the drawing and
  stays; its English is a small note placed on empty ground beside it (a
  coarse map of where the page is drawn tells paper and flat tone from
  faces, hair and hands) or, where everything around it is drawn, on the
  sound itself — never on the picture. A sound repeated down a column is
  said twice at most, and noted once.
- **Scroll memory never confuses neighbours.** Remembered lettering is found
  again by its pixels, never by position: its best match along the scroll
  must clearly beat every other spot, and the ink there must match stroke
  for stroke at near full resolution. Two balloons side by side, a line one
  character different, or a new balloon scrolled into an old one's place are
  never mistaken for each other. A line read again keeps the words the
  reader already saw, unless the model now cuts that lettering differently.
- **A nudge reads only what it revealed.** The scroll since the last fully
  read frame is measured to the pixel from the frames themselves; when the
  page only moved a little, the model is sent just the newly revealed strip
  (with a margin for a balloon the last stop cut in half), and the rest of
  the screen repaints from memory. The new balloon is then the model's
  first answer instead of its last, the upload is a fraction of the page,
  and a nudge that revealed next to nothing sends no request at all. If
  memory has lost any line outside the strip, the whole screen is read.
- **Optional AI redraw.** Off by default. When on, lettering drawn on
  detailed art is sent as crops to an image model, whose redraw is used only
  inside each letter's mask and only where it agrees with the art just
  around it; after two refusals it rests for 15 minutes.

### The classic path (other AI providers, and Gemini in text-only mode)

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
                                    AI translation (+ LRU cache, glossary + cast
                                     + story context; replies stream and paint
                                     balloon by balloon)
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
- **Streamed AI rendering, no draft**: the AI reply is streamed and parsed
  as it arrives, so each balloon is painted the moment the model finishes
  writing it, in reading order — the page fills in balloon by balloon
  instead of all at once when the last one closes. Nothing is painted first
  and re-worded later: each line appears once, in the AI's words. While a
  pass runs, the floating button's busy ring shows it; the status pill
  speaks only for errors, a rejected key, your own taps and diagnostics.
  Requests are laid out stable-first (system
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
  the page comes back as though the model had said nothing.
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
  nor dark and slipped between the others.

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
  geometry, and the AI is told the language setting is a guess to be
  overridden by what the page actually says.
- **Nothing is silently left untranslated**: a region the vision model skips
  used to render nothing, so a page came back with translated balloons
  interleaved with raw ones and no sign anything was missing. Whatever it
  passes over that OCR could read now goes to the AI again as text — a
  weaker translation for those balloons, but a finished page.
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
  through every streamed line and the moments after each paint — so a
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
  translations that merely echo the source are filtered — a bubble renders
  correctly or not at all.
- **SFX intelligence**: oversized katakana bursts are classified as sound
  effects, which the AI renders as compact comic captions (WHAM, BA-DUMP) —
  or they stay untouched art — never word-for-word translated. Japanese
  sound effects name states as often as noises, and the model is told so:
  シーン is a silence and ジー a stare, not an invented crash.
- **Language auto-detect** races all three CJK recognizers and pins the winner
  after two consecutive wins, so steady-state pages pay for exactly one OCR pass.
- **Bubble grouping** clusters OCR lines with direction-aware padding
  (union-find), then reads vertical columns right-to-left like a human.
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

**Slow internet?** Lettering seen at an earlier stop repaints from memory at
once, and new lines appear one by one as the AI writes them — there is no
machine draft to fill in while you wait. Turn on **Data saver** to shrink
vision uploads, or set AI Vision to **Text only** for requests a few KB big.
With no connection at all nothing new is translated, and the status pill
says the AI couldn't read the page, and why.

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
