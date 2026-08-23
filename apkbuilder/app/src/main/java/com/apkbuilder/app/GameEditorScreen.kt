package com.apkbuilder.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkbuilder.core.HtmlSyntaxCheck

/**
 * In-app code editor for the game HTML/JS. Edits are held in [text] (hoisted to
 * MainActivity so they persist and feed the build). Includes a static syntax
 * check; runtime validation happens in [PreviewScreen].
 */
@Composable
fun GameEditorScreen(
    text: String,
    onTextChange: (String) -> Unit,
    onInsertStarter: () -> Unit,
    onPreview: () -> Unit,
    onBack: () -> Unit,
) {
    var checkResult by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("コード編集 (game.html)", style = MaterialTheme.typography.titleMedium)
            Text(
                "ここで編集した内容が、ローカルHTMLとしてそのままビルドに使われます(ファイル選択より優先)。",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = text,
                onValueChange = {
                    onTextChange(it)
                    checkResult = null
                },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrect = false),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            checkResult?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val issues = HtmlSyntaxCheck.check(text)
                        checkResult = if (issues.isEmpty()) {
                            "✅ 構文チェック: 問題は見つかりませんでした"
                        } else {
                            "⚠ 構文の問題:\n" + issues.joinToString("\n") { "・${it.message}" }
                        }
                    }) { Text("構文チェック") }
                    OutlinedButton(onClick = onInsertStarter) { Text("ひな形挿入") }
                    OutlinedButton(onClick = onPreview) { Text("プレビュー") }
                    OutlinedButton(onClick = onBack) { Text("戻る") }
                }
            }
        }
    }
}

const val STARTER_HTML = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<title>My Game</title>
<style>
  html, body { margin: 0; height: 100%; background: #111; color: #eee;
    display: flex; align-items: center; justify-content: center;
    font-family: sans-serif; }
  #tap { font-size: 6vw; }
</style>
</head>
<body>
  <div id="tap">タップ: <span id="n">0</span></div>
  <script>
    var n = 0;
    document.body.addEventListener('click', function () {
      n += 1;
      document.getElementById('n').textContent = n;
    });
  </script>
</body>
</html>
"""
