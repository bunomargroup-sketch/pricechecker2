#!/usr/bin/env bash
# =============================================================================
# BEN AMOR Price Checker — reproducible APK build (no Android Studio needed)
# =============================================================================
# Builds the SAME APK that Android Studio produces from this project, using
# only open toolchain components (see README "How the shipped APKs were
# built"). Windows users: just use Android Studio — this script is for
# Linux/CI and documents exactly how the prebuilt APKs in apk/ were made.
#
# Usage:
#   ./build-apk.sh            build release + debug APKs into apk/
#   BOOTSTRAP=1 ./build-apk.sh  also download/prepare the toolchain first
#
# Requirements: python3 + Pillow (icons), curl, git, gh (authenticated),
# unzip, zip. The toolchain is fetched into ~/.cache/android-toolchain.
# =============================================================================
set -euo pipefail

VERSION_NAME="1.0.1"
VERSION_CODE="2"

RELEASE_STORE_PASS="benamor-release-2024"
RELEASE_KEY_PASS="benamor-release-2024"
RELEASE_ALIAS="benamor"

HERE="$(cd "$(dirname "$0")" && pwd)"
TC="${TOOLCHAIN_DIR:-$HOME/.cache/android-toolchain}"
BUILD="$HERE/.build"
OUT="$HERE/apk"

ANDROID_JAR="$TC/sdk/platforms/android-34/android.jar"
AAPT2="$TC/bin/aapt2"
ZIPALIGN_BIN="$TC/bin/zipalign"
D8_JAR="$TC/jars/r8-master.jar"
APKSIGNER_JAR="$TC/jars/apksigner.jar"
JDK="$TC/jdk17repo/linux-x86"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

