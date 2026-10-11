# RumiTX

![RumiTX logo](assets/rumitx-logo.webp)

An open replacement for the stock Talkie app on the **DOOGEE S80** rugged phone —
unlocking the real DMR digital radio hardware inside it.

The S80 ships with a genuine DMR transceiver module (DMR007) on its serial port,
but the stock app is the only key to it, and it's... not great. RumiTX talks to
the module directly using its native binary protocol: program channels with
separate TX/RX frequencies, DMR color codes and talkgroups, key the transmitter
from an on-screen or physical PTT button, and send DMR text messages.

> **Status: private testing.** The protocol is decoded and RF-verified
> (transmissions captured on SDR), and the app's radio layer is rewritten around
> it. On-phone testing is in progress. This repo will go public once the app is
> stable and installs cleanly.

## What works so far

- Full DMR007 binary protocol decoded from the stock app: channel programming
  (`68 36` digital / `68 35` analog), TX start/stop (`68 06`), DMR SMS
  (`68 07`), volume, auto-power, and query frames — checksums verified
- Module UART confirmed at **57600 baud 8N1** (not 9600)
- RF verified with an RTL-SDR: programmed 446.5555 MHz, keyed ~9 s, clean
  +20 dB narrowband DMR 4FSK carrier, on frequency
- DMR SMS send verified over the air (burst length scales with message length)
- `Dmr007Frame.java` — exact Java port of the frame builders, self-tested
  against stock-verified vectors
- `RadioController.java` — binary-protocol radio controller (replaces the old
  AT-command layer)
- Physical PTT side-button mapping decoded (KEYCODE_F9)

See [DMR007-PROTOCOL.txt](DMR007-PROTOCOL.txt) for the full protocol
documentation and [rumitx-spec.txt](rumitx-spec.txt) for the hardware/software
spec.

## Layout

- `app/` — Android app source (package `com.rumitx.app`)
- `tools/dmr007_frame.py` — Python reference implementation of every frame
  builder, with self-tests (start here to understand the protocol)
- `tools/analyze_cap.py`, `tools/goertzel.py` — SDR capture analysis helpers
- `native/ptt_client.cpp` — native PTT/ioctl helper source
- `docs/` — protocol documentation
- `build-apk.sh` — manual APK build script (no Gradle daemon needed)

## Building

Prerequisites: Android SDK build-tools 28.0.3 and `android-28/android.jar`,
plus a JDK. Set `RUMITX_SDK` to your SDK path (defaults to
`$REPO/../rumitx/android-sdk`):

```sh
RUMITX_SDK=/path/to/android-sdk ./build-apk.sh
```

This produces a signed debug APK. A release signing key is **not** in this repo
— generate your own before publishing.

## Installing / testing

RumiTX targets the DOOGEE S80 (Android 8.1) with root. The radio module lives
on `/dev/ttyMT1` and PTT goes through `/dev/hctintercom_node` ioctls, so the
app needs root/su access on current builds. Graceful device checks and a
no-root-required install path are on the roadmap before public release.

## Legal / safety

**Only transmit where you are licensed or authorized to do so.** The S80's
400–480 MHz range covers amateur and commercial allocations that require a
license in most countries. Test with an SDR receiver before transmitting, and
never key the transmitter blindly.

## License

To be chosen before public release.
