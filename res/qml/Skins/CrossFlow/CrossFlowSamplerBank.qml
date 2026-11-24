import Mixxx 1.0 as Mixxx
import QtQuick
import QtQuick.Layouts

Rectangle {
    id: root

    property color accent: "#04a7df"
    property int bank: 0
    signal loadRequested(string group)

    readonly property var sampleNames: [
        qsTr("RISER 2"), qsTr("RISER 4"), qsTr("FALL 2"), qsTr("FALL 4"),
        qsTr("REV S"), qsTr("REV L"), qsTr("WHOOSH F"), qsTr("WHOOSH S"),
        qsTr("IMPACT H"), qsTr("IMPACT D"), qsTr("IMPACT M"), qsTr("SUB S"),
        qsTr("SUB M"), qsTr("BOOM"), qsTr("SLAM"), "808"
    ]

    border.color: "#4a4d53"
    border.width: 1
    color: "#2b2d35"

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 4
        spacing: 3

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 18
            spacing: 6

            Text {
                color: root.accent
                font.bold: true
                font.pixelSize: 12
                text: qsTr("SAMPLE BANK")
            }
            Text {
                Layout.fillWidth: true
                color: "#757a82"
                font.pixelSize: 10
                text: qsTr("TAP: PLAY / STOP   HOLD: LOAD FROM LIBRARY")
            }
            Repeater {
                model: 3

                Rectangle {
                    required property int index
                    readonly property bool active: root.bank === index

                    border.color: active ? root.accent : "#585d65"
                    border.width: 1
                    color: active ? Qt.alpha(root.accent, 0.2) : "#1d1d1d"
                    radius: 2
                    width: 22

                    Text {
                        anchors.centerIn: parent
                        color: active ? "#ffffff" : "#bec4ca"
                        font.bold: true
                        font.pixelSize: 10
                        text: index + 1
                    }
                    TapHandler {
                        onTapped: root.bank = index
                    }
                }
            }
        }
        GridLayout {
            Layout.fillHeight: true
            Layout.fillWidth: true
            columnSpacing: 2
            columns: 3
            rowSpacing: 2

            Repeater {
                model: 6

                Rectangle {
                    id: pad

                    required property int index
                    readonly property int samplerIndex: root.bank * 6 + index
                    readonly property string group: `[Sampler${samplerIndex + 1}]`
                    readonly property string name: root.sampleNames[samplerIndex]
                    readonly property bool playing: playControl.value > 0
                    readonly property bool loaded: loadedControl.value > 0

                    border.color: padTap.pressed ? "#ffffff" : (pad.playing ? root.accent : (pad.loaded ? "#6a6f78" : "#585d65"))
                    border.width: pad.playing ? 2 : 1
                    color: padTap.pressed ? Qt.alpha(root.accent, 0.24) : (pad.playing ? Qt.alpha(root.accent, 0.16) : "#1d1d1d")
                    radius: 2

                    Mixxx.ControlProxy {
                        id: playControl

                        group: pad.group
                        key: "play"
                    }
                    Mixxx.ControlProxy {
                        id: cuePlayControl

                        group: pad.group
                        key: "cue_gotoandplay"
                    }
                    Mixxx.ControlProxy {
                        id: loadedControl

                        group: pad.group
                        key: "track_loaded"
                    }

                    Text {
                        anchors.centerIn: parent
                        color: pad.playing ? "#ffffff" : "#f0f1f3"
                        elide: Text.ElideRight
                        font.bold: true
                        font.pixelSize: Math.max(7, Math.min(11, Math.round(pad.width / 6)))
                        horizontalAlignment: Text.AlignHCenter
                        text: pad.name
                        width: parent.width - 4
                    }
                    Rectangle {
                        anchors.right: parent.right
                        anchors.rightMargin: 3
                        anchors.top: parent.top
                        anchors.topMargin: 3
                        color: pad.loaded ? (pad.playing ? "#3ad33a" : root.accent) : "#585d65"
                        height: 4
                        radius: 2
                        width: 4
                    }
                    TapHandler {
                        id: padTap

                        gesturePolicy: TapHandler.WithinBounds
                        onLongPressed: root.loadRequested(pad.group)
                        onTapped: {
                            if (pad.playing) {
                                playControl.value = 0;
                            } else if (pad.loaded) {
                                cuePlayControl.trigger();
                            } else {
                                root.loadRequested(pad.group);
                            }
                        }
                    }
                }
            }
        }
    }
}
