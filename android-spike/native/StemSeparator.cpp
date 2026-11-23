/*
 * Mixsphere Phase 0 spike - offline stem separation.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "StemSeparator.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstdint>
#include <fstream>
#include <vector>

namespace mixsphere {
namespace {

using Complex = std::complex<float>;
using Spectrum = std::vector<std::vector<Complex>>;

constexpr int kFrame = 1024;
constexpr int kHop = kFrame / 2;
constexpr int kBins = kFrame / 2 + 1;
constexpr int kMedianRadius = 4;
constexpr int kMaxSeconds = 60;
constexpr float kPi = 3.14159265358979323846f;

void fft(std::vector<Complex>& values) {
    const int n = static_cast<int>(values.size());
    for (int i = 1, j = 0; i < n; ++i) {
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) {
            j ^= bit;
        }
        j ^= bit;
        if (i < j) {
            std::swap(values[i], values[j]);
        }
    }
    for (int length = 2; length <= n; length <<= 1) {
        const float angle = -2.0f * kPi / length;
        const Complex step(std::cos(angle), std::sin(angle));
        for (int start = 0; start < n; start += length) {
            Complex twiddle(1.0f, 0.0f);
            for (int i = 0; i < length / 2; ++i) {
                const Complex even = values[start + i];
                const Complex odd = values[start + i + length / 2] * twiddle;
                values[start + i] = even + odd;
                values[start + i + length / 2] = even - odd;
                twiddle *= step;
            }
        }
    }
}

void inverseFft(std::vector<Complex>& values) {
    for (auto& value : values) {
        value = std::conj(value);
    }
    fft(values);
    const float scale = 1.0f / static_cast<float>(values.size());
    for (auto& value : values) {
        value = std::conj(value) * scale;
    }
}

std::vector<float> hannWindow() {
    std::vector<float> window(kFrame);
    for (int i = 0; i < kFrame; ++i) {
        window[i] = 0.5f - 0.5f * std::cos(2.0f * kPi * i / (kFrame - 1));
    }
    return window;
}

Spectrum transform(const std::vector<float>& signal, const std::vector<float>& window) {
    const int count = signal.size() <= static_cast<size_t>(kFrame)
            ? 1
            : static_cast<int>((signal.size() - kFrame + kHop - 1) / kHop) + 1;
    Spectrum spectrum(count, std::vector<Complex>(kBins));
    for (int frame = 0; frame < count; ++frame) {
        std::vector<Complex> buffer(kFrame);
        for (int i = 0; i < kFrame; ++i) {
            const long index = static_cast<long>(frame) * kHop + i;
            const float sample = index >= 0 && index < static_cast<long>(signal.size())
                    ? signal[static_cast<size_t>(index)]
                    : 0.0f;
            buffer[i] = Complex(sample * window[i], 0.0f);
        }
        fft(buffer);
        for (int bin = 0; bin < kBins; ++bin) {
            spectrum[frame][bin] = buffer[bin];
        }
    }
    return spectrum;
}

float medianAt(const std::vector<float>& magnitudes, int frame, int bin, int frames, bool alongTime) {
    std::vector<float> values;
    values.reserve(2 * kMedianRadius + 1);
    for (int delta = -kMedianRadius; delta <= kMedianRadius; ++delta) {
        const int f = alongTime ? frame + delta : frame;
        const int b = alongTime ? bin : bin + delta;
        if (f < 0 || f >= frames || b < 0 || b >= kBins) {
            continue;
        }
        values.push_back(magnitudes[static_cast<size_t>(f) * kBins + b]);
    }
    const auto middle = values.begin() + values.size() / 2;
    std::nth_element(values.begin(), middle, values.end());
    return *middle;
}

float vocalWeight(int bin, int sampleRate) {
    const float frequency = static_cast<float>(bin) * sampleRate / kFrame;
    const float low = std::clamp((frequency - 120.0f) / 380.0f, 0.0f, 1.0f);
    const float high = std::clamp((4800.0f - frequency) / 1600.0f, 0.0f, 1.0f);
    return 0.9f * low * high;
}

std::vector<float> reconstructWithMask(const Spectrum& spectrum,
        const std::vector<float>& mask,
        const std::vector<float>& window,
        int samples) {
    const int frames = static_cast<int>(spectrum.size());
    const size_t length = static_cast<size_t>(samples) + kFrame;
    std::vector<float> output(length, 0.0f);
    std::vector<float> weights(length, 0.0f);
    for (int frame = 0; frame < frames; ++frame) {
        const size_t base = static_cast<size_t>(frame) * kBins;
        std::vector<Complex> buffer(kFrame);
        for (int bin = 0; bin < kFrame; ++bin) {
            if (bin < kBins) {
                buffer[bin] = spectrum[frame][bin] * mask[base + bin];
            } else {
                const int mirrored = kFrame - bin;
                buffer[bin] = std::conj(spectrum[frame][mirrored]) * mask[base + mirrored];
            }
        }
        inverseFft(buffer);
        const size_t offset = static_cast<size_t>(frame) * kHop;
        for (int i = 0; i < kFrame && offset + i < length; ++i) {
            const float weight = window[i] * window[i];
            output[offset + i] += buffer[i].real() * window[i];
            weights[offset + i] += weight;
        }
    }
    for (size_t i = 0; i < output.size(); ++i) {
        output[i] /= std::max(weights[i], 1.0e-6f);
        if (i < static_cast<size_t>(samples)) {
            output[i] = std::clamp(output[i], -1.0f, 1.0f);
        }
    }
    output.resize(samples);
    return output;
}

void put32(std::vector<unsigned char>& bytes, size_t at, uint32_t value) {
    for (int i = 0; i < 4; ++i) {
        bytes[at + i] = static_cast<unsigned char>((value >> (8 * i)) & 0xff);
    }
}

void put16(std::vector<unsigned char>& bytes, size_t at, uint16_t value) {
    for (int i = 0; i < 2; ++i) {
        bytes[at + i] = static_cast<unsigned char>((value >> (8 * i)) & 0xff);
    }
}

} // namespace

StemSet separateStems(const PcmTrack& input) {
    StemSet result;
    if (!input.valid()) {
        return result;
    }
    const int channels = input.channels;
    const int frames = std::min(input.frames(), input.sampleRate * kMaxSeconds);
    const int samples = frames * channels;
    const auto window = hannWindow();

    std::vector<float> mono(static_cast<size_t>(frames));
    for (int i = 0; i < frames; ++i) {
        float sum = 0.0f;
        for (int channel = 0; channel < channels; ++channel) {
            sum += input.samples[static_cast<size_t>(i) * channels + channel];
        }
        mono[i] = sum / channels;
    }

    const Spectrum analysis = transform(mono, window);
    const int spectrumFrames = static_cast<int>(analysis.size());
    std::vector<float> magnitudes(static_cast<size_t>(spectrumFrames) * kBins);
    for (int frame = 0; frame < spectrumFrames; ++frame) {
        for (int bin = 0; bin < kBins; ++bin) {
            magnitudes[static_cast<size_t>(frame) * kBins + bin] =
                    std::abs(analysis[frame][bin]);
        }
    }

    std::vector<float> percussiveMask(magnitudes.size());
    std::vector<float> vocalsMask(magnitudes.size());
    std::vector<float> beatMask(magnitudes.size());
    std::vector<float> harmonyMask(magnitudes.size());
    for (int frame = 0; frame < spectrumFrames; ++frame) {
        for (int bin = 0; bin < kBins; ++bin) {
            const size_t at = static_cast<size_t>(frame) * kBins + bin;
            const float h = medianAt(magnitudes, frame, bin, spectrumFrames, true);
            const float p = medianAt(magnitudes, frame, bin, spectrumFrames, false);
            const float total = h + p + 1.0e-8f;
            const float harmonic = h / total;
            const float percussive = p / total;
            const float voice = vocalWeight(bin, input.sampleRate);
            percussiveMask[at] = percussive;
            vocalsMask[at] = harmonic * voice;
            beatMask[at] = percussive;
            harmonyMask[at] = harmonic * (1.0f - voice);
        }
    }

    result.vocals.channels = channels;
    result.vocals.sampleRate = input.sampleRate;
    result.vocals.samples.resize(samples);
    result.beat.channels = channels;
    result.beat.sampleRate = input.sampleRate;
    result.beat.samples.resize(samples);
    result.harmony.channels = channels;
    result.harmony.sampleRate = input.sampleRate;
    result.harmony.samples.resize(samples);

    for (int channel = 0; channel < channels; ++channel) {
        std::vector<float> signal(static_cast<size_t>(frames));
        for (int i = 0; i < frames; ++i) {
            signal[i] = input.samples[static_cast<size_t>(i) * channels + channel];
        }
        const Spectrum channelSpectrum = transform(signal, window);
        const auto vocals = reconstructWithMask(channelSpectrum, vocalsMask, window, frames);
        const auto beat = reconstructWithMask(channelSpectrum, beatMask, window, frames);
        const auto harmony = reconstructWithMask(channelSpectrum, harmonyMask, window, frames);
        for (int i = 0; i < frames; ++i) {
            result.vocals.samples[static_cast<size_t>(i) * channels + channel] = vocals[i];
            result.beat.samples[static_cast<size_t>(i) * channels + channel] = beat[i];
            result.harmony.samples[static_cast<size_t>(i) * channels + channel] = harmony[i];
        }
    }
    return result;
}

PcmTrack mixStems(const PcmTrack& first, const PcmTrack& second) {
    PcmTrack result;
    if (!first.valid() || !second.valid() || first.channels != second.channels ||
            first.sampleRate != second.sampleRate) {
        return result;
    }
    result.channels = first.channels;
    result.sampleRate = first.sampleRate;
    result.samples.resize(first.samples.size());
    for (size_t i = 0; i < result.samples.size(); ++i) {
        result.samples[i] = std::clamp(first.samples[i] + second.samples[i], -1.0f, 1.0f);
    }
    return result;
}

bool writePcm16Wav(const PcmTrack& track, const std::string& path) {
    if (!track.valid()) {
        return false;
    }
    const size_t frames = static_cast<size_t>(track.frames());
    const size_t dataSize = frames * track.channels * 2;
    std::vector<unsigned char> bytes(44 + dataSize);
    const char riff[4] = {'R', 'I', 'F', 'F'};
    const char wave[4] = {'W', 'A', 'V', 'E'};
    const char fmt[4] = {'f', 'm', 't', ' '};
    const char data[4] = {'d', 'a', 't', 'a'};
    for (int i = 0; i < 4; ++i) {
        bytes[i] = riff[i];
        bytes[8 + i] = wave[i];
        bytes[12 + i] = fmt[i];
        bytes[36 + i] = data[i];
    }
    put32(bytes, 4, static_cast<uint32_t>(36 + dataSize));
    put32(bytes, 16, 16);
    put16(bytes, 20, 1);
    put16(bytes, 22, static_cast<uint16_t>(track.channels));
    put32(bytes, 24, static_cast<uint32_t>(track.sampleRate));
    put32(bytes, 28, static_cast<uint32_t>(track.sampleRate * track.channels * 2));
    put16(bytes, 32, static_cast<uint16_t>(track.channels * 2));
    put16(bytes, 34, 16);
    put32(bytes, 40, static_cast<uint32_t>(dataSize));
    for (size_t i = 0; i < track.samples.size(); ++i) {
        const float value = std::clamp(track.samples[i], -1.0f, 1.0f);
        const int16_t sample = static_cast<int16_t>(value * 32767.0f);
        put16(bytes, 44 + i * 2, static_cast<uint16_t>(sample));
    }
    std::ofstream output(path, std::ios::binary);
    if (!output) {
        return false;
    }
    output.write(reinterpret_cast<const char*>(bytes.data()), bytes.size());
    return output.good();
}

} // namespace mixsphere
