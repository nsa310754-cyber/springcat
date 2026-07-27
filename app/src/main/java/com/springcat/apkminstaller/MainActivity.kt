package com.springcat.apkminstaller

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.springcat.apkminstaller.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var pending = listOf<Uri>()

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        uris.forEach { u ->
            runCatching {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        accept(uris, forceInstall = true)
    }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        prefs.watchTreeUri = uri.toString()
        prefs.clearSeen()
        AppLog.i("監視フォルダを設定: ${DocumentFile.fromTreeUri(this, uri)?.name ?: uri}")
        if (prefs.watchEnabled) {
            WatchService.stop(this)
            WatchService.start(this)
        }
        render()
    }

    private val askNotification = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 通知が拒否されても動作はする */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        Notifications.ensureChannels(this)

        binding.btnPick.setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        binding.btnInstallPending.setOnClickListener {
            val p = pending
            pending = emptyList()
            render()
            InstallService.enqueue(this, p)
        }
        binding.btnFolder.setOnClickListener { pickFolder.launch(null) }
        binding.btnUnknownSources.setOnClickListener { openUnknownSourcesSettings() }
        binding.btnClearLog.setOnClickListener { AppLog.clear() }

        binding.swAuto.setOnCheckedChangeListener { _, v -> prefs.autoInstall = v }
        binding.swAllSplits.setOnCheckedChangeListener { _, v -> prefs.installAllSplits = v }
        binding.swWatch.setOnCheckedChangeListener { _, v ->
            if (v && prefs.watchTreeUri == null) {
                binding.swWatch.isChecked = false
                Toast.makeText(this, R.string.pick_folder_first, Toast.LENGTH_LONG).show()
                pickFolder.launch(null)
                return@setOnCheckedChangeListener
            }
            prefs.watchEnabled = v
            if (v) WatchService.start(this) else WatchService.stop(this)
            render()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppLog.lines.collect { lines ->
                    binding.txtLog.text = lines.joinToString("\n")
                    binding.scrollLog.post { binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN) }
                }
            }
        }

        requestNotificationPermissionIfNeeded()
        if (prefs.watchEnabled && prefs.watchTreeUri != null) WatchService.start(this)

        handleIntent(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        AppState.isForeground = true
        render()
    }

    override fun onStop() {
        AppState.isForeground = false
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(streamExtra(intent))
            Intent.ACTION_SEND_MULTIPLE -> streamListExtra(intent)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        // 二重処理を防ぐ
        intent.action = null
        intent.data = null
        accept(uris, forceInstall = false)
    }

    private fun accept(uris: List<Uri>, forceInstall: Boolean) {
        val names = uris.joinToString(", ") { UriUtils.displayName(this, it) }
        AppLog.i("受け取り: ${uris.size} 件 ($names)")

        val unsupported = uris.filterNot { UriUtils.isSupportedBundle(UriUtils.displayName(this, it)) }
        if (unsupported.isNotEmpty()) {
            AppLog.i("! 対応拡張子ではないファイルが含まれています。ZIP として展開を試みます。")
        }

        if (forceInstall || prefs.autoInstall) {
            InstallService.enqueue(this, uris)
            pending = emptyList()
        } else {
            pending = uris
        }
        render()
    }

    private fun render() {
        binding.swAuto.isChecked = prefs.autoInstall
        binding.swAllSplits.isChecked = prefs.installAllSplits
        binding.swWatch.isChecked = prefs.watchEnabled

        val folder = prefs.watchTreeUri?.let { uri ->
            runCatching { DocumentFile.fromTreeUri(this, Uri.parse(uri))?.name }.getOrNull()
                ?: Uri.parse(uri).lastPathSegment
        }
        binding.btnFolder.text = getString(R.string.watch_folder_fmt, folder ?: getString(R.string.not_set))

        binding.btnInstallPending.visibility =
            if (pending.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnInstallPending.text =
            getString(R.string.install_pending_fmt, pending.size)

        binding.txtStatus.text = if (canInstallPackages()) {
            getString(R.string.status_ready)
        } else {
            getString(R.string.status_need_unknown_sources)
        }
    }

    private fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    private fun openUnknownSourcesSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) askNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun streamListExtra(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        }
}
