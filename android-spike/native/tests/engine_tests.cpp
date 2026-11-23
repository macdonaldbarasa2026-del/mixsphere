/*
 * Mixsphere Phase 0 - native DSP unit tests.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <gtest/gtest.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <limits>
#include <utility>
#include <vector>

#include "DeckEngine.h"
#include "StemSeparator.h"
#include "WavReader.h"
#include "engine/enginexfader.h"

namespace {

using namespace mixsphere;

PcmTrack makeTrack(int frames, int sampleRate, double frequency) {
    PcmTrack track;
    track.channels = 2;
    track.sampleRate = sampleRate;
    track.samples.resize(static_cast<size_t>(frames) * 2);
    for (int i = 0; i < frames; ++i) {
        const float value = static_cast<float>(
                std::sin(2.0 * 3.14159265358979323846 * frequency * i / sampleRate) * 0.5);
        track.samples[static_cast<size_t>(i) * 2] = value;
        track.samples[static_cast<size_t>(i) * 2 + 1] = value;
    }
    return track;
}

void writePcm16Wav(const char* path, const PcmTrack& track) {
    const int dataSize = track.frames() * track.channels * 2;
    std::vector<unsigned char> bytes(static_cast<size_t>(44 + dataSize), 0);
    auto put32 = [&](size_t at, uint32_t v) {
        bytes[at] = v & 0xff;
        bytes[at + 1] = (v >> 8) & 0xff;
        bytes[at + 2] = (v >> 16) & 0xff;
        bytes[at + 3] = (v >> 24) & 0xff;
    };
    auto put16 = [&](size_t at, uint16_t v) {
        bytes[at] = v & 0xff;
        bytes[at + 1] = (v >> 8) & 0xff;
    };
    const char* riff = "RIFF";
    const char* wave = "WAVE";
    const char* fmt = "fmt ";
    const char* data = "data";
    for (int i = 0; i < 4; ++i) {
        bytes[i] = riff[i];
        bytes[8 + i] = wave[i];
        bytes[12 + i] = fmt[i];
        bytes[36 + i] = data[i];
    }
    put32(4, static_cast<uint32_t>(36 + dataSize));
    put32(16, 16);
    put16(20, 1);
    put16(22, static_cast<uint16_t>(track.channels));
    put32(24, static_cast<uint32_t>(track.sampleRate));
    put32(28, static_cast<uint32_t>(track.sampleRate * track.channels * 2));
    put16(32, static_cast<uint16_t>(track.channels * 2));
    put16(34, 16);
    put32(40, static_cast<uint32_t>(dataSize));
    for (int f = 0; f < track.frames(); ++f) {
        for (int c = 0; c < track.channels; ++c) {
            const int16_t sample = static_cast<int16_t>(
                    track.samples[static_cast<size_t>(f) * track.channels + c] * 32768.0f);
            const size_t at = 44 + (static_cast<size_t>(f) * track.channels + c) * 2;
            bytes[at] = static_cast<unsigned char>(sample & 0xff);
            bytes[at + 1] = static_cast<unsigned char>((sample >> 8) & 0xff);
        }
    }
    std::ofstream out(path, std::ios::binary);
    out.write(reinterpret_cast<const char*>(bytes.data()), bytes.size());
}

} // namespace

TEST(WavReaderTest, ReadsPcm16StereoExactly) {
    const auto source = makeTrack(4096, 48000, 440.0);
    const char* path = "/tmp/mixsphere_phase0_test.wav";
    writePcm16Wav(path, source);

    const auto track = WavReader::read(path);
    ASSERT_TRUE(track.valid());
    EXPECT_EQ(track.channels, 2);
    EXPECT_EQ(track.sampleRate, 48000);
    EXPECT_EQ(track.frames(), 4096);
    EXPECT_NEAR(track.samples[0], source.samples[0], 1.0e-4);
    EXPECT_NEAR(track.samples[100], source.samples[100], 1.0e-4);
    std::remove(path);
}

TEST(MixxxXfaderTest, ConstantPowerGainsAreNormalised) {
    const double calibration = EngineXfader::getPowerCalibration(1.0);
    EXPECT_NEAR(calibration, 0.5, 1.0e-9);
    CSAMPLE_GAIN left = 0.0f;
    CSAMPLE_GAIN right = 0.0f;
    EngineXfader::getXfadeGains(0.0, 1.0, calibration, MIXXX_XFADER_CONSTPWR, false, &left, &right);
    const double power = static_cast<double>(left) * left + static_cast<double>(right) * right;
    EXPECT_NEAR(power, 1.0, 1.0e-5);
}

TEST(DeckEngineTest, RendersAudibleOutput) {
    DeckEngine engine(48000);
    auto track = std::make_unique<const PcmTrack>(makeTrack(96000, 48000, 440.0));
    ASSERT_TRUE(engine.load(0, std::move(track)));
    engine.setCrossfader(-1.0);
    engine.play(0, true);
    std::vector<float> buffer(4096);
    engine.render(buffer);
    EXPECT_GT(engine.renderedEnergy(), 1.0);
}

TEST(DeckEngineTest, TempoChangeWithKeylockRemainsAudible) {
    DeckEngine engine(48000);
    auto track = std::make_unique<const PcmTrack>(makeTrack(96000, 48000, 440.0));
    ASSERT_TRUE(engine.load(0, std::move(track)));
    engine.setCrossfader(-1.0);
    engine.play(0, true);
    std::vector<float> buffer(8192);

    engine.setTempo(0, 1.10, true);
    engine.render(buffer);
    EXPECT_NEAR(engine.renderedTempo(0), 1.10, 0.001);
    EXPECT_TRUE(engine.renderedKeylock(0));
    EXPECT_GT(engine.renderedEnergy(), 1.0);

    engine.setTempo(0, 0.90, true);
    engine.render(buffer);
    EXPECT_NEAR(engine.renderedTempo(0), 0.90, 0.001);
    EXPECT_TRUE(engine.renderedKeylock(0));
    EXPECT_GT(engine.renderedEnergy(), 1.0);
}

TEST(StemSeparatorTest, StemsReconstructInputAndCarryEnergy) {
    PcmTrack input;
    input.channels = 2;
    input.sampleRate = 48000;
    constexpr int kFrames = 48000;
    input.samples.resize(kFrames * 2);
    for (int i = 0; i < kFrames; ++i) {
        const double t = static_cast<double>(i) / input.sampleRate;
        const double vocal = 0.35 * std::sin(2.0 * 3.14159265358979323846 * 440.0 * t);
        const double harmony = 0.25 * std::sin(2.0 * 3.14159265358979323846 * 261.6 * t);
        const double beat = (i % 12000) < 900 ? 0.4 * std::sin(2.0 * 3.14159265358979323846 * 60.0 * t) : 0.0;
        const float value = static_cast<float>(vocal + harmony + beat);
        input.samples[i * 2] = value;
        input.samples[i * 2 + 1] = value;
    }

    const StemSet stems = separateStems(input);
    ASSERT_EQ(stems.vocals.channels, 2);
    ASSERT_EQ(stems.harmony.frames(), kFrames);
    ASSERT_GT(stems.vocals.samples.size(), 0U);

    double energy = 0.0;
    for (float sample : stems.vocals.samples) {
        energy += sample * sample;
    }
    EXPECT_GT(energy, 1.0e-3);

    double error = 0.0;
    for (size_t i = 0; i < input.samples.size(); ++i) {
        const float sum = stems.vocals.samples[i] + stems.beat.samples[i] + stems.harmony.samples[i];
        error += std::abs(sum - input.samples[i]);
    }
    EXPECT_LT(error / input.samples.size(), 0.02);
}

TEST(WavReaderTest, RejectsTruncatedAndNonWaveInput) {
    const char* path = "/tmp/mixsphere_phase0_invalid.wav";
    {
        std::ofstream out(path, std::ios::binary | std::ios::trunc);
        out << "not a wave";
    }
    EXPECT_FALSE(WavReader::read(path).valid());
    std::remove(path);
}

TEST(DeckEngineTest, IgnoresNonFiniteControls) {
    DeckEngine engine(48000);
    auto track = std::make_unique<const PcmTrack>(makeTrack(4096, 48000, 440.0));
    ASSERT_TRUE(engine.load(0, std::move(track)));
    engine.setTempo(0, std::numeric_limits<double>::quiet_NaN(), true);
    engine.setCrossfader(std::numeric_limits<double>::infinity());
    engine.setGain(0, std::numeric_limits<double>::quiet_NaN());
    engine.seek(0, std::numeric_limits<double>::quiet_NaN());

    engine.setCrossfader(-1.0);
    engine.setPlaying(0, true);
    std::vector<float> buffer(2048);
    engine.render(buffer);
    EXPECT_NEAR(engine.renderedTempo(0), 1.0, 1.0e-6);
    EXPECT_GT(engine.renderedEnergy(), 0.0);
}
