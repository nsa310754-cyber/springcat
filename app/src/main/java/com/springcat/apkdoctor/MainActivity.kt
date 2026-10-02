package com.springcat.apkdoctor

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.springcat.apkdoctor.ui.ApkDoctorTheme
import com.springcat.apkdoctor.ui.MainScreen
import com.springcat.apkdoctor.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.onFilesSelected(uris)
    }

    private val createFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri?.let(viewModel::save)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ApkDoctorTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                MainScreen(
                    state = state,
                    onPickFile = {
                        // Bundles arrive with assorted MIME types, so accept anything
                        // and decide by content. Multiple selection covers the
                        // base.apk + split APKs case.
                        pickFiles.launch(arrayOf("*/*"))
                    },
                    onRepair = viewModel::repair,
                    onInstall = viewModel::install,
                    onRootInstall = viewModel::installWithRoot,
                    onSave = { createFile.launch(viewModel.suggestedFileName()) },
                    onReset = viewModel::reset,
                    onGrantUnknownSources = { startActivity(viewModel.unknownSourcesIntent()) },
                    onUninstallConflict = { startActivity(viewModel.uninstallIntent(it)) },
                    onMessagesShown = viewModel::dismissMessages,
                )
            }
        }

        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshInstallPermission()
    }

    /** Opens an APK (or several split APKs) shared or tapped from another app. */
    private fun handleIncoming(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { viewModel.onFileSelected(it) }

            Intent.ACTION_SEND -> streamExtra(intent)?.let { viewModel.onFileSelected(it) }

            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                uris?.filterNotNull()?.takeIf { it.isNotEmpty() }?.let { viewModel.onFilesSelected(it) }
            }
        }
    }

    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
}
