# Changelog

## Unreleased

A full review of the app, with every finding checked and fixed.

- **Auto-scroll**
  - Translation no longer starts on a page that is still gliding slowly,
    and a stop always waits for its own translation.
  - MangaLens's own buttons no longer keep it from hurrying through the
    gaps between panels, or pass for the end of the page.
  - It also stops at the end of the page while translating.
  - Turning its Accessibility switch off while it runs stops it with a
    message instead of leaving it stuck.
  - It waits while the long-press menu is open, and a speed tap no longer
    stops your next touch from pausing it.
- **Translation**
  - Stop can no longer crash the app while a frame is being copied.
  - A page with an animated banner or a video no longer translates over
    and over.
  - The last page's cards no longer come back over a new page after a
    failed pass.
  - Each series keeps its own names and story from the first page on, is
    recognised again when you come back to it after a long read, and a
    read still running when you tap New series no longer teaches the next
    one. A few failed reads in a row (offline, rate-limited) no longer
    make it forget the series.
  - A reply with no lines no longer beats one with lines; the other
    requests keep going.
  - Small scroll nudges no longer leave rows unread, and a remembered
    answer is reused only where the same words are found again.
- **Cleaning the page**
  - On screentone, panel borders and outlines beside the lettering are no
    longer cut through and filled with dots.
  - Art text is no longer wiped flat because a line once drifted into it.
  - The AI clean-up sends at most eight regions and paints none of its
    lettering back, and skips the request when no image model is left.
  - Two balloons drawn joined each get their own line of English again,
    instead of one block running across the outline between them.
- **Reading the page on the phone**
  - Two joined balloons cut by the screen's edge are treated as cut.
  - Korean lines ending in 야 or 라 are no longer run into the next
    speaker's.
  - The on-device reading models are released when you stop.
- **The app**
  - A leftover 1.0.1 Solid lettering switch no longer hides No ghosts.
  - Allow it no longer crashes on phones without Android's overlay
    permission page.
  - The first Tweaks or scroll-button request after turning the phone is
    no longer lost.
  - OpenRouter and OpenAI refusals (HTTP 403) are no longer reported as a
    bad key.
- The release build signs the APK in a separate job that never runs build
  code.

## 1.0.6

Faster auto-scroll.

**Download:** `MangaLens.apk` below updates 0.9.2 and newer in place.

- **Auto-scroll goes much faster now.** The speed runs from 1 to 15 instead
  of 1 to 10: levels 1–10 are as they were, and each new level is about a
  third faster than the one before, so 15 moves about three times as fast
  as 10 did. The finger also drags a longer stretch of the screen before it
  lifts, so fast speeds run more smoothly.
