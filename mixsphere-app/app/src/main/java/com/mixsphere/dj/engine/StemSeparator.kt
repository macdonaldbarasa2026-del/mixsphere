package com.mixsphere.dj.engine

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Offline DSP stem render. The vocal layer is a center-weighted focus, not model-grade isolation. */
class StemResult(val directory: File, val files: Map<String, File>)

object StemSeparator {

    private const val FRAME = 2048
    private const val HOP = 512
    private const val BINS = FRAME / 2 + 1
    private const val TIME_RADIUS = 6
    private const val FREQ_RADIUS = 4
    private const val CHUNK_SECONDS = 4
    fun separate(context: Context, uri: Uri, onProgress: (Int) -> Unit): StemResult? {
        val track = AudioDecoder.decode(context, uri) ?: return null
        return separate(context, track, uri.toString(), onProgress)
    }

    fun separate(
        context: Context,
        track: DecodedTrack,
        cacheKey: String,
        onProgress: (Int) -> Unit,
        shouldCancel: () -> Boolean = { false },
    ): StemResult? {
        if (track.frames < FRAME * 2 || track.sampleRate <= 0) return null

        val outputNames = listOf("vocals", "beat", "harmony", "no_vocals", "no_beat", "no_harmony")
        val directory = File(
            context.cacheDir,
            "mixsphere-stems/${Integer.toHexString(cacheKey.hashCode())}",
        )
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create stem cache directory")
        }

