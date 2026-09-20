# Changelog

## 0.11.0

The hard-page release: lettering drawn straight onto the art is retouched
the way a scanlator retouches it, the translator is told how much room each
balloon has, and the detector, grouper and sentence linker were scored
against corpora of the balloon shapes and dialogue constructions that
defeated them.

**Download:** `MangaLens.apk` below updates 0.9.2 and newer in place. Still on
0.9.1? Use `MangaLens-legacy-update.apk` once (Android 9+), as described in
the README.

- **Lettering on open art is retouched, not boxed.** A monologue set in
  columns on a night sky, a caption on a landscape or a shout over the top
  of a page used to get a card floated over the art — hiding the panel and
  leaving the original in view beside it. Each line is now wiped back into
  the art around it (the colour outside one edge run across to the colour
  outside the other, from median samples, so gradients pass through and a
  star cannot streak) and the English is set over the spot in stroked,
  balanced lettering sized from the original glyphs. The art between two
  columns is never touched.
- **Long words are hyphenated before the type shrinks.** UNBELIEV-ABLE at a
  readable size instead of a whole balloon at the smallest type.
- **The translator writes to the balloon.** Every region carries a `fit`
  budget — the English characters that sit in it at full size — and the
  model is asked to tighten to it rather than pad; the vision prompt also
  reads the balloon's shape for its voice (thought cloud, narration box,
  burst, handwritten aside).
- **Short lines in big balloons are found.** 「え？」 across half a panel was
  under the lettering floor; it is admitted when its lettering is centred.
- **Per-glyph OCR boxes read as columns.** Large vertical lettering comes
  back one square box per glyph; the arrangement now decides direction, so
  two columns read right-to-left instead of interleaved.
- **Neighbouring blocks on the art stay separate.** Two monologues set side
  by side a column apart used to weld into one paragraph; a group outside
  every balloon is now cut at a gap far wider than its other gaps.
- **Split sentences: fewer false welds.** 的/了 and 야/라 leave the
  connective tables; a bare particle followed by a two-character reply, a
  short te-form request and a Korean quotative ending no longer link. The
  labelled corpus stands at 14/14 linked and 25/25 kept apart.
- **Sound effects match script-blind.** Hiragana, katakana and OCR's 一 for
  ー all reach the same entry; a few hundred entries across Japanese, Korean
  and Chinese, traditional forms included.
- **Corpora.** `BalloonTaxonomyTest` scores thirty balloon shapes at phone
  resolution; `OnArtPageRenderTest` renders the night-sky page end to end and
  checks the pixels.

## 0.10.1

Tap-to-turn readers are noticed on the first tap.

**Download:** `MangaLens.apk` below updates 0.9.2 and newer in place. Still on
0.9.1? Use `MangaLens-legacy-update.apk` once (Android 9+), as described in
the README.

- **The page turn's one frame is never dropped.** A screen capture delivers
  a frame only when the screen changes, and a reader that turns the page on
  a tap changes it exactly once: one frame with the new page on it, then
  stillness. The capture loop looks at about twelve frames a second and
  used to drop the rest outright, so when that one frame landed within
  80 ms of the tap's own ripple it was dropped too, and with nothing after
  it the swap went unseen — the previous page's translation sat on the new
  page until the next tap. A frame that arrives too soon is now held and
  looked at when the interval is up, so the last frame of any burst is
  always seen.
- **A turn during the polish is caught at once.** The comparison against
  the translated page now runs from the moment the page is grabbed until
  its cards come down — while the model is still streaming balloons, and
  through the moments after each paint — instead of only once the pass has
  finished and settled. Turning the page mid-stream used to go unnoticed
  until the stream ended.
- **The button and the pill are masked out.** The floating button, its busy
  ring and the status pill are captured along with the page and were
  compared as if they were part of it; on a page with dense cards the pill
  appearing after a pass could pass for a page change and start the pass
  over. Their footprint is now excluded from every comparison, tracked as
  the pill comes and goes and the button is dragged, and the cells a card
  or pill has just left stay excluded until the screen has caught up.
