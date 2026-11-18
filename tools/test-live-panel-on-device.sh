#!/usr/bin/env bash
set -euo pipefail

id=app.mangalens.preview
out=app/build/reader-live-device
mkdir -p "$out"
# A background Pixel Launcher ANR can cover the entire reading session on a fresh AVD.
# The event buffer still makes any crash or ANR from MangaLens itself fail this test.
adb shell settings put global hide_error_dialogs 1
adb shell am force-stop com.google.android.apps.nexuslauncher
adb shell wm size 720x1280
adb shell wm density 280
adb shell settings put secure immersive_mode_confirmations confirmed
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
bash tools/fetch-reader-page.sh "$id"
# The app reaches the runner's private model over the emulator's loopback interface.
adb reverse tcp:7878 tcp:7878
# A CPU runner pays a substantial one-time prompt prefill. Warm only the
# app's fixed instructions; the comic image and its words arrive from Android.
python3 - <<'PY'
import json, os, re, textwrap, urllib.request
from pathlib import Path
source = Path("app/src/main/java/app/mangalens/translate/VisionLlmEngine.kt").read_text()
match = re.search(r'private val SYSTEM_PROMPT = """(.*?)"""', source, re.S)
prompt = textwrap.dedent(match.group(1)).strip()
body = {"model": os.environ["MANGALENS_LIVE_MODEL"], "max_tokens": 1,
        "messages": [{"role": "system", "content": prompt}, {"role": "user", "content": "Ready."}]}
req = urllib.request.Request(os.environ["MANGALENS_LIVE_ENDPOINT"], data=json.dumps(body).encode(),
                             headers={"Content-Type": "application/json"})
with urllib.request.urlopen(req, timeout=240) as reply:
    print("Model instructions warmed", json.load(reply).get("usage"))
PY
adb logcat -c
adb shell am instrument -w -r -e build live -e liveEndpoint "$MANGALENS_LIVE_ENDPOINT" -e liveModel "$MANGALENS_LIVE_MODEL" \
    -e class 'app.mangalens.reader.PublishedComicExperienceTest#realModelLettersThePublishedKoreanPanelOnTheDevice' \
    "$id.test/androidx.test.runner.AndroidJUnitRunner" | tee "$out/instrumentation.txt"
adb logcat -d -s MangaLensUX:I > "$out/device.log"
cat "$out/device.log"
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
test -s "$out/live/live-panel.json"
cat "$out/live/live-panel.json"
