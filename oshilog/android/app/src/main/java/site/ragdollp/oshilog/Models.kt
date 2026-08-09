package site.ragdollp.oshilog

import java.util.Calendar

// ---------- data model ----------

data class Oshi(
    val id: String,
    var name: String,
    var emoji: String = "💜",
    var color: Long = 0xFF9A80D8,
    var birthday: String = "",   // "MM-DD"
    var group: String = "",
    var memo: String = ""
)

data class EventItem(
    val id: String,
    var title: String,
    var kind: String = "ライブ",
    var oshiId: String? = null,
    var date: String,            // "YYYY-MM-DD"
    var place: String = "",
    var memo: String = ""
)

data class Ticket(
    val id: String,
    var title: String,
    var oshiId: String? = null,
    var date: String,
    var resultDate: String = "",
    var place: String = "",
    var seat: String = "",
    var amount: Int? = null,
    var status: String = "applied"  // applied / won / lost / paid
)

data class Expense(
    val id: String,
    var amount: Int,
    var category: String = "other",
    var oshiId: String? = null,
    var date: String,
    var memo: String = ""
)

data class Profile(
    var name: String = "推し活ユーザー",
    var emoji: String = "🙆‍♀️",
    var themeColor: Long = 0xFFFF3D8B,
    var welcomed: Boolean = false
)

data class OshiData(
    var profile: Profile = Profile(),
    var oshi: MutableList<Oshi> = mutableListOf(),
    var events: MutableList<EventItem> = mutableListOf(),
    var tickets: MutableList<Ticket> = mutableListOf(),
    var expenses: MutableList<Expense> = mutableListOf()
)

// ---------- constants ----------

data class Cat(val id: String, val name: String, val color: Long)

val CATS = listOf(
    Cat("ticket", "チケット", 0xFFA98FE6),
    Cat("goods", "グッズ", 0xFFC9A8EF),
    Cat("cd", "CD・DVD", 0xFFB0A0EA),
    Cat("travel", "遠征交通費", 0xFFE0A8D8),
    Cat("hotel", "ホテル", 0xFFEFB0D0),
    Cat("food", "飲食", 0xFFF2A6C4),
    Cat("beauty", "美容", 0xFFF6B6AC),
    Cat("other", "その他", 0xFFD7C6F0)
)
fun catOf(id: String): Cat = CATS.firstOrNull { it.id == id } ?: CATS.last()

val OSHI_COLORS = listOf(
    0xFF9A80D8, 0xFFFF3D8B, 0xFF4F9DF0, 0xFF2FB98A,
    0xFFF2913A, 0xFFE0554F, 0xFF7EC8F0, 0xFFB98FE0
)
val OSHI_EMOJIS = listOf("💜", "💙", "💚", "💛", "🧡", "❤️", "🩷", "⭐", "🌟", "🎤", "🎸", "👑")

val EVENT_KINDS = listOf("ライブ", "コンサート", "フェス", "握手会", "ファンミ", "発売日", "応募締切", "当落発表", "入金期限", "その他")

val TICKET_STATUS = listOf(
    "applied" to "応募済み",
    "won" to "当選",
    "lost" to "落選",
    "paid" to "入金済み"
)
fun statusLabel(s: String): String = TICKET_STATUS.firstOrNull { it.first == s }?.second ?: "応募済み"

// ---------- date / format helpers ----------

object D {
    private val WD = arrayOf("日", "月", "火", "水", "木", "金", "土")

    fun pad(n: Int) = if (n < 10) "0$n" else "$n"

    fun todayStr(): String {
        val c = Calendar.getInstance()
        return "${c.get(Calendar.YEAR)}-${pad(c.get(Calendar.MONTH) + 1)}-${pad(c.get(Calendar.DAY_OF_MONTH))}"
    }

    fun parse(s: String): Calendar {
        val p = s.split("-")
        val c = Calendar.getInstance()
        c.clear()
        c.set(
            p.getOrNull(0)?.toIntOrNull() ?: 2025,
            (p.getOrNull(1)?.toIntOrNull() ?: 1) - 1,
            p.getOrNull(2)?.toIntOrNull() ?: 1
        )
        return c
    }

    private fun midnight(c: Calendar): Long {
        val x = c.clone() as Calendar
        x.set(Calendar.HOUR_OF_DAY, 0); x.set(Calendar.MINUTE, 0)
        x.set(Calendar.SECOND, 0); x.set(Calendar.MILLISECOND, 0)
        return x.timeInMillis
    }

    fun daysUntil(s: String): Int {
        val today = midnight(Calendar.getInstance())
        val d = midnight(parse(s))
        return Math.round((d - today) / 86400000.0).toInt()
    }

    /** days until next occurrence of a MM-DD birthday, or null */
    fun daysToBirthday(mmdd: String): Int? {
        if (mmdd.isBlank()) return null
        val p = mmdd.split("-")
        if (p.size < 2) return null
        val mo = (p[0].toIntOrNull() ?: return null) - 1
        val da = p[1].toIntOrNull() ?: return null
        val today = Calendar.getInstance()
        val t = midnight(today)
        val next = Calendar.getInstance()
        next.clear(); next.set(today.get(Calendar.YEAR), mo, da)
        if (midnight(next) < t) next.set(Calendar.YEAR, today.get(Calendar.YEAR) + 1)
        return Math.round((midnight(next) - t) / 86400000.0).toInt()
    }

    fun jDate(s: String): String {
        val c = parse(s)
        return "${c.get(Calendar.MONTH) + 1}/${c.get(Calendar.DAY_OF_MONTH)}（${WD[c.get(Calendar.DAY_OF_WEEK) - 1]}）"
    }

    fun inMonth(s: String, y: Int, m: Int): Boolean {
        val c = parse(s); return c.get(Calendar.YEAR) == y && c.get(Calendar.MONTH) == m
    }

    fun inYear(s: String, y: Int): Boolean = parse(s).get(Calendar.YEAR) == y

    fun yen(n: Int): String = "%,d 円".format(n)

    fun monthKey(y: Int, m: Int) = "$y-${pad(m + 1)}"
    fun dayStr(y: Int, m: Int, d: Int) = "$y-${pad(m + 1)}-${pad(d)}"
}
