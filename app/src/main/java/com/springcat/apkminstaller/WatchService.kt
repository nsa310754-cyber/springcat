package com.springcat.apkminstaller

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 指定フォルダ (SAF ツリー) を監視して、新しい APKM/APKS/XAPK/APK が置かれたら自動でインストールする。
 * 転送途中のファイルを掴まないように、サイズが 1 周期変化しなかったものだけを対象にする。
 */
class WatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: Prefs
    private val lastSize = HashMap<String, Long>()
    private var running = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Notifications.ensureChannels(this)
        startForeground(
            Notifications.ID_WATCH,
            Notifications.progress(this, "フォルダを監視中", "新しいバンドルを待っています")
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val treeUri = prefs.watchTreeUri?.let { Uri.parse(it) }
        if (treeUri == null) {
            AppLog.e("監視フォルダが未設定です")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            scope.launch { watch(treeUri) }
        }
        return START_STICKY
    }

    private suspend fun watch(treeUri: Uri) {
        val root = DocumentFile.fromTreeUri(this, treeUri)
        if (root == null || !root.isDirectory) {
            AppLog.e("監視フォルダにアクセスできません")
            stopSelf()
            return
        }
        AppLog.i("👀 監視開始: ${root.name ?: treeUri}")

        // 既にあるファイルは対象外にする
        var primed = false

        while (scope.isActive) {
            val newUris = mutableListOf<Uri>()
            runCatching {
                root.listFiles().forEach { doc ->
                    val name = doc.name ?: return@forEach
                    if (doc.isDirectory || !UriUtils.isSupportedBundle(name)) return@forEach
                    val key = "${doc.uri}|${doc.length()}|${doc.lastModified()}"
                    if (prefs.isSeen(key)) return@forEach

                    if (!primed) {
                        prefs.markSeen(key)
                        return@forEach
                    }
                    // 前回と同じサイズになったら転送完了とみなす
                    val prev = lastSize[doc.uri.toString()]
                    val size = doc.length()
                    lastSize[doc.uri.toString()] = size
                    if (prev != null && prev == size && size > 0) {
                        prefs.markSeen(key)
                        lastSize.remove(doc.uri.toString())
                        AppLog.i("🆕 新しいファイルを検出: $name")
                        newUris += doc.uri
                    }
                }
            }.onFailure { AppLog.e("監視中にエラー", it) }

            if (newUris.isNotEmpty()) InstallService.enqueue(this, newUris)
            if (!primed) {
                primed = true
                AppLog.i("既存ファイルは対象外として記録しました")
            }
            delay(prefs.pollSeconds * 1000L)
        }
    }

    override fun onDestroy() {
        AppLog.i("監視を停止しました")
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, WatchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }
}
