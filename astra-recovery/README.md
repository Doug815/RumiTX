# RumiTX 2030 — Stock-vs-RumiTX source recovery handoff

**Build/package date:** 2026-10-11  
**Purpose:** pass a verified binary comparison and compile-tested recovery coordination code to Rumi, Scout, or Brody.

## Actual deliverables

- `docs/COMPARISON.md` — what differs in the factory stock binary versus the reverted RumiTX APK, fresh-boot failure diagnosis and a safe stage-by-stage fix plan.
- `src/com/rumitx/recovery/BootCoordinator.java` — Android 8.1-compatible, no-Android-dependencies Java class that coordinates transport and an experimental RX lifecycle through injected adapters. **Requires Rumi's actual implementations to become operational.**
- `test/.../BootCoordinatorTest.java` — five mock tests, run successfully with local javac.
- `build/rumitx-recovery-coordinator-java8.jar` — **compiled Java 8 bytecode** for the portable module, not a standalone Android APK or ARM64 HIDL executable.
- `build/host_test.sh` — rebuilds and tests the module with JDK 8+.
- `scripts/collect_reboot_evidence.sh` — read-only ADB collection script.
- `reference/stock-talkie-extract.zip` — original stock APK/VDEX/ODEX/native libs, unmodified.
- `reference/RumiTX-debug-2026-10-11.apk` — reverted RumiTX APK, unmodified.
- `reference/RumiTX_S80_HIDL_ARM64_BuildKit_2026-10-11.zip` — source of a separate ARM64 native HIDL client (requires Oreo AOSP tree to build; not device-verified).
- `EVIDENCE.json` — exact SHA-256 hashes, observations, unresolved questions and next tasks.

## Run locally

```bash
bash build/host_test.sh
# PASS: five BootCoordinator host tests
```

## Exact status / constraints

A **working replacement APK and the platform-specific ARM64 HIDL executable have NOT been compiled here**. The host has `javac` and `clang`, but no Android SDK/NDK, AOSP 8.1 platform libraries, `hidl-gen`, native RumiTX helper source or original RumiTX Gradle source. This limitation cannot be bypassed by simply merging APKs; there is also an application-signature and Android privileged-service boundary.

The compiled JAR covers only orchestration; to integrate it, Rumi must supply adapters for existing helper commands, module query, and HIDL RPC. `BootCoordinator.enableRx` is explicitly **experimental** and must run on a worker, never the UI thread.

**Highest priority:** obtain current failing fresh-boot log showing actual `READY`, `start`, `serial`, UART/query, HIDL stages. `getService()` alone **never** proves RX initialization.
