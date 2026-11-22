/*
 * Mixsphere Phase 0 spike - real PCM WAV reader.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Supports 16-bit signed PCM and 32-bit IEEE float, mono or stereo.
 * No decoder simulation: samples are read from the file bit-exactly.
 */
#include "WavReader.h"

#include <algorithm>
#include <cstring>
#include <fstream>
#include <limits>

namespace mixsphere {
namespace {

uint32_t le32(const unsigned char* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
            (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

uint16_t le16(const unsigned char* p) {
    return static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8);
}

} // namespace

PcmTrack WavReader::read(const std::string& path) {
    PcmTrack track;
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        return track;
    }

    unsigned char header[44];
    in.read(reinterpret_cast<char*>(header), sizeof(header));
    if (in.gcount() != static_cast<std::streamsize>(sizeof(header))) {
        return track;
    }
    if (std::memcmp(header, "RIFF", 4) != 0 || std::memcmp(header + 8, "WAVE", 4) != 0) {
        return track;
    }

    uint16_t audioFormat = 0;
    uint16_t channels = 0;
    uint32_t sampleRate = 0;
    uint16_t bitsPerSample = 0;
    uint32_t dataSize = 0;
    std::streampos dataOffset = -1;

    in.seekg(12, std::ios::beg);
    unsigned char chunk[8];
    while (in.good()) {
        in.read(reinterpret_cast<char*>(chunk), sizeof(chunk));
        if (in.gcount() != static_cast<std::streamsize>(sizeof(chunk))) {
            break;
        }
        const uint32_t size = le32(chunk + 4);
        if (std::memcmp(chunk, "fmt ", 4) == 0) {
            std::vector<unsigned char> fmt(size);
            in.read(reinterpret_cast<char*>(fmt.data()), size);
            if (in.gcount() != static_cast<std::streamsize>(size) || size < 16) {
                return track;
            }
            audioFormat = le16(fmt.data());
            channels = le16(fmt.data() + 2);
            sampleRate = le32(fmt.data() + 4);
            bitsPerSample = le16(fmt.data() + 14);
        } else if (std::memcmp(chunk, "data", 4) == 0) {
            dataSize = size;
            dataOffset = in.tellg();
        }
        if (dataOffset >= 0) {
            break;
        }
        in.seekg(size + (size & 1u), std::ios::cur);
    }

    if (dataOffset < 0 || (channels != 1 && channels != 2) || sampleRate == 0) {
        return track;
    }
    if (!((audioFormat == 1 && bitsPerSample == 16) ||
            (audioFormat == 3 && bitsPerSample == 32))) {
        return track;
    }

    in.seekg(dataOffset, std::ios::beg);
    std::vector<unsigned char> data(dataSize);
    in.read(reinterpret_cast<char*>(data.data()), dataSize);
    if (in.gcount() != static_cast<std::streamsize>(dataSize)) {
        return track;
    }

    const int frames = static_cast<int>(dataSize / (channels * (bitsPerSample / 8)));
    track.channels = channels;
    track.sampleRate = static_cast<int>(sampleRate);
    track.samples.resize(static_cast<size_t>(frames) * channels);

    for (int f = 0; f < frames; ++f) {
        for (int c = 0; c < channels; ++c) {
            const size_t byte = static_cast<size_t>(f) * channels + c;
            if (audioFormat == 1) {
                const size_t off = byte * 2;
                const int16_t raw = static_cast<int16_t>(le16(data.data() + off));
                track.samples[byte] = static_cast<float>(raw) / 32768.0f;
            } else {
                const size_t off = byte * 4;
                uint32_t bits = le32(data.data() + off);
                float value;
                std::memcpy(&value, &bits, sizeof(value));
                track.samples[byte] = value;
            }
        }
    }
    return track;
}

} // namespace mixsphere
