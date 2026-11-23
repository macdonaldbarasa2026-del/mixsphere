/*
 * Mixsphere Phase 0 spike - real PCM WAV reader.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
#pragma once

#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace mixsphere {

struct PcmTrack {
    std::vector<float> samples;
    int channels = 0;
    int sampleRate = 0;
    bool valid() const {
        return channels > 0 && sampleRate > 0 && !samples.empty();
    }
    int frames() const {
        return channels > 0 ? static_cast<int>(samples.size() / channels) : 0;
    }
};

class WavReader {
  public:
    static PcmTrack read(const std::string& path);
};

} // namespace mixsphere
