package com.mixsphere.dj

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import java.io.File
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.mixsphere.dj.data.LibraryTrack
import com.mixsphere.dj.data.TrackLibrary
import com.mixsphere.dj.engine.AudioDecoder
import com.mixsphere.dj.engine.Deck
import com.mixsphere.dj.engine.MixerEngine
import com.mixsphere.dj.engine.StemResult
import com.mixsphere.dj.engine.StemSeparator
import com.mixsphere.dj.ui.CrossfaderView
import com.mixsphere.dj.ui.LevelView
import com.mixsphere.dj.ui.JogView
import com.mixsphere.dj.ui.Theme
import com.mixsphere.dj.ui.Theme.MATCH
import com.mixsphere.dj.ui.Theme.WRAP
import com.mixsphere.dj.ui.VerticalFader
import com.mixsphere.dj.ui.WaveformView
import com.mixsphere.dj.ui.XyPadView
import kotlin.math.abs

class DjActivity : Activity() {

    private lateinit var engine: MixerEngine
    private var sampleBank: SampleBank? = null
    private val deckUis = ArrayList<DeckUi>(2)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var modePanel: FrameLayout
    private lateinit var libraryOverlay: FrameLayout
    private lateinit var crossfaderView: CrossfaderView
    private lateinit var masterMeter: LevelView
    private var mode = MODE_EQ
    private var fxDeck = 1
    private val fxButtons = HashMap<Int, Button>()
    private var stemResult: StemResult? = null
    private var stemTarget = 0
    private var stemBusy = false
    private var stemStatus: TextView? = null
    private val deckUris = arrayOf<Uri?>(null, null)
    private var pendingDeck = 0
    private val modeButtons = HashMap<String, Button>()
    private val refresh = object : Runnable {
        override fun run() {
            for (ui in deckUis) ui.tick()
            masterMeter.setLevel(engine.masterLevel)
            status.text = String.format(
                "%d kHz  •  %s",
                OUTPUT_RATE / 1000,
                if (engine.isRunning) "AUDIO ACTIVE" else "READY",
            )
            handler.postDelayed(this, 60L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = MixerEngine(OUTPUT_RATE)
        sampleBank = SampleBank(this)
        setContentView(buildUi())
        maybeRequestPermission()
        handler.post(refresh)
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.let { uri ->
            pendingDeck = 0
            loadFromUri(uri, 0)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        engine.close()
        sampleBank?.release()
        sampleBank = null
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG0)
        }
        root.addView(buildTopBar(), LinearLayout.LayoutParams(MATCH, dp(46)))

        val deckA = DeckUi(0).also { deckUis.add(it) }
        val deckB = DeckUi(1).also { deckUis.add(it) }

        val waveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.SCOPE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        waveRow.addView(deckA.wave, LinearLayout.LayoutParams(0, MATCH, 1f))
        waveRow.addView(View(this).apply { setBackgroundColor(Theme.BG3) }, LinearLayout.LayoutParams(dp(2), MATCH))
        waveRow.addView(deckB.wave, LinearLayout.LayoutParams(0, MATCH, 1f))
        waveRow.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(btn("BEND", Theme.DIM) { nudge(0, 0.03f) }, LinearLayout.LayoutParams(dp(62), 0, 1f))
                addView(btn("SCRATCH", Theme.DIM) { nudge(1, -0.03f) }, LinearLayout.LayoutParams(dp(62), 0, 1f))
            },
            LinearLayout.LayoutParams(dp(66), MATCH),
        )
        root.addView(waveRow, LinearLayout.LayoutParams(MATCH, dp(76)))

        val stage = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stage.addView(deckA.root, LinearLayout.LayoutParams(0, MATCH, 1f))
        stage.addView(buildCentre(), LinearLayout.LayoutParams(dp(126), MATCH))
        stage.addView(deckB.root, LinearLayout.LayoutParams(0, MATCH, 1f))
        root.addView(stage, LinearLayout.LayoutParams(MATCH, 0, 1f))

        modePanel = FrameLayout(this)
        root.addView(modePanel, LinearLayout.LayoutParams(MATCH, dp(112)))
        showMode(MODE_EQ)

        root.addView(buildCrossfaderBar(), LinearLayout.LayoutParams(MATCH, dp(52)))

