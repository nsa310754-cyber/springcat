package com.springcat.ide.ui.screen

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.springcat.ide.core.file.EditorBridge
import com.springcat.ide.core.run.CodeRunner

private data class LogLine(val level: ConsoleMessage.MessageLevel, val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(onBack: () -> Unit) {
    val name = EditorBridge.fileName
    val language = EditorBridge.language
    val logs = remember { mutableStateListOf<LogLine>() }
    var reloadKey by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            ScreenHeader(
                title = "Run · $name",
                onBack = onBack,
                action = {
                    IconButton(onClick = { logs.clear(); reloadKey++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        webViewClient = WebViewClient()
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                logs.add(
                                    LogLine(
                                        message.messageLevel(),
                                        "${message.message()}  (line ${message.lineNumber()})",
                                    ),
                                )
                                return true
                            }
                        }
                    }
                },
                update = { web ->
                    // reloadKey participates so tapping reload reloads the document.
                    @Suppress("UNUSED_EXPRESSION") reloadKey
                    val html = CodeRunner.buildDocument(EditorBridge.source, language)
                    web.loadDataWithBaseURL(
                        "file:///android_asset/",
                        html,
                        "text/html",
                        "utf-8",
                        null,
                    )
                },
            )

            ConsolePanel(logs)
        }
    }
}

@Composable
private fun ConsolePanel(logs: List<LogLine>) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp, max = 220.dp)
            .background(Color(0xFF0A100D))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "Console (${logs.size})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (logs.isEmpty()) {
            Text(
                "No output yet.",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF6B7C74),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(logs) { line ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            line.text,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colorFor(line.level),
                        )
                    }
                }
            }
        }
    }
}

private fun colorFor(level: ConsoleMessage.MessageLevel): Color = when (level) {
    ConsoleMessage.MessageLevel.ERROR -> Color(0xFFFF6B6B)
    ConsoleMessage.MessageLevel.WARNING -> Color(0xFFF7C948)
    else -> Color(0xFFB6C6BE)
}
