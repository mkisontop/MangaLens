#!/usr/bin/env bash
set -euo pipefail

dest=app/build/reader-fixtures/potion-contest-page.jpg
url=https://www.peppercarrot.com/0_sources/ep06_The-Potion-Contest/low-res/kr_Pepper-and-Carrot_by-David-Revoy_E06P02.jpg
mkdir -p "$(dirname "$dest")"
curl -fsSL --retry 3 --connect-timeout 15 --max-time 90 "$url" -o "$dest"
sha256sum "$dest"
