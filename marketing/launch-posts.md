# Launch kit

Ready-to-paste posts for the first communities. Rules of thumb before
posting anywhere:

- **Lead with the demo.** Record 20–30 seconds on your own tablet: scroll a
  raw, stop, bubbles turn English, scroll on. Vertical video. Post the clip
  and let it do the selling. (For official material — this site, the README —
  use the original demo strip in this folder, not screenshots of copyrighted
  manhwa.)
- **Read each subreddit's self-promo rule first** (usually in the sidebar).
  "Free and open source, made this myself, feedback wanted" is welcome almost
  everywhere that ads are not. Post as yourself, answer every comment.
- **Never mention aggregator/piracy sites by name.** The app is a screen
  translator; where people read is their business.
- One community per day, not five at once — each post gets your full
  attention, and Reddit's spam filter stays calm.

Links to use:
- Download: https://github.com/mkisontop/mangalens/releases/latest
- Source: https://github.com/mkisontop/mangalens
- Site: https://mkisontop.github.io/mangalens/

---

## 1 · r/manhwa · r/OtomeIsekai · r/Manhua (reader communities)

**Title:** I built a free, open-source Android app that translates raw
manhwa live on your screen — the bubbles get cleaned and re-lettered like a
scanlation

**Body:**

Reader first, developer second: I kept catching up to a series and hitting
the wall where the official translation ends. So I built MangaLens.

It's an Android app that watches your screen while you read in your normal
browser or reader app. When you stop scrolling, it finds the speech bubbles
— in the pixels, not by guessing — wipes them clean through their exact
shape, and re-letters them in English in comic type. Scroll on and it gets
out of your way instantly. It handles Korean, Japanese and Chinese, spiky
shout bubbles, black narration boxes, tinted bubbles, the lot.

[DEMO VIDEO/GIF HERE]

The details that matter:

- Completely free and open source (MIT). No account, no ads, no tracking.
- OCR runs on your device. The default engine needs no setup at all.
- Optional "AI Pro" mode uses your own key — Google AI Studio keys are free
  — and keeps a glossary so character names stay consistent across chapters.
- Not on the Play Store yet; it's a direct APK from GitHub Releases, signed
  and checksummed, and the app offers updates itself.

Download + source: https://github.com/mkisontop/mangalens

It's an early version and I want it to be great — if a bubble style trips it
up, send me a screenshot and I'll fix it. And obviously: when the official
release of something exists, buy it.

---

## 2 · Mihon / reader-app communities (r/mihonapp and similar)

**Title:** Companion app for reading untranslated series: live on-screen
translation that re-letters the bubbles (FOSS, Android)

**Body:**

We've all got that series where the scanlation is 40 chapters behind the
raws. MangaLens is an overlay app that translates whatever's on screen —
so it works on top of any reader, browser, or source, no extension or
integration needed.

Stop scrolling for a third of a second and the bubbles get wiped and
re-typeset in English; scroll and the overlay clears instantly. Detection
is pixel-based (flood-fill balloon masks, not OCR-box guessing), so shout
bubbles, black flashback boxes and pastel bubbles all work. KO/JA/ZH → EN.

- FOSS (MIT), no account/ads/analytics, OCR fully on-device
- Free default engine; optional BYO-key AI mode (Gemini free tier works)
  with per-series glossary and story memory
- Direct APK from GitHub Releases with checksums + in-app update checks —
  the usual sideload flow this community already knows

Repo: https://github.com/mkisontop/mangalens

Happy to answer anything technical — the README has a fairly deep writeup
of how balloon detection and the typesetting work.

---

## 3 · r/androidapps / r/opensource / r/SideProject

**Title:** MangaLens — I built a live screen translator for manhwa/manga
that re-letters speech bubbles in place (Android, FOSS)

**Body:**

MangaLens captures the screen with MediaProjection, detects speech balloons
as enclosed ink-bounded regions (four flood-fill passes: white, burst/spiky,
inverted, tinted), OCRs the CJK text on-device with ML Kit, translates it,
then wipes each balloon through its detected interior mask and re-typesets
the English in comic lettering with a letterer's line taper.

The interesting problems were never the translation — they were things like:
burst balloons whose borders are disconnected ticks (flood fill leaks; you
have to seal them with dilated ink first), translucent overlays reading as
"AI slop" vs opaque cleaning reading as human work, and caching pages by
their content instead of screen coordinates so scrolling back is free.

[DEMO GIF]

- Kotlin, Jetpack Compose, no backend at all — optional AI mode is BYO key
- MIT, ~zero dependencies beyond ML Kit and OkHttp
- Direct APK via GitHub Releases (signed, checksummed, in-app update checks)

Source: https://github.com/mkisontop/mangalens

Feedback very welcome — especially weird bubble styles that break detection.

---

## Later (once there are users and reviews to point at)

- **Show HN**: "Show HN: MangaLens – live screen translation for manhwa that
  re-letters the bubbles (Android, FOSS)" — post morning US time, link the
  repo, first comment explains the balloon-detection approach honestly.
- **Language-learning subs** (r/Korean, r/LearnJapanese): frame as training
  wheels for reading native webtoons — show the peek-at-original button.
- **Shorts/TikTok/Reels**: 15-second clips, text overlay "reading raws with
  no translation ->", the magic moment, link in bio. Repeatable weekly.
- **Product Hunt**: only after the site has a real demo video and the repo
  has stars to show.
