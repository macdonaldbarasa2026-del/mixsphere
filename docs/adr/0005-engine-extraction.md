# ADR-0005: Engine extraction strategy and fallback trigger

- Status: Accepted
- Phase: 0
- Owner: Software Engineer

## Context
`EngineMixer` includes both `soundio/soundmanager.h` and `recording/recordingmanager.h` (`src/engine/enginemixer.h:18-19`), and the default CMake target mixes 624 sources including 151 UI files. A "pure engine" shared library therefore requires an explicit, documented curation.

## Decision
Primary: add a `mixsphere_engine` CMake target that compiles the control, engine, builtin effects, analyzer, sources, audio, track, util, qm-dsp, and rigtorp sources, excludes UI/skin/dialog/QML/PrintSupport/Svg, and adds the Oboe device plus JNI bridge. Sever the `RecordingManager` include only by introducing a real `IMasterRecorder` interface with a working file-encoder implementation — never by an empty stub.

Fallback trigger (measured, not guessed): switch to the DSP-only strategy if either (a) more than ~30 included Mixxx sources still require GUI-only headers after exclusion, or (b) provisioning the pinned Qt-Android + vcpkg environment fails to produce a cross-build after two documented CI iterations.

Fallback: compile Mixxx DSP units (xfader, filters, buffer scalers, BPM/key detection, loops, cues, waveform analyzer) into `libmixsphere_engine.so` and write a fresh `DeckEngine` orchestrator around Oboe with lock-free command queues. All reused DSP keeps Mixxx license headers.

## Reasons
- Preserving the real mixer engine gives the strongest proof and the most reusable Phase 3 foundation.
- An explicit numeric trigger prevents unbounded refactoring disguised as a spike and honours the rule that infeasibility must be evidenced.
- The fallback still satisfies the non-negotiable rule because the DSP is real Mixxx code, not a mock.

## Consequences
- `docs/PATCHES.md` must list every file changed to sever coupling, with hunk and reason.
- CI logs for failed provisioning attempts are kept as evidence even if the fallback is used.
