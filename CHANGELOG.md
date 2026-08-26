# Changelog

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
