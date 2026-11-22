# ADR-0002: Qt scope in the engine library

- Status: Accepted
- Phase: 0
- Owner: Software Engineer

## Context
Mixxx 2.7.0-alpha is Qt 6 based. `ControlObject`, `EngineBuffer`, `EngineMixer`, and most engine classes are `QObject` subclasses with `Q_OBJECT` and Qt signals/slots. The default `mixxx-lib` target links `Concurrent, Core, Gui, Network, OpenGL, PrintSupport, Qml, Sql, Svg, Test, Widgets, Xml` and contains 151 UI/skin/dialog sources. Mixsphere's UI is Jetpack Compose, not Qt.

Alternatives considered:
1. Keep the full Qt stack including Widgets/QML and use Qt AndroidActivity as the app shell.
2. Keep QtCore-family modules only; expose JNI; Compose owns UI.
3. Replace the Qt control bus with a plain C++ control bus.

## Decision
Option 2. Build `libmixsphere_engine.so` against `QtCore`, `QtSql`, `QtConcurrent`, `QtXml`, `QtNetwork`. Exclude Widgets, QML/Quick, Gui, Svg, PrintSupport, OpenGL from the engine target. Use JNI as the Kotlin boundary. MOC runs for the included QObject classes.

## Reasons
- Option 1 imports a second UI framework, doubles the APK's native UI footprint, and conflicts with the Compose design direction.
- Option 3 requires rewriting the control layer and all its consumers before the spike can prove anything; it is a measured optimisation for Phase 3, not a Phase 0 prerequisite.
- Qt for Android is officially supported and upstream Mixxx already carries an Android CMake path and pinned vcpkg environment, so Option 2 is the lowest-risk proof.

## Consequences
- MOC and Qt Android deployment remain build requirements in CI.
- Qt licensing must be handled explicitly: use Qt under LGPL v3 (dynamic), publish source, license the combined work `GPL-3.0-or-later` with Mixxx attribution. Final legal review is a Phase 4 gate.
- If measured QObject/signal-slot overhead in the audio path exceeds the latency budget, revisit Option 3 with benchmark evidence.
