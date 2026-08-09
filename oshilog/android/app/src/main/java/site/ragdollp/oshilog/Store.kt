package site.ragdollp.oshilog

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import java.util.Calendar

/**
 * 端末内(SharedPreferences)にJSONで保存する簡易ストア。
 * 変更のたびに revision を進め、Compose 側はそれを読んで再描画する。
 */
class Store(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("oshilog", Context.MODE_PRIVATE)
    private val gson = Gson()

    var data: OshiData = load()
        private set

    // Compose が購読する再描画トリガ
    var revision by mutableIntStateOf(0)
        private set

    private fun load(): OshiData {
        val s = prefs.getString("data", null) ?: return OshiData()
        return try {
            val d = gson.fromJson(s, OshiData::class.java) ?: OshiData()
            // Gson は未定義フィールドを null にすることがあるため補正
            if (d.profile == null) d.profile = Profile()
            if (d.oshi == null) d.oshi = mutableListOf()
            if (d.events == null) d.events = mutableListOf()
            if (d.tickets == null) d.tickets = mutableListOf()
            if (d.expenses == null) d.expenses = mutableListOf()
            if (d.profile.themeColor == 0L) d.profile.themeColor = 0xFFFF3D8B
            d
        } catch (e: Exception) {
            OshiData()
        }
    }

    fun save() {
        prefs.edit().putString("data", gson.toJson(data)).apply()
        revision++
    }

    fun replaceAll(newData: OshiData) {
        data = newData
        save()
    }

    fun newId(): String =
        System.currentTimeMillis().toString(36) + (Math.random() * 1e6).toInt().toString(36)

    // ---------- upserts / deletes ----------

    fun upsertOshi(o: Oshi) { val i = data.oshi.indexOfFirst { it.id == o.id }; if (i >= 0) data.oshi[i] = o else data.oshi.add(o); save() }
    fun deleteOshi(id: String) { data.oshi.removeAll { it.id == id }; save() }

    fun upsertEvent(e: EventItem) { val i = data.events.indexOfFirst { it.id == e.id }; if (i >= 0) data.events[i] = e else data.events.add(e); save() }
    fun deleteEvent(id: String) { data.events.removeAll { it.id == id }; save() }

    fun upsertTicket(t: Ticket) { val i = data.tickets.indexOfFirst { it.id == t.id }; if (i >= 0) data.tickets[i] = t else data.tickets.add(t); save() }
    fun deleteTicket(id: String) { data.tickets.removeAll { it.id == id }; save() }

    fun upsertExpense(x: Expense) { val i = data.expenses.indexOfFirst { it.id == x.id }; if (i >= 0) data.expenses[i] = x else data.expenses.add(x); save() }
    fun deleteExpense(id: String) { data.expenses.removeAll { it.id == id }; save() }

    fun setTheme(color: Long) { data.profile.themeColor = color; save() }
    fun markWelcomed() { data.profile.welcomed = true; save() }

    // ---------- lookups ----------

    fun oshiById(id: String?): Oshi? = if (id == null) null else data.oshi.firstOrNull { it.id == id }
    fun oshiName(id: String?): String = oshiById(id)?.name ?: ""
    fun oshiEmoji(id: String?): String = oshiById(id)?.emoji ?: "🎤"
    fun oshiColor(id: String?): Long = oshiById(id)?.color ?: 0xFFB19BE0

    // ---------- aggregates ----------

    fun sumMonth(y: Int, m: Int): Int = data.expenses.filter { D.inMonth(it.date, y, m) }.sumOf { it.amount }
    fun sumYear(y: Int): Int = data.expenses.filter { D.inYear(it.date, y) }.sumOf { it.amount }

    fun upcomingEvents(): List<EventItem> =
        data.events.filter { D.daysUntil(it.date) >= 0 }.sortedBy { it.date }

    // ---------- sample data (idempotent: replaces) ----------

    fun seedSample() {
        val theme = data.profile.themeColor
        val nd = OshiData()
        nd.profile.welcomed = true
        nd.profile.themeColor = theme

        val c = Calendar.getInstance()
        val y = c.get(Calendar.YEAR); val m = c.get(Calendar.MONTH)
        val last = Calendar.getInstance().apply { set(y, m + 1, 0) }.get(Calendar.DAY_OF_MONTH)
        fun ds(day: Int) = D.dayStr(y, m, minOf(day, last))

        val o1 = Oshi(newId(), "みお", "💜", 0xFF9A80D8, "07-15", "Stellar", "最推し")
        val o2 = Oshi(newId(), "れん", "💙", 0xFF4F9DF0, "${D.pad(m + 1)}-${D.pad(minOf(22, last))}", "Stellar", "")
        nd.oshi.addAll(listOf(o1, o2))

        nd.events.addAll(listOf(
            EventItem(newId(), "Stellar ARENA LIVE 2025", "ライブ", o1.id, ds(20), "○○アリーナ", "開場17:00 / 開演18:00"),
            EventItem(newId(), "みお 生誕祭イベント", "ファンミ", o1.id, ds(28), "パシフィコ横浜", "")
        ))
        nd.tickets.addAll(listOf(
            Ticket(newId(), "ARENA LIVE 大阪公演", o1.id, ds(24), ds(10), "大阪城ホール", "アリーナ B7", 9800, "won"),
            Ticket(newId(), "ARENA LIVE 東京公演", o2.id, ds(30), ds(12), "東京ドーム", "", 9800, "applied")
        ))
        nd.expenses.addAll(listOf(
            Expense(newId(), 9800, "ticket", o1.id, ds(3), "大阪公演 チケット"),
            Expense(newId(), 4620, "goods", o1.id, ds(6), "アクスタ・ペンライト"),
            Expense(newId(), 3300, "cd", o2.id, ds(9), "新譜 初回盤"),
            Expense(newId(), 2800, "travel", o1.id, ds(20), "新幹線（大阪）"),
            Expense(newId(), 8500, "hotel", o1.id, ds(20), "大阪 1泊"),
            Expense(newId(), 1200, "food", o1.id, ds(20), "推しカフェ")
        ))
        replaceAll(nd)
    }

    fun resetAll() {
        val nd = OshiData()
        nd.profile.welcomed = true
        replaceAll(nd)
    }
}
