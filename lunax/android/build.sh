#!/usr/bin/env bash
# Build & sign the Lunax APK from the raw Android SDK tools (no Gradle needed).
#
#   ANDROID_HOME=/path/to/sdk ./build.sh
#
# Produces:  build/lunax.apk  (debug-signed, installable)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WEB="$(cd "$HERE/.." && pwd)"                    # the lunax/ web app
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)"
ANDROID_JAR="$PLATFORM/android.jar"

AAPT2="$BT/aapt2"; D8="$BT/d8"; ZIPALIGN="$BT/zipalign"; APKSIGNER="$BT/apksigner"

OUT="$HERE/build"
rm -rf "$OUT"; mkdir -p "$OUT/assets" "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "· bundling web assets"
cp "$WEB"/index.html "$WEB"/styles.css "$WEB"/lunabasic.js "$WEB"/archive.js "$WEB"/app.js "$OUT/assets/"

echo "· compiling resources"
"$AAPT2" compile --dir "$HERE/res" -o "$OUT/compiled/res.zip"

echo "· linking resources + manifest + assets"
"$AAPT2" link \
    -o "$OUT/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$HERE/AndroidManifest.xml" \
    -A "$OUT/assets" \
    --java "$OUT/gen" \
    --min-sdk-version 26 --target-sdk-version 34 \
    --version-code 1 --version-name 1.0 \
    "$OUT/compiled/res.zip"

echo "· compiling Java"
javac -source 8 -target 8 -classpath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    "$HERE"/src/com/lunax/app/*.java \
    "$OUT"/gen/com/lunax/app/R.java

echo "· dexing"
"$D8" --lib "$ANDROID_JAR" --min-api 26 --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')

echo "· packaging dex into apk"
( cd "$OUT/dex" && zip -q -X "$OUT/base.apk" classes.dex )

echo "· zipalign"
"$ZIPALIGN" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "· signing"
KS="$OUT/debug.keystore"
keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias lunax -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Lunax, O=Lunax, C=JP" >/dev/null 2>&1
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/lunax.apk" "$OUT/aligned.apk"
"$APKSIGNER" verify --verbose "$OUT/lunax.apk" | head -3

echo ""
echo "✓ built: $OUT/lunax.apk"
ls -lh "$OUT/lunax.apk" | awk '{print "  size:", $5}'
