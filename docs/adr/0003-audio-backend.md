# ADR-0003: Target audio backend — Oboe behind the AudioSource seam

- Status: Accepted target architecture; implementation is not yet verified
- Phase: 0
- Owner: Software Engineer

## Context

Mixxx's audio path is `SoundManager` → `SoundDevicePortAudio` → `AudioSource::buffer(const AudioOutput&)`. `EngineMixer` implements `AudioSource` (`src/soundio/soundmanagerutil.h:240`). PortAudio's Android support is legacy OpenSL ES and is not the supported low-latency path on modern Android. The project requires AAudio with OpenSL ES fallback and a callback with zero allocations, locks, or I/O.

## Decision

The target design is an `OboeSoundDevice` (native, in `src/android/`) that owns an Oboe `AudioStream` configured with AAudio as the preferred API and OpenSL ES fallback. For the extracted-engine path, `onAudioReady` will call `EngineMixer::buffer(output)` and copy the resulting `std::span<const CSAMPLE>` into `audioData`. No allocation, mutex, file, or JNI call may occur in the callback. Control changes use Mixxx's atomic `ControlValue`; telemetry flows UI-wards through the vendored `rigtorp::SPSCQueue`.

The current `android-spike` harness does not yet use `EngineMixer::buffer`; it renders with a new `DeckEngine` and reuses Mixxx `EngineXfader`. See ADR-0006. Oboe latency is queried off the data callback with `AudioStream::calculateLatencyMillis()`; unsupported stream APIs report no estimate. See [Oboe AudioStream API](https://github.com/google/oboe/blob/1.9.0/include/oboe/AudioStream.h).

## Reasons

- `AudioSource` is already a pure interface, so the engine does not need to know about Oboe.
- Oboe is Google's maintained abstraction for AAudio/OpenSLES and provides the latency headroom needed for the < 20 ms target.
- Keeping the callback free of locks and allocation matches Mixxx's existing real-time design intent.

## Consequences

- PortAudio and its enumerator are excluded from the engine target; PipeWire, JACK, and network devices are excluded too.
- USB/Bluetooth MIDI input is a separate Phase 3 concern and does not enter the callback.
- Latency must be measured, not asserted: instrumented test logs native callback duration and Oboe-reported latency, with emulator results explicitly labelled non-representative.
