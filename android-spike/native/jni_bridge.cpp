/*
 * Mixsphere Phase 0 spike - JNI bridge.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Thin ABI only: no audio processing, no state beyond the engine and output.
 */
#include <jni.h>

#include <exception>
#include <memory>
#include <new>
#include <string>
#include <utility>

#include "DeckEngine.h"
#include "OboeOutput.h"
#include "WavReader.h"

namespace {

struct EngineHandle {
    explicit EngineHandle(int sampleRate)
            : engine(sampleRate),
              output(&engine) {
    }
    mixsphere::DeckEngine engine;
    mixsphere::OboeOutput output;
};

EngineHandle* handleOf(jlong handle) {
    return reinterpret_cast<EngineHandle*>(handle);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeCreate(JNIEnv*, jobject, jint sampleRate) {
    if (sampleRate <= 0) {
        return 0;
    }
    try {
        return reinterpret_cast<jlong>(new EngineHandle(sampleRate));
    } catch (const std::bad_alloc&) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete handleOf(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeLoadTrack(
        JNIEnv* env, jobject, jlong handle, jint deck, jstring path) {
    auto* h = handleOf(handle);
    if (h == nullptr || path == nullptr) {
        return JNI_FALSE;
    }
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) {
        return JNI_FALSE;
    }
    std::string fileName;
    try {
        fileName.assign(chars);
    } catch (const std::exception&) {
        env->ReleaseStringUTFChars(path, chars);
        return JNI_FALSE;
    }
    env->ReleaseStringUTFChars(path, chars);

    try {
        auto decoded = std::make_unique<mixsphere::PcmTrack>(
                mixsphere::WavReader::read(fileName));
        if (!decoded->valid()) {
            return JNI_FALSE;
        }
        return h->engine.load(deck, std::move(decoded)) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::bad_alloc&) {
        return JNI_FALSE;
    } catch (const std::exception&) {
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeStartAudio(JNIEnv*, jobject, jlong handle) {
    auto* h = handleOf(handle);
    return h != nullptr && h->output.start(h->engine.outputSampleRate()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativePlay(
        JNIEnv*, jobject, jlong handle, jint deck, jboolean playing) {
    if (auto* h = handleOf(handle)) {
        h->engine.setPlaying(deck, playing == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeSetTempo(
        JNIEnv*, jobject, jlong handle, jint deck, jdouble tempo, jboolean keylock) {
    if (auto* h = handleOf(handle)) {
        h->engine.setTempo(deck, tempo, keylock == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeSetCrossfader(
        JNIEnv*, jobject, jlong handle, jdouble position) {
    if (auto* h = handleOf(handle)) {
        h->engine.setCrossfader(position);
    }
}

JNIEXPORT void JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeSeek(
        JNIEnv*, jobject, jlong handle, jint deck, jdouble fraction) {
    if (auto* h = handleOf(handle)) {
        h->engine.seek(deck, fraction);
    }
}

JNIEXPORT void JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeSetGain(
        JNIEnv*, jobject, jlong handle, jint deck, jdouble gain) {
    if (auto* h = handleOf(handle)) {
        h->engine.setGain(deck, gain);
    }
}

JNIEXPORT jdouble JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeRenderedEnergy(JNIEnv*, jobject, jlong handle) {
    auto* h = handleOf(handle);
    return h != nullptr ? h->engine.renderedEnergy() : 0.0;
}

JNIEXPORT jdouble JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeRenderedTempo(
        JNIEnv*, jobject, jlong handle, jint deck) {
    auto* h = handleOf(handle);
    return h != nullptr ? h->engine.renderedTempo(deck) : 0.0;
}

JNIEXPORT jboolean JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeRenderedKeylock(
        JNIEnv*, jobject, jlong handle, jint deck) {
    auto* h = handleOf(handle);
    return h != nullptr && h->engine.renderedKeylock(deck) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeAudioApi(JNIEnv* env, jobject, jlong handle) {
    auto* h = handleOf(handle);
    return env->NewStringUTF(h != nullptr ? h->output.apiName().c_str() : "none");
}

JNIEXPORT jdouble JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeLatencyMs(JNIEnv*, jobject, jlong handle) {
    auto* h = handleOf(handle);
    return h != nullptr ? h->output.estimatedLatencyMs() : -1.0;
}

JNIEXPORT jdouble JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeCallbackMicros(JNIEnv*, jobject, jlong handle) {
    auto* h = handleOf(handle);
    return h != nullptr ? h->output.lastCallbackMicros() : -1.0;
}

JNIEXPORT jint JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeSampleRate(JNIEnv*, jobject, jlong handle) {
    auto* h = handleOf(handle);
    return h != nullptr ? h->output.actualSampleRate() : 0;
}

} // extern "C"
