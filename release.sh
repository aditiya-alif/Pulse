#!/usr/bin/env bash
# Produce a signed release APK.
#
#   KS_PASS=... KEY_PASS=... VERSION_CODE=3 VERSION_NAME=2.1.0 ./release.sh
#
# Signing material is read from the environment and is never written into the
# repository. Point KEYSTORE at your release keystore; the debug keystore is used
# only if you have not set one, and the script says so loudly because an APK
# signed with a debug key cannot be published and can be forged by anyone.
#
# Output: release/Pulse-<version>.apk
set -euo pipefail

REPO="$(cd "$(dirname "$0")" && pwd)"
VERSION_NAME="${VERSION_NAME:-}"
VERSION_CODE="${VERSION_CODE:-}"

if [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ]; then
  echo "error: set VERSION_NAME and VERSION_CODE" >&2
  echo "  e.g. VERSION_NAME=2.0.0 VERSION_CODE=3 ./release.sh" >&2
  exit 2
fi

if [ -z "${KEYSTORE:-}" ]; then
  echo "WARNING: KEYSTORE is not set; falling back to the debug keystore." >&2
  echo "         This APK cannot be published and anyone can sign as you." >&2
fi

OUT="$REPO/release"
mkdir -p "$OUT"

VERSION_NAME="$VERSION_NAME" VERSION_CODE="$VERSION_CODE" \
  "$REPO/build_apk.sh" "$REPO" "$REPO/build"

APK="$REPO/build/app-debug.apk"
DEST="$OUT/Pulse-$VERSION_NAME.apk"
cp "$APK" "$DEST"

echo
echo "=== release APK ==="
ls -la "$DEST"
echo "sha256: $(sha256sum "$DEST" | cut -d' ' -f1)"
echo
echo "Install:  adb install -r -d $DEST"
echo "Tag:      git tag -a v$VERSION_NAME -m \"Pulse $VERSION_NAME\" && git push origin v$VERSION_NAME"
