package site.ragdollp.sqlbackup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SqlBackupTheme {
                SqlBackupApp(vm)
            }
        }
    }
}

@Composable
fun SqlBackupTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF1E4FBF),
        secondary = androidx.compose.ui.graphics.Color(0xFF2F6FED),
        background = androidx.compose.ui.graphics.Color(0xFFF3F6FB),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

private sealed class Screen {
    data object Connections : Screen()
    data class ConnectionForm(val editId: String?) : Screen()
    data object Databases : Screen()
    data class DatabaseActions(val dbName: String) : Screen()
    data class Backup(val dbName: String) : Screen()
    data class Restore(val dbName: String?) : Screen()
    data object History : Screen()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SqlBackupApp(vm: AppViewModel) {
    val backStack = remember { mutableStateListOf<Screen>(Screen.Connections) }
    val current = backStack.last()

    fun push(screen: Screen) = backStack.add(screen)
    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    val connectState by vm.connectState.collectAsState()
    val activeProfile = (connectState as? ConnectState.Connected)?.profile

    val title = when (val s = current) {
        Screen.Connections -> "接続先を選択"
        is Screen.ConnectionForm -> if (s.editId == null) "新しい接続" else "接続を編集"
        Screen.Databases -> activeProfile?.label ?: "データベース"
        is Screen.DatabaseActions -> s.dbName
        is Screen.Backup -> "バックアップ: ${s.dbName}"
        is Screen.Restore -> if (s.dbName != null) "リストア: ${s.dbName}" else "リストア"
        Screen.History -> "履歴"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (backStack.size > 1) {
                        IconButton(onClick = {
                            pop()
                        }) { Icon(Icons.Default.ArrowBack, contentDescription = "戻る") }
                    }
                },
                actions = {
                    if (current != Screen.History) {
                        IconButton(onClick = { push(Screen.History) }) {
                            Icon(Icons.Default.History, contentDescription = "履歴")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = androidx.compose.ui.graphics.Color.White,
                    navigationIconContentColor = androidx.compose.ui.graphics.Color.White,
                    actionIconContentColor = androidx.compose.ui.graphics.Color.White,
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = current) {
                Screen.Connections -> ConnectionsScreen(
                    vm = vm,
                    onAdd = { push(Screen.ConnectionForm(null)) },
                    onEdit = { id -> push(Screen.ConnectionForm(id)) },
                    onConnected = { push(Screen.Databases) },
                )
                is Screen.ConnectionForm -> ConnectionFormScreen(
                    vm = vm,
                    editId = s.editId,
                    onConnected = { push(Screen.Databases) },
                )
                Screen.Databases -> DatabasesScreen(
                    vm = vm,
                    profile = activeProfile,
                    onSelectDb = { dbName -> push(Screen.DatabaseActions(dbName)) },
                    onNewRestore = { push(Screen.Restore(null)) },
                )
                is Screen.DatabaseActions -> DatabaseActionsScreen(
                    dbName = s.dbName,
                    onBackup = { push(Screen.Backup(s.dbName)) },
                    onRestore = { push(Screen.Restore(s.dbName)) },
                )
                is Screen.Backup -> BackupScreen(vm = vm, profile = activeProfile, dbName = s.dbName)
                is Screen.Restore -> RestoreScreen(vm = vm, profile = activeProfile, initialDbName = s.dbName)
                Screen.History -> HistoryScreen(vm = vm)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 接続一覧
// ---------------------------------------------------------------------------

@Composable
private fun ConnectionsScreen(
    vm: AppViewModel,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onConnected: () -> Unit,
) {
    val connections by vm.connections.collectAsState()
    val connectState by vm.connectState.collectAsState()

    LaunchedEffect(connectState) {
        if (connectState is ConnectState.Connected) onConnected()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "保存済みの接続先をタップすると接続します。SQL Server の BACKUP DATABASE / RESTORE DATABASE をアプリから実行できます。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (connections.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("接続先がまだありません。右下の + から追加してください。")
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(connections, key = { it.id }) { conn ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(conn.label, fontWeight = FontWeight.Bold)
                                Text(
                                    "${conn.host}:${conn.port}" + if (conn.username.isNotBlank()) "  (${conn.username})" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onEdit(conn.id) }) { Text("編集") }
                            Button(onClick = { vm.connect(conn) }) { Text("接続") }
                        }
                    }
                }
            }
        }

