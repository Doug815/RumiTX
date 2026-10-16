#!/usr/bin/env bash
# Sign RumiTX v2.3.1 with the original ThinkPad keystore, not any temporary key.
# Usage: bash tools/sign_v231_for_s80.sh unsigned.apk original-signed.apk
set -euo pipefail
[[ $# -ge 2 ]] || { echo "Usage: $0 unsigned.apk previous-signed.apk" >&2; exit 1; }
UNSIGNED="$1"
REFERENCE="$2"
KEYSTORE="$HOME/rumitx-work/signing/local-debug.keystore"
STAGING="$HOME/RumiTX-staging"
mkdir -p "$STAGING"
for command_name in zipalign apksigner aapt sha256sum; do
  command -v "$command_name" >/dev/null || { echo "ERROR: $command_name not in PATH" >&2; exit 1; }
done
[[ -r "$UNSIGNED" && -r "$REFERENCE" && -r "$KEYSTORE" ]] || { echo "ERROR: APK or original private key missing" >&2; exit 1; }
EXPECTED_BADGING="package: name='com.rumitx.app' versionCode='20301031' versionName='2.3.1'"
ACTUAL_BADGING="$(aapt dump badging "$UNSIGNED" | head -n1)"
[[ "$ACTUAL_BADGING" == "$EXPECTED_BADGING"* ]] || { echo "ERROR: Unexpected package metadata: $ACTUAL_BADGING" >&2; exit 1; }
REF_CERT="$(apksigner verify --print-certs "$REFERENCE" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)"
[[ -n "$REF_CERT" ]] || { echo "ERROR: Reference signing certificate not found" >&2; exit 1; }
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT
zipalign -f -p 4 "$UNSIGNED" "$TMPDIR/aligned.apk"
SIGNED="$TMPDIR/signed.apk"
apksigner sign --ks "$KEYSTORE" --out "$SIGNED" "$TMPDIR/aligned.apk"
apksigner verify --verbose --print-certs "$SIGNED"
SIGNED_CERT="$(apksigner verify --print-certs "$SIGNED" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)"
if [[ "$SIGNED_CERT" != "$REF_CERT" ]]; then
  echo "CRITICAL: SIGNATURE MISMATCH. Staging stopped. Do not install." >&2; exit 1
fi
OUTPUT="$STAGING/RumiTX-v2.3.1-release.apk"
cp "$SIGNED" "$OUTPUT"
echo "MATCHED SIGNING CERTIFICATE SHA-256: $SIGNED_CERT"
aapt dump badging "$OUTPUT" | head -n1
sha256sum "$OUTPUT"
echo "Staged: $OUTPUT"
echo "WARNING: versionCode 20301031 cannot update newer installations (e.g. 20301047)."
echo "DO NOT uninstall a newer version to bypass this check; app data could be lost."
echo "If version-compatible, run: adb install -r '$OUTPUT'"
echo "Then launch: adb shell am start -n com.rumitx.app/.MainActivity"
echo "Live channels, PTT, Voice TX guard still require hands-on testing."
