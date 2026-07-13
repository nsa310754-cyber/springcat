package com.springcat.ide.ui.screen

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UploadFile
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
    var importError by remember { mutableStateOf<String?>(null) }

    // Import an existing source file from device storage via the system picker.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val imported = runCatching { importFile(context, uri, store) }
        imported
            .onSuccess { file ->
                files = store.list()
                open = file
                content = store.read(file)
                importError = null
            }
            .onFailure { importError = "Import failed: ${it.message}" }
    }

    // Export the whole workspace as a single .zip through the system file picker.
    val exportZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { store.exportZip(it) }
                ?: error("Could not open destination")
        }
            .onSuccess { count ->
                importError = null
                Toast.makeText(context, "Exported $count file(s) to ZIP", Toast.LENGTH_SHORT).show()
            }
            .onFailure { importError = "Export failed: ${it.message}" }
    }

    val current = open
    if (current == null) {
        Scaffold(
            topBar = {
                ScreenHeader(
                    title = "Editor",
                    onBack = onBack,
                    action = {
                        if (files.isNotEmpty()) {
                            IconButton(onClick = { exportZipLauncher.launch("springcat-project.zip") }) {
                                Icon(Icons.Filled.FolderZip, contentDescription = "Export project as ZIP")
                            }
                        }
                        IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Filled.UploadFile, contentDescription = "Import file")
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showNewDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New file")
                }
            },
        ) { padding ->
            if (files.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(
                        importError ?: "No files yet. Tap + to create, or ⬆ to import from a file.",
                        color = if (importError != null) Color(0xFFFF6B6B) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    importError?.let { err ->
                        item {
                            Text(
                                err,
                                color = Color(0xFFFF6B6B),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
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

/**
 * Reads a picked document as text and saves it into the workspace under its
 * original file name, so imported code lands in the editor with the right
 * language detected. Refuses obviously-binary files.
 */
private fun importFile(context: Context, uri: Uri, store: WorkspaceStore): WorkspaceFile {
    val name = queryDisplayName(context, uri)
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("Could not open the selected file")
    // NUL bytes are a reliable signal that this isn't a text source file.
    if (bytes.take(8000).contains(0.toByte())) {
        throw IllegalArgumentException("Not a text file")
    }
    val text = bytes.toString(Charsets.UTF_8)
    val target = if (store.exists(name)) uniqueName(store, name) else name
    return store.write(target, text)
}

/** Resolve a human file name for a content Uri, falling back to the path tail. */
private fun queryDisplayName(context: Context, uri: Uri): String {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)?.let { if (it.isNotBlank()) return it }
            }
        }
    return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "imported.txt"
}

/** Add a numeric suffix so importing never silently overwrites an existing file. */
private fun uniqueName(store: WorkspaceStore, name: String): String {
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var i = 1
    while (store.exists("$base-$i$ext")) i++
    return "$base-$i$ext"
}
