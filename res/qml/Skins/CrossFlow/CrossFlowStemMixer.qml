import Mixxx 1.0 as Mixxx
import QtQuick
import QtQuick.Layouts

Rectangle {
    id: root

    required property string group
    required property color deckColor
    property bool compact: false
    signal stemsRequested

    readonly property var deckPlayer: Mixxx.PlayerManager.getPlayer(group)
    readonly property var currentTrack: deckPlayer?.currentTrack ?? null

    Mixxx.ControlProxy {
        id: stemCount

        group: root.group
        key: "stem_count"
    }
    Mixxx.ControlProxy {
        id: loadedControl

        group: root.group
        key: "track_loaded"
    }

    Repeater {
        id: allStems

        model: root.currentTrack ? root.currentTrack.stemsModel : 0

        delegate: Item {
            required property int index
            required property var model
            readonly property string stemLabel: (model.label || "").toLowerCase()
            property alias muted: stemMute.value

            Mixxx.ControlProxy {
                id: stemMute

                group: `${root.group.slice(0, -1)}_Stem${index + 1}]`
                key: "mute"
            }
        }
    }

    function indicesFor(kind) {
        const out = [];
        for (let i = 0; i < allStems.count; ++i) {
            const label = allStems.itemAt(i).stemLabel;
            const match = kind === "vocals" ? (label.indexOf("vocal") >= 0 || label.indexOf("vox") >= 0)
                : kind === "beat" ? (label.indexOf("drum") >= 0 || label.indexOf("beat") >= 0 || label.indexOf("percussion") >= 0)
                    : (label.indexOf("bass") >= 0 || label.indexOf("other") >= 0 || label.indexOf("harmony") >= 0 || label.indexOf("synth") >= 0 || label.indexOf("key") >= 0 || label.indexOf("accompaniment") >= 0);
            if (match) {
                out.push(i);
            }
        }
        if (out.length === 0 && allStems.count > 0) {
            const byIndex = kind === "beat" ? [0] : kind === "harmony" ? [1, 2] : [3];
            for (let i = 0; i < byIndex.length; ++i) {
                if (byIndex[i] < allStems.count) {
                    out.push(byIndex[i]);
                }
            }
        }
        return out;
    }
    function mutedFor(kind) {
        const indices = indicesFor(kind);
        if (indices.length === 0) {
            return true;
        }
        for (let i = 0; i < indices.length; ++i) {
            if (allStems.itemAt(indices[i]).muted <= 0) {
                return false;
            }
        }
        return true;
    }
    function setMuted(kind, mute) {
        const indices = indicesFor(kind);
        for (let i = 0; i < indices.length; ++i) {
            allStems.itemAt(indices[i]).muted = mute ? 1 : 0;
        }
    }
    function solo(kind) {
        const kinds = ["vocals", "beat", "harmony"];
        for (let k = 0; k < kinds.length; ++k) {
            setMuted(kinds[k], kinds[k] !== kind);
        }
    }
    function restore() {
        const kinds = ["vocals", "beat", "harmony"];
        for (let k = 0; k < kinds.length; ++k) {
            setMuted(kinds[k], false);
        }
    }

    border.color: "#4a4d53"
    border.width: 1
    color: "#2b2d35"

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 3
        spacing: 3

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: root.compact ? 14 : 18
            color: Qt.alpha(root.deckColor, 0.14)
            radius: 2
            Text {
                anchors.centerIn: parent
                color: root.deckColor
                font.bold: true
                font.pixelSize: 10
                text: qsTr("STEMS / DROPS")
            }
        }
        Text {
            Layout.fillWidth: true
            color: "#bec4ca"
            elide: Text.ElideRight
            font.pixelSize: 10
            text: root.currentTrack ? root.currentTrack.title : qsTr("NO TRACK")
        }

        GridLayout {
            Layout.fillHeight: true
            Layout.fillWidth: true
            columnSpacing: 3
            columns: 3
            rowSpacing: 3

            Repeater {
                model: [
                    { "kind": "vocals", "label": qsTr("VOCALS"), "drop": false },
                    { "kind": "beat", "label": qsTr("BEAT"), "drop": false },
                    { "kind": "harmony", "label": qsTr("HARMONY"), "drop": false },
                    { "kind": "vocals", "label": qsTr("NO VOCALS"), "drop": true },
                    { "kind": "beat", "label": qsTr("NO BEAT"), "drop": true },
                    { "kind": "harmony", "label": qsTr("NO HARMONY"), "drop": true }
                ]

                Rectangle {
                    id: stemPad

                    required property var modelData
                    readonly property bool drop: modelData.drop
                    readonly property bool engaged: drop ? root.mutedFor(modelData.kind) : !root.mutedFor(modelData.kind)
                    readonly property color padColor: modelData.kind === "vocals" ? "#b388ff" : modelData.kind === "beat" ? "#ff6a5c" : "#4fc3f7"

                    border.color: tap.pressed ? "#ffffff" : (engaged ? padColor : "#585d65")
                    border.width: engaged ? 2 : 1
                    color: tap.pressed ? Qt.alpha(padColor, 0.24) : (engaged ? Qt.alpha(padColor, 0.16) : "#1d1d1d")
                    radius: 2
                    enabled: stemCount.value > 0

                    Text {
                        anchors.centerIn: parent
                        color: stemPad.engaged ? "#ffffff" : "#bec4ca"
                        elide: Text.ElideRight
                        font.bold: true
                        font.pixelSize: Math.max(8, Math.min(12, Math.round(parent.width / 7)))
                        horizontalAlignment: Text.AlignHCenter
                        text: stemPad.modelData.label
                        width: parent.width - 4
                    }
                    TapHandler {
                        id: tap

                        onLongPressed: stemPad.drop ? root.restore() : root.solo(stemPad.modelData.kind)
                        onTapped: root.setMuted(stemPad.modelData.kind, !root.mutedFor(stemPad.modelData.kind))
                    }
                }
            }
        }
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: root.compact ? 20 : 24
            border.color: root.deckColor
            border.width: 1
            color: separateTap.pressed ? Qt.alpha(root.deckColor, 0.24) : "#1d1d1d"
            radius: 2
            visible: loadedControl.value > 0

            Text {
                anchors.centerIn: parent
                color: root.deckColor
                font.bold: true
                font.pixelSize: 10
                text: qsTr("SEPARATE STEMS")
            }
            TapHandler {
                id: separateTap

                onTapped: {
                    const model = Mixxx.Stems.spleeterInstalled ? "spleeter" : (Mixxx.Stems.htdemucsInstalled ? "htdemucs" : "");
                    if (model.length === 0) {
                        root.stemsRequested();
                    } else {
                        Mixxx.Stems.separateTrack(root.currentTrack.trackLocationUrl, model, false);
                        root.stemsRequested();
                    }
                }
            }
        }
    }
}
