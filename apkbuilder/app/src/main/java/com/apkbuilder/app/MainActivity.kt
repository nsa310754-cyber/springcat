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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

private enum class Screen { BUILDER, EDITOR, PREVIEW, ANALYZER }

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

    // Update signing: reuse an existing keystore so the output installs over a previous version.
    var updateKeystoreUri by remember { mutableStateOf<Uri?>(null) }
    var ksStorePw by remember { mutableStateOf("") }
    var ksKeyPw by remember { mutableStateOf("") }
    var ksAlias by remember { mutableStateOf("") }

    var isGenerating by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var pendingZip by remember { mutableStateOf<ByteArray?>(null) }

    // Last build outputs (for install / analyze) and the analyzer view state.
    var lastBuiltApk by remember { mutableStateOf<ByteArray?>(null) }
    var lastBuiltArtifact by remember { mutableStateOf<ByteArray?>(null) }
    var analyzeInfo by remember { mutableStateOf<com.apkbuilder.core.ArtifactInfo?>(null) }
    var analyzeError by remember { mutableStateOf<String?>(null) }

    // "Update from existing APK/AAB": parse a prior build to seed the form
    // (app name / package / permissions) and bump the version so the output
    // installs over it. Extra permissions not shown as checkboxes are kept too.
    var updateSourceStatus by remember { mutableStateOf<String?>(null) }
    var isReadingUpdateSource by remember { mutableStateOf(false) }

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
    val pickKeystore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) updateKeystoreUri = uri
    }
    val pickArtifact = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            analyzeInfo = null
            analyzeError = null
            scope.launch {
                try {
                    val info = withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("ファイルを読み込めませんでした")
                        com.apkbuilder.core.ArtifactAnalyzer.analyze(bytes)
                    }
                    analyzeInfo = info
                } catch (e: Exception) {
                    analyzeError = e.message
                }
            }
        }
    }
    val pickUpdateApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            isReadingUpdateSource = true
            updateSourceStatus = null
            scope.launch {
                try {
                    val info = withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("ファイルを読み込めませんでした")
                        com.apkbuilder.core.ArtifactAnalyzer.analyze(bytes)
                    }
                    info.label?.takeIf { it.isNotBlank() }?.let { appName = it }
                    info.packageId?.takeIf { it.isNotBlank() }?.let { packageId = it }
                    val oldName = info.versionName ?: versionName
                    val oldCode = info.versionCode ?: (versionCode.toIntOrNull() ?: 1)
                    versionName = bumpVersionName(oldName)
                    versionCode = (oldCode + 1).toString()
                    if (info.permissions.isNotEmpty()) {
                        selectedPermissions.value = info.permissions.toSet() + INTERNET_PERMISSION
                    }
                    val known = PERMISSION_OPTIONS.map { it.permission }.toSet()
                    val extra = info.permissions.filterNot { it in known }
                    updateSourceStatus = buildString {
                        appendLine("元の${info.kind}を読み込みました。")
                        appendLine("・アプリ名: ${info.label ?: "(参照)"}")
                        appendLine("・パッケージ: ${info.packageId ?: "?"}")
                        appendLine("・バージョン: ${info.versionName ?: "?"} (code ${info.versionCode ?: "?"}) → $versionName (code $versionCode)")
                        appendLine("・権限: ${info.permissions.size}件を自動チェックしました" + if (extra.isNotEmpty()) "(うち一覧外 ${extra.size}件も出力に含めます)" else "")
                        append("※ 上書きインストールするには、下の「署名 / アップデート」で元の keystore も指定してください。")
                    }.trimEnd()
                } catch (e: Exception) {
                    updateSourceStatus = "読み込みに失敗しました: ${e.message}"
                } finally {
                    isReadingUpdateSource = false
                }
            }
        }
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
                val result = withContext(Dispatchers.Default) {
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
                            existingKeystoreUri = updateKeystoreUri,
                            existingKeystoreStorePassword = ksStorePw,
                            existingKeystoreKeyPassword = ksKeyPw,
                            existingKeystoreAlias = ksAlias.trim(),
                        ),
                    )
                }
                pendingZip = result.zip
                lastBuiltApk = result.rawApk
                lastBuiltArtifact = result.rawArtifact
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

    fun showSigningReport() {
        val uri = updateKeystoreUri
        if (uri == null) {
            statusText = "先に既存のkeystoreを選択してください(署名レポートは生成鍵ではその都度変わります)。"
            return
        }
        scope.launch {
            statusText = withContext(Dispatchers.IO) {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("keystore を読み込めませんでした")
                    val key = com.apkbuilder.core.KeystoreLoader.load(bytes, ksStorePw, ksKeyPw.ifBlank { ksStorePw }, ksAlias.trim())
                    "署名レポート\n" +
                        "Subject: ${key.subject}\n" +
                        "SHA-1: ${key.certificateSha1Fingerprint}\n" +
                        "SHA-256: ${key.certificateSha256Fingerprint}\n" +
                        "MD5: ${key.certificateMd5Fingerprint}\n" +
                        "有効期限: ${key.validUntil}"
                } catch (e: Exception) {
                    "署名レポート失敗: ${e.message}"
                }
            }
        }
    }

    fun installLast() {
        val apk = lastBuiltApk
        if (apk == null) {
            statusText = "先にAPKを生成してください(AAB/Playパッケージは直接インストールできません)。"
            return
        }
        runCatching { Installer.install(context, apk, appName) }
            .onFailure { statusText = "インストール起動に失敗: ${it.message}" }
    }

    fun analyzeLast() {
        val art = lastBuiltArtifact ?: return
        analyzeError = null
        analyzeInfo = null
        screen = Screen.ANALYZER
        scope.launch {
            val info = withContext(Dispatchers.Default) {
                runCatching { com.apkbuilder.core.ArtifactAnalyzer.analyze(art) }.getOrNull()
            }
            if (info != null) analyzeInfo = info else analyzeError = "解析に失敗しました"
        }
    }

    when (screen) {
        Screen.ANALYZER -> AnalyzerScreen(
            info = analyzeInfo,
            error = analyzeError,
            onPick = { pickArtifact.launch(arrayOf("*/*")) },
            onBack = { screen = Screen.BUILDER },
        )
        Screen.EDITOR -> GameEditorScreen(
            text = editedHtml,
            onTextChange = { editedHtml = it },
            onInsertStarter = { if (editedHtml.isBlank()) editedHtml = STARTER_HTML },
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
            onPickUpdateApk = { pickUpdateApk.launch(arrayOf("*/*")) },
            isReadingUpdateSource = isReadingUpdateSource,
            updateSourceStatus = updateSourceStatus,
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
            updateKeystoreSet = updateKeystoreUri != null, onPickKeystore = { pickKeystore.launch(arrayOf("*/*")) },
            onClearKeystore = { updateKeystoreUri = null },
            ksStorePw = ksStorePw, onKsStorePw = { ksStorePw = it },
            ksKeyPw = ksKeyPw, onKsKeyPw = { ksKeyPw = it },
            ksAlias = ksAlias, onKsAlias = { ksAlias = it },
            onSigningReport = { showSigningReport() },
            onAnalyzeFile = {
                analyzeInfo = null; analyzeError = null; screen = Screen.ANALYZER
            },
            hasBuild = pendingZip != null,
            canInstall = lastBuiltApk != null,
            onInstall = { installLast() },
            onAnalyzeBuild = { analyzeLast() },
            isGenerating = isGenerating, statusText = statusText, onGenerate = { startGenerate() },
        )
    }
}

