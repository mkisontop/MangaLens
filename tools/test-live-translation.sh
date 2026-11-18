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
engine="https://github.com/ggml-org/llama.cpp/releases/download/b6752/llama-b6752-bin-ubuntu-x64.zip"
fetch() { curl -fsSL --retry 3 --retry-all-errors --connect-timeout 20 --max-time 720 "$1" -o "$2"; }
fetch "$engine" "$work/engine.zip"
echo "31cad32d9e191505ddf0c0e1f8ff2ab9f6ff64dbe9525b0931e9cf1cb8990d9b  $work/engine.zip" | sha256sum --check
unzip -q "$work/engine.zip" -d "$work/engine"
server=$(find "$work/engine" -type f -name llama-server -print -quit)
extra=()
if [[ "${MANGALENS_LIVE_VISION:-0}" == 1 ]]; then
    model=qwen2.5-vl-7b-instruct-q4_k_m
    revision=508edd0afaa66bb9e9f40587acc2184f02daf1f6
    base="https://huggingface.co/ggml-org/Qwen2.5-VL-7B-Instruct-GGUF/resolve/$revision"
    model_file="$work/Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf"
    projector="$work/mmproj-Qwen2.5-VL-7B-Instruct-Q8_0.gguf"
    fetch "$base/$(basename "$model_file")" "$model_file"
    fetch "$base/$(basename "$projector")" "$projector"
    echo "9258bf05b12686d097ff3b6b18d968ab393649780aa2b3cd67fec43d50554392  $model_file" | sha256sum --check
    echo "2ddb555391bae966e412deab9e07b58afa18bcc06930ba0f1c78a3695ab9e506  $projector" | sha256sum --check
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
LD_LIBRARY_PATH="$(dirname "$server")" "$server" -m "$model_file" "${extra[@]}" --alias "$model" -c 8192 -t 4 -np 1 \
    --host 127.0.0.1 --port 7878 > "$work/server.log" 2>&1 &
server_pid=$!
for ((attempt=0; attempt<90; attempt++)); do
    if curl -fsS http://127.0.0.1:7878/health > /dev/null 2>&1; then break; fi
    if ! kill -0 "$server_pid" 2>/dev/null; then tail -40 "$work/server.log"; exit 1; fi
    sleep 2
done
curl -fsS http://127.0.0.1:7878/health
export MANGALENS_LIVE_ENDPOINT=http://127.0.0.1:7878/v1/chat/completions
export MANGALENS_LIVE_MODEL="$model"
run_test "$@"
