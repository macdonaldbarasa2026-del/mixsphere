# Phase 0 Continuation Checkpoint

Date: 2026-11-22
Owner: Software Engineer
State: **Work in progress; Phase 0 gate is not met**

This file is the restart point for the next session. The shared workspace is an editor-only snapshot without `.git`; the other terminal owns pushing. Do not assume local edits described below are already in the PR.

## GitHub state checked today

- Repository: [macdonaldbarasa2026-del/mixsphere](https://github.com/macdonaldbarasa2026-del/mixsphere), fork of `mixxxdj/mixxx`.
- PR #1 architecture docs: merged.
- PR #2 `fix/phase0-android-apk-target`: stale/conflicting baseline workflow; do not merge.
- PR #3 `feat/phase0-mixsphere-rebrand`: open; changes package/app label/attribution. It does not provide a new product icon.
- PR #4 `feat/phase0-native-spike`: open; native spike and workflow are being reviewed. At the last check its head was `1c868e2e4dc2a4f45e0309beda77482555bd88fc`.
- Latest Phase 0 workflow runs visible for PR #4 were queued. No successful build, APK artifact, emulator result, or measured latency is confirmed.

The local workspace files have since changed. Comparing with PR #4 showed local-only differences in `DeckEngine.h/.cpp`, `OboeOutput.cpp`, `jni_bridge.cpp`, `WavReader.cpp`, native CMake/tests, app Gradle/Kotlin facade, workflow, port/status docs, and ADR-0003. `TempoControl.kt`, its JVM test, and ADR-0006 audio-harness decision were not present on PR #4 at that check. Have the other terminal sync/review these local edits into the task branch before merging.

## Current local implementation

- `android-spike/native/CMakeLists.txt` defines `libmixsphere_engine.so`, uses Qt Core, Oboe pinned to commit `03242e9ef9495e418e6ae83954d239dc9193ec5c`, and compiles Mixxx `src/engine/enginexfader.cpp`.
- `DeckEngine` is a new two-deck renderer. It uses the real Mixxx crossfader DSP, a bounded PCM WAV reader, and an experimental granular overlap-add keylock path.
- `OboeOutput` prefers AAudio and falls back to OpenSL ES, queries `calculateLatencyMillis()` away from the callback, records callback duration, and sets the engine rate from the opened stream.
- JNI exposes load/play/tempo/crossfader and callback telemetry. Kotlin validates tempo input and has JVM boundary tests.
- The instrumented test starts Oboe and waits for actual callback output/control telemetry. It no longer renders a second buffer concurrently with the device callback.
- Callback-facing track data is immutable and published by raw atomic pointer. Ownership remains on the control side until engine destruction, avoiding shared-pointer refcount/free work on the real-time callback. Seek is passed as an atomic fraction/generation command. Tempo and keylock are encoded in one lock-free 32-bit atomic snapshot. Compile-time checks require lock-free callback atomics.
- WAV support is limited to PCM16/float32, mono/stereo, with decoded output capped at 64 MiB. This is test-spike decoding, not the product library decoder.
- Host GTest and Kotlin unit test sources are present locally. No build or test was run in this editor workspace.

## Documentation caveats

- The harness does **not** currently run Mixxx `EngineBuffer` or `EngineMixer`; it is a new DeckEngine around one actual Mixxx DSP unit. Its success alone does not prove full Mixxx engine extraction.
- The primary extraction feasibility attempt was cancelled before completion. The fallback selection therefore still needs its source/build evidence and clear ADR disposition.
- Current app ABI is `arm64-v8a` only. The upstream buildenv selects the `arm64-android` vcpkg triplet. `armeabi-v7a` is not implemented; investigate a separate `arm-neon-android` dependency build rather than describing it as universally infeasible.
- Keylock is experimental and has not been checked against a reference signal. Do not claim product-quality pitch preservation.
- Emulator latency is not representative of a physical phone. No CPU, memory, battery, or latency numbers are currently validated.
- ADR numbering conflict resolved: the keylock decision is now ADR-0008 (`docs/adr/0008-keylock-spike.md`); ADR-0006 is the audio-harness boundary decision.
- `docs/PHASE0_SPIKE.md` has been refreshed from the current sources; `docs/PHASE0_STATUS.md` is superseded by this checkpoint.

## Next-session sequence

1. Ask the other terminal to sync and review the local-only changes listed above into PR #4. Keep all changes on the task branch; do not merge until required CI passes.
2. Check the latest PR #4 Actions runs and artifacts. Fix compile/test/workflow failures based on logs; do not report a green gate from queued jobs.
3. Correct stale spike/status docs and resolve duplicate ADR numbering. Confirm the Mixxx patch register matches actual upstream source changes.
4. Record the full-engine extraction attempt and decide whether the documented fallback trigger is met.
5. Resolve the armv7 dependency/build path or record concrete CI failure evidence and an alternative.
6. Complete Phase 0 only when cloud CI builds the APK, the emulator log shows `PHASE0_PROOF_OF_LIFE=PASS` plus timing data, and artifacts are attached to the PR. Then stop and wait for approval before Phase 1.

Do not begin PRD, UX, or icon work before the Phase 0 gate is accepted.
