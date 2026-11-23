# Cross DJ (Mixvibes) UI Research — Input to Mixsphere UX

Owner: Software Engineer
Date: 2026-11-23
Status: **Research only. No UI implementation. Phase 0 UX work is gated until the gate is accepted (see PHASE0_HANDOFF.md).**

## Purpose

Mixsphere uses the **Mixxx engine** as its backend (`docs/MIXXX_ANALYSIS.md`, `android-spike/native`).
This note records **public, functional UI/UX ideas** observed in Mixvibes **Cross DJ for Android** so
they can inform a *new* Mixsphere interface later. It is design research, not a port, clone, or
reverse-engineering exercise. No Cross DJ code, assets, icons, layouts, strings, or branding were
copied or downloaded.

## Sources (public / official, read-only)

- Official product page: <https://www.mixvibes.com/cross-dj>
- Official Android beta page: <https://www.mixvibes.com/crossdj-androidbeta>
- Google Play listing (com.mixvibes.crossdjfree): <https://play.google.com/store/apps/details?id=com.mixvibes.crossdjfree>
- Digital DJ Tips hands-on review: <https://www.digitaldjtips.com/reviews/cross-dj-android/>
- DJ Mag review: <https://djmag.com/content/mixvibes-cross-dj-android-app>
- DJWORX 2026 overview: <https://djworx.com/cross-dj-review/>
- DJWORX 2015 (2.3) and 2014 (2.0) release reports: <https://djworx.com/add-colour-cross-dj-2-3-android/> , <https://djworx.com/mixvibes-cross-dj-2/>
- Mixvibes forum release posts (feature announcements): <https://forum.mixvibes.com/>

## Observed UI/UX patterns (functional ideas — safe to learn from)

1. **Industry-standard two-deck + center mixer.** Two decks with a central mixer and crossfader,
   cue/play/sync under each deck. This is a *conventional* DJ layout, not protectable expression.
2. **Waveform-first player.** Parallel waveforms for visual sync checking; pinch-to-zoom;
   waveform tap for seek; beatgrid editing on the waveform; detailed waveform expands during jog interaction.
3. **Mode tabs per deck ("separate tabs").** Swipe to show a different panel on each side — e.g. EQ on
   deck A while FX is on deck B. Active tab is tinted so the user sees at a glance what is engaged.
4. **Three mode buttons above the mixer:** Loop/cues (hot cues + slip), EQ/volume (3-band), FX (X/Y pads).
5. **Adaptive layout by screen size/orientation.** On small phones, secondary controls collapse to
   full-screen pad views; on larger screens EQ stays visible; portrait mode shows one large deck
   ("single player") for accurate touch control.
6. **Big touch targets, staged disclosure.** Main screen intentionally simple; advanced functions live
   one tap away in sub-screens (settings, advanced settings, gain, quantize, deck protection,
   master limiter, record quality, pitch-bend amount).
7. **Assistive toggles prominent.** Auto-sync, quantize, auto-gain, slip mode, key lock — surfaced as
   first-class controls rather than buried.
8. **Visual state feedback.** Colored active tabs, VU meters, circular beat/bar counter, deck color
   customization (7 colors per deck) as an at-a-glance state cue.
9. **Library ergonomics.** Sort by title/artist/album/BPM/key/length; folder navigation; BPM and key
   shown in the list; pre-analysis/beatgrid on import.
10. **Performance extras.** Hot cues (8–16), loops (1/32–8 beats), sampler banks, X/Y FX pads with
    locked/free modes, stem separation, automix, Ableton Link. (What is actually in the current
    Android generation varies — reviews note Android lags iOS; verify before treating as table stakes.)

## Legal / originality rules for adapting these ideas

- **Safe:** functional layout concepts, control groupings, interaction gestures, workflow order,
  feature categories, and general "two decks + mixer" conventions. These are ideas/functionality.
