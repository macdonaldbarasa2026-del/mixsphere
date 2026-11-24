import "../.." as Skin
import Mixxx 1.0 as Mixxx
import QtQuick
import QtQuick.Layouts

Rectangle {
    id: root

    required property string mode
    signal modeSelected(string mode)

    readonly property color accent: "#04a7df"

    border.color: "#4a4d53"
    border.width: 1
    color: "#2b2d35"

    component Meter: Item {
        id: meter

        required property string group

        Mixxx.ControlProxy {
            id: vuControl

            group: meter.group
            key: "vu_meter"
            updateInterval: 33
        }
        Mixxx.ControlProxy {
            id: peakControl

            group: meter.group
            key: "peak_indicator"
            updateInterval: 33
        }
        Rectangle {
            anchors.fill: parent
            color: "#1d1d1d"
        }
        Rectangle {
            anchors.bottom: parent.bottom
            anchors.left: parent.left
            anchors.right: parent.right
            color: peakControl.value > 0 ? "#e5332a" : (vuControl.value > 0.8 ? "#f0a020" : "#2fbf4f")
            height: Math.round(parent.height * Math.max(0, Math.min(1, vuControl.value)))
        }
    }

    component MonButton: Rectangle {
        id: mon

        required property string group
        readonly property bool active: pflControl.value > 0

        Mixxx.ControlProxy {
            id: pflControl

            group: mon.group
            key: "pfl"
        }
        border.color: mon.active ? root.accent : "#585d65"
        border.width: 1
        color: monTap.pressed ? "#26292f" : (mon.active ? Qt.alpha(root.accent, 0.16) : "#1d1d1d")
        radius: 2

        Text {
            anchors.centerIn: parent
            color: mon.active ? root.accent : "#f0f1f3"
            font.bold: true
            font.pixelSize: Math.max(8, Math.min(12, Math.round(parent.width / 4)))
            text: qsTr("MON")
        }
        TapHandler {
            id: monTap

            onTapped: pflControl.value = pflControl.value > 0 ? 0 : 1
        }
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 4
        spacing: 4

        Repeater {
            model: [
                { "mode": "eq", "icon": "eq.svg", "label": qsTr("EQ") },
                { "mode": "loop", "icon": "loop.svg", "label": qsTr("LOOP") },
                { "mode": "fx", "icon": "fx.svg", "label": "FX" },
                { "mode": "smp", "icon": "smp.svg", "label": qsTr("SMP") },
                { "mode": "stem", "icon": "stem.svg", "label": "STEM" }
            ]

            CrossFlowButton {
                required property var modelData

                Layout.fillWidth: true
                Layout.preferredHeight: Math.round(Math.min(root.width * 0.8, (parent.height - parent.spacing * 7) / 8))
                accent: root.accent
                icon: Qt.resolvedUrl("icons/" + modelData.icon)
                label: modelData.label
                on: root.mode === modelData.mode
                onTapped: root.modeSelected(modelData.mode)
            }
        }

        Item {
            Layout.fillHeight: true
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 1
            color: "#585d65"
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: Math.round(root.width * 0.7)
            spacing: 4

            Text {
                color: "#bec4ca"
                font.bold: true
                font.pixelSize: Math.max(8, Math.min(11, Math.round(root.width / 8)))
                text: qsTr("MSTR")
            }
            Meter {
                Layout.fillHeight: true
                Layout.fillWidth: true
                group: "[Master]"
            }
        }
        Repeater {
            model: 2

            RowLayout {
                required property int index
                readonly property string channelGroup: `[Channel${index + 1}]`

                Layout.fillWidth: true
                Layout.preferredHeight: Math.round(root.width * 0.7)
                spacing: 4

                Text {
                    color: index === 0 ? "#04a7df" : "#f87819"
                    font.bold: true
                    font.pixelSize: Math.max(8, Math.min(11, Math.round(root.width / 8)))
                    text: index === 0 ? "A" : "B"
                }
                Meter {
                    Layout.fillHeight: true
                    Layout.fillWidth: true
                    group: channelGroup
                }
                MonButton {
                    Layout.fillHeight: true
                    Layout.preferredWidth: Math.round(root.width * 0.55)
                    group: channelGroup
                }
            }
        }
    }
}