- **Read-ahead survives the pill.** A frame read ahead of the stability
  window is checked against the live screen before it is used; that check
  now ignores the controls too, and also counts how many cells changed, so
  a swap between two mostly-white pages can never slip through as "still
  the same frame".

## 0.10.0

The speed release: the page is read while the loop is still waiting for the
reader to stop, the AI polish arrives balloon by balloon, and the balloons
themselves are found and cleaned on the pages that used to defeat it.

**Download:** `MangaLens.apk` below updates 0.9.2 and newer in place. Still on
0.9.1? Use `MangaLens-legacy-update.apk` once (Android 9+), as described in
the README.

- **Read ahead of the stop.** OCR and balloon detection start about 150 ms
  after the last motion — while the stability window is still running — and
  are thrown away if scrolling resumes. When the window closes, the page is
  usually already read and only translation remains. The two also run side
  by side instead of one after the other.
- **Streamed polish.** AI replies are parsed as they stream, and each balloon
  is painted the moment the model finishes writing it, over the draft the
  reader is already reading. Requests are laid out stable-first, with cache
  breakpoints on the Anthropic API, so the series memory costs next to
  nothing page after page.
- **AI reasoning setting.** Fast, balanced or thorough, translated into each
  provider's own thinking control for the models that take one: Claude
  effort, Gemini thinking level, OpenAI reasoning effort, OpenRouter's
  reasoning parameter. Balanced thinks a little on the page image and the
  least on text.
- **Requests shaped per model.** Gemini 3 keeps its default temperature,
  OpenAI reasoning models get `max_completion_tokens` and no temperature
  (they rejected the old request outright), and the output cap now leaves
  room for thinking so a thorough model can never truncate its own page.
- **Close-ups for the model.** Regions OCR could not read are also sent as
  enlarged crops from the full-resolution frame, badged with their region
  id, so a strong vision model gets legible lettering where it matters.
- **Balloons on tablets.** Detection now summarises every grid cell by its
  darkest and lightest pixel as well as its mean, so a hairline outline on a
  high-resolution capture is still a wall — previously it averaged to grey
  and the balloon leaked into the page and was never found.
- **Joined balloons come apart.** Two balloons drawn touching are eroded into
  their separate cores and each grows back over its own share; each keeps
  its own text and card. A balloon with a waist or a tail stays one balloon.
- **Balloons at the screen edge.** A balloon the frame cuts through is found
  and cleaned rather than rendered as a floating card, under strict gates.
- **Art panels are not balloons.** A white panel with a shaded figure in it
  passes every enclosure test a balloon does; the flat mid-tones of drawn art
  now give it away.
- **Panels read off the page.** The panel grid is detected from the gutters,
  and pages with a grid are read panel by panel. The one layout balloon
  geometry alone cannot decide — a full-height side panel beside a two-panel
  tier — now reads correctly both ways.
- **Text set to the balloon's shape.** Lines are fitted to the interior row
  by row, about the body's own centre, tails ignored: round balloons get a
  letterer's taper because that is their shape, tall ones get short lines
  all the way down, and type shrinks only when the words do not fit.
- **Gradient balloons keep their gradient.** A coloured balloon is cleaned by
  continuing its own paper under the lettering instead of a flat patch.
- **A second look at unread balloons.** A balloon OCR read nothing in is
  cropped from the full-resolution frame, enlarged, and read again.
- **Diagnostics** now report the crop re-reads and the panels found, and
  outline the panel grid in blue beside the magenta balloons.

## 0.9.3

