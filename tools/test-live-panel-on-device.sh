#!/usr/bin/env bash
set -euo pipefail

id=app.mangalens.preview
out=${MANGALENS_LIVE_DEVICE_OUT:-app/build/reader-live-device}
reader_source=${MANGALENS_READER_SOURCE:-.}
mkdir -p "$out"
bash tools/setup-reader-device.sh
adb install -r -g "$reader_source/app/build/outputs/apk/debug/app-debug.apk"
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm clear "$id" | grep -q Success
bash tools/fetch-reader-page.sh "$id"
# The app reaches the runner's private model over the emulator's loopback interface.
python3 tools/observe-panel-model.py "$out/model-requests.jsonl" &
observer=$!
projection_observer=
cleanup_observers() {
    kill "$observer" 2>/dev/null || true
    if [[ -n "$projection_observer" ]]; then kill "$projection_observer" 2>/dev/null || true; fi
}
trap cleanup_observers EXIT
for attempt in {1..20}; do
    if curl -fsS http://127.0.0.1:7879/health > /dev/null 2>&1; then break; fi
    sleep 0.2
done
curl -fsS http://127.0.0.1:7879/health > /dev/null
adb reverse tcp:7879 tcp:7879
# A CPU runner pays a substantial one-time prompt prefill. Warm only the
# app's fixed instructions; the comic image and its words arrive from Android.
python3 - "$reader_source" <<'PY'
import json, os, re, sys, textwrap, urllib.request
from pathlib import Path
source = (Path(sys.argv[1]) / "app/src/main/java/app/mangalens/translate/VisionLlmEngine.kt").read_text()
match = re.search(r'private val SYSTEM_PROMPT = """(.*?)"""', source, re.S)
prompt = textwrap.dedent(match.group(1)).strip()
body = {"model": os.environ["MANGALENS_LIVE_MODEL"], "max_tokens": 1,
        "messages": [{"role": "system", "content": prompt}, {"role": "user", "content": "Ready."}]}
req = urllib.request.Request(os.environ["MANGALENS_LIVE_ENDPOINT"], data=json.dumps(body).encode(),
                             headers={"Content-Type": "application/json"})
with urllib.request.urlopen(req, timeout=240) as reply:
    print("Model instructions warmed", json.load(reply).get("usage"))
PY
holdout=0
if [[ "${MANGALENS_READER_SOURCE:-.}" == "." ]]; then holdout=1; fi
adb logcat -c
adb shell am instrument -w -r -e build live -e liveHoldout "$holdout" -e liveEndpoint http://127.0.0.1:7879/v1/chat/completions -e liveModel "$MANGALENS_LIVE_MODEL" \
    -e class 'app.mangalens.reader.PublishedComicExperienceTest#realModelLettersThePublishedKoreanPanelOnTheDevice' \
    "$id.test/androidx.test.runner.AndroidJUnitRunner" | tee "$out/instrumentation.txt"
adb logcat -d -s MangaLensUX:I > "$out/device.log"
cat "$out/device.log"
cat "$out/model-requests.jsonl"
adb exec-out run-as "$id" tar -cf - -C files/reader-ux live > "$out/page.tar"
tar -xf "$out/page.tar" -C "$out"
rm "$out/page.tar"
cp app/src/androidTest/assets/peppercarrot-CREDITS.txt "$out/"
for detail in "$out"/live/published-*-detail.jpg; do
    [[ -f "$detail" ]] || continue
    echo "MangaLensDetail $(basename "$detail")"
    base64 -w 960 "$detail" | sed 's/^/MangaLensFrame /'
done
adb logcat -b events -d -s am_anr am_crash > "$out/process-events.txt"
if grep -E 'am_(anr|crash).*[, ]app\.mangalens([.:, ]|$)' "$out/process-events.txt"; then exit 1; fi
grep -Eq '^OK \(1 test\)' "$out/instrumentation.txt"
test -s "$out/live/live-contest.json"
test -s "$out/live/live-panel.json"
cat "$out/live/live-contest.json" "$out/live/live-panel.json"
if [[ "$holdout" == 1 ]]; then cat "$out/live/live-potion.json"; fi

if [[ -n "${MANGALENS_LIVE_COMPARE_ORDER:-}" ]]; then
    adb shell am instrument -w -r -e build vision -e compareVision 1 \
        -e class 'app.mangalens.reader.BufferedPageExperienceTest#anchoredVisionStreamsTwelveLinesOntoTheirOwnBalloons' \
        "$id.test/androidx.test.runner.AndroidJUnitRunner" | tee "$out/anchored-instrumentation.txt"
    adb exec-out run-as "$id" cat files/reader-ux/vision/anchored.csv > "$out/anchored.csv"
    cat "$out/anchored.csv"
    grep -Eq '^OK \(1 test\)' "$out/anchored-instrumentation.txt"
    adb logcat -b events -d -s am_anr am_crash > "$out/process-events.txt"
    if grep -E 'am_(anr|crash).*[, ]app\.mangalens([.:, ]|$)' "$out/process-events.txt"; then exit 1; fi
fi

if [[ "$holdout" == 1 && -n "${MANGALENS_LIVE_COMPARE_ORDER:-}" ]]; then
    # Test the system capture service with a different language and a previously unseen page.
    python3 tools/observe-panel-model.py "$out/projection-requests.jsonl" 7880 &
    projection_observer=$!
    for attempt in {1..20}; do
        if curl -fsS http://127.0.0.1:7880/health > /dev/null 2>&1; then break; fi
        sleep 0.2
    done
    curl -fsS http://127.0.0.1:7880/health > /dev/null
    adb reverse tcp:7880 tcp:7880
    adb shell pm clear "$id" | grep -q Success
    adb shell pm grant "$id" android.permission.POST_NOTIFICATIONS
    adb shell appops set "$id" SYSTEM_ALERT_WINDOW allow
    adb logcat -c
    adb shell am instrument -w -r -e build projection -e liveModel "$MANGALENS_LIVE_MODEL" \
        -e projectionEndpoint http://127.0.0.1:7880/v1/chat/completions \
        -e class 'app.mangalens.reader.ReaderExperienceTest#liveScreenSharingReplacesUnseenJapaneseDialogue' \
        "$id.test/androidx.test.runner.AndroidJUnitRunner" | tee "$out/projection-instrumentation.txt"
    adb logcat -d > "$out/projection-device.log"
    adb logcat -b events -d -s am_anr am_crash > "$out/projection-events.txt"
    adb exec-out run-as "$id" tar -cf - -C files/reader-ux projection | tar -xf - -C "$out"
    cat "$out/projection-requests.jsonl"
    if grep -E 'am_(anr|crash).*[, ]app\.mangalens([.:, ]|$)' "$out/projection-events.txt"; then exit 1; fi
    grep -Eq '^OK \(1 test\)' "$out/projection-instrumentation.txt"
    cat "$out/projection/projection-japanese.json"
fi
