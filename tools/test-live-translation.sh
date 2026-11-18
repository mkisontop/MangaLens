#!/usr/bin/env bash
set -euo pipefail

export MANGALENS_LIVE_REPORT="$PWD/app/build/reader-live/translations.json"
run_test() {
    if (($#)); then
        "$@"
    else
        ./gradlew :app:testDebugUnitTest --tests app.mangalens.translate.LiveTranslationQualityTest
    fi
}

if [[ -n "${GEMINI_API_KEY:-}" && $# == 0 ]]; then
    run_test
    exit
fi

# Run a real, deterministic model on the CI CPU when no provider key is configured.
work=$(mktemp -d)
server_pid=
cleanup() {
    local status=$?
    if ((status != 0)) && [[ -f "$work/server.log" ]]; then tail -50 "$work/server.log"; fi
    if [[ -n "$server_pid" ]]; then kill "$server_pid" 2>/dev/null || true; fi
    rm -rf "$work"
}
trap cleanup EXIT
fetch() { curl -fsSL --retry 3 --retry-all-errors --connect-timeout 20 --max-time 720 "$1" -o "$2"; }
mkdir -p "$work/engine"
if [[ "${MANGALENS_LIVE_VISION:-0}" == 1 ]]; then
    engine="https://github.com/ggml-org/llama.cpp/releases/download/b12503/llama-b12503-bin-ubuntu-x64.tar.gz"
    fetch "$engine" "$work/engine.tar.gz"
    echo "0d585f8a78ffdfdc4f6303a089aaa1379c15323b33483b6548c85c774a376a9d  $work/engine.tar.gz" | sha256sum --check
    tar -xzf "$work/engine.tar.gz" -C "$work/engine"
else
    engine="https://github.com/ggml-org/llama.cpp/releases/download/b6752/llama-b6752-bin-ubuntu-x64.zip"
    fetch "$engine" "$work/engine.zip"
    echo "31cad32d9e191505ddf0c0e1f8ff2ab9f6ff64dbe9525b0931e9cf1cb8990d9b  $work/engine.zip" | sha256sum --check
    unzip -q "$work/engine.zip" -d "$work/engine"
fi
server=$(find "$work/engine" -type f -name llama-server -print -quit)
extra=()
if [[ "${MANGALENS_LIVE_VISION:-0}" == 1 ]]; then
    model=qwen3-vl-8b-instruct-q4_k_m
    revision=00e7d63528e65d7b64e80e1293a8360b4af6a594
    base="https://huggingface.co/Qwen/Qwen3-VL-8B-Instruct-GGUF/resolve/$revision"
    model_file="$work/Qwen3VL-8B-Instruct-Q4_K_M.gguf"
    projector="$work/mmproj-Qwen3VL-8B-Instruct-Q8_0.gguf"
    fetch "$base/$(basename "$model_file")" "$model_file"
    fetch "$base/$(basename "$projector")" "$projector"
    echo "67d1659bfe71b89d50b45a4ad1a9e5b997e5bb16ce5da66a6a6167abd569e9e2  $model_file" | sha256sum --check
    echo "c6ba85508d82f42590e6eb77d5340369ab6fecf107a7561d809523d8aa5f3bfd  $projector" | sha256sum --check
    extra=(--mmproj "$projector")
else
    model=qwen2.5-7b-instruct-q4_k_m
    revision=bb5d59e06d9551d752d08b292a50eb208b07ab1f
    base="https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/$revision"
    model_file="$work/$model-00001-of-00002.gguf"
    tail_file="$work/$model-00002-of-00002.gguf"
    fetch "$base/$(basename "$model_file")" "$model_file"
    fetch "$base/$(basename "$tail_file")" "$tail_file"
    echo "dfce12e3862a5283ccfb88221b48480e58745165de856439950d0f22590580db  $model_file" | sha256sum --check
    echo "539cf93f78e887edea1c04e2d7d8cdaca9d01dae9c9025bcb8accbe29df3d72a  $tail_file" | sha256sum --check
fi
export MANGALENS_LIVE_ENDPOINT=http://127.0.0.1:7878/v1/chat/completions
export MANGALENS_LIVE_MODEL="$model"
start_model() {
    LD_LIBRARY_PATH="$(dirname "$server")" "$server" -m "$model_file" "${extra[@]}" --alias "$model" -c 8192 -t 4 -np 1 \
        --host 127.0.0.1 --port 7878 > "$work/server.log" 2>&1 &
    server_pid=$!
    for ((attempt=0; attempt<90; attempt++)); do
        if curl -fsS http://127.0.0.1:7878/health > /dev/null 2>&1; then break; fi
        if ! kill -0 "$server_pid" 2>/dev/null; then tail -40 "$work/server.log"; exit 1; fi
        sleep 2
    done
    curl -fsS http://127.0.0.1:7878/health
}

stop_model() {
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
    server_pid=
}

check_panel_requests() {
    python3 - "$work/server.log" "$MANGALENS_LIVE_DEVICE_OUT" <<'PY'
import json, re, sys
from pathlib import Path
log = Path(sys.argv[1]).read_text(errors="replace")
out_dir = Path(sys.argv[2])
records = [json.loads(row) for row in (out_dir / "model-requests.jsonl").read_text().splitlines()]
pages = [r for r in records if r["images"]]
reviews = [r for r in records if not r["images"]]
assert len(pages) == 2 and all(r["completed"] and r["image_bytes"] > 0 for r in pages), (
    "Each zoomed panel must receive one completed image request", records)
assert 1 <= pages[0]["images"] <= 2 and pages[1]["images"] == 1, (
    "The contextual panel may keep one detailed close-up and the ordinary zoom uses one image", records)
assert records[0] == pages[0] and records[-1] == pages[1] and len(reviews) <= 1, (
    "The ordinary follow-up balloon must finish with its single image request", records)
assert all(r["purpose"] == "forms_of_address" and r["review_items"] == 1 and r["completed"] for r in reviews), (
    "Only the ambiguous address may receive a small text-only review", records)
assert all(r["regions"] >= 1 and r["ocr_hints"] == 0 for r in pages), (
    "These zoomed balloons must be read from the lettering in the images", records)
assert pages[0]["story_entries"] == 0 and pages[1]["story_entries"] > 0, (
    "The second frame must use the model's own preceding dialogue", records)
images = [int(value) for value in re.findall(r"image (?:processed|slice encoded) in (\d+) ms", log)]
report = {"android_requests": records, "image_processing_ms": images}
out = out_dir / "model-timing.json"
out.write_text(json.dumps(report, indent=2) + "\n")
print(report)
PY
}

export MANGALENS_LIVE_DEVICE_OUT=${MANGALENS_LIVE_DEVICE_OUT:-app/build/reader-live-device}
case "${MANGALENS_LIVE_COMPARE_ORDER:-single}" in
    baseline-first) arms=(baseline candidate) ;;
    candidate-first) arms=(candidate baseline) ;;
    single) arms=(single) ;;
    *) echo "Unknown model comparison order" >&2; exit 2 ;;
esac
report_root=$MANGALENS_LIVE_DEVICE_OUT
for arm in "${arms[@]}"; do
    if [[ "$arm" != single ]]; then
        export MANGALENS_LIVE_DEVICE_OUT="$report_root/$arm"
        if [[ "$arm" == baseline ]]; then
            export MANGALENS_READER_SOURCE=previous-reader
        else
            export MANGALENS_READER_SOURCE=.
        fi
    fi
    # Fresh model process for each build, so the other arm's images, answers and KV cache cannot leak into it.
    start_model
    run_test "$@"
    if [[ "${MANGALENS_LIVE_VISION:-0}" == 1 && $# -gt 0 ]]; then check_panel_requests; fi
    stop_model
done
