#!/usr/bin/env bash
set -euo pipefail

id=app.mangalens.preview
out=app/build/reader-live-device
mkdir -p "$out"
adb shell wm size 720x1280
adb shell wm density 280
adb shell settings put secure immersive_mode_confirmations confirmed
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
bash tools/fetch-reader-page.sh "$id"
# The app reaches the runner's private model over the emulator's loopback interface.
adb reverse tcp:7878 tcp:7878
adb logcat -c
adb shell am instrument -w -r -e build live -e liveEndpoint "$MANGALENS_LIVE_ENDPOINT" \
    -e class 'app.mangalens.reader.PublishedComicExperienceTest#realModelLettersThePublishedKoreanPanelOnTheDevice' \
    "$id.test/androidx.test.runner.AndroidJUnitRunner" | tee "$out/instrumentation.txt"
adb logcat -d -s MangaLensUX:I > "$out/device.log"
cat "$out/device.log"
adb exec-out run-as "$id" tar -cf - -C files/reader-ux live > "$out/page.tar"
tar -xf "$out/page.tar" -C "$out"
rm "$out/page.tar"
cp app/src/androidTest/assets/peppercarrot-CREDITS.txt "$out/"
grep -Eq '^OK \(1 test\)' "$out/instrumentation.txt"
test -s "$out/live/live-panel.json"
cat "$out/live/live-panel.json"
