package com.mixsphere.dj

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/** 16 royalty-free transition one-shots bundled as CC0 assets. */
class SampleBank(context: Context) {

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .build()

    private val ids = IntArray(SPECS.size)

    init {
        SPECS.forEachIndexed { index, spec ->
            try {
                val descriptor = context.assets.openFd("samples/${spec.file}")
                ids[index] = soundPool.load(descriptor, 1)
                descriptor.close()
            } catch (_: Exception) {
                ids[index] = 0
            }
        }
    }

    fun play(index: Int) {
        val id = ids.getOrNull(index) ?: return
        if (id != 0) soundPool.play(id, 1f, 1f, 0, 0, 1f)
    }

    fun release() = soundPool.release()

    companion object {
        data class Spec(val label: String, val file: String)

        val SPECS = listOf(
            Spec("808", "bass-808.wav"),
            Spec("BOOM", "boom-drop.wav"),
            Spec("SLAM", "cinematic-slam.wav"),
            Spec("FALL 2", "faller-2s.wav"),
            Spec("FALL 4", "faller-4s.wav"),
            Spec("IMP D", "impact-deep.wav"),
            Spec("IMP H", "impact-heavy.wav"),
            Spec("IMP M", "impact-medium.wav"),
            Spec("REV L", "reverse-long.wav"),
            Spec("REV S", "reverse-short.wav"),
            Spec("RISE 2", "riser-2s.wav"),
            Spec("RISE 4", "riser-4s.wav"),
            Spec("SUB M", "sub-drop-medium.wav"),
            Spec("SUB S", "sub-drop-short.wav"),
            Spec("WHOOS F", "whoosh-fast.wav"),
            Spec("WHOOS S", "whoosh-slow.wav"),
        )
    }
}
