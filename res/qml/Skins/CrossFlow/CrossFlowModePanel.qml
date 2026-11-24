import "../.." as Skin
import "../Booth" as Booth
import Mixxx 1.0 as Mixxx
import QtQuick
import QtQuick.Layouts

Rectangle {
    id: root

    required property string mode
    property bool compact: false
    signal loadRequested(string group)
    signal stemsRequested

    readonly property color deck1Color: "#04a7df"
    readonly property color deck2Color: "#f87819"

    border.color: "#4a4d53"
    border.width: 1
    color: "#2b2d35"

    component ChannelEq: RowLayout {
        id: eqChannel

        required property string group
        required property color deckColor
        required property string label
        readonly property string eqGroup: `[EqualizerRack1_${group}_Effect1]`
        readonly property string quickGroup: `[QuickEffectRack1_${group}]`

        spacing: 3

        Rectangle {
            Layout.fillHeight: true
            Layout.preferredWidth: 22
            color: eqChannel.deckColor
            radius: 2

            Text {
                anchors.centerIn: parent
                color: "#0d0f12"
                font.bold: true
                font.pixelSize: 14
                text: eqChannel.label
            }
        }
        Repeater {
            model: [
                { "label": qsTr("TRIM"), "group": eqChannel.group, "key": "pregain", "bipolar": false },
                { "label": qsTr("HIGH"), "group": eqChannel.eqGroup, "key": "parameter3", "bipolar": true },
                { "label": qsTr("MID"), "group": eqChannel.eqGroup, "key": "parameter2", "bipolar": true },
                { "label": qsTr("LOW"), "group": eqChannel.eqGroup, "key": "parameter1", "bipolar": true },
                { "label": qsTr("FILTER"), "group": eqChannel.quickGroup, "key": "super1", "bipolar": false }
            ]

            Booth.BoothKnob {
                required property var modelData

                Layout.fillHeight: true
                Layout.fillWidth: true
                bipolar: modelData.bipolar
                color: eqChannel.deckColor
                group: modelData.group
                key: modelData.key
                label: modelData.label
            }
        }
    }

    component LoopPad: Rectangle {
        id: loopPad

        required property string group
        required property real beats
        required property string label
        property color padColor: "#04a7df"
        readonly property string controlKey: `beatloop_${beats}_toggle`

        Mixxx.ControlProxy {
            id: toggleControl

            group: loopPad.group
            key: loopPad.controlKey
        }
        border.color: padTap.pressed ? "#ffffff" : (toggleControl.value > 0 ? padColor : "#585d65")
        border.width: toggleControl.value > 0 ? 2 : 1
        color: padTap.pressed ? Qt.alpha(padColor, 0.24) : (toggleControl.value > 0 ? Qt.alpha(padColor, 0.16) : "#1d1d1d")
        radius: 2

        Text {
            anchors.centerIn: parent
            color: toggleControl.value > 0 ? "#ffffff" : "#f0f1f3"
            font.bold: true
            font.pixelSize: Math.max(8, Math.min(12, Math.round(parent.width / 4)))
            text: loopPad.label
        }
        TapHandler {
            id: padTap

            onTapped: toggleControl.trigger()
        }
    }

    Item {
        anchors.fill: parent
        anchors.margins: 4
        visible: root.mode === "eq"

        RowLayout {
            anchors.fill: parent
            spacing: 6

            ChannelEq {
                Layout.fillHeight: true
                Layout.fillWidth: true
                deckColor: root.deck1Color
                group: "[Channel1]"
                label: "A"
            }
            Rectangle {
                Layout.fillHeight: true
                Layout.preferredWidth: 1
                color: "#585d65"
            }
            ChannelEq {
                Layout.fillHeight: true
                Layout.fillWidth: true
                deckColor: root.deck2Color
                group: "[Channel2]"
                label: "B"
            }
        }
    }

    Item {
        anchors.fill: parent
        anchors.margins: 4
        visible: root.mode === "loop"

        RowLayout {
            anchors.fill: parent
            spacing: 6

            Repeater {
                model: [
                    { "group": "[Channel1]", "color": root.deck1Color, "label": "A" },
                    { "group": "[Channel2]", "color": root.deck2Color, "label": "B" }
                ]

                ColumnLayout {
                    id: loopChannel

                    required property var modelData
                    readonly property string channelGroup: modelData.group
                    readonly property var sizes: [0.25, 0.5, 1, 2, 4, 8, 16, 32]
                    readonly property var labels: ["1/4", "1/2", "1", "2", "4", "8", "16", "32"]

                    Layout.fillHeight: true
                    Layout.fillWidth: true
                    spacing: 3

                    Text {
                        color: modelData.color
                        font.bold: true
                        font.pixelSize: 11
                        text: qsTr("DECK %1").arg(modelData.label)
                    }
                    GridLayout {
                        Layout.fillHeight: true
                        Layout.fillWidth: true
                        columnSpacing: 3
                        columns: 4
                        rowSpacing: 3

                        Repeater {
                            model: 8

                            LoopPad {
                                Layout.fillHeight: true
                                Layout.fillWidth: true
                                beats: loopChannel.sizes[index]
                                color: loopChannel.modelData.color
                                group: loopChannel.channelGroup
                                label: loopChannel.labels[index]
                            }
                        }
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        Layout.preferredHeight: 24
                        spacing: 3

                        Repeater {
                            model: [
                                { "label": "½", "key": "loop_halve" },
                                { "label": "×2", "key": "loop_double" },
                                { "label": qsTr("RELOOP"), "key": "reloop_toggle" }
                            ]

                            Rectangle {
                                required property var modelData

                                Layout.fillHeight: true
                                Layout.fillWidth: true
                                border.color: "#585d65"
                                border.width: 1
                                color: tap.pressed ? "#26292f" : "#1d1d1d"
                                radius: 2

                                Mixxx.ControlProxy {
                                    id: command

                                    group: loopChannel.channelGroup
                                    key: modelData.key
                                }
                                Text {
                                    anchors.centerIn: parent
                                    color: "#f0f1f3"
                                    font.bold: true
                                    font.pixelSize: 10
                                    text: modelData.label
                                }
                                TapHandler {
                                    id: tap

                                    onTapped: command.trigger()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Item {
        anchors.fill: parent
        anchors.margins: 4
        visible: root.mode === "fx"

        RowLayout {
            anchors.fill: parent
            spacing: 6

            Skin.XYPad {
                Layout.fillHeight: true
                Layout.preferredWidth: Math.min(parent.width * 0.5, 320)
                accentColor: "#04a7df"
                border.color: "#4a4d53"
                buttonBorderColor: "#585d65"
                buttonColor: "#1d1d1d"
                buttonTextColor: "#f0f1f3"
                closable: false
                color: "#2b2d35"
                controlRadius: 2
                deck1Color: root.deck1Color
                deck2Color: root.deck2Color
                holdColor: "#04a7df"
                radius: 0
                surfaceBorderColor: "#4a4d53"
                surfaceColor: "#0d0f12"
                surfaceRadius: 0
                title: qsTr("XY FX")
                wetColor: "#d4e157"
            }
            ColumnLayout {
                Layout.fillHeight: true
                Layout.fillWidth: true
                spacing: 4

                Text {
                    color: "#f0f1f3"
                    font.pixelSize: 10
                    text: qsTr("QUICK FILTER / BEAT FX")
                }
                RowLayout {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 80
                    spacing: 4

                    Repeater {
                        model: [
                            { "group": "[Channel1]", "color": root.deck1Color, "label": "A" },
                            { "group": "[Channel2]", "color": root.deck2Color, "label": "B" }
                        ]

                        Booth.BoothKnob {
                            required property var modelData

                            Layout.fillHeight: true
                            Layout.fillWidth: true
                            bipolar: false
                            color: modelData.color
                            group: `[QuickEffectRack1_${modelData.group}]`
                            key: "super1"
                            label: modelData.label
                        }
                    }
                }
                Item {
                    Layout.fillHeight: true
                }
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 28
                    border.color: effectEnabled.value > 0 ? "#04a7df" : "#585d65"
                    border.width: 1
                    color: effectTap.pressed ? "#26292f" : "#1d1d1d"
                    radius: 2

                    Mixxx.ControlProxy {
                        id: effectEnabled

                        group: "[EffectRack1_EffectUnit1]"
                        key: "enabled"
                    }
                    Text {
                        anchors.centerIn: parent
                        color: effectEnabled.value > 0 ? "#04a7df" : "#f0f1f3"
                        font.bold: true
                        font.pixelSize: 11
                        text: effectEnabled.value > 0 ? qsTr("BEAT FX ON") : qsTr("BEAT FX OFF")
                    }
                    TapHandler {
                        id: effectTap

                        onTapped: effectEnabled.value = effectEnabled.value > 0 ? 0 : 1
                    }
                }
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 28
                    border.color: "#585d65"
                    border.width: 1
                    color: nextTap.pressed ? "#26292f" : "#1d1d1d"
                    radius: 2

                    Mixxx.ControlProxy {
                        id: nextEffect

                        group: "[EffectRack1_EffectUnit1_Effect1]"
                        key: "next_effect"
                    }
                    Text {
                        anchors.centerIn: parent
                        color: "#f0f1f3"
                        font.bold: true
                        font.pixelSize: 11
                        text: qsTr("NEXT EFFECT")
                    }
                    TapHandler {
                        id: nextTap

                        onTapped: nextEffect.trigger()
                    }
                }
            }
        }
    }

    CrossFlowSamplerBank {
        anchors.fill: parent
        anchors.margins: 4
        accent: root.deck1Color
        visible: root.mode === "smp"
        onLoadRequested: group => root.loadRequested(group)
    }

    Item {
        anchors.fill: parent
        anchors.margins: 4
        visible: root.mode === "stem"

        RowLayout {
            anchors.fill: parent
            spacing: 6

            CrossFlowStemMixer {
                Layout.fillHeight: true
                Layout.fillWidth: true
                compact: root.compact
                deckColor: root.deck1Color
                group: "[Channel1]"
                onStemsRequested: root.stemsRequested()
            }
            Rectangle {
                Layout.fillHeight: true
                Layout.preferredWidth: 1
                color: "#585d65"
            }
            CrossFlowStemMixer {
                Layout.fillHeight: true
                Layout.fillWidth: true
                compact: root.compact
                deckColor: root.deck2Color
                group: "[Channel2]"
                onStemsRequested: root.stemsRequested()
            }
        }
    }
}
