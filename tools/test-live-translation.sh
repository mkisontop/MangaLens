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
    if [[ -n "$server_pid" ]]; then kill "$server_pid" 2>/dev/null || true; fi
    rm -rf "$work"
}
trap cleanup EXIT
model=qwen2.5-7b-instruct-q4_k_m
revision=bb5d59e06d9551d752d08b292a50eb208b07ab1f
base="https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/$revision"
engine="https://github.com/ggml-org/llama.cpp/releases/download/b6752/llama-b6752-bin-ubuntu-x64.zip"
fetch() { curl -fsSL --retry 3 --retry-all-errors --connect-timeout 20 --max-time 720 "$1" -o "$2"; }

fetch "$engine" "$work/engine.zip"
fetch "$base/$model-00001-of-00002.gguf" "$work/$model-00001-of-00002.gguf"
fetch "$base/$model-00002-of-00002.gguf" "$work/$model-00002-of-00002.gguf"
(
    cd "$work"
    sha256sum --check <<SUMS
31cad32d9e191505ddf0c0e1f8ff2ab9f6ff64dbe9525b0931e9cf1cb8990d9b  engine.zip
dfce12e3862a5283ccfb88221b48480e58745165de856439950d0f22590580db  $model-00001-of-00002.gguf
539cf93f78e887edea1c04e2d7d8cdaca9d01dae9c9025bcb8accbe29df3d72a  $model-00002-of-00002.gguf
SUMS
    unzip -q engine.zip -d engine
)
server=$(find "$work/engine" -type f -name llama-server -print -quit)
model_file="$work/$model-00001-of-00002.gguf"
LD_LIBRARY_PATH="$(dirname "$server")" "$server" -m "$model_file" --alias "$model" -c 8192 -t 4 \
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
