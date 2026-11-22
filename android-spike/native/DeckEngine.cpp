/*
 * Mixsphere Phase 0 spike - two-deck engine using Mixxx's EngineXfader DSP.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "DeckEngine.h"

#include <algorithm>
#include <cmath>

namespace mixsphere {
namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr int kGrainSourceFrames = 1024;
constexpr uint32_t kKeylockBit = 0x80000000u;
constexpr uint32_t kTempoScale = 1000000u;

} // namespace

DeckEngine::DeckEngine(int outputSampleRate) : m_outputSampleRate(outputSampleRate) {
    for (auto& deck : m_decks) {
        deck.gain.store(1.0f, std::memory_order_relaxed);
    }
}

void DeckEngine::setOutputSampleRate(int sampleRate) {
    if (sampleRate > 0) {
        m_outputSampleRate.store(sampleRate, std::memory_order_relaxed);
    }
}

bool DeckEngine::load(int deck, std::unique_ptr<const PcmTrack> track) {
    if (deck < 0 || deck >= static_cast<int>(m_decks.size()) || !track || !track->valid()) {
        return false;
    }
    std::lock_guard lock(m_trackMutex);
    const PcmTrack* published = track.get();
    m_ownedTracks.push_back(std::move(track));
    auto& target = m_decks[deck];
    target.track.store(published, std::memory_order_release);
    target.seekFraction.store(0.0f, std::memory_order_relaxed);
    target.seekGeneration.fetch_add(1, std::memory_order_release);
    return true;
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
    if (!std::isfinite(tempo)) {
        return;
    }
    const double clamped = std::clamp(tempo, 0.05, 4.0);
    uint32_t control = static_cast<uint32_t>(std::lround(clamped * kTempoScale));
    if (keylock) {
        control |= kKeylockBit;
    }
    m_decks[deck].tempoControl.store(control, std::memory_order_relaxed);
}

void DeckEngine::setGain(int deck, double gain) {
    if (deck >= 0 && deck <= 1 && std::isfinite(gain)) {
        m_decks[deck].gain.store(static_cast<float>(std::clamp(gain, 0.0, 2.0)),
                std::memory_order_relaxed);
    }
}

void DeckEngine::setCrossfader(double position) {
    if (!std::isfinite(position)) {
        return;
    }
    m_crossfader.store(static_cast<float>(std::clamp(position, -1.0, 1.0)),
            std::memory_order_relaxed);
}

void DeckEngine::seek(int deck, double fraction) {
    if (deck < 0 || deck > 1 || !std::isfinite(fraction)) {
        return;
    }
    auto& target = m_decks[deck];
    std::lock_guard lock(m_trackMutex);
    target.seekFraction.store(static_cast<float>(std::clamp(fraction, 0.0, 1.0)),
            std::memory_order_relaxed);
    target.seekGeneration.fetch_add(1, std::memory_order_release);
}

float DeckEngine::renderedTempo(int deck) const {
    if (deck < 0 || deck >= static_cast<int>(m_decks.size())) {
        return 0.0f;
    }
    return m_decks[deck].renderedTempo.load(std::memory_order_relaxed);
}

bool DeckEngine::renderedKeylock(int deck) const {
    if (deck < 0 || deck >= static_cast<int>(m_decks.size())) {
        return false;
    }
    return m_decks[deck].renderedKeylock.load(std::memory_order_relaxed);
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
    std::fill(interleaved.begin(), interleaved.end(), 0.0f);

    CSAMPLE_GAIN gain1 = 0.0f;
    CSAMPLE_GAIN gain2 = 0.0f;
    EngineXfader::getXfadeGains(m_crossfader.load(std::memory_order_relaxed), 1.0,
            EngineXfader::getPowerCalibration(1.0), MIXXX_XFADER_CONSTPWR, false,
            &gain1, &gain2);

    renderDeck(m_decks[0], gain1 * m_decks[0].gain.load(std::memory_order_relaxed), interleaved);
    renderDeck(m_decks[1], gain2 * m_decks[1].gain.load(std::memory_order_relaxed), interleaved);

    double energy = 0.0;
    for (float value : interleaved) {
        energy += static_cast<double>(value) * value;
    }
    m_energy.store(static_cast<float>(energy), std::memory_order_relaxed);
}

void DeckEngine::renderDeck(Deck& deck, float gain, std::span<float> interleaved) {
    const uint32_t tempoControl = deck.tempoControl.load(std::memory_order_relaxed);
    const float tempo = static_cast<float>(tempoControl & ~kKeylockBit) / kTempoScale;
    const bool keylock = (tempoControl & kKeylockBit) != 0;
    deck.renderedTempo.store(tempo, std::memory_order_relaxed);
    deck.renderedKeylock.store(keylock, std::memory_order_relaxed);

    const PcmTrack* track = deck.track.load(std::memory_order_acquire);
    if (track == nullptr || !track->valid() || track->channels < 1 || track->channels > 2) {
        return;
    }
    const uint32_t seekGeneration = deck.seekGeneration.load(std::memory_order_acquire);
    if (seekGeneration != deck.appliedSeekGeneration) {
        const double fraction = deck.seekFraction.load(std::memory_order_relaxed);
        deck.position = fraction * track->frames();
        const double baseRate = static_cast<double>(track->sampleRate) /
                m_outputSampleRate.load(std::memory_order_relaxed);
        deck.outputTime = baseRate > 0.0 ? deck.position / baseRate : 0.0;
        deck.appliedSeekGeneration = seekGeneration;
    }
    if (gain <= 0.0f || !deck.playing.load(std::memory_order_relaxed)) {
        return;
    }
    if (keylock) {
        renderKeylocked(deck, *track, gain, tempo, interleaved);
    } else {
        renderPlain(deck, *track, gain, tempo, interleaved);
    }
}

void DeckEngine::renderPlain(Deck& deck, const PcmTrack& track, float gain, float tempo,
        std::span<float> interleaved) {
    const int outFrames = static_cast<int>(interleaved.size() / 2);
    const double baseRate = static_cast<double>(track.sampleRate) /
            m_outputSampleRate.load(std::memory_order_relaxed);
    const double step = baseRate * tempo;
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

void DeckEngine::renderKeylocked(Deck& deck, const PcmTrack& track, float gain, float tempo,
        std::span<float> interleaved) {
    const int outFrames = static_cast<int>(interleaved.size() / 2);
    const double baseRate = static_cast<double>(track.sampleRate) /
            m_outputSampleRate.load(std::memory_order_relaxed);
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
