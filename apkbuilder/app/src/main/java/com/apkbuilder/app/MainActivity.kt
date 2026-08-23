package com.apkbuilder.app

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.apkbuilder.core.pwa.PwaManifest
import com.apkbuilder.core.pwa.PwaManifestFetcher
import com.apkbuilder.core.pwa.PwaManifestValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PermissionOption(val label: String, val permission: String)

private const val INTERNET_PERMISSION = "android.permission.INTERNET"

private val PERMISSION_OPTIONS = listOf(
    PermissionOption("インターネット通信", INTERNET_PERMISSION),
    PermissionOption("バイブレーション", "android.permission.VIBRATE"),
    PermissionOption("カメラ", "android.permission.CAMERA"),
    PermissionOption("マイク", "android.permission.RECORD_AUDIO"),
    PermissionOption("通知", "android.permission.POST_NOTIFICATIONS"),
    PermissionOption("位置情報(高精度)", "android.permission.ACCESS_FINE_LOCATION"),
    PermissionOption("位置情報(おおよそ)", "android.permission.ACCESS_COARSE_LOCATION"),
    PermissionOption("写真/動画の読み取り", "android.permission.READ_EXTERNAL_STORAGE"),
    PermissionOption("ストレージへの書き込み", "android.permission.WRITE_EXTERNAL_STORAGE"),
)

private const val DEFAULT_APP_NAME = "マイゲーム"

