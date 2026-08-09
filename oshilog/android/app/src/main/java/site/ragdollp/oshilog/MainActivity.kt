package site.ragdollp.oshilog

import android.app.Activity
import android.app.DatePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import java.util.Calendar

// ============================ palette ============================
private val BG = Color(0xFFF6F0FB)
private val CARD = Color(0xFFFFFFFF)
private val INK = Color(0xFF4A4458)
private val INK_SOFT = Color(0xFF7C7690)
private val INK_FAINT = Color(0xFF9A94AC)
private val PURPLE = Color(0xFF9A80D8)
private val PURPLE_DEEP = Color(0xFF7B62C6)
private val LINE = Color(0xFFF0EAF8)
private val FIELD = Color(0xFFF7F2FC)

// ============================ Activity + Ads ============================

class MainActivity : ComponentActivity() {
    private lateinit var ads: InterstitialManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileAds.initialize(this) {}
        ads = InterstitialManager(this)
        ads.load()
        val store = Store(this)
        setContent {
            App(store, onBreak = { ads.maybeShow() })
        }
    }
}

/** インタースティシャル(全画面)広告。画面の区切りで呼ばれ、前回から5分経過時のみ表示。 */
class InterstitialManager(private val activity: Activity) {
    private val adUnitId = "ca-app-pub-8357981710510236/9323149768"
    private val cooldownMs = 5 * 60 * 1000L
    private var ad: InterstitialAd? = null
    private var loading = false
    private var lastAt = System.currentTimeMillis()   // 起動直後は出さない

    fun load() {
        if (loading || ad != null) return
        loading = true
        InterstitialAd.load(activity, adUnitId, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(a: InterstitialAd) { ad = a; loading = false }
                override fun onAdFailedToLoad(e: LoadAdError) { ad = null; loading = false }
            })
    }

    fun maybeShow() {
        if (System.currentTimeMillis() - lastAt < cooldownMs) return
        val a = ad ?: run { load(); return }
        a.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { ad = null; load() }
            override fun onAdFailedToShowFullScreenContent(e: AdError) { ad = null; load() }
        }
        lastAt = System.currentTimeMillis()
        a.show(activity)
    }
}

// ============================ dialogs / forms state ============================

private data class Confirm(val msg: String, val yes: String, val danger: Boolean, val onYes: () -> Unit)
private data class Prefill(val title: String? = null, val place: String? = null, val kind: String? = null, val oshiId: String? = null, val oshiName: String? = null)
private data class FormTarget(val type: String, val id: String?, val prefill: Prefill? = null)   // type: event/ticket/expense/oshi

// ============================ root App ============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(store: Store, onBreak: () -> Unit) {
    val rev = store.revision            // subscribe → recompose on any data change
    val accent = Color(store.data.profile.themeColor)

    var tab by remember { mutableStateOf(0) }               // 0 home 1 cal 2 rec 3 my
    var showAdd by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf<FormTarget?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    var showWelcome by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!store.data.profile.welcomed) { showWelcome = true; store.markWelcomed() }
    }

    fun switchTab(t: Int) { tab = t; onBreak() }

    MaterialTheme(colorScheme = lightColorScheme(primary = accent)) {
        Scaffold(
            containerColor = BG,
            topBar = { TopBar(tab, onSearch = { showSearch = true }, onHelp = { showHelp = true }) },
            bottomBar = {
                BottomBar(tab, accent, onTab = { switchTab(it) }, onAdd = { showAdd = true })
            }
        ) { pad ->
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = 24.dp)
            ) {
                when (tab) {
                    0 -> HomeScreen(store, accent, onAdd = { form = FormTarget(it, null) }, onEdit = { t, id -> form = FormTarget(t, id) }, goTab = { switchTab(it) })
                    1 -> CalendarScreen(store, onAdd = { form = FormTarget(it, null) }, onEdit = { t, id -> form = FormTarget(t, id) })
                    2 -> RecordScreen(store, onAdd = { form = FormTarget(it, null) }, onEdit = { t, id -> form = FormTarget(t, id) })
                    3 -> MyPageScreen(store, accent,
                        onAdd = { form = FormTarget(it, null) },
                        onEdit = { t, id -> form = FormTarget(t, id) },
                        onHelp = { showHelp = true },
                        onSeed = {
                            confirm = if (store.data.oshi.isNotEmpty() || store.data.expenses.isNotEmpty() || store.data.events.isNotEmpty() || store.data.tickets.isNotEmpty())
                                Confirm("現在のデータをサンプルで置き換えますか？\n（今の内容は削除されます）", "置き換える", true) { store.seedSample() }
                            else Confirm("サンプルデータを追加しますか？", "追加する", false) { store.seedSample() }
                        },
                        onReset = { confirm = Confirm("すべてのデータを消去しますか？\nこの操作は取り消せません。", "消去する", true) { store.resetAll() } }
                    )
                }
            }
        }

        if (showAdd) AddSheet(
            onDismiss = { showAdd = false },
            onPick = { showAdd = false; form = FormTarget(it, null) },
            onSearch = { showAdd = false; showSearch = true }
        )

        if (showSearch) SearchSheet(store, onDismiss = { showSearch = false }, onRegister = { showSearch = false; form = it })

        form?.let { ft ->
            FormSheet(store, ft,
                onClose = { form = null },
                onSaved = { form = null; onBreak() },
                onDelete = { msg, act -> confirm = Confirm(msg, "削除する", true) { act(); form = null } }
            )
        }

        confirm?.let { c ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                confirmButton = {
                    TextButton(onClick = { c.onYes(); confirm = null }) {
                        Text(c.yes, color = if (c.danger) Color(0xFFF5227D) else PURPLE_DEEP, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("キャンセル", color = INK_SOFT) } },
                text = { Text(c.msg, color = INK) },
                containerColor = CARD
            )
        }

        if (showHelp) HelpDialog { showHelp = false }

        if (showWelcome) AlertDialog(
            onDismissRequest = { showWelcome = false },
            title = { Text("💜 OshiLog へようこそ", fontWeight = FontWeight.Bold) },
            text = { Text("推し活の予定・チケット・支出・推し情報を、これひとつでまとめて管理できます。\n\nまずは下中央の「＋」から追加するか、サンプルで試してみてください。", color = INK_SOFT) },
            confirmButton = { TextButton(onClick = { showWelcome = false; store.seedSample() }) { Text("サンプルで試す", color = accent, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { showWelcome = false }) { Text("自分で始める", color = INK_SOFT) } },
            containerColor = CARD
        )
    }
}

