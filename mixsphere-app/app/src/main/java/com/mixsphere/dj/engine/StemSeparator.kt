package com.mixsphere.dj.engine

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Result of an offline stem separation. */
class StemResult(val directory: File, val files: Map<String, File>)

/** Original STFT / harmonic-percussive-vocal separator.
 *  No external DSP library is used. Separation runs off the UI thread. */
object StemSeparator {

    private const val FRAME = 2048
    private const val HOP = 1024
    private const val BINS = FRAME / 2 + 1
    private const val MAX_SECONDS = 90
    private const val TIME_RADIUS = 6
    private const val FREQ_RADIUS = 5

    fun separate(context: Context, uri: Uri, onProgress: (Int) -> Unit): StemResult? {
        val decoded = AudioDecoder.decode(context, uri) ?: return null
        val limit = minOf(decoded.frames, MAX_SECONDS * decoded.sampleRate)
        if (limit < FRAME * 4) return null
        val mono = FloatArray(limit)
        for (i in 0 until limit) mono[i] = (decoded.left[i] + decoded.right[i]) * 0.5f

        val frameCount = (limit - FRAME) / HOP + 1
        val real = FloatArray(frameCount * BINS)
        val imag = FloatArray(frameCount * BINS)
        val window = FloatArray(FRAME) { (0.5 - 0.5 * cos(2.0 * PI * it / FRAME)).toFloat() }
        val fftRe = FloatArray(FRAME)
        val fftIm = FloatArray(FRAME)

        for (frame in 0 until frameCount) {
            val offset = frame * HOP
            for (i in 0 until FRAME) {
                fftRe[i] = mono[offset + i] * window[i]
                fftIm[i] = 0f
            }
            fft(fftRe, fftIm, false)
            System.arraycopy(fftRe, 0, real, frame * BINS, BINS)
            System.arraycopy(fftIm, 0, imag, frame * BINS, BINS)
            if (frame % 24 == 0) onProgress((frame * 40 / frameCount).coerceIn(0, 40))
        }

        val vocal = synthesize(real, imag, frameCount, decoded.sampleRate, window, Kind.VOCAL, onProgress)
        val beat = synthesize(real, imag, frameCount, decoded.sampleRate, window, Kind.BEAT, onProgress)
        val harmony = synthesize(real, imag, frameCount, decoded.sampleRate, window, Kind.HARMONY, onProgress)
        val length = minOf(limit, (frameCount - 1) * HOP + FRAME)
        val noVocal = subtract(mono, vocal, length)
        val noBeat = subtract(mono, beat, length)
        val noHarmony = subtract(mono, harmony, length)

        val dir = File(context.filesDir, "stems")
        if (!dir.exists()) dir.mkdirs()
        val files = linkedMapOf<String, File>()
        val entries = listOf(
            "vocals" to vocal, "beat" to beat, "harmony" to harmony,
            "no_vocals" to noVocal, "no_beat" to noBeat, "no_harmony" to noHarmony,
        )
        entries.forEachIndexed { index, (name, pcm) ->
            val file = File(dir, "$name.wav")
            writeWav(file, pcm, length, decoded.sampleRate)
            files[name] = file
            onProgress(88 + (index + 1) * 2)
        }
        onProgress(100)
        return StemResult(dir, files)
    }

    private enum class Kind { VOCAL, BEAT, HARMONY }

    private fun magnitude(real: FloatArray, imag: FloatArray, index: Int): Float =
        sqrt(real[index] * real[index] + imag[index] * imag[index])

    private fun timeAverage(real: FloatArray, imag: FloatArray, frame: Int, bin: Int, frames: Int): Float {
        var sum = 0f
        var count = 0
        for (f in maxOf(0, frame - TIME_RADIUS)..minOf(frames - 1, frame + TIME_RADIUS)) {
            sum += magnitude(real, imag, f * BINS + bin)
            count++
        }
        return sum / count.coerceAtLeast(1)
    }

    private fun freqAverage(real: FloatArray, imag: FloatArray, frame: Int, bin: Int): Float {
        var sum = 0f
        var count = 0
        val base = frame * BINS
        for (b in maxOf(0, bin - FREQ_RADIUS)..minOf(BINS - 1, bin + FREQ_RADIUS)) {
            sum += magnitude(real, imag, base + b)
            count++
        }
        return sum / count.coerceAtLeast(1)
    }

