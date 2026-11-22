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
    const int actualSampleRate = m_stream->getSampleRate();
    m_actualSampleRate.store(actualSampleRate, std::memory_order_relaxed);
    m_engine->setOutputSampleRate(actualSampleRate);
    return true;
}

bool OboeOutput::start(int requestedSampleRate) {
    if (m_stream && m_stream->getState() == oboe::StreamState::Started) {
        return true;
    }
    if (m_stream) {
        stop();
    }
    if (openStream(oboe::AudioApi::AAudio, requestedSampleRate) &&
            m_stream->requestStart() == oboe::Result::OK) {
        return true;
    }
    stop();
    if (openStream(oboe::AudioApi::OpenSLES, requestedSampleRate) &&
            m_stream->requestStart() == oboe::Result::OK) {
        return true;
    }
    stop();
    return false;
}

void OboeOutput::stop() {
    if (m_stream) {
        m_stream->requestStop();
        m_stream->close();
        m_stream.reset();
    }
    m_api = oboe::AudioApi::Unspecified;
    m_actualSampleRate.store(0, std::memory_order_relaxed);
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
    const auto latency = m_stream->calculateLatencyMillis();
    return latency ? latency.value() : -1.0;
}

double OboeOutput::lastCallbackMicros() const {
    return static_cast<double>(m_lastCallbackMicros.load(std::memory_order_relaxed));
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
    m_lastCallbackMicros.store(
            static_cast<uint32_t>(std::chrono::duration_cast<std::chrono::microseconds>(
                    end - begin).count()),
            std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

} // namespace mixsphere
