/*
 * Mixsphere Phase 0 spike - JNI bridge.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Thin ABI only: no audio processing, no state beyond the engine and output.
 */
#include <jni.h>

#include <memory>
#include <new>
#include <string>
#include <vector>

#include "DeckEngine.h"
#include "OboeOutput.h"
#include "WavReader.h"

namespace {

struct EngineHandle {
    explicit EngineHandle(int sampleRate)
            : engine(sampleRate), output(&engine) {}
    mixsphere::DeckEngine engine;
    mixsphere::OboeOutput output;
};

EngineHandle* handleOf(jlong handle) {
    return reinterpret_cast<EngineHandle*>(handle);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeCreate(JNIEnv*, jclass, jint sampleRate) {
    try {
        return reinterpret_cast<jlong>(new EngineHandle(sampleRate));
    } catch (const std::bad_alloc&) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete handleOf(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeLoadTrack(
        JNIEnv* env, jclass, jlong handle, jint deck, jstring path) {
    auto* h = handleOf(handle);
    if (h == nullptr || path == nullptr) {
        return JNI_FALSE;
    }
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) {
        return JNI_FALSE;
    }
    const std::string fileName(chars);
    env->ReleaseStringUTFChars(path, chars);

    auto track = std::make_shared<mixsphere::PcmTrack>(mixsphere::WavReader::read(fileName));
    if (!track->valid()) {
        return JNI_FALSE;
    }
    h->engine.load(deck, std::move(track));
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeStartAudio(JNIEnv*, jclass, jlong handle) {
    auto* h = handleOf(handle);
    return h != nullptr && h->output.start(h->engine.outputSampleRate()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativePlay(
        JNIEnv*, jclass, jlong handle, jint deck, jboolean playing) {
    if (auto* h = handleOf(handle)) {
        h->engine.setPlaying(deck, playing == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeSetTempo(
        JNIEnv*, jclass, jlong handle, jint deck, jdouble tempo, jboolean keylock) {
    if (auto* h = handleOf(handle)) {
        h->engine.setTempo(deck, tempo, keylock == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeSetCrossfader(
        JNIEnv*, jclass, jlong handle, jdouble position) {
    if (auto* h = handleOf(handle)) {
        h->engine.setCrossfader(position);
    }
}

JNIEXPORT jdouble JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeRenderedEnergy(JNIEnv*, jclass, jlong handle) {
    auto* h = handleOf(handle);
    return h != nullptr ? h->engine.renderedEnergy() : 0.0;
}

JNIEXPORT jdouble JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeRenderBlock(
        JNIEnv*, jclass, jlong handle, jint frames) {
    auto* h = handleOf(handle);
    if (h == nullptr || frames <= 0) {
        return 0.0;
    }
    static thread_local std::vector<float> scratch;
    scratch.resize(static_cast<size_t>(frames) * 2);
    h->engine.render(scratch);
    return h->engine.renderedEnergy();
}

JNIEXPORT jstring JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeAudioApi(JNIEnv* env, jclass, jlong handle) {
    auto* h = handleOf(handle);
    return env->NewStringUTF(h != nullptr ? h->output.apiName().c_str() : "none");
}

JNIEXPORT jdouble JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeLatencyMs(JNIEnv*, jclass, jlong handle) {
    auto* h = handleOf(handle);
    return h != nullptr ? h->output.estimatedLatencyMs() : -1.0;
}

JNIEXPORT jdouble JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeCallbackMicros(JNIEnv*, jclass, jlong handle) {
    auto* h = handleOf(handle);
    return h != nullptr ? h->output.lastCallbackMicros() : -1.0;
}

JNIEXPORT jint JNICALL
Java_com_mixsphere_engine_MixsphereEngine_nativeSampleRate(JNIEnv*, jclass, jlong handle) {
    auto* h = handleOf(handle);
    return h != nullptr ? h->output.actualSampleRate() : 0;
}

} // extern "C"
