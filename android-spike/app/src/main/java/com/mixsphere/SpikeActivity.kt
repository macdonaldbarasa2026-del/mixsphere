/*
 * Mixsphere Phase 0 spike - two-deck landscape controller UI.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The layout mirrors the conventional two-deck + centre-mixer arrangement:
 * sync strip, dual waveforms, per-deck jog/transport/pitch, mode panel and a
 * split-colour crossfader. Every audio control is bound to MixsphereEngine.
 */
package com.mixsphere

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.mixsphere.engine.MixsphereEngine
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin

class SpikeActivity : Activity() {
    private var engine: MixsphereEngine? = null
    private var audioStarted = false
    private var pendingDeck = 0
    private var mode = MODE_EQ
    private val decks = ArrayList<DeckUi>(2)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var syncMeter: SyncMeterView
    private lateinit var recButton: Button
    private lateinit var masterMeter: LevelView
    private lateinit var crossfader: CrossfaderView
    private lateinit var beatgridRow: View
    private lateinit var modePanel: FrameLayout
    private val modeButtons = HashMap<String, Button>()
    private var recording = false

    private val refresh = object : Runnable {
        override fun run() {
            engine?.let { active ->
                val level = active.renderedEnergy().toFloat()
                val rateA = decks[0].rate
                val rateB = decks[1].rate
                syncMeter.setCloseness(
                    (1.0 - (abs(rateA - rateB) / 1.5).coerceIn(0.0, 1.0)).toFloat()
                )
                masterMeter.setLevel(level)
                statusText.text =
                    "${active.audioApi()}  ${active.sampleRate()} Hz  " +
                    "${String.format("%.1f", active.latencyMs())} ms  " +
                    "cb ${String.format("%.0f", active.callbackMicros())} us"
                for (deck in decks) {
                    deck.wave.update(level, deck.playing)
                    deck.jog.setSpinning(deck.playing)
                    deck.playButton.setTextColor(if (deck.playing) deck.color else TEXT)
                    deck.keyButton.setTextColor(if (deck.keylock) deck.color else TEXT)
                }
            }
            handler.postDelayed(this, 80L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        engine = MixsphereEngine()
        handler.post(refresh)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        engine?.close()
        engine = null
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_LOAD || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val deck = decks[pendingDeck]
        val copied = copyToCache(uri, "deck${deck.index}.wav")
        if (copied == null) {
            deck.name.text = "LOAD FAILED"
            return
        }
        if (engine?.loadTrack(deck.index, copied.absolutePath) == true) {
            deck.loaded = true
            deck.name.text = copied.nameWithoutExtension
        } else {
            deck.name.text = "UNSUPPORTED FILE"
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG0)
        }
        val deckA = DeckUi(0).also { decks.add(it) }
        val deckB = DeckUi(1).also { decks.add(it) }

        root.addView(buildSyncStrip(), LinearLayout.LayoutParams(MATCH, dp(46)))
        root.addView(buildWaveformStrip(), LinearLayout.LayoutParams(MATCH, dp(92)))
        beatgridRow = buildBeatgridRow()
        beatgridRow.visibility = View.GONE
        root.addView(beatgridRow, LinearLayout.LayoutParams(MATCH, dp(30)))

        val stage = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stage.addView(deckA.root, LinearLayout.LayoutParams(0, MATCH, 1f))
        stage.addView(buildCentre(), LinearLayout.LayoutParams(dp(132), MATCH))
        stage.addView(deckB.root, LinearLayout.LayoutParams(0, MATCH, 1f))
        root.addView(stage, LinearLayout.LayoutParams(MATCH, 0, 1f))

        modePanel = FrameLayout(this)
        root.addView(modePanel, LinearLayout.LayoutParams(MATCH, dp(88)))
        showMode(MODE_EQ)

        root.addView(buildCrossfaderBar(), LinearLayout.LayoutParams(MATCH, dp(58)))
        return root
    }

    private fun buildSyncStrip(): View {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BG1)
            setPadding(dp(8), 0, dp(8), 0)
        }
        statusText = TextView(this).apply {
            text = "MIXSPHERE  |  ENGINE STARTING"
            setTextColor(TEXT_DIM)
            textSize = 10f
            setSingleLine(true)
        }
        strip.addView(statusText, LinearLayout.LayoutParams(0, WRAP, 1f))

