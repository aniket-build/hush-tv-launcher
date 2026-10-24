#!/bin/bash
# Builds build/hush.apk with the bare Android SDK tools (no Gradle, no libraries).
#
# Needs: JDK 17 and the Android SDK with "platforms;android-28" and any "build-tools;3x".
# The first build creates a private signing key in keystore/ (never commit it; app updates must
# be signed with the same key, so back it up).
set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || ls -d ~/.local/jdk-17*/Contents/Home 2>/dev/null | head -1 || true)
fi
[ -x "${JAVA_HOME:-}/bin/javac" ] || { echo "JDK 17 not found: set JAVA_HOME" >&2; exit 1; }
export JAVA_HOME

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
for guess in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do [ -z "$SDK" ] && [ -d "$guess" ] && SDK="$guess"; done
[ -d "$SDK" ] || { echo "Android SDK not found: set ANDROID_SDK_ROOT" >&2; exit 1; }
BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
JAR="$SDK/platforms/android-28/android.jar"
[ -f "$JAR" ] || { echo "Missing platform: run  sdkmanager \"platforms;android-28\"" >&2; exit 1; }
[ -x "$BT/aapt2" ] || { echo "Missing build-tools: run  sdkmanager \"build-tools;34.0.0\"" >&2; exit 1; }

rm -rf build && mkdir -p build/gen build/classes

echo "== resources"
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml \
    --java build/gen --min-sdk-version 24 --target-sdk-version 28 build/res.zip

echo "== java"
"$JAVA_HOME/bin/javac" -nowarn -Xlint:-options -source 8 -target 8 \
    -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" \
    -d build/classes $(find src build/gen -name '*.java')

echo "== dex"
"$BT/d8" --release --min-api 24 --lib "$JAR" --output build $(find build/classes -name '*.class')

echo "== package"
cp build/base.apk build/unsigned.apk
(cd build && zip -q -j unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

echo "== sign"
mkdir -p keystore
if [ ! -f keystore/hush.jks ]; then
    PW=$(openssl rand -hex 16)
    echo "$PW" > keystore/password.txt && chmod 600 keystore/password.txt
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore keystore/hush.jks -alias hush \
        -keyalg RSA -keysize 2048 -validity 10000 -storepass "$PW" -keypass "$PW" \
        -dname "CN=Hush TV Launcher" >/dev/null 2>&1
fi
PW=$(cat keystore/password.txt)
"$BT/apksigner" sign --ks keystore/hush.jks --ks-pass "pass:$PW" --out build/hush.apk build/aligned.apk
"$BT/apksigner" verify build/hush.apk

ls -l build/hush.apk | awk '{print "== built", $NF, $5, "bytes"}'
