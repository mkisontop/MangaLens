#!/usr/bin/env bash
set -euo pipefail

operation=$1
directory=$2
shift 2
case "$operation" in
    build)
        log=$(mktemp)
        trap 'rm -f "$log"' EXIT
        # An unavailable dependency can affect the whole device matrix. Retry only
        # Gradle's dependency-resolution failure, never a failed build or test.
        if "$directory/gradlew" -p "$directory" "$@" 2>&1 | tee "$log"; then
            exit 0
        else
            status=${PIPESTATUS[0]}
        fi
        if ! grep -Eq 'Could not resolve all (files|artifacts|dependencies) for configuration' "$log"; then
            exit "$status"
        fi
        sleep 15
        "$directory/gradlew" -p "$directory" --refresh-dependencies "$@"
        ;;
    pack)
        mkdir -p "$directory"
        files=(app/build/outputs/apk/debug/app-debug.apk
               app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
               app/build/reader-fixtures/potion-contest-page.jpg "$@")
        sha256sum "${files[@]}" > "$directory/reader-builds.sha256"
        tar -cf "$directory/reader-builds.tar" "${files[@]}"
        ;;
    unpack)
        tar -xf "$directory/reader-builds.tar"
        sha256sum --check "$directory/reader-builds.sha256"
        ;;
    *)
        echo "Expected build, pack or unpack" >&2
        exit 2
        ;;
esac
