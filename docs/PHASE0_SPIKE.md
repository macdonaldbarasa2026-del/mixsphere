# Phase 0 Spike Implementation

Owner: Software Engineer
Status: Implemented in branch `feat/phase0-native-spike`; **not yet verified** by CI. No gate claim is made until the workflow artefacts exist.

## What is implemented

`android-spike/native/` builds `libmixsphere_engine.so`:

| Component | Source | Notes |
|---|---|---|
| Crossfader DSP | Mixxx `src/engine/enginexfader.cpp` | Compiled from the fork, upstream header intact, constant-power curve |
| Audio output | `OboeOutput.*` | AAudio preferred, OpenSL ES fallback; callback does only render + timing |
| Deck engine | `DeckEngine.*` | Two decks, linear resample or granular keylock, preallocated, atomic controls |
| WAV decoding | `WavReader.*` | Real 16-bit PCM and 32-bit float RIFF parsing, no simulation |
| JNI ABI | `jni_bridge.cpp` | Thin: create/destroy, load, play, tempo, crossfader, telemetry |
| Host tests | `native/tests/` | WAV fidelity, xfader power law, audible output, keylock tempo |

`android-spike/app/` is a minimal Kotlin host (`com.mixsphere`) with an
instrumented proof-of-life test. Jetpack Compose is Phase 2.

## Public native contract

`com.mixsphere.engine.MixsphereEngine` exposes: `loadTrack`, `startAudio`,
`play`, `setTempo(deck, tempo, keylock)`, `setCrossfader`, `renderBlock` (test),
`renderedEnergy`, `audioApi`, `latencyMs`, `callbackMicros`, `sampleRate`.

## Real-time contract

- Oboe callback: `DeckEngine::render` only. No malloc, mutex, file or JNI call.
- Track swap: `std::atomic<std::shared_ptr<const PcmTrack>>` (C++20).
- Tempo, play state, keylock, gain, crossfader: `std::atomic`.
- Measured values: callback duration via `steady_clock`, Oboe estimated output
  latency via `getEstimatedOutputLatency()`.

## CI verification plan

1. `cpp-unit-tests` — host GTest (qt6-base-dev, gtest pinned by runner image).
2. `kotlin-unit-tests` — `gradle testDebugUnitTest`.
3. `static-checks` — rejects placeholder markers in spike sources.
4. `ndk-build` — sources the pinned Mixxx Android buildenv (NDK 27.2.12479018,
   API 35, Qt6 via vcpkg `arm64-android`), assembles a debug APK, uploads it.
5. `proof-of-life` — API 35 arm64 emulator, generates a real 48 kHz sine WAV,
   loads/plays it, applies 1.10x and 0.90x with keylock, uploads logcat lines
   prefixed `MixspherePhase0`.

## Gate definition (unchanged from PORT_PLAN)

Green CI plus uploaded logcat showing `PHASE0_PROOF_OF_LIFE=PASS` and an APK
artifact. Until then this phase is **not** complete.

## Known limitations to report, not hide

- 32-bit `armeabi-v7a` is not built; see ADR-0007.
- Keylock is spike-grade granular OLA, not Mixxx RubberBand; see ADR-0006.
- The full `mixxx-lib` extraction feasibility run was cancelled before
  completion, so there is no evidence yet that the primary strategy works.
  The fallback strategy is what this implementation represents.
- Emulator latency figures are not representative of physical device latency.