# -----------------------------------------------------------------------------
# Toolchain bootstrap (only what is missing)
# -----------------------------------------------------------------------------
bootstrap() {
  mkdir -p "$TC/bin" "$TC/jars" "$TC/src" "$TC/sdk/platforms/android-34"

  # aapt2 — from the aaptjs3 npm package (bundles official aapt2 binaries)
  if [ ! -x "$AAPT2" ]; then
    log "Fetching aapt2 (npm: aaptjs3)"
    local tmp; tmp="$(mktemp -d)"
    local url
    url="$(curl -fsS https://registry.npmjs.org/aaptjs3 \
      | python3 -c 'import json,sys;d=json.load(sys.stdin);print(d["versions"][d["dist-tags"]["latest"]]["dist"]["tarball"])')"
    curl -fsSL "$url" -o "$tmp/aaptjs3.tgz"
    tar xzf "$tmp/aaptjs3.tgz" -C "$tmp"
    cp "$tmp/package/bin/x64/linux/aapt2" "$AAPT2"; chmod +x "$AAPT2"
  fi

  # android.jar (API 34) — from the Sable/android-platforms mirror
  if [ ! -f "$ANDROID_JAR" ]; then
    log "Fetching android-34 platform jar"
    gh api -H "Accept: application/vnd.github.raw" \
      "repos/Sable/android-platforms/contents/android-34/android.jar?ref=master" \
      > "$ANDROID_JAR"
  fi

  # d8 — from the LineageOS mirror of AOSP prebuilts/r8 (r8-master.jar = D8)
  if [ ! -f "$D8_JAR" ]; then
    log "Fetching d8 (r8-master.jar)"
    gh api -H "Accept: application/vnd.github.raw" \
      "repos/LineageOS/android_prebuilts_r8/contents/r8-master.jar?ref=lineage-17.1" \
      > "$D8_JAR"
  fi

  # zipalign (+ its libc++.so) — from the LineageOS build-tools prebuilts
  if [ ! -x "$ZIPALIGN_BIN" ]; then
    log "Fetching zipalign"
    gh api -H "Accept: application/vnd.github.raw" \
      "repos/LineageOS/android_prebuilts_build-tools/contents/linux-x86/bin/zipalign?ref=lineage-21.0" \
      > "$ZIPALIGN_BIN"; chmod +x "$ZIPALIGN_BIN"
    gh api -H "Accept: application/vnd.github.raw" \
      "repos/LineageOS/android_prebuilts_build-tools/contents/linux-x86/lib64/libc%2B%2B.so?ref=lineage-21.0" \
      > "$TC/bin/libc++.so"
  fi

  # JDK 17 (javac) — sparse partial clone of the AOSP prebuilt JDK
  if [ ! -x "$JDK/bin/javac" ]; then
    log "Fetching OpenJDK 17 (AOSP prebuilts, sparse clone)"
    git clone --quiet --filter=blob:none --no-checkout --depth 1 \
      --branch android13-d1-release --single-branch \
      https://github.com/msft-mirror-aosp/platform.prebuilts.jdk.jdk17.git "$TC/jdk17repo"
    ( cd "$TC/jdk17repo" \
      && git sparse-checkout init --cone \
      && git sparse-checkout set linux-x86/bin linux-x86/lib linux-x86/conf linux-x86/release linux-x86/legal \
      && git checkout --quiet )
    chmod +x "$JDK/bin/"* || true
  fi

  # apksigner — compiled from the LineageOS mirror of AOSP tools/apksig
  if [ ! -f "$APKSIGNER_JAR" ]; then
    log "Building apksigner from source (AOSP tools/apksig)"
    if [ ! -d "$TC/src/apksig" ]; then
      git clone --quiet --depth 1 -b lineage-20.0 \
        https://github.com/LineageOS/android_tools_apksig "$TC/src/apksig"
    fi
    rm -rf "$TC/apksig-build"; mkdir -p "$TC/apksig-build/classes"
    ( cd "$TC/src/apksig" \
      && sed -i 's|Security.addProvider(new org.conscrypt.OpenSSLProvider());|/* conscrypt omitted (optional AOSP-only provider) */|' \
           src/apksigner/java/com/android/apksigner/ApkSignerTool.java \
      && find src/main/java src/apksigner/java -name "*.java" > "$TC/apksig-build/sources.txt" \
      && "$JDK/bin/javac" --release 11 -nowarn -d "$TC/apksig-build/classes" \
           @"$TC/apksig-build/sources.txt" \
      && cp src/apksigner/java/com/android/apksigner/help*.txt \
           "$TC/apksig-build/classes/com/android/apksigner/" )
    printf 'Main-Class: com.android.apksigner.ApkSignerTool\n' > "$TC/apksig-build/manifest.mf"
    "$JDK/bin/jar" --create --file "$APKSIGNER_JAR" \
      --manifest "$TC/apksig-build/manifest.mf" -C "$TC/apksig-build/classes" .
  fi
}

