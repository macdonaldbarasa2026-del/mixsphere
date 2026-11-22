/*
 * Mixsphere Phase 0 spike - two-deck engine using Mixxx's EngineXfader DSP.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The audio path performs no allocation, no locking and no I/O. Control values
 * use fixed-width lock-free atomics. Track samples are immutable;
 * ownership is retained off the audio thread for the engine lifetime.
 *
 * Keylock uses a preallocated granular overlap-add time stretcher: source is
 * read at unit rate while grains are scheduled at the requested tempo. Its
 * pitch behavior is experimental and has not been validated against a reference.
 */
#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <span>
#include <vector>

#include "WavReader.h"
#include "engine/enginexfader.h"

namespace mixsphere {

static_assert(std::atomic<float>::is_always_lock_free);
static_assert(std::atomic<bool>::is_always_lock_free);
static_assert(std::atomic<uint32_t>::is_always_lock_free);
static_assert(std::atomic<int>::is_always_lock_free);
static_assert(std::atomic<const void*>::is_always_lock_free);

class DeckEngine {
  public:
    struct Deck {
        std::atomic<const PcmTrack*> track{nullptr};
        // Micro-rate fixed-point tempo plus one keylock bit; one 32-bit
        // atomic gives the audio callback a coherent control snapshot.
        std::atomic<uint32_t> tempoControl{1000000u};
        std::atomic<bool> playing{false};
        std::atomic<float> gain{1.0f};
        std::atomic<float> seekFraction{0.0f};
        std::atomic<uint32_t> seekGeneration{0};
        std::atomic<float> renderedTempo{1.0f};
        std::atomic<bool> renderedKeylock{false};
        // These cursors are owned and changed only by the audio callback.
        uint32_t appliedSeekGeneration = 0;
        double position = 0.0;
        double outputTime = 0.0;
    };

    explicit DeckEngine(int outputSampleRate);
    void render(std::span<float> interleaved);

    bool load(int deck, std::unique_ptr<const PcmTrack> track);
    void setPlaying(int deck, bool playing);
    void setTempo(int deck, double tempo, bool keylock);
    void setGain(int deck, double gain);
    void setCrossfader(double position);
    void seek(int deck, double fraction);

    double renderedEnergy() const { return m_energy.load(std::memory_order_relaxed); }
    float renderedTempo(int deck) const;
    bool renderedKeylock(int deck) const;
    int outputSampleRate() const { return m_outputSampleRate.load(std::memory_order_relaxed); }
    void setOutputSampleRate(int sampleRate);

  private:
    void renderDeck(Deck& deck, float gain, std::span<float> interleaved);
    void renderPlain(Deck& deck, const PcmTrack& track, float gain, float tempo,
            std::span<float> interleaved);
    void renderKeylocked(Deck& deck, const PcmTrack& track, float gain, float tempo,
            std::span<float> interleaved);
    static void sampleAt(const PcmTrack& track, double position, float& left, float& right);

    std::array<Deck, 2> m_decks;
    // Track ownership and replacement happen on control threads. The callback
    // reads only the published raw pointer and never adjusts a reference count.
    std::mutex m_trackMutex;
    std::vector<std::unique_ptr<const PcmTrack>> m_ownedTracks;
    std::atomic<float> m_crossfader{0.0f};
    std::atomic<float> m_energy{0.0f};
    std::atomic<int> m_outputSampleRate;
};

} // namespace mixsphere