    private fun synthesize(
        real: FloatArray,
        imag: FloatArray,
        frameCount: Int,
        sampleRate: Int,
        window: FloatArray,
        kind: Kind,
        onProgress: (Int) -> Unit,
    ): FloatArray {
        val out = FloatArray((frameCount - 1) * HOP + FRAME)
        val re = FloatArray(FRAME)
        val im = FloatArray(FRAME)
        val progressBase = when (kind) {
            Kind.VOCAL -> 40
            Kind.BEAT -> 56
            Kind.HARMONY -> 72
        }
        for (frame in 0 until frameCount) {
            val base = frame * BINS
            for (bin in 0 until BINS) {
                val index = base + bin
                val mag = magnitude(real, imag, index)
                if (mag < 1e-7f) {
                    re[bin] = 0f
                    im[bin] = 0f
                    if (bin in 1 until FRAME / 2) {
                        re[FRAME - bin] = 0f
                        im[FRAME - bin] = 0f
                    }
                    continue
                }
                val harmonic = timeAverage(real, imag, frame, bin, frameCount)
                val percussive = freqAverage(real, imag, frame, bin)
                val denominator = harmonic + percussive + 1e-6f
                var target = when (kind) {
                    Kind.BEAT -> mag * (percussive / denominator)
                    Kind.HARMONY -> mag * (harmonic / denominator)
                    Kind.VOCAL -> 0f
                }
                if (kind == Kind.VOCAL) {
                    val hz = bin.toFloat() * sampleRate / FRAME
                    val mid = if (hz >= 180f && hz <= 4000f) 1f else 0.12f
                    val previous = if (frame > 0) magnitude(real, imag, index - BINS) else 0f
                    val flux = ((mag - previous) / (mag + previous + 1e-6f)).coerceIn(0f, 1f)
                    val vocalShare = mid * (0.35f + 0.65f * flux) * 0.7f
                    target = mag * vocalShare
                }
                val scale = target / mag
                re[bin] = real[index] * scale
                im[bin] = imag[index] * scale
                if (bin in 1 until FRAME / 2) {
                    re[FRAME - bin] = real[index] * scale
                    im[FRAME - bin] = -imag[index] * scale
                }
            }
            fft(re, im, true)
            val offset = frame * HOP
            for (i in 0 until FRAME) {
                if (offset + i < out.size) out[offset + i] += re[i] * window[i] * 2f
            }
            if (frame % 24 == 0) {
                onProgress(progressBase + (frame * 16 / frameCount).coerceIn(0, 16))
            }
        }
        return out
    }

    private fun subtract(original: FloatArray, stem: FloatArray, length: Int): FloatArray =
        FloatArray(length) { i ->
            (original[i] - (stem.getOrNull(i) ?: 0f)).coerceIn(-1f, 1f)
        }

    private fun writeWav(file: File, mono: FloatArray, length: Int, sampleRate: Int) {
        val dataSize = length * 4
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1.toShort())
        buffer.putShort(2.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 4)
        buffer.putShort(4.toShort())
        buffer.putShort(16.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        for (i in 0 until length) {
            val value = (mono[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            buffer.putShort(value)
            buffer.putShort(value)
        }
        FileOutputStream(file).use { it.write(buffer.array()) }
    }

    private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }
        var len = 2
        while (len <= n) {
            val angle = (if (inverse) 2.0 else -2.0) * PI / len
            val stepRe = cos(angle).toFloat()
            val stepIm = sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var wr = 1f
                var wi = 0f
                val half = len shr 1
                for (k in 0 until half) {
                    val a = start + k
                    val b = a + half
                    val vr = re[b] * wr - im[b] * wi
                    val vi = re[b] * wi + im[b] * wr
                    val ur = re[a]
                    val ui = im[a]
                    re[a] = ur + vr
                    im[a] = ui + vi
                    re[b] = ur - vr
                    im[b] = ui - vi
                    val next = wr * stepRe - wi * stepIm
                    wi = wr * stepIm + wi * stepRe
                    wr = next
                }
                start += len
            }
            len = len shl 1
        }
        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }
}
