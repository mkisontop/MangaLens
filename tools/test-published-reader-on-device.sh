#!/usr/bin/env bash
set -euo pipefail

out=app/build/android-15
id=app.mangalens.preview
mkdir -p "$out"
bash tools/setup-reader-device.sh
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell appops set "$id" SYSTEM_ALERT_WINDOW allow
bash tools/fetch-reader-page.sh "$id"
adb shell am start -W -n "$id/app.mangalens.MainActivity" | tee "$out/home-launch.txt"
grep -Eq '^Status: ok' "$out/home-launch.txt"
adb shell am force-stop "$id"
adb logcat -c
suite=app.mangalens.reader.PublishedComicExperienceTest
capture=app.mangalens.reader.ReaderExperienceTest
adb shell am instrument -w -r -e build candidate \
    -e class "$suite#publishedKoreanBalloonsShowReadableEnglishWithinTheirOwnPanels,$suite#followRealArtworkByTheExactNumberOfScrolledPixels,$capture#anExpiredScreenGrantReturnsHomeAndFreshConsentResumesCapture" \
    "$id.test/androidx.test.runner.AndroidJUnitRunner" | tee "$out/instrumentation.txt"
adb logcat -d -s MangaLensUX:I > "$out/recognition.txt"
cat "$out/recognition.txt"
adb exec-out run-as "$id" tar -cf - -C files/reader-ux candidate > "$out/screens.tar"
tar -xf "$out/screens.tar" -C "$out"
rm "$out/screens.tar"
cp app/src/androidTest/assets/peppercarrot-CREDITS.txt "$out/"
cat "$out/candidate/published.csv" "$out/candidate/published-scroll.csv"
adb logcat -b events -d -s am_anr am_crash > "$out/process-events.txt"
if grep -E 'am_(anr|crash).*[, ]app\.mangalens([.:, ]|$)' "$out/process-events.txt"; then exit 1; fi
grep -Eq '^OK \(3 tests\)' "$out/instrumentation.txt"
