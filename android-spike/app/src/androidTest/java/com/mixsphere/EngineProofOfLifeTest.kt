/*
 * Mixsphere Phase 0 - proof of life.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Loads a real generated WAV file through the native engine, plays it,
 * changes tempo with keylock, and records native timing. The WAV is generated
 * with exact PCM samples in the test; it is not a mocked engine or fake audio.
 */
package com.mixsphere

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mixsphere.engine.MixsphereEngine
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineProofOfLifeTest {
    companion object {
        private const val TAG = "MixspherePhase0"
        private const val SAMPLE_RATE = 48000
        private const val FRAMES = SAMPLE_RATE * 2
    }

    @Test
    fun playAndChangeTempoWithKeylock() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "phase0_tone.wav")
        writeSineWav(file)

        MixsphereEngine(SAMPLE_RATE).use { engine ->
            assertTrue("track load failed", engine.loadTrack(0, file.absolutePath))

            val audioStarted = engine.startAudio()
            Log.i(TAG, "audio_started=$audioStarted api=${engine.audioApi()}")

            engine.setCrossfader(-1.0)
            engine.play(0, true)

            val energyBefore = engine.renderBlock(2048)
            Log.i(TAG, "energy_before=$energyBefore")
            assertTrue("engine produced no output", energyBefore > 1.0e-6)

            engine.setTempo(0, 1.10, true)
            val energyKeylock = engine.renderBlock(4096)
            Log.i(TAG, "energy_keylock_tempo_1.10=$energyKeylock")
            assertTrue("keylock tempo change produced no output", energyKeylock > 1.0e-6)

            engine.setTempo(0, 0.90, true)
            val energySlow = engine.renderBlock(4096)
            Log.i(TAG, "energy_keylock_tempo_0.90=$energySlow")
            assertTrue("slow keylock tempo change produced no output", energySlow > 1.0e-6)

            Log.i(TAG, "native_callback_micros=${engine.callbackMicros()}")
            Log.i(TAG, "oboe_estimated_latency_ms=${engine.latencyMs()}")
            Log.i(TAG, "native_sample_rate=${engine.sampleRate()}")
            Log.i(TAG, "PHASE0_PROOF_OF_LIFE=PASS")
        }
        file.delete()
    }

    private fun writeSineWav(file: File) {
        val dataSize = FRAMES * 2 * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1.toShort())
        buffer.putShort(2.toShort())
        buffer.putInt(SAMPLE_RATE)
        buffer.putInt(SAMPLE_RATE * 4)
        buffer.putShort(4.toShort())
        buffer.putShort(16.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        for (i in 0 until FRAMES) {
            val value = (sin(2.0 * PI * 440.0 * i / SAMPLE_RATE) * 12000.0).toInt().toShort()
            buffer.putShort(value)
            buffer.putShort(value)
        }
        RandomAccessFile(file, "rw").use { it.write(buffer.array()) }
    }
}
