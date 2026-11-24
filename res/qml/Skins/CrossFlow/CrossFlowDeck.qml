import "../.." as Skin
import "../../Deck" as DeckComponent
import "../Booth" as Booth
import Mixxx 1.0 as Mixxx
import QtQuick
import QtQuick.Layouts

Rectangle {
    id: root

    required property string group
    required property int deckNumber
    property bool mirrored: false
    property bool compact: false
    property bool scratchEnabled: true
    signal loadRequested(string group)
    signal stemsRequested

    readonly property var deckPlayer: Mixxx.PlayerManager.getPlayer(group)
    readonly property var currentTrack: deckPlayer?.currentTrack ?? null
    readonly property bool loaded: trackLoadedControl.value > 0
    readonly property bool hasStems: stemCountControl.value > 0
    readonly property color deckColor: deckNumber === 1 ? "#04a7df" : "#f87819"
    readonly property real unit: Math.max(9, Math.min(height / 15, width / 32))
    property string padMode: "hotcue"

    function pad(number, digits) {
        return ("000" + number).slice(-digits);
    }
    border.color: "#4a4d53"
    border.width: 1
    color: "#2b2d35"

    Mixxx.ControlProxy {
        id: trackLoadedControl

        group: root.group
        key: "track_loaded"
    }
    Mixxx.ControlProxy {
        id: playControl

        group: root.group
        key: "play"
    }
    Mixxx.ControlProxy {
        id: cueControl

        group: root.group
        key: "cue_default"
    }
    Mixxx.ControlProxy {
        id: cueIndicatorControl

        group: root.group
        key: "cue_indicator"
    }
    Mixxx.ControlProxy {
        id: syncControl

        group: root.group
        key: "sync_enabled"
    }
    Mixxx.ControlProxy {
        id: syncLeaderControl

        group: root.group
        key: "sync_leader"
    }
    Mixxx.ControlProxy {
        id: quantizeControl

        group: root.group
        key: "quantize"
    }
    Mixxx.ControlProxy {
        id: keylockControl

        group: root.group
        key: "keylock"
    }
    Mixxx.ControlProxy {
        id: bpmControl

        group: root.group
        key: "bpm"
    }
    Mixxx.ControlProxy {
        id: rateRatioControl

        group: root.group
        key: "rate_ratio"
    }
    Mixxx.ControlProxy {
        id: rateRangeControl

        group: root.group
        key: "rateRange"
    }
    Mixxx.ControlProxy {
        id: loopEnabledControl

        group: root.group
        key: "loop_enabled"
    }
    Mixxx.ControlProxy {
        id: loopSizeControl

        group: root.group
        key: "beatloop_size"
    }
    Mixxx.ControlProxy {
        id: sampleRateControl

        group: root.group
        key: "track_samplerate"
    }
    Mixxx.ControlProxy {
        id: stemCountControl

        group: root.group
        key: "stem_count"

        onValueChanged: {
            if (value <= 0 && root.padMode === "stem") {
                root.padMode = "hotcue";
            }
        }
    }
    Mixxx.ControlProxy {
        id: bendUpControl

        group: root.group
        key: "rate_temp_up"
    }
    Mixxx.ControlProxy {
        id: bendDownControl

        group: root.group
        key: "rate_temp_down"
    }
    Mixxx.ControlProxy {
        id: jumpControl

        group: root.group
        key: ""
    }

    component ToolButton: Rectangle {
        id: tool

        property url icon: ""
        property string label: ""
        property bool on: false
        property bool showIcon: true
        property color onColor: "#04a7df"
        signal tapped
        signal held
        signal pressedChanged(bool pressed)

        border.color: on ? onColor : "#585d65"
        border.width: 1
        color: toolTap.pressed ? "#26292f" : (on ? Qt.alpha(onColor, 0.16) : "#1d1d1d")
        radius: 2

        Image {
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.top: parent.top
            anchors.topMargin: tool.label.length > 0 ? 2 : 0
            fillMode: Image.PreserveAspectFit
            height: tool.label.length > 0 ? Math.min(parent.width - 4, parent.height * 0.55) : Math.min(parent.width, parent.height) * 0.65
            source: tool.icon
            visible: tool.showIcon && tool.icon.toString().length > 0
            width: tool.label.length > 0 ? Math.min(parent.width - 4, parent.height * 0.55) : Math.min(parent.width, parent.height) * 0.65
        }
        Text {
            anchors.bottom: parent.bottom
            anchors.horizontalCenter: parent.horizontalCenter
            color: tool.on ? tool.onColor : "#f0f1f3"
            font.bold: true
            font.pixelSize: Math.max(7, Math.min(10, Math.round(root.unit * 0.8)))
            text: tool.label
            visible: tool.label.length > 0
        }
        TapHandler {
            id: toolTap

            gesturePolicy: TapHandler.WithinBounds
            onLongPressed: tool.held()
            onPressedChanged: tool.pressedChanged(pressed)
            onTapped: tool.tapped()
        }
    }

    component Pad: Rectangle {
        id: padItem

        required property int index
        readonly property int number: index + 1
        readonly property int sizeIndex: Math.floor(index / 2) + (index % 2) * 4
        readonly property var jumpSizes: [0.03125, 0.125, 0.25, 0.5, 1, 2, 4, 8]
        readonly property var jumpLabels: ["FINE", "1/8", "1/4", "1/2", "1", "2", "4", "8"]
        readonly property var loopSizes: [0.25, 0.5, 1, 2, 4, 8, 16, 32]
        readonly property var loopLabels: ["1/4", "1/2", "1", "2", "4", "8", "16", "32"]
        readonly property bool hotcueSet: hotcueStatus.value > 0
        readonly property color hotcueColor: {
            const rgb = hotcueColorControl.value;
            return rgb >= 0 ? Qt.rgba(((rgb >> 16) & 255) / 255, ((rgb >> 8) & 255) / 255, (rgb & 255) / 255, 1) : "#3ad33a";
        }
        readonly property bool lit: {
            switch (root.padMode) {
            case "hotcue":
                return hotcueSet;
            case "beatloop":
                return loopEnabledControl.value > 0 && loopSizeControl.value === loopSizes[index];
            default:
                return false;
            }
        }

        border.color: padTap.pressed ? "#ffffff" : (lit ? (root.padMode === "hotcue" ? hotcueColor : root.deckColor) : "#585d65")
        border.width: 1
        color: padTap.pressed ? Qt.alpha(root.deckColor, 0.2) : (lit ? Qt.alpha(root.deckColor, 0.12) : "#1d1d1d")
        radius: 2

        Mixxx.ControlProxy {
            id: hotcueStatus

            group: root.group
            key: `hotcue_${padItem.number}_status`
        }
        Mixxx.ControlProxy {
            id: hotcueColorControl

            group: root.group
            key: `hotcue_${padItem.number}_color`
        }
        Mixxx.ControlProxy {
            id: hotcuePosition

            group: root.group
            key: `hotcue_${padItem.number}_position`
        }
        Mixxx.ControlProxy {
            id: hotcueActivate

            group: root.group
            key: `hotcue_${padItem.number}_activate`
        }
        Mixxx.ControlProxy {
            id: beatloopToggle

            group: root.group
            key: `beatloop_${padItem.loopSizes[padItem.index]}_toggle`
        }

        Text {
            anchors.centerIn: parent
            color: padItem.lit ? "#ffffff" : "#bec4ca"
            font.bold: true
            font.pixelSize: Math.max(8, Math.round(root.unit * 0.95))
            text: {
                switch (root.padMode) {
                case "hotcue":
                    return String.fromCharCode(65 + padItem.index);
                case "beatjump":
                    return padItem.jumpLabels[padItem.sizeIndex];
                default:
                    return padItem.loopLabels[padItem.index];
                }
            }
        }
        TapHandler {
            id: padTap

            gesturePolicy: TapHandler.WithinBounds

            onLongPressed: {
                if (root.padMode === "hotcue" && padItem.hotcueSet) {
                    popupHotcue.hotcueNumber = padItem.number;
                    hotcuePopup.open();
                }
            }
            onPressedChanged: {
                switch (root.padMode) {
                case "hotcue":
                    hotcueActivate.value = pressed ? 1 : 0;
                    break;
                case "beatjump":
                    if (pressed) {
                        const back = padTap.point.position.x < padItem.width / 2;
                        jumpControl.key = `beatjump_${padItem.jumpSizes[padItem.sizeIndex]}_${back ? "backward" : "forward"}`;
                        jumpControl.trigger();
                    }
                    break;
                default:
                    if (pressed) {
                        beatloopToggle.trigger();
                    }
                }
            }
        }
    }

    Skin.Hotcue {
        id: popupHotcue

        group: root.group
        hotcueNumber: 1
    }
    Skin.HotcuePopup {
        id: hotcuePopup

        hotcue: popupHotcue

        Connections {
            function onIsSetChanged() {
                if (!popupHotcue.isSet) {
                    hotcuePopup.close();
                }
            }

            target: popupHotcue
        }
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 4
        spacing: 3

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: Math.round(root.unit * 2)
            color: "#1d1d1d"
            radius: 2

            RowLayout {
                anchors.fill: parent
                anchors.margins: 2
                spacing: 4

                Rectangle {
                    Layout.fillHeight: true
                    Layout.preferredWidth: Math.round(root.unit * 3.4)
                    color: root.deckColor
                    radius: 2

                    Text {
                        anchors.centerIn: parent
                        color: "#0d0f12"
                        font.bold: true
                        font.pixelSize: Math.round(root.unit)
                        text: qsTr("LOAD")
                    }
                    TapHandler {
                        onTapped: root.loadRequested(root.group)
                    }
                }
                Text {
                    Layout.fillWidth: true
                    color: "#ffffff"
                    elide: Text.ElideRight
                    font.pixelSize: Math.round(root.unit * 0.95)
                    text: root.loaded && root.currentTrack ? root.currentTrack.title : qsTr("NO TRACK")
                }
                Text {
                    color: root.deckColor
                    font.bold: true
                    font.pixelSize: Math.round(root.unit * 0.95)
                    text: root.loaded && root.currentTrack ? (root.currentTrack.keyText || "--") : "--"
                }
                Text {
                    color: "#f0f1f3"
                    font.pixelSize: Math.round(root.unit * 0.9)
                    text: root.loaded ? bpmControl.value.toFixed(1) : "--"
                }
            }
        }

        RowLayout {
            Layout.fillHeight: true
            Layout.fillWidth: true
            layoutDirection: root.mirrored ? Qt.RightToLeft : Qt.LeftToRight
            spacing: 4

            ColumnLayout {
                Layout.fillHeight: true
                Layout.fillWidth: true
                Layout.minimumWidth: root.unit * 6
                Layout.preferredWidth: root.unit * 10
                spacing: 3

                GridLayout {
                    Layout.fillHeight: true
                    Layout.fillWidth: true
                    columnSpacing: 3
                    columns: 2
                    rowSpacing: 3
                    visible: root.padMode !== "stem"

                    Repeater {
                        model: 8

                        Pad {
                            Layout.fillHeight: true
                            Layout.fillWidth: true
                        }
                    }
                }
                CrossFlowStemMixer {
                    Layout.fillHeight: true
                    Layout.fillWidth: true
                    compact: root.compact
                    deckColor: root.deckColor
                    group: root.group
                    visible: root.padMode === "stem"
                    onStemsRequested: root.stemsRequested()
                }
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: Math.round(root.unit * 1.8)
                    border.color: root.padMode === "stem" ? "#b388ff" : "#585d65"
                    color: "#1d1d1d"
                    radius: 2

                    Text {
                        anchors.centerIn: parent
                        color: root.padMode === "stem" ? "#b388ff" : "#f0f1f3"
                        font.bold: true
                        font.pixelSize: Math.max(8, Math.round(root.unit * 0.85))
                        text: {
                            switch (root.padMode) {
                            case "beatjump":
                                return qsTr("BEAT JUMP");
                            case "beatloop":
                                return qsTr("BEAT LOOP");
                            case "stem":
                                return qsTr("STEMS");
                            default:
                                return qsTr("HOT CUE");
                            }
                        }
                    }
                    TapHandler {
                        onTapped: {
                            const modes = root.hasStems ? ["hotcue", "beatjump", "beatloop", "stem"] : ["hotcue", "beatjump", "beatloop"];
                            root.padMode = modes[(modes.indexOf(root.padMode) + 1) % modes.length];
                        }
                    }
                }
            }

            ColumnLayout {
                Layout.fillHeight: true
                Layout.preferredWidth: Math.round(root.unit * 3.6)
                spacing: 3

                ToolButton {
                    Layout.fillWidth: true
                    Layout.preferredHeight: Math.round(root.unit * 2.2)
                    icon: Qt.resolvedUrl("icons/bend.svg")
                    label: root.syncLeaderControl.value > 0 ? "MASTER" : "SYNC"
                    on: syncControl.value > 0 || syncLeaderControl.value > 0
                    onColor: root.deckColor
                    onHeld: syncLeaderControl.value = syncLeaderControl.value > 0 ? 0 : 1
                    onTapped: syncControl.value = syncControl.value > 0 ? 0 : 1
                }
                Item {
                    Layout.fillHeight: true
                }
                Rectangle {
                    Layout.preferredHeight: Math.round(root.unit * 4)
                    Layout.preferredWidth: Math.round(root.unit * 4)
                    Layout.alignment: Qt.AlignHCenter
                    border.color: cueIndicatorControl.value > 0 ? "#ffcf7a" : "#6b522a"
                    border.width: 2
                    color: cueTap.pressed ? "#3a2408" : "#1d1d1d"
                    radius: width / 2

                    Text {
                        anchors.centerIn: parent
                        color: cueIndicatorControl.value > 0 ? "#ffcf7a" : "#f0f1f3"
                        font.bold: true
                        font.pixelSize: Math.max(9, Math.round(parent.height * 0.25))
                        text: "CUE"
                    }
                    TapHandler {
                        id: cueTap

                        onPressedChanged: cueControl.value = pressed ? 1 : 0
                    }
                }
                Rectangle {
                    Layout.preferredHeight: Math.round(root.unit * 4)
                    Layout.preferredWidth: Math.round(root.unit * 4)
                    Layout.alignment: Qt.AlignHCenter
                    border.color: playControl.value > 0 ? "#3ad33a" : "#2a6a2a"
                    border.width: 2
                    color: playTap.pressed ? "#0c2a0c" : "#1d1d1d"
                    radius: width / 2

                    Row {
                        anchors.centerIn: parent
                        spacing: 3
                        visible: playControl.value > 0

                        Repeater {
                            model: 2

                            Rectangle {
                                color: "#3ad33a"
                                height: Math.round(parent.parent.height * 0.34)
                                width: 3
                            }
                        }
                    }
                    Skin.Triangle {
                        anchors.centerIn: parent
                        color: "#6ab86a"
                        height: Math.round(parent.height * 0.34)
                        visible: playControl.value <= 0
                        width: Math.round(height * 0.9)
                    }
                    TapHandler {
                        id: playTap

                        onTapped: playControl.value = playControl.value > 0 ? 0 : 1
                    }
                }
                Item {
                    Layout.fillHeight: true
                }
            }

            Item {
                id: jogArea

                readonly property real size: Math.min(width, height)

                Layout.fillHeight: true
                Layout.fillWidth: true
                Layout.minimumWidth: root.unit * 12
                Layout.preferredWidth: root.unit * 22

                Rectangle {
                    anchors.centerIn: parent
                    border.color: "#585d65"
                    border.width: Math.max(2, Math.round(jogArea.size * 0.04))
                    color: "#0d0f12"
                    height: jogArea.size
                    radius: width / 2
                    width: jogArea.size

                    Rectangle {
                        anchors.fill: parent
                        anchors.margins: Math.round(jogArea.size * 0.1)
                        border.color: "#35373e"
                        border.width: 1
                        color: "transparent"
                        radius: width / 2
                    }
                    Repeater {
                        model: [0.035, 0.075, 0.13]

                        Rectangle {
                            anchors.fill: parent
                            anchors.margins: Math.round(jogArea.size * modelData)
                            border.color: "#35373e"
                            border.width: 1
                            color: "transparent"
                            opacity: 0.7
                            radius: width / 2
                        }
                    }
                    Rectangle {
                        anchors.centerIn: parent
                        color: Qt.alpha(root.deckColor, 0.08)
                        height: jogArea.size * 0.42
                        radius: width / 2
                        width: height
                    }
                    DeckComponent.TouchSpinny {
                        id: spinny

                        anchors.fill: parent
                        enabled: root.scratchEnabled
                        group: root.group
                        indicatorVisible: root.loaded

                        indicator: Item {
                            height: spinny.height
                            width: spinny.width

                            Rectangle {
                                anchors.horizontalCenter: parent.horizontalCenter
                                anchors.top: parent.top
                                anchors.topMargin: 2
                                color: root.deckColor
                                height: Math.round(jogArea.size * 0.12)
                                radius: width / 2
                                width: Math.max(3, Math.round(jogArea.size * 0.035))
                            }
                        }
                    }
                    Column {
                        anchors.centerIn: parent

                        Text {
                            anchors.horizontalCenter: parent.horizontalCenter
                            color: syncLeaderControl.value > 0 ? root.deckColor : "#ffffff"
                            font.pixelSize: Math.round(jogArea.size * 0.18)
                            text: root.loaded ? bpmControl.value.toFixed(1) : "--"
                        }
                        Text {
                            anchors.horizontalCenter: parent.horizontalCenter
                            color: "#bec4ca"
                            font.pixelSize: Math.round(jogArea.size * 0.08)
                            text: {
                                const percent = (rateRatioControl.value - 1) * 100;
                                return (percent >= 0.005 ? "+" : "") + percent.toFixed(1) + "% ±" + Math.round(rateRangeControl.value * 100);
                            }
                        }
                    }
                }
                Skin.Triangle {
                    anchors.left: parent.left
                    anchors.verticalCenter: parent.verticalCenter
                    color: root.scratchEnabled ? root.deckColor : "#585d65"
                    height: Math.max(8, jogArea.size * 0.08)
                    pointsRight: false
                    width: height * 0.8
                }
                Skin.Triangle {
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    color: root.scratchEnabled ? root.deckColor : "#585d65"
                    height: Math.max(8, jogArea.size * 0.08)
                    pointsRight: true
                    width: height * 0.8
                }
            }

            Booth.BoothFader {
                Layout.fillHeight: true
                Layout.preferredWidth: Math.round(root.unit * 2.4)
                centerMark: true
                color: root.deckColor
                group: root.group
                inverted: true
                key: "rate"
            }
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: Math.round(root.unit * 2)
            spacing: 3

            ToolButton {
                Layout.fillHeight: true
                Layout.preferredWidth: Math.round(root.unit * 2.6)
                icon: Qt.resolvedUrl("icons/bend.svg")
                label: "−"
                onPressedChanged: pressed => bendDownControl.value = pressed ? 1 : 0
            }
            ToolButton {
                Layout.fillHeight: true
                Layout.preferredWidth: Math.round(root.unit * 2.6)
                icon: Qt.resolvedUrl("icons/bend.svg")
                label: "+"
                onPressedChanged: pressed => bendUpControl.value = pressed ? 1 : 0
            }
            ToolButton {
                Layout.fillHeight: true
                Layout.preferredWidth: Math.round(root.unit * 3)
                icon: Qt.resolvedUrl("icons/grid.svg")
                label: "GRID"
                onTapped: {
                    if (root.loaded) {
                        beatgridPopup.open();
                    }
                }
            }
            DeckComponent.BeatgridPopup {
                id: beatgridPopup

                group: root.group
            }
            ToolButton {
                Layout.fillHeight: true
                Layout.preferredWidth: Math.round(root.unit * 3.4)
                icon: Qt.resolvedUrl("icons/scratch.svg")
                label: "SCRATCH"
                on: root.scratchEnabled
                onTapped: root.scratchEnabled = !root.scratchEnabled
            }
            ToolButton {
                Layout.fillHeight: true
                Layout.preferredWidth: Math.round(root.unit * 2.6)
                label: "Q"
                on: quantizeControl.value > 0
                onTapped: quantizeControl.value = quantizeControl.value > 0 ? 0 : 1
                showIcon: false
            }
            ToolButton {
                Layout.fillHeight: true
                Layout.preferredWidth: Math.round(root.unit * 2.6)
                label: "KEY"
                on: keylockControl.value > 0
                onTapped: keylockControl.value = keylockControl.value > 0 ? 0 : 1
                showIcon: false
            }
            Item {
                Layout.fillWidth: true
            }
            ToolButton {
                Layout.fillHeight: true
                Layout.preferredWidth: Math.round(root.unit * 3.4)
                icon: Qt.resolvedUrl("icons/stem.svg")
                label: "STEM"
                onTapped: root.stemsRequested()
            }
        }
    }
}
