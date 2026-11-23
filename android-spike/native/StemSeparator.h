/*
 * Mixsphere Phase 0 spike - offline stem separation.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Harmonic/percussive decomposition (HPSS) on an STFT magnitude spectrogram,
 * followed by a vocal-band weighting of the harmonic part. Three masked
 * inverse STFTs produce vocals, beat and harmony stems that sum to the input.
 * All work happens off the audio callback on a control thread.
 */
#pragma once

#include <string>

#include "WavReader.h"

namespace mixsphere {

struct StemSet {
    PcmTrack vocals;
    PcmTrack beat;
    PcmTrack harmony;
};

StemSet separateStems(const PcmTrack& input);
PcmTrack mixStems(const PcmTrack& first, const PcmTrack& second);
bool writePcm16Wav(const PcmTrack& track, const std::string& path);

} // namespace mixsphere