        if (connectState is ConnectState.Connecting) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("接続中…", style = MaterialTheme.typography.bodySmall)
        }
        if (connectState is ConnectState.Error) {
            Spacer(Modifier.height(8.dp))
            ErrorBanner((connectState as ConnectState.Error).message)
        }

        Spacer(Modifier.height(12.dp))
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("新しい接続を追加")
        }
    }
}

// ---------------------------------------------------------------------------
// 接続の追加・編集
// ---------------------------------------------------------------------------

@Composable
private fun ConnectionFormScreen(
    vm: AppViewModel,
    editId: String?,
    onConnected: () -> Unit,
) {
    val connections by vm.connections.collectAsState()
    val editing = remember(editId, connections) { connections.find { it.id == editId } }

    var name by rememberSaveable(editId) { mutableStateOf(editing?.name ?: "") }
    var host by rememberSaveable(editId) { mutableStateOf(editing?.host ?: "") }
    var port by rememberSaveable(editId) { mutableStateOf((editing?.port ?: 1433).toString()) }
    var database by rememberSaveable(editId) { mutableStateOf(editing?.database ?: "") }
    var username by rememberSaveable(editId) { mutableStateOf(editing?.username ?: "sa") }
    var password by rememberSaveable(editId) { mutableStateOf(editing?.password ?: "") }
    var windowsAuth by rememberSaveable(editId) { mutableStateOf(editing?.windowsAuth ?: false) }
    var domain by rememberSaveable(editId) { mutableStateOf(editing?.domain ?: "") }
    var encrypt by rememberSaveable(editId) { mutableStateOf(editing?.encrypt ?: false) }

    val connectState by vm.connectState.collectAsState()

    DisposableEffect(Unit) {
        onDispose { vm.resetConnectState() }
    }
    LaunchedEffect(connectState) {
        if (connectState is ConnectState.Connected) onConnected()
    }

    fun currentProfile() = ConnectionProfile(
        id = editId ?: java.util.UUID.randomUUID().toString(),
        name = name,
        host = host.trim(),
        port = port.toIntOrNull() ?: 1433,
        database = database.trim(),
        username = username.trim(),
        password = password,
        windowsAuth = windowsAuth,
        domain = domain.trim(),
        encrypt = encrypt,
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("接続名 (任意)") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(host, { host = it }, label = { Text("ホスト / IPアドレス") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            port, { port = it.filter { c -> c.isDigit() } },
            label = { Text("ポート") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            database, { database = it },
            label = { Text("初期データベース (省略可・既定は master)") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = windowsAuth, onCheckedChange = { windowsAuth = it })
            Spacer(Modifier.width(8.dp))
            Text("Windows 認証 (NTLM) を使う")
        }
        if (windowsAuth) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(domain, { domain = it }, label = { Text("ドメイン") }, modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            username, { username = it },
            label = { Text(if (windowsAuth) "ユーザー名" else "ログイン名 (例: sa)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            password, { password = it },
            label = { Text("パスワード") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = encrypt, onCheckedChange = { encrypt = it })
            Spacer(Modifier.width(8.dp))
            Text("接続を暗号化する (SSL)")
        }

        Spacer(Modifier.height(16.dp))
        if (connectState is ConnectState.Connecting) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        if (connectState is ConnectState.Error) {
            ErrorBanner((connectState as ConnectState.Error).message)
            Spacer(Modifier.height(8.dp))
        }

        Row {
            OutlinedButton(
                onClick = { vm.saveConnection(currentProfile()) },
                modifier = Modifier.weight(1f),
            ) { Text("保存") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    vm.saveConnection(currentProfile())
                    vm.connect(currentProfile())
                },
                enabled = host.isNotBlank() && connectState !is ConnectState.Connecting,
                modifier = Modifier.weight(1f),
            ) { Text("接続") }
        }

        if (editId != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { vm.deleteConnection(editId) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("この接続を削除")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// データベース一覧
// ---------------------------------------------------------------------------

@Composable
private fun DatabasesScreen(
    vm: AppViewModel,
    profile: ConnectionProfile?,
    onSelectDb: (String) -> Unit,
    onNewRestore: () -> Unit,
) {
    if (profile == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("接続されていません") }
        return
    }
    val databases by vm.databases.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("データベース (${databases.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.refreshDatabases(profile) }) { Text("更新") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(databases, key = { it.name }) { db ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelectDb(db.name) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(db.name, fontWeight = FontWeight.Bold)
                            Text(
                                "${db.stateDesc} ・ 復旧モデル: ${db.recoveryModel} ・ ${"%.1f".format(db.sizeMb)} MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onNewRestore, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Restore, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("新しいデータベースとしてリストア")
        }
    }
}

// ---------------------------------------------------------------------------
// データベースごとのアクション選択
// ---------------------------------------------------------------------------

@Composable
private fun DatabaseActionsScreen(dbName: String, onBackup: () -> Unit, onRestore: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(dbName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Backup, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("バックアップ", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "このデータベースを SQL Server 上のファイルにバックアップします (BACKUP DATABASE)。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onBackup, modifier = Modifier.fillMaxWidth()) { Text("バックアップを実行") }
            }
        }
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("リストア", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "バックアップファイルからこのデータベースへ上書きリストアします (RESTORE DATABASE ... REPLACE)。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text("リストアを実行") }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// バックアップ画面
// ---------------------------------------------------------------------------

@Composable
private fun BackupScreen(vm: AppViewModel, profile: ConnectionProfile?, dbName: String) {
    if (profile == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("接続されていません") }
        return
    }
    val timestamp = remember { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) }
    var filePath by rememberSaveable { mutableStateOf("C:\\\\Backup\\\\${dbName}_$timestamp.bak") }
    var compression by rememberSaveable { mutableStateOf(true) }
    var differential by rememberSaveable { mutableStateOf(false) }
    var copyOnly by rememberSaveable { mutableStateOf(false) }

    val backupState by vm.backupState.collectAsState()
    val progress by vm.progress.collectAsState()

    DisposableEffect(Unit) { onDispose { vm.resetBackupState() } }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            "バックアップ先のパスは SQL Server が稼働しているマシン上のパスです(スマホ側のパスではありません)。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            filePath, { filePath = it },
            label = { Text("バックアップ先ファイルパス (サーバー上)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        CheckRow("圧縮する (COMPRESSION)", compression) { compression = it }
        CheckRow("差分バックアップ (DIFFERENTIAL)", differential) { differential = it }
        CheckRow("コピーのみ (COPY_ONLY・ログチェーンに影響しない)", copyOnly) { copyOnly = it }

        Spacer(Modifier.height(16.dp))
        if (progress.running) {
            ProgressBlock(progress)
            Spacer(Modifier.height(12.dp))
        }
        when (val s = backupState) {
            is RunState.Success -> SuccessBanner(s.message)
            is RunState.Failed -> ErrorBanner(s.message)
            else -> {}
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                vm.runBackup(profile, dbName, filePath.trim(), compression, differential, copyOnly)
            },
            enabled = filePath.isNotBlank() && backupState !is RunState.Running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (backupState is RunState.Running) "実行中…" else "バックアップを実行")
        }
    }
}

// ---------------------------------------------------------------------------
// リストア画面
// ---------------------------------------------------------------------------

@Composable
private fun RestoreScreen(vm: AppViewModel, profile: ConnectionProfile?, initialDbName: String?) {
    if (profile == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("接続されていません") }
        return
    }
    var dbName by rememberSaveable { mutableStateOf(initialDbName ?: "") }
    var filePath by rememberSaveable { mutableStateOf("") }
    var withReplace by rememberSaveable { mutableStateOf(initialDbName != null) }
    val moveOverrides = remember { mutableStateMapOf<String, String>() }

    val restoreState by vm.restoreState.collectAsState()
    val progress by vm.progress.collectAsState()
    val header by vm.headerState.collectAsState()
    val fileList by vm.fileListState.collectAsState()

    DisposableEffect(Unit) { onDispose { vm.resetRestoreState() } }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            "バックアップファイルは SQL Server が稼働しているマシン上のパスを指定してください。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            filePath, { filePath = it },
            label = { Text("バックアップファイルパス (サーバー上)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { vm.inspectBackupFile(profile, filePath.trim()) },
            enabled = filePath.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("バックアップファイルを解析") }

        header?.let { h ->
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("元のデータベース: ${h.databaseName ?: "-"}", style = MaterialTheme.typography.bodySmall)
                    Text("バックアップ名: ${h.backupName ?: "-"}", style = MaterialTheme.typography.bodySmall)
                    Text("開始: ${h.startDate ?: "-"} / 終了: ${h.finishDate ?: "-"}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            dbName, { dbName = it },
            label = { Text("リストア先データベース名") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        CheckRow("既存データベースを上書きする (REPLACE)", withReplace) { withReplace = it }

        if (fileList.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("データ/ログファイルの移動先 (MOVE)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            fileList.forEach { f ->
                val current = moveOverrides[f.logicalName] ?: f.physicalName
                OutlinedTextField(
                    value = current,
                    onValueChange = { moveOverrides[f.logicalName] = it },
                    label = { Text("${f.logicalName} (${if (f.type == "L") "ログ" else "データ"})") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        if (progress.running) {
            ProgressBlock(progress)
            Spacer(Modifier.height(12.dp))
        }
        when (val s = restoreState) {
            is RunState.Success -> SuccessBanner(s.message)
            is RunState.Failed -> ErrorBanner(s.message)
            else -> {}
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val moves = fileList.map { it.logicalName to (moveOverrides[it.logicalName] ?: it.physicalName) }
                vm.runRestore(profile, dbName.trim(), filePath.trim(), withReplace, moves)
            },
            enabled = dbName.isNotBlank() && filePath.isNotBlank() && restoreState !is RunState.Running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (restoreState is RunState.Running) "実行中…" else "リストアを実行")
        }
    }
}

// ---------------------------------------------------------------------------
// 履歴
// ---------------------------------------------------------------------------

@Composable
private fun HistoryScreen(vm: AppViewModel) {
    val history by vm.history.collectAsState()
    val fmt = remember { SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("実行履歴 (${history.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.clearHistory() }) { Text("クリア") }
        }
        Spacer(Modifier.height(8.dp))
        if (history.isEmpty()) {
            Text("まだ履歴はありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn {
            items(history, key = { it.id }) { h ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (h.status == OperationStatus.SUCCESS) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (h.status == OperationStatus.SUCCESS)
                                androidx.compose.ui.graphics.Color(0xFF2E7D32)
                            else MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            val label = when (h.type) {
                                OperationType.BACKUP -> "バックアップ"
                                OperationType.RESTORE -> "リストア"
                                OperationType.TEST_CONNECTION -> "接続確認"
                            }
                            Text("$label ・ ${h.databaseName}", fontWeight = FontWeight.Bold)
                            Text(h.connectionLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (h.filePath != "-") {
                                Text(h.filePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(h.message, style = MaterialTheme.typography.bodySmall)
                            Text(fmt.format(Date(h.timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 共通パーツ
// ---------------------------------------------------------------------------

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ProgressBlock(progress: ProgressState) {
    Column {
        Text(progress.label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        if (progress.percent != null) {
            LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth())
            Text("${progress.percent}%", style = MaterialTheme.typography.labelSmall)
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFFDECEA))) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text(message, color = androidx.compose.ui.graphics.Color(0xFFB3261E), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SuccessBanner(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFE8F5E9))) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFF2E7D32))
            Spacer(Modifier.width(8.dp))
            Text(message, color = androidx.compose.ui.graphics.Color(0xFF1B5E20), style = MaterialTheme.typography.bodySmall)
        }
    }
}