- Installing still needs Play Protect's scanning switched off for a moment
  (auto-scroll's Accessibility switch is what it blocks); see the README.

## 1.0.5

Auto-scroll: MangaLens scrolls the page for you, in any app, and slows down
for the big balloons.

**Download:** `MangaLens.apk` below updates 0.9.2 and newer in place.

- **Auto-scroll.** Tap the new **▼** beside the 文A bubble and the page moves
  on by itself — in Brave, a reader app, anything that scrolls. **−** and
  **+** change the speed as it goes, **❚❚** stops it, and touching the
  screen pauses it until you let go. It stops by itself at the end of the
  page. It is also in the long-press menu, and Tweaks → Auto-scroll sets
  the speed it starts at.
- **It slows down for big balloons.** MangaLens watches the page on your
  phone as it moves and slows right down while a big balloon goes by, the
  more the bigger it is, then hurries through the empty gaps between
  panels. No AI or connection is involved, so it works the same with
  translation napping.
- **With translation or without.** Napping (tap 文A), the page glides
  without stopping. Awake, it glides half a screen, waits for that stop's
  translation, gives you time to read it, and glides on.
- **One switch in Accessibility.** Only an accessibility service can move
  another app's page, so auto-scroll comes with one, "MangaLens auto-scroll":
  Tweaks → Auto-scroll → **Turn on** takes you there. It can only drag the
  page — it can't read the screen, see what you type or tap anything. On
  Android 13 and later, if the switch is greyed out, allow restricted
  settings in App info first (the link is right under the button).
- **Installing:** where Play Protect's enhanced fraud protection is on,
  Android refuses a downloaded app with an accessibility service ("App
  blocked to protect your device"). Switch that protection off for the
  install and back on afterwards; see the README.
- **Fewer slow stops while you scroll yourself.** Lines read at the last
  stop are found again more reliably, thin ones like a lone "……" included,
  so a small scroll reads only the new strip instead of the whole screen
  again (about 1 stop in 10 used to).
- A line the screen's edge cut in half is read whole at the next stop,
  instead of keeping the half-sentence it was first read as.
- **Cleaner balloons.** A panel whose boxes all slid off their balloons,
  a box that slid down out of its balloon, and a face under a drifted box
  are all handled: the balloon is cleaned whole and the art is left alone.
- Words that fit no centred block are set in the balloon's largest clear
  area instead of spilling over its edge.

## 1.0.4

Faster, above all when you scroll.

**Download:** `MangaLens.apk` below updates 0.9.2 and newer in place.

- **A scroll reads only what's new.** After a scroll, only the strip the
  scroll revealed goes to the AI, and the lines you had already read come
  back the moment you stop, moved with the page, instead of the whole screen
  being read again. On real pages, the first new line after a small scroll
  arrived in 1.7 s instead of 4.2 s, and the whole screen was done in 2.7 s
  instead of 5.7 s. These times were measured from a server; your phone adds
  the time it takes to upload the page.
- **A new page's first line comes sooner.** The AI's first line is painted as
  soon as it arrives, without waiting for the phone's own text recognition,
  and the page is sent the moment it settles: about 1.7–1.9 s to the first
  line instead of 2.4 s, measured the same way.
- **Lines cut by the screen's edge stay translated** after a scroll.
- **When Google is overloaded**, MangaLens switches to a stand-in model at
  once instead of waiting it out, and gives up on a connection that has gone
  silent.
- **Cleaner balloons.** A balloon the AI only partly boxed is cleaned whole,
  and white lettering edged in black on dark art, and the white halo around
  lettering, are erased with it.
- **Better typesetting.** Names and short words stay whole whenever they fit,
  words break where a dictionary would break them, free lettering keeps off
  its neighbours' lines, and sounds the AI wraps in asterisks are lettered
  without them.
- The reading code is compiled ahead of time, so the first page after
  starting MangaLens is quicker too.
- With **diagnostics** on, the pill says how long each stop took from the
  moment you stopped: to the first line, how many lines came back straight
  away, and the upload apart.

## 1.0.3

No more grey ghost, and nothing extra to allow for it.

**Download:** `MangaLens.apk` below updates 0.9.2 and newer in place.

- **Clean balloons on Android 12 and later.** Android draws an overlay that
  lets your taps through at no more than 80% strength, so a fifth of the page
  showed through every cleaned balloon: a grey ghost of the original lettering
  under the English. MangaLens now dims the whole page by that fifth while it
  is awake, and paints each cleaned balloon, card and erased patch with the
  page's share already taken off, so the two meet at exactly the same level
  and nothing of the original shows through. No accessibility service or any
  other permission is involved.
- The page is a little darker while MangaLens is awake, and back to full
  brightness while it naps. **Tweaks → No ghosts** switches the veil off if
  you would rather have full brightness and the faint ghost.
- Floating cards hide the art under them completely now, too.

## 1.0.2

Installs again. Where Play Protect's enhanced fraud protection is on, 1.0.1
was refused outright ("App blocked to protect your device"), because of the
accessibility service it added for solid lettering: Play Protect blocks any
app installed from a browser or file manager that declares one, whatever the
service does. That service is gone; everything else in 1.0.1 is here.

**Download:** `MangaLens.apk` below updates 0.9.2 and newer in place.

- **No accessibility service.** The optional setup step **Make the English
  solid** and the **Solid lettering** row in Tweaks went with it. If you had
  switched it on in 1.0.1, there is nothing to undo: without the service
  the switch does nothing, even where Android still remembers it as on.
- On Android 12 and later a faint trace of the original can show under the
  English again, as it did before 1.0.1: Android draws an overlay that lets
  your taps through at no more than 80% strength.
- The release build now refuses to publish an APK that declares anything
  Play Protect blocks sideloaded installs for.

## 1.0.1

Cleaner pages: every balloon is wiped whole, the English can be solid black
on white, lettering stays where it belongs, and the translation reads like an
English scanlation rather than a translation of one.

**Download:** `MangaLens.apk` below updates 0.9.2 and newer in place.

- **Solid lettering (no more grey ghost).** Since Android 12 an app overlay
  that lets taps through is drawn at no more than 80% opacity, so every
  cleaned balloon showed a faint grey ghost of the original and the English
  was dark grey. Turn on the new optional setup step **Make the English
  solid** (or the **Solid lettering** row in Tweaks): MangaLens is switched on
  under Accessibility, and Android then draws its lettering at full strength.
  The switch only lets it draw — it gets no accessibility events, can't read
  the screen and can't tap for you. Android 13+ may first ask you to allow
  restricted settings in App info; the step shows how.
- **Whole balloons, every time.** When the model's box around a vertical
  balloon's text missed a column — the short first one, a lone last glyph —
  that column stayed on the page beside the English, and the English was
  squeezed into one column. The missing column is now recognised as the
  same line, and the whole balloon is cleaned and lettered.
- **Balloons the detector used to miss** — see-through ones with the art
  showing faintly through, ones breaking a panel border, ones cut by the page
  edge, bursts around big lettering — are now found from their own lettering
  outward, cleaned whole and lettered into their shape.
- **Lettering stays where it belongs.** English set over text on the art is
  centred on that text (a stray stroke of art no longer drags it a line
  away) and stays inside its own panel. Inside a dense balloon the type may go
  a step smaller before it would spill over the outline, and words that
  still cannot fit are haloed in the balloon's paper. Thoughts are lettered in
  bold italic, dialogue's weight, instead of a faint thin italic.
- **A scanlator's English.** The AI is asked for natural, idiomatic English in
  each character's voice rather than word-for-word structure: no calques,
  the plain words of English adult comics instead of clinical ones, and a
  proofread of every line — while still adding nothing the source doesn't say.
- **Simpler Tweaks.** Tweaks shows the language, hands-free, text size and a
  Your AI card that says whether your key works (Test it, Change key).
  Timing, data saver, thinking time, the model (Automatic by default), other
  AI providers and diagnostics wait under **More options**.

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
