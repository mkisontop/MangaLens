# Changelog

## 1.0.0

MangaLens 1.0: translation is AI-only and straight — no machine draft, no
"upgrading" — the app has a new face, Fuki, and Gemini reads the page
itself the moment you stop, with every line cleaned and re-lettered the
way a scanlation team does it.

**Download:** `MangaLens.apk` below updates 0.9.2 and newer in place. Still on
0.9.1? Use `MangaLens-legacy-update.apk` once (Android 9+), as described in
the README.

- **AI only** — the free Google and offline engines are gone, and there is
  no machine draft before the AI: each line appears once, in the AI's words.
  Gemini is the default provider (the fastest, with a free tier); a key you
  saved for another provider keeps working. Without a key the pill says
  "Add your Gemini key in MangaLens" instead of translating.
- **A new face: Fuki.** The home screen is one big button — Fuki, a
  speech balloon that says GO! and turns red to STOP — on cream newsprint
  with ink outlines and hard shadows. First run is two steps (float over
  apps, paste a free Gemini key), and Fuki wakes up a step at a time as
  you go, then proves the key by lettering a line. Paused, Fuki naps: tap
  it to wake it (Stop has its own button). Every setting moved to one
  Tweaks page; the overlay's quick menu
  opens it, its button and pill are restyled to read over white and black
  pages, and in tap-to-translate mode a tap on 文A translates the page.
- **A quieter pill.** While a page translates, the button's busy ring says
  so and the pill stays out of the way — no "translating…", no
  "upgrading…". It speaks up only for what you need to know: a page the AI
  couldn't read, and why (network, rate limit, rejected key), your own
  taps, the optional art clean-up, and diagnostics when they are on.
- **AI-first reading with Gemini.** The screen goes to the model about
  0.15 s after scrolling stops, alongside on-device analysis instead of after
  it, through Google's native API. The model finds every piece of lettering
  itself — balloons, captions, text on the art, sound effects — and each line
  is painted the moment it streams in, dialogue first in reading order.
  Measured on hard test pages, the first line lands about 1.5–2 s after the
  request; two identical requests race and the slower is cancelled, which
  cuts Google's occasional 3–6 s stalls. Pages go up at 1280 px (Gemini
  reads them at a fixed budget anyway), and the connection is opened while
  you scroll so no stop pays the handshake.
- **Best model by measurement.** `gemini-flash-latest` (currently Gemini 3.8
  Flash) is the default: on the test pages it found every line with tight
  boxes and read like a scanlation. 3.5 Flash-Lite answered ~0.6 s sooner but
  mistranslated, merged separate balloons into one and invented a sound
  effect; it stays selectable for plain pages.
- **Expert translation, reviewed by experts.** A panel of reviewers
  (Japanese manga, Korean and Chinese, lettering) critiqued live output and
  the prompt now carries their rules: one English per source sound, chosen by
  meaning (BA-DUMP, SHRAK, DOOM), never asterisks or romanized cries; breaths,
  moans and giggles lettered as the sound ("Hah... hah♡", "Heh heh"); rough
  speech kept rough and crude words matched; stammers the English way;
  titles translated, name honorifics kept. Faithful and complete — nothing
  omitted, summarised or softened.
- **Balloons instantly on scroll.** Lettering translated at an earlier stop
  is found again by its own pixels — searched along the scroll, verified
  stroke by stroke at near full resolution — and repainted before any request
  goes out. Never by position: two balloons side by side, a line one
  character different, or a new balloon in an old balloon's place are never
  confused, and a line shown twice is never guessed at.
- **A small scroll reads only what it revealed.** When a webtoon stop only
  nudged the page, the model is sent just the new strip and everything
  already read repaints from memory at once, so the new balloon arrives
  first — about 1.6 s after the stop on the test strip — instead of after
  every line you have already seen. A nudge that revealed nothing new sends
  no request.
- **Never wipes art that looks like a balloon.** A face in line art, a
  highlight, screentone or the inside of a big glyph can pass for a balloon.
  A detection is now cleaned only when its interior holds nothing but the
  lettering the model found there; otherwise that lettering is erased on its
  own. Two balloons drawn joined keep a line each, in their own lobes.
- **Text on the art is erased, not covered.** Narration on the art, side
  comments on screentone and white-outlined thoughts over a figure are
  erased stroke by stroke — screentone continued, gradients followed — and
  re-lettered in their own colours and outline. No more rounded cards.
- **Sound effects like a scanlation handles them.** Only the ones that tell
  you something are translated; decorative action lettering is left alone.
  A small one on plain ground is erased and re-lettered; a big one drawn
  across the art keeps its place and gets a small English note — on empty
  ground beside it, or on the sound itself where everything around it is
  drawn, never over a face or a figure. A heartbeat drawn four times down a
  column is noted "BA-DUMP BA-DUMP", once, and a long column is lettered
  down the column rather than spilling across the art beside it.
- **Lettering styles.** Shouts heavier, thoughts in italic, narration calmer,
  sound effects bold italic and outlined; the English fades in rather than
  popping.
- **Steadier on a scroll.** A line read again at the next stop keeps the
  words you already saw, but a balloon the model now reads in pieces (or
  whole) is never said twice or halved. A read abandoned by a quick scroll
  is cancelled instead of streaming on, a reply that broke off part-way is
  never cached as the whole page, and a rejected API key says so instead of
  failing silently.
- **Optional AI redraw.** Off by default. When on, an image model redraws
  detailed art under lettering drawn on it; only crops around that
  lettering are sent, the redraw is used only where it matches the art
  around each letter, and after two refusals it rests for 15 minutes.

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
