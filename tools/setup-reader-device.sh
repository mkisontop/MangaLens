#!/usr/bin/env bash
set -euo pipefail

# A background Pixel Launcher ANR can cover the entire reading session on a fresh AVD.
# The event buffer still makes any crash or ANR from MangaLens itself fail this test.
adb shell settings put global hide_error_dialogs 1
adb shell am force-stop com.google.android.apps.nexuslauncher
adb shell wm size 720x1280
adb shell wm density 280
adb shell settings put secure immersive_mode_confirmations confirmed
