#!/usr/bin/env bash
# Store the local Android debug keystore as ANDROID_DEBUG_KEYSTORE_BASE64.
# CI signs release APKs with it so an update installs over the previous build.
# The keystore bytes are piped to gh and are not printed.
#
# Run from anywhere: bash scripts/ci/set-github-secrets.sh
# Requires: gh, authenticated with permission to set Actions secrets.
# Override: ANDROID_DEBUG_KEYSTORE, REPO.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
REPO="${REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"
KEYSTORE="${ANDROID_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"

if [ ! -s "$KEYSTORE" ]; then
  echo "ERROR: missing or empty: $KEYSTORE" >&2
  exit 1
fi

keystore_b64="$(base64 -w0 "$KEYSTORE")"
if [ -z "$keystore_b64" ]; then
  echo "ERROR: could not encode $KEYSTORE" >&2
  exit 1
fi

printf '%s' "$keystore_b64" | gh secret set ANDROID_DEBUG_KEYSTORE_BASE64 --repo "$REPO"
echo "Set ANDROID_DEBUG_KEYSTORE_BASE64 on $REPO"
gh secret list --repo "$REPO"
