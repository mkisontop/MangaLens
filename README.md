# MangaLens preview build

Debug builds of branch `claude/manga-lens-realtime-translation-bv8riz`
at commit d3c91ae (read-ahead analysis, streamed AI polish, AI reasoning setting,
region close-ups for the vision model,
resolution-robust balloon detection, panel-aware order, shape-aware
typesetting, inpainted fills).

Direct downloads:

- Standard build, 41 MB:
  https://raw.githubusercontent.com/mkisontop/MangaLens/apk-preview/MangaLens-realtime.apk
- The same build inside a zip, 24 MB (open the zip, then install the APK):
  https://raw.githubusercontent.com/mkisontop/MangaLens/apk-preview/MangaLens-realtime.zip
- Compact build, 19 MB (code shrinker on, native libraries compressed;
  same source, shrinking not covered by the test run):
  https://raw.githubusercontent.com/mkisontop/MangaLens/apk-preview/MangaLens-realtime-compact.apk

All are signed with the public debug key, so they cannot update a
release-signed install: uninstall 0.9.3 first. Version name is still 0.9.3.

This branch holds only these files. Delete it when the build is no longer
needed and they leave the repository's history.
