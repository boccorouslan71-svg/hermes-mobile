#!/usr/bin/env bash
set -euo pipefail

# Build-time helper. The Android APK intentionally does not fetch arbitrary code
# during runtime. Supply a pinned ARM64 bootstrap archive and optional Hermes
# executable, then this script stages bootstrap-aarch64.zip for Gradle assets.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/android/app/src/main/assets"
mkdir -p "$ASSETS"

: "${BOOTSTRAP_URL:=https://github.com/termux/termux-packages/releases/download/bootstrap-2024.06.14/bootstrap-aarch64.zip}"
: "${BOOTSTRAP_SHA256:=}"

if [[ ! -f "$ASSETS/bootstrap-aarch64.zip" ]]; then
  curl --fail --location --retry 3 "$BOOTSTRAP_URL" -o "$ASSETS/bootstrap-aarch64.zip"
fi
if [[ -n "$BOOTSTRAP_SHA256" ]]; then
  echo "$BOOTSTRAP_SHA256  $ASSETS/bootstrap-aarch64.zip" | sha256sum -c -
fi

if [[ -n "${HERMES_BINARY:-}" ]]; then
  install -Dm755 "$HERMES_BINARY" "$ASSETS/hermes/bin/hermes"
fi
printf 'Prepared pinned bootstrap in %s\n' "$ASSETS"
