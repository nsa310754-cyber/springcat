package site.ragdollp.pocketzip

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = pocketZipColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PocketZipApp()
                }
            }
        }
    }
}

@Composable
private fun pocketZipColorScheme(): ColorScheme {
    val teal = Color(0xFF1F8A70)
    return lightColorScheme(
        primary = teal,
        onPrimary = Color.White,
        secondary = Color(0xFF146856),
    )
}

private fun isAllFilesAccessGranted(): Boolean = Environment.isExternalStorageManager()

private fun openAllFilesAccessSettings(context: Context) {
    val intent = try {
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    } catch (_: Exception) {
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
}

@Composable
fun PocketZipApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var permissionGranted by remember { mutableStateOf(isAllFilesAccessGranted()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = isAllFilesAccessGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val exclusionStore = remember { ExclusionStore(context) }
    var excludedPaths by remember { mutableStateOf(exclusionStore.load()) }
    fun updateExcluded(newSet: Set<String>) {
        excludedPaths = newSet
        exclusionStore.save(newSet)
    }

    val zipState by ZipProgressBus.state.collectAsState()

    when {
        !permissionGranted -> PermissionScreen(onGrantClick = { openAllFilesAccessSettings(context) })
        zipState !is ZipState.Idle -> ProgressScreen(
            state = zipState,
            onCancel = { ZipProgressBus.cancelRequested = true },
            onDismiss = { ZipProgressBus.state.value = ZipState.Idle },
        )
        else -> BrowseScreen(
            excludedPaths = excludedPaths,
            onExcludedChange = ::updateExcluded,
            onStartZip = {
                val roots = StorageRoots.list(context)
                val intent = Intent(context, ZipService::class.java).apply {
                    action = ZipService.ACTION_START
                    putStringArrayListExtra(ZipService.EXTRA_ROOTS, ArrayList(roots.map { it.dir.path }))
                    putStringArrayListExtra(ZipService.EXTRA_ROOT_LABELS, ArrayList(roots.map { it.label }))
                    putStringArrayListExtra(ZipService.EXTRA_EXCLUDED, ArrayList(excludedPaths))
                }
                context.startForegroundService(intent)
                ZipProgressBus.state.value = ZipState.Running("", 0, 0, 0)
            },
        )
    }
}

@Composable
private fun PermissionScreen(onGrantClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("PocketZip", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "端末内の全ファイルを検索して ZIP にまとめるには、" +
                "「すべてのファイルへのアクセス」の許可が必要です。\n\n" +
                "次の画面で PocketZip を探し、アクセスを許可してください。",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrantClick) { Text("許可する") }
    }
}

@Composable
private fun BrowseScreen(
    excludedPaths: Set<String>,
    onExcludedChange: (Set<String>) -> Unit,
    onStartZip: () -> Unit,
) {
    val context = LocalContext.current
    val roots = remember { StorageRoots.list(context) }
    var expandedPaths by remember { mutableStateOf(setOf<String>()) }
    val childrenCache = remember { mutableStateMapOf<String, List<File>>() }

    LaunchedEffect(expandedPaths) {
        for (path in expandedPaths) {
            if (!childrenCache.containsKey(path)) {
                val kids = withContext(Dispatchers.IO) {
                    runCatching {
                        File(path).listFiles()
                            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                            ?: emptyList()
                    }.getOrDefault(emptyList())
                }
                childrenCache[path] = kids
            }
        }
    }

    fun visibleRows(): List<Pair<File, Int>> {
        val rows = mutableListOf<Pair<File, Int>>()
        fun recurse(file: File, depth: Int) {
            rows += file to depth
            if (file.isDirectory && file.path in expandedPaths) {
                childrenCache[file.path]?.forEach { recurse(it, depth + 1) }
            }
        }
        roots.forEach { recurse(it.dir, 0) }
        return rows
    }
    val rows = visibleRows()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("保存するファイルを選ぶ") })
        },
        bottomBar = {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "チェックを外したファイル・フォルダは保存されません。" +
                        "フォルダを除外すると中身もまとめて除外されます。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (excludedPaths.isNotEmpty()) {
                    Text(
                        "除外中: ${excludedPaths.size} 件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onStartZip,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("圧縮して保存") }
            }
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            items(rows, key = { it.first.path }) { (file, depth) ->
                FileRow(
                    file = file,
                    depth = depth,
                    expanded = file.path in expandedPaths,
                    checked = !isPathExcluded(file.path, excludedPaths),
                    onToggleExpand = {
                        expandedPaths = if (file.path in expandedPaths) {
                            expandedPaths - file.path
                        } else {
                            expandedPaths + file.path
                        }
                    },
                    onToggleCheck = { isChecked ->
                        onExcludedChange(
                            if (isChecked) excludedPaths - file.path else excludedPaths + file.path
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    file: File,
    depth: Int,
    expanded: Boolean,
    checked: Boolean,
    onToggleExpand: () -> Unit,
    onToggleCheck: (Boolean) -> Unit,
) {
    val isDir = file.isDirectory
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isDir, onClick = onToggleExpand)
            .padding(start = (depth * 20).dp, top = 8.dp, bottom = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (isDir) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = null,
                )
            }
        }
        Checkbox(checked = checked, onCheckedChange = onToggleCheck)
        Icon(
            imageVector = if (isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = if (checked) MaterialTheme.colorScheme.primary else Color.Gray,
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                file.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (checked) MaterialTheme.colorScheme.onSurface else Color.Gray,
            )
            if (!isDir) {
                Text(
                    formatBytes(file.length()),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
        }
    }
}

@Composable
private fun ProgressScreen(state: ZipState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (state) {
            is ZipState.Running -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(24.dp))
                Text("${state.filesDone} 個 処理済み (${formatBytes(state.bytesDone)})")
                if (state.currentPath.isNotEmpty()) {
                    Text(state.currentPath, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (state.skipped > 0) {
                    Text("スキップ: ${state.skipped} 個", color = Color.Gray)
                }
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = onCancel) { Text("キャンセル") }
            }
            is ZipState.Done -> {
                Text("圧縮が完了しました", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                Text("${state.filesDone} 個 / ${formatBytes(state.bytesDone)}")
                if (state.skipped > 0) {
                    Text("読み取れなかったファイル: ${state.skipped} 個", color = Color.Gray)
                }
                Spacer(Modifier.height(8.dp))
                Text("保存先: ${state.outputPath}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(24.dp))
                Row {
                    Button(onClick = { shareZip(context, state.outputPath) }) { Text("共有する") }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = onDismiss) { Text("戻る") }
                }
            }
            is ZipState.Cancelled -> {
                Text("キャンセルしました", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = onDismiss) { Text("戻る") }
            }
            is ZipState.Failed -> {
                Text("エラーが発生しました", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(state.message, color = Color.Gray)
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = onDismiss) { Text("戻る") }
            }
            ZipState.Idle -> Unit
        }
    }
}

private fun shareZip(context: Context, path: String) {
    val file = File(path)
    val uri = FileProvider.getUriForFile(context, "site.ragdollp.pocketzip.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "ZIP を共有"))
}
