package com.apkbuilder.app

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.apkbuilder.core.ApkAssembler
import com.apkbuilder.core.ApkSigner
import com.apkbuilder.core.BuildConfig
import com.apkbuilder.core.KeystoreGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PermissionOption(val label: String, val permission: String)

private val PERMISSION_OPTIONS = listOf(
    PermissionOption("インターネット通信", "android.permission.INTERNET"),
    PermissionOption("バイブレーション", "android.permission.VIBRATE"),
    PermissionOption("カメラ", "android.permission.CAMERA"),
    PermissionOption("マイク", "android.permission.RECORD_AUDIO"),
    PermissionOption("通知", "android.permission.POST_NOTIFICATIONS"),
    PermissionOption("位置情報(高精度)", "android.permission.ACCESS_FINE_LOCATION"),
    PermissionOption("位置情報(おおよそ)", "android.permission.ACCESS_COARSE_LOCATION"),
    PermissionOption("写真/動画の読み取り", "android.permission.READ_EXTERNAL_STORAGE"),
    PermissionOption("ストレージへの書き込み", "android.permission.WRITE_EXTERNAL_STORAGE"),
)

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

    var appName by remember { mutableStateOf("マイゲーム") }
    var packageId by remember { mutableStateOf(defaultPackageId("マイゲーム")) }
    var versionName by remember { mutableStateOf("1.0") }
    var versionCode by remember { mutableStateOf("1") }

    var iconUri by remember { mutableStateOf<Uri?>(null) }
    var iconPreview by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    var gameFileUri by remember { mutableStateOf<Uri?>(null) }
    var gameFolderUri by remember { mutableStateOf<Uri?>(null) }

    val selectedPermissions = remember { mutableStateOf(setOf("android.permission.INTERNET")) }

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
    val saveOutput = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val zip = pendingZip
        if (uri != null && zip != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(zip) }
            }.onSuccess {
                statusText = "保存しました: apk / keystore / パスワード情報を含むzipを書き出しました。"
            }.onFailure {
                statusText = "保存に失敗しました: ${it.message}"
            }
            pendingZip = null
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
            "HTMLゲーム(またはWebアプリ)からAndroidアプリ(apk)を作成します。アイコン・権限・アプリ名を選んで「APKを生成」を押してください。",
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { pickIcon.launch("image/*") }) {
                Text(if (iconUri == null) "画像を選択" else "画像を変更")
            }
            iconPreview?.let { bmp ->
                Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(48.dp))
            }
        }

        Divider()
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
                gameFileUri != null -> "選択中: ${gameFileUri}"
                gameFolderUri != null -> "選択中(フォルダ): ${gameFolderUri}"
                else -> "未選択(index.html/game.htmlを含むフォルダ、または単一のHTMLファイル)"
            },
            style = MaterialTheme.typography.bodySmall,
        )

        Divider()
        Text("必要な権限", style = MaterialTheme.typography.titleMedium)
        for (option in PERMISSION_OPTIONS) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = selectedPermissions.value.contains(option.permission),
                    onCheckedChange = { checked ->
                        selectedPermissions.value = if (checked) {
                            selectedPermissions.value + option.permission
                        } else {
                            selectedPermissions.value - option.permission
                        }
                    },
                )
                Text(option.label)
            }
        }

        Divider()
        Button(
            enabled = !isGenerating,
            onClick = {
                val html = gameFileUri
                val folder = gameFolderUri
                if (html == null && folder == null) {
                    statusText = "ゲームのHTMLファイルかフォルダを選択してください。"
                    return@Button
                }
                if (!packageId.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))) {
                    statusText = "パッケージIDの形式が正しくありません (例: com.example.mygame)"
                    return@Button
                }
                isGenerating = true
                statusText = "生成中..."
                scope.launch {
                    try {
                        val zip = withContext(Dispatchers.Default) {
                            generateApk(
                                context = context,
                                appName = appName,
                                packageId = packageId,
                                versionName = versionName,
                                versionCode = versionCode.toIntOrNull() ?: 1,
                                permissions = selectedPermissions.value.toList(),
                                iconUri = iconUri,
                                gameFileUri = html,
                                gameFolderUri = folder,
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
                Text("APK / AAB / keystore を作成しています…")
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

private fun generateApk(
    context: android.content.Context,
    appName: String,
    packageId: String,
    versionName: String,
    versionCode: Int,
    permissions: List<String>,
    iconUri: Uri?,
    gameFileUri: Uri?,
    gameFolderUri: Uri?,
): ByteArray {
    val templateBytes = context.assets.open("template.apk").use { it.readBytes() }

    val fileOverrides = HashMap<String, ByteArray>()
    fileOverrides.putAll(
        when {
            gameFileUri != null -> WebBundleReader.readSingleHtmlFile(context.contentResolver, gameFileUri)
            gameFolderUri != null -> WebBundleReader.readFolder(context, gameFolderUri)
            else -> error("no game source selected")
        },
    )
    if (iconUri != null) {
        val iconBytes = context.contentResolver.openInputStream(iconUri)?.use { it.readBytes() }
            ?: error("could not read the chosen icon")
        fileOverrides.putAll(IconResizer.buildIconOverrides(iconBytes))
    }

    val config = BuildConfig(
        appLabel = appName,
        packageId = packageId,
        versionName = versionName,
        versionCode = versionCode,
        permissions = permissions,
    )

    val unsigned = ApkAssembler.assemble(templateBytes, config, fileOverrides)
    val keystore = KeystoreGenerator.generate(commonName = appName)
    val signed = ApkSigner.sign(unsigned, keystore)

    return OutputBundler.bundle(appName, signed, keystore)
}
