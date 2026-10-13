# RumiTX v2.3.1 — TX_AUDIO bridge

**BASE:** https://github.com/Doug815/RumiTX/tree/v2.3.1-source
**FEATURE:** feature/tx-audio-v231

The public main branch is outdated. Never downgrade the DOOGEE S80. This
branch preserves v2.3.1 TAK positions, emergency beacon, GeoChat/messaging,
repeater markers, maps, holo UI, HIDL and PTT helper functionality.

Application ID: com.rumitx.app
Bridge update versionCode: 20301032
Bridge update versionName: 2.3.1-txbridge

Do not install a debug-signed APK over the existing release-signed app. Build
with the SAME original v2.3.1 signing certificate, stored off-repository.
Do not uninstall the installed app to work around a signature mismatch.

## Integration

ThinkPad Omarchy (qwen3:4b verified; voice agent and dashboard built)
 -> TTS 16 kHz mono PCM WAV (max 15 seconds)
 -> tools/tx_audio_client.py HMAC request
 -> USB ADB -> com.rumitx.app.TX_AUDIO dynamic RadioService receiver
 -> singleton RadioController programChannel/pttDown/pttUp
 -> MediaPlayer audio via speaker -> acoustic microphone pickup -> DMR vocoder.

The preexisting /dev/ttyMT1 at 57600 8N1 uses binary DMR007. The helper
/data/local/tmp/rumitx-ptt at localhost:45921 is SINGLE CLIENT. The bridge
creates no second helper socket or serial connection. RadioService owns
the existing singleton. The PttDispatcher blocks competing radio commands
and PTT-down during bridge TX; PTT-up remains available.

## Remote command API

Action: com.rumitx.app.TX_AUDIO
All command extras are STRING fields:
  audio   absolute file under /data/user/0/com.rumitx.app/files/voice_bridge/audio/
  tx      MHz decimal; required
  rx      optional RX MHz (defaults TX)
  digital optional, only 1 in this version
  cc      optional 0-15, default 1
  tg      optional 1-16777215, default 99

Authentication extras:
  issued  13-digit UTC epoch milliseconds, <=90-second clock skew
  nonce   32 lowercase hex characters (persisted, replay prevented)
  sha256  64 lowercase hex characters (entire WAV file digest)
  mac     HMAC-SHA256 signature as 64 hex characters

Signature canonical UTF-8 payload is these 10 newline-separated items:
TX_AUDIO_V1, audio, tx, rx, digital, cc, tg, issued, nonce, sha256.
The client signs exact values and the server validates SHA-256 and full
WAV contents before any PTT command.

Result action: com.rumitx.app.TX_RESULT, package-scoped. Contains success,
message, tx, sha256 and rf_verified=false. Remote observation:
adb logcat -s RumiTX-TX:I
Audit: private voice_bridge/tx_events.jsonl

## Security and RF authorization

The 400-470 MHz hardware tuning range does not confer authority to transmit.
An explicit operator-controlled file allowed_channels.csv must list every
allowed MHz,digital,color_code,talkgroup tuple, one per line, for example:

YOUR_ALLOWED_MHZ,1,1,99

Only use real MHz values allowed for the operator, mode and service.
No channel is enabled by default. Pair by running:
bash tools/provision_tx_audio.sh

The pairing process creates a 32-byte random shared key in tx_audio.key.
NEVER commit keys, allowlists, signing certificates or personal radio IDs.
The receiver fails closed without key and allowlist.

Bridge enforces authentication, replay prevention, strict 44-byte WAV
header at 16kHz, mono 16-bit, max 15 seconds, SHA, TX cooldown and
single-session radio lock. PTT-up always attempted in final cleanup.
Afterward it attempts to restore the previously selected operator channel.
A hardware/helper watchdog remains mandatory; process crash or a blocking
kernel call cannot be guaranteed safe by app code alone.

## Sender and tests

Produce canonical WAV:
ffmpeg -i input.wav -ac 1 -ar 16000 -sample_fmt s16 output.wav

Dry run (does NOT transmit):
python3 tools/tx_audio_client.py --wav output.wav --tx YOUR_ALLOWED_MHZ

Live on an explicitly permitted channel with a suitable receiver/test setup:
python3 tools/tx_audio_client.py --wav output.wav --tx YOUR_ALLOWED_MHZ --adb

The dynamic receiver exists only when RadioService is running; start
RumiTX once after boot, then no touchscreen actions are needed for requests.
Acoustic speaker-to-microphone coupling is experimental. MediaPlayer
completion is NOT proof of DMR audio on the air. SET_LOOPBACK_TYPE is NOT
enabled or validated in this revision. Use RTL-SDR V4 demodulation and
a listening receiver for actual end-to-end testing.

## Release gates

1. Verify this branch is derived from v2.3.1-source, never old main.
2. Confirm package ID and versionCode greater than installed v2.3.1.
3. Compile with Android SDK API 28, Gradle 7.5 and JDK 17.
4. Sign privately using the original release certificate (NOT debug key).
5. Compare signing cert SHA-256 against the installed phone APK.
6. Reject bad MAC, replay, old timestamp, bad audio hash, unauthorized
   channel, out-of-range frequency and simultaneous manual PTT without RF.
7. Observe PTT-down then PTT-up on valid test and all failure paths.
8. Confirm previous channel returns, RX audio, TAK, GeoChat, beacons,
   markers, maps, messages and channel bank continue functioning.
9. Prove acoustic audio works over-the-air with RTL-SDR V4. Report any
   failed or unverified conditions explicitly.

A GitHub Actions workflow produces a *debug test build* only if CI passes.
That artifact is not an installable upgrade to a differently-signed release.
No on-device or RF validation has yet been performed here.