# -----------------------------------------------------------------------------
# Build
# -----------------------------------------------------------------------------
main() {
  if [ "${BOOTSTRAP:-0}" = "1" ]; then bootstrap; fi
  for f in "$AAPT2" "$ZIPALIGN_BIN" "$D8_JAR" "$APKSIGNER_JAR" "$ANDROID_JAR" "$JDK/bin/javac"; do
    [ -e "$f" ] || { echo "Missing toolchain piece: $f  (run: BOOTSTRAP=1 ./build-apk.sh)" >&2; exit 1; }
  done

  mkdir -p "$BUILD" "$OUT" "$HERE/keystore"

  log "Generating launcher icons"
  python3 "$HERE/scripts/make_icons.py"

  log "Generating keystores (first run only)"
  if [ ! -f "$HERE/keystore/benamor-release.jks" ]; then
    "$JDK/bin/keytool" -genkeypair -keystore "$HERE/keystore/benamor-release.jks" \
      -alias "$RELEASE_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass "$RELEASE_STORE_PASS" -keypass "$RELEASE_KEY_PASS" \
      -dname "CN=BEN AMOR GROUP, OU=IT, O=BEN AMOR GROUP, L=Tripoli, C=LY"
  fi
  if [ ! -f "$HERE/keystore/debug.keystore" ]; then
    "$JDK/bin/keytool" -genkeypair -keystore "$HERE/keystore/debug.keystore" \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass android -keypass android \
      -dname "CN=Android Debug,O=Android,C=US"
  fi

  log "Compiling resources (aapt2)"
  rm -rf "$BUILD"; mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"
  # Standalone aapt2 needs package= in the manifest, while AGP 8 (Android
  # Studio) forbids it — inject it into a build-time copy.
  sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    package="com.benamorgroup.pricechecker">|' \
    "$HERE/app/src/main/AndroidManifest.xml" > "$BUILD/AndroidManifest.xml"
  grep -q 'package="com.benamorgroup.pricechecker"' "$BUILD/AndroidManifest.xml" \
    || { echo "Failed to inject package attribute into manifest copy" >&2; exit 1; }
  "$AAPT2" compile --dir "$HERE/app/src/main/res" -o "$BUILD/res.zip"
  "$AAPT2" link -o "$BUILD/app.unsigned.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$BUILD/AndroidManifest.xml" \
    --min-sdk-version 24 --target-sdk-version 34 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    --java "$BUILD/gen" \
    "$BUILD/res.zip"

  log "Compiling Java (javac, target 8)"
  find "$HERE/app/src/main/java" "$BUILD/gen" -name "*.java" > "$BUILD/sources.txt"
  # --release 8 with android.jar on the classpath: java.* comes from the
  # Java 8 platform (fully covered by Android API 24+), android.* from the
  # platform jar. Lambdas/method-refs are desugared by d8 in the next step.
  "$JDK/bin/javac" --release 8 -encoding UTF-8 -nowarn \
    -classpath "$ANDROID_JAR" \
    -d "$BUILD/classes" @"$BUILD/sources.txt"

  log "Dexing (d8)"
  find "$BUILD/classes" -name "*.class" -print0 \
    | xargs -0 "$JDK/bin/java" -cp "$D8_JAR" com.android.tools.r8.D8 \
        --release --lib "$ANDROID_JAR" --min-api 24 --output "$BUILD/dex"

  log "Packaging APK"
  ( cd "$BUILD/dex" && zip -q -X ../app.unsigned.apk classes.dex )
  LD_LIBRARY_PATH="$TC/bin" "$ZIPALIGN_BIN" -f 4 \
    "$BUILD/app.unsigned.apk" "$BUILD/app.aligned.apk"

  sign() { # $1 keystore  $2 alias  $3 pass  $4 outfile
    "$JDK/bin/java" -cp "$APKSIGNER_JAR" com.android.apksigner.ApkSignerTool sign \
      --ks "$1" --ks-key-alias "$2" \
      --ks-pass "pass:$3" --key-pass "pass:$3" \
      --out "$4" "$BUILD/app.aligned.apk"
  }

  log "Signing release APK"
  sign "$HERE/keystore/benamor-release.jks" "$RELEASE_ALIAS" \
       "$RELEASE_STORE_PASS" "$OUT/BenAmorPriceChecker-$VERSION_NAME-release.apk"

  log "Signing debug APK"
  sign "$HERE/keystore/debug.keystore" androiddebugkey android \
       "$OUT/BenAmorPriceChecker-$VERSION_NAME-debug.apk"

  log "Verifying"
  "$JDK/bin/java" -cp "$APKSIGNER_JAR" com.android.apksigner.ApkSignerTool \
    verify --print-certs "$OUT/BenAmorPriceChecker-$VERSION_NAME-release.apk" | head -6
  "$AAPT2" dump badging "$OUT/BenAmorPriceChecker-$VERSION_NAME-release.apk" | head -12

  log "Done:"
  ls -la "$OUT"
}

main "$@"
