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
|---|---|---|---|---|
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

```
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
|---|---|---|---|
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

Pinned versions (recorded, not floating):
- NDK `27.2.12479018` (from `tools/android_buildenv.sh`)
- Android API 35, build-tools `35.0.0`
- JDK 17 (from `tools/android_buildenv.sh`)
- CMake 3.31.x (Qt 6.10 requires >= 3.22; pin exact patch in workflow)
- Oboe `1.9.x` via pinned `FetchContent` tag
- AGP / Kotlin / Gradle: pinned exact in `gradle/libs.versions.toml` (created in the scaffold step)
- Mixxx prebuilt vcpkg buildenv: pinned name + SHA256 from `tools/android_buildenv.sh`

Workflow jobs (PR and main):
1. `static`: clang-format / clang-tidy check, ktlint/detekt, license-header check.
2. `cpp-host-tests`: host GTest unit tests for DSP units (no Android needed).
3. `kotlin-tests`: JVM unit tests.
4. `ndk-build`: matrix `arm64-v8a`, `armeabi-v7a`; cache vcpkg + Qt + ccache; compile `libmixsphere_engine.so`.
5. `instrumented`: API 35 arm64 emulator; install test APK; push generated WAV; run proof-of-life test; capture logcat latency lines; upload as artifact.
6. `package`: assemble signed debug APK + AAB, upload artifacts. Debug keystore generated in CI (never committed); release signing deferred to Phase 4 with GitHub Secrets.
7. Gate job requires all above to pass.

## 8. Proof of life (gate)

An instrumented test that:
1. Starts the app's `MixxxEngine` through JNI.
2. Loads a locally generated 5-second 44.1 kHz sine WAV (generated in CI/emulator, no third-party asset).
3. Starts playback; asserts non-silent output is produced by the engine (native buffer energy check).
4. Sets tempo to 1.10 with keylock enabled via the JNI control API; asserts the engine reports the new rate and continues producing non-silent output.
5. Logs measured native callback duration and any Oboe latency estimate to logcat; the workflow uploads that log.

**Gate is met only when** the workflow is green and the uploaded logcat shows both play and tempo-with-keylock assertions passing, plus an APK artifact is attached to the PR.

## 9. Deliverables checklist

- [x] `docs/MIXXX_ANALYSIS.md`
- [x] `docs/PORT_PLAN.md`
- [ ] `docs/adr/0001..0005`
- [ ] `docs/PATCHES.md` (created and kept current as patches are applied)
- [ ] CI workflow + pinned version catalog
- [ ] `libmixsphere_engine.so` source (CMake + Oboe + JNI)
- [ ] Minimal Kotlin host + instrumented proof-of-life test
- [ ] Green CI run with logs + APK artifact
- [ ] Phase 0 report to Product Manager; STOP for approval

## 10. Execution status (2026-11-22)

Mixxx upstream documents an Android APK build using its Qt/vcpkg environment. The Mixsphere GitHub fork exists, and the architecture documents were merged in PR #1. The Phase 0 workflow is being corrected in PR #2 to use the documented `apk` CMake target. This build is an upstream Android baseline only; it does not satisfy the Mixsphere engine extraction gate.

The fork currently has no verified `libmixsphere_engine.so` target, Oboe device, JNI playback host, instrumented play/keylock test, emulator latency log, or passing APK artifact for this proof. Until those are implemented and CI evidence is attached to a PR, Phase 0 remains open and later product/design phases do not start.
