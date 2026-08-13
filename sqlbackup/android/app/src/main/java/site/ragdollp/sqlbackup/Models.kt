package site.ragdollp.sqlbackup

import java.util.UUID

/** 保存済み接続プロファイル。パスワードは端末内 SharedPreferences にのみ保存される。 */
data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 1433,
    val database: String = "",
    val username: String = "",
    val password: String = "",
    val windowsAuth: Boolean = false,
    val domain: String = "",
    val encrypt: Boolean = false,
) {
    val label: String
        get() = name.ifBlank { "$host:$port" }
}

data class DatabaseInfo(
    val name: String,
    val stateDesc: String,
    val recoveryModel: String,
    val sizeMb: Double,
)

data class BackupFileEntry(
    val logicalName: String,
    val physicalName: String,
    val type: String, // "D" = データ, "L" = ログ
)

data class BackupHeaderInfo(
    val backupName: String?,
    val backupType: Int?,
    val databaseName: String?,
    val startDate: String?,
    val finishDate: String?,
)

enum class OperationType { BACKUP, RESTORE, TEST_CONNECTION }
enum class OperationStatus { RUNNING, SUCCESS, FAILED }

data class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: OperationType,
    val connectionLabel: String,
    val databaseName: String,
    val filePath: String,
    val status: OperationStatus,
    val message: String,
    val durationMs: Long = 0,
)

/** BACKUP/RESTORE 実行中の進捗 (0..100、不明な場合は null)。 */
data class ProgressState(
    val running: Boolean = false,
    val percent: Int? = null,
    val label: String = "",
)
