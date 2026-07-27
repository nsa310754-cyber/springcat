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

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onFileSelected)
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
                        // and decide by content.
                        pickFile.launch(arrayOf("*/*"))
                    },
                    onRepair = viewModel::repair,
                    onInstall = viewModel::install,
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

    /** Opens an APK shared or tapped from another app. */
    private fun handleIncoming(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }

            else -> null
        }
        uri?.let(viewModel::onFileSelected)
    }
}
