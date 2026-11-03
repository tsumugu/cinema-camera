#!/bin/sh
# Small bootstrap used because this workspace has no Gradle installation.
# Downloads only the pinned official Gradle release, checking its published SHA256.
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
gradle_version=8.13
tool_dir="$project_dir/.toolchain"
gradle_bin="$tool_dir/gradle-$gradle_version/bin/gradle"
if [ ! -x "$gradle_bin" ]; then
    mkdir -p "$tool_dir"
    archive="$tool_dir/gradle-$gradle_version-bin.zip"
    checksum="$archive.sha256"
    base_url="https://services.gradle.org/distributions/gradle-$gradle_version-bin.zip"
    curl --fail --location --connect-timeout 15 --max-time 300 "$base_url" -o "$archive"
    curl --fail --location --connect-timeout 15 --max-time 30 "$base_url.sha256" -o "$checksum"
    expected=$(tr -d '[:space:]' < "$checksum")
    if command -v sha256sum >/dev/null 2>&1; then
        actual=$(sha256sum "$archive" | cut -d ' ' -f 1)
    else
        actual=$(shasum -a 256 "$archive" | cut -d ' ' -f 1)
    fi
    if [ "$actual" != "$expected" ]; then
        rm -f "$archive"
        echo 'Gradle download checksum mismatch' >&2
        exit 1
    fi
    unzip -q -o "$archive" -d "$tool_dir"
fi
exec "$gradle_bin" -p "$project_dir" "$@"
