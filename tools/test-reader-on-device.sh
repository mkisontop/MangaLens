#!/usr/bin/env bash
set -euo pipefail

out=app/build/reader-experience
mkdir -p "$out"
adb shell wm size 720x1280
adb shell wm density 280
adb shell settings put secure immersive_mode_confirmations confirmed
failed=0
builds=(baseline candidate)
if [[ "${READER_ORDER:-baseline-first}" == candidate-first ]]; then builds=(candidate baseline); fi
for build in "${builds[@]}"; do
    id=app.mangalens.preview
    apk=app/build/outputs/apk/debug/app-debug.apk
    tests=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if [[ "$build" == baseline ]]; then
        id=app.mangalens
        apk=app/build/reader-baseline/baseline.apk
        tests=app/build/reader-baseline/tests.apk
    fi
    adb install -r -g "$apk"
    adb install -r -g "$tests"
    adb shell pm clear "$id"
    bash tools/fetch-reader-page.sh "$id"
    adb shell pm grant "$id" android.permission.POST_NOTIFICATIONS
    adb shell appops set "$id" SYSTEM_ALERT_WINDOW allow
    adb shell am start -W -n "$id/app.mangalens.MainActivity" | tee "$out/$build-home-launch.txt"
    grep -Eq '^Status: ok' "$out/$build-home-launch.txt"
    adb shell am force-stop "$id"
    adb logcat -c
    adb shell am instrument -w -r -e build "$build" "$id.test/androidx.test.runner.AndroidJUnitRunner" \
        | tee "$out/$build-instrumentation.txt"
    adb logcat -d > "$out/$build-device-log.txt"
    if adb exec-out run-as "$id" tar -cf - -C files/reader-ux "$build" > "$out/$build.tar"; then
        tar -xf "$out/$build.tar" -C "$out"
        rm "$out/$build.tar"
    fi
    for csv in "$out/$build"/*.csv; do
        [[ -f "$csv" ]] && sed "s/^/$build $(basename "$csv") /" "$csv"
    done
    if [[ "$build" == candidate ]]; then
        for detail in "$out/$build"/published-*-detail.jpg; do
            [[ -f "$detail" ]] || continue
            echo "MangaLensDetail $(basename "$detail")"
            base64 -w 960 "$detail" | sed 's/^/MangaLensFrame /'
        done
    fi
    if ! grep -Eq '^OK \([1-9][0-9]* tests?\)' "$out/$build-instrumentation.txt"; then
        failed=1
        grep -Ei 'MangaLensUX|UnsatisfiedLinkError|FATAL EXCEPTION|dlopen failed|text recognizer.*fail' "$out/$build-device-log.txt" | tail -80 || true
        # Preserve a view of a failed fixture even in a logs-only CI client.
        for screen in "$out/$build"/dense-0.png "$out/$build"/page-*-screen.png; do
            [[ -f "$screen" ]] || continue
            echo "MangaLensScreenshot $build/$(basename "$screen")"
            base64 -w 960 "$screen" | sed 's/^/MangaLensFrame /'
        done
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
cp app/src/androidTest/assets/peppercarrot-CREDITS.txt "$out/"
if ((failed)); then exit 1; fi
python3 tools/compare-reader-experience.py "$out"
