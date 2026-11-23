# Mixsphere Android Port Plan (Phase 0 Spike)

Owner: Software Engineer
Depends on: `docs/MIXXX_ANALYSIS.md`
Status: Plan. Implementation proceeds only after the ADRs in `docs/adr/` are accepted.

## 0. Scope of this phase

Prove, with CI evidence, that a Mixxx-derived engine can be loaded into an Android app, play a file, and apply tempo change with keylock. Nothing outside that proof is in scope: no PRD, no UI design system, no full feature set.

## 1. Repository and working model

- Fork: `mixxxdj/mixxx` → `macdonaldbarasa2026-del/mixsphere` (literal fork, default branch `main`, created for this phase).
- The prior non-fork PWA repository was renamed `mixsphere-legacy-pwa-archived`. It is excluded from all Mixsphere work. Permanent deletion requires a `delete_repo` OAuth scope unavailable in this non-interactive environment; the exact command is recorded in the Phase 0 report.
- One branch per task, Conventional Commits, PR per change, CI green before merge. No direct commits to `main`.
- The phone is editor/research only. All clone, dependency fetch, NDK compile, Gradle build, tests, and packaging run in **GitHub Actions**.

## 2. Dependency audit (build status on NDK, decision)

| Dependency | Mixxx usage | NDK status | Decision | Rationale |
| --- | --- | --- | --- | --- |
| Qt 6.2+ (`Core`, `Sql`, `Concurrent`, `Xml`, `Network`) | ControlObject/QObject, QtSql library, settings, threading | Supported by Qt for Android | **Keep** in engine target | Rewriting QObject/signal-slot semantics would touch hundreds of files; QtCore has no UI dependency |
| Qt `Widgets`, `Qml`, `Quick`, `Gui`, `Svg`, `PrintSupport`, `OpenGL` | Skins/dialogs/cover art | Mostly supported; PrintSupport is not a meaningful Android target | **Exclude** from engine target | UI is Jetpack Compose; excluding cuts ~151 sources and APK size |
| PortAudio (`lib/portaudio`) | `SoundDevicePortAudio`, enumeration | Vendored, has legacy Android OpenSL support but stalled | **Replace** with Oboe | Oboe is Google's supported low-latency API (AAudio → OpenSLES fallback) |
| SoundTouch | `EngineBufferScaleST`, beat analyzer | Portable C++, ARM NEON; needs CMake patching for NDK | **Patch/vendor** | Required for keylock baseline |
| RubberBand 3.x (LGPL) | `EngineBufferScaleRubberBand`, worker pool | Has Android support via its own build; needs port wiring | **Patch/vendor**, preferred keylock backend | Higher quality than SoundTouch for tempo+keylock |
| TagLib | Metadata read/write | Cross-compiles via vcpkg `arm64-android` | **Use as-is (vcpkg)** | Already integrated in `src/sources/metadatasourcetaglib.*` |
| libsndfile | WAV/FLAC/OGG decode + encode | Cross-compiles via vcpkg | **Use as-is** | Already integrated (`soundsourcesndfile.cpp`, `encodersndfileflac.cpp`) |
| FFmpeg 4.1.9+ (LGPL build) | MP3/AAC/M4A decode (`soundsourceffmpeg.cpp`) | vcpkg `arm64-android` triplet provides it | **Use as-is, LGPL flags** | Only viable accurate-seek decoder for AAC in Mixxx's source model |
| chromaprint | Fingerprinting (`musicbrainz/chromaprinter.cpp`) | Cross-compiles via vcpkg | **Exclude from Phase 0** | Optional feature; not needed for gate |
| qm-dsp (vendored, GPL) | BPM/key detection (`analyzerqueenmary*`) | Portable C++, vendored at `lib/qm-dsp` | **Use as-is (vendored)** | Core to BPM/key detection MVP |
| SQLite | Library DB via QtSql | Android platform SQLite + Qt Sql plugin | **Use via QtSql** | Reuse Mixxx DAO layer if library is included later |
| KeyFinder / FFTW / Ebur128 | Key/EBU analyzers | Cross-compile heavier | **Defer** | Not needed for gate |

Every patch applied to Mixxx sources or build files will be recorded in `docs/PATCHES.md` with file, hunk, and reason. No silent edits.

## 3. Qt decision (ADR-0002)