- **Do not copy:** Cross DJ's or Mixvibes' logos, name, icons, artwork, screenshots, exact color
  values/palette used as brand trade dress, custom fonts, sounds/samples, marketing copy, or any source code.
- Don't reuse proprietary sample/loop libraries or streaming integrations; Mixsphere should source its
  own or use openly licensed content with attribution.
- Give Mixvibes no attribution as a code source (we used none); if inspiration is acknowledged at all,
  phrase it as "general two-deck DJ convention", not "based on Cross DJ".
- Keep Mixsphere's own identity: its name, its GPL-3.0-or-later SPDX, Mixxx attribution headers, and the
  documented open-source dependency license list (`docs/MIXXX_ANALYSIS.md` §4).
- "Inspired by common DJ workflow" is the defensible framing; cloning the exact screen is not.

## Mapping to the Mixsphere backend that already exists

Current `MixsphereEngine.kt` / `jni_bridge.cpp` API (what a UI could bind to **today**):

| UI concept | Backend support now | Notes |
| --- | --- | --- |
| Two decks, load track | `loadTrack(deck, path)` | WAV PCM16/float32 only, 64 MiB cap (spike decoder) |
| Play / pause per deck | `play(deck, playing)` | — |
| Tempo / pitch slider | `setTempo(deck, tempo, keylock)` | tempo normalized by `TempoControl` |
| Keylock toggle | `setTempo(..., keylock)` / `renderedKeylock(deck)` | experimental granular path, not Mixxx RubberBand |
| Crossfader | `setCrossfader(position)` | real Mixxx `EngineXfader` DSP |
| Level / VU feedback | `renderedEnergy()` | proof-of-life telemetry, not a real per-deck VU meter |
| Audio health/debug | `audioApi()`, `latencyMs()`, `callbackMicros()`, `sampleRate()` | Phase 0 harness telemetry; not product UI |

**Not yet available — do not design bindings until the engine exposes them:** per-deck volume,
3-band EQ, hot cues, loops, slip, sync/beatgrid, waveform data, seek, cue points, FX, stems, sampler,
key detection, library/BPM analysis, recording. These belong to the full Mixxx engine extraction and
later phases; several are listed in `docs/PORT_PLAN.md` and the master plan.

## Practical implication

A first Mixsphere UI should be a **simple, conventional two-deck view** built only on the API above:
two waveforms/placeholders, cue/play/sync placeholders, one tempo slider per deck, a keylock toggle,
and a crossfader. The *structure* can echo the universal DJ conventions observed in Cross DJ and
Mixxx, but the visuals must be original Mixsphere design. The richer "separate tabs / X-Y FX / pads"
ideas are recorded above as future options, not commitments.

## Decompiled layout evidence (local snapshot)

The UI impression was checked against the locally decompiled Cross DJ 4.1.2 snapshot in `~/CROSSDJ-RESEARCH/decompiled/resources` (source APK: `~/CROSSDJ-RESEARCH/original/crossdj-4.1.2.apk`). The landscape main screen `res/layout/activity_main.xml` is a `MainCrossDJLayout` containing a `player_fragment_container` split into two equal player panes, a sync/beat-match strip, a master `Vumeter`, a bottom `crossFader` (range 0..1000, centre 500), EQ/LOOP/FX pane-switch buttons, and two waveform panes from `res/layout/double_waveform_layout.xml` with per-deck pitch views. Each player pane (`res/layout/player_area.xml`) is a three-band vertical stack: top load/collection bar, middle jog/scope area (~70% of the pane), and a bottom transport row with CUE / PLAY / SYNC. `res/layout/jog_zone_layout.xml` places a vertical pitch fader beside the jog area; `res/layout/mixer_view.xml` shows GAIN, VOL and HI/MID/LOW/FLT sliders with per-deck VU meters.

## Mixsphere additions beyond the observed layout

