package com.mixsphere.dj.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.PI
import java.util.concurrent.atomic.AtomicInteger

/** One deck. All control fields are safe to write from the UI thread;
 *  playback cursors and filters belong to the audio thread only. */
class Deck(val index: Int) {

    @Volatile var track: DecodedTrack? = null
        private set
    @Volatile var playing = false
    @Volatile var tempo = 1.0
    @Volatile var keylock = false
    @Volatile var gain = 1f
    @Volatile var busGain = 1f
    @Volatile var eqLow = 1f
    @Volatile var eqMid = 1f
    @Volatile var eqHigh = 1f
    @Volatile var filter = 0.5f
    @Volatile var fxType = 0
    @Volatile var fxX = 0.5f
    @Volatile var fxY = 0.5f
    @Volatile var cueFraction = 0f
    @Volatile var loopStart = -1f
    @Volatile var loopEnd = -1f
    val hotCues = FloatArray(8) { -1f }
    @Volatile var level = 0f
    @Volatile var positionFraction = 0f
        private set

    private val seekRequest = AtomicInteger(NO_SEEK)
    private var position = 0.0
    private var outputTime = 0.0
    private var grainSourceOrigin = 0.0
    private var lowL = 0f
    private var lowR = 0f
    private var highL = 0f
    private var highR = 0f
    private var aLow = 0.08f
    private var aHigh = 0.45f
    private val sample = FloatArray(2)
    private val delayL = FloatArray(8192)
    private val delayR = FloatArray(8192)
    private var delayIndex = 0
    private var lfoPhase = 0f
    private var filterL = 0f
    private var filterR = 0f
    private var toneL = 0f
    private var toneR = 0f
    private val fxOut = FloatArray(2)

    fun load(next: DecodedTrack?) {
        track = next
        position = 0.0
        seekRequest.set(NO_SEEK)
        outputTime = 0.0
        grainSourceOrigin = 0.0
        positionFraction = 0f
        playing = false
        cueFraction = 0f
        loopStart = -1f
        loopEnd = -1f
        for (i in hotCues.indices) hotCues[i] = -1f
        lowL = 0f
        lowR = 0f
        highL = 0f
        highR = 0f
        filterL = 0f
        filterR = 0f
        toneL = 0f
        toneR = 0f
        delayL.fill(0f)
        delayR.fill(0f)
        delayIndex = 0
        lfoPhase = 0f
        level = 0f
        if (next != null) {
            val rate = next.sampleRate.toDouble()
            aLow = (1.0 - exp(-2.0 * PI * 250.0 / rate)).toFloat()
            aHigh = (1.0 - exp(-2.0 * PI * 4000.0 / rate)).toFloat()
        }
    }

    fun seek(fraction: Float) {
        val current = track ?: return
        val f = fraction.coerceIn(0f, 1f)
        seekRequest.set(java.lang.Float.floatToRawIntBits(f))
        positionFraction = f
    }

    fun scratch(deltaRadians: Float) {
        val current = track ?: return
        if (current.frames <= 0) return
        val framesPerRotation = current.sampleRate * (60.0 / 33.333333)
        val deltaFrames = deltaRadians.toDouble() / (2.0 * PI) * framesPerRotation
        val next = (positionFraction.toDouble() * current.frames + deltaFrames)
            .coerceIn(0.0, (current.frames - 1).coerceAtLeast(0).toDouble())
        seek((next / current.frames).toFloat())
    }

    fun setLoop(start: Float, end: Float) {
        loopStart = start
        loopEnd = max(start + 0.005f, end)
    }

    fun clearLoop() {
        loopStart = -1f
        loopEnd = -1f
    }

    fun cue() {
        seek(cueFraction)
    }

    fun hotCue(index: Int) {
        if (index !in hotCues.indices) return
        val stored = hotCues[index]
        if (stored < 0f) hotCues[index] = positionFraction else seek(stored)
    }

    fun setCueHere() {
        cueFraction = positionFraction
    }

    fun setFx(type: Int, x: Float, y: Float) {
        fxType = type
        fxX = x.coerceIn(0f, 1f)
        fxY = y.coerceIn(0f, 1f)
    }

    private fun processFx(l: Float, r: Float) {
        when (fxType) {
            1 -> {
                val cutoff = (abs(fxX - 0.5f) * 2f).coerceIn(0.02f, 1f)
                val coefficient = cutoff * cutoff * 0.5f + 0.01f
                filterL += coefficient * (l - filterL)
                filterR += coefficient * (r - filterR)
                if (fxX < 0.5f) {
                    fxOut[0] = filterL
                    fxOut[1] = filterR
                } else {
                    fxOut[0] = l - filterL
                    fxOut[1] = r - filterR
                }
            }
            2 -> {
                val wet = fxX
                val feedback = (fxY * 0.72f).coerceIn(0f, 0.75f)
                val readIndex = (delayIndex - 1152 + delayL.size) % delayL.size
                val delayedL = delayL[readIndex]
                val delayedR = delayR[readIndex]
                delayL[delayIndex] = l + delayedL * feedback
                delayR[delayIndex] = r + delayedR * feedback
                delayIndex = (delayIndex + 1) % delayL.size
                fxOut[0] = l * (1f - wet * 0.35f) + delayedL * wet
                fxOut[1] = r * (1f - wet * 0.35f) + delayedR * wet
            }
            3 -> {
                lfoPhase += 0.0006f * (0.4f + fxY)
                if (lfoPhase > 1f) lfoPhase -= 1f
                val depth = 12f + fxX * 72f
                val delayFrames = (depth * (0.5f + 0.5f * sin(2.0 * PI * lfoPhase)))
                    .toInt().coerceAtLeast(1)
                val readIndex = (delayIndex - delayFrames + delayL.size) % delayL.size
                delayL[delayIndex] = l
                delayR[delayIndex] = r
                delayIndex = (delayIndex + 1) % delayL.size
                fxOut[0] = (l + delayL[readIndex]) * 0.5f
                fxOut[1] = (r + delayR[readIndex]) * 0.5f
            }
            else -> {
                fxOut[0] = l
                fxOut[1] = r
            }
        }
    }

