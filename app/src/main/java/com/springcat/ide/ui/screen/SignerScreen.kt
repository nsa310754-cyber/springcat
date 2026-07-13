package com.springcat.ide.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.springcat.ide.core.keystore.KeystoreManager
import com.springcat.ide.core.signing.ApkSignerService
import com.springcat.ide.core.signing.JarSigner
import com.springcat.ide.core.signing.SigningResult
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SignerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { KeystoreManager() }

    var keystoreUri by remember { mutableStateOf<Uri?>(null) }
    var inputUri by remember { mutableStateOf<Uri?>(null) }
    var storePassword by remember { mutableStateOf("") }
    var keyPassword by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("upload") }
    var storeType by remember { mutableStateOf("PKCS12") }
    var status by remember { mutableStateOf<String?>(null) }
    var signedFile by remember { mutableStateOf<File?>(null) }
    var working by remember { mutableStateOf(false) }

    val pickKeystore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        keystoreUri = it
    }
    val pickInput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        inputUri = it
    }
    val saveOutput = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val f = signedFile ?: return@rememberLauncherForActivityResult
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        }.onSuccess { status = "Signed package saved." }.onFailure { status = "Save failed: ${it.message}" }
    }

    Scaffold(topBar = { ScreenHeader(title = "APK / AAB Signer", onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = { pickKeystore.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(keystoreUri?.let { "Keystore: ${it.lastPathSegment}" } ?: "Choose keystore (.p12 / .jks)")
            }
            OutlinedButton(onClick = { pickInput.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(inputUri?.let { "Input: ${it.lastPathSegment}" } ?: "Choose APK or AAB to sign")
            }

            passwordField("Keystore password", storePassword) { storePassword = it }
            passwordField("Key password (blank = same)", keyPassword) { keyPassword = it }
            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it },
                label = { Text("Key alias") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = storeType,
                onValueChange = { storeType = it.uppercase() },
                label = { Text("Store type (PKCS12 / JKS)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    val ks = keystoreUri
                    val input = inputUri
                    if (ks == null || input == null) {
                        status = "Pick both a keystore and an input file first."
                        return@Button
                    }
                    working = true
                    status = "Signing…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            signPackage(
                                context, manager, ks, input, storePassword, keyPassword.ifBlank { storePassword },
                                alias, storeType,
                            )
                        }
                        working = false
                        when (result) {
                            is SignOutcome.Ok -> {
                                signedFile = result.file
                                status = "Signed with ${result.schemes}. Tap “Save output”."
                            }
                            is SignOutcome.Error -> status = "Error: ${result.message}"
                        }
                    }
                },
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (working) "Working…" else "Sign") }

            signedFile?.let { f ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Signed output ready", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            val name = inputUri?.lastPathSegment?.substringAfterLast('/') ?: "app"
                            saveOutput.launch(signedName(name))
                        }) { Text("Save output") }
                    }
                }
            }

            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private sealed interface SignOutcome {
    data class Ok(val file: File, val schemes: String) : SignOutcome
    data class Error(val message: String) : SignOutcome
}

private fun signPackage(
    context: android.content.Context,
    manager: KeystoreManager,
    keystoreUri: Uri,
    inputUri: Uri,
    storePassword: String,
    keyPassword: String,
    alias: String,
    storeType: String,
): SignOutcome {
    return try {
        val keystoreBytes = context.contentResolver.openInputStream(keystoreUri)?.use { it.readBytes() }
            ?: return SignOutcome.Error("Cannot read keystore")
        val keyStore = manager.load(keystoreBytes, storeType, storePassword.toCharArray())

        val inputName = inputUri.lastPathSegment.orEmpty()
        val isAab = inputName.endsWith(".aab", ignoreCase = true)

        val tmpIn = File.createTempFile("in_", if (isAab) ".aab" else ".apk", context.cacheDir)
        context.contentResolver.openInputStream(inputUri)?.use { src -> tmpIn.outputStream().use { src.copyTo(it) } }
            ?: return SignOutcome.Error("Cannot read input package")
        val tmpOut = File.createTempFile("out_", if (isAab) ".aab" else ".apk", context.cacheDir)

        val result: SigningResult = if (isAab) {
            val key = keyStore.getKey(alias, keyPassword.toCharArray()) as? PrivateKey
                ?: return SignOutcome.Error("No private key for alias '$alias'")
            val cert = keyStore.getCertificateChain(alias)?.firstOrNull() as? X509Certificate
                ?: return SignOutcome.Error("No certificate for alias '$alias'")
            JarSigner().sign(tmpIn, tmpOut, key, cert)
        } else {
            ApkSignerService().sign(tmpIn, tmpOut, keyStore, alias, keyPassword.toCharArray())
        }

        tmpIn.delete()
        when (result) {
            is SigningResult.Success -> SignOutcome.Ok(result.outputFile, result.schemes)
            is SigningResult.Failure -> SignOutcome.Error(result.message)
        }
    } catch (t: Throwable) {
        SignOutcome.Error(t.message ?: t.javaClass.simpleName)
    }
}

private fun signedName(original: String): String {
    val base = original.substringBeforeLast('.')
    val ext = original.substringAfterLast('.', "apk")
    return "${base}-signed.$ext"
}

@Composable
private fun passwordField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}
