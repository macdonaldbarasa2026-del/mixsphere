# ADR-0006: Phase 0 Oboe audio harness boundary

- Status: Accepted for the Phase 0 experiment only
- Owner: Software Engineer

## Context

The initial Android spike needs to test JNI loading, Oboe stream startup, callback rendering, and device timing. The current native target includes Mixxx's real `EngineXfader` source, but it does not include Mixxx `EngineBuffer` or `EngineMixer`. The latter's source dependencies include `SoundManager` and `RecordingManager`; `EngineBuffer` also owns reader workers and time-stretching backends. The existing source map establishes coupling, but no failed extraction build or GUI-header count has yet validated the fallback trigger in ADR-0005.

## Decision

Keep the current `DeckEngine` and WAV reader as an explicitly scoped integration harness. Do not label its results as full Mixxx engine extraction or count the current implementation as satisfying the Phase 0 gate. Continue evaluating the primary `EngineMixer`/`EngineBuffer` extraction against the real fork source. If that evaluation proves too tangled, update ADR-0005 with the source/build evidence and formally select the DSP-only fallback.

The harness may establish the Oboe/JNI and callback path only after cloud CI builds it and the emulator proof passes. Its time-stretch algorithm is not Mixxx RubberBand/SoundTouch and is not a product-quality keylock claim.

## Consequences

- The test fixture uses real PCM samples and actual Oboe output; it is not a mock audio engine.
- Track data is immutable while rendered. Replaced tracks are retained until the engine is destroyed so the audio callback cannot free sample buffers. This bounds callback work but can grow memory across repeated loads; the harness is limited to its small proof fixture and requires a proper reclamation design before product use.
- The current upstream Android dependency bundle only exposes an `arm64-android` triplet. `armeabi-v7a` CI support remains unresolved and must not be claimed until an ABI-compatible Qt/dependency build is available.
- No emulator measurement or green CI evidence exists yet.
