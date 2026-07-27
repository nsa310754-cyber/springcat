package com.springcat.apkdoctor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.springcat.apkdoctor.R
import com.springcat.apkdoctor.diagnose.ApkReport
import com.springcat.apkdoctor.diagnose.Diagnosis
import com.springcat.apkdoctor.diagnose.Severity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainUiState,
    onPickFile: () -> Unit,
    onRepair: () -> Unit,
    onInstall: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onGrantUnknownSources: () -> Unit,
    onUninstallConflict: (String) -> Unit,
    onMessagesShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message, state.error) {
        val text = state.error ?: state.message
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            onMessagesShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (state.report != null && !state.busy) {
                        OutlinedButton(onClick = onReset) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.btn_reset))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.busy) {
                item { BusyCard(state.statusText) }
            }

            if (state.report == null && !state.busy) {
                item { WelcomeCard(onPickFile) }
                item { SupportedFixesCard() }
            }

            state.report?.let { report ->
                item { SummaryCard(report) }

                if (report.isInstallableAsIs) {
                    item { HealthyCard() }
                } else {
                    item { SectionTitle(stringResource(R.string.section_issues)) }
                    items(report.diagnoses) { diagnosis -> DiagnosisRow(diagnosis) }
                }

                item {
                    ActionRow(
                        state = state,
                        report = report,
                        onRepair = onRepair,
                        onInstall = onInstall,
                        onSave = onSave,
                        onGrantUnknownSources = onGrantUnknownSources,
                        onUninstallConflict = onUninstallConflict,
                    )
                }
            }

            if (state.log.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.section_log)) }
                item { LogCard(state.log) }
            }
        }
    }
}

@Composable
private fun WelcomeCard(onPickFile: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.Android,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.welcome_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.btn_pick_file))
            }
        }
    }
}

@Composable
private fun SupportedFixesCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.fixes_title), style = MaterialTheme.typography.titleSmall)
            for (line in stringResource(R.string.fixes_body).split('\n')) {
                Text("・$line", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun BusyCard(statusText: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(statusText.ifBlank { stringResource(R.string.working) }, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SummaryCard(report: ApkReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(report.displayName, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            InfoLine(stringResource(R.string.label_package), report.packageName ?: "—")
            InfoLine(
                stringResource(R.string.label_version),
                listOfNotNull(report.versionName, "(${report.versionCode})").joinToString(" "),
            )
            InfoLine(stringResource(R.string.label_sdk), "min ${report.minSdk ?: "—"} / target ${report.targetSdk ?: "—"}")
            InfoLine(stringResource(R.string.label_abi), report.abis.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "—")
            InfoLine(
                stringResource(R.string.label_signature),
                report.signatureSchemes.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: stringResource(R.string.signature_none),
            )
            if (report.partCount > 1) {
                InfoLine(
                    stringResource(R.string.label_parts),
                    stringResource(R.string.parts_value, report.selectedPartCount, report.partCount),
                )
            }
            InfoLine(stringResource(R.string.label_size), formatSize(report.sizeBytes))
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HealthyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.healthy_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.healthy_body), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DiagnosisRow(diagnosis: Diagnosis) {
    val (icon, tint) = when (diagnosis.severity) {
        Severity.BLOCKER -> Icons.Default.Error to MaterialTheme.colorScheme.error
        Severity.WARNING -> Icons.Default.Warning to MaterialTheme.colorScheme.tertiary
        Severity.INFO -> Icons.Default.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(14.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(diagnosis.id.titleRes), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(diagnosis.id.detailRes, *diagnosis.args.toTypedArray()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (diagnosis.fix != null) {
                    Text(
                        stringResource(R.string.auto_fixable),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    state: MainUiState,
    report: ApkReport,
    onRepair: () -> Unit,
    onInstall: () -> Unit,
    onSave: () -> Unit,
    onGrantUnknownSources: () -> Unit,
    onUninstallConflict: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (state.needsUnknownSources) {
            FilledTonalButton(onClick = onGrantUnknownSources, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_grant_unknown_sources))
            }
        }

        state.conflictingPackage?.let { packageName ->
            OutlinedButton(
                onClick = { onUninstallConflict(packageName) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_uninstall_existing, packageName))
            }
        }

        val canRepair = report.blockers.isNotEmpty() && report.repairActions.isNotEmpty()

        if (canRepair) {
            Button(onClick = onRepair, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Healing, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (report.isRepairable) R.string.btn_repair else R.string.btn_repair_partial,
                    ),
                )
            }
        }

        if (report.isInstallableAsIs || state.repaired != null) {
            Button(onClick = onInstall, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (state.repaired != null) R.string.btn_install_fixed else R.string.btn_install))
            }
        }

        if (state.repaired != null) {
            OutlinedButton(onClick = onSave, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.btn_save))
            }
        }

        // Some blockers (an ABI mismatch, a signature conflict) can only be
        // resolved by the user; say so instead of implying a one-tap fix.
        if (report.blockers.any { it.fix == null }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.not_repairable_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.not_repairable_body), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        state.repaired?.takeIf { it.resigned }?.let {
            Text(
                stringResource(R.string.resigned_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LogCard(log: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (line in log) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
