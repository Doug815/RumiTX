# RumiTX 2030 — verified binary comparison and fresh-reboot recovery plan

**Input binaries actually inspected**

- `reference/stock-talkie-extract.zip`: `talkie.apk`, `HcTTalkie.odex`, `HcTTalkie.vdex`, `libtalkie_port.so`, `talkie.so`. Supplied by user from stock S80.
- `reference/RumiTX-debug-2026-10-11.apk`: reverted RumiTX build supplied by user.
- `reference/RumiTX_S80_HIDL_ARM64_BuildKit_2026-10-11.zip`: previously prepared source-only native HIDL client kit, not a compiled device ELF.

All observations refer to these supplied binaries, NOT an uninspected Git repository. The stock `.vdex` uses optimized/quickened invoke instructions, so the wrapper names are verified, but the complete runtime app startup ordering cannot be proven from this simple disassembly alone.

## Confirmed differences

| Concern | Stock HcTTalkie | Reverted RumiTX | Consequence |
|---|---|---|---|
| HIDL discovery | Uses generated `ITalkiePort` proxy and `SerialPortService` lifecycle methods | `MainActivity.lambda$testHidlAccess$1` reflects `HwBinder.getService(String,String)` and checks non-null | RumiTX logs `HIDL service FOUND` **without any hardware initialization** |
| HIDL lifecycle | `SerialPortService` has `talkieInit`, `talkieAppStart`, `talkieSpeakEnable`, `talkieSpeakDisable`, `talkieAppExit`, `talkieDeinit` | Reverted HIDL test invokes none of these | A previously initialized module can appear functional until reboot; missing fresh-boot initialization remains unverified hypothesis |
| Service/audio | Stock `talkieOpen()` and `talkieSpeakEnable()` participate in service/audio-focus and HIDL flow; HAL service controls kernel amp | No RX-enabling HIDL call in reverted `testHidlAccess()` | Audible chirp cannot reasonably be attributed to service discovery alone |
| Helper | Stock talks through vendor service; native HAL performs ioctl | `RadioController.connect()` requires TCP `127.0.0.1:45921`, `READY`, `start` success, `serial` success, then UART open | Each command/ACK must be traced separately on fresh boot |
| Channel programming | Stock issues module commands over serial | `programChannel()` sends an encoded frame and waits up to 5 s for a valid reply | Failure may mean a command/ACK/reader/parsing issue even if UART permissions are 0666 |
| PTT | Stock HIDL ppt_down/up | `pttDown()` sends a DMR007 frame first and only then sends daemon command `down 0` | A serial failure can mask an otherwise functional helper PTT path |
| RX volume | Stock `Fragment_speak.setVolume` uses 1..8 and `ATCommond.getAtSetvolume` with property-dependent ASCII vs digital format | Reverted `MainActivity` adjusts volume 1..8, calls `RadioController.setVolume` and `Dmr007Frame.buildVolume` (internal formatter clamps 0..10) | Module volume is separate from Android AudioManager; verify actual format and acknowledgements |
| App packaging | `talkie.apk` + odex/vdex and native libs are factory artifacts | 46 KiB RumiTX APK has no native `rumitx-ptt` helper inside | Reinstalling APK cannot itself restore helper executable, permissions, or daemon lifecycle |

### Strongest falsifiable diagnosis

1. Confirm the helper emits `READY`, and `start` and `serial` each return `OK` after reboot. `READY` is only the TCP greeting.
2. Confirm a supported read-only radio-module query returns an actual module response through `/dev/ttyMT1`. Opening the character device or changing its mode to 0666 does not prove the module is powered or listening.
3. Only when the base transport is good, confirm the HIDL service object, `talkie_init` return value, asynchronous `talkie_speak_enable` submission, callback, GPIO IRQ, and amplifier log **as separate stages**.
4. Do not issue APP_START from **both** daemon and HIDL unless the original firmware's idempotency and ordering are positively established. A double start may reset the same hardware state.
5. Do not infer TX from an app toast. The relevant confirmation is helper `down` ACK **plus** RF observation on an authorized test bench/channel.

### What is NOT proven