private enum class Screen { BUILDER, EDITOR, PREVIEW }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(Screen.BUILDER) }

    var appName by remember { mutableStateOf(DEFAULT_APP_NAME) }
    var packageId by remember { mutableStateOf(defaultPackageId(DEFAULT_APP_NAME)) }
    var versionName by remember { mutableStateOf("1.0") }
    var versionCode by remember { mutableStateOf("1") }

    var iconUri by remember { mutableStateOf<Uri?>(null) }
    var iconPreview by remember { mutableStateOf<ImageBitmap?>(null) }

    var gameFileUri by remember { mutableStateOf<Uri?>(null) }
    var gameFolderUri by remember { mutableStateOf<Uri?>(null) }

    var editedHtml by remember { mutableStateOf("") }
    val screenshots = remember { mutableStateListOf<ByteArray>() }

    var pwaMode by remember { mutableStateOf(false) }
    var pwaUrl by remember { mutableStateOf("") }
    var isCheckingManifest by remember { mutableStateOf(false) }
    var pwaManifest by remember { mutableStateOf<PwaManifest?>(null) }
    var pwaIconBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pwaCheckStatus by remember { mutableStateOf<String?>(null) }

    val selectedPermissions = remember { mutableStateOf(setOf(INTERNET_PERMISSION)) }

    var googleServicesUri by remember { mutableStateOf<Uri?>(null) }
    var admobAppId by remember { mutableStateOf("") }
    var admobBannerUnitId by remember { mutableStateOf("") }

    var obfuscateGame by remember { mutableStateOf(false) }
    var outputFormat by remember { mutableStateOf(OutputFormat.APK) }

    var isGenerating by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var pendingZip by remember { mutableStateOf<ByteArray?>(null) }

    // Preview uses the edited HTML; if empty, fall back to the redirect stub (PWA) or a starter page.
    fun currentPreviewHtml(): String = when {
        editedHtml.isNotBlank() -> editedHtml
        pwaMode && pwaManifest != null -> Generation.buildPwaRedirectHtml(pwaManifest!!.startUrl)
        else -> STARTER_HTML
    }

    // Seed the editor from the picked HTML file (or a starter) the first time it's opened.
    fun seedEditorThen(go: () -> Unit) {
        if (editedHtml.isNotBlank() || pwaMode) {
            go(); return
        }
        val fileUri = gameFileUri
        if (fileUri == null) {
            editedHtml = STARTER_HTML
            go(); return
        }
        scope.launch {
            editedHtml = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(fileUri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull() ?: STARTER_HTML
            }
            go()
        }
    }

    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            iconUri = uri
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    iconPreview = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }
        }
    }
    val pickGameFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            gameFileUri = uri
            gameFolderUri = null
            editedHtml = ""
        }
    }
    val pickGameFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            gameFolderUri = uri
            gameFileUri = null
            editedHtml = ""
        }
    }
    val pickGoogleServices = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) googleServicesUri = uri
    }
    val saveOutput = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val zip = pendingZip
        if (uri != null && zip != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(zip) }
            }.onSuccess {
                statusText = "保存しました。"
            }.onFailure {
                statusText = "保存に失敗しました: ${it.message}"
            }
            pendingZip = null
        }
    }

    fun checkPwaManifest() {
        if (pwaUrl.isBlank()) {
            pwaCheckStatus = "URLを入力してください"
            return
        }
        isCheckingManifest = true
        pwaCheckStatus = null
        pwaManifest = null
        pwaIconBytes = null
        scope.launch {
            try {
                val manifest = withContext(Dispatchers.IO) {
                    PwaManifestFetcher.fetchManifestFor(PwaManifestFetcher.normalizeUrl(pwaUrl))
                }
                val validation = PwaManifestValidator.validate(manifest)
                val bestIcon = manifest.bestIcon()
                val iconBytes = bestIcon?.let {
                    withContext(Dispatchers.IO) { runCatching { PwaManifestFetcher.fetchBytes(it.src) }.getOrNull() }
                }
                pwaManifest = manifest
                pwaIconBytes = iconBytes
                if (iconBytes != null) {
                    iconPreview = BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)?.asImageBitmap()
                }
                if (appName.isBlank() || appName == DEFAULT_APP_NAME) {
                    manifest.displayName?.let {
                        appName = it
                        packageId = defaultPackageId(it)
                    }
                }
                pwaCheckStatus = buildString {
                    appendLine(if (validation.installable) "✅ インストール可能な構成です" else "❌ 不足があります")
                    appendLine("名前: ${manifest.displayName ?: "(なし)"}")
                    appendLine("開始URL: ${manifest.startUrl}")
                    appendLine("表示モード: ${manifest.display ?: "(未指定)"}")
                    appendLine("アイコン: ${manifest.icons.size}個 (最大 ${bestIcon?.maxDimension() ?: 0}px)")
                    validation.issues.forEach { appendLine("❌ $it") }
                    validation.warnings.forEach { appendLine("⚠️ $it") }
                }.trimEnd()
            } catch (e: Exception) {
                pwaCheckStatus = "エラー: ${e.message}"
            } finally {
                isCheckingManifest = false
            }
        }
    }

    fun startGenerate() {
        if (pwaMode) {
            if (pwaManifest == null) {
                statusText = "先に「manifest.jsonを確認」を実行してください。"
                return
            }
        } else if (gameFileUri == null && gameFolderUri == null && editedHtml.isBlank()) {
            statusText = "ゲームのHTMLを選択するか、コード編集で作成してください。"
            return
        }
        if (!packageId.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))) {
            statusText = "パッケージIDの形式が正しくありません (例: com.example.mygame)"
            return
        }
        isGenerating = true
        statusText = "生成中..."
        val effectivePermissions = if (pwaMode) selectedPermissions.value + INTERNET_PERMISSION else selectedPermissions.value
        scope.launch {
            try {
                val zip = withContext(Dispatchers.Default) {
                    Generation.generate(
                        GenerationParams(
                            context = context,
                            appName = appName,
                            packageId = packageId,
                            versionName = versionName,
                            versionCode = versionCode.toIntOrNull() ?: 1,
                            permissions = effectivePermissions.toList(),
                            iconUri = iconUri,
                            gameFileUri = if (pwaMode) null else gameFileUri,
                            gameFolderUri = if (pwaMode) null else gameFolderUri,
                            editedHtml = if (pwaMode) null else editedHtml.ifBlank { null },
                            pwaManifest = pwaManifest,
                            pwaIconBytes = pwaIconBytes,
                            obfuscateGame = obfuscateGame && !pwaMode,
                            googleServicesUri = googleServicesUri,
                            admobAppId = admobAppId.trim().takeIf { it.isNotBlank() },
                            admobBannerUnitId = admobBannerUnitId.trim().takeIf { it.isNotBlank() },
                            format = outputFormat,
                            screenshots = screenshots.toList(),
                        ),
                    )
                }
                pendingZip = zip
                statusText = "生成が完了しました。保存先を選んでください。"
                val suffix = when (outputFormat) {
                    OutputFormat.APK -> "apk"
                    OutputFormat.AAB -> "aab"
                    OutputFormat.PLAY_ZIP -> "play"
                }
                saveOutput.launch("$appName-$suffix.zip")
            } catch (e: Exception) {
                statusText = "エラー: ${e.message}"
            } finally {
                isGenerating = false
            }
        }
    }

    when (screen) {
        Screen.EDITOR -> GameEditorScreen(
            text = editedHtml,
            onTextChange = { editedHtml = it },
            onPreview = { screen = Screen.PREVIEW },
            onBack = { screen = Screen.BUILDER },
        )
        Screen.PREVIEW -> PreviewScreen(
            html = currentPreviewHtml(),
            onScreenshot = { screenshots.add(it) },
            screenshotCount = screenshots.size,
            onBack = { screen = Screen.BUILDER },
        )
        Screen.BUILDER -> BuilderForm(
            appName = appName, onAppName = { appName = it; packageId = defaultPackageId(it) },
            packageId = packageId, onPackageId = { packageId = it },
            versionName = versionName, onVersionName = { versionName = it },
            versionCode = versionCode, onVersionCode = { versionCode = it.filter(Char::isDigit) },
            iconPreview = iconPreview, hasIcon = iconUri != null, onPickIcon = { pickIcon.launch("image/*") },
            pwaMode = pwaMode, onSetPwaMode = { pwaMode = it },
            pwaUrl = pwaUrl, onPwaUrl = { pwaUrl = it },
            isCheckingManifest = isCheckingManifest, onCheckPwa = { checkPwaManifest() }, pwaCheckStatus = pwaCheckStatus,
            gameFileUri = gameFileUri, gameFolderUri = gameFolderUri, editedHtmlPresent = editedHtml.isNotBlank(),
            onPickFile = { pickGameFile.launch(arrayOf("text/html")) },
            onPickFolder = { pickGameFolder.launch(null) },
            onEdit = { seedEditorThen { screen = Screen.EDITOR } },
            onPreview = { seedEditorThen { screen = Screen.PREVIEW } },
            screenshotCount = screenshots.size,
            obfuscateGame = obfuscateGame, onObfuscate = { obfuscateGame = it },
            googleServicesSet = googleServicesUri != null, onPickGoogleServices = { pickGoogleServices.launch(arrayOf("application/json", "*/*")) },
            admobAppId = admobAppId, onAdmobAppId = { admobAppId = it },
            admobBannerUnitId = admobBannerUnitId, onAdmobBannerUnitId = { admobBannerUnitId = it },
            selectedPermissions = selectedPermissions.value,
            onTogglePermission = { perm, on ->
                selectedPermissions.value = if (on) selectedPermissions.value + perm else selectedPermissions.value - perm
            },
            outputFormat = outputFormat, onOutputFormat = { outputFormat = it },
            isGenerating = isGenerating, statusText = statusText, onGenerate = { startGenerate() },
        )
    }
}

