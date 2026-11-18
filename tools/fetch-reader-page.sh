#!/usr/bin/env bash
set -euo pipefail

dest=app/build/reader-fixtures/potion-contest-page.jpg
url=https://www.peppercarrot.com/0_sources/ep06_The-Potion-Contest/low-res/kr_Pepper-and-Carrot_by-David-Revoy_E06P02.jpg
sha=8bd17200387dc787439078d695785a38535bfd872a0a43fbb8ee9a0a2721454e
if (($# == 0)); then
    if [[ -s "$dest" ]] && echo "$sha  $dest" | sha256sum --check --status; then exit 0; fi
    mkdir -p "$(dirname "$dest")"
    curl -fsSL --retry 3 --connect-timeout 15 --max-time 90 "$url" -o "$dest"
    echo "$sha  $dest" | sha256sum --check
else
    # Check the exact bytes the reader will decode inside its own app sandbox.
    adb shell run-as "$1" mkdir -p files
    adb push "$dest" /data/local/tmp/reader-comic.jpg
    adb shell "cat /data/local/tmp/reader-comic.jpg | run-as $1 tee files/potion-contest-page.jpg >/dev/null"
    actual=$(adb shell run-as "$1" sha256sum files/potion-contest-page.jpg | cut -d' ' -f1)
    [[ "$actual" == "$sha" ]] || { echo "Comic checksum on the device differs: $actual"; exit 1; }
fi
