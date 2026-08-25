package site.ragdollp.pocketzip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 選択されたルート配下を、除外パスを除いて 1 つの ZIP に固めるフォアグラウンドサービス。
 * 進行状況は ZipProgressBus 経由で MainActivity に伝える。
 */
class ZipService : Service() {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                ZipProgressBus.cancelRequested = true
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val rootPaths = intent.getStringArrayListExtra(EXTRA_ROOTS).orEmpty()
                val rootLabels = intent.getStringArrayListExtra(EXTRA_ROOT_LABELS).orEmpty()
                val roots = rootPaths.mapIndexed { i, p ->
                    File(p) to (rootLabels.getOrNull(i) ?: File(p).name)
                }
                val excluded = intent.getStringArrayListExtra(EXTRA_EXCLUDED).orEmpty().toSet()
                ZipProgressBus.cancelRequested = false
                startForeground(NOTIFICATION_ID, buildNotification("圧縮を開始しています…"))
                scope.launch { runZip(roots, excluded) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    private suspend fun runZip(roots: List<Pair<File, String>>, excluded: Set<String>) {
        val startedAt = System.currentTimeMillis()
        val outDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PocketZip"
        )
        outDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(outDir, "backup_$stamp.zip")

        var filesDone = 0
        var bytesDone = 0L
        var skipped = 0
        var lastUpdate = 0L
        var cancelled = false

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile))).use { zos ->
                for ((root, label) in roots) {
                    if (!root.exists()) continue
                    val walker = root.walkTopDown()
                        .onEnter { dir ->
                            if (ZipProgressBus.cancelRequested) return@onEnter false
                            val p = dir.path
                            // 出力先フォルダ自身と、自アプリの専用領域は対象から外す。
                            p != outDir.path && !isOwnAppData(dir) && !isPathExcluded(p, excluded)
                        }
                        .onFail { _, _ -> skipped++ }
                        .iterator()

                    while (walker.hasNext()) {
                        if (ZipProgressBus.cancelRequested) {
                            cancelled = true
                            break
                        }
                        val f = walker.next()
                        if (f.isDirectory) continue
                        val path = f.path
                        if (path == outFile.path) continue
                        if (isPathExcluded(path, excluded)) continue

                        try {
                            val entryName = File(label, f.relativeTo(root).path).path
                            zos.putNextEntry(ZipEntry(entryName))
                            FileInputStream(f).use { input -> input.copyTo(zos, 64 * 1024) }
                            zos.closeEntry()
                            filesDone++
                            bytesDone += f.length()
                        } catch (_: Exception) {
                            skipped++
                            runCatching { zos.closeEntry() }
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 250) {
                            lastUpdate = now
                            ZipProgressBus.state.value =
                                ZipState.Running(f.name, filesDone, bytesDone, skipped)
                            updateNotification(f.name, filesDone)
                        }
                    }
                    if (cancelled) break
                }
            }

            if (cancelled) {
                outFile.delete()
                ZipProgressBus.state.value = ZipState.Cancelled(null)
            } else {
                ZipProgressBus.state.value = ZipState.Done(
                    outputPath = outFile.path,
                    filesDone = filesDone,
                    bytesDone = bytesDone,
                    skipped = skipped,
                    durationMs = System.currentTimeMillis() - startedAt,
                )
            }
        } catch (e: Exception) {
            outFile.delete()
            ZipProgressBus.state.value = ZipState.Failed(e.message ?: e.toString())
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun isOwnAppData(dir: File): Boolean {
        val path = dir.path
        return path.contains("/Android/data/$packageName") || path.contains("/Android/obb/$packageName")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "圧縮の進行状況",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("PocketZip で圧縮中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
    }

    private fun updateNotification(currentFileName: String, filesDone: Int) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIFICATION_ID, buildNotification("${filesDone}個処理済み: $currentFileName"))
    }

    companion object {
        const val ACTION_START = "site.ragdollp.pocketzip.action.START"
        const val ACTION_CANCEL = "site.ragdollp.pocketzip.action.CANCEL"
        const val EXTRA_ROOTS = "extra_roots"
        const val EXTRA_ROOT_LABELS = "extra_root_labels"
        const val EXTRA_EXCLUDED = "extra_excluded"
        private const val CHANNEL_ID = "pocketzip_progress"
        private const val NOTIFICATION_ID = 1
    }
}
