#!/usr/bin/env bash
set -euo pipefail

out=app/build/reader-experience
mkdir -p "$out"
adb shell wm size 720x1280
adb shell wm density 280
for build in baseline candidate; do
    apk=app/build/outputs/apk/debug/app-debug.apk
    if [[ "$build" == baseline ]]; then apk=app/build/reader-baseline/baseline.apk; fi
    adb install -r -g "$apk"
    adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    adb shell appops set app.mangalens SYSTEM_ALERT_WINDOW allow
    adb logcat -c
    adb shell am instrument -w -r -e build "$build" app.mangalens.test/androidx.test.runner.AndroidJUnitRunner \
        | tee "$out/$build-instrumentation.txt"
    adb logcat -d -s MangaLensUX:I AndroidRuntime:E > "$out/$build-device-log.txt"
    if adb exec-out run-as app.mangalens tar -cf - -C files/reader-ux "$build" > "$out/$build.tar"; then
        tar -xf "$out/$build.tar" -C "$out"
        rm "$out/$build.tar"
    fi
    grep -Eq '^OK \([1-9][0-9]* tests?\)' "$out/$build-instrumentation.txt"
done
python3 tools/compare-reader-experience.py "$out"
