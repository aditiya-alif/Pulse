#!/usr/bin/env bash
# Build Pulse without Gradle.
#
# The pipeline is the Android toolchain directly:
#   aapt2 compile -> aapt2 link -> javac -> d8 -> zip -> zipalign -> apksigner
#
# Why not Gradle: this project is built on-device from a terminal, with no
# Android Studio and no daemon. aapt2, d8, apksigner and javac are enough for an
# app with no dependencies.
#
# Requirements, all discoverable through $PATH or the environment:
#   aapt2, d8, apksigner, javac   - Android SDK build-tools + a JDK
#   python3                       - for repack.py and zipalign.py
#   an android.jar                - set ANDROID_JAR if not at the default
#   a keystore                    - set KEYSTORE (defaults to ./debug.keystore)
#
# Signing credentials come from the environment, never from the repository:
#   KEYSTORE        path to the keystore
#   KS_PASS         keystore password   (default "android")
#   KEY_PASS        key password        (defaults to KS_PASS)
#   KS_ALIAS        key alias           (default "androiddebugkey")
#
# Usage:
#   ./build_apk.sh [project-dir] [output-dir]
#
# project-dir is the directory holding AndroidManifest.xml. If you point it at a
# repository root that contains app/, the app/ module is used automatically.
set -euo pipefail

PROJ="$(cd "${1:-$(dirname "$0")}" && pwd)"
REPO="$PROJ"
if [ ! -f "$PROJ/AndroidManifest.xml" ] && [ -f "$PROJ/app/AndroidManifest.xml" ]; then
  PROJ="$PROJ/app"
fi
OUT="${2:-$PROJ/build}"

# Tools. Override any of these with an environment variable of the same name.
AAPT2="${AAPT2:-aapt2}"
D8="${D8:-d8}"
APKSIGNER="${APKSIGNER:-apksigner}"
JAVAC="${JAVAC:-javac}"
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_HOME/platforms/android.jar}"
[ -f "$ANDROID_JAR" ] || ANDROID_JAR="$ANDROID_SDK_ROOT/platforms/android.jar"
KEYSTORE="${KEYSTORE:-$REPO/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KEY_PASS="${KEY_PASS:-$KS_PASS}"
KS_ALIAS="${KS_ALIAS:-androiddebugkey}"

MIN=26
TGT=34

# Version overrides. Both are written into the manifest at link time, so a release
# can be stamped without editing the file by hand:
#   VERSION_CODE=3 VERSION_NAME=2.1.0 ./build_apk.sh
VERSION_CODE="${VERSION_CODE:-}"
VERSION_NAME="${VERSION_NAME:-}"
VERSION_FLAGS=()
if [ -n "$VERSION_CODE" ] || [ -n "$VERSION_NAME" ]; then
  # --replace-version is required: without it aapt2 only injects the version when
  # the manifest declares none, and this manifest does declare one.
  VERSION_FLAGS+=(--replace-version)
  [ -n "$VERSION_CODE" ] && VERSION_FLAGS+=(--version-code "$VERSION_CODE")
  [ -n "$VERSION_NAME" ] && VERSION_FLAGS+=(--version-name "$VERSION_NAME")
fi

cd "$PROJ"
rm -rf "$OUT"
mkdir -p "$OUT"/{compiled,classes,dex,gen}
# aapt2 link resolves the manifest relative to the working directory, so the
# project dir must be the cwd for the whole pipeline.

echo "[1/6] aapt2 compile resources"
if [ -d res ] && [ -n "$(find res -type f 2>/dev/null | head -1)" ]; then
  "$AAPT2" compile --dir res -o "$OUT/compiled.zip"
  LINK_RES=(-R "$OUT/compiled.zip")
else
  LINK_RES=()
fi

echo "[2/6] aapt2 link -> base.apk + R.java"
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN" \
  --target-sdk-version "$TGT" \
  --auto-add-overlay \
  "${VERSION_FLAGS[@]}" \
  "${LINK_RES[@]}"

echo "[3/6] javac (capped heap)"
SRC=$(find src "$OUT/gen" -name '*.java' 2>/dev/null)
# android.jar is on -classpath, not -bootclasspath: lambdas need the JDK's
# LambdaMetafactory, which android.jar does not ship. d8 desugars java 8 down.
"$JAVAC" -nowarn -encoding UTF-8 \
  -source 8 -target 8 \
  -J-Xmx768m -J-XX:+UseSerialGC \
  -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" $SRC > "$OUT/javac.log" 2>&1 || {
    echo "javac failed:"; tail -40 "$OUT/javac.log"; exit 3; }
grep -v "bootstrap class path\|source value 8\|target value 8\|deprecat" "$OUT/javac.log" || true
if [ -z "$(find "$OUT/classes" -name '*.class' 2>/dev/null | head -1)" ]; then
  echo "javac produced no classes"; exit 3
fi

echo "[4/6] d8 -> dex"
CLASSES=$(find "$OUT/classes" -name '*.class')
"$D8" --min-api "$MIN" --lib "$ANDROID_JAR" --output "$OUT/dex" $CLASSES

echo "[5/6] repack"
# The base APK from aapt2 plus classes.dex plus lib/<abi>/*.so, assembled in
# Python. The Android `zip` utility is not used here on purpose: builds differ in
# whether an absolute archive path is honoured or treated as a member name, and
# the failure mode is a silently tiny APK.
REPACK_ARGS=("$OUT/base.apk" "$OUT/unsigned.apk" "classes.dex:$OUT/dex/classes.dex")
if [ -d assets ] && [ -n "$(find assets -type f 2>/dev/null | head -1)" ]; then
  while IFS= read -r f; do
    REPACK_ARGS+=("${f#./}:$PROJ/$f")
  done < <(cd assets && find . -type f)
fi
# Native libraries go in at lib/<abi>/: that is the one place Android still lets
# an app exec a bundled binary from, and it is how the bundled adb runs.
if [ -d jniLibs ] && [ -n "$(find jniLibs -type f 2>/dev/null | head -1)" ]; then
  while IFS= read -r f; do
    REPACK_ARGS+=("lib/${f#lib/}:$PROJ/jniLibs/$f")
  done < <(cd jniLibs && find lib -type f)
  echo "      jniLibs: $(find jniLibs -type f | wc -l) files, $(du -sh jniLibs | cut -f1)"
fi
python3 "$REPO/repack.py" "${REPACK_ARGS[@]}"
python3 "$REPO/zipalign.py" "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] apksigner sign"
"$APKSIGNER" sign \
  --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --ks-key-alias "$KS_ALIAS" \
  --min-sdk-version "$MIN" \
  --out "$OUT/app-debug.apk" "$OUT/aligned.apk"

echo "=== BUILD OK ==="
ls -la "$OUT/app-debug.apk"
