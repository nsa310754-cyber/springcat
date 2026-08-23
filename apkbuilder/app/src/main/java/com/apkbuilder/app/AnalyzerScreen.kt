package com.apkbuilder.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apkbuilder.core.ArtifactInfo

/** Displays an ArtifactInfo — the Android-Studio-style "APK Analyzer" view. */
@Composable
fun AnalyzerScreen(info: ArtifactInfo?, error: String?, onPick: () -> Unit, onBack: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("APK / AAB 解析", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onPick) { Text("apk / aab ファイルを選択") }

            error?.let { Text("エラー: $it", color = MaterialTheme.colorScheme.error) }

            if (info != null) {
                Divider()
                Row2("種類", info.kind)
                Row2("パッケージ", info.packageId ?: "?")
                Row2("バージョン", "${info.versionName ?: "?"} (code ${info.versionCode ?: "?"})")
                Row2("アプリ名", info.label ?: "(リソース参照)")
                Row2("minSdk / targetSdk", "${info.minSdk ?: "?"} / ${info.targetSdk ?: "?"}")
                Row2("ファイル数", info.fileCount.toString())
                Row2("DEX 数", info.dexCount.toString())
                Row2("合計サイズ(非圧縮)", humanSize(info.totalUncompressedSize))

                Divider()
                Text("権限 (${info.permissions.size})", style = MaterialTheme.typography.titleSmall)
                if (info.permissions.isEmpty()) {
                    Text("(なし)", style = MaterialTheme.typography.bodySmall)
                } else {
                    info.permissions.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall) }
                }

                Divider()
                Text("大きいファイル (上位)", style = MaterialTheme.typography.titleSmall)
                info.largestEntries.forEach {
                    Text(
                        "${humanSize(it.size).padStart(9)}  ${it.name}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }

            Divider()
            OutlinedButton(onClick = onBack) { Text("戻る") }
        }
    }
}

@Composable
private fun Row2(key: String, value: String) {
    Text("$key: $value", style = MaterialTheme.typography.bodyMedium)
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
