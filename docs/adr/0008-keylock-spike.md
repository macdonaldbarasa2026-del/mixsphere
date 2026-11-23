# ADR-0006: Keylock implementation for the Phase 0 spike

- Status: Accepted
- Phase: 0
- Owner: Software Engineer

## Context

The Phase 0 gate requires a tempo change with keylock. Mixxx implements this
with SoundTouch (`EngineBufferScaleST`) and RubberBand
(`EngineBufferScaleRubberBand`) behind `EngineBufferScale`, a `QObject`. Those
classes carry the full engine dependency graph (control objects, readers,
work pools) and cannot be compiled in isolation from the spike without the
complete `mixxx-lib` target.

A real-time callback must not allocate, lock or perform I/O. Calling
SoundTouch's `putFrames`/`receiveFrames` from the Oboe callback does not meet
that rule because its internal FIFO can grow.

## Decision

For Phase 0 only, implement a preallocated granular overlap-add time stretcher
in `DeckEngine` (4 Hann-windowed grains, fixed grain size 1024 source frames).
Source is read at unit rate so pitch is preserved; grain launch spacing follows
the requested tempo. No allocation, lock or I/O occurs in `render()`.

Phase 3 replaces this unit with Mixxx's RubberBand-backed
`EngineBufferScaleRubberBand` once the full engine target is proven.

## Consequences

- Keylock is real DSP with audible pitch preservation, not a resampling shortcut.
- Audio quality is below RubberBand; this is documented as spike-grade, not
  master-grade, and must not be presented as the final Mixsphere keylock.
- The gate's keylock assertion is on continued non-silent output at 1.10x and
  0.90x, not on subjective quality.