        libraryOverlay = buildLibraryOverlay()
        root.addView(
            libraryOverlay,
            FrameLayout.LayoutParams(MATCH, MATCH, Gravity.TOP or Gravity.CENTER_HORIZONTAL),
        )
        libraryOverlay.visibility = View.GONE
        return root
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.BG1)
            setPadding(dp(12), 0, dp(12), 0)
        }
        val brand = TextView(this).apply {
            text = "MIXSPHERE"
            setTextColor(Theme.TEXT)
            textSize = 15f
            letterSpacing = 0.1f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setSingleLine(true)
        }
        bar.addView(brand, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            setMargins(0, 0, dp(12), 0)
        })
        bar.addView(View(this).apply {
            background = Theme.rounded(Theme.ACCENT, dp(5).toFloat())
        }, LinearLayout.LayoutParams(dp(6), dp(6)).apply {
            setMargins(0, 0, dp(8), 0)
        })
        status = TextView(this).apply {
            setTextColor(Theme.DIM)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.05f
            setSingleLine(true)
        }
        bar.addView(status, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(btn("TRACKS", Theme.BG0, Theme.ACCENT) { openLibrary() },
            LinearLayout.LayoutParams(WRAP, dp(30)).apply { setMargins(dp(6), 0, 0, 0) })
        return bar
    }

    private fun buildCentre(): View {
        masterMeter = LevelView(this).apply { color = Theme.GREEN }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG0)
            setPadding(dp(3), dp(5), dp(3), dp(5))
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (entry in listOf(
            MODE_EQ to "EQ", MODE_LOOP to "LOOP", MODE_FX to "FX",
            MODE_SAMPLE to "SMP", MODE_CUE to "CUE", MODE_STEM to "STEM",
        )) {
            val button = btn(entry.second, Theme.DIM) { showMode(entry.first) }
            modeButtons[entry.first] = button
            tabs.addView(button, LinearLayout.LayoutParams(0, dp(28), 1f))
        }
        column.addView(tabs, LinearLayout.LayoutParams(MATCH, WRAP))

        column.addView(text("MASTER", Theme.DIM, 10f, Gravity.CENTER),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, dp(2)) })
        column.addView(masterMeter, LinearLayout.LayoutParams(dp(16), 0, 1f))

        for (ui in deckUis) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, 0)
            }
            row.addView(text(if (ui.index == 0) "A" else "B", ui.color, 11f),
                LinearLayout.LayoutParams(WRAP, WRAP))
            ui.meter = LevelView(this).apply { color = ui.color }
            row.addView(ui.meter, LinearLayout.LayoutParams(0, dp(8), 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            })
            column.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        column.addView(text("MASTER BUS", Theme.DIM, 9f, Gravity.CENTER),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, 0) })
        return column
    }

    private fun buildCrossfaderBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.BG1)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        bar.addView(btn("AUTO A", Theme.DECK_A) { crossfaderView.setValue(-1f) },
            LinearLayout.LayoutParams(WRAP, dp(30)))
        crossfaderView = CrossfaderView(this).apply {
            onValue = { engine.setCrossfader(it) }
        }
        bar.addView(crossfaderView, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
            setMargins(dp(6), 0, dp(6), 0)
        })
        bar.addView(btn("AUTO B", Theme.DECK_B) { crossfaderView.setValue(1f) },
            LinearLayout.LayoutParams(WRAP, dp(30)))
        return bar
    }

    private fun showMode(next: String) {
        mode = next
        for ((key, button) in modeButtons) {
            button.setTextColor(if (key == mode) Theme.ACCENT else Theme.DIM)
        }
        modePanel.removeAllViews()
        val view = when (mode) {
            MODE_LOOP -> buildLoopPanel()
            MODE_FX -> buildFxPanel()
            MODE_SAMPLE -> buildSamplePanel()
            MODE_CUE -> buildCuePanel()
            MODE_STEM -> buildStemPanel()
            else -> buildEqPanel()
        }
        modePanel.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun buildEqPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.BG1)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        for (ui in deckUis) {
            for (band in BANDS) {
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                val fader = VerticalFader(this, ui.color)
                fader.setValue(if (band == "VOL") 1f else 0.5f)
                fader.onValue = { value ->
                    val deck = engine.decks[ui.index]
                    when (band) {
                        "VOL" -> deck.gain = value * 1.4f
                        "HI" -> deck.eqHigh = value * 2f
                        "MID" -> deck.eqMid = value * 2f
                        "LOW" -> deck.eqLow = value * 2f
                    }
                }
                cell.addView(fader, LinearLayout.LayoutParams(dp(30), 0, 1f))
                cell.addView(text(band, Theme.DIM, 9f, Gravity.CENTER),
                    LinearLayout.LayoutParams(WRAP, WRAP))
                panel.addView(cell, LinearLayout.LayoutParams(0, MATCH, 1f))
            }
        }
        return panel
    }

    private fun buildLoopPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG1)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        for (ui in deckUis) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(text(if (ui.index == 0) "A" else "B", ui.color, 11f),
                LinearLayout.LayoutParams(dp(20), MATCH, Gravity.CENTER_VERTICAL))
            for (entry in LOOP_BEATS) {
                row.addView(btn(entry.first, Theme.TEXT) {
                    setLoop(ui.index, entry.second)
                }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                })
            }
            row.addView(btn("EXIT", Theme.RED) { engine.decks[ui.index].clearLoop() },
                LinearLayout.LayoutParams(dp(58), dp(40)).apply { setMargins(dp(2), 0, 0, 0) })
            panel.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        return panel
    }

    private fun buildFxPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.BG1)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val pad = XyPadView(this).apply {
            onValue = { x, y ->
                val deck = engine.decks[fxDeck]
                deck.setFx(deck.fxType, x, 1f - y)
            }
        }
        panel.addView(pad, LinearLayout.LayoutParams(0, MATCH, 1f))
        val effects = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val target = btn("FX: ${if (fxDeck == 0) "A" else "B"}", Theme.ACCENT) { button ->
            fxDeck = 1 - fxDeck
            button.text = "FX: ${if (fxDeck == 0) "A" else "B"}"
        }
        effects.addView(target, LinearLayout.LayoutParams(MATCH, 0, 1f))
        fxButtons.clear()
        for (entry in listOf(0 to "OFF", 1 to "FILTER", 2 to "ECHO", 3 to "FLANGER")) {
            val button = btn(entry.second, Theme.DIM) {
                engine.decks[fxDeck].fxType = entry.first
                for ((key, item) in fxButtons) {
                    item.setTextColor(if (key == entry.first) Theme.ACCENT else Theme.DIM)
                }
            }
            fxButtons[entry.first] = button
            effects.addView(button, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
                setMargins(0, dp(2), 0, dp(2))
            })
        }
        panel.addView(effects, LinearLayout.LayoutParams(dp(100), MATCH).apply {
            setMargins(dp(8), 0, 0, 0)
        })
        return panel
    }

    private fun buildSamplePanel(): View {
        val grid = GridLayout(this).apply {
            rowCount = 4
            columnCount = 4
            setBackgroundColor(Theme.BG1)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        for ((index, spec) in SampleBank.SPECS.withIndex()) {
            val color = if (index % 2 == 0) Theme.DECK_A else Theme.DECK_B
            val pad = btn(spec.label, color) { sampleBank?.play(index) }
            grid.addView(
                pad,
                GridLayout.LayoutParams(
                    GridLayout.spec(index / 4, 1f),
                    GridLayout.spec(index % 4, 1f),
                ).apply {
                    width = 0
                    height = MATCH
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                },
            )
        }
        return grid
    }

    private fun buildCuePanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.BG1)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        for (ui in deckUis) {
            val group = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Theme.BG2)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            for (i in 0 until 8) {
                val pad = btn((i + 1).toString(), if (i % 2 == 0) Theme.DECK_A else Theme.DECK_B) {
                    engine.decks[ui.index].hotCue(i)
                }
                group.addView(pad, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                })
            }
            panel.addView(group, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        return panel
    }

    private fun buildStemPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.BG1)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        val grid = GridLayout(this).apply {
            rowCount = 2
            columnCount = 3
        }
        val labels = listOf(
            "vocals" to "VOCALS", "beat" to "BEAT", "harmony" to "HARMONY",
            "no_vocals" to "NO VOC", "no_beat" to "NO BEAT", "no_harmony" to "NO HARM",
        )
        for ((index, entry) in labels.withIndex()) {
            val pad = btn(entry.second, if (index % 2 == 0) Theme.DECK_A else Theme.DECK_B) {
                val result = stemResult
                if (result == null || stemBusy) {
                    startSeparation()
                } else {
                    result.files[entry.first]?.let { playStem(it) }
                }
            }
            grid.addView(
                pad,
                GridLayout.LayoutParams(
                    GridLayout.spec(index / 3, 1f),
                    GridLayout.spec(index % 3, 1f),
                ).apply {
                    width = 0
                    height = MATCH
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                },
            )
        }
        panel.addView(grid, LinearLayout.LayoutParams(0, MATCH, 1f))

        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, 0, 0)
        }
        val target = btn("TO ${if (stemTarget == 0) "A" else "B"}", Theme.ACCENT) { button ->
            stemTarget = 1 - stemTarget
            button.text = "TO ${if (stemTarget == 0) "A" else "B"}"
        }
        side.addView(target, LinearLayout.LayoutParams(MATCH, dp(30)))
        val statusView = text(
            if (stemBusy) "SEPARATING…" else if (stemResult == null) "TAP A PAD TO ANALYSE" else "STEMS READY",
            Theme.DIM, 9f,
        )
        stemStatus = statusView
        side.addView(statusView, LinearLayout.LayoutParams(MATCH, 0, 1f))
        panel.addView(side, LinearLayout.LayoutParams(dp(112), MATCH))
        return panel
    }

    private fun startSeparation() {
        if (stemBusy) return
        val source = deckUris[0] ?: deckUris[1]
        if (source == null) {
            stemStatus?.text = "LOAD A TRACK FIRST"
            return
        }
        stemBusy = true
        stemStatus?.text = "SEPARATING…"
        Thread {
            val result = StemSeparator.separate(this, source) { progress ->
                handler.post { stemStatus?.text = "SEPARATING $progress%" }
            }
            handler.post {
                stemBusy = false
                stemResult = result
                stemStatus?.text = if (result == null) "SEPARATION FAILED" else "STEMS READY"
            }
        }.start()
    }

    private fun playStem(file: File) {
        val ui = deckUis[stemTarget]
        ui.name.text = "LOADING STEM"
        Thread {
            val decoded = AudioDecoder.decode(this, Uri.fromFile(file))
            handler.post {
                if (decoded == null) {
                    ui.name.text = "STEM FAILED"
                    return@post
                }
                engine.load(stemTarget, decoded)
                ui.loaded = true
                ui.name.text = file.name.removeSuffix(".wav").replace('_', ' ').uppercase()
                ui.wave.setPeaks(decoded.peaks)
                ui.bpm.text = String.format("%.1f BPM", decoded.bpm)
                if (!engine.isRunning) engine.start()
                engine.decks[stemTarget].playing = true
                ui.playing = true
                ui.playButton.text = "PAUSE"
            }
        }.start()
    }

    private fun buildLibraryOverlay(): FrameLayout {
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#E812151A")) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG1)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text("TRACK LIBRARY", Theme.ACCENT, 14f), LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(btn("CLOSE", Theme.TEXT) { libraryOverlay.visibility = View.GONE },
            LinearLayout.LayoutParams(WRAP, dp(30)))
        card.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        val scroller = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroller.addView(list, ScrollView.LayoutParams(MATCH, WRAP))
        card.addView(scroller, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val items = TrackLibrary.load(this)
        if (items.isEmpty()) {
            list.addView(text("NO AUDIO FOUND ON DEVICE", Theme.DIM, 12f, Gravity.CENTER))
        }
        for (item in items) list.addView(libraryRow(item))

        card.id = View.generateViewId()
        overlay.addView(card, FrameLayout.LayoutParams(libraryWidth(), MATCH, Gravity.CENTER))
        return overlay
    }

    private fun libraryWidth(): Int =
        minOf(dp(760), (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(280)))

    private fun libraryRow(item: LibraryTrack): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.BG2)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val label = TextView(this).apply {
            text = "${item.title}  —  ${item.artist}"
            setTextColor(Theme.TEXT)
            textSize = 11f
            setSingleLine(true)
        }
        row.addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(btn("A", Theme.DECK_A) { loadFromUri(item.uri, 0) },
            LinearLayout.LayoutParams(dp(40), dp(30)))
        row.addView(btn("B", Theme.DECK_B) { loadFromUri(item.uri, 1) },
            LinearLayout.LayoutParams(dp(40), dp(30)).apply { setMargins(dp(4), 0, 0, 0) })
        return row
    }

    private fun openLibrary() {
        libraryOverlay.removeAllViews()
        val card = buildLibraryOverlay().getChildAt(0)
        libraryOverlay.addView(card, FrameLayout.LayoutParams(libraryWidth(), MATCH, Gravity.CENTER))
        libraryOverlay.visibility = View.VISIBLE
    }

    private fun loadFromUri(uri: Uri, deckIndex: Int) {
        libraryOverlay.visibility = View.GONE
        deckUris[deckIndex] = uri
        stemResult = null
        val ui = deckUis[deckIndex]
        ui.name.text = "LOADING…"
        Thread {
            val decoded = AudioDecoder.decode(this, uri)
            handler.post {
                if (decoded == null) {
                    ui.name.text = "UNSUPPORTED FILE"
                } else {
                    engine.load(deckIndex, decoded)
                    val seconds = decoded.durationMs / 1000L
                    ui.name.text = formatTrack(uri, seconds)
                    ui.bpm.text = String.format("%.1f BPM", decoded.bpm)
                    ui.wave.setPeaks(decoded.peaks)
                    ui.loaded = true
                }
            }
        }.start()
    }

    private fun formatTrack(uri: Uri, seconds: Long): String {
        val last = uri.lastPathSegment ?: "TRACK"
        return "${last.take(28)}  ${seconds / 60}:${String.format("%02d", seconds % 60)}"
    }

    private fun setLoop(deckIndex: Int, beats: Float) {
        val deck = engine.decks[deckIndex]
        val track = deck.track ?: return
        val bpm = track.bpm.coerceAtLeast(40f)
        val seconds = beats * 60f / bpm
        val length = track.durationMs / 1000f
        if (length <= 0f) return
        deck.setLoop(deck.positionFraction, (deck.positionFraction + seconds / length).coerceAtMost(1f))
    }

    private fun nudge(deckIndex: Int, delta: Float) {
        val deck = engine.decks[deckIndex]
        deck.tempo = (deck.tempo + delta).coerceIn(0.5, 1.5)
    }

    private fun maybeRequestPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), 1)
        }
    }

    private fun dp(value: Int): Int = Theme.dp(this, value)

    private fun text(value: String, color: Int, size: Float, gravity: Int = Gravity.START): TextView =
        TextView(this).apply {
            text = value
            setTextColor(color)
            textSize = size
            this.gravity = gravity
            isAllCaps = true
        }

    private fun btn(value: String, color: Int, onClick: (Button) -> Unit): Button =
        Theme.button(this, value, color, onClick = onClick)

    private fun btn(value: String, color: Int, background: Int, onClick: (Button) -> Unit): Button =
        Theme.button(this, value, color, background, onClick = onClick)

    private inner class DeckUi(val index: Int) {
        val color = if (index == 0) Theme.DECK_A else Theme.DECK_B
        val root = LinearLayout(this@DjActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.rounded(Theme.BG1, dp(10).toFloat())
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val wave = WaveformView(this@DjActivity, color)
        lateinit var meter: LevelView
        lateinit var name: TextView
        lateinit var bpm: TextView
        lateinit var playButton: Button
        lateinit var keyButton: Button
        lateinit var jog: JogView
        lateinit var tempoLabel: TextView
        var loaded = false
        var playing = false
        var keylock = false
        var rate = 1.0

        init {
            val header = LinearLayout(this@DjActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Theme.BG2)
                setPadding(dp(5), 0, dp(7), 0)
            }
            name = text("NO TRACK", Theme.TEXT, 11f)
            name.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            name.ellipsize = android.text.TextUtils.TruncateAt.END
            bpm = text("-- BPM", color, 10f)
            header.addView(text(if (index == 0) "A" else "B", Theme.BG0, 10f, Gravity.CENTER).apply {
                typeface = Typeface.DEFAULT_BOLD
                background = Theme.rounded(color, dp(6).toFloat())
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            header.addView(name, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                setMargins(dp(7), 0, dp(4), 0)
            })
            header.addView(btn("LOAD", Theme.TEXT, Theme.BG3) { openPicker() },
                LinearLayout.LayoutParams(WRAP, dp(28)))
            header.addView(bpm, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                setMargins(dp(7), 0, 0, 0)
            })
            root.addView(header, LinearLayout.LayoutParams(MATCH, dp(34)))

            jog = JogView(this@DjActivity, color, if (index == 0) "A" else "B")
            jog.onScratchRate = { scratch ->
                if (scratch == 0f) {
                    engine.decks[index].tempo = rate
                } else {
                    engine.decks[index].tempo =
                        (0.5f + scratch * 2f).toDouble().coerceIn(0.05, 1.5)
                }
            }
            root.addView(jog, LinearLayout.LayoutParams(MATCH, 0, 1f))

            val transport = LinearLayout(this@DjActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val cue = btn("CUE", Theme.TEXT) { engine.decks[index].cue() }
            playButton = btn("PLAY", color) { togglePlay() }
            val sync = btn("SYNC", Theme.TEXT) {
                engine.sync(index, 1 - index)
                rate = engine.decks[index].tempo
            }
            keyButton = btn("KEY", Theme.TEXT) { toggleKey() }
            transport.addView(cue, LinearLayout.LayoutParams(0, MATCH, 1f))
            transport.addView(playButton, LinearLayout.LayoutParams(0, MATCH, 1f))
            transport.addView(sync, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
            transport.addView(keyButton, LinearLayout.LayoutParams(0, MATCH, 1f))
            root.addView(transport, LinearLayout.LayoutParams(MATCH, dp(40)))

            val pitch = LinearLayout(this@DjActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Theme.BG1)
                setPadding(dp(6), dp(3), dp(6), dp(3))
            }
            val fader = VerticalFader(this@DjActivity, color)
            fader.setValue(0.5f)
            fader.onValue = { value ->
                rate = 0.5 + value
                engine.decks[index].tempo = rate
            }
            pitch.addView(fader, LinearLayout.LayoutParams(dp(26), dp(62)))
            pitch.addView(text("TEMPO", Theme.DIM, 10f),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(8), 0, dp(6), 0) })
            tempoLabel = text(rate.format(), Theme.TEXT, 11f).apply {
                typeface = Typeface.MONOSPACE
            }
            pitch.addView(tempoLabel)
            root.addView(pitch, LinearLayout.LayoutParams(MATCH, dp(68)))
        }

        fun tick() {
            val deck = engine.decks[index]
            wave.setProgress(deck.positionFraction)
            wave.setActive(playing)
            jog.setSpinning(playing)
            jog.advance(250f * deck.tempo.toFloat())
            tempoLabel.text = rate.format()
            if (::meter.isInitialized) meter.setLevel(deck.level)
            playButton.setTextColor(if (playing) color else Theme.TEXT)
            playButton.setBackground(Theme.rounded(if (playing) color else Theme.BG2))
            keyButton.setTextColor(if (keylock) color else Theme.TEXT)
        }

        fun openPicker() {
            pendingDeck = index
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "audio/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(intent, "Load track"), REQUEST_LOAD)
        }

        fun togglePlay() {
            if (!loaded) {
                name.text = "LOAD A TRACK FIRST"
                return
            }
            if (!engine.isRunning) engine.start()
            playing = !playing
            engine.decks[index].playing = playing
            playButton.text = if (playing) "PAUSE" else "PLAY"
        }

        fun toggleKey() {
            keylock = !keylock
            engine.decks[index].keylock = keylock
            keyButton.text = if (keylock) "KEY ON" else "KEY"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_LOAD || resultCode != RESULT_OK) return
        data?.data?.let { loadFromUri(it, pendingDeck) }
    }

    private fun Double.format(): String = String.format("%.2fx", this)

    companion object {
        private const val REQUEST_LOAD = 42
        private const val OUTPUT_RATE = 48_000
        private const val MODE_EQ = "EQ"
        private const val MODE_LOOP = "LOOP"
        private const val MODE_FX = "FX"
        private const val MODE_SAMPLE = "SMP"
        private const val MODE_CUE = "CUE"
        private const val MODE_STEM = "STEM"
        private val BANDS = listOf("VOL", "HI", "MID", "LOW")
        private val LOOP_BEATS = listOf(
            "1/4" to 0.25f, "1/2" to 0.5f, "1" to 1f,
            "2" to 2f, "4" to 4f, "8" to 8f,
        )
    }
}