- **Bundled sample bank:** 16 CC0 DJ transitions (risers, fallers, reverse whooshes, impacts, sub drops, 808) from `wyzowl/sfx-kit`, converted to PCM16 WAV for the spike decoder and stored under `android-spike/app/src/main/assets/samples` with `LICENSE.txt`. The SMP pad grid triggers them through a dedicated engine instance with one-shot scheduling.
- **Stem tools:** `android-spike/native/StemSeparator.*` performs offline STFT/HPS separation into vocals, beat and harmony (60 s cap), and writes the isolated stems plus `no_vocals`, `no_beat` and `no_harmony` mixes. A host GTest verifies near-exact reconstruction. The STEM pad grid exposes all six outputs.
- **Live bindings extended:** `seek(deck, fraction)` and `setGain(deck, gain)` are now exported through JNI, so CUE, EQ VOL and one-shot scheduling use real engine controls.

## Styling assessment from the decompiled resources

`values/colors.xml` gives the functional UI palette: neutral greys `BG0..BG4` (dark base through light label grey), an orange highlight, a red record highlight, and `#808080` for fader labels. `values/dimens.xml` sets the small-control scale: 2dip jog margins, 6dip button radius, 8dip groove, 10dip slider width, 24dip crossfader end padding, 60dip waveform side controls, 64dip mode-switch zone, 72dip pitch overlay. `layout/*` uses Roboto text, 12sp TEMPO labels, 14sp fader labels, 24sp deck switches, layered black/grey bar backgrounds, rounded 5dip slider grooves with a wide white/grey thumb, and split-colour crossfader rendering. `mixer_view.xml` and `mixer_view_hero.xml` put HI/MID/LOW/VOL faders with a narrow vertical VU meter and a cue-monitor button; `double_waveform_layout.xml` adds BEND/SCRATCH modes, beatgrid editing (x2, /2, RESET, shift) and floating pitch overlays. `activity_main.xml` adds REC, a sync/beat-match strip, EQ/LOOP/FX mode switches, master VU and auto-fade ends on the crossfader.

The spike uses the same **structure, proportions, control vocabulary and dark/orange visual language** with Mixsphere's own hex values and an additional cyan deck-B accent; no Cross DJ hex values, icons, fonts, images or strings were copied.

## Recreated spike UI

`android-spike/app/src/main/java/com/mixsphere/SpikeActivity.kt` implements the full landscape arrangement:

- top sync strip with REC, a sync closeness meter, and `audioApi()` / `sampleRate()` / `latencyMs()` / `callbackMicros()` telemetry;
- a full-width dual waveform strip with beatgrid ticks, playhead and BEND/SCRATCH mode buttons;
- two equal deck panes with LOAD, circular jog wheels (touch to rotate), CUE / PLAY / SYNC / KEY transport, per-deck VU and vertical TEMPO pitch faders;
- a centre column with EQ / LOOP / FX / SMP mode switches, master and per-deck meters with cue-monitor toggles;
- a beatgrid tool row (x2, /2, shift, zoom, RESET, MORE) revealed from the waveform strip, plus an AUTOMIX label;
- a mode panel: EQ shows VOL/HI/MID/LOW faders, LOOP shows eight loop pads, FX shows an X/Y pad and effect selectors, SMP shows four sampler pads;
- a split-colour crossfader with AUTO A / AUTO B ends and a DEMO tone generator.

Live engine bindings: LOAD/PLAY via `loadTrack` and `play`, TEMPO via `setTempo(deck, rate, keylock)` (SYNC copies one deck's rate to the other), CUE via the newly exposed `seek(deck, 0.0)`, EQ VOL via `setGain(deck, gain)`, and the crossfader via `setCrossfader(position)`. HI/MID/LOW tone, loop pads, FX pad, jog rotation and REC are visual/spike-level controls with no native effect; they are included for layout fidelity, not presented as product features. This is harness UI for the spike, not product UX; the gate reminder below still applies.

## Gate reminder

Per `docs/PHASE0_HANDOFF.md`, PRD/UX/icon work must not begin before the Phase 0 gate is accepted.
This document is research input only; keep it on the task branch and do not act on it until the gate
passes.
