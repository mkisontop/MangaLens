#!/usr/bin/env bash
set -euo pipefail

out=app/build/reader-experience
mkdir -p "$out"
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell appops set app.mangalens SYSTEM_ALERT_WINDOW allow
adb shell wm size 720x1280
adb shell wm density 280
adb logcat -c
adb shell am instrument -w -r app.mangalens.test/androidx.test.runner.AndroidJUnitRunner | tee "$out/instrumentation.txt"
adb logcat -d -s MangaLensUX:I AndroidRuntime:E > "$out/device-log.txt"
adb exec-out run-as app.mangalens tar -cf - -C files reader-ux > "$out/screens.tar" || true
if tar -tf "$out/screens.tar" >/dev/null 2>&1; then
    tar -xf "$out/screens.tar" -C "$out"
    rm "$out/screens.tar"
fi
if [[ -f "$out/reader-ux/metrics.csv" ]]; then cat "$out/reader-ux/metrics.csv"; fi
grep -Eq '^OK \([1-9][0-9]* tests?\)' "$out/instrumentation.txt"