        val writers = LinkedHashMap<String, WavWriter>()
        try {
            for (name in outputNames) {
                writers[name] = WavWriter(File(directory, "$name.wav"), track.sampleRate)
            }

            val chunkSize = track.sampleRate * CHUNK_SECONDS
            var start = 0
            while (start < track.frames) {
                ensureActive(shouldCancel)
                val end = minOf(track.frames, start + chunkSize)
                val chunk = separateChunk(track, start, end, shouldCancel)
                writers.getValue("vocals").append(chunk.left[0], chunk.right[0])
                writers.getValue("beat").append(chunk.left[1], chunk.right[1])
                writers.getValue("harmony").append(chunk.left[2], chunk.right[2])
                writers.getValue("no_vocals").appendResidual(track, start, chunk.left[0], chunk.right[0])
                writers.getValue("no_beat").appendResidual(track, start, chunk.left[1], chunk.right[1])
                writers.getValue("no_harmony").appendResidual(track, start, chunk.left[2], chunk.right[2])
                start = end
                onProgress((start.toLong() * 88 / track.frames).toInt().coerceIn(0, 88))
            }

            writers.values.forEach { it.finish() }
            onProgress(100)
            return StemResult(directory, outputNames.associateWith { File(directory, "$it.wav") })
        } finally {
            writers.values.forEach { it.close() }
        }
    }

    private data class ChunkStems(val left: Array<FloatArray>, val right: Array<FloatArray>)

    private fun separateChunk(
        track: DecodedTrack,
        coreStart: Int,
        coreEnd: Int,
        shouldCancel: () -> Boolean,
    ): ChunkStems {
        val contextSamples = HOP * (TIME_RADIUS + 2) + FRAME
        val segmentStart = maxOf(0, coreStart - contextSamples)
        val segmentEnd = minOf(track.frames, coreEnd + contextSamples)
        val segmentFrames = segmentEnd - segmentStart
        val padding = FRAME / 2
        val paddedLength = segmentFrames + FRAME
        val frameCount = (segmentFrames + HOP - 1) / HOP + 1
        val spectrumSize = frameCount * BINS
        val window = FloatArray(FRAME) {
            (0.5 - 0.5 * cos(2.0 * PI * it / (FRAME - 1))).toFloat()
        }
        val leftReal = FloatArray(spectrumSize)
        val leftImag = FloatArray(spectrumSize)
        val rightReal = FloatArray(spectrumSize)
        val rightImag = FloatArray(spectrumSize)
        val fftReal = FloatArray(FRAME)
        val fftImag = FloatArray(FRAME)

        for (frame in 0 until frameCount) {
            if (frame % 16 == 0) ensureActive(shouldCancel)
            val offset = frame * HOP
            for (i in 0 until FRAME) {
                val trackIndex = segmentStart + offset + i - padding
                fftReal[i] = if (trackIndex in segmentStart until segmentEnd) {
                    track.left[trackIndex] * window[i]
                } else {
                    0f
                }
                fftImag[i] = 0f
            }
            fft(fftReal, fftImag, inverse = false)
            val spectrumOffset = frame * BINS
            System.arraycopy(fftReal, 0, leftReal, spectrumOffset, BINS)
            System.arraycopy(fftImag, 0, leftImag, spectrumOffset, BINS)

            for (i in 0 until FRAME) {
                val trackIndex = segmentStart + offset + i - padding
                fftReal[i] = if (trackIndex in segmentStart until segmentEnd) {
                    track.right[trackIndex] * window[i]
                } else {
                    0f
                }
                fftImag[i] = 0f
            }
            fft(fftReal, fftImag, inverse = false)
            System.arraycopy(fftReal, 0, rightReal, spectrumOffset, BINS)
            System.arraycopy(fftImag, 0, rightImag, spectrumOffset, BINS)
        }

        val averageMagnitude = FloatArray(spectrumSize)
        val centerMagnitude = FloatArray(spectrumSize)
        val sideMagnitude = FloatArray(spectrumSize)
        for (i in 0 until spectrumSize) {
            val lr = leftReal[i]
            val li = leftImag[i]
            val rr = rightReal[i]
            val ri = rightImag[i]
            averageMagnitude[i] = (magnitude(lr, li) + magnitude(rr, ri)) * 0.5f
            centerMagnitude[i] = magnitude((lr + rr) * 0.5f, (li + ri) * 0.5f)
            sideMagnitude[i] = magnitude((lr - rr) * 0.5f, (li - ri) * 0.5f)
        }

        val masks = Array(3) { FloatArray(spectrumSize) }
        val timeValues = FloatArray(TIME_RADIUS * 2 + 1)
        val frequencyValues = FloatArray(FREQ_RADIUS * 2 + 1)
        for (frame in 0 until frameCount) {
            if (frame % 16 == 0) ensureActive(shouldCancel)
            val offset = frame * BINS
            for (bin in 0 until BINS) {
                val index = offset + bin
                val harmonic = medianTime(averageMagnitude, frame, bin, frameCount, timeValues)
                val percussive = medianFrequency(averageMagnitude, frame, bin, frequencyValues)
                val h2 = harmonic * harmonic
                val p2 = percussive * percussive
                val beatMask = (p2 / (p2 + h2 + 1e-8f)).coerceIn(0f, 1f)
                val tonalMask = 1f - beatMask

                val frequency = bin.toFloat() * track.sampleRate / FRAME
                val vocalBand = smoothStep(90f, 180f, frequency) *
                    (1f - smoothStep(5_000f, 8_500f, frequency))
                val center = centerMagnitude[index] /
                    (centerMagnitude[index] + sideMagnitude[index] + 1e-8f)
                val vocalMask = tonalMask * vocalBand * (0.2f + 0.8f * center) * 0.72f

                masks[0][index] = vocalMask
                masks[1][index] = beatMask
                masks[2][index] = tonalMask - vocalMask
            }
        }

        val paddedWeights = FloatArray(paddedLength)
        val outputLeft = Array(3) { FloatArray(paddedLength) }
        val outputRight = Array(3) { FloatArray(paddedLength) }
        val workReal = FloatArray(FRAME)
        val workImag = FloatArray(FRAME)
        for (frame in 0 until frameCount) {
            if (frame % 8 == 0) ensureActive(shouldCancel)
            val spectrumOffset = frame * BINS
            val sampleOffset = frame * HOP
            for (i in 0 until FRAME) {
                val outputIndex = sampleOffset + i
                if (outputIndex < paddedLength) {
                    val w = window[i]
                    paddedWeights[outputIndex] += w * w
                }
            }

            for (stem in 0 until 3) {
                applyMaskAndSynthesize(
                    leftReal, leftImag, masks[stem], spectrumOffset, sampleOffset,
                    window, paddedLength, workReal, workImag, outputLeft[stem],
                )
                applyMaskAndSynthesize(
                    rightReal, rightImag, masks[stem], spectrumOffset, sampleOffset,
                    window, paddedLength, workReal, workImag, outputRight[stem],
                )
            }
        }

        val coreOffset = padding + coreStart - segmentStart
        val coreFrames = coreEnd - coreStart
        val resultLeft = Array(3) { FloatArray(coreFrames) }
        val resultRight = Array(3) { FloatArray(coreFrames) }
        for (i in 0 until coreFrames) {
            val index = coreOffset + i
            val weight = paddedWeights[index].coerceAtLeast(1e-8f)
            for (stem in 0 until 3) {
                resultLeft[stem][i] = outputLeft[stem][index] / weight
                resultRight[stem][i] = outputRight[stem][index] / weight
            }
        }
        return ChunkStems(resultLeft, resultRight)
    }

    private fun applyMaskAndSynthesize(
        sourceReal: FloatArray,
        sourceImag: FloatArray,
        mask: FloatArray,
        spectrumOffset: Int,
        sampleOffset: Int,
        window: FloatArray,
        outputSize: Int,
        workReal: FloatArray,
        workImag: FloatArray,
        output: FloatArray,
    ) {
        for (bin in 0 until BINS) {
            val scale = mask[spectrumOffset + bin]
            workReal[bin] = sourceReal[spectrumOffset + bin] * scale
            workImag[bin] = if (bin == 0 || bin == FRAME / 2) {
                0f
            } else {
                sourceImag[spectrumOffset + bin] * scale
            }
            if (bin in 1 until FRAME / 2) {
                workReal[FRAME - bin] = workReal[bin]
                workImag[FRAME - bin] = -workImag[bin]
            }
        }
        fft(workReal, workImag, inverse = true)
        for (i in 0 until FRAME) {
            val outputIndex = sampleOffset + i
            if (outputIndex < outputSize) {
                output[outputIndex] += workReal[i] * window[i]
            }
        }
    }

    private fun medianTime(
        magnitudes: FloatArray,
        frame: Int,
        bin: Int,
        frames: Int,
        scratch: FloatArray,
    ): Float {
        var count = 0
        for (delta in -TIME_RADIUS..TIME_RADIUS) {
            val neighbor = frame + delta
            if (neighbor in 0 until frames) scratch[count++] = magnitudes[neighbor * BINS + bin]
        }
        return median(scratch, count)
    }

    private fun medianFrequency(
        magnitudes: FloatArray,
        frame: Int,
        bin: Int,
        scratch: FloatArray,
    ): Float {
        var count = 0
        val low = maxOf(0, bin - FREQ_RADIUS)
        val high = minOf(BINS - 1, bin + FREQ_RADIUS)
        val offset = frame * BINS
        for (neighbor in low..high) scratch[count++] = magnitudes[offset + neighbor]
        return median(scratch, count)
    }

    private fun median(values: FloatArray, count: Int): Float {
        for (i in 1 until count) {
            val value = values[i]
            var j = i - 1
            while (j >= 0 && values[j] > value) {
                values[j + 1] = values[j]
                j--
            }
            values[j + 1] = value
        }
        return values[count / 2]
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun magnitude(real: Float, imaginary: Float): Float =
        sqrt(real * real + imaginary * imaginary)

    private fun ensureActive(shouldCancel: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || shouldCancel()) {
            throw InterruptedException("Stem separation cancelled")
        }
    }

    private fun fft(real: FloatArray, imaginary: FloatArray, inverse: Boolean) {
        val size = real.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val realValue = real[i]
                real[i] = real[j]
                real[j] = realValue
                val imaginaryValue = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = imaginaryValue
            }
        }

        var length = 2
        while (length <= size) {
            val angle = (if (inverse) 2.0 else -2.0) * PI / length
            val stepReal = cos(angle).toFloat()
            val stepImaginary = sin(angle).toFloat()
            val half = length / 2
            var start = 0
            while (start < size) {
                var twiddleReal = 1f
                var twiddleImaginary = 0f
                for (offset in 0 until half) {
                    val even = start + offset
                    val odd = even + half
                    val oddReal = real[odd] * twiddleReal - imaginary[odd] * twiddleImaginary
                    val oddImaginary = real[odd] * twiddleImaginary + imaginary[odd] * twiddleReal
                    val evenReal = real[even]
                    val evenImaginary = imaginary[even]
                    real[even] = evenReal + oddReal
                    imaginary[even] = evenImaginary + oddImaginary
                    real[odd] = evenReal - oddReal
                    imaginary[odd] = evenImaginary - oddImaginary
                    val nextTwiddleReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary
                    twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal
                    twiddleReal = nextTwiddleReal
                }
                start += length
            }
            length = length shl 1
        }
        if (inverse) {
            for (i in 0 until size) {
                real[i] /= size
                imaginary[i] /= size
            }
        }
    }

    private class WavWriter(file: File, sampleRate: Int) {
        private val output = RandomAccessFile(file, "rw")
        private val rate = sampleRate
        private var frames = 0L
        private var finished = false

        init {
            output.setLength(0)
            output.write(ByteArray(44))
        }

        fun append(left: FloatArray, right: FloatArray) {
            val bytes = ByteArray(left.size * 4)
            for (i in left.indices) {
                putSample(bytes, i * 4, left[i])
                putSample(bytes, i * 4 + 2, right[i])
            }
            output.write(bytes)
            frames += left.size
        }

        fun appendResidual(
            track: DecodedTrack,
            start: Int,
            stemLeft: FloatArray,
            stemRight: FloatArray,
        ) {
            val bytes = ByteArray(stemLeft.size * 4)
            for (i in stemLeft.indices) {
                putSample(bytes, i * 4, track.left[start + i] - stemLeft[i])
                putSample(bytes, i * 4 + 2, track.right[start + i] - stemRight[i])
            }
            output.write(bytes)
            frames += stemLeft.size
        }

        fun finish() {
            val dataBytes = frames * 4L
            if (dataBytes > Int.MAX_VALUE - 36L) throw IOException("Stem WAV exceeds RIFF size limit")
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt((36L + dataBytes).toInt())
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1.toShort())
            header.putShort(2.toShort())
            header.putInt(rate)
            header.putInt(rate * 4)
            header.putShort(4.toShort())
            header.putShort(16.toShort())
            header.put("data".toByteArray())
            header.putInt(dataBytes.toInt())
            output.seek(0)
            output.write(header.array())
            finished = true
        }

        fun close() {
            if (!finished) output.setLength(0)
            output.close()
        }

        private fun putSample(bytes: ByteArray, offset: Int, value: Float) {
            val sample = (value.coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767)
            bytes[offset] = sample.toByte()
            bytes[offset + 1] = (sample shr 8).toByte()
        }
    }
}
