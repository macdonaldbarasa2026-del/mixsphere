# Mixsphere Phase 0 Status and Handoff

Owner: Software Engineer
Date: 2026-11-22
Status: **Gate not met; implementation and CI validation continue**

## Work in the shared workspace

- Architecture and dependency audit are in `docs/MIXXX_ANALYSIS.md` and `docs/PORT_PLAN.md`.
- ADR-0001 through ADR-0006 record repository, Qt, audio, decoder, extraction, and current harness boundary decisions.
- `android-spike/native` contains a CMake shared-library target, Oboe output, JNI facade, bounded PCM WAV reader, two-deck render loop, tempo/keylock control, and Mixxx `EngineXfader` DSP integration.
- `android-spike/native/tests` contains GTest source for WAV parsing, crossfader DSP, output rendering, keylock controls, and invalid numeric controls.
- `android-spike/app` contains the Kotlin JNI facade, Kotlin tempo boundary tests, and an instrumented test that starts Oboe and observes output/control telemetry from its callback.
- Callback-facing samples are immutable and published as raw pointers; ownership stays on control-side storage until engine destruction. Callback controls and telemetry use lock-free atomic types guarded by compile-time assertions. Track I/O and decode remain outside the callback.

## What this does and does not prove

The present deck code is a new `DeckEngine` around one real Mixxx DSP unit (`EngineXfader`). It does not execute Mixxx `EngineBuffer` or `EngineMixer`. The WAV reader handles only PCM16 or IEEE float32 mono/stereo and caps decoded storage at 64 MiB. The granular overlap-add keylock is experimental and is not Mixxx RubberBand/SoundTouch. This harness can test Oboe/JNI/callback plumbing, but its success alone will not be reported as full Mixxx engine extraction. ADR-0006 records the boundary and the evidence still needed to select the fallback.

## CI and performance evidence

The source tests and workflow are present, but these latest local edits have not been built or run in CI yet. No APK, emulator log, callback duration, latency, CPU, memory, or battery measurement is available. The app currently targets `arm64-v8a`; Mixxx's upstream Android build environment selects only the `arm64-android` dependency triplet. The required `armeabi-v7a` dependency/build path remains open.

Upstream's Android build guide documents an APK build using the `apk` CMake target. That baseline is distinct from the Mixsphere JNI/DSP harness and does not satisfy its proof gate: [Mixxx Android build guide](https://github.com/mixxxdj/mixxx/wiki/Building-for-Android).

## Remaining gate work

- [ ] Run the C++ host and Kotlin JVM unit tests in GitHub Actions.
- [ ] Build `libmixsphere_engine.so` and the debug APK with the pinned NDK/CMake/Gradle/AGP/Kotlin/Oboe/JDK toolchain.
- [ ] Run the API 35 arm64 emulator test; require Oboe startup, real callback output, both keylock tempo changes, and logcat timing data.
- [ ] Verify the uploaded APK is debug-signed and attach logs/artifact to the task PR.
- [ ] Produce the required `armeabi-v7a` build or document concrete dependency/toolchain failure and viable alternative.
- [ ] Continue the primary `EngineBuffer`/`EngineMixer` extraction feasibility check; only select the DSP-only fallback after recording the source/build evidence.
- [ ] Measure and report callback duration and Oboe estimated latency from CI. Do not infer physical-device CPU, memory, or battery numbers from an emulator.

Product, visual design, and icon work remain outside Phase 0. Phase 0 stays open until the CI evidence satisfies the gate.
