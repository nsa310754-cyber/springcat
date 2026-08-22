package com.apkbuilder.app

import android.content.Context
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.apkbuilder.core.ApkAssembler
import com.apkbuilder.core.ApkSigner
import com.apkbuilder.core.AssetLinksGenerator
import com.apkbuilder.core.BuildConfig
import com.apkbuilder.core.FirebaseConfigParser
import com.apkbuilder.core.GameObfuscator
import com.apkbuilder.core.KeystoreGenerator
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    BuilderScreen()
                }
            }
        }
    }
}

@Composable
private fun BuilderScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var appName by remember { mutableStateOf(DEFAULT_APP_NAME) }
    var packageId by remember { mutableStateOf(defaultPackageId(DEFAULT_APP_NAME)) }
    var versionName by remember { mutableStateOf("1.0") }
    var versionCode by remember { mutableStateOf("1") }

    var iconUri by remember { mutableStateOf<Uri?>(null) }
    var iconPreview by remember { mutableStateOf<ImageBitmap?>(null) }

    // Local-HTML mode
    var gameFileUri by remember { mutableStateOf<Uri?>(null) }
    var gameFolderUri by remember { mutableStateOf<Uri?>(null) }

    // PWA mode
    var pwaMode by remember { mutableStateOf(false) }
    var pwaUrl by remember { mutableStateOf("") }
    var isCheckingManifest by remember { mutableStateOf(false) }
    var pwaManifest by remember { mutableStateOf<PwaManifest?>(null) }
    var pwaIconBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pwaCheckStatus by remember { mutableStateOf<String?>(null) }

    val selectedPermissions = remember { mutableStateOf(setOf(INTERNET_PERMISSION)) }

    // Other services (Firebase / AdMob / Play Services)
    var googleServicesUri by remember { mutableStateOf<Uri?>(null) }
    var admobAppId by remember { mutableStateOf("") }
    var admobBannerUnitId by remember { mutableStateOf("") }

    var obfuscateGame by remember { mutableStateOf(false) }

    var isGenerating by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var pendingZip by remember { mutableStateOf<ByteArray?>(null) }

    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            iconUri = uri
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    iconPreview = bmp?.asImageBitmap()
                }
            }
        }
    }
    val pickGameFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            gameFileUri = uri
            gameFolderUri = null
        }
    }
    val pickGameFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            gameFolderUri = uri
            gameFileUri = null
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
                statusText = "保存しました: apk / keystore / パスワード情報" +
                    (if (pwaMode) " / assetlinks.json" else "") + " を含むzipを書き出しました。"
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
                    val bmp = BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)
                    iconPreview = bmp?.asImageBitmap()
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("APK Builder", style = MaterialTheme.typography.headlineSmall)
        Text(
            "HTMLゲーム、または既存サイトのPWAからAndroidアプリ(apk)を作成します。",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = appName,
            onValueChange = {
                appName = it
                packageId = defaultPackageId(it)
            },
            label = { Text("アプリ名") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = packageId,
            onValueChange = { packageId = it },
            label = { Text("パッケージID (applicationId)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = versionName,
                onValueChange = { versionName = it },
                label = { Text("バージョン名") },
                modifier = Modifier.fillMaxWidth(0.5f),
            )
            OutlinedTextField(
                value = versionCode,
                onValueChange = { versionCode = it.filter(Char::isDigit) },
                label = { Text("バージョンコード") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Divider()
        Text("アイコン画像", style = MaterialTheme.typography.titleMedium)
        Text(
            "PWAモードでは manifest.json のアイコンを自動取得します。ここで選ぶと手動指定が優先されます。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { pickIcon.launch("image/*") }) {
                Text(if (iconUri == null) "画像を選択" else "画像を変更")
            }
            iconPreview?.let { bmp ->
                Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(48.dp))
            }
        }

        Divider()
        Text("作成方法", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!pwaMode) {
                Button(onClick = {}) { Text("ローカルHTML") }
            } else {
                OutlinedButton(onClick = { pwaMode = false }) { Text("ローカルHTML") }
            }
            if (pwaMode) {
                Button(onClick = {}) { Text("PWAモード (URL)") }
            } else {
                OutlinedButton(onClick = { pwaMode = true }) { Text("PWAモード (URL)") }
            }
        }

        if (pwaMode) {
            OutlinedTextField(
                value = pwaUrl,
                onValueChange = { pwaUrl = it },
                label = { Text("アプリにしたいサイトのURL") },
                placeholder = { Text("https://example.com/") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !isCheckingManifest, onClick = { checkPwaManifest() }) {
                    Text("manifest.jsonを確認")
                }
                if (isCheckingManifest) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }
            pwaCheckStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        } else {
            Text("ゲーム本体 (HTML/JS/CSS)", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickGameFile.launch(arrayOf("text/html")) }) {
                    Text("HTMLファイルを1つ選択")
                }
                OutlinedButton(onClick = { pickGameFolder.launch(null) }) {
                    Text("フォルダを選択")
                }
            }
            Text(
                when {
                    gameFileUri != null -> "選択中: $gameFileUri"
                    gameFolderUri != null -> "選択中(フォルダ): $gameFolderUri"
                    else -> "未選択(index.html/game.htmlを含むフォルダ、または単一のHTMLファイル)"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = obfuscateGame && !pwaMode, enabled = !pwaMode, onCheckedChange = { obfuscateGame = it })
            Text(
                "ゲーム本体ファイル(game.html)を難読化(暗号化)する" +
                    if (pwaMode) "(PWAモードでは対象外)" else "",
            )
        }

        Divider()
        Text("その他のサービス (任意)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Firebase・AdMob・Play Servicesを組み込みます。どちらか一方でも指定すると、それらを" +
                "同梱した少し大きめのテンプレートでビルドされます(指定しない場合は軽量版のまま)。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickGoogleServices.launch(arrayOf("application/json", "*/*")) }) {
                Text(if (googleServicesUri == null) "google-services.json を追加" else "google-services.json を変更")
            }
        }
        if (googleServicesUri != null) {
            Text("選択中(Firebase): $googleServicesUri", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = admobAppId,
            onValueChange = { admobAppId = it },
            label = { Text("AdMob App ID (任意)") },
            placeholder = { Text("ca-app-pub-xxxxxxxxxxxxxxxx~yyyyyyyyyy") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = admobBannerUnitId,
            onValueChange = { admobBannerUnitId = it },
            label = { Text("AdMob バナー広告ユニットID (任意)") },
            placeholder = { Text("ca-app-pub-xxxxxxxxxxxxxxxx/yyyyyyyyyy") },
            modifier = Modifier.fillMaxWidth(),
        )

        Divider()
        Text("必要な権限", style = MaterialTheme.typography.titleMedium)
        for (option in PERMISSION_OPTIONS) {
            val forced = pwaMode && option.permission == INTERNET_PERMISSION
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = forced || selectedPermissions.value.contains(option.permission),
                    enabled = !forced,
                    onCheckedChange = { checked ->
                        selectedPermissions.value = if (checked) {
                            selectedPermissions.value + option.permission
                        } else {
                            selectedPermissions.value - option.permission
                        }
                    },
                )
                Text(option.label + if (forced) "(PWAモードのため必須)" else "")
            }
        }

        Divider()
        Button(
            enabled = !isGenerating,
            onClick = {
                if (pwaMode) {
                    if (pwaManifest == null) {
                        statusText = "先に「manifest.jsonを確認」を実行してください。"
                        return@Button
                    }
                } else if (gameFileUri == null && gameFolderUri == null) {
                    statusText = "ゲームのHTMLファイルかフォルダを選択してください。"
                    return@Button
                }
                if (!packageId.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))) {
                    statusText = "パッケージIDの形式が正しくありません (例: com.example.mygame)"
                    return@Button
                }
                isGenerating = true
                statusText = "生成中..."
                val effectivePermissions = if (pwaMode) {
                    selectedPermissions.value + INTERNET_PERMISSION
                } else {
                    selectedPermissions.value
                }
                scope.launch {
                    try {
                        val zip = withContext(Dispatchers.Default) {
                            generateApk(
                                context = context,
                                appName = appName,
                                packageId = packageId,
                                versionName = versionName,
                                versionCode = versionCode.toIntOrNull() ?: 1,
                                permissions = effectivePermissions.toList(),
                                iconUri = iconUri,
                                gameFileUri = if (pwaMode) null else gameFileUri,
                                gameFolderUri = if (pwaMode) null else gameFolderUri,
                                pwaManifest = pwaManifest,
                                pwaIconBytes = pwaIconBytes,
                                obfuscateGame = obfuscateGame && !pwaMode,
                                googleServicesUri = googleServicesUri,
                                admobAppId = admobAppId.trim().takeIf { it.isNotBlank() },
                                admobBannerUnitId = admobBannerUnitId.trim().takeIf { it.isNotBlank() },
                            )
                        }
                        pendingZip = zip
                        statusText = "生成が完了しました。保存先を選んでください。"
                        saveOutput.launch("$appName-build.zip")
                    } catch (e: Exception) {
                        statusText = "エラー: ${e.message}"
                    } finally {
                        isGenerating = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("APKを生成")
        }

        if (isGenerating) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text("APK / keystore を作成しています…")
            }
        }
        if (statusText.isNotEmpty()) {
            Text(statusText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun defaultPackageId(appName: String): String {
    val slug = appName.lowercase()
        .replace(Regex("[^a-z0-9]+"), "")
        .ifEmpty { "app" }
    val safe = if (slug.first().isDigit()) "a$slug" else slug
    return "com.apkbuilder.generated.$safe"
}

private fun buildPwaRedirectHtml(startUrl: String): String = """
    <!DOCTYPE html>
    <html><head><meta charset="utf-8">
    <meta http-equiv="refresh" content="0; url=$startUrl">
    <script>location.replace("$startUrl");</script>
    </head><body></body></html>
""".trimIndent()

private fun generateApk(
    context: Context,
    appName: String,
    packageId: String,
    versionName: String,
    versionCode: Int,
    permissions: List<String>,
    iconUri: Uri?,
    gameFileUri: Uri?,
    gameFolderUri: Uri?,
    pwaManifest: PwaManifest?,
    pwaIconBytes: ByteArray?,
    obfuscateGame: Boolean,
    googleServicesUri: Uri?,
    admobAppId: String?,
    admobBannerUnitId: String?,
): ByteArray {
    val usesServices = googleServicesUri != null || admobAppId != null
    val templateAsset = if (usesServices) "template-services.apk" else "template.apk"
    val templateBytes = context.assets.open(templateAsset).use { it.readBytes() }

    val fileOverrides = HashMap<String, ByteArray>()
    if (pwaManifest != null) {
        fileOverrides["assets/game.html"] = buildPwaRedirectHtml(pwaManifest.startUrl).toByteArray()
    } else {
        fileOverrides.putAll(
            when {
                gameFileUri != null -> WebBundleReader.readSingleHtmlFile(context.contentResolver, gameFileUri)
                gameFolderUri != null -> WebBundleReader.readFolder(context, gameFolderUri)
                else -> error("no game source selected")
            },
        )
    }

    // A manually chosen icon always wins over the one auto-downloaded from the manifest.
    if (iconUri != null) {
        val iconBytes = context.contentResolver.openInputStream(iconUri)?.use { it.readBytes() }
            ?: error("could not read the chosen icon")
        fileOverrides.putAll(IconResizer.buildIconOverrides(iconBytes))
    } else if (pwaIconBytes != null) {
        // Best-effort: an undecodable auto-fetched icon (e.g. an SVG that slipped through)
        // shouldn't fail the whole build — fall back to the template's default icon instead.
        runCatching { fileOverrides.putAll(IconResizer.buildIconOverrides(pwaIconBytes)) }
    }

    // Obfuscation only ever applies to the entry HTML (assets/game.html) — other referenced
    // files (js/css/images) stay plain, matching this repo's existing ../android encryption scheme.
    val filesToOmit = mutableSetOf<String>()
    if (obfuscateGame) {
        val plainHtml = fileOverrides.remove("assets/game.html")
            ?: error("game.html がありません(難読化の対象がありません)")
        fileOverrides["assets/game.enc"] = GameObfuscator.encryptGameHtml(plainHtml)
        filesToOmit.add("assets/game.html")
    }

    if (googleServicesUri != null) {
        val googleServicesJson = context.contentResolver.openInputStream(googleServicesUri)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("google-services.json を読み込めませんでした")
        val firebaseConfig = FirebaseConfigParser.toRuntimeConfig(googleServicesJson, packageId)
        fileOverrides["assets/firebase-config.json"] = firebaseConfig.json.toByteArray()
    }
    if (admobAppId != null && admobBannerUnitId != null) {
        fileOverrides["assets/admob-config.json"] = """{"bannerAdUnitId":"$admobBannerUnitId"}""".toByteArray()
    }

    val config = BuildConfig(
        appLabel = appName,
        packageId = packageId,
        versionName = versionName,
        versionCode = versionCode,
        permissions = permissions,
        admobApplicationId = admobAppId,
    )

    val unsigned = ApkAssembler.assemble(templateBytes, config, fileOverrides, filesToOmit)
    val keystore = KeystoreGenerator.generate(commonName = appName)
    val signed = ApkSigner.sign(unsigned, keystore)

    val assetLinksJson = if (pwaManifest != null) {
        AssetLinksGenerator.generate(packageId, keystore.certificateSha256Fingerprint)
    } else {
        null
    }

    return OutputBundler.bundle(appName, signed, keystore, assetLinksJson)
}