**Still on 0.9.1?** On Android 9 or newer, use the one-time
**[legacy update APK](https://github.com/mkisontop/mangalens/releases/latest/download/MangaLens-legacy-update.apk)**
instead of the normal download. On Android 8/8.1, see the migration note below.

- **A real OpenRouter key option.** OpenRouter now has its own clearly labelled
  key field and a direct link to the OpenRouter key page. API keys and model
  choices are stored separately for every provider, so changing providers can
  never reuse an Anthropic, OpenAI, Gemini, OpenRouter, or custom-endpoint
  credential in the wrong place. Existing settings migrate to whichever
  named provider was selected before the upgrade; legacy custom-endpoint
  tokens require re-entry because their ownership cannot be proven. Keys live
  in Android's no-backup storage, separate from ordinary preferences.
- **Repairs the 0.9.1 update path.** The 0.9.1 release workflow had no private
  signing secret and silently fell back to the public debug key. Version 0.9.2
  correctly used the private key, so Android rejected it as an update rather
  than risk replacing the app with code from a different signer.
- **One-time Android 9+ bridge.** Users who still have 0.9.1 can install
  `MangaLens-legacy-update.apk` once. It contains a verified debug→private
  signing lineage, preserves the existing app data, and makes subsequent
  normal private-signed updates compatible. Users already on 0.9.2 must use
  the normal `MangaLens.apk`.
- **Android 8/8.1 migration.** Those releases cannot rotate an APK signing key.
  Users on 0.9.1 must record any settings they need, uninstall it, and install
  the normal APK once. The legacy bridge must not be used there: it can remain
  compatible with the old signature, but cannot rotate the installed identity.
- **Releases now fail closed.** Gradle always produces an unsigned local
  release APK. CI alone zipaligns and signs official APKs, verifies the pinned
  private certificate after signing, and refuses to publish if either signing
  secret is missing or invalid, the tag points at another commit, or Android's
  version code did not increase. `MangaLens.apk` remains private-key-only; the
  legacy bridge is published under a separate, explicit name.

## 0.9.2

Four fixes from the first real field test, all in the direction of "the
translation is always readable and never stale".

- **Readable on black panels.** Floating cards now paint effectively solid,
  pick lettering that contrasts with their actual fill (light text on dark
  cards), and wear a hairline edge that shows against same-colored art. A
  night scene can no longer swallow its own translation.
- **The AI polish can't erase the page.** When "✨ upgrading…" comes back
  empty or partial, the draft cards you were reading stay up — the polish
  replaces exactly what it answered and nothing more. The pill says
  "draft kept" when that happens.
- **Instant page-turns are noticed under heavy coverage.** Tap-to-turn
  readers swap the page in one frame; with cards over most of the old page
  the comparison used to go blind and stale translations sat on the new
  page. The detector now reads whatever remains uncovered and lowers its
  threshold as coverage grows.
- **Dense pages stay visible.** Tall on-art narration columns no longer
  demand panel-sized cards: the narrow column is wiped clean and a compact
  card floats over it, cards cap at half a screen, and overlapping cards
  nudge apart instead of stacking into a slab.

The card-opacity slider is retired — dialogue is always solid, and the
peek gesture (long-press → peek) is the way to see the original art.

## 0.9.1

First release from MangaLens's own repository.

**New**
- **The app now updates itself politely.** On open it makes one anonymous
  check against this repository's latest release; if a newer version exists, a
  small banner offers it. Nothing downloads without a tap, and being offline
  shows nothing at all.
- **Gemini model picker, always current.** With a (free) Google AI Studio key
  pasted in, one tap fetches Google's live model list and offers the usable
  text models newest-first — Flash before Flash-Lite before Pro. Models
  released long after this build ships will appear the day they exist.
- **Release-signing groundwork.** CI support for a private release key was
  added here, but the first run did not have that secret and fell back to the
  debug key. Version 0.9.3 repairs that update path and removes the fallback.

**Download:** grab `MangaLens.apk` below — that name always points at the
newest release. Verify with `checksums.txt` if you like.

## 0.9.0

- Retired the sticky-scroll experiment; the live loop is the product: scroll
  and overlays clear instantly, stop and the page translates. Revisited pages
  re-paint from cache.
- Fourth balloon-detection pass for tinted (pink/lavender) speech bubbles.

## 0.8.x

- Balloons detected from pixels with interior masks; burst, inverted and
  panel-shaped balloons.
- Scanlation-grade rendering: the balloon is wiped opaque and re-lettered in
  Comic Neue with a human letterer's taper.
- Animated on/off toggle on the floating button.

## 0.7.x

- Content-keyed vision cache (no more coordinate-remembered translations),
  burst balloons, Latin-script raws, text-anchored placement.