**Recommendation: keep QtCore + QtSql + QtConcurrent + QtXml + QtNetwork; drop Widgets/QML/Gui from the engine target.**

Reasons:

1. `ControlObject` and major engine classes are `QObject` with `Q_OBJECT`; signals/slots are structural, not incidental.
2. Upstream already provides an Android Qt build path and pinned vcpkg environment — following it is the lowest-risk proof.
3. QtCore modules have no UI dependency and a modest footprint.
4. A plain C++ control bus would require rewriting the control layer and every consumer before we even reach the gate; that is a Phase 3 optimisation decision, justified only by measured numbers.

Trade-off accepted: the engine is Qt-flavoured C++, so Kotlin talks to it through JNI only. Compose owns all UI.

## 4. Audio architecture (ADR-0003)

```text
Kotlin/Compose UI
   |  (JNI calls: loadTrack, play, setTempo, setKeylock)
   v
JNI bridge  ---- writes control values (atomics) ---->  ControlObject/ControlValue
   |                                                        |
   |                                                        v
   |                                              EngineMixer : AudioSource
   |                                                        | buffer(output)
   v                                                        v
Oboe AudioStream (AAudio preferred, OpenSLES fallback)  <----+
   onAudioReady: zero allocation, zero locks, zero I/O
```

Rules enforced in the spike:

- `onAudioReady` only calls `EngineMixer::buffer()` and copies the returned span into `audioData`.
- All control changes go through atomic `ControlValue` writes (already lock-free in Mixxx).
- Waveform/analysis data crosses to UI through the vendored `rigtorp/SPSCQueue` (`lib/rigtorp/SPSCQueue/include`, already used by Mixxx).
- Target: < 20 ms callback-to-DAC latency. Measure native callback duration with `std::chrono` and report Oboe's estimated latency where available. Emulator numbers are explicitly labelled as not device-representative.

## 5. Decoding decision (ADR-0004)

| Option | Fit for a DJ deck | OS | Verdict |
| --- | --- | --- | --- |
| MediaCodec | Async buffer queues, seek granularity and format variability are poor for scratching/looping | Android platform | Defer |
| FFmpeg (LGPL) | Already in Mixxx, accurate seek, MP3/AAC/FLAC/OGG/WAV | LGPL 2.1+ | **Primary** |
| dr_libs / minimp3 | Tiny, CC0, accurate MP3 seek, no AAC | CC0/public domain | Optional future MP3-only fast path |
| Mixxx native readers (FLAC/Ogg/sndfile) | Already integrated | LGPL | Used alongside FFmpeg |

## 6. Engine extraction strategy and fallback

### Primary

Create a new CMake target `mixsphere_engine` (output `libmixsphere_engine.so`) in the fork:

- Include `src/control/`, `src/engine/`, `src/effects/` (builtin backend only), `src/analyzer/`, `src/sources/`, `src/audio/`, `src/util/`, `src/track/`, `lib/qm-dsp`, `lib/rigtorp`.
- Exclude `src/skin/`, `src/dialog/`, `src/widget/`, `src/preferences/ UI`, QML, PrintSupport, Svg, network broadcast, external library integrations.
- Add `src/android/oboe_sound_device.*` implementing the `SoundDevice`/`AudioSource` seam.
- Add `src/android/jni_bridge.cpp` with a thin JNI ABI.
- Apply minimal, documented patches to sever `EngineMixer`'s include of `RecordingManager` if needed (replace with a small `IMasterRecorder` interface backed by a real file encoder — no stubs).

### Fallback trigger

Invoke if CI log shows any of:

- more than ~30 Mixxx sources require GUI-only headers after exclusion, or
- Qt Android + vcpkg dependency provisioning fails to produce a working cross-build after two documented CI iterations.

### Fallback

Build `libmixsphere_engine.so` from Mixxx DSP units only (`enginexfader`, `enginefilter*`, `enginebufferscale*`, `bpmcontrol`/`qm-dsp`, `loopingcontrol`, `cuecontrol`, `analyzerwaveform`) and write a fresh `DeckEngine` around Oboe with lock-free command queues. DSP remains Mixxx code with headers intact; the orchestration layer is new Mixsphere code. The build log evidence and unit tests are attached to the same PR.

## 7. Build and CI (cloud only)

Pinned Phase 0 tool versions currently present in the spike:

