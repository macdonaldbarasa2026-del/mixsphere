/*
 * Mixsphere Phase 0 spike - Oboe output.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * AAudio is preferred; Oboe falls back to OpenSL ES when open() fails.
 * The data callback performs no allocation, locking or I/O.
 */
#pragma once

#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <string>

#include <oboe/Oboe.h>

#include "DeckEngine.h"

namespace mixsphere {

class OboeOutput : public oboe::AudioStreamDataCallback {
  public:
    explicit OboeOutput(DeckEngine* engine);
    ~OboeOutput() override;

    bool start(int requestedSampleRate);
    void stop();
    std::string apiName() const;
    double estimatedLatencyMs() const;
    double lastCallbackMicros() const;
    int actualSampleRate() const;

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
            int32_t numFrames) override;

  private:
    bool openStream(oboe::AudioApi api, int requestedSampleRate);

    DeckEngine* m_engine;
    std::shared_ptr<oboe::AudioStream> m_stream;
    oboe::AudioApi m_api = oboe::AudioApi::Unspecified;
    std::atomic<uint32_t> m_lastCallbackMicros{0};
    std::atomic<int> m_actualSampleRate{0};
};

} // namespace mixsphere
