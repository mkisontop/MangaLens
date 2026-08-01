# Changelog

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
- **Real release signing.** Official APKs are signed with a private release
  key in CI. The committed debug keystore now signs debug builds only.

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
