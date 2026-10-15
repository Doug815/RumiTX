# Rumi AI Companion — Android 8+ standalone client

**Package:** com.rumitx.rumiai. Separate installation from com.rumitx.app (RumiTX); no replacement of its signature, APK, or radio components.

## Status / verified backend
Uses only the confirmed RumiTX FastAPI v0.2 protocol for /status, /infer, /command.
- GET /status: read token; response has service, version, llm, radio.
- POST /infer {"utterance":"switch to channel 5"}: read token. Returns {"action":{"intent":"set_channel","args":{"channel":5}},"confirmation_required":false,"executed":false}. NEVER executes.
- POST /command {"intent":"set_channel","args":{"channel":5}}: command token and Idempotency-Key; user confirmation, validator, controller. Never direct hardware; current backend simulator.
- Settings includes **optional chat endpoint path** (blank by default). V2 conversational protocol not verified. If entered, app calls POST path with {"message":"..."} and displays response fields answer/response/text/message. Adapt this as soon as V2 contract arrives. No claim of active conversation support until tested.

## Setup
1. Install Tailscale on the Android device, join tailnet.
2. Serve FastAPI using Tailscale Serve as described in the existing API.md. HTTPS cert must validate normally.
3. Launch Rumi AI > Settings; enter HTTPS Tailscale Serve URL without path (example: https://host.tailnet-name.ts.net). Paste server-issued READ and COMMAND tokens (32+ characters, distinct).
4. Save. Go to Diagnostics > Ping status.
5. Chat > enter "switch to channel five" > Interpret. Review normalized JSON, click Execute only if intended.
6. Android speech input uses RecognizerIntent with offline preference. True offline STT requires a local recognition engine installed on the device. Spoken answers use Android TTS; installed voice determines offline availability.

## Safeguards
- HTTPS-only; platform cert verification and hostname verification, no cleartext/TrustAll.
- Tokens encrypted in Android Keystore-backed AES/GCM; no tokens in diagnostics.
- No silent command execution, hardware IO, or PTT access.
- Only backend validator + controller controls changes. Frequency and DMR stage and never auto-transmit.
- Session-only diagnostic events without utterances or credentials.
- Service disconnect won't change channels.
- No health/first-aid guidance claims: server V2 knowledge endpoints not verified yet.

## Build
```bash
gradle -p rumi-ai-client :app:assembleDebug
```
Use JDK 17, Android SDK 35, Gradle 8.7. CI uses these versions. Debug APK is signed with GitHub runner debug key and is installable for testing but not for stable release/update signing.

## Limitations and test plan
- Voice input is provided by an installed Android recognizer, not custom V2 streaming STT.
- General conversational questions require a V2 endpoint and matching JSON response schema; optional /chat adapter is provisional.
- /status actual RF fields depend on live backend; v0.2 demo controller is simulation.
- Test on S80 for UI layout, permissions, latency, Tailscale reconnect, channel allowlist rejection, no automatic transmit, 401/403, and no loss of existing app. Android 8.1 phone testing is pending.