    fun read(out: FloatArray, frames: Int, outputRate: Int) {
        val current = track
        if (current == null || !playing || gain <= 0f || busGain <= 0f) {
            level *= 0.9f
            return
        }
        if (current.frames <= 0) return
        consumeSeek(current)

        val baseRate = current.sampleRate.toDouble() / outputRate.toDouble()
        var energy = 0f
        for (i in 0 until frames) {
            if (loopStart >= 0f && positionFraction >= loopEnd) {
                seekFromAudio(loopStart, current)
            }
            if (position >= current.frames) {
                playing = false
                position = current.frames.toDouble()
                positionFraction = 1f
                break
            }

            val l: Float
            val r: Float
            if (keylock) {
                readGrain(current, baseRate, sample)
                l = sample[0]
                r = sample[1]
                position += baseRate * tempo
            } else {
                current.frameAt(position, sample)
                l = sample[0]
                r = sample[1]
                position += baseRate * tempo
            }
            positionFraction = (position / current.frames).toFloat().coerceIn(0f, 1f)
            outputTime += 1.0

            lowL += aLow * (l - lowL)
            lowR += aLow * (r - lowR)
            highL += aHigh * (l - highL)
            highR += aHigh * (r - highR)
            val trebleL = l - highL
            val trebleR = r - highR
            val midL = l - lowL - trebleL
            val midR = r - lowR - trebleR
            var eqL = lowL * eqLow + midL * eqMid + trebleL * eqHigh
            var eqR = lowR * eqLow + midR * eqMid + trebleR * eqHigh
            if (filter != 0.5f) {
                val lowpass = filter < 0.5f
                val strength = if (lowpass) filter * 2f else (1f - filter) * 2f
                val coefficient = strength * strength * 0.35f + 0.01f
                toneL += coefficient * (eqL - toneL)
                toneR += coefficient * (eqR - toneR)
                if (lowpass) {
                    eqL = toneL
                    eqR = toneR
                } else {
                    eqL -= toneL
                    eqR -= toneR
                }
            }
            processFx(eqL, eqR)
            val volume = gain * busGain
            val mixedL = fxOut[0] * volume
            val mixedR = fxOut[1] * volume
            out[2 * i] += mixedL
            out[2 * i + 1] += mixedR
            energy += mixedL * mixedL + mixedR * mixedR
        }
        level = min(1f, max(0f, abs(energy) * 0.00002f / frames))
    }

    private fun seekFromAudio(fraction: Float, current: DecodedTrack) {
        position = fraction.toDouble() * current.frames
        grainSourceOrigin = position
        outputTime = 0.0
        positionFraction = fraction
    }

    private fun consumeSeek(current: DecodedTrack) {
        val request = seekRequest.getAndSet(NO_SEEK)
        if (request == NO_SEEK) return
        val fraction = java.lang.Float.intBitsToFloat(request)
        position = fraction.toDouble() * current.frames
        grainSourceOrigin = position
        outputTime = 0.0
        positionFraction = fraction
    }

    /** Preallocated granular overlap-add: tempo changes without pitch shift. */
    private fun readGrain(current: DecodedTrack, baseRate: Double, out: FloatArray) {
        val grainSource = 2048.0
        val grainOut = grainSource / tempo.coerceIn(0.05, 4.0)
        val hopOut = grainOut * 0.5
        val hopSource = grainSource * 0.5
        val t = outputTime
        val first = max(0L, floor(t / hopOut).toLong() - 1L)
        var l = 0f
        var r = 0f
        var windowSum = 0f
        for (g in first..first + 3) {
            val launch = g * hopOut
            val local = (t - launch) / grainOut
            if (local < 0.0 || local > 1.0) continue
            val window = (0.5 - 0.5 * cos(2.0 * PI * local)).toFloat()
            val sourcePosition = grainSourceOrigin + g * hopSource + (t - launch) * baseRate
            if (sourcePosition < 0.0 || sourcePosition >= current.frames) continue
            current.frameAt(sourcePosition, sample)
            l += sample[0] * window
            r += sample[1] * window
            windowSum += window
        }
        if (windowSum > 0f) {
            out[0] = l / windowSum
            out[1] = r / windowSum
        } else {
            out[0] = 0f
            out[1] = 0f
        }
    }

    companion object {
        const val NO_SEEK = -1
    }
}