@Composable
private fun BuilderForm(
    appName: String, onAppName: (String) -> Unit,
    packageId: String, onPackageId: (String) -> Unit,
    versionName: String, onVersionName: (String) -> Unit,
    versionCode: String, onVersionCode: (String) -> Unit,
    iconPreview: ImageBitmap?, hasIcon: Boolean, onPickIcon: () -> Unit,
    pwaMode: Boolean, onSetPwaMode: (Boolean) -> Unit,
    pwaUrl: String, onPwaUrl: (String) -> Unit,
    isCheckingManifest: Boolean, onCheckPwa: () -> Unit, pwaCheckStatus: String?,
    gameFileUri: Uri?, gameFolderUri: Uri?, editedHtmlPresent: Boolean,
    onPickFile: () -> Unit, onPickFolder: () -> Unit, onEdit: () -> Unit, onPreview: () -> Unit,
    screenshotCount: Int,
    obfuscateGame: Boolean, onObfuscate: (Boolean) -> Unit,
    googleServicesSet: Boolean, onPickGoogleServices: () -> Unit,
    admobAppId: String, onAdmobAppId: (String) -> Unit,
    admobBannerUnitId: String, onAdmobBannerUnitId: (String) -> Unit,
    selectedPermissions: Set<String>, onTogglePermission: (String, Boolean) -> Unit,
    outputFormat: OutputFormat, onOutputFormat: (OutputFormat) -> Unit,
    isGenerating: Boolean, statusText: String, onGenerate: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("APK Builder", style = MaterialTheme.typography.headlineSmall)
        Text("HTMLゲーム、または既存サイトのPWAからAndroidアプリを作成します。", style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(value = appName, onValueChange = onAppName, label = { Text("アプリ名") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = packageId, onValueChange = onPackageId, label = { Text("パッケージID (applicationId)") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = versionName, onValueChange = onVersionName, label = { Text("バージョン名") }, modifier = Modifier.fillMaxWidth(0.5f))
            OutlinedTextField(value = versionCode, onValueChange = onVersionCode, label = { Text("バージョンコード") }, modifier = Modifier.fillMaxWidth())
        }

        Divider()
        Text("アイコン画像", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onPickIcon) { Text(if (hasIcon) "画像を変更" else "画像を選択") }
            iconPreview?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(48.dp)) }
        }

        Divider()
        Text("作成方法", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormatToggle("ローカルHTML", !pwaMode) { onSetPwaMode(false) }
            FormatToggle("PWAモード (URL)", pwaMode) { onSetPwaMode(true) }
        }

        if (pwaMode) {
            OutlinedTextField(value = pwaUrl, onValueChange = onPwaUrl, label = { Text("アプリにしたいサイトのURL") }, placeholder = { Text("https://example.com/") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !isCheckingManifest, onClick = onCheckPwa) { Text("manifest.jsonを確認") }
                if (isCheckingManifest) CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
            pwaCheckStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        } else {
            Text("ゲーム本体 (HTML/JS/CSS)", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickFile) { Text("HTMLを1つ選択") }
                OutlinedButton(onClick = onPickFolder) { Text("フォルダを選択") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onEdit) { Text("コードを編集") }
                OutlinedButton(onClick = onPreview) { Text("プレビュー/スクショ") }
            }
            Text(
                when {
                    editedHtmlPresent -> "エディタで編集した内容を使用します"
                    gameFileUri != null -> "選択中: $gameFileUri"
                    gameFolderUri != null -> "選択中(フォルダ): $gameFolderUri"
                    else -> "未選択(「コードを編集」で新規作成もできます)"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (screenshotCount > 0) Text("スクリーンショット: ${screenshotCount}枚 撮影済み", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = obfuscateGame && !pwaMode, enabled = !pwaMode, onCheckedChange = onObfuscate)
            Text("ゲーム本体を難読化(暗号化)する" + if (pwaMode) "(PWAでは対象外)" else "")
        }

        Divider()
        Text("その他のサービス (任意)", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onPickGoogleServices) {
            Text(if (googleServicesSet) "google-services.json を変更" else "google-services.json を追加(Firebase)")
        }
        OutlinedTextField(value = admobAppId, onValueChange = onAdmobAppId, label = { Text("AdMob App ID (任意)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = admobBannerUnitId, onValueChange = onAdmobBannerUnitId, label = { Text("AdMob バナー広告ユニットID (任意)") }, modifier = Modifier.fillMaxWidth())

        Divider()
        Text("必要な権限", style = MaterialTheme.typography.titleMedium)
        for (option in PERMISSION_OPTIONS) {
            val forced = pwaMode && option.permission == INTERNET_PERMISSION
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = forced || selectedPermissions.contains(option.permission),
                    enabled = !forced,
                    onCheckedChange = { onTogglePermission(option.permission, it) },
                )
                Text(option.label + if (forced) "(必須)" else "")
            }
        }

        Divider()
        Text("出力フォーマット", style = MaterialTheme.typography.titleMedium)
        OutputFormatRow("APK (実機インストール用)", OutputFormat.APK, outputFormat, onOutputFormat)
        OutputFormatRow("AAB (Google Play アップロード用)", OutputFormat.AAB, outputFormat, onOutputFormat)
        OutputFormatRow("Google Play 提出パッケージ (.zip: aab+スクショ+掲載情報)", OutputFormat.PLAY_ZIP, outputFormat, onOutputFormat)

        Divider()
        Button(enabled = !isGenerating, onClick = onGenerate, modifier = Modifier.fillMaxWidth()) {
            Text(
                when (outputFormat) {
                    OutputFormat.APK -> "APKを生成"
                    OutputFormat.AAB -> "AABを生成"
                    OutputFormat.PLAY_ZIP -> "Google Play パッケージを生成"
                },
            )
        }
        if (isGenerating) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text("作成しています…")
            }
        }
        if (statusText.isNotEmpty()) Text(statusText, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FormatToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
}

@Composable
private fun OutputFormatRow(label: String, value: OutputFormat, selected: OutputFormat, onSelect: (OutputFormat) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected == value, onClick = { onSelect(value) })
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun defaultPackageId(appName: String): String {
    val slug = appName.lowercase().replace(Regex("[^a-z0-9]+"), "").ifEmpty { "app" }
    val safe = if (slug.first().isDigit()) "a$slug" else slug
    return "com.apkbuilder.generated.$safe"
}
