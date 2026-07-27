package com.springcat.apkminstaller

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 受け取ったバンドルを順番に展開してインストールするフォアグラウンドサービス。
 * 複数ファイルを渡してもキューに積まれて 1 つずつ処理される。
 */
class InstallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ConcurrentLinkedQueue<Uri>()
    private val lock = Mutex()
    private lateinit var prefs: Prefs

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Notifications.ensureChannels(this)
        startForeground(Notifications.ID_INSTALL, Notifications.progress(this, TITLE, "準備中…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uris = extractUris(intent)
        if (uris.isEmpty() && queue.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        uris.forEach { queue.add(it) }
        if (uris.isNotEmpty()) {
            AppLog.i("キューに追加: ${uris.size} 件 (待ち ${queue.size} 件)")
        }
        scope.launch { drain() }
        return START_NOT_STICKY
    }

    private suspend fun drain() = lock.withLock {
        while (true) {
            val uri = queue.poll() ?: break
            val name = UriUtils.displayName(this, uri)
            notify("$name を処理中… (残り ${queue.size})")
            runCatching { processOne(uri, name) }
                .onFailure { AppLog.e("✗ $name の処理に失敗", it) }
        }
        stopForeground()
        stopSelf()
    }

    private suspend fun processOne(uri: Uri, name: String) {
        AppLog.i("▶ $name を展開中…")
        val workDir = File(cacheDir, "work/${System.nanoTime()}")
        try {
            val extracted = BundleExtractor.extract(this, uri, name, workDir)
            extracted.warnings.forEach { AppLog.i("  ! $it") }
            AppLog.i("  APK ${extracted.apks.size} 個を検出")

            val groups = ApkGrouper.group(this, extracted.apks)
            if (groups.size > 1) AppLog.i("  ${groups.size} 個のアプリが入っています")

            groups.forEachIndexed { i, g ->
                val selection = SplitPicker.pick(this, g.files, prefs.installAllSplits)
                selection.notes.forEach { AppLog.i("  ! $it") }
                if (selection.skipped.isNotEmpty()) {
                    AppLog.i("  不要な split を ${selection.skipped.size} 個スキップ")
                }
                val label = g.packageName ?: name
                notify("$label をインストール中… (${i + 1}/${groups.size})")
                AppLog.i("● インストール開始: $label (${selection.chosen.size} 個の APK)")

                val outcome = ApkInstaller.install(this, selection.chosen, label)
                if (outcome.isSuccess) {
                    AppLog.i("✔ 完了: ${outcome.packageName ?: label}")
                } else {
                    AppLog.e("✗ 失敗: $label -> ${ApkInstaller.statusText(outcome.status)}" +
                        (outcome.message?.let { " / $it" } ?: ""))
                }
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun notify(text: String) {
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(Notifications.ID_INSTALL, Notifications.progress(this, TITLE, text))
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun extractUris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(EXTRA_URIS, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS)
        }
        return list ?: emptyList()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TITLE = "SpringCat Installer"
        const val EXTRA_URIS = "uris"

        fun enqueue(context: Context, uris: List<Uri>) {
            if (uris.isEmpty()) return
            val intent = Intent(context, InstallService::class.java).apply {
                putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