@Composable
private fun BuilderForm(
    onPickUpdateApk: () -> Unit,
    isReadingUpdateSource: Boolean,
    updateSourceStatus: String?,
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
    updateKeystoreSet: Boolean, onPickKeystore: () -> Unit, onClearKeystore: () -> Unit,
    ksStorePw: String, onKsStorePw: (String) -> Unit,
    ksKeyPw: String, onKsKeyPw: (String) -> Unit,
    ksAlias: String, onKsAlias: (String) -> Unit,
    onSigningReport: () -> Unit,
    onAnalyzeFile: () -> Unit,
    hasBuild: Boolean, canInstall: Boolean, onInstall: () -> Unit, onAnalyzeBuild: () -> Unit,
    isGenerating: Boolean, statusText: String, onGenerate: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("APK Builder", style = MaterialTheme.typography.headlineSmall)
        Text("HTMLゲーム、または既存サイトのPWAからAndroidアプリを作成します。", style = MaterialTheme.typography.bodySmall)

        Divider()
        Text("既存のAPK/AABからアップデートを作成", style = MaterialTheme.typography.titleMedium)
        Text(
            "以前作ったAPK(またはAAB)を選ぶと、アプリ名・パッケージ・権限を自動で読み取り、" +
                "各項目に反映します(権限のチェックボックスも自動でオン)。バージョンは自動で +0.1 されます" +
                "(下の欄でいつでも変更できます)。あとは新しいHTMLを選ぶだけでアップデート版になります。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !isReadingUpdateSource, onClick = onPickUpdateApk) { Text("元のAPK/AABを選んで自動入力") }
            if (isReadingUpdateSource) CircularProgressIndicator(modifier = Modifier.size(20.dp))
        }
        updateSourceStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Divider()
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
        Text("署名 / アップデート", style = MaterialTheme.typography.titleMedium)
        Text(
            "既存のkeystoreを指定すると、その鍵で署名します(以前のバージョンに上書きインストール" +
                "できるアップデートを作れます)。指定しない場合は毎回新しい鍵を生成します。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickKeystore) {
                Text(if (updateKeystoreSet) "keystoreを変更" else "既存のkeystoreを選択(.keystore/.jks/.p12)")
            }
            if (updateKeystoreSet) OutlinedButton(onClick = onClearKeystore) { Text("解除") }
        }
        if (updateKeystoreSet) {
            OutlinedTextField(
                value = ksStorePw, onValueChange = onKsStorePw,
                label = { Text("ストアパスワード (storePassword)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ksKeyPw, onValueChange = onKsKeyPw,
                label = { Text("鍵パスワード (keyPassword / 空ならストアと同じ)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ksAlias, onValueChange = onKsAlias,
                label = { Text("エイリアス (空なら最初の鍵を自動使用)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "※ 本ツールで作った keystore-info.txt に alias / storePassword / keyPassword が書いてあります。",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onSigningReport) { Text("署名レポート(SHA-1/256)を表示") }
            Text(
                "SHA-1 は Firebase / Google Sign-In / Maps などの登録で必要です(Android Studio の signingReport 相当)。",
                style = MaterialTheme.typography.bodySmall,
            )
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
        if (hasBuild) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canInstall) Button(onClick = onInstall) { Text("実機にインストール") }
                OutlinedButton(onClick = onAnalyzeBuild) { Text("生成物を解析") }
            }
        }
        if (statusText.isNotEmpty()) Text(statusText, style = MaterialTheme.typography.bodyMedium)

        Divider()
        Text("APK / AAB 分析", style = MaterialTheme.typography.titleMedium)
        Text(
            "APK・AABファイルを選ぶと、パッケージ・バージョン・アプリ名・minSdk/targetSdk・" +
                "権限一覧・DEX数・合計サイズ・大きいファイルの内訳を表示します。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onAnalyzeFile) { Text("APK / AAB を分析(ファイルから)") }
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

/**
 * Bumps a versionName by +0.1 for an update. "1.0" -> "1.1", "1.9" -> "2.0",
 * "2" -> "2.1". Non-numeric dotted versions (e.g. "1.2.3") fall back to
 * incrementing the last numeric segment ("1.2.4"). Unparseable input is
 * returned unchanged so the user can edit it by hand.
 */
private fun bumpVersionName(name: String): String {
    val trimmed = name.trim()
    val asNumber = trimmed.toDoubleOrNull()
    if (asNumber != null) {
        val rounded = Math.round((asNumber + 0.1) * 10.0) / 10.0
        return if (rounded == Math.floor(rounded)) "${rounded.toLong()}.0" else rounded.toString()
    }
    val parts = trimmed.split(".").toMutableList()
    val lastNumeric = parts.indexOfLast { it.toIntOrNull() != null }
    if (lastNumeric >= 0) {
        parts[lastNumeric] = (parts[lastNumeric].toInt() + 1).toString()
        return parts.joinToString(".")
    }
    return trimmed
}

private fun defaultPackageId(appName: String): String {
    val slug = appName.lowercase().replace(Regex("[^a-z0-9]+"), "").ifEmpty { "app" }
    val safe = if (slug.first().isDigit()) "a$slug" else slug
    return "com.apkbuilder.generated.$safe"
}
