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
    fun loadTrack(deck: Int, path: String): Boolean = nativeLoadTrack(activeHandle(), deck, path)
    @Synchronized
    fun startAudio(): Boolean = nativeStartAudio(activeHandle())
    @Synchronized
    fun play(deck: Int, playing: Boolean) = nativePlay(activeHandle(), deck, playing)
    @Synchronized
    fun setTempo(deck: Int, tempo: Double, keylock: Boolean) =
        nativeSetTempo(activeHandle(), deck, TempoControl.normalize(tempo), keylock)
    @Synchronized
    fun setCrossfader(position: Double) = nativeSetCrossfader(activeHandle(), position)
    @Synchronized
    fun seek(deck: Int, fraction: Double) {
        require(fraction.isFinite()) { "seek fraction must be finite" }
        nativeSeek(activeHandle(), deck, fraction.coerceIn(0.0, 1.0))
    }
    @Synchronized
    fun setGain(deck: Int, gain: Double) {
        require(gain.isFinite()) { "gain must be finite" }
        nativeSetGain(activeHandle(), deck, gain.coerceIn(0.0, 1.0))
    }
    @Synchronized
    fun renderedEnergy(): Double = nativeRenderedEnergy(activeHandle())
    @Synchronized
    fun renderedTempo(deck: Int): Double = nativeRenderedTempo(activeHandle(), deck)
    @Synchronized
    fun renderedKeylock(deck: Int): Boolean = nativeRenderedKeylock(activeHandle(), deck)
    @Synchronized
    fun audioApi(): String = nativeAudioApi(activeHandle())
    @Synchronized
    fun latencyMs(): Double = nativeLatencyMs(activeHandle())
    @Synchronized
    fun callbackMicros(): Double = nativeCallbackMicros(activeHandle())
    @Synchronized
    fun sampleRate(): Int = nativeSampleRate(activeHandle())

    private fun activeHandle(): Long = checkNotClosed(handle)

    private fun checkNotClosed(value: Long): Long {
        check(value != 0L) { "engine is closed" }
        return value
    }

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
    private external fun nativeSeek(handle: Long, deck: Int, fraction: Double)
    private external fun nativeSetGain(handle: Long, deck: Int, gain: Double)
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
