package com.springcat.ide.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.springcat.ide.core.file.EditorBridge
import com.springcat.ide.core.file.WorkspaceFile
import com.springcat.ide.core.file.WorkspaceStore
import com.springcat.ide.core.lang.Language
import com.springcat.ide.core.lang.Problem
import com.springcat.ide.core.lang.SyntaxChecker
import com.springcat.ide.core.lang.SyntaxHighlighter
import com.springcat.ide.core.run.CodeRunner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(onBack: () -> Unit, onRun: () -> Unit) {
    val context = LocalContext.current
    val store = remember { WorkspaceStore(context.filesDir) }
    val highlighter = remember { SyntaxHighlighter() }

    var files by remember { mutableStateOf(store.list()) }
    var open by remember { mutableStateOf<WorkspaceFile?>(null) }
    var content by remember { mutableStateOf("") }
    var showNewDialog by remember { mutableStateOf(false) }

    val current = open
    if (current == null) {
        Scaffold(
            topBar = { ScreenHeader(title = "Editor", onBack = onBack) },
            floatingActionButton = {
                FloatingActionButton(onClick = { showNewDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New file")
                }
            },
        ) { padding ->
            if (files.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(
                        "No files yet. Tap + to create one.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(files, key = { it.name }) { f ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(f.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${Language.fromFileName(f.name).displayName} · ${f.sizeBytes} B",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        open = f
                                        content = store.read(f)
                                    }) { Text("Open") }
                                    OutlinedButton(onClick = {
                                        store.delete(f)
                                        files = store.list()
                                    }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showNewDialog) {
            NewFileDialog(
                onDismiss = { showNewDialog = false },
                onCreate = { name ->
                    val created = store.create(name)
                    files = store.list()
                    open = created
                    content = store.read(created)
                    showNewDialog = false
                },
            )
        }
        return
    }

    val language = remember(current.name) { Language.fromFileName(current.name) }
    val problems = remember(content, language) { SyntaxChecker.check(content, language) }
    Scaffold(
        topBar = {
            ScreenHeader(
                title = current.name,
                onBack = {
                    store.write(current.name, content)
                    files = store.list()
                    open = null
                },
                action = {
                    if (CodeRunner.isRunnable(language)) {
                        IconButton(onClick = {
                            store.write(current.name, content)
                            EditorBridge.set(current.name, content)
                            onRun()
                        }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Run")
                        }
                    }
                    TextButton(onClick = { store.write(current.name, content) }) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = "${language.displayName}  ·  ${content.length} chars" +
                    if (problems.isEmpty()) "  ·  ✓ no problems" else "  ·  ${problems.size} problem(s)",
                style = MaterialTheme.typography.labelMedium,
                color = if (problems.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    Color(0xFFFF6B6B)
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            BasicTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = remember(language) {
                    VisualTransformation { text ->
                        TransformedText(
                            highlighter.highlight(text.text, language),
                            androidx.compose.ui.text.input.OffsetMapping.Identity,
                        )
                    }
                },
            )
            if (problems.isNotEmpty()) {
                ProblemsPanel(problems)
            }
        }
    }
}

@Composable
private fun ProblemsPanel(problems: List<Problem>) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 160.dp)
            .background(Color(0xFF1A0F0F))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            "Problems",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFFF6B6B),
        )
        LazyColumn {
            items(problems) { p ->
                Text(
                    "L${p.line}:${p.column}  ${p.message}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFFE6A5A5),
                )
            }
        }
    }
}

@Composable
private fun NewFileDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New file") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("File name (e.g. Main.kt)") },
                    singleLine = true,
                )
                if (name.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Detected: ${Language.fromFileName(name).displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
