package com.mixsphere.dj

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import com.mixsphere.dj.engine.MixerEngine
import com.mixsphere.dj.engine.StemResult
import com.mixsphere.dj.engine.StemSeparator
import com.mixsphere.dj.ui.CrossfaderView
import com.mixsphere.dj.ui.JogView
import com.mixsphere.dj.ui.LevelView
import com.mixsphere.dj.ui.Theme
import com.mixsphere.dj.ui.Theme.MATCH
import com.mixsphere.dj.ui.Theme.WRAP
import com.mixsphere.dj.ui.VerticalFader
import com.mixsphere.dj.ui.WaveformView
import com.mixsphere.dj.ui.XyPadView
import java.io.File
import java.lang.ref.WeakReference
import kotlin.math.abs

class DjActivity : Activity() {

    private lateinit var engine: MixerEngine
    private var sampleBank: SampleBank? = null
    private val deckUis = ArrayList<DeckUi>(2)
    private val bigWaves = arrayOfNulls<WaveformView>(2)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: FrameLayout
    private lateinit var libraryOverlay: FrameLayout
    private lateinit var status: TextView
    private lateinit var crossfaderView: CrossfaderView
    private lateinit var masterMeter: LevelView
    private val navButtons = HashMap<String, Button>()
    private val pages = HashMap<String, View>()
    private var currentPage = PAGE_HOME
    private var pendingDeck = 0
    private var sampleGroup = 0
    private var fxDeck = 0
    private var fxType = 0
    private var stemSource = 0
    private var stemTarget = 1
    private var stemResult: StemResult? = null
    private var stemBusy = false
    private var stemGeneration = 0
    private var stemWorker: Thread? = null
    private var stemStatus: TextView? = null
    private val deckUris = arrayOf<Uri?>(null, null)
    private val deckLoadGeneration = IntArray(2)

