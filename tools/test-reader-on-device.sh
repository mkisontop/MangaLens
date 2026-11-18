#!/usr/bin/env bash
set -euo pipefail

out=app/build/reader-experience
mkdir -p "$out"
adb shell wm size 720x1280
adb shell wm density 280
adb shell settings put secure immersive_mode_confirmations confirmed
failed=0
for build in baseline candidate; do
    apk=app/build/outputs/apk/debug/app-debug.apk
    if [[ "$build" == baseline ]]; then apk=app/build/reader-baseline/baseline.apk; fi
    adb install -r -g "$apk"
    adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    adb shell pm clear app.mangalens
    adb shell pm grant app.mangalens android.permission.POST_NOTIFICATIONS
    adb shell appops set app.mangalens SYSTEM_ALERT_WINDOW allow
    adb logcat -c
    adb shell am instrument -w -r -e build "$build" app.mangalens.test/androidx.test.runner.AndroidJUnitRunner \
        | tee "$out/$build-instrumentation.txt"
    adb logcat -d > "$out/$build-device-log.txt"
    if adb exec-out run-as app.mangalens tar -cf - -C files/reader-ux "$build" > "$out/$build.tar"; then
        tar -xf "$out/$build.tar" -C "$out"
        rm "$out/$build.tar"
    fi
    if ! grep -Eq '^OK \([1-9][0-9]* tests?\)' "$out/$build-instrumentation.txt"; then
        failed=1
        grep -Ei 'MangaLensUX|UnsatisfiedLinkError|FATAL EXCEPTION|dlopen failed|text recognizer.*fail' "$out/$build-device-log.txt" | tail -80 || true
        if [[ -f "$out/$build/windows.xml" ]]; then
            python3 - "$out/$build/windows.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
for node in ET.parse(sys.argv[1]).iter("node"):
    label = node.get("text") or node.get("content-desc")
    if label:
        print(node.get("package"), label)
PY
        fi
    fi
done
if ((failed)); then exit 1; fi
python3 tools/compare-reader-experience.py "$out"
