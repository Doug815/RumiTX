#!/bin/bash
# Manual APK build for RumiTX (no Gradle needed; VM RAM too low for the Gradle daemon).
# Produces a signed debug APK at ~/workspace/rumitx/RumiTX-debug.apk
set -e
RUMITX="$(cd "$(dirname "$0")" && pwd)"
APP="$RUMITX/app/app/src/main"
SDK="${RUMITX_SDK:-$RUMITX/../rumitx/android-sdk}"
BT="$SDK/build-tools/28.0.3"
AJAR="$SDK/platforms/android-28/android.jar"
OUT="$RUMITX/build/manual-apk"
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt2 compile resources"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/compiled_res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2" link -o "$OUT/app-unsigned.apk" \
  -I "$AJAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$OUT/gen" \
  "$OUT/compiled_res.zip"

echo "[3/6] javac (app sources + generated R.java)"
find "$RUMITX/app/app/src/main/java" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -cp "$AJAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "[4/6] d8 -> classes.dex"
"$BT/d8" --min-api 26 --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class" | tr '\n' ' ')

echo "[5/6] add classes.dex to APK"
cp "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"
(cd "$OUT/dex" && zip -q "$OUT/app-aligned.apk" classes.dex)

echo "[6/6] zipalign + sign"
"$BT/zipalign" -f 4 "$OUT/app-aligned.apk" "$OUT/app-final.apk"
if [ ! -f "$RUMITX/debug.keystore" ]; then
  keytool -genkeypair -keystore "$RUMITX/debug.keystore" -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10950 -storepass android \
    -keypass android -dname "CN=Android Debug,O=Android,C=US" -noprompt
fi
"$BT/apksigner" sign --ks "$RUMITX/debug.keystore" --ks-pass pass:android \
  --out "$RUMITX/RumiTX-debug.apk" "$OUT/app-final.apk"
"$BT/apksigner" verify --print-certs "$RUMITX/RumiTX-debug.apk" | head -3
ls -la "$RUMITX/RumiTX-debug.apk"
echo "BUILD OK"
