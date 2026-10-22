#!/usr/bin/env bash
# Run ONLY on the original ThinkPad with its existing signing key.
# Usage: bash tools/sign_llama_bundled.sh <unsigned-release.apk> <previous-signed-rumitx.apk>
set -euo pipefail
[[ $# -eq 2 ]] || { echo "Usage: $0 <unsigned.apk> <previous-signed.apk>" >&2; exit 2; }
UNSIGNED="$1"
REFERENCE="$2"
KEYSTORE="$HOME/rumitx-work/signing/local-debug.keystore"
OUTPUT="/tmp/RumiTX-v2.4.0-agent-api-20400000.apk"
for p in aapt apksigner zipalign sha256sum; do command -v "$p" >/dev/null || { echo "Missing tool: $p" >&2; exit 2; }; done
[[ -r "$KEYSTORE" && -r "$UNSIGNED" && -r "$REFERENCE" ]] || { echo "Missing APK or original signing keystore" >&2; exit 3; }
BADGING="$(aapt dump badging "$UNSIGNED" | head -1)"
echo "$BADGING"
[[ "$BADGING" == "package: name='com.rumitx.app' versionCode='20400000' versionName='2.4.0-agent-api'"* ]] || { echo "Wrong metadata — refusing to sign" >&2; exit 4; }
REFERENCE_CERT="$(apksigner verify --print-certs "$REFERENCE" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)"
[[ -n "$REFERENCE_CERT" ]] || { echo "Missing previous APK certificate" >&2; exit 4; }
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
zipalign -f -p 4 "$UNSIGNED" "$TEMP_DIR/aligned.apk"
apksigner sign --ks "$KEYSTORE" --out "$TEMP_DIR/signed.apk" "$TEMP_DIR/aligned.apk"
apksigner verify --verbose --print-certs "$TEMP_DIR/signed.apk"
NEW_CERT="$(apksigner verify --print-certs "$TEMP_DIR/signed.apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)"
[[ "$NEW_CERT" == "$REFERENCE_CERT" ]] || { echo "CRITICAL: signing key mismatch; refusing to stage output" >&2; exit 5; }
cp "$TEMP_DIR/signed.apk" "$OUTPUT"
echo "Verified signing certificate: $NEW_CERT"
echo "Output: $OUTPUT"
sha256sum "$OUTPUT"
echo "Device installation/testing has NOT been performed."
