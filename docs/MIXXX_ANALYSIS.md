# Mixxx Android Engine Extraction Analysis (Mixsphere Phase 0)

Owner: Software Engineer
Status: Preliminary source audit. The source and CI claims below still require confirmation against the checked-out fork and a passing cloud run.
Source of truth: upstream `mixxxdj/mixxx` source files and its published Android build guide. The current workspace is not a Git checkout, so fork provenance and branch contents could not be independently confirmed here.
Method: prior source-tree inspection recorded in this workspace plus upstream documentation. No local clone or local build (phone is editor-only per project rules).

## 1. Repository facts (measured)

| Fact | Evidence |
|---|---|
| Upstream version | `project(mixxx VERSION 2.7.0 LANGUAGES C CXX)` at `CMakeLists.txt:532`; prerelease `alpha` at `:534` |
| Build system | CMake (root `CMakeLists.txt`, 5724 lines). No SCons/CMake migration ambiguity remains. |
| Source size | 6391 tracked files; 624 `.cpp` entries in the `mixxx-lib` source list; 151 of them under `src/skin/`, `src/dialog/`, `src/widget/`, `src/preferences/` |
| Engine size | 140 files under `src/engine/` |
| License | `COPYING`, `LICENSE` present; project is GPL v2+ (Mixxx). Derived work must stay GPL-compatible and preserve headers. |
| Upstream Android support | `CMakeLists.txt:60-96` sets `ANDROID`, API 35, `arm64-v8a`, NDK clang, `c++_shared`; `packaging/android/` contains `AndroidManifest.xml`, `MainActivity.java`, `UsbPermission.java`, resources; `tools/android_buildenv.sh` pins NDK `27.2.12479018`, SDK/API 35, and vcpkg triplet `arm64-android`. |

