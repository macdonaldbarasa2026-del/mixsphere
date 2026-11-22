/*
 * Mixsphere Phase 0 spike - two-deck engine using Mixxx's EngineXfader DSP.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The audio path performs no allocation, no locking and no I/O. Control values
 * are atomic. Track replacement uses std::atomic<std::shared_ptr<...>> (C++20).
 *
 * Keylock uses a preallocated granular overlap-add time stretcher: source is
 * read at unit rate (pitch preserved) while grains are scheduled at the
 * requested tempo. Phase 3 replaces this with Mixxx's RubberBand scaler.
 */
#pragma once

#include <array>
#include <atomic>
#include <cmath>
#include <memory>
#include <span>

#include "WavReader.h"
#include "engine/enginexfader.h"

namespace mixsphere {

class DeckEngine {
  public:
    struct Deck {
        std::atomic<std::shared_ptr<const PcmTrack>> track{};
        std::atomic<double> tempo{1.0};
        std::atomic<bool> playing{false};
        std::atomic<bool> keylock{false};
        std::atomic<double> gain{1.0};
        double position = 0.0;
        double outputTime = 0.0;
    };

    explicit DeckEngine(int outputSampleRate);
    void render(std::span<float> interleaved);

    void load(int deck, std::shared_ptr<const PcmTrack> track);
    void setPlaying(int deck, bool playing);
    void setTempo(int deck, double tempo, bool keylock);
    void setGain(int deck, double gain);
    void setCrossfader(double position);
    void seek(int deck, double fraction);

    double renderedEnergy() const { return m_energy.load(std::memory_order_relaxed); }
    int outputSampleRate() const { return m_outputSampleRate; }

  private:
    void renderDeck(Deck& deck, float gain, std::span<float> interleaved);
    void renderPlain(Deck& deck, const PcmTrack& track, float gain,
            std::span<float> interleaved);
    void renderKeylocked(Deck& deck, const PcmTrack& track, float gain,
            std::span<float> interleaved);
    static void sampleAt(const PcmTrack& track, double position, float& left, float& right);

    std::array<Deck, 2> m_decks;
    std::atomic<double> m_crossfader{0.0};
    std::atomic<double> m_energy{0.0};
    int m_outputSampleRate;
};

} // namespace mixsphere
