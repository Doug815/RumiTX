# Test report — October 17, 2026

## Verified locally

- Production Vinext build passed; all nine application/API routes emitted. Build reports a large Three.js client chunk warning.
- TypeScript check passed after correcting JSON response type boundaries.
- 15 deterministic checks passed: valid channel changes, out-of-range handling, casual chat, repeater request, status request, transmit clarification, garbled input, negated channel request, fixture count/uniqueness/labels, zero self-distance.
- 1,000 local 50-fixture distance-sort iterations: median 0.0062 ms; p95 0.0233 ms in this container. These are pure computation results, NOT D1 query times or hosted API latency.
- Real supplied APK ZIP inspected. SHA-256 and byte count computed, stored in public/reference-build.json. Native libraries and packaged build metadata observed. APK was not executed.
- Code review: identity required for lab and all writes; server-side admin required for hardware changes and moderation; per-owner logs and state; fixed SQL with bound parameters; mutation origin checks; bounded inputs; optimistic concurrency on thread updates.

## Not verified

- Browser rendering, visual QA, keyboard/accessibility flows, and mobile layout. No supported browser testing connection was available; no preview server or alternate browser driver was used.
- Live signed-in persistence, Google OAuth, email/password login, APK execution, native screens, RF/audio/HIDL/JNI behavior, Android refresh persistence, live LLM accuracy, or 15s/30s database retrieval/cancellation targets.
- Full hosted security testing with distinct admin/member accounts. Code review is not a substitute for those tests.
- No 2-minute screen recording was generated.

## Scope and limitations

Web replica, not Android emulation. Sites auth/D1, not Supabase. 50 synthetic in-memory fixtures, not live repeaters. Local APK hashing, not upload/install. Newsletter storage, not email delivery. Three.js geometric approximation, not photorealistic reconstruction. Native screenshot/video slots are pending.

Remaining before calling the entire original brief complete: pixel-faithful native UI capture, actual seeded repeater DB, full profile/history/avatar functions, live model adapter, contact email delivery, Supabase/OAuth migration if desired, release approval/upload pipeline, detailed admin/member runtime tests, and demonstration recording.
