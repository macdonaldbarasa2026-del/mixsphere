package com.mixsphere.dj.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/** Two-deck mixer that streams original DSP output to Android's low-latency output. */
class MixerEngine(private val outputRate: Int = 48_000) : AutoCloseable {

    val decks = listOf(Deck(0), Deck(1))

    @Volatile var crossfader = 0f
        private set
    @Volatile var masterLevel = 0f
        private set
    @Volatile var isRunning = false
        private set

    private var track: AudioTrack? = null
    private var mixerThread: Thread? = null
    private val framesPerBuffer = 1920
    private val mixBuffer = FloatArray(framesPerBuffer * 2)

    fun start() {
        if (isRunning) return
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(outputRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(framesPerBuffer * 2 * Float.SIZE_BYTES * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack.play()
        track = audioTrack
        isRunning = true
        mixerThread = Thread({ mixLoop() }, "mixsphere-audio").also { it.start() }
    }

    fun stop() {
        isRunning = false
        mixerThread?.join(2000)
        mixerThread = null
        track?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        track = null
    }

    fun setCrossfader(value: Float) {
        crossfader = value.coerceIn(-1f, 1f)
    }

    fun load(deckIndex: Int, decoded: DecodedTrack) {
        decks[deckIndex].load(decoded)
    }

    fun sync(target: Int, source: Int) {
        val from = decks[source].track?.bpm ?: 120f
        val to = decks[target].track?.bpm ?: 120f
        if (to > 0f) decks[target].tempo = (from / to).toDouble().coerceIn(0.5, 1.5)
    }

    private fun mixLoop() {
        val audioTrack = track ?: return
        while (isRunning) {
            java.util.Arrays.fill(mixBuffer, 0f)
            val t = (crossfader + 1f) * 0.5f
            decks[0].busGain = cos(t * Math.PI.toFloat() * 0.5f)
            decks[1].busGain = sin(t * Math.PI.toFloat() * 0.5f)
            decks[0].read(mixBuffer, framesPerBuffer, outputRate)
            decks[1].read(mixBuffer, framesPerBuffer, outputRate)

            var peak = 0f
            for (i in mixBuffer.indices) {
                val v = tanh(mixBuffer[i] * 1.1f) * 0.92f
                mixBuffer[i] = v
                val a = kotlin.math.abs(v)
                if (a > peak) peak = a
            }
            masterLevel = peak
            try {
                audioTrack.write(mixBuffer, 0, mixBuffer.size, AudioTrack.WRITE_BLOCKING)
            } catch (e: Exception) {
                isRunning = false
            }
        }
    }

    override fun close() = stop()
}
