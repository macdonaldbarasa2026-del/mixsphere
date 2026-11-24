import QtQuick
import QtQuick.LocalStorage 2.0

QtObject {
    id: root

    function database() {
        return LocalStorage.openDatabaseSync("CrossFlowSession", "1.0", "CrossFlow session", 100000);
    }
    function save(state) {
        try {
            const db = root.database();
            db.transaction(function(tx) {
                tx.executeSql("CREATE TABLE IF NOT EXISTS session(key TEXT PRIMARY KEY, value TEXT)");
                tx.executeSql("INSERT OR REPLACE INTO session(key, value) VALUES(?, ?)", ["current", JSON.stringify(state)]);
            });
            return true;
        } catch (error) {
            console.warn("CrossFlow: session save failed", error);
            return false;
        }
    }
    function load() {
        try {
            const db = root.database();
            let result = null;
            db.transaction(function(tx) {
                tx.executeSql("CREATE TABLE IF NOT EXISTS session(key TEXT PRIMARY KEY, value TEXT)");
                result = tx.executeSql("SELECT value FROM session WHERE key = ?", ["current"]);
            });
            if (result && result.rows.length > 0) {
                return JSON.parse(result.rows.item(0).value);
            }
        } catch (error) {
            console.warn("CrossFlow: session load failed", error);
        }
        return null;
    }
    function clear() {
        try {
            root.database().transaction(function(tx) {
                tx.executeSql("DELETE FROM session WHERE key = ?", ["current"]);
            });
        } catch (error) {
            console.warn("CrossFlow: session clear failed", error);
        }
    }
}