    private val refresh = object : Runnable {
        override fun run() {
            if (::engine.isInitialized) {
                for (ui in deckUis) ui.tick()
                for (index in 0..1) {
                    bigWaves[index]?.setProgress(engine.decks[index].positionFraction)
                }
                masterMeter.setLevel(engine.masterLevel)
                status.text = String.format(
                    "MIXSPHERE DJ  %d Hz  %s", OUTPUT_RATE,
                    if (engine.isRunning) "LIVE" else "STANDBY",
                )
            }
            handler.postDelayed(this, 60L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = MixerEngine(OUTPUT_RATE)
        sampleBank = SampleBank(this)
        setContentView(buildRoot())
        showPage(PAGE_HOME)
        maybeRequestPermission()
        handler.post(refresh)
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.let { loadFromUri(it, 0) }
    }

    override fun onDestroy() {
        stemGeneration++
        stemWorker?.interrupt()
        for (index in deckLoadGeneration.indices) deckLoadGeneration[index]++
        handler.removeCallbacks(refresh)
        engine.close()
        sampleBank?.release()
        sampleBank = null
        super.onDestroy()
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG0)
        }
        root.addView(buildTopBar(), LinearLayout.LayoutParams(MATCH, dp(44)))
        root.addView(buildNavBar(), LinearLayout.LayoutParams(MATCH, dp(40)))
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(buildCrossfaderBar(), LinearLayout.LayoutParams(MATCH, dp(50)))
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
            setPadding(dp(10), 0, dp(10), 0)
        }
        val brand = text("MIXSPHERE", Theme.ACCENT, 14f).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        bar.addView(brand)
        status = text("ENGINE STANDBY", Theme.DIM, 10f).apply {
            typeface = Typeface.MONOSPACE
            setSingleLine(true)
        }
        bar.addView(status, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            setMargins(dp(10), 0, dp(8), 0)
        })
        bar.addView(btn("LIBRARY", Theme.TEXT, Theme.BG2) { openLibrary() },
            LinearLayout.LayoutParams(WRAP, dp(30)))
        return bar
    }

    private fun buildNavBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.BG0)
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }
        val entries = listOf(
            PAGE_HOME to "HOME", PAGE_EQ to "EQ", PAGE_LOOP to "LOOP",
            PAGE_CUE to "CUE", PAGE_FX to "FX", PAGE_STEM to "STEM",
            PAGE_SAMPLE to "SAMPLE", PAGE_WAVE to "WAVE",
        )
        for (entry in entries) {
            val button = btn(entry.second, Theme.DIM, Theme.BG1) { showPage(entry.first) }
            navButtons[entry.first] = button
            bar.addView(button, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        return bar
    }

    private fun showPage(page: String) {
        currentPage = page
        for ((key, button) in navButtons) {
            button.setTextColor(if (key == page) Theme.ACCENT else Theme.DIM)
            button.setBackground(Theme.rounded(if (key == page) Theme.BG2 else Theme.BG1, dp(5).toFloat()))
        }
        content.removeAllViews()
        val view = pages.getOrPut(page) {
            when (page) {
                PAGE_EQ -> buildEqPage()
                PAGE_LOOP -> buildLoopPage()
                PAGE_CUE -> buildCuePage()
                PAGE_FX -> buildFxPage()
                PAGE_STEM -> buildStemPage()
                PAGE_SAMPLE -> buildSamplePage()
                PAGE_WAVE -> buildWavePage()
                else -> buildHomePage()
            }
        }
        content.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun buildHomePage(): View {
        val stage = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val deckA = DeckUi(0).also { deckUis.add(0, it) }
        val deckB = DeckUi(1).also { deckUis.add(1, it) }
        stage.addView(deckA.root, LinearLayout.LayoutParams(0, MATCH, 1f))
        stage.addView(buildHomeCentre(), LinearLayout.LayoutParams(dp(88), MATCH))
        stage.addView(deckB.root, LinearLayout.LayoutParams(0, MATCH, 1f))
        return stage
    }

    private fun buildHomeCentre(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Theme.BG1)
            setPadding(dp(3), dp(6), dp(3), dp(6))
        }
        masterMeter = LevelView(this).apply { color = Theme.GREEN }
        column.addView(text("MASTER", Theme.DIM, 9f, Gravity.CENTER))
        column.addView(masterMeter, LinearLayout.LayoutParams(dp(14), 0, 1f).apply {
            setMargins(0, dp(6), 0, dp(6))
        })
        column.addView(btn("REC", Theme.RED, Theme.BG2) { }.apply {
            setTextColor(if (false) Theme.RED else Theme.DIM)
        }, LinearLayout.LayoutParams(dp(52), dp(28)))
        column.addView(text("SYNC", Theme.DIM, 9f, Gravity.CENTER).apply {
            setPadding(0, dp(8), 0, 0)
        })
        column.addView(btn("MATCH", Theme.GREEN, Theme.BG2) { engine.sync(1, 0) },
            LinearLayout.LayoutParams(dp(58), dp(30)))
        return column
    }

    private fun buildEqPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG1)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val faders = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (deckIndex in 0..1) {
            val color = if (deckIndex == 0) Theme.DECK_A else Theme.DECK_B
            val group = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Theme.BG2)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            group.addView(text(if (deckIndex == 0) "A" else "B", color, 12f, Gravity.CENTER),
                LinearLayout.LayoutParams(dp(18), MATCH).apply {
                    gravity = Gravity.CENTER_VERTICAL
                })
            for (band in BANDS) {
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                val fader = VerticalFader(this, color)
                fader.setValue(if (band == "VOL") 0.72f else 0.5f)
                fader.onValue = { value ->
                    val deck = engine.decks[deckIndex]
                    when (band) {
                        "VOL" -> deck.gain = value * 1.4f
                        "HI" -> deck.eqHigh = value * 2f
                        "MID" -> deck.eqMid = value * 2f
                        "LOW" -> deck.eqLow = value * 2f
                        "FLT" -> deck.filter = value
                    }
                }
                cell.addView(fader, LinearLayout.LayoutParams(dp(26), 0, 1f))
                cell.addView(text(band, Theme.DIM, 9f, Gravity.CENTER),
                    LinearLayout.LayoutParams(WRAP, WRAP))
                group.addView(cell, LinearLayout.LayoutParams(0, MATCH, 1f))
            }
            faders.addView(group, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
        root.addView(faders, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    private fun buildLoopPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG1)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        for (deckIndex in 0..1) {
            val color = if (deckIndex == 0) Theme.DECK_A else Theme.DECK_B
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(text(if (deckIndex == 0) "A" else "B", color, 12f),
                LinearLayout.LayoutParams(dp(22), MATCH).apply {
                    gravity = Gravity.CENTER_VERTICAL
                })
            for (entry in LOOP_BEATS) {
                row.addView(btn(entry.first, Theme.TEXT, Theme.BG2) {
                    setLoop(deckIndex, entry.second)
                }, LinearLayout.LayoutParams(0, MATCH, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
            row.addView(btn("EXIT", Theme.RED, Theme.BG2) {
                engine.decks[deckIndex].clearLoop()
            }, LinearLayout.LayoutParams(dp(56), MATCH))
            root.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
                setMargins(0, dp(3), 0, dp(3))
            })
        }
        return root
    }

    private fun buildCuePage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.BG1)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        for (deckIndex in 0..1) {
            val color = if (deckIndex == 0) Theme.DECK_A else Theme.DECK_B
            val group = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Theme.BG2)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            group.addView(btn(if (deckIndex == 0) "DECK A CUES" else "DECK B CUES", color, Theme.BG2) {
                engine.decks[deckIndex].setCueHere()
            }, LinearLayout.LayoutParams(MATCH, dp(30)))
            val grid = GridLayout(this).apply { rowCount = 2; columnCount = 4 }
            for (i in 0 until 8) {
                val pad = btn((i + 1).toString(), color, Theme.BG1) {
                    engine.decks[deckIndex].hotCue(i)
                }
                grid.addView(
                    pad,
                    GridLayout.LayoutParams(
                        GridLayout.spec(i / 4, 1f), GridLayout.spec(i % 4, 1f),
                    ).apply {
                        width = 0; height = MATCH; setMargins(dp(2), dp(2), dp(2), dp(2))
                    },
                )
            }
            group.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))
            root.addView(group, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
        return root
    }

    private fun buildFxPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.BG1)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val pad = XyPadView(this)
        pad.onValue = { x, y -> engine.decks[fxDeck].setFx(fxType, x, 1f - y) }
        root.addView(pad, LinearLayout.LayoutParams(0, MATCH, 1f))
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        side.addView(btn("FX ${if (fxDeck == 0) "A" else "B"}", Theme.ACCENT, Theme.BG2) { button ->
            fxDeck = 1 - fxDeck
            button.text = "FX ${if (fxDeck == 0) "A" else "B"}"
            engine.decks[fxDeck].setFx(fxType, 0.5f, 0.5f)
        }, LinearLayout.LayoutParams(MATCH, dp(30)))
        for (entry in FX_NAMES) {
            side.addView(btn(entry.second, if (entry.first == fxType) Theme.ACCENT else Theme.DIM, Theme.BG2) {
                fxType = entry.first
                engine.decks[fxDeck].setFx(fxType, 0.5f, 0.5f)
                pages.remove(PAGE_FX)
                showPage(PAGE_FX)
            }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { setMargins(0, dp(2), 0, dp(2)) })
        }
        root.addView(side, LinearLayout.LayoutParams(dp(110), MATCH).apply {
            setMargins(dp(8), 0, 0, 0)
        })
        return root
    }

    private fun buildStemPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG1)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(btn("SRC ${if (stemSource == 0) "A" else "B"}", Theme.TEXT, Theme.BG2) { button ->
            stemSource = 1 - stemSource
            stemResult = null
            stemGeneration++
            stemWorker?.interrupt()
            button.text = "SRC ${if (stemSource == 0) "A" else "B"}"
            stemStatus?.text = "LOCAL DSP • SELECT A PAD"
        }, LinearLayout.LayoutParams(dp(80), dp(30)))
        header.addView(btn("TO ${if (stemTarget == 0) "A" else "B"}", Theme.ACCENT, Theme.BG2) { button ->
            stemTarget = 1 - stemTarget
            button.text = "TO ${if (stemTarget == 0) "A" else "B"}"
        }, LinearLayout.LayoutParams(dp(70), dp(30)).apply { setMargins(dp(4), 0, 0, 0) })
        val statusView = text("LOCAL DSP • SELECT A PAD", Theme.DIM, 9f)
        stemStatus = statusView
        header.addView(statusView, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
            setMargins(dp(6), 0, 0, 0); gravity = Gravity.CENTER_VERTICAL
        })
        root.addView(header, LinearLayout.LayoutParams(MATCH, dp(32)))

        val grid = GridLayout(this).apply { rowCount = 2; columnCount = 3 }
        for ((index, entry) in STEM_LABELS.withIndex()) {
            val pad = btn(entry.second, if (index % 2 == 0) Theme.DECK_A else Theme.DECK_B, Theme.BG2) {
                val result = stemResult
                if (result == null || stemBusy) startSeparation()
                else result.files[entry.first]?.let { playStem(it) }
            }
            grid.addView(
                pad,
                GridLayout.LayoutParams(
                    GridLayout.spec(index / 3, 1f), GridLayout.spec(index % 3, 1f),
                ).apply {
                    width = 0; height = MATCH; setMargins(dp(3), dp(3), dp(3), dp(3))
                },
            )
        }
        root.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
            setMargins(0, dp(6), 0, 0)
        })
        return root
    }

    private fun buildSamplePage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG1)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (entry in SAMPLE_GROUPS.withIndex()) {
            tabs.addView(btn(entry.value, if (entry.index == sampleGroup) Theme.ACCENT else Theme.DIM, Theme.BG2) {
                sampleGroup = entry.index
                pages.remove(PAGE_SAMPLE)
                showPage(PAGE_SAMPLE)
            }, LinearLayout.LayoutParams(0, dp(30), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        root.addView(tabs, LinearLayout.LayoutParams(MATCH, WRAP))
        val indices = when (sampleGroup) {
            1 -> (0..7).toList()
            2 -> (8..11).toList()
            3 -> (12..15).toList()
            else -> (0..15).toList()
        }
        val columns = if (indices.size <= 8) 4 else 4
        val rows = (indices.size + columns - 1) / columns
        val grid = GridLayout(this).apply { rowCount = rows; columnCount = columns }
        for ((cell, index) in indices.withIndex()) {
            val spec = SampleBank.SPECS[index]
            val color = when (index / 4) {
                0 -> Theme.DECK_A
                1 -> Theme.DECK_B
                2 -> Theme.ACCENT
                else -> Theme.GREEN
            }
            val pad = btn(spec.label, color, Theme.BG2) { sampleBank?.play(index) }
            grid.addView(
                pad,
                GridLayout.LayoutParams(
                    GridLayout.spec(cell / columns, 1f), GridLayout.spec(cell % columns, 1f),
                ).apply {
                    width = 0; height = MATCH; setMargins(dp(3), dp(3), dp(3), dp(3))
                },
            )
        }
        root.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
            setMargins(0, dp(6), 0, 0)
        })
        return root
    }

    private fun buildWavePage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG0)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val waves = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (deckIndex in 0..1) {
            val color = if (deckIndex == 0) Theme.DECK_A else Theme.DECK_B
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            column.addView(text("DECK ${if (deckIndex == 0) "A" else "B"} WAVEFORM", color, 10f, Gravity.CENTER))
            val wave = WaveformView(this, color)
            wave.onSeek = { fraction -> engine.decks[deckIndex].seek(fraction) }
            bigWaves[deckIndex] = wave
            column.addView(wave, LinearLayout.LayoutParams(MATCH, 0, 1f))
            val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            controls.addView(btn("−", Theme.TEXT, Theme.BG2) { shiftTempo(deckIndex, -0.01) },
                LinearLayout.LayoutParams(dp(44), dp(32)))
            controls.addView(btn("+", Theme.TEXT, Theme.BG2) { shiftTempo(deckIndex, 0.01) },
                LinearLayout.LayoutParams(dp(44), dp(32)).apply { setMargins(dp(2), 0, 0, 0) })
            controls.addView(btn("MATCH", Theme.GREEN, Theme.BG2) {
                engine.sync(deckIndex, 1 - deckIndex)
            }, LinearLayout.LayoutParams(0, dp(32), 1f).apply { setMargins(dp(2), 0, 0, 0) })
            column.addView(controls, LinearLayout.LayoutParams(MATCH, dp(34)))
            waves.addView(column, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        root.addView(waves, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val zoomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        zoomRow.addView(text("ZOOM", Theme.DIM, 10f), LinearLayout.LayoutParams(WRAP, MATCH).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        zoomRow.addView(btn("−", Theme.TEXT, Theme.BG2) { zoomWaves(-1) },
            LinearLayout.LayoutParams(dp(46), dp(30)).apply { setMargins(dp(6), 0, 0, 0) })
        zoomRow.addView(btn("+", Theme.TEXT, Theme.BG2) { zoomWaves(1) },
            LinearLayout.LayoutParams(dp(46), dp(30)).apply { setMargins(dp(4), 0, 0, 0) })
        zoomRow.addView(btn("BACK TO DECKS", Theme.ACCENT, Theme.BG2) { showPage(PAGE_HOME) },
            LinearLayout.LayoutParams(0, dp(30), 1f).apply { setMargins(dp(8), 0, 0, 0) })
        root.addView(zoomRow, LinearLayout.LayoutParams(MATCH, dp(34)).apply {
            setMargins(0, dp(6), 0, 0)
        })
        return root
    }

    private fun zoomWaves(delta: Int) {
        val next = (bigWaves[0]?.zoomLevel() ?: 1) + delta
        bigWaves[0]?.setZoom(next)
        bigWaves[1]?.setZoom(next)
    }

    private fun shiftTempo(index: Int, delta: Double) {
        val deck = engine.decks[index]
        deck.tempo = (deck.tempo + delta).coerceIn(0.5, 1.5)
        deckUis.getOrNull(index)?.rate = deck.tempo
    }

    private fun buildCrossfaderBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.BG1)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        bar.addView(btn("AUTO A", Theme.DECK_A, Theme.BG2) { crossfaderView.setValue(-1f) },
            LinearLayout.LayoutParams(WRAP, dp(30)))
        crossfaderView = CrossfaderView(this)
        crossfaderView.onValue = { engine.setCrossfader(it) }
        bar.addView(crossfaderView, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
            setMargins(dp(8), 0, dp(8), 0)
        })
        bar.addView(btn("AUTO B", Theme.DECK_B, Theme.BG2) { crossfaderView.setValue(1f) },
            LinearLayout.LayoutParams(WRAP, dp(30)))
        return bar
    }

    private fun buildLibraryOverlay(): FrameLayout {
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#E812151A")) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG1)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(text("TRACK LIBRARY", Theme.ACCENT, 13f), LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(btn("CLOSE", Theme.TEXT, Theme.BG2) { libraryOverlay.visibility = View.GONE },
            LinearLayout.LayoutParams(WRAP, dp(30)))
        card.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        val scroller = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroller.addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        card.addView(scroller, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val items = TrackLibrary.load(this)
        if (items.isEmpty()) {
            list.addView(text("NO AUDIO FOUND — GRANT STORAGE ACCESS", Theme.DIM, 11f, Gravity.CENTER))
        }
        for (item in items) list.addView(libraryRow(item))
        overlay.addView(card, FrameLayout.LayoutParams(dp(720), MATCH, Gravity.CENTER))
        return overlay
    }

    private fun libraryRow(item: LibraryTrack): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.BG2)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val label = text("${item.title} — ${item.artist}", Theme.TEXT, 11f)
        label.setSingleLine(true)
        row.addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(btn("A", Theme.DECK_A, Theme.BG1) { loadFromUri(item.uri, 0) },
            LinearLayout.LayoutParams(dp(40), dp(30)))
        row.addView(btn("B", Theme.DECK_B, Theme.BG1) { loadFromUri(item.uri, 1) },
            LinearLayout.LayoutParams(dp(40), dp(30)).apply { setMargins(dp(4), 0, 0, 0) })
        return row
    }

    private fun openLibrary() {
        libraryOverlay.removeAllViews()
        val card = buildLibraryOverlay().getChildAt(0)
        libraryOverlay.addView(card, FrameLayout.LayoutParams(dp(720), MATCH, Gravity.CENTER))
        libraryOverlay.visibility = View.VISIBLE
    }

    private fun loadFromUri(uri: Uri, deckIndex: Int) {
        libraryOverlay.visibility = View.GONE
        val generation = ++deckLoadGeneration[deckIndex]
        deckUris[deckIndex] = uri
        stemResult = null
        stemGeneration++
        stemWorker?.interrupt()
        deckUis.getOrNull(deckIndex)?.name?.text = "LOADING…"
        val context = applicationContext
        val callbackHandler = handler
        val activityReference = WeakReference(this)
        Thread({
            val decoded = AudioDecoder.decode(context, uri)
            callbackHandler.post {
                val activity = activityReference.get() ?: return@post
                if (activity.isDestroyed || generation != activity.deckLoadGeneration[deckIndex]) return@post
                val ui = activity.deckUis[deckIndex]
                if (decoded == null) {
                    ui.name.text = "LOAD FAILED — TRY ANOTHER FILE"
                } else {
                    activity.engine.load(deckIndex, decoded)
                    ui.name.text = activity.formatTrack(uri, decoded.durationMs / 1000L)
                    ui.bpm.text = String.format("%.1f BPM", decoded.bpm)
                    ui.wave.setPeaks(decoded.peaks)
                    ui.loaded = true
                    activity.bigWaves[deckIndex]?.setPeaks(decoded.peaks)
                }
            }
        }, "mixsphere-load-$deckIndex").start()
    }

    private fun formatTrack(uri: Uri, seconds: Long): String {
        val name = uri.lastPathSegment ?: "TRACK"
        return "${name.take(24)} ${seconds / 60}:${String.format("%02d", seconds % 60)}"
    }

    private fun setLoop(deckIndex: Int, beats: Float) {
        val deck = engine.decks[deckIndex]
        val track = deck.track ?: return
        val seconds = beats * 60f / track.bpm.coerceAtLeast(40f)
        val length = track.durationMs / 1000f
        if (length <= 0f) return
        deck.setLoop(deck.positionFraction, (deck.positionFraction + seconds / length).coerceAtMost(1f))
    }

    private fun startSeparation() {
        if (stemBusy) return
        val sourceTrack = engine.decks[stemSource].track
        if (sourceTrack == null) {
            stemStatus?.text = "LOAD A TRACK FIRST"
            return
        }
        val generation = ++stemGeneration
        val cacheKey = deckUris[stemSource]?.toString()
            ?: "deck-$stemSource-${sourceTrack.sampleRate}-${sourceTrack.frames}"
        stemBusy = true
        stemStatus?.text = "LOCAL DSP • STARTING"
        stemWorker = Thread({
            val result = try {
                StemSeparator.separate(
                    this, sourceTrack, cacheKey,
                    onProgress = { progress ->
                        handler.post {
                            if (generation == stemGeneration && !isDestroyed) {
                                stemStatus?.text = "DSP SEPARATION $progress%"
                            }
                        }
                    },
                    shouldCancel = { generation != stemGeneration },
                )
            } catch (error: Exception) {
                null
            }
            handler.post {
                if (generation != stemGeneration || isDestroyed) return@post
                stemBusy = false
                stemResult = result
                stemStatus?.text = if (result == null) "SEPARATION FAILED" else "STEMS READY • TAP A STEM"
            }
        }, "mixsphere-stems").also { it.start() }
    }

    private fun playStem(file: File) {
        val ui = deckUis[stemTarget]
        ui.name.text = "LOADING STEM"
        Thread({
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
                bigWaves[stemTarget]?.setPeaks(decoded.peaks)
                if (!engine.isRunning) engine.start()
                engine.decks[stemTarget].playing = true
                ui.playing = true
                ui.playButton.text = "PAUSE"
            }
        }, "mixsphere-stem-play").start()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_LOAD && resultCode == RESULT_OK) {
            data?.data?.let { loadFromUri(it, pendingDeck) }
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

    private fun btn(value: String, color: Int, background: Int, onClick: (Button) -> Unit): Button =
        Theme.button(this, value, color, background, onClick = onClick)

    private inner class DeckUi(val index: Int) {
        val color = if (index == 0) Theme.DECK_A else Theme.DECK_B
        val root = LinearLayout(this@DjActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.rounded(Theme.BG1, dp(10).toFloat())
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        val wave = WaveformView(this@DjActivity, color)
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
                setPadding(dp(4), 0, dp(4), 0)
            }
            val loadButton = btn("LOAD ${if (index == 0) "A" else "B"}", color, Theme.BG1) {
                openPicker()
            }
            name = text("NO TRACK", Theme.TEXT, 10f)
            name.setSingleLine(true)
            bpm = text("-- BPM", color, 9f)
            if (index == 0) {
                header.addView(loadButton, LinearLayout.LayoutParams(WRAP, dp(28)))
                header.addView(name, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    setMargins(dp(4), 0, dp(3), 0)
                })
                header.addView(bpm)
            } else {
                header.addView(bpm)
                header.addView(name, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    setMargins(dp(3), 0, dp(4), 0)
                })
                header.addView(loadButton, LinearLayout.LayoutParams(WRAP, dp(28)))
            }
            root.addView(header, LinearLayout.LayoutParams(MATCH, dp(32)))
            root.addView(wave, LinearLayout.LayoutParams(MATCH, dp(14)))

            val jogZone = LinearLayout(this@DjActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val pitch = LinearLayout(this@DjActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val fader = VerticalFader(this@DjActivity, color)
            fader.setValue(0.5f)
            fader.onValue = { value ->
                rate = 0.5 + value
                engine.decks[index].tempo = rate
            }
            pitch.addView(fader, LinearLayout.LayoutParams(dp(24), 0, 1f))
            pitch.addView(text("KEY", Theme.DIM, 8f, Gravity.CENTER), LinearLayout.LayoutParams(WRAP, WRAP))
            tempoLabel = text(rate.format(), Theme.TEXT, 9f, Gravity.CENTER)
            pitch.addView(tempoLabel, LinearLayout.LayoutParams(WRAP, WRAP))
            jog = JogView(this@DjActivity, color, if (index == 0) "A" else "B")
            jog.onScratch = { delta -> engine.decks[index].scratch(delta) }
            if (index == 0) {
                jogZone.addView(pitch, LinearLayout.LayoutParams(dp(32), MATCH))
                jogZone.addView(jog, LinearLayout.LayoutParams(0, MATCH, 1f))
            } else {
                jogZone.addView(jog, LinearLayout.LayoutParams(0, MATCH, 1f))
                jogZone.addView(pitch, LinearLayout.LayoutParams(dp(32), MATCH))
            }
            root.addView(jogZone, LinearLayout.LayoutParams(MATCH, 0, 1f))

            val transport = LinearLayout(this@DjActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
            }
            val cue = btn("CUE", Theme.TEXT, Theme.BG2) { engine.decks[index].cue() }
            playButton = btn("PLAY", color, Theme.BG2) { togglePlay() }
            val sync = btn("SYNC", Theme.TEXT, Theme.BG2) {
                engine.sync(index, 1 - index)
                rate = engine.decks[index].tempo
            }
            keyButton = btn("KEY", Theme.TEXT, Theme.BG2) { toggleKey() }
            transport.addView(cue, LinearLayout.LayoutParams(0, MATCH, 1f))
            transport.addView(playButton, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
            transport.addView(sync, LinearLayout.LayoutParams(0, MATCH, 1f))
            transport.addView(keyButton, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                setMargins(dp(2), 0, 0, 0)
            })
            root.addView(transport, LinearLayout.LayoutParams(MATCH, dp(42)))
        }

        fun tick() {
            val deck = engine.decks[index]
            wave.setProgress(deck.positionFraction)
            wave.setActive(playing)
            jog.setSpinning(playing)
            jog.setPosition(deck.positionFraction)
            tempoLabel.text = rate.format()
            playButton.setTextColor(if (playing) color else Theme.TEXT)
            keyButton.setTextColor(if (keylock) color else Theme.TEXT)
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

        fun openPicker() {
            pendingDeck = index
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "audio/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(intent, "Load track"), REQUEST_LOAD)
        }
    }

    private fun Double.format(): String = String.format("%.2fx", this)

    companion object {
        private const val REQUEST_LOAD = 42
        private const val OUTPUT_RATE = 48_000
        private const val PAGE_HOME = "HOME"
        private const val PAGE_EQ = "EQ"
        private const val PAGE_LOOP = "LOOP"
        private const val PAGE_CUE = "CUE"
        private const val PAGE_FX = "FX"
        private const val PAGE_STEM = "STEM"
        private const val PAGE_SAMPLE = "SAMPLE"
        private const val PAGE_WAVE = "WAVE"
        private val BANDS = listOf("VOL", "HI", "MID", "LOW", "FLT")
        private val LOOP_BEATS = listOf(
            "1/16" to 0.0625f, "1/8" to 0.125f, "1/4" to 0.25f, "1/2" to 0.5f,
            "1" to 1f, "2" to 2f, "4" to 4f,
        )
        private val FX_NAMES = listOf(0 to "OFF", 1 to "FILTER", 2 to "ECHO", 3 to "FLANGER")
        private val SAMPLE_GROUPS = listOf("ALL", "TRANSITIONS", "DROPS", "IMPACTS")
        private val STEM_LABELS = listOf(
            "vocals" to "VOCAL", "beat" to "BEAT", "harmony" to "HARM",
            "no_vocals" to "NO VOC", "no_beat" to "NO BEAT", "no_harmony" to "NO HARM",
        )
    }
}
