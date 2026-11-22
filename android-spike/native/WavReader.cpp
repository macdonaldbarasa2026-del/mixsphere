/*
 * Mixsphere Phase 0 spike - real PCM WAV reader.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Supports 16-bit signed PCM and 32-bit IEEE float, mono or stereo.
 * No decoder simulation: samples are read from the file bit-exactly.
 */
#include "WavReader.h"

#include <algorithm>
#include <cmath>
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

    unsigned char header[12];
    in.read(reinterpret_cast<char*>(header), sizeof(header));
    if (in.gcount() != static_cast<std::streamsize>(sizeof(header)) ||
            std::memcmp(header, "RIFF", 4) != 0 || std::memcmp(header + 8, "WAVE", 4) != 0) {
        return track;
    }
    const uint32_t riffSize = le32(header + 4);
    in.seekg(0, std::ios::end);
    const std::streamoff fileSize = in.tellg();
    if (fileSize < 12 || riffSize > static_cast<uint64_t>(fileSize - 8)) {
        return track;
    }
    const std::streamoff riffEnd = static_cast<std::streamoff>(riffSize) + 8;

    uint16_t audioFormat = 0;
    uint16_t channels = 0;
    uint32_t sampleRate = 0;
    uint16_t bitsPerSample = 0;
    uint32_t dataSize = 0;
    std::streampos dataOffset = -1;

    in.seekg(12, std::ios::beg);
    unsigned char chunk[8];
    while (in.good()) {
        const std::streamoff chunkHeaderOffset = static_cast<std::streamoff>(in.tellg());
        if (chunkHeaderOffset < 0 || chunkHeaderOffset + 8 > riffEnd) {
            break;
        }
        in.read(reinterpret_cast<char*>(chunk), sizeof(chunk));
        if (in.gcount() != static_cast<std::streamsize>(sizeof(chunk))) {
            break;
        }
        const uint32_t size = le32(chunk + 4);
        const std::streamoff payloadOffset = static_cast<std::streamoff>(in.tellg());
        if (payloadOffset < 0 || size > static_cast<uint64_t>(riffEnd - payloadOffset)) {
            return track;
        }
        if (std::memcmp(chunk, "fmt ", 4) == 0) {
            if (size < 16 || size > 4096) {
                return track;
            }
            unsigned char fmt[16];
            in.read(reinterpret_cast<char*>(fmt), sizeof(fmt));
            if (in.gcount() != static_cast<std::streamsize>(sizeof(fmt))) {
                return track;
            }
            audioFormat = le16(fmt);
            channels = le16(fmt + 2);
            sampleRate = le32(fmt + 4);
            bitsPerSample = le16(fmt + 14);
            in.seekg(payloadOffset + size + (size & 1u), std::ios::beg);
        } else if (std::memcmp(chunk, "data", 4) == 0) {
            dataSize = size;
            dataOffset = payloadOffset;
            in.seekg(payloadOffset + size + (size & 1u), std::ios::beg);
        } else {
            in.seekg(payloadOffset + size + (size & 1u), std::ios::beg);
        }
        if (dataOffset >= 0 && audioFormat != 0) {
            break;
        }
    }

    if (dataOffset < 0 || (channels != 1 && channels != 2) || sampleRate == 0) {
        return track;
    }
    if (!((audioFormat == 1 && bitsPerSample == 16) ||
            (audioFormat == 3 && bitsPerSample == 32))) {
        return track;
    }
    const uint32_t bytesPerSample = bitsPerSample / 8;
    const uint32_t bytesPerFrame = channels * bytesPerSample;
    constexpr uint32_t kMaxDecodedBytes = 128u * 1024u * 1024u;
    if (bytesPerFrame == 0 || dataSize % bytesPerFrame != 0 ||
            dataSize / bytesPerFrame > static_cast<uint32_t>(
                    std::numeric_limits<int>::max()) ||
            static_cast<uint64_t>(dataSize / bytesPerFrame) * channels * sizeof(float) >
                    kMaxDecodedBytes) {
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
                track.samples[byte] = std::isfinite(value) ? std::clamp(value, -1.0f, 1.0f) : 0.0f;
            }
        }
    }
    return track;
}

} // namespace mixsphere