// ============================ chrome ============================

@Composable
private fun TopBar(tab: Int, onSearch: () -> Unit, onHelp: () -> Unit) {
    val title = when (tab) { 0 -> "ホーム"; 1 -> "カレンダー"; 2 -> "記録"; else -> "マイページ" }
    Surface(color = BG) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text("🔎", Modifier.align(Alignment.CenterStart).clickable { onSearch() }, fontSize = 18.sp)
            Text(title, Modifier.align(Alignment.Center), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF3F3A4A))
            Text("？", Modifier.align(Alignment.CenterEnd).clickable { onHelp() }, fontSize = 18.sp, color = INK_SOFT)
        }
    }
}

@Composable
private fun BottomBar(current: Int, accent: Color, onTab: (Int) -> Unit, onAdd: () -> Unit) {
    Surface(color = Color.White, shadowElevation = 10.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem(Modifier.weight(1f), "🏠", "ホーム", current == 0, accent) { onTab(0) }
            NavItem(Modifier.weight(1f), "📅", "カレンダー", current == 1, accent) { onTab(1) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(56.dp).offset(y = (-14).dp).clip(CircleShape).background(accent).clickable { onAdd() },
                    contentAlignment = Alignment.Center
                ) { Text("＋", color = Color.White, fontSize = 28.sp) }
            }
            NavItem(Modifier.weight(1f), "📝", "記録", current == 2, accent) { onTab(2) }
            NavItem(Modifier.weight(1f), "👤", "マイページ", current == 3, accent) { onTab(3) }
        }
    }
}

@Composable
private fun NavItem(modifier: Modifier, icon: String, label: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    Column(modifier.clickable { onClick() }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 20.sp)
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (active) PURPLE_DEEP else Color(0xFFB3AAC6))
    }
}

// ============================ shared bits ============================

@Composable
private fun OCard(modifier: Modifier = Modifier, bg: Color = CARD, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = bg, shape = RoundedCornerShape(18.dp), shadowElevation = 2.dp, modifier = modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun SectionH(label: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp, start = 2.dp, end = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = INK_FAINT, fontWeight = FontWeight.Bold)
        if (action != null) Text(action, fontSize = 11.sp, color = PURPLE_DEEP, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onAction?.invoke() })
    }
}

@Composable
private fun EmptyState(msg: String, cta: String, onCta: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(msg, color = INK_FAINT, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(cta, color = PURPLE_DEEP, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onCta() })
    }
}

@Composable
private fun EmojiThumb(emoji: String, color: Color, size: Int = 52) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(Brush.linearGradient(listOf(color, Color(0xFFC9A8EF)))), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = (size / 2.2).sp)
    }
}

// ============================ HOME ============================

