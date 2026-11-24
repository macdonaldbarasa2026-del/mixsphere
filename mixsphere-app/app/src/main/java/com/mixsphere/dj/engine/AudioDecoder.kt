package com.mixsphere.dj.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Fully decoded, immutable PCM track used by both decks. */
class DecodedTrack(
    val sampleRate: Int,
    val frames: Int,
    val left: FloatArray,
    val right: FloatArray,
    val peaks: FloatArray,
    val bpm: Float,
    val durationMs: Long,
) {
    val isStereo: Boolean get() = true

    fun frameAt(position: Double, out: FloatArray) {
        val i = position.toInt()
        if (i < 0 || i >= frames) {
            out[0] = 0f
            out[1] = 0f
            return
        }
        val j = if (i + 1 < frames) i + 1 else i
        val f = (position - i).toFloat()
        out[0] = left[i] * (1f - f) + left[j] * f
        out[1] = right[i] * (1f - f) + right[j] * f
    }
}

/** Decodes any format Android supports into floating point PCM.
 *  This is original code; no third-party or GPL decoder is used. */
object AudioDecoder {

    private const val TIMEOUT_US = 100_000L
    private const val MAX_SECONDS = 240
    private const val TARGET_RATE = 48_000
    private const val PEAK_BUCKETS = 1200

    fun decode(context: Context, uri: Uri): DecodedTrack? = try {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        decodeExtractor(extractor)
    } catch (e: Exception) {
        null
    }

    fun decodeAsset(context: Context, path: String): DecodedTrack? = try {
        val descriptor = context.assets.openFd(path)
        val extractor = MediaExtractor()
        extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
        descriptor.close()
        decodeExtractor(extractor)
    } catch (e: Exception) {
        null
    }

    private fun decodeExtractor(extractor: MediaExtractor): DecodedTrack? {
        try {
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null

            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var outRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else TARGET_RATE
            var outChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2
            val isFloat = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT

            val chunks = ArrayList<ByteArray>()
            val info = MediaCodec.BufferInfo()
            var sawEos = false
            var decodedBytes = 0
            val byteLimit = MAX_SECONDS * TARGET_RATE * outChannels * 4

            while (!sawEos) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    codec.getInputBuffer(inIndex)?.let { buffer ->
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val changed = codec.outputFormat
                        if (changed.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            outRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (changed.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            outChannels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        continue
                    }
                    if (outIndex < 0) break
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0 && decodedBytes < byteLimit) {
                        val bytes = ByteArray(info.size.coerceAtMost(byteLimit - decodedBytes))
                        buffer.position(info.offset)
                        buffer.get(bytes)
                        chunks.add(bytes)
                        decodedBytes += bytes.size
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawEos = true
                }
            }
            codec.stop()
            codec.release()
            extractor.release()

            if (chunks.isEmpty() || outRate <= 0) return null
            return toTrack(chunks, outRate, outChannels, isFloat)
        } catch (e: Exception) {
            return null
        }
    }

    private fun toTrack(chunks: List<ByteArray>, rate: Int, channels: Int, isFloat: Boolean): DecodedTrack {
        val totalBytes = chunks.sumOf { it.size }
        val frameCount = if (isFloat) totalBytes / (channels * 4) else totalBytes / (channels * 2)
        val joined = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (chunk in chunks) joined.put(chunk)
        joined.rewind()

        val left = FloatArray(frameCount)
        val right = FloatArray(frameCount)
        if (isFloat) {
            val floats = joined.asFloatBuffer().order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frameCount) {
                left[i] = floats.get(i * channels)
                right[i] = if (channels > 1) floats.get(i * channels + 1) else left[i]
            }
        } else {
            val shorts = joined.asShortBuffer().order(ByteOrder.LITTLE_ENDIAN)
            val scale = 1f / 32768f
            for (i in 0 until frameCount) {
                left[i] = shorts.get(i * channels) * scale
                right[i] = if (channels > 1) shorts.get(i * channels + 1) * scale else left[i]
            }
        }

        val peaks = FloatArray(PEAK_BUCKETS)
        val stride = max(1, frameCount / PEAK_BUCKETS)
        for (i in 0 until PEAK_BUCKETS) {
            var peak = 0f
            val start = i * stride
            val end = minOf(start + stride, frameCount)
            for (j in start until end) {
                val value = max(abs(left[j]), abs(right[j]))
                if (value > peak) peak = value
            }
            peaks[i] = peak
        }

        val bpm = estimateBpm(left, right, rate)
        val durationMs = frameCount * 1000L / rate
        return DecodedTrack(rate, frameCount, left, right, peaks, bpm, durationMs)
    }

    /** Onset-autocorrelation BPM estimate in the 60–180 range. */
    private fun estimateBpm(left: FloatArray, right: FloatArray, rate: Int): Float {
        val hop = max(1, rate / 100)
        val count = left.size / hop
        if (count < 400) return 120f
        val onset = FloatArray(count)
        var previous = 0f
        for (i in 0 until count) {
            var sum = 0f
            val start = i * hop
            val end = minOf(start + hop, left.size)
            for (j in start until end) {
                val m = (left[j] + right[j]) * 0.5f
                sum += m * m
            }
            val energy = sqrt(sum / max(1, end - start))
            onset[i] = max(0f, energy - previous)
            previous = energy
        }
        var bestLag = 50
        var bestScore = -1f
        for (lag in 33..100) {
            var score = 0f
            var i = lag
            while (i < count) {
                score += onset[i] * onset[i - lag]
                i++
            }
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        return (6000f / bestLag).coerceIn(60f, 180f)
    }
}
