/*
 * Mixsphere Phase 0 spike - bundled CC0 sample bank.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The source sounds are released under CC0 1.0 by the SFX Kit project
 * (github.com/wyzowl/sfx-kit). They are converted to PCM16 WAV for the spike
 * decoder and stored under assets/samples with their license notice.
 */
package com.mixsphere

import android.content.Context
import java.io.File
import java.io.FileOutputStream

class SampleLibrary(private val context: Context) {
    data class Sample(val label: String, val fileName: String, val durationMs: Long)

    val samples = listOf(
        Sample("RISER 2", "riser-2s.wav", 2195L),
        Sample("RISER 4", "riser-4s.wav", 4191L),
        Sample("FALL 2", "faller-2s.wav", 1882L),
        Sample("FALL 4", "faller-4s.wav", 3870L),
        Sample("REV S", "reverse-short.wav", 499L),
        Sample("REV L", "reverse-long.wav", 1358L),
        Sample("WHOOSH F", "whoosh-fast.wav", 252L),
        Sample("WHOOSH S", "whoosh-slow.wav", 1016L),
        Sample("IMPACT H", "impact-heavy.wav", 300L),
        Sample("IMPACT D", "impact-deep.wav", 1204L),
        Sample("IMPACT M", "impact-medium.wav", 162L),
        Sample("SUB S", "sub-drop-short.wav", 1175L),
        Sample("SUB M", "sub-drop-medium.wav", 2154L),
        Sample("BOOM", "boom-drop.wav", 4293L),
        Sample("SLAM", "cinematic-slam.wav", 2225L),
        Sample("808", "bass-808.wav", 1469L),
    )

    private val directory = File(context.filesDir, "samples")

    fun install() {
        directory.mkdirs()
        context.assets.open("samples/LICENSE.txt").use { input ->
            FileOutputStream(File(directory, "LICENSE.txt")).use { output ->
                input.copyTo(output)
            }
        }
        for (sample in samples) {
            val target = File(directory, sample.fileName)
            if (target.exists() && target.length() > 0L) continue
            context.assets.open("samples/${sample.fileName}").use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
        }
    }

    fun path(index: Int): String = File(directory, samples[index].fileName).absolutePath
}
