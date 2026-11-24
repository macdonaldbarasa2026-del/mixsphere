package com.mixsphere.dj.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
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
    private const val TAG = "MixsphereDecode"

    private fun maxPcmBytes(rate: Int, channels: Int, isFloat: Boolean): Long =
        MAX_SECONDS.toLong() * rate * channels * (if (isFloat) 4L else 2L)

    fun decode(context: Context, uri: Uri): DecodedTrack? {
        var temporary: File? = null
        return try {
            val mime = context.contentResolver.getType(uri) ?: "audio/*"
            val extension = when {
                mime.contains("wav") -> ".wav"
                mime.contains("ogg") -> ".ogg"
                mime.contains("flac") -> ".flac"
                mime.contains("mpeg") || mime.contains("mp3") -> ".mp3"
                mime.contains("mp4") || mime.contains("m4a") -> ".m4a"
                else -> ".bin"
            }
            temporary = File(
                context.cacheDir,
                "decode-${System.currentTimeMillis()}-${abs(uri.hashCode())}$extension",
            )
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Cannot open $uri")
            input.use { stream -> FileOutputStream(temporary).use { stream.copyTo(it) } }
            val decoded = decodeFile(temporary)
            if (decoded == null) Log.e(TAG, "No decoder could handle $mime ($uri)")
            decoded
        } catch (e: Exception) {
            Log.e(TAG, "Decode failed: ${e.message}", e)
            null
        } finally {
            temporary?.delete()
        }
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

    fun decodeFile(file: File): DecodedTrack? = try {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
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
            var outRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else TARGET_RATE
            var outChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2
            val isFloat = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                return readRaw(extractor, format, outRate, outChannels, isFloat)
            }
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val chunks = ArrayList<ByteArray>()
            val info = MediaCodec.BufferInfo()
            var sawEos = false
            var decodedBytes = 0L
            var truncated = false

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
                    if (buffer != null && info.size > 0) {
                        val remaining = (maxPcmBytes(outRate, outChannels, isFloat) - decodedBytes)
                            .coerceAtLeast(0L)
                        val copySize = minOf(info.size.toLong(), remaining).toInt()
                        if (copySize < info.size) truncated = true
                        if (copySize > 0) {
                            val bytes = ByteArray(copySize)
                            buffer.position(info.offset)
                            buffer.get(bytes)
                            chunks.add(bytes)
                            decodedBytes += bytes.size
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawEos = true
                }
            }
            codec.stop()
            codec.release()
            extractor.release()

            if (truncated) {
                Log.e(TAG, "Track exceeds the $MAX_SECONDS-second decode limit")
                return null
            }
            if (chunks.isEmpty() || outRate <= 0) return null
            return toTrack(chunks, outRate, outChannels, isFloat)
        } catch (e: Exception) {
            return null
        }
    }

    private fun readRaw(
        extractor: MediaExtractor,
        format: MediaFormat,
        rate: Int,
        channels: Int,
        isFloat: Boolean,
    ): DecodedTrack? {
        val maxBytes = maxPcmBytes(rate, channels, isFloat)
        val chunks = ArrayList<ByteArray>()
        val scratch = ByteBuffer.allocateDirect(8192)
        var total = 0L
        var truncated = false
        while (true) {
            scratch.clear()
            val size = extractor.readSampleData(scratch, 0)
            if (size < 0) break
            val remaining = (maxBytes - total).coerceAtLeast(0L)
            val copySize = minOf(size.toLong(), remaining).toInt()
            if (copySize < size) {
                truncated = true
                break
            }
            scratch.position(0)
            scratch.limit(copySize)
            val chunk = ByteArray(copySize)
            scratch.get(chunk)
            chunks.add(chunk)
            total += copySize
            val hasMore = extractor.advance()
            if (total >= maxBytes && hasMore) {
                truncated = true
                break
            }
            if (!hasMore) break
        }
        extractor.release()
        if (truncated) {
            Log.e(TAG, "Raw track exceeds the $MAX_SECONDS-second decode limit")
            return null
        }
        if (total <= 0) return null
        return toTrack(chunks, rate, channels, isFloat)
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
            for (i in 0 until frameCount) {
                val base = i * channels * 4
                left[i] = joined.getFloat(base)
                right[i] = if (channels > 1) joined.getFloat(base + 4) else left[i]
            }
        } else {
            val scale = 1f / 32768f
            for (i in 0 until frameCount) {
                val base = i * channels * 2
                left[i] = joined.getShort(base) * scale
                right[i] = if (channels > 1) joined.getShort(base + 2) * scale else left[i]
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