@Composable
private fun HomeScreen(store: Store, accent: Color, onAdd: (String) -> Unit, onEdit: (String, String) -> Unit, goTab: (Int) -> Unit) {
    val now = Calendar.getInstance()
    val y = now.get(Calendar.YEAR); val m = now.get(Calendar.MONTH)
    val monthSpend = store.sumMonth(y, m)
    val yearSpend = store.sumYear(y)
    val up = store.upcomingEvents()
    val next = up.firstOrNull()
    val evCount = store.data.events.count { D.inMonth(it.date, y, m) }

    // nearest birthday
    var bdayOshi: Oshi? = null; var bdayDays = Int.MAX_VALUE
    store.data.oshi.forEach { o -> D.daysToBirthday(o.birthday)?.let { if (it < bdayDays) { bdayDays = it; bdayOshi = o } } }

    val empty = store.data.oshi.isEmpty() && store.data.events.isEmpty() && store.data.tickets.isEmpty() && store.data.expenses.isEmpty()
    if (empty) {
        OCard(bg = Color(0xFFFDEEF4)) {
            Text("👋 ようこそ！", fontWeight = FontWeight.Bold, color = INK)
            Spacer(Modifier.height(4.dp))
            Text("下の「＋」から推しや予定を追加できます。", color = INK_SOFT, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text("✨ サンプルで試す", color = PURPLE_DEEP, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { store.seedSample() })
        }
    }

    // countdown hero
    if (next != null) {
        val dn = D.daysUntil(next.date)
        Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { onEdit("event", next.id) }) {
            Column(Modifier.background(Brush.linearGradient(listOf(accent, Color(0xFFB98FE0)))).padding(16.dp)) {
                Text("次の予定まで", color = Color.White.copy(alpha = .92f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        (if (store.oshiName(next.oshiId).isNotEmpty()) store.oshiName(next.oshiId) + " / " else "") + next.title,
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(if (dn == 0) "当日" else "あと$dn", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                    if (dn > 0) Text("日", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Text(D.jDate(next.date) + (if (next.place.isNotEmpty()) " ・ " + next.place else ""), color = Color.White.copy(alpha = .9f), fontSize = 11.sp)
            }
        }
    }

    // money card
    OCard(bg = Color(0xFFEDE6FB)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmojiThumb("💳", Color(0xFFB9A6EC), 40)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("今月の推し活費", color = INK_FAINT, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(D.yen(monthSpend), color = INK, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                Text("今年の合計 " + D.yen(yearSpend), color = INK_FAINT, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    // duo
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard(Modifier.weight(1f), Color(0xFFFDEEF4), "📅", "次のライブまで", if (next != null) "${D.daysUntil(next.date)}" else "—", if (next != null) "日" else "")
        StatCard(Modifier.weight(1f), Color(0xFFF1ECFB), "⭐", "今月のイベント", "$evCount", "件")
    }

    bdayOshi?.let { o ->
        SectionH("誕生日カウントダウン")
        Surface(shape = RoundedCornerShape(18.dp), shadowElevation = 2.dp, color = CARD, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { onEdit("oshi", o.id) }) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                EmojiThumb("🎂", Color(o.color), 44)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("推しの誕生日まで", color = INK_FAINT, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("${o.name} の誕生日", color = INK, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                }
                Text(if (bdayDays == 0) "今日🎉" else "あと${bdayDays}日", color = Color(o.color), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }

    SectionH("次の予定", "カレンダー ›") { goTab(1) }
    if (next != null) {
        RowCard(onClick = { onEdit("event", next.id) }) {
            EmojiThumb(store.oshiEmoji(next.oshiId), Color(store.oshiColor(next.oshiId)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(D.jDate(next.date), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = INK)
                Text(next.title, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = INK)
                if (next.place.isNotEmpty()) Text("会場：" + next.place, fontSize = 12.sp, color = INK_FAINT)
            }
            Text("›", color = Color(0xFFC8C1DA), fontSize = 20.sp)
        }
    } else OCard { EmptyState("予定はまだありません", "＋ 予定を追加する") { onAdd("event") } }

    SectionH("チケット状況")
    val tk = store.data.tickets.sortedByDescending { it.date }.firstOrNull()
    if (tk != null) {
        RowCard(onClick = { onEdit("ticket", tk.id) }) {
            EmojiThumb("🎫", Color(0xFFA98FE6))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tk.title, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = INK)
                    Spacer(Modifier.width(6.dp))
                    StatusBadge(tk.status, accent)
                }
                Text(D.jDate(tk.date) + (if (tk.place.isNotEmpty()) "　" + tk.place else ""), fontSize = 12.sp, color = INK_FAINT)
            }
            Text("›", color = Color(0xFFC8C1DA), fontSize = 20.sp)
        }
    } else OCard { EmptyState("チケットはまだありません", "＋ チケットを追加する") { onAdd("ticket") } }
}

@Composable
private fun StatCard(modifier: Modifier, bg: Color, icon: String, label: String, value: String, unit: String) {
    Surface(color = bg, shape = RoundedCornerShape(18.dp), shadowElevation = 2.dp, modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(icon, fontSize = 20.sp)
            Text(label, color = INK_FAINT, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, color = INK, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                if (unit.isNotEmpty()) Text(unit, color = INK, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RowCard(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Surface(color = CARD, shape = RoundedCornerShape(18.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { onClick() }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun StatusBadge(status: String, accent: Color) {
    val (txt, col) = when (status) {
        "won" -> "当選🎉" to accent
        "lost" -> "落選" to INK_FAINT
        "paid" -> "入金済み✅" to Color(0xFF3AA981)
        else -> "応募済み" to PURPLE_DEEP
    }
    Text(txt, color = col, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
}

// ============================ CALENDAR ============================

@Composable
private fun CalendarScreen(store: Store, onAdd: (String) -> Unit, onEdit: (String, String) -> Unit) {
    val now = Calendar.getInstance()
    var y by remember { mutableStateOf(now.get(Calendar.YEAR)) }
    var m by remember { mutableStateOf(now.get(Calendar.MONTH)) }

    MonthNav("$y 年 ${m + 1} 月",
        onPrev = { if (m == 0) { m = 11; y-- } else m-- },
        onNext = { if (m == 11) { m = 0; y++ } else m++ })

    val firstDow = Calendar.getInstance().apply { clear(); set(y, m, 1) }.get(Calendar.DAY_OF_WEEK) - 1
    val days = Calendar.getInstance().apply { set(y, m + 1, 0) }.get(Calendar.DAY_OF_MONTH)
    val evDays = store.data.events.filter { D.inMonth(it.date, y, m) }.map { D.parse(it.date).get(Calendar.DAY_OF_MONTH) }.toSet()
    val tkDays = store.data.tickets.filter { D.inMonth(it.date, y, m) }.map { D.parse(it.date).get(Calendar.DAY_OF_MONTH) }.toSet()
    val bdDays = store.data.oshi.mapNotNull { o -> o.birthday.split("-").let { if (it.size >= 2 && (it[0].toIntOrNull() ?: 0) - 1 == m) it[1].toIntOrNull() else null } }.toSet()
    val today = D.todayStr()

    OCard {
        Row(Modifier.fillMaxWidth()) {
            listOf("日", "月", "火", "水", "木", "金", "土").forEachIndexed { i, w ->
                Text(w, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = when (i) { 0 -> Color(0xFFE690B3); 6 -> Color(0xFF8FA8E0); else -> INK_FAINT })
            }
        }
        val cells = ArrayList<Int?>()
        repeat(firstDow) { cells.add(null) }
        for (d in 1..days) cells.add(d)
        while (cells.size % 7 != 0) cells.add(null)
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    Box(Modifier.weight(1f).padding(vertical = 3.dp), contentAlignment = Alignment.Center) {
                        if (day == null) { Spacer(Modifier.height(38.dp)) } else {
                            val ds = D.dayStr(y, m, day)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier.size(30.dp).clip(CircleShape)
                                        .then(if (ds == today) Modifier.border(2.dp, PURPLE, CircleShape) else Modifier),
                                    contentAlignment = Alignment.Center
                                ) { Text("$day", fontSize = 14.sp, color = Color(0xFF5C5670)) }
                                Row(Modifier.height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    if (evDays.contains(day)) Dot(Color(0xFFC9A8EF))
                                    if (tkDays.contains(day)) Dot(Color(0xFFFF3D8B))
                                    if (bdDays.contains(day)) Dot(Color(0xFFF6B83A))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    Text("🟣 予定　🔴 チケット　🟡 誕生日", color = INK_FAINT, fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))

    val items = (store.data.events.filter { D.inMonth(it.date, y, m) }.map { "event" to it.id to it.date } +
            store.data.tickets.filter { D.inMonth(it.date, y, m) }.map { "ticket" to it.id to it.date })
        .sortedBy { it.second }

    if (items.isEmpty()) {
        OCard { EmptyState("この月の予定はありません", "＋ 予定を追加する") { onAdd("event") } }
    } else {
        items.forEach { triple ->
            val type = triple.first.first; val id = triple.first.second
            if (type == "event") {
                val e = store.data.events.first { it.id == id }
                RowCard(onClick = { onEdit("event", id) }) {
                    EmojiThumb(store.oshiEmoji(e.oshiId), Color(store.oshiColor(e.oshiId)), 44)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(D.jDate(e.date), fontSize = 12.sp, color = INK_FAINT, fontWeight = FontWeight.Bold)
                        Text(e.title, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = INK)
                        if (e.place.isNotEmpty()) Text(e.place, fontSize = 12.sp, color = Color(0xFFB3AAC6))
                    }
                }
            } else {
                val t = store.data.tickets.first { it.id == id }
                RowCard(onClick = { onEdit("ticket", id) }) {
                    EmojiThumb("🎫", Color(0xFFA98FE6), 44)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(D.jDate(t.date), fontSize = 12.sp, color = INK_FAINT, fontWeight = FontWeight.Bold)
                        Text(t.title + " ・ " + statusLabel(t.status), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = INK)
                        if (t.place.isNotEmpty()) Text(t.place, fontSize = 12.sp, color = Color(0xFFB3AAC6))
                    }
                }
            }
        }
    }
}

@Composable private fun Dot(c: Color) { Box(Modifier.size(5.dp).clip(CircleShape).background(c)) }

@Composable
private fun MonthNav(label: String, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text("‹", Modifier.clickable { onPrev() }.padding(horizontal = 14.dp), fontSize = 22.sp, color = Color(0xFFB7AECD))
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = INK)
        Text("›", Modifier.clickable { onNext() }.padding(horizontal = 14.dp), fontSize = 22.sp, color = Color(0xFFB7AECD))
    }
}

// ============================ RECORD ============================

@Composable
private fun RecordScreen(store: Store, onAdd: (String) -> Unit, onEdit: (String, String) -> Unit) {
    val now = Calendar.getInstance()
    var y by remember { mutableStateOf(now.get(Calendar.YEAR)) }
    var m by remember { mutableStateOf(now.get(Calendar.MONTH)) }

    MonthNav("$y 年 ${m + 1} 月",
        onPrev = { if (m == 0) { m = 11; y-- } else m-- },
        onNext = { if (m == 11) { m = 0; y++ } else m++ })

    val total = store.sumMonth(y, m)
    val prevCal = Calendar.getInstance().apply { set(y, m, 1); add(Calendar.MONTH, -1) }
    val prev = store.sumMonth(prevCal.get(Calendar.YEAR), prevCal.get(Calendar.MONTH))
    val diff = total - prev
    val days = Calendar.getInstance().apply { set(y, m + 1, 0) }.get(Calendar.DAY_OF_MONTH)
    val perDay = (1..days).map { d -> store.data.expenses.filter { it.date == D.dayStr(y, m, d) }.sumOf { it.amount } }

    OCard {
        Text("今月の合計", color = INK_FAINT, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(D.yen(total), color = INK, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(10.dp))
            if (diff != 0) {
                val up = diff > 0
                Surface(color = if (up) Color(0xFFFDE6EF) else Color(0xFFE8F0FF), shape = RoundedCornerShape(20.dp)) {
                    Text((if (up) "前月比 +" else "前月比 ") + "%,d".format(diff), color = if (up) Color(0xFFF5227D) else Color(0xFF5F7FD0),
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        BarChart(perDay)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("1日", "8日", "15日", "22日", "${days}日").forEach { Text(it, fontSize = 11.sp, color = Color(0xFFB3AAC6)) }
        }
    }

    SectionH("カテゴリ別")
    val byCat = CATS.associate { c -> c to store.data.expenses.filter { D.inMonth(it.date, y, m) && it.category == c.id }.sumOf { it.amount } }
    if (byCat.values.all { it == 0 }) {
        OCard { EmptyState("この月の記録はありません", "＋ 支出を追加する") { onAdd("expense") } }
    } else {
        OCard {
            CATS.filter { (byCat[it] ?: 0) > 0 }.forEachIndexed { i, c ->
                if (i > 0) HorizontalDivider(color = LINE, thickness = 1.dp)
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c.color)))
                    Spacer(Modifier.width(10.dp))
                    Text(c.name, color = Color(0xFF5C5670), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(D.yen(byCat[c] ?: 0), fontWeight = FontWeight.ExtraBold, color = INK)
                }
            }
        }
    }

    val exps = store.data.expenses.filter { D.inMonth(it.date, y, m) }.sortedByDescending { it.date }
    if (exps.isNotEmpty()) {
        SectionH("明細（タップで編集）")
        OCard {
            exps.forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider(color = LINE, thickness = 1.dp)
                Row(Modifier.fillMaxWidth().clickable { onEdit("expense", e.id) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(catOf(e.category).color)))
                    Spacer(Modifier.width(10.dp))
                    Text(D.jDate(e.date) + " ・ " + catOf(e.category).name + (if (e.memo.isNotEmpty()) " ・ " + e.memo else ""),
                        color = Color(0xFF5C5670), fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(D.yen(e.amount), fontWeight = FontWeight.ExtraBold, color = INK, fontSize = 14.sp)
                }
            }
        }
    }

    val yearTotal = store.sumYear(y)
    SectionH("$y 年の推し別合計", "計 " + D.yen(yearTotal))
    val byOshi = store.data.expenses.filter { D.inYear(it.date, y) }.groupBy { it.oshiId }.mapValues { it.value.sumOf { e -> e.amount } }
    OCard {
        if (byOshi.isEmpty()) EmptyState("今年の記録はありません", "＋ 支出を追加する") { onAdd("expense") }
        else byOshi.entries.sortedByDescending { it.value }.forEachIndexed { i, (oid, sum) ->
            if (i > 0) HorizontalDivider(color = LINE, thickness = 1.dp)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text(if (oid == null) "推し未設定" else store.oshiEmoji(oid) + " " + store.oshiName(oid), modifier = Modifier.weight(1f), color = INK)
                Text(D.yen(sum), fontWeight = FontWeight.ExtraBold, color = INK)
            }
        }
    }
}

@Composable
private fun BarChart(values: List<Int>) {
    val maxV = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(120.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        values.forEach { v ->
            val frac = if (v > 0) (v.toFloat() / maxV).coerceIn(0.03f, 1f) else 0f
            Box(Modifier.weight(1f).fillMaxHeight(frac).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(Brush.verticalGradient(listOf(Color(0xFFC9A8EF), Color(0xFFA98FE6)))))
        }
    }
}

// ============================ MYPAGE ============================

@Composable
private fun MyPageScreen(store: Store, accent: Color, onAdd: (String) -> Unit, onEdit: (String, String) -> Unit, onHelp: () -> Unit, onSeed: () -> Unit, onReset: () -> Unit) {
    val recCount = store.data.events.size + store.data.tickets.size + store.data.expenses.size
    OCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(60.dp).clip(CircleShape).background(Color(0xFFF3E3F2)), contentAlignment = Alignment.Center) { Text(store.data.profile.emoji, fontSize = 30.sp) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(store.data.profile.name, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = INK)
                Text("推し ${store.data.oshi.size} ・ 記録 $recCount 件", fontSize = 12.sp, color = INK_FAINT)
            }
        }
    }

    SectionH("推し", "＋ 追加") { onAdd("oshi") }
    OCard {
        if (store.data.oshi.isEmpty()) EmptyState("推しはまだ登録されていません", "＋ 推しを登録する") { onAdd("oshi") }
        else store.data.oshi.forEachIndexed { i, o ->
            if (i > 0) HorizontalDivider(color = LINE, thickness = 1.dp)
            Row(Modifier.fillMaxWidth().clickable { onEdit("oshi", o.id) }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Color(o.color)), contentAlignment = Alignment.Center) { Text(o.emoji, fontSize = 19.sp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(o.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = INK)
                    val meta = buildList { if (o.group.isNotEmpty()) add(o.group); if (o.birthday.isNotEmpty()) add("🎂" + o.birthday) }
                    if (meta.isNotEmpty()) Text(meta.joinToString(" ・ "), fontSize = 11.sp, color = INK_FAINT)
                }
                Text("›", color = Color(0xFFC8C1DA), fontSize = 18.sp)
            }
        }
    }

    SectionH("テーマカラー（推しカラー）")
    OCard {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OSHI_COLORS.forEach { c ->
                val sel = c == store.data.profile.themeColor
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                        .then(if (sel) Modifier.border(3.dp, PURPLE, CircleShape) else Modifier)
                        .clickable { store.setTheme(c) }
                )
            }
        }
    }

    SectionH("データ・ヘルプ")
    OCard {
        MenuItem("❓", "使い方・OshiLogについて", onHelp)
        HorizontalDivider(color = LINE, thickness = 1.dp)
        MenuItem("✨", "サンプルデータを入れる", onSeed)
        HorizontalDivider(color = LINE, thickness = 1.dp)
        MenuItem("🗑️", "すべてのデータを消去", onReset)
    }
    Text("OshiLog v1.0 ・ データはこの端末内に保存されます", color = INK_FAINT, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
}

@Composable
private fun MenuItem(icon: String, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 20.sp, modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = INK, modifier = Modifier.weight(1f))
        Text("›", color = Color(0xFFC8C1DA), fontSize = 18.sp)
    }
}

@Composable
private fun HelpDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("OshiLog の使い方", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HelpP("🏠 ホーム", "次の予定までのカウントダウン、今月／今年の推し活費、今月のイベント数、チケット状況、推しの誕生日までの日数が一目で分かります。")
                HelpP("📅 カレンダー", "ライブ・握手会・発売日などを登録。日付のマーカーで予定・チケット・誕生日が分かります。")
                HelpP("＋ 追加", "下中央の「＋」から、予定・チケット・支出・推しを追加できます。")
                HelpP("📝 記録", "推し活費をカテゴリ別・推し別・月別／年別で自動集計します。")
                HelpP("👤 マイページ", "推しの管理、テーマカラー変更、サンプル投入・消去ができます。")
                HelpP("💾 保存", "データはこの端末内に保存され、閉じても残ります。項目をタップで編集・削除できます。")
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("とじる", color = PURPLE_DEEP, fontWeight = FontWeight.Bold) } },
        containerColor = CARD
    )
}

@Composable private fun HelpP(h: String, b: String) {
    Text(h, fontWeight = FontWeight.Bold, color = INK, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
    Text(b, color = INK_SOFT, fontSize = 13.sp)
}

// ============================ ADD SHEET ============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(onDismiss: () -> Unit, onPick: (String) -> Unit, onSearch: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CARD) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("＋ 追加する", fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            AddOpt("🔎", "アーティスト・フェスから探す", "検索して推し・ライブを登録") { onSearch() }
            AddOpt("📅", "ライブ・イベント予定", "ライブ / 握手会 / 発売日 / 締切など") { onPick("event") }
            AddOpt("🎫", "チケット応募・当落", "応募 → 当落 → 入金まで管理") { onPick("ticket") }
            AddOpt("💰", "推し活費の記録", "チケット / グッズ / 遠征など") { onPick("expense") }
            AddOpt("💜", "推しを登録", "名前 / 誕生日 / 推しカラー") { onPick("oshi") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheet(store: Store, onDismiss: () -> Unit, onRegister: (FormTarget) -> Unit) {
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) } // key of expanded result row
    val results = remember(query) { Catalog.search(query) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CARD) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("アーティスト・フェスを検索", fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text("内蔵リストから検索（オフライン）", fontSize = 11.sp, color = INK_FAINT, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("例）YOASOBI / フジロック", color = INK_FAINT) },
                leadingIcon = { Text("🔎", fontSize = 16.sp) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = FIELD, focusedBorderColor = PURPLE, unfocusedBorderColor = LINE)
            )
            Spacer(Modifier.height(10.dp))

            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (query.isBlank()) {
                    Text("アーティスト名・フェス名を入力してください。\n見つからない場合は「＋ 予定」から自由に登録できます。", color = INK_FAINT, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
                } else if (results.isEmpty()) {
                    Text("該当なし。「＋ 予定」や「＋ 推し」から手動で登録できます。", color = INK_FAINT, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
                } else {
                    results.forEach { hit ->
                        val key = when (hit) { is SearchHit.Artist -> "a:" + hit.a.name; is SearchHit.Fest -> "f:" + hit.f.name }
                        val open = expanded == key
                        Surface(color = FIELD, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                            Column {
                                Row(Modifier.fillMaxWidth().clickable { expanded = if (open) null else key }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    when (hit) {
                                        is SearchHit.Artist -> {
                                            Text("🎤", fontSize = 20.sp); Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) { Text(hit.a.name, fontWeight = FontWeight.Bold, color = INK); Text("アーティスト", fontSize = 11.sp, color = INK_FAINT) }
                                        }
                                        is SearchHit.Fest -> {
                                            Text("🎪", fontSize = 20.sp); Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) { Text(hit.f.name, fontWeight = FontWeight.Bold, color = INK); Text("フェス ・ " + hit.f.venue, fontSize = 11.sp, color = INK_FAINT) }
                                        }
                                    }
                                    Text(if (open) "▲" else "▼", color = INK_FAINT, fontSize = 12.sp)
                                }
                                if (open) {
                                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        when (hit) {
                                            is SearchHit.Artist -> {
                                                val existing = store.data.oshi.firstOrNull { it.name == hit.a.name }?.id
                                                MiniBtn("⭐ 推しに登録", Modifier.weight(1f)) { onRegister(FormTarget("oshi", null, Prefill(oshiName = hit.a.name))) }
                                                MiniBtn("🎤 ライブを登録", Modifier.weight(1f)) { onRegister(FormTarget("event", null, Prefill(title = hit.a.name + " LIVE", kind = "ライブ", oshiId = existing))) }
                                            }
                                            is SearchHit.Fest -> {
                                                MiniBtn("🎪 このフェスのライブを登録", Modifier.weight(1f)) { onRegister(FormTarget("event", null, Prefill(title = hit.f.name, place = hit.f.venue, kind = "フェス"))) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniBtn(label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(color = PURPLE, shape = RoundedCornerShape(12.dp), modifier = modifier.clickable { onClick() }) {
        Text(label, Modifier.padding(vertical = 11.dp), textAlign = TextAlign.Center, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun AddOpt(icon: String, title: String, sub: String, onClick: () -> Unit) {
    Surface(color = FIELD, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { onClick() }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFEFE6FB)), contentAlignment = Alignment.Center) { Text(icon, fontSize = 20.sp) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = INK)
                Text(sub, fontSize = 11.sp, color = INK_FAINT)
            }
        }
    }
}

// ============================ FORM SHEET ============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormSheet(store: Store, target: FormTarget, onClose: () -> Unit, onSaved: () -> Unit, onDelete: (String, () -> Unit) -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = CARD) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            when (target.type) {
                "event" -> EventForm(store, target.id, target.prefill, onClose, onSaved, onDelete)
                "ticket" -> TicketForm(store, target.id, onClose, onSaved, onDelete)
                "expense" -> ExpenseForm(store, target.id, onClose, onSaved, onDelete)
                "oshi" -> OshiForm(store, target.id, target.prefill, onClose, onSaved, onDelete)
            }
        }
    }
}

@Composable private fun FormTitle(t: String) { Text(t, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) }

@Composable private fun FLabel(t: String, req: Boolean = false) {
    Row(Modifier.padding(bottom = 6.dp, top = 6.dp)) {
        Text(t, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = INK_SOFT)
        if (req) Text(" *", color = Color(0xFFFF3D8B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OField(value: String, onValue: (String) -> Unit, placeholder: String = "", number: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onValue, singleLine = true,
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, color = INK_FAINT) },
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = FIELD, focusedBorderColor = PURPLE, unfocusedBorderColor = LINE)
    )
}

@Composable
private fun DateField(value: String, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    Surface(color = FIELD, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, LINE),
        modifier = Modifier.fillMaxWidth().clickable {
            val c = D.parse(value.ifEmpty { D.todayStr() })
            DatePickerDialog(ctx, { _, yy, mm, dd -> onPick("$yy-${D.pad(mm + 1)}-${D.pad(dd)}") },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }) {
        Text(if (value.isEmpty()) "日付を選ぶ" else value, Modifier.padding(13.dp), color = if (value.isEmpty()) INK_FAINT else INK, fontSize = 15.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Dropdown(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.second == selected }?.first ?: ""
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = label, onValueChange = {}, readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = FIELD, focusedBorderColor = PURPLE, unfocusedBorderColor = LINE)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (lab, value) -> DropdownMenuItem(text = { Text(lab) }, onClick = { onSelect(value); expanded = false }) }
        }
    }
}

@Composable
private fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) ->
            val on = id == selected
            Surface(color = if (on) PURPLE else FIELD, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, LINE),
                modifier = Modifier.weight(1f).clickable { onSelect(id) }) {
                Text(label, Modifier.padding(vertical = 11.dp), textAlign = TextAlign.Center, color = if (on) Color.White else INK_SOFT, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun FormButtons(accent: Color, canDelete: Boolean, error: String, onDelete: () -> Unit, onCancel: () -> Unit, onSave: () -> Unit) {
    if (error.isNotEmpty()) Text(error, color = Color(0xFFF5227D), fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (canDelete) Surface(color = Color(0xFFFDEAF0), shape = RoundedCornerShape(14.dp), modifier = Modifier.clickable { onDelete() }) {
            Text("削除", Modifier.padding(horizontal = 18.dp, vertical = 14.dp), color = Color(0xFFF5227D), fontWeight = FontWeight.Bold)
        }
        Surface(color = FIELD, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).clickable { onCancel() }) {
            Text("キャンセル", Modifier.padding(vertical = 14.dp).fillMaxWidth(), textAlign = TextAlign.Center, color = INK_SOFT, fontWeight = FontWeight.Bold)
        }
        Surface(color = accent, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).clickable { onSave() }) {
            Text("保存", Modifier.padding(vertical = 14.dp).fillMaxWidth(), textAlign = TextAlign.Center, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EventForm(store: Store, id: String?, prefill: Prefill?, onClose: () -> Unit, onSaved: () -> Unit, onDelete: (String, () -> Unit) -> Unit) {
    val ex = id?.let { i -> store.data.events.firstOrNull { it.id == i } }
    var title by remember { mutableStateOf(ex?.title ?: prefill?.title ?: "") }
    var kind by remember { mutableStateOf(ex?.kind ?: prefill?.kind ?: "ライブ") }
    var oshiId by remember { mutableStateOf(ex?.oshiId ?: prefill?.oshiId) }
    var date by remember { mutableStateOf(ex?.date ?: D.todayStr()) }
    var place by remember { mutableStateOf(ex?.place ?: prefill?.place ?: "") }
    var memo by remember { mutableStateOf(ex?.memo ?: "") }
    var err by remember { mutableStateOf("") }

    FormTitle(if (id != null) "予定を編集" else "ライブ・イベント予定")
    FLabel("タイトル", true); OField(title, { title = it }, "例）○○ ARENA LIVE")
    FLabel("種類"); Dropdown(EVENT_KINDS.map { it to it }, kind) { kind = it }
    FLabel("推し"); Dropdown(oshiOptions(store), oshiId) { oshiId = it }
    FLabel("日付", true); DateField(date) { date = it }
    FLabel("会場"); OField(place, { place = it }, "例）○○アリーナ")
    FLabel("メモ"); OField(memo, { memo = it }, "開場時間・座席など")
    FormButtons(Color(store.data.profile.themeColor), id != null, err,
        onDelete = { onDelete("この予定を削除しますか？") { store.deleteEvent(id!!) } },
        onCancel = onClose,
        onSave = {
            if (title.isBlank()) err = "タイトルを入力してください"
            else { store.upsertEvent(EventItem(id ?: store.newId(), title.trim(), kind, oshiId, date, place.trim(), memo.trim())); onSaved() }
        })
}

@Composable
private fun TicketForm(store: Store, id: String?, onClose: () -> Unit, onSaved: () -> Unit, onDelete: (String, () -> Unit) -> Unit) {
    val ex = id?.let { i -> store.data.tickets.firstOrNull { it.id == i } }
    var title by remember { mutableStateOf(ex?.title ?: "") }
    var oshiId by remember { mutableStateOf(ex?.oshiId) }
    var date by remember { mutableStateOf(ex?.date ?: D.todayStr()) }
    var resultDate by remember { mutableStateOf(ex?.resultDate ?: "") }
    var place by remember { mutableStateOf(ex?.place ?: "") }
    var seat by remember { mutableStateOf(ex?.seat ?: "") }
    var amount by remember { mutableStateOf(ex?.amount?.toString() ?: "") }
    var status by remember { mutableStateOf(ex?.status ?: "applied") }
    var err by remember { mutableStateOf("") }

    FormTitle(if (id != null) "チケットを編集" else "チケット応募・当落")
    FLabel("公演名", true); OField(title, { title = it }, "例）大阪公演")
    FLabel("推し"); Dropdown(oshiOptions(store), oshiId) { oshiId = it }
    FLabel("開催日", true); DateField(date) { date = it }
    FLabel("当落発表日"); DateField(resultDate) { resultDate = it }
    FLabel("会場"); OField(place, { place = it }, "例）大阪城ホール")
    FLabel("座席"); OField(seat, { seat = it }, "例）アリーナA5")
    FLabel("金額（円）"); OField(amount, { amount = it.filter { c -> c.isDigit() } }, "9000", number = true)
    FLabel("状態"); Segmented(TICKET_STATUS, status) { status = it }
    FormButtons(Color(store.data.profile.themeColor), id != null, err,
        onDelete = { onDelete("このチケットを削除しますか？") { store.deleteTicket(id!!) } },
        onCancel = onClose,
        onSave = {
            if (title.isBlank()) err = "公演名を入力してください"
            else { store.upsertTicket(Ticket(id ?: store.newId(), title.trim(), oshiId, date, resultDate, place.trim(), seat.trim(), amount.toIntOrNull(), status)); onSaved() }
        })
}

@Composable
private fun ExpenseForm(store: Store, id: String?, onClose: () -> Unit, onSaved: () -> Unit, onDelete: (String, () -> Unit) -> Unit) {
    val ex = id?.let { i -> store.data.expenses.firstOrNull { it.id == i } }
    var amount by remember { mutableStateOf(ex?.amount?.toString() ?: "") }
    var category by remember { mutableStateOf(ex?.category ?: "ticket") }
    var oshiId by remember { mutableStateOf(ex?.oshiId) }
    var date by remember { mutableStateOf(ex?.date ?: D.todayStr()) }
    var memo by remember { mutableStateOf(ex?.memo ?: "") }
    var err by remember { mutableStateOf("") }

    FormTitle(if (id != null) "支出を編集" else "推し活費の記録")
    FLabel("金額（円）", true); OField(amount, { amount = it.filter { c -> c.isDigit() } }, "例）3000", number = true)
    FLabel("カテゴリ"); Dropdown(CATS.map { it.name to it.id }, category) { category = it }
    FLabel("推し"); Dropdown(oshiOptions(store), oshiId) { oshiId = it }
    FLabel("日付", true); DateField(date) { date = it }
    FLabel("メモ"); OField(memo, { memo = it }, "例）アクスタ・遠征名など")
    FormButtons(Color(store.data.profile.themeColor), id != null, err,
        onDelete = { onDelete("この支出を削除しますか？") { store.deleteExpense(id!!) } },
        onCancel = onClose,
        onSave = {
            val a = amount.toIntOrNull() ?: 0
            if (a <= 0) err = "金額を正しく入力してください"
            else { store.upsertExpense(Expense(id ?: store.newId(), a, category, oshiId, date, memo.trim())); onSaved() }
        })
}

@Composable
private fun OshiForm(store: Store, id: String?, prefill: Prefill?, onClose: () -> Unit, onSaved: () -> Unit, onDelete: (String, () -> Unit) -> Unit) {
    val ex = id?.let { i -> store.data.oshi.firstOrNull { it.id == i } }
    var name by remember { mutableStateOf(ex?.name ?: prefill?.oshiName ?: "") }
    var emoji by remember { mutableStateOf(ex?.emoji ?: OSHI_EMOJIS[0]) }
    var color by remember { mutableStateOf(ex?.color ?: OSHI_COLORS[0]) }
    var birthday by remember { mutableStateOf(ex?.birthday ?: "") }
    var group by remember { mutableStateOf(ex?.group ?: "") }
    var memo by remember { mutableStateOf(ex?.memo ?: "") }
    var err by remember { mutableStateOf("") }

    FormTitle(if (id != null) "推しを編集" else "推しを登録")
    FLabel("名前", true); OField(name, { name = it }, "例）推しの名前")
    FLabel("アイコン")
    FlowEmoji(emoji) { emoji = it }
    FLabel("推しカラー")
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OSHI_COLORS.forEach { c ->
            Box(Modifier.size(32.dp).clip(CircleShape).background(Color(c)).then(if (c == color) Modifier.border(3.dp, PURPLE, CircleShape) else Modifier).clickable { color = c })
        }
    }
    FLabel("誕生日（MM-DD）"); OField(birthday, { birthday = it }, "例）07-15")
    FLabel("所属グループ"); OField(group, { group = it }, "例）○○")
    FLabel("メモ"); OField(memo, { memo = it }, "自由メモ")
    FormButtons(Color(store.data.profile.themeColor), id != null, err,
        onDelete = { onDelete("この推しを削除しますか？") { store.deleteOshi(id!!) } },
        onCancel = onClose,
        onSave = {
            if (name.isBlank()) err = "名前を入力してください"
            else {
                var bd = birthday.trim()
                if (bd.isNotEmpty() && !bd.contains("-") && bd.length == 4) bd = bd.substring(0, 2) + "-" + bd.substring(2)
                store.upsertOshi(Oshi(id ?: store.newId(), name.trim(), emoji, color, bd, group.trim(), memo.trim())); onSaved()
            }
        })
}

@Composable
private fun FlowEmoji(selected: String, onSelect: (String) -> Unit) {
    Column {
        OSHI_EMOJIS.chunked(6).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { e ->
                    val on = e == selected
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(if (on) Color.White else FIELD)
                        .then(if (on) Modifier.border(2.dp, PURPLE, RoundedCornerShape(11.dp)) else Modifier)
                        .clickable { onSelect(e) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 20.sp) }
                }
            }
        }
    }
}

private fun oshiOptions(store: Store): List<Pair<String, String?>> =
    listOf("推し未設定" to null) + store.data.oshi.map { (it.emoji + " " + it.name) to it.id }
