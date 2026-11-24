import "../.." as Skin
import "../Booth" as Booth
import "." as CrossFlow
import Mixxx 1.0 as Mixxx
import QtQuick
import QtQuick.Controls

Item {
    id: root

    required property ApplicationWindow applicationWindow
    property var menuBar: null

    property int viewMode: 1
    readonly property bool mixView: viewMode === 1
    readonly property bool waveView: viewMode === 2
    readonly property bool browsing: viewMode === 3
    readonly property bool compact: height < 520

    readonly property bool controllerConnected: {
        const devices = Mixxx.ControllerManager.knownDevices;
        for (let i = 0; i < devices.length; ++i) {
            if (devices[i].enabled) {
                return true;
            }
        }
        return false;
    }

    property string loadTarget: ""
    readonly property var loadTargetPlayer: loadTarget.length > 0 ? Mixxx.PlayerManager.getPlayer(loadTarget) : null
    property int playView: 1

    readonly property int numDecks: 2
    readonly property int numSamplers: 16
    readonly property int numPreviewDecks: 1
    readonly property int waveformOverviewTypeRgb: 2

    readonly property var skinTheme: ({
            "accentColor": "#04a7df",
            "backgroundColor": "#0d0f12",
            "sunkenBackgroundColor": "#0c0d0f",
            "darkGray": "#0d0f12",
            "darkGray2": "#35373e",
            "darkGray3": "#4a4d53",
            "darkGray4": "#1d1d1d",
            "textColor": "#ffffff",
            "panelSplitterBackground": "#0d0f12",
            "libraryScrollHandle": "#3a3c40",
            "libraryScrollHandleHovered": "#5c6068",
            "libraryScrollHandlePressed": "#f0f1f3",
            "libraryScrollTrack": "#0d0f12",
            "libraryActionBackground": "#35373e",
            "libraryActionDisabledBackground": "#111214",
            "libraryActionText": "#ffffff",
            "libraryActionDisabledText": "#5c6068",
            "libraryDeck1Background": "#04a7df",
            "libraryDeck1Pressed": "#ffc27a",
            "libraryDeck2Background": "#f87819",
            "libraryDeck2Pressed": "#7de1f0",
            "libraryConfirmBackground": "#04a7df",
            "libraryKeyMatchColor": "#3ad33a",
            "libraryKeyMatchBackground": "#1a3ad33a"
        })

    readonly property var topBarItems: [
        { "name": "dj", "label": "MIX", "view": true },
        { "name": "wave", "label": qsTr("WAVE"), "view": true },
        { "name": "lib", "label": qsTr("BROWSE"), "view": true },
        { "name": "load1", "label": qsTr("LOAD %1").arg(1), "view": false },
        { "name": "load2", "label": qsTr("LOAD %1").arg(2), "view": false },
        { "name": "split", "label": "SPLIT", "view": false },
        { "name": "rec", "label": "REC", "view": false }
    ]
    readonly property var hiddenTopBarItems: Mixxx.MobileApp.hiddenTopBarItems
    readonly property var viewNames: ({
            "1": "dj",
            "2": "wave",
            "3": "lib"
        })

    function itemShown(name) {
        return root.hiddenTopBarItems.indexOf(name) < 0;
    }
    function showView(mode) {
        root.loadTarget = "";
        root.viewMode = mode;
        root.playView = mode;
    }
    function showPlayView() {
        root.loadTarget = "";
        root.viewMode = root.playView;
    }
    function openQuickLoad(group) {
        root.loadTarget = (group && group.length > 0) ? group : "[Channel1]";
        root.viewMode = 3;
    }
    function toggleLibraryView() {
        if (root.browsing && root.loadTarget.length === 0) {
            root.showPlayView();
        } else {
            root.openQuickLoad("[Channel1]");
        }
    }
    function preferredView() {
        const order = root.controllerConnected ? [2, 1, 3] : [1, 2, 3];
        const mode = order.find(m => root.itemShown(root.viewNames[m]));
        return mode ?? 3;
    }
    function leaveHiddenView() {
        if (root.browsing || root.itemShown(root.viewNames[root.viewMode])) {
            return;
        }
        root.showView(root.preferredView());
    }
    function openStemsQueue() {
        stemsOverlay.openQueue();
    }

    Component.onCompleted: {
        root.showView(root.preferredView());
        Qt.callLater(root.restoreState);
    }
    onHiddenTopBarItemsChanged: root.leaveHiddenView()
    onControllerConnectedChanged: {
        if ((root.viewMode === 1 || root.viewMode === 2) && root.viewMode !== root.preferredView()) {
            root.showView(root.preferredView());
        }
    }

    Connections {
        function onTrackChanged() {
            if (root.browsing) {
                root.showPlayView();
            }
        }

        ignoreUnknownSignals: true
        target: root.loadTargetPlayer
    }

    Mixxx.ControlProxy {
        group: "[App]"
        key: "num_decks"

        onInitializedChanged: value = root.numDecks
    }
    Mixxx.ControlProxy {
        group: "[App]"
        key: "num_samplers"

        onInitializedChanged: value = root.numSamplers
    }
    Mixxx.ControlProxy {
        group: "[App]"
        key: "num_preview_decks"

        onInitializedChanged: value = root.numPreviewDecks
    }
    Mixxx.ControlProxy {
        group: "[Waveform]"
        key: "WaveformOverviewType"

        onInitializedChanged: value = root.waveformOverviewTypeRgb
    }
    Mixxx.ControlProxy {
        id: headSplitControl

        group: "[Master]"
        key: "headSplit"
    }
    Mixxx.ControlProxy {
        id: sessionCrossfader

        group: "[Master]"
        key: "crossfader"
    }
    Skin.LibraryScanSummaryDialog {
    }

    CrossFlow.CrossFlowSession {
        id: sessionStore
    }
    property bool shutdownDone: false

    function pathOf(url) {
        const text = url.toString();
        return text.indexOf("file://") === 0 ? decodeURIComponent(text.substring(7)) : text;
    }
    function captureState() {
        const decks = [];
        for (let i = 0; i < sessionDecks.count; ++i) {
            const deck = sessionDecks.itemAt(i);
            decks.push({
                    "location": deck.location,
                    "position": deck.position.value,
                    "rate": deck.rate.value,
                    "keylock": deck.keylock.value
                });
        }
        const samplers = [];
        for (let i = 0; i < sessionSamplers.count; ++i) {
            samplers.push(sessionSamplers.itemAt(i).location);
        }
        return {
            "version": 1,
            "viewMode": root.viewMode,
            "mode": mixViewItem.mode,
            "crossfader": sessionCrossfader.value,
            "decks": decks,
            "samplers": samplers
        };
    }
    function restoreState() {
        const state = sessionStore.load();
        if (!state || state.version !== 1) {
            return;
        }
        root.viewMode = state.viewMode === 2 || state.viewMode === 3 ? state.viewMode : 1;
        root.playView = root.viewMode;
        mixViewItem.mode = typeof state.mode === "string" ? state.mode : "eq";
        sessionCrossfader.value = state.crossfader;
        const decks = state.decks || [];
        for (let i = 0; i < decks.length && i < sessionDecks.count; ++i) {
            const saved = decks[i];
            if (!saved.location) {
                continue;
            }
            const deck = sessionDecks.itemAt(i);
            Mixxx.PlayerManager.loadLocationToPlayer(saved.location, deck.group, false);
            Qt.callLater(function() {
                deck.position.value = saved.position;
                deck.rate.value = saved.rate;
                deck.keylock.value = saved.keylock;
            });
        }
        const samplers = state.samplers || [];
        for (let i = 0; i < samplers.length && i < sessionSamplers.count; ++i) {
            if (samplers[i]) {
                Mixxx.PlayerManager.loadLocationToPlayer(samplers[i], sessionSamplers.itemAt(i).group, false);
            }
        }
    }
    function shutdown() {
        if (root.shutdownDone) {
            return;
        }
        root.shutdownDone = true;
        for (let i = 0; i < sessionDecks.count; ++i) {
            sessionDecks.itemAt(i).play.value = 0;
        }
        Mixxx.Stems.cancelAll();
        sessionStore.save(root.captureState());
    }

    Repeater {
        id: sessionDecks

        model: 2

        delegate: Item {
            required property int index
            readonly property string group: `[Channel${index + 1}]`
            readonly property var player: Mixxx.PlayerManager.getPlayer(group)
            readonly property string location: player && player.currentTrack ? root.pathOf(player.currentTrack.trackLocationUrl) : ""

            property alias position: positionControl
            property alias rate: rateControl
            property alias keylock: keylockControl
            property alias play: playControl

            Mixxx.ControlProxy {
                id: positionControl

                group: group
                key: "playposition"
            }
            Mixxx.ControlProxy {
                id: rateControl

                group: group
                key: "rate"
            }
            Mixxx.ControlProxy {
                id: keylockControl

                group: group
                key: "keylock"
            }
            Mixxx.ControlProxy {
                id: playControl

                group: group
                key: "play"
            }
        }
    }
    Repeater {
        id: sessionSamplers

        model: 16

        delegate: Item {
            required property int index
            readonly property string group: `[Sampler${index + 1}]`
            readonly property var player: Mixxx.PlayerManager.getPlayer(group)
            readonly property string location: player && player.currentTrack ? root.pathOf(player.currentTrack.trackLocationUrl) : ""
        }
    }
    Connections {
        function onAboutToQuit() {
            root.shutdown();
        }

        target: Qt.application
    }
    Component.onDestruction: root.shutdown()

    component TopTab: Rectangle {
        id: tab

        required property url icon
        required property string label
        property bool active: false
        signal clicked

        border.color: active ? "#04a7df" : "#585d65"
        border.width: active ? 2 : 1
        color: tap.pressed ? "#35373e" : "#0d0f12"
        height: parent.height
        width: root.width < 760 ? Math.max(44, tabText.implicitWidth + 26) : Math.max(root.compact ? 70 : 100, tabText.implicitWidth + 34)

        Image {
            anchors.left: parent.left
            anchors.leftMargin: 5
            anchors.verticalCenter: parent.verticalCenter
            fillMode: Image.PreserveAspectFit
            height: Math.min(parent.height - 6, 18)
            source: tab.icon
            width: height
        }
        Text {
            id: tabText

            anchors.centerIn: parent
            anchors.horizontalCenterOffset: 9
            color: tab.active ? "#04a7df" : "#f0f1f3"
            font.bold: true
            font.pixelSize: root.width < 760 ? 9 : (root.compact ? 10 : 12)
            text: tab.label
        }
        TapHandler {
            id: tap

            onTapped: tab.clicked()
        }
    }

    Rectangle {
        anchors.fill: parent
        color: "#0d0f12"
    }

    Item {
        id: content

        anchors.fill: parent
        enabled: !settingsPopup.opened

        Row {
            id: topBar

            anchors.left: parent.left
            anchors.leftMargin: 4
            anchors.top: parent.top
            anchors.topMargin: 3
            height: root.compact ? 28 : 36
            spacing: root.width < 760 ? 3 : 6

            TopTab {
                active: root.mixView
                icon: Qt.resolvedUrl("icons/smp.svg")
                label: "MIX"
                visible: root.itemShown("dj")
                onClicked: root.showView(1)
            }
            TopTab {
                active: root.waveView
                icon: Qt.resolvedUrl("icons/wave.svg")
                label: qsTr("WAVE")
                visible: root.itemShown("wave")
                onClicked: root.showView(2)
            }
            TopTab {
                active: root.browsing && root.loadTarget.length === 0
                icon: Qt.resolvedUrl("icons/browse.svg")
                label: qsTr("BROWSE")
                visible: root.itemShown("lib")
                onClicked: root.toggleLibraryView()
            }
            TopTab {
                active: root.browsing && root.loadTarget === "[Channel1]"
                icon: Qt.resolvedUrl("icons/drop.svg")
                label: qsTr("LOAD %1").arg(1)
                visible: root.itemShown("load1")
                onClicked: root.openQuickLoad("[Channel1]")
            }
            TopTab {
                active: root.browsing && root.loadTarget === "[Channel2]"
                icon: Qt.resolvedUrl("icons/drop.svg")
                label: qsTr("LOAD %1").arg(2)
                visible: root.itemShown("load2")
                onClicked: root.openQuickLoad("[Channel2]")
            }
        }

        Row {
            anchors.right: parent.right
            anchors.rightMargin: 4
            anchors.top: topBar.top
            height: topBar.height
            spacing: 6

            TopTab {
                active: Mixxx.Stems.hold !== ""
                icon: Qt.resolvedUrl("icons/stem.svg")
                label: Mixxx.Stems.hold !== "" ? qsTr("STEM PAUSED") : `STEM ${Math.round(Mixxx.Stems.progress * 100)}%`
                visible: Mixxx.Stems.pendingCount > 0 || Mixxx.Stems.progress > 0
                onClicked: root.openStemsQueue()
            }
            TopTab {
                active: headSplitControl.value > 0
                icon: Qt.resolvedUrl("icons/bend.svg")
                label: "SPLIT"
                visible: root.itemShown("split")
                onClicked: headSplitControl.value = headSplitControl.value > 0 ? 0 : 1
            }
            Skin.MobileRecordButton {
                fontPixelSize: root.width < 760 ? 9 : (root.compact ? 10 : 12)
                height: parent.height
                idleBorderColor: "#585d65"
                idleColor: "#0d0f12"
                radius: 0
                visible: root.itemShown("rec")
                width: Math.max(root.width < 760 ? 48 : 82, implicitWidth)
            }
            TopTab {
                active: settingsPopup.opened
                icon: Qt.resolvedUrl("icons/utility.svg")
                label: qsTr("UTILITY")
                onClicked: settingsPopup.open()
            }
        }

        CrossFlow.CrossFlowMixView {
            id: mixViewItem

            anchors.bottom: parent.bottom
            anchors.bottomMargin: 3
            anchors.left: parent.left
            anchors.leftMargin: 3
            anchors.right: parent.right
            anchors.rightMargin: 3
            anchors.top: topBar.bottom
            anchors.topMargin: 3
            compact: root.compact
            visible: root.mixView && !root.browsing

            onLoadRequested: group => root.openQuickLoad(group)
            onStemsRequested: root.openStemsQueue()
        }

        Item {
            anchors.bottom: parent.bottom
            anchors.bottomMargin: 3
            anchors.left: parent.left
            anchors.leftMargin: 3
            anchors.right: parent.right
            anchors.rightMargin: 3
            anchors.top: topBar.bottom
            anchors.topMargin: 3
            visible: root.waveView && !root.browsing

            Column {
                anchors.bottom: parent.bottom
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.top: parent.top
                spacing: 3

                Skin.WaveformDisplay {
                    group: "[Channel1]"
                    height: (parent.height - 6) / 2
                    isTouchMode: true
                    showHud: false
                    width: parent.width
                }
                Skin.WaveformDisplay {
                    group: "[Channel2]"
                    height: (parent.height - 6) / 2
                    isTouchMode: true
                    showHud: false
                    width: parent.width
                }
            }
        }

        Loader {
            id: library

            property bool keepLoaded: false

            anchors.bottom: parent.bottom
            anchors.bottomMargin: 3
            anchors.left: parent.left
            anchors.leftMargin: 3
            anchors.right: parent.right
            anchors.rightMargin: 3
            anchors.top: topBar.bottom
            anchors.topMargin: 3
            active: keepLoaded || root.browsing
            visible: root.browsing
            onLoaded: keepLoaded = true

            sourceComponent: Component {
                Booth.BoothBrowse {
                    acceptsController: !settingsPopup.opened
                    anchors.fill: parent
                    compact: root.compact
                    loadTarget: root.loadTarget
                }
            }
        }
    }

    Skin.StemsOverlay {
        id: stemsOverlay

        anchors.fill: parent
        z: 95
    }

    Skin.Settings {
        id: settingsPopup

        backgroundColor: "#0d0f12"
        headerColor: "#35373e"
        title: qsTr("UTILITY")
        height: root.applicationWindow.isMobile ? parent.height : Math.min(840, parent.height)
        modal: true
        width: root.applicationWindow.isMobile ? parent.width : Math.min(1400, parent.width)
        x: root.applicationWindow.isMobile ? 0 : Math.round((parent.width - width) / 2)
        y: root.applicationWindow.isMobile ? 0 : Math.round((parent.height - height) / 2)

        Overlay.modal: Rectangle {
            color: "#99000000"
        }
    }

    property bool mobileControlsCreated: false

    Mixxx.SkinControlCreator {
        group: "[Skin]"
        key: "mobile_library_toggle"

        Component.onCompleted: root.mobileControlsCreated = true
    }
    Mixxx.ControlProxy {
        group: "[Skin]"
        key: root.mobileControlsCreated ? "mobile_library_toggle" : ""

        onValueChanged: {
            if (value > 0) {
                root.toggleLibraryView();
            }
        }
    }
    Repeater {
        model: 2

        Item {
            id: deckLoaderAccess

            required property int index
            readonly property string group: `[Channel${index + 1}]`
            property bool controlCreated: false

            Mixxx.SkinControlCreator {
                group: "[Skin]"
                key: `mobile_loader_deck${deckLoaderAccess.index + 1}`

                Component.onCompleted: deckLoaderAccess.controlCreated = true
            }
            Mixxx.ControlProxy {
                group: "[Skin]"
                key: deckLoaderAccess.controlCreated ? `mobile_loader_deck${deckLoaderAccess.index + 1}` : ""

                onValueChanged: {
                    if (value > 0) {
                        root.openQuickLoad(deckLoaderAccess.group);
                    }
                }
            }
            Mixxx.ControlProxy {
                group: deckLoaderAccess.group
                key: "LoadSelectedTrack"

                onValueChanged: {
                    if (value > 0 && !library.visible) {
                        Qt.callLater(root.openQuickLoad, deckLoaderAccess.group);
                    }
                }
            }
        }
    }
}
