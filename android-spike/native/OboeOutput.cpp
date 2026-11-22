/*
 * Mixsphere Phase 0 spike - Oboe output.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "OboeOutput.h"

#include <chrono>

namespace mixsphere {

OboeOutput::OboeOutput(DeckEngine* engine) : m_engine(engine) {}

OboeOutput::~OboeOutput() {
    stop();
}

bool OboeOutput::openStream(oboe::AudioApi api, int requestedSampleRate) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(2)
            ->setSampleRate(requestedSampleRate)
            ->setAudioApi(api)
            ->setDataCallback(this);

    oboe::AudioStream* raw = nullptr;
    const oboe::Result result = builder.openStream(&raw);
    if (result != oboe::Result::OK || raw == nullptr) {
        return false;
    }
    m_stream.reset(raw);
    m_api = api;
    m_actualSampleRate.store(m_stream->getSampleRate());
    return true;
}

bool OboeOutput::start(int requestedSampleRate) {
    if (!openStream(oboe::AudioApi::AAudio, requestedSampleRate) &&
            !openStream(oboe::AudioApi::OpenSLES, requestedSampleRate)) {
        return false;
    }
    return m_stream->requestStart() == oboe::Result::OK;
}

void OboeOutput::stop() {
    if (m_stream) {
        m_stream->requestStop();
        m_stream.reset();
    }
}

std::string OboeOutput::apiName() const {
    switch (m_api) {
        case oboe::AudioApi::AAudio:
            return "AAudio";
        case oboe::AudioApi::OpenSLES:
            return "OpenSLES";
        default:
            return "Unspecified";
    }
}

double OboeOutput::estimatedLatencyMs() const {
    if (!m_stream) {
        return -1.0;
    }
    const auto latency = m_stream->getEstimatedOutputLatency();
    if (!latency) {
        return -1.0;
    }
    return static_cast<double>(latency.value()->count()) / 1000000.0;
}

double OboeOutput::lastCallbackMicros() const {
    return static_cast<double>(m_lastCallbackNanos.load(std::memory_order_relaxed)) / 1000.0;
}

int OboeOutput::actualSampleRate() const {
    return m_actualSampleRate.load();
}

oboe::DataCallbackResult OboeOutput::onAudioReady(oboe::AudioStream* stream, void* audioData,
        int32_t numFrames) {
    const auto begin = std::chrono::steady_clock::now();
    const int channels = stream->getChannelCount();
    m_engine->render(std::span<float>(static_cast<float*>(audioData),
            static_cast<size_t>(numFrames) * channels));
    const auto end = std::chrono::steady_clock::now();
    m_lastCallbackNanos.store(
            static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                    end - begin).count()),
            std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

} // namespace mixsphere