- The exact stock post-reboot call sequence: optimized VDEX method bodies include quickened vtable invocations; a more complete dequickening/trace or source is needed.
- Whether `talkie_init` should happen before the helper `start`, after it, or instead of it for this specific kernel build.
- Whether a blocking `SPEAK_ENABLE` occurs from a missing GPIO IRQ enable, missing module power, driver initialization, or a specific input state. Logs should be used to decide.
- The stock HIDL transaction method's success integers and callback integer semantics.
- Current live phone SELinux context, `/dev/ttyMT1` ownership and vendor service process state: user-supplied reports are contextual, not live tool verification.

## Recommended integration — minimal source changes in Rumi's repository

These instructions are for Rumi, who holds original *source*; an APK is not a substitute for a clean source tree.

**1) Extend `RadioController`** with a transport result containing a distinct success flag for every stage: TCP, greeting, `start` ACK, `serial` ACK, UART opened, supported module response. Do not set `connected=true` just because UART opened. Don't re-send `start` if it has already been acknowledged in this process's hardware lifecycle.

**2) Replace `MainActivity.testHidlAccess()`** with two separate controls:
- `Probe HIDL` — non-mutating `getService`, logs only “HIDL binder found”.
- `Initialize RX` — after the helper is healthy, runs the verified hardware sequence through an asynchronous vendor client, logs `init`, `speak_enable` submission, callback value, and IRQ/amp confirmation. Never do blocking driver/HIDL operations on the UI or TCP request thread.

**3) Keep one owner of APP_START.** Initially keep daemon TCP `start` (already present) as APP_START owner. Check whether HIDL `talkie_init` can be safely run afterwards and `talkie_speak_enable` can be submitted **without** a second `talkie_app_start`. If it fails, test an alternate *separate session* with HIDL owning initialization and APP_START while the daemon only provides UART and TX access. Do not combine both starts in one session.

**4) RX state should be reported independently** from HIDL reachable, helper ready, UART open, callback delivered, kernel IRQ seen, and audible output. Delivering the callback does not prove voice/static; audible RX confirms it. Preserve TX path until this is known.

**5) On explicit shutdown** call `speak_disable`; call `app_exit`/`deinit` only for state that the same component owns. If HIDL vendor driver is blocked, cleanup may block too—run off main thread with telemetry, do not claim process timeout resets vendor kernel state.

**6) Make boot readiness observable.** Add a visible diagnostics panel with each stage, return code, timing, and last error. Treat changed SELinux or device-node modes as exceptional diagnostics, not as a durable deployment fix. Service, init, ueventd and sepolicy ownership must be addressed for production.

**7) Build, deploy and verify** with Rumi's source and matching Android 8.1 platform tools. No reliable installable RumiTX APK can be produced from APK binaries + missing native daemon source alone. This package includes a tested portable Java coordination module, not a rebuilt app.

## Verification matrix after a clean reboot

| Test | Required signal | If it fails |
|---|---|---|
| Helper | `READY` and separate `start OK`, `serial OK` | Debug helper command protocol and ioctl/termios errors; no HIDL retries yet |
| Module | Successful query reply | Look for module power, UART speed, serial consumer conflicts and framing |
| HIDL | non-null binder handle | Check service registration and permissions |
| HIDL init | return code logged | Inspect HAL file descriptor/global state |
| RX enable | oneway submission + callback or timeout | Compare kernel `hct_eint_speaken_isr`, `hct_wait_speak_en`, amp logs |
| Audible RX | static/voice on authorized test bench | Verify volume, routing, squelch, IRQ, amplifier |
| TX | TX helper ACK + external RF observation | Test only on authorized test frequency; don't infer from UI status |

## Required inputs for a fully rebuilt APK

- Rumi's Gradle/Android source repository or a lossless enough decompilation project, Android SDK/platform API 27 and build scripts.
- Exact native helper source and compiled artifact; documented helper request/response protocol.
- The real `ITalkiePort` binding/transaction implementation used by Rumi now and logs from a failing fresh boot.
- Signing key/keystore only if updating an existing installation in place. **Do not put signing keys or passwords in AI handoff packages.**

## Important caveat about the portable recovery library

The `BootCoordinator` is a **tested Java-8 integration skeleton**, not a device driver or APK. It accepts `Helper` and `Hidl` adapter implementations that Rumi must wire to the current app. Default `HELPER` APP_START ownership means the coordinator intentionally avoids a second HIDL app-start. Its HIDL init-after-helper-start order is a *testable experiment*, not a verified stock startup order. Don't run its RX method in a production app before validation.
