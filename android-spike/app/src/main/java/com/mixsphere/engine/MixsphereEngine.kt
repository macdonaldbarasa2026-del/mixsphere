/*
 * Mixsphere Phase 0 spike - JNI facade.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.mixsphere.engine

import java.io.Closeable

class MixsphereEngine(sampleRate: Int = 48000) : Closeable {
    private var handle: Long = nativeCreate(sampleRate)

    init {
        require(handle != 0L) { "native engine allocation failed" }
    }

    @Synchronized
    fun loadTrack(deck: Int, path: String): Boolean = nativeLoadTrack(handle, deck, path)
    @Synchronized
    fun startAudio(): Boolean = nativeStartAudio(handle)
    @Synchronized
    fun play(deck: Int, playing: Boolean) = nativePlay(handle, deck, playing)
    @Synchronized
    fun setTempo(deck: Int, tempo: Double, keylock: Boolean) =
        nativeSetTempo(handle, deck, tempo, keylock)
    @Synchronized
    fun setCrossfader(position: Double) = nativeSetCrossfader(handle, position)
    @Synchronized
    fun renderedEnergy(): Double = nativeRenderedEnergy(handle)
    @Synchronized
    fun renderedTempo(deck: Int): Double = nativeRenderedTempo(handle, deck)
    @Synchronized
    fun renderedKeylock(deck: Int): Boolean = nativeRenderedKeylock(handle, deck)
    @Synchronized
    fun audioApi(): String = nativeAudioApi(handle)
    @Synchronized
    fun latencyMs(): Double = nativeLatencyMs(handle)
    @Synchronized
    fun callbackMicros(): Double = nativeCallbackMicros(handle)
    @Synchronized
    fun sampleRate(): Int = nativeSampleRate(handle)

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(sampleRate: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeLoadTrack(handle: Long, deck: Int, path: String): Boolean
    private external fun nativeStartAudio(handle: Long): Boolean
    private external fun nativePlay(handle: Long, deck: Int, playing: Boolean)
    private external fun nativeSetTempo(handle: Long, deck: Int, tempo: Double, keylock: Boolean)
    private external fun nativeSetCrossfader(handle: Long, position: Double)
    private external fun nativeRenderedEnergy(handle: Long): Double
    private external fun nativeRenderedTempo(handle: Long, deck: Int): Double
    private external fun nativeRenderedKeylock(handle: Long, deck: Int): Boolean
    private external fun nativeAudioApi(handle: Long): String
    private external fun nativeLatencyMs(handle: Long): Double
    private external fun nativeCallbackMicros(handle: Long): Double
    private external fun nativeSampleRate(handle: Long): Int

    companion object {
        init {
            System.loadLibrary("mixsphere_engine")
        }
    }
}
