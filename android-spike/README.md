# Mixsphere Phase 0 Android audio spike

This is a real Android/Oboe/JNI playback harness for Phase 0. It decodes a
bounded PCM WAV file, renders it from a native two-deck mixer, and reuses
Mixxx's `EngineXfader` DSP implementation. It does **not** currently run
Mixxx's `EngineBuffer` or `EngineMixer`; the new `DeckEngine` is a fresh
orchestrator. Treat this as an Oboe/backend and extracted-DSP probe, not proof
that the complete Mixxx engine has been extracted.

The audio callback reads immutable decoded track data and atomic control values.
Track I/O, decoding, and ownership changes happen on caller threads. Callback
memory use, real-time behavior, and playback/keylock assertions still require
a green Android CI run and emulator logs before this spike can be called proven.

The fixture reader supports PCM16 and IEEE float32 WAV, mono or stereo, and caps
decoded memory at 64 MiB. This is not the product library decoder. The
instrumented test creates a known two-second WAV fixture, starts an actual
Oboe stream, verifies callback output and applied tempo/keylock controls, then
logs callback duration and Oboe's latency estimate.

Licensing: Mixxx source files compiled into the target retain their original
GPL v2-or-later headers. Mixsphere-authored files are GPL v3-or-later. Mixxx is
Copyright (C) 2001-2026 Mixxx Development Team. Any later source patch to Mixxx
must be listed in `docs/PATCHES.md`.
