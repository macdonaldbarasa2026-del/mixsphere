import "../.." as Skin
import "../Booth" as Booth
import "." as CrossFlow
import Mixxx 1.0 as Mixxx
import QtQuick
import QtQuick.Layouts

Rectangle {
    id: root

    property bool compact: false
    signal loadRequested(string group)
    signal stemsRequested

    property string mode: "eq"
    property bool scratchEnabled: true
    readonly property int gap: 3

    Mixxx.ControlProxy {
        id: crossfaderControl

        group: "[Master]"
        key: "crossfader"
    }

    color: "#0d0f12"

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 2
        spacing: root.gap

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: root.compact ? Math.round(root.height * 0.14) : Math.round(root.height * 0.16)
            spacing: root.gap

            Skin.WaveformDisplay {
                Layout.fillHeight: true
                Layout.fillWidth: true
                group: "[Channel1]"
                isTouchMode: true
                showHud: false
            }
            Skin.WaveformDisplay {
                Layout.fillHeight: true
                Layout.fillWidth: true
                group: "[Channel2]"
                isTouchMode: true
                showHud: false
            }
            ColumnLayout {
                Layout.fillHeight: true
                Layout.preferredWidth: 22
                spacing: 2

                Rectangle {
                    Layout.fillHeight: true
                    Layout.fillWidth: true
                    color: Qt.alpha("#04a7df", autoA.pressed ? 0.4 : 0.12)
                    radius: 2
                    Text {
                        anchors.centerIn: parent
                        color: "#04a7df"
                        font.bold: true
                        font.pixelSize: 10
                        text: "A"
                    }
                    TapHandler {
                        id: autoA

                        onTapped: crossfaderControl.value = -1
                    }
                }
                Rectangle {
                    Layout.fillHeight: true
                    Layout.fillWidth: true
                    color: Qt.alpha("#f87819", autoB.pressed ? 0.4 : 0.12)
                    radius: 2
                    Text {
                        anchors.centerIn: parent
                        color: "#f87819"
                        font.bold: true
                        font.pixelSize: 10
                        text: "B"
                    }
                    TapHandler {
                        id: autoB

                        onTapped: crossfaderControl.value = 1
                    }
                }
            }
        }

        RowLayout {
            Layout.fillHeight: true
            Layout.fillWidth: true
            spacing: root.gap

            CrossFlow.CrossFlowDeck {
                Layout.fillHeight: true
                Layout.fillWidth: true
                compact: root.compact
                deckNumber: 1
                group: "[Channel1]"
                scratchEnabled: root.scratchEnabled
                onLoadRequested: group => root.loadRequested(group)
                onStemsRequested: root.stemsRequested()
            }
            CrossFlow.CrossFlowCenter {
                Layout.fillHeight: true
                Layout.preferredWidth: Math.round(Math.min(root.width * 0.1, 92))
                mode: root.mode
                onModeSelected: selected => root.mode = selected
            }
            CrossFlow.CrossFlowDeck {
                Layout.fillHeight: true
                Layout.fillWidth: true
                compact: root.compact
                deckNumber: 2
                group: "[Channel2]"
                mirrored: true
                scratchEnabled: root.scratchEnabled
                onLoadRequested: group => root.loadRequested(group)
                onStemsRequested: root.stemsRequested()
            }
        }

        CrossFlow.CrossFlowModePanel {
            Layout.fillWidth: true
            Layout.preferredHeight: root.compact ? Math.round(root.height * 0.2) : Math.round(root.height * 0.24)
            compact: root.compact
            mode: root.mode
            onLoadRequested: group => root.loadRequested(group)
            onStemsRequested: root.stemsRequested()
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: root.compact ? 28 : 34
            border.color: "#4a4d53"
            border.width: 1
            color: "#2b2d35"

            Row {
                anchors.left: parent.left
                anchors.leftMargin: 8
                anchors.verticalCenter: parent.verticalCenter
                spacing: 8

                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    color: "#bec4ca"
                    font.pixelSize: 10
                    text: qsTr("PHONES")
                }
                Booth.BoothKnob {
                    group: "[Master]"
                    height: parent.height - 4
                    key: "headMix"
                    width: height
                }
            }
            Booth.BoothFader {
                anchors.centerIn: parent
                centerMark: true
                group: "[Master]"
                height: parent.height - 6
                horizontal: true
                key: "crossfader"
                width: Math.round(Math.min(parent.width * 0.4, 420))
            }
            Row {
                anchors.verticalCenter: parent.verticalCenter
                anchors.right: parent.right
                anchors.rightMargin: 8
                spacing: 8

                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    color: "#bec4ca"
                    font.pixelSize: 10
                    text: qsTr("MASTER")
                }
                Booth.BoothKnob {
                    bipolar: false
                    group: "[Master]"
                    height: parent.height - 4
                    key: "gain"
                    width: height
                }
            }
        }
    }
}
