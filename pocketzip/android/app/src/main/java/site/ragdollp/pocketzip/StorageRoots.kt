package site.ragdollp.pocketzip

import android.content.Context
import android.os.storage.StorageManager
import java.io.File

data class StorageRoot(val label: String, val dir: File)

/** 内部ストレージ + 挿さっている SD カードなど、スキャン対象のルート一覧を返す。 */
object StorageRoots {
    fun list(context: Context): List<StorageRoot> {
        val result = mutableListOf<StorageRoot>()
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            for (volume in sm.storageVolumes) {
                val dir = volume.directory ?: continue
                val label = if (volume.isPrimary) {
                    "内部ストレージ"
                } else {
                    volume.getDescription(context) ?: dir.name
                }
                result += StorageRoot(label, dir)
            }
        } catch (_: Exception) {
            // 端末依存の例外はすべて無視して下のフォールバックへ。
        }
        if (result.isEmpty()) {
            result += StorageRoot(
                "内部ストレージ",
                android.os.Environment.getExternalStorageDirectory()
            )
        }
        return result
    }
}