        syncMeter = SyncMeterView(this)
        strip.addView(syncMeter, LinearLayout.LayoutParams(dp(120), dp(14)))
        strip.addView(
            TextView(this).apply {
                text = "AUTOMIX"
                setTextColor(TEXT_DIM)
                textSize = 9f
                setPadding(dp(4), 0, dp(4), 0)
            },
        )

        recButton = button("REC", REC_TEXT) { toggleRecord() }
        strip.addView(recButton, LinearLayout.LayoutParams(WRAP, dp(32)))
        return strip
    }

    private fun buildWaveformStrip(): View {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(SCOPE_BG)
        }
        for (deck in decks) {
            val holder = FrameLayout(this).apply { setBackgroundColor(SCOPE_BG) }
            deck.wave = WaveformView(this, deck.color)
            holder.addView(deck.wave, FrameLayout.LayoutParams(MATCH, MATCH))
            strip.addView(holder, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        val divider = View(this).apply { setBackgroundColor(BG3) }
        strip.addView(divider, LinearLayout.LayoutParams(dp(2), MATCH))

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG1)
        }
        tools.addView(button("BEND", TEXT_DIM) { }, LinearLayout.LayoutParams(dp(64), 0, 1f))
        tools.addView(button("SCRATCH", TEXT_DIM) { }, LinearLayout.LayoutParams(dp(64), 0, 1f))
        tools.addView(button("GRID", TEXT_DIM) {
            beatgridRow.visibility = if (beatgridRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }, LinearLayout.LayoutParams(dp(64), 0, 1f))
        strip.addView(tools, LinearLayout.LayoutParams(dp(68), MATCH))
        return strip
    }

    private fun buildBeatgridRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BG2)
            setPadding(dp(6), 0, dp(6), 0)
        }
        for (label in listOf("x2", "/2", "<<", ">>", "+", "-", "RESET", "MORE")) {
            row.addView(
                button(label, TEXT) { },
                LinearLayout.LayoutParams(0, dp(24), 1f).apply { setMargins(dp(2), 0, dp(2), 0) },
            )
        }
        return row
    }

    private fun buildCentre(): View {
        masterMeter = LevelView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG0)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }

        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (entry in listOf(
            MODE_EQ to "EQ",
            MODE_LOOP to "LOOP",
            MODE_FX to "FX",
            MODE_SAMPLE to "SMP",
        )) {
            val button = button(entry.second, TEXT_DIM) { showMode(entry.first) }
            modeButtons[entry.first] = button
            modes.addView(button, LinearLayout.LayoutParams(0, dp(30), 1f))
        }
        column.addView(modes, LinearLayout.LayoutParams(MATCH, WRAP))

        column.addView(
            TextView(this).apply {
                text = "MASTER"
                setTextColor(LABEL)
                textSize = 10f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(4))
            },
        )
        column.addView(masterMeter, LinearLayout.LayoutParams(dp(18), 0, 1f))

        for (deck in decks) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            row.addView(
                TextView(this).apply {
                    text = if (deck.index == 0) "A" else "B"
                    setTextColor(deck.color)
                    textSize = 11f
                },
            )
            deck.meter = LevelView(this)
            row.addView(deck.meter, LinearLayout.LayoutParams(0, dp(8), 1f))
            val monitor = button("MON", TEXT_DIM) { }
            var monitoring = false
            monitor.setOnClickListener {
                monitoring = !monitoring
                monitor.setTextColor(if (monitoring) deck.color else TEXT_DIM)
            }
            row.addView(monitor, lp(WRAP, dp(20)))
            column.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        column.addView(
            TextView(this).apply {
                text = "MIXSPHERE"
                setTextColor(TEXT_DIM)
                textSize = 10f
                gravity = Gravity.CENTER
                isAllCaps = true
                setPadding(0, dp(8), 0, 0)
            },
        )
        return column
    }

    private fun showMode(next: String) {
        mode = next
        for ((key, button) in modeButtons) {
            button.setTextColor(if (key == mode) ACCENT else TEXT_DIM)
        }
        modePanel.removeAllViews()
        modePanel.addView(
            when (mode) {
                MODE_LOOP -> buildLoopPanel()
                MODE_FX -> buildFxPanel()
                MODE_SAMPLE -> buildSamplePanel()
                else -> buildEqPanel()
            },
            FrameLayout.LayoutParams(MATCH, MATCH),
        )
    }

    private fun buildEqPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG1)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        for (deck in decks) {
            for (band in BANDS) {
                val column = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                val fader = VerticalSeekBar(this, deck.color)
                fader.max = 1000
                fader.progress = if (band == "VOL") 1000 else 500
                if (band == "VOL") {
                    fader.setOnSeekBarChangeListener(simpleListener { value ->
                        engine?.setGain(deck.index, value / 1000.0)
                    })
                }
                column.addView(fader, LinearLayout.LayoutParams(dp(26), 0, 1f))
                column.addView(
                    TextView(this).apply {
                        text = band
                        setTextColor(LABEL)
                        textSize = 9f
                    },
                )
                val cell = LinearLayout(this).apply { gravity = Gravity.CENTER }
                cell.addView(column, LinearLayout.LayoutParams(0, MATCH, 1f))
                panel.addView(cell, LinearLayout.LayoutParams(0, MATCH, 1f))
            }
        }
        return panel
    }

    private fun buildLoopPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG1)
        }
        for (size in LOOP_SIZES) {
            panel.addView(
                button(size, TEXT) {
                    for (i in 0 until panel.childCount) {
                        val child = panel.getChildAt(i) as? Button ?: continue
                        child.setBackgroundColor(
                            if (child.text.toString() == size) ACCENT_DARK else BUTTON_BG
                        )
                    }
                },
                LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(4), 0, dp(4), 0) },
            )
        }
        return panel
    }

    private fun buildFxPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG1)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val pad = XyPadView(this)
        panel.addView(pad, LinearLayout.LayoutParams(0, MATCH, 1f))
        val effects = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (name in FX_NAMES) {
            effects.addView(
                button(name, TEXT_DIM) { },
                LinearLayout.LayoutParams(MATCH, 0, 1f).apply { setMargins(0, dp(2), 0, dp(2)) },
            )
        }
        panel.addView(effects, LinearLayout.LayoutParams(dp(96), MATCH))
        return panel
    }

    private fun buildSamplePanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG1)
        }
        for (index in 1..4) {
            panel.addView(
                button("S$index", TEXT) { },
                LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(4), 0, dp(4), 0) },
            )
        }
        return panel
    }

    private fun buildCrossfaderBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BG1)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        bar.addView(button("AUTO A", DECK_A_COLOR) { crossfader.setValue(-1f) }, lp(WRAP, dp(30)))
        crossfader = CrossfaderView(this)
        crossfader.onValue = { value -> engine?.setCrossfader(value.toDouble()) }
        bar.addView(crossfader, LinearLayout.LayoutParams(0, MATCH, 1f))
        bar.addView(button("AUTO B", DECK_B_COLOR) { crossfader.setValue(1f) }, lp(WRAP, dp(30)))
        bar.addView(button("DEMO", TEXT_DIM) { loadDemoTone() }, lp(WRAP, dp(30)))
        return bar
    }

    private fun lp(width: Int, height: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, height)

    private inner class DeckUi(val index: Int) {
        val label = if (index == 0) "DECK A" else "DECK B"
        val color = if (index == 0) DECK_A_COLOR else DECK_B_COLOR
        val root = LinearLayout(this@SpikeActivity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG0)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        lateinit var wave: WaveformView
        lateinit var jog: JogView
        lateinit var meter: LevelView
        lateinit var name: TextView
        lateinit var keyNote: TextView
        lateinit var playButton: Button
        lateinit var keyButton: Button
        var loaded = false
        var playing = false
        var keylock = false
        var rate = 1.0

        init {
            val top = LinearLayout(this@SpikeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(BG2)
            }
            name = TextView(this@SpikeActivity).apply {
                text = "NO TRACK"
                setTextColor(TEXT)
                textSize = 11f
                setSingleLine(true)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), 0, dp(4), 0)
            }
            keyNote = TextView(this@SpikeActivity).apply {
                text = "KEY"
                setTextColor(color)
                textSize = 9f
                setPadding(0, 0, dp(6), 0)
            }
            top.addView(button("LOAD", TEXT) { openPicker() }, LinearLayout.LayoutParams(WRAP, dp(30)))
            top.addView(name, LinearLayout.LayoutParams(0, MATCH, 1f))
            top.addView(keyNote)
            root.addView(top, LinearLayout.LayoutParams(MATCH, dp(38)))

            jog = JogView(this@SpikeActivity, color, if (index == 0) "A" else "B")
            root.addView(jog, LinearLayout.LayoutParams(MATCH, 0, 1f))

            val transport = LinearLayout(this@SpikeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
            }
            playButton = button("PLAY", color) { togglePlay() }
            val cue = button("CUE", TEXT) { cue() }
            val sync = button("SYNC", TEXT) { syncToThis() }
            keyButton = button("KEY", TEXT) { setKeyLock() }
            transport.addView(cue, LinearLayout.LayoutParams(0, MATCH, 1f))
            transport.addView(playButton, LinearLayout.LayoutParams(0, MATCH, 1f))
            transport.addView(sync, LinearLayout.LayoutParams(0, MATCH, 1f))
            transport.addView(keyButton, LinearLayout.LayoutParams(0, MATCH, 1f))
            root.addView(transport, LinearLayout.LayoutParams(MATCH, dp(42)))

            val pitchRow = LinearLayout(this@SpikeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(BG1)
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }
            val pitch = VerticalSeekBar(this@SpikeActivity, color)
            pitch.max = 1000
            pitch.progress = 500
            val rateLabel = TextView(this@SpikeActivity).apply {
                text = "1.00x"
                setTextColor(TEXT)
                textSize = 11f
            }
            pitch.setOnSeekBarChangeListener(simpleListener { value ->
                rate = 0.5 + value / 1000.0
                rateLabel.text = String.format("%.2fx", rate)
                engine?.setTempo(index, rate, keylock)
            })
            pitchRow.addView(pitch, LinearLayout.LayoutParams(dp(30), dp(76)))
            pitchRow.addView(
                TextView(this@SpikeActivity).apply {
                    text = "TEMPO"
                    setTextColor(LABEL)
                    textSize = 10f
                },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(8), 0, dp(6), 0) },
            )
            pitchRow.addView(rateLabel)
            root.addView(pitchRow, LinearLayout.LayoutParams(MATCH, dp(82)))
        }

        fun openPicker() {
            pendingDeck = index
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "audio/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(intent, "Load $label"), REQUEST_LOAD)
        }

        fun togglePlay() {
            val active = engine ?: return
            if (!loaded) {
                name.text = "LOAD A TRACK FIRST"
                return
            }
            if (!audioStarted) audioStarted = active.startAudio()
            playing = !playing
            active.play(index, playing)
            playButton.text = if (playing) "PAUSE" else "PLAY"
        }

        fun cue() {
            engine?.seek(index, 0.0)
            jog.reset()
        }

        fun syncToThis() {
            val other = decks[1 - index]
            other.rate = rate
            other.keylock = keylock
            engine?.setTempo(other.index, rate, keylock)
            engine?.setTempo(index, rate, keylock)
        }

        fun setKeyLock() {
            keylock = !keylock
            engine?.setTempo(index, rate, keylock)
            keyButton.text = if (keylock) "KEY ON" else "KEY OFF"
            keyNote.text = if (keylock) "KEY LOCK" else "KEY"
        }
    }

    private fun toggleRecord() {
        recording = !recording
        recButton.setTextColor(if (recording) REC_TEXT else TEXT_DIM)
        recButton.setBackgroundColor(if (recording) REC_ACTIVE else BUTTON_BG)
    }

    private fun loadDemoTone() {
        val file = File(cacheDir, "mixsphere_demo.wav")
        writeDemoWav(file)
        val deck = decks[0]
        if (engine?.loadTrack(0, file.absolutePath) == true) {
            deck.loaded = true
            deck.name.text = "DEMO TONE 440 Hz"
        }
    }

    private fun copyToCache(uri: Uri, name: String): File? = try {
        val out = File(cacheDir, name)
        contentResolver.openInputStream(uri).use { input ->
            FileOutputStream(out).use { stream -> input?.copyTo(stream) }
        }
        out.takeIf { it.length() > 0L }
    } catch (e: Exception) {
        null
    }

    private fun writeDemoWav(file: File) {
        val frames = SAMPLE_RATE * 2
        val dataSize = frames * 4
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
        for (i in 0 until frames) {
            val value = (sin(2.0 * PI * 440.0 * i / SAMPLE_RATE) * 12000.0).toInt().toShort()
            buffer.putShort(value)
            buffer.putShort(value)
        }
        FileOutputStream(file).use { it.write(buffer.array()) }
    }

    private fun button(text: String, color: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 10f
            isAllCaps = true
            setTextColor(color)
            background = rounded(BUTTON_BG)
            setOnClickListener { onClick() }
        }

    private class SyncMeterView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var closeness = 0f

        fun setCloseness(value: Float) {
            closeness = value.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = GROOVE
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 4f, 4f, paint)
            paint.color = SYNCMETER
            val end = width * closeness
            if (end > 1f) canvas.drawRoundRect(0f, 0f, end, height.toFloat(), 4f, 4f, paint)
        }
    }

    private class WaveformView(context: Context, private val color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var level = 0f
        private var phase = 0f

        fun update(value: Float, playing: Boolean) {
            level = value.coerceIn(0f, 1f)
            if (playing) phase += 0.06f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val mid = height / 2f
            paint.color = GRID
            paint.strokeWidth = 1f
            canvas.drawLine(0f, mid, width.toFloat(), mid, paint)
            for (x in 0..width step 24) {
                canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), paint)
            }
            val bars = 56
            val step = width.toFloat() / bars
            paint.color = color
            paint.strokeWidth = Math.max(2f, step * 0.55f)
            val drive = Math.min(1f, level * 6f)
            for (i in 0 until bars) {
                val wave = sin(i * 0.49f + phase * 3f) * 0.55f +
                    sin(i * 0.17f - phase) * 0.45f
                val amplitude = Math.abs(wave) * drive * mid * 0.9f
                if (amplitude < 1f) continue
                val x = i * step + step / 2f
                canvas.drawLine(x, mid - amplitude, x, mid + amplitude, paint)
            }
            val playhead = ((phase * 40f) % width).coerceIn(0f, width.toFloat())
            paint.color = TEXT
            paint.strokeWidth = 2f
            canvas.drawLine(playhead, 0f, playhead, height.toFloat(), paint)
        }
    }

    private class JogView(context: Context, private val color: Int, private val letter: String) :
        View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var angle = 0f
        private var lastAngle = 0f
        private var spinning = false
        private var dragging = false

        fun setSpinning(value: Boolean) {
            if (spinning != value) {
                spinning = value
                invalidate()
            }
        }

        fun reset() {
            angle = 0f
            invalidate()
        }

        init {
            isClickable = true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val a = atan2(event.y - height / 2f, event.x - width / 2f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    lastAngle = a
                }
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    var delta = a - lastAngle
                    if (delta > PI) delta -= (2 * PI).toFloat()
                    if (delta < -PI) delta += (2 * PI).toFloat()
                    angle += delta
                    lastAngle = a
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = Math.min(cx, cy) * 0.86f
            paint.color = GROOVE
            canvas.drawCircle(cx, cy, radius, paint)
            paint.color = BG2
            canvas.drawCircle(cx, cy, radius * 0.92f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = GRID
            canvas.drawCircle(cx, cy, radius * 0.74f, paint)
            canvas.drawCircle(cx, cy, radius * 0.42f, paint)
            paint.color = color
            paint.strokeWidth = 4f
            canvas.drawArc(
                cx - radius * 0.92f,
                cy - radius * 0.92f,
                cx + radius * 0.92f,
                cy + radius * 0.92f,
                Math.toDegrees(angle.toDouble()).toFloat(),
                42f,
                false,
                paint,
            )
            paint.style = Paint.Style.FILL
            paint.color = if (spinning) color else TEXT_DIM
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = radius * 0.5f
            canvas.drawText(letter, cx, cy + radius * 0.16f, paint)
        }
    }

    private class LevelView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var level = 0f

        fun setLevel(value: Float) {
            level = value.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = GROOVE
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            val top = (1f - level) * height
            paint.color = if (level > 0.8f) LEVEL_HOT else LEVEL_OK
            canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), paint)
        }
    }

    private class CrossfaderView(context: Context) : View(context) {
        var onValue: ((Float) -> Unit)? = null
        private var value = 0f

        init {
            isClickable = true
        }

        fun setValue(next: Float) {
            value = next.coerceIn(-1f, 1f)
            onValue?.invoke(value)
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP ->
                    setValue((event.x / width * 2f - 1f).coerceIn(-1f, 1f))
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val mid = height / 2f
            paint.color = GROOVE
            canvas.drawRoundRect(0f, mid - 6f, width.toFloat(), mid + 6f, 5f, 5f, paint)
            paint.color = DECK_A_COLOR
            val centre = width / 2f
            if (value < 0f) {
                canvas.drawRoundRect(0f, mid - 6f, centre, mid + 6f, 5f, 5f, paint)
            } else {
                canvas.drawRoundRect(0f, mid - 6f, centre + (width / 2f) * value, mid + 6f, 5f, 5f, paint)
            }
            paint.color = DECK_B_COLOR
            if (value > 0f) {
                canvas.drawRoundRect(centre, mid - 6f, width.toFloat(), mid + 6f, 5f, 5f, paint)
            } else {
                canvas.drawRoundRect(centre + (width / 2f) * value, mid - 6f, width.toFloat(), mid + 6f, 5f, 5f, paint)
            }
            paint.color = TEXT
            paint.strokeWidth = 2f
            canvas.drawLine(centre, 0f, centre, height.toFloat(), paint)
            val x = centre + (width / 2f) * value
            paint.color = Color.WHITE
            canvas.drawRoundRect(x - 9f, 0f, x + 9f, height.toFloat(), 4f, 4f, paint)
        }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    }

    private class XyPadView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var x = 0.5f
        private var y = 0.5f

        init {
            isClickable = true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    x = (event.x / width).coerceIn(0f, 1f)
                    y = (event.y / height).coerceIn(0f, 1f)
                    invalidate()
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = GROOVE
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.color = GRID
            paint.strokeWidth = 1f
            canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), paint)
            canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, paint)
            paint.color = ACCENT
            canvas.drawCircle(width * x, height * y, 7f, paint)
        }
    }

    private class VerticalSeekBar(context: Context, color: Int) : SeekBar(context) {
        init {
            progressTintList = ColorStateList.valueOf(color)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(GROOVE)
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(heightSpec, widthSpec)
            setMeasuredDimension(measuredHeight, measuredWidth)
        }

        override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
            super.onSizeChanged(h, w, oldW, oldH)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.rotate(-90f, width / 2f, height / 2f)
            super.onDraw(canvas)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isEnabled) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    progress = (max - (event.y * max / height).toInt()).coerceIn(0, max)
                    performClick()
                }
            }
            return true
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(5).toFloat()
        setColor(color)
    }

    private fun simpleListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    companion object {
        private const val REQUEST_LOAD = 42
        private const val SAMPLE_RATE = 48000
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val MODE_EQ = "EQ"
        private const val MODE_LOOP = "LOOP"
        private const val MODE_FX = "FX"
        private const val MODE_SAMPLE = "SMP"
        private val BANDS = listOf("VOL", "HI", "MID", "LOW")
        private val LOOP_SIZES = listOf("1/32", "1/16", "1/8", "1/4", "1/2", "1", "2", "4")
        private val FX_NAMES = listOf("FILTER", "ECHO", "FLANGER")
        private val BG0 = Color.parseColor("#191A1D")
        private val BG1 = Color.parseColor("#2E3036")
        private val BG2 = Color.parseColor("#3E424A")
        private val BG3 = Color.parseColor("#5A5F68")
        private val SCOPE_BG = Color.parseColor("#0B0D10")
        private val GROOVE = Color.parseColor("#111317")
        private val GRID = Color.parseColor("#262A31")
        private val TEXT = Color.parseColor("#D9DEE3")
        private val TEXT_DIM = Color.parseColor("#9299A2")
        private val LABEL = Color.parseColor("#868D96")
        private val ACCENT = Color.parseColor("#F08226")
        private val ACCENT_DARK = Color.parseColor("#7A4318")
        private val DECK_A_COLOR = Color.parseColor("#F08226")
        private val DECK_B_COLOR = Color.parseColor("#46B7C9")
        private val SYNCMETER = Color.parseColor("#8CC63F")
        private val LEVEL_OK = Color.parseColor("#8CC63F")
        private val LEVEL_HOT = Color.parseColor("#E8563F")
        private val REC_TEXT = Color.parseColor("#F26A60")
        private val REC_ACTIVE = Color.parseColor("#8A2B22")
        private val BUTTON_BG = Color.parseColor("#34383F")
    }
}
