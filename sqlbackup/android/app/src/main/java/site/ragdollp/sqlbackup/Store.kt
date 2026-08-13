package site.ragdollp.sqlbackup

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 接続プロファイルと操作履歴を端末内 SharedPreferences (JSON) に保存する。 */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("sqlbackup_store", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun loadConnections(): List<ConnectionProfile> {
        val json = prefs.getString(KEY_CONNECTIONS, null) ?: return emptyList()
        val type = object : TypeToken<List<ConnectionProfile>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveConnections(list: List<ConnectionProfile>) {
        prefs.edit().putString(KEY_CONNECTIONS, gson.toJson(list)).apply()
    }

    fun upsertConnection(profile: ConnectionProfile) {
        val list = loadConnections().toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        saveConnections(list)
    }

    fun deleteConnection(id: String) {
        saveConnections(loadConnections().filterNot { it.id == id })
    }

    fun loadHistory(): List<HistoryEntry> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        val type = object : TypeToken<List<HistoryEntry>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addHistory(entry: HistoryEntry) {
        val list = (listOf(entry) + loadHistory()).take(200)
        prefs.edit().putString(KEY_HISTORY, gson.toJson(list)).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    companion object {
        private const val KEY_CONNECTIONS = "connections"
        private const val KEY_HISTORY = "history"
    }
}
