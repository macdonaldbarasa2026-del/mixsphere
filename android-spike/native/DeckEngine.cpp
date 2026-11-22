/*
 * Mixsphere Phase 0 spike - two-deck engine using Mixxx's EngineXfader DSP.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "DeckEngine.h"

#include <algorithm>

namespace mixsphere {
namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr int kGrainSourceFrames = 1024;

} // namespace

DeckEngine::DeckEngine(int outputSampleRate) : m_outputSampleRate(outputSampleRate) {
    for (auto& deck : m_decks) {
        deck.gain.store(1.0);
    }
}

void DeckEngine::load(int deck, std::shared_ptr<const PcmTrack> track) {
    if (deck < 0 || deck > 1) {
        return;
    }
    m_decks[deck].position = 0.0;
    m_decks[deck].outputTime = 0.0;
    m_decks[deck].track.store(std::move(track));
}

void DeckEngine::setPlaying(int deck, bool playing) {
    if (deck >= 0 && deck <= 1) {
        m_decks[deck].playing.store(playing, std::memory_order_relaxed);
    }
}

void DeckEngine::setTempo(int deck, double tempo, bool keylock) {
    if (deck < 0 || deck > 1) {
        return;
    }
    const double clamped = std::clamp(tempo, 0.05, 4.0);
    m_decks[deck].tempo.store(clamped, std::memory_order_relaxed);
    m_decks[deck].keylock.store(keylock, std::memory_order_relaxed);
}

void DeckEngine::setGain(int deck, double gain) {
    if (deck >= 0 && deck <= 1) {
        m_decks[deck].gain.store(std::clamp(gain, 0.0, 2.0), std::memory_order_relaxed);
    }
}

void DeckEngine::setCrossfader(double position) {
    m_crossfader.store(std::clamp(position, -1.0, 1.0), std::memory_order_relaxed);
}

void DeckEngine::seek(int deck, double fraction) {
    if (deck < 0 || deck > 1) {
        return;
    }
    auto track = m_decks[deck].track.load();
    if (track && track->frames() > 0) {
        m_decks[deck].position = std::clamp(fraction, 0.0, 1.0) * track->frames();
    }
}

void DeckEngine::sampleAt(const PcmTrack& track, double position, float& left, float& right) {
    const int frames = track.frames();
    if (frames <= 0) {
        left = right = 0.0f;
        return;
    }
    if (position < 0.0 || position >= frames) {
        left = right = 0.0f;
        return;
    }
    const int i0 = static_cast<int>(position);
    const int i1 = std::min(i0 + 1, frames - 1);
    const float f = static_cast<float>(position - i0);
    const size_t l0 = static_cast<size_t>(i0) * track.channels;
    const size_t l1 = static_cast<size_t>(i1) * track.channels;
    left = track.samples[l0] * (1.0f - f) + track.samples[l1] * f;
    right = track.channels == 2 ?
            track.samples[l0 + 1] * (1.0f - f) + track.samples[l1 + 1] * f : left;
}

void DeckEngine::render(std::span<float> interleaved) {
    const int frames = static_cast<int>(interleaved.size() / 2);
    std::fill(interleaved.begin(), interleaved.end(), 0.0f);

    CSAMPLE_GAIN gain1 = 0.0f;
    CSAMPLE_GAIN gain2 = 0.0f;
    EngineXfader::getXfadeGains(m_crossfader.load(std::memory_order_relaxed), 1.0,
            EngineXfader::getPowerCalibration(1.0), MIXXX_XFADER_CONSTPWR, false,
            &gain1, &gain2);

    renderDeck(m_decks[0], gain1 * static_cast<float>(m_decks[0].gain.load()), interleaved);
    renderDeck(m_decks[1], gain2 * static_cast<float>(m_decks[1].gain.load()), interleaved);

    double energy = 0.0;
    for (float value : interleaved) {
        energy += static_cast<double>(value) * value;
    }
    m_energy.store(energy, std::memory_order_relaxed);
}

void DeckEngine::renderDeck(Deck& deck, float gain, std::span<float> interleaved) {
    if (gain <= 0.0f || !deck.playing.load(std::memory_order_relaxed)) {
        return;
    }
    auto track = deck.track.load();
    if (!track || !track->valid() || track->channels < 1 || track->channels > 2) {
        return;
    }
    if (deck.keylock.load(std::memory_order_relaxed)) {
        renderKeylocked(deck, *track, gain, interleaved);
    } else {
        renderPlain(deck, *track, gain, interleaved);
    }
}

void DeckEngine::renderPlain(Deck& deck, const PcmTrack& track, float gain,
        std::span<float> interleaved) {
    const int outFrames = static_cast<int>(interleaved.size() / 2);
    const double baseRate = static_cast<double>(track.sampleRate) / m_outputSampleRate;
    const double step = baseRate * deck.tempo.load(std::memory_order_relaxed);
    for (int i = 0; i < outFrames; ++i) {
        float left = 0.0f;
        float right = 0.0f;
        if (deck.position < track.frames()) {
            sampleAt(track, deck.position, left, right);
        }
        interleaved[2 * i] += left * gain;
        interleaved[2 * i + 1] += right * gain;
        deck.position += step;
        if (deck.position >= track.frames()) {
            deck.position = 0.0;
        }
    }
}

void DeckEngine::renderKeylocked(Deck& deck, const PcmTrack& track, float gain,
        std::span<float> interleaved) {
    const int outFrames = static_cast<int>(interleaved.size() / 2);
    const double baseRate = static_cast<double>(track.sampleRate) / m_outputSampleRate;
    const double tempo = deck.tempo.load(std::memory_order_relaxed);
    const double grainOut = kGrainSourceFrames / tempo;
    const double hopOut = grainOut * 0.5;
    const double hopSource = kGrainSourceFrames * 0.5;

    for (int i = 0; i < outFrames; ++i) {
        const double t = deck.outputTime;
        const long firstGrain = std::max(0L, static_cast<long>(std::floor(t / hopOut)) - 1);
        const long lastGrain = firstGrain + 3;
        float left = 0.0f;
        float right = 0.0f;
        float windowSum = 0.0f;

        for (long g = firstGrain; g <= lastGrain; ++g) {
            const double launch = static_cast<double>(g) * hopOut;
            const double local = (t - launch) / grainOut;
            if (local < 0.0 || local > 1.0) {
                continue;
            }
            const double window = 0.5 - 0.5 * std::cos(2.0 * kPi * local);
            const double sourcePosition = static_cast<double>(g) * hopSource +
                    (t - launch) * baseRate;
            if (sourcePosition < 0.0 || sourcePosition >= track.frames()) {
                continue;
            }
            float gl = 0.0f;
            float gr = 0.0f;
            sampleAt(track, sourcePosition, gl, gr);
            const float w = static_cast<float>(window);
            left += gl * w;
            right += gr * w;
            windowSum += w;
        }

        if (windowSum > 0.0f) {
            interleaved[2 * i] += (left / windowSum) * gain;
            interleaved[2 * i + 1] += (right / windowSum) * gain;
        }
        deck.outputTime += 1.0;
        if (deck.outputTime * baseRate > track.frames() * 2.0) {
            deck.outputTime = 0.0;
        }
    }
}

} // namespace mixsphere
