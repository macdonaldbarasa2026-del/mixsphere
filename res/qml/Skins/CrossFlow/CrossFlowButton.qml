import QtQuick

Rectangle {
    id: root

    required property url icon
    property string label: ""
    property bool on: false
    property color accent: "#04a7df"
    property bool showIcon: true
    signal tapped
    signal held

    border.color: on ? root.accent : "#585d65"
    border.width: on ? 2 : 1
    color: tap.pressed ? "#26292f" : (on ? Qt.alpha(root.accent, 0.16) : "#1d1d1d")
    radius: 3

    Image {
        id: iconImage

        anchors.horizontalCenter: parent.horizontalCenter
        anchors.top: parent.top
        anchors.topMargin: root.label.length > 0 ? 3 : 0
        fillMode: Image.PreserveAspectFit
        height: root.label.length > 0 ? Math.min(width, parent.height * 0.55) : Math.min(parent.width, parent.height) * 0.7
        source: root.icon
        visible: root.showIcon
        width: root.label.length > 0 ? Math.min(parent.width - 4, parent.height * 0.55) : Math.min(parent.width, parent.height) * 0.7
    }
    Text {
        anchors.bottom: parent.bottom
        anchors.bottomMargin: root.label.length > 0 ? 2 : 0
        anchors.horizontalCenter: parent.horizontalCenter
        color: root.on ? root.accent : "#f0f1f3"
        elide: Text.ElideRight
        font.bold: true
        font.pixelSize: Math.max(8, Math.min(12, Math.round(root.width / 6)))
        horizontalAlignment: Text.AlignHCenter
        text: root.label
        visible: root.label.length > 0
        width: parent.width - 4
    }
    TapHandler {
        id: tap

        gesturePolicy: TapHandler.WithinBounds
        onLongPressed: root.held()
        onTapped: root.tapped()
    }
}
