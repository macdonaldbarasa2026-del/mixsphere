# ADR-0004: Audio decoding strategy

- Status: Accepted
- Phase: 0
- Owner: Software Engineer

## Context
The spike must load and play a file. Mixxx already has decoders for WAV/FLAC/OGG (`soundsourcesndfile.cpp`, `soundsourceflac.cpp`, `soundsourceoggvorbis.cpp`) and for MP3/AAC/M4A via FFmpeg (`soundsourceffmpeg.cpp`). Android also offers MediaCodec; single-header options such as minimp3/dr_libs exist.

## Decision
Use Mixxx's existing `SoundSourceProxy` reader stack for the spike: libsndfile/FLAC/Vorbis for WAV/FLAC/OGG and an LGPL-configured FFmpeg build for MP3/AAC/M4A. MediaCodec is deferred. minimp3/dr_libs are recorded as an optional future MP3-only fast path.

## Reasons
- MediaCodec is asynchronous and buffer-queue oriented; random access, accurate seek, and sample-accurate looping/scratching are awkward and would force a second threading model inside the deck.
- FFmpeg is already integrated into Mixxx with accurate seeking and supports all required container/codec combinations; LGPL 2.1+ configuration is compatible with a GPLv3 distribution when dynamically linked.
- Reusing Mixxx readers avoids writing a decoder during a spike whose only goal is proving the engine runs.

## Consequences
- FFmpeg must be built with LGPL-only flags in the pinned vcpkg environment; GPL codecs are disabled.
- APK size increases by the FFmpeg shared libraries; this is measured in the performance report and may be optimised later.
- License notices for FFmpeg, libsndfile, FLAC, Vorbis, and TagLib must be published with the app (Phase 4).