- NDK `27.2.12479018` (from Mixxx `tools/android_buildenv.sh`)
- Android API 35, build-tools `35.0.0`
- Temurin JDK `17.0.21.1+2` in the Phase 0 workflow
- CMake `3.31.6` in the app Gradle configuration
- Oboe `1.9.0` pinned by commit `03242e9ef9495e418e6ae83954d239dc9193ec5c` in CMake FetchContent
- AGP `8.7.3`, Kotlin `2.0.21`, Gradle `8.9`
- Mixxx prebuilt vcpkg buildenv: URL and SHA256 pinned by `tools/android_buildenv.sh`

The current app ABI filter is `arm64-v8a`. The upstream dependency bundle selected by `tools/android_buildenv.sh` uses the `arm64-android` triplet; an `armeabi-v7a` Qt/dependency package has not been identified or built. Phase 0 cannot claim the required second ABI until that dependency path is proven.

Current workflow jobs (pull requests and manual dispatch):

1. Static placeholder-marker check for the native and Kotlin/Android sources.
2. Host C++ GTest suite for WAV parsing, Mixxx crossfader DSP, deck rendering, and control bounds.
3. Kotlin JVM tests for tempo input validation.
4. Android NDK build for `arm64-v8a`; Gradle assembles a debug APK and uploads it with the build log. Qt/vcpkg, Oboe FetchContent, and Gradle dependencies are cached.
5. API 35 arm64 emulator test starts Oboe, plays the generated file, checks callback-observed tempo/keylock controls, captures callback duration and latency, and uploads the log and test report.

Known CI gaps: lint/header validation is not yet implemented; `armeabi-v7a` is not built because the pinned Mixxx build environment only provides the `arm64-android` Qt triplet; workflow status and artifacts for the latest code edits are pending. The workflow is not a green gate until every required job passes and the APK/log artifacts are reviewed.

## 8. Proof of life (gate)

An instrumented test that:

1. Starts the app's `MixsphereEngine` through JNI.
2. Loads a locally generated two-second 48 kHz PCM16 sine WAV fixture (generated in the instrumented test; no third-party audio asset).
3. Starts an Oboe stream and playback; asserts callback output is non-silent.
4. Sets tempo to 1.10 and 0.90 with keylock enabled; waits until callback telemetry reports each control and asserts output remains non-silent.
5. Logs measured native callback duration and any Oboe latency estimate to logcat; the workflow uploads that log.

**Gate is met only when** the workflow is green and the uploaded logcat shows both play and tempo-with-keylock assertions passing, plus an APK artifact is attached to the PR.

## 9. Deliverables checklist

- [x] `docs/MIXXX_ANALYSIS.md`
- [x] `docs/PORT_PLAN.md`
- [x] `docs/adr/0001..0006` (0006 records that the current code is a harness, not full engine extraction)
- [x] `docs/PATCHES.md` (created; no upstream Mixxx source modifications have been made)
- [x] CI workflow with pinned tool versions in Gradle/CMake/workflow files (arm64 only; armv7 and green CI remain open)
- [x] `libmixsphere_engine.so` source scaffold (CMake + Oboe + JNI); cloud build remains unverified
- [x] Minimal Kotlin host + instrumented proof-of-life test source; emulator result remains unverified
- [ ] Green CI run with logs + APK artifact
- [ ] Phase 0 report to Product Manager; STOP for approval

## 10. Execution status (2026-11-22)

The local spike now contains a CMake `libmixsphere_engine.so` target, Oboe output with AAudio/OpenSL ES selection, a JNI facade, a PCM WAV fixture reader, a new `DeckEngine`, C++ host unit tests, Kotlin tempo-range tests, and an instrumented test that exercises the Oboe callback. The deck engine reuses Mixxx `EngineXfader` DSP but does not yet run Mixxx `EngineBuffer` or `EngineMixer`; the harness is not proof of full engine extraction. The current granular keylock algorithm is not Mixxx RubberBand/SoundTouch and has not had pitch-quality validation.

No cloud compile or emulator result is available for these local edits yet. The Phase 0 gate remains open until CI builds the library and APK, the emulator test passes through the Oboe callback, and uploaded logs contain measured callback/latency values. The fallback decision also remains provisional until the primary extraction attempt has documented source/build evidence. `armeabi-v7a` is unresolved because the pinned upstream dependency bundle is arm64 only. See `docs/PHASE0_STATUS.md` and ADR-0006.