**Conclusion before any code:** Mixxx already documents an Android build path using Qt for Android and a vcpkg dependency environment. The published steps build an APK with the `apk` target. Mixsphere's spike is *shaping that engine into a native Compose app with an Oboe audio backend*, and proving playback/keylock in the extracted engine. An upstream Android APK alone does not satisfy this proof. See [Mixxx's Android build guide](https://github.com/mixxxdj/mixxx/wiki/Building-for-Android).

## 2. Architecture map

### 2.1 Control bus (`src/control/` — 31 files)
- `ControlObject : QObject` with `Q_OBJECT` and signals (`src/control/controlobject.h:10-170`).
- `Control` / `ControlValue` provide the atomic value storage used by the audio thread.
- `ControlProxy`, `PollingControlProxy`, `ControlPotmeter`, `ControlPushButton`, `ControlEncoder`, `ControlLinPotmeter`, `ControlLogPotmeter`, `ControlAudiotaperPot`.
- Persistence via `preferences/usersettings.h` (`ConfigObject`).
- **Qt dependency:** QtCore only (QObject, QEvent, QAtomic*, QMutex). No Widgets/QML needed for the bus itself.

### 2.2 Engine core (`src/engine/`)
- `EngineMixer : public QObject, public AudioSource` (`src/engine/enginemixer.h:40`). This is the single pull point for audio: `AudioSource::buffer(const AudioOutput&)`.
- `EngineBuffer` per deck (`src/engine/enginebuffer.h:55`) with `Q_OBJECT`, owns:
  - `CachingReader` / `CachingReaderWorker` (`src/engine/cachingreader/`) — background decode thread feeding a lock-free-ish ring buffer.
  - `ReadAheadManager`.
  - Scalers: `EngineBufferScaleLinear`, `EngineBufferScaleST` (SoundTouch), `EngineBufferScaleRubberBand` + `RubberBandWorkerPool` / `RubberBandTask` (keylock).
  - Controls under `src/engine/controls/`: `BpmControl`, `KeyControl`, `RateControl`, `LoopingControl`, `CueControl`, `QuantizeControl`, `ClockControl`, `VinylControlControl`.
  - `PositionScratchController`, `SlipModeState`.
- Channels: `EngineDeck`, `EngineAux`, `EngineMicrophone`, `EngineChannel`.
- Mixing/util: `ChannelMixer`, `EngineXfader`, `EnginePregain`, `EngineVuMeter`, `EngineDelay`, `EngineTalkoverDucking`, `EngineSideChainCompressor`.
- Filters: `src/engine/filters/` — Bessel4/8, Butterworth4/8, LinkwitzRiley2/4/8, MoogLadder4, biquad1, pan. These are self-contained DSP and are the strongest reuse candidates.

### 2.3 Audio I/O (`src/soundio/`)
- `SoundManager : QObject` owns `SoundDevice*` instances and enumerators.
- `AudioSource` is a **clean pure-virtual interface** in `src/soundio/soundmanagerutil.h:240-255`:
  `virtual std::span<const CSAMPLE> buffer(const AudioOutput&) const = 0;`
  plus `onOutputConnected` / `onOutputDisconnected` hooks guaranteed to run outside the callback.
- `AudioDestination` (same file, `:258+`) is the input-side interface.
- Backends: `SoundDevicePortAudio` (+ `paV19Callback`, `callbackProcess*`, `paFinishedCallback`), PipeWire, network, `-NOT ANDROID` PortMidi (`:3785`).
- **Seam:** an Oboe device can call `EngineMixer::buffer(output)` exactly as PortAudio does. No engine rewrite is required for audio I/O in principle.

### 2.4 Effects (`src/effects/`, `src/engine/effects/`)
- `EffectsManager`, `EffectManager`, `EngineEffectsManager`, `EngineEffectChain`, `EngineEffect`, `EngineEffectParameter`, `Message`.
- Backends: `builtin` (~35 effects: EQ variants, filter, echo, reverb, flanger, phaser, compressor, bitcrusher, glitch, metronome, etc.), LV2, AudioUnit (Apple-only, `.mm`), plus VST-related code.
- **Android impact:** built-in effects are portable C++/QtCore. AudioUnit backend is excluded by platform. LV2 can be excluded for MVP.

### 2.5 Analyzers (`src/analyzer/`)
- `AnalyzerThread`, `TrackAnalysisScheduler`, `AnalyzerTrack`, `AnalysisScheduledTrack`.
- `AnalyzerBeats` (QM-DSP Queen Mary tempo tracker / SoundTouch beats), `AnalyzerKey` (KeyFinder / Queen Mary key), `AnalyzerWaveform`, `AnalyzerEbur128`, `AnalyzerGain`, `AnalyzerSilence`.
- **Android impact:** analyzers run off the audio callback on `EngineWorkerScheduler`/`AnalyzerThread`; good fit for `WorkManager` orchestration from Kotlin, but the DSP itself stays native.

### 2.6 Library (`src/library/`)
- QtSql + SQLite DAOs (`src/library/dao/`), `TrackCollection`, `BaseSqlTableModel`, cover art via QtGui `QImage`, and a large set of `dlg*.ui` / model-view widgets.
- **Android impact:** this is the most UI-coupled subsystem. For Mixsphere, library *storage/query* can be reused (QtSql/SQLite), but the model/view and dialogs must be replaced by Room + Compose + SAF/MediaStore. Excluded from `libmixsphere_engine.so` in Phase 0.

### 2.7 Sync (`src/engine/sync/`, `src/engine/controls/`)
- `EngineSync`, `Syncable`, internal clock, `BpmControl`, `RateControl`, `QuantizeControl`. Sample-accurate beat/quantize logic lives here.

### 2.8 Recording (`src/recording/`)
- `RecordingManager` is included directly by `engineMixer.h` (`#include "recording/recordingmanager.h"`).
- Encoders in `src/encoder/`: FLAC, Vorbis, LAME MP3, sndfile WAV.
- **Coupling evidence:** `EngineMixer` depends on both `soundmanager.h` and `recordingmanager.h`. This is the concrete reason a "pure engine" library is not a one-line CMake change.

## 3. Coupling assessment (the real risk)

| Coupling | Evidence | Severity for extraction |
|---|---|---|
| `EngineMixer` → `SoundManager` | `#include "soundio/soundmanager.h"` in `enginemixer.h:19` | High: drags device enumeration and PortAudio into the engine target |
| `EngineMixer` → `RecordingManager` | `#include "recording/recordingmanager.h"` in `enginemixer.h:18` | Medium: can be stubbed with a real file encoder if decoupled |
| `ControlObject : QObject` + `Q_OBJECT` everywhere | `controlobject.h:3,10,170`; `enginebuffer.h:5,56` | Medium: forces QtCore (acceptable) and MOC into the build |
| 151 UI sources in `mixxx-lib` | CMake source list scan | High for APK size, not for correctness: must be excluded |
| Cover art uses QtGui `QImage` | `src/library/coverart.*` | Medium: forces Gui unless cover art is dropped from engine target |
| vcpkg buildenv required | `tools/android_buildenv.sh`, `CMakeLists.txt:100-360` | High for CI: must download pinned prebuilt env (~large) or cross-build deps |

**Decision:** attempt full curated engine extraction first; if the build log shows the coupling above cannot be severed without rewriting hundreds of files, invoke the documented fallback (Section 6 of PORT_PLAN).

## 4. Licensing notes

- Mixxx is GPL v2 or later; all Mixxx source headers must be preserved in every derived file.
- Vendored components have their own licenses: `lib/qm-dsp/COPYING` (GPL), `lib/portaudio/LICENSE.txt`, `lib/fidlib/COPYING` + `COPYING_LIB`.
- FFmpeg must be built with LGPL configuration (no `--enable-gpl`) to avoid pulling GPL codecs into an already-GPL app unnecessarily and to keep distribution simple.
- Qt 6 is LGPL v3 / GPL v3 / commercial. Using the LGPL build dynamically is compatible with a GPL v3 distribution of Mixsphere. Final SPDX for the app must be chosen and reviewed before Phase 4 (Security); proposed: `GPL-3.0-or-later` with attribution to Mixxx and all dependency licenses published.

## 5. Evidence index

All statements above were derived from:
- `CMakeLists.txt` lines 60-96, 532-534, 1180-1680, 2349-2380, 2763-2830, 3243-3310, 3613-3790, 3800-3880
- `tools/android_buildenv.sh`
- `packaging/android/AndroidManifest.xml`, `packaging/android/src/org/mixxx/MainActivity.java`
- `src/engine/enginebuffer.h`, `src/engine/enginemixer.h`, `src/control/controlobject.h`
- `src/soundio/soundmanagerutil.h`, `src/soundio/soundmanager.h`, `src/soundio/sounddeviceportaudio.cpp`
- GitHub tree listing of `main` (6391 paths)
