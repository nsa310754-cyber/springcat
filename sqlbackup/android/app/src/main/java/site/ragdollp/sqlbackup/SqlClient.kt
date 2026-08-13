package site.ragdollp.sqlbackup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement

/**
 * jTDS 経由で SQL Server に接続し、BACKUP/RESTORE 等の T-SQL を発行するクライアント。
 * すべての public 関数は suspend であり、呼び出し側で IO ディスパッチャに乗る必要はない
 * (内部で withContext(Dispatchers.IO) している)。
 */
object SqlClient {

    init {
        Class.forName("net.sourceforge.jtds.jdbc.Driver")
    }

    private fun buildUrl(profile: ConnectionProfile, database: String? = null): String {
        val db = database ?: profile.database
        val sb = StringBuilder("jdbc:jtds:sqlserver://${profile.host}:${profile.port}")
        if (db.isNotBlank()) sb.append("/$db")
        sb.append(";appname=SqlBackupApp;loginTimeout=15")
        if (profile.windowsAuth) {
            sb.append(";useNTLMv2=true")
            if (profile.domain.isNotBlank()) sb.append(";domain=${profile.domain}")
        }
        if (profile.encrypt) sb.append(";ssl=require")
        return sb.toString()
    }

    private fun openConnection(profile: ConnectionProfile, database: String? = null): Connection {
        val url = buildUrl(profile, database)
        return DriverManager.getConnection(url, profile.username, profile.password)
    }

    suspend fun testConnection(profile: ConnectionProfile): Result<String> = withContext(Dispatchers.IO) {
        try {
            openConnection(profile).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT @@VERSION AS v").use { rs ->
                        val version = if (rs.next()) rs.getString("v") else "接続に成功しました"
                        Result.success(version.lineSequence().first())
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listDatabases(profile: ConnectionProfile): Result<List<DatabaseInfo>> = withContext(Dispatchers.IO) {
        try {
            openConnection(profile, "master").use { conn ->
                val sql = """
                    SELECT d.name AS name, d.state_desc AS state_desc, d.recovery_model_desc AS recovery_model,
                           ISNULL((SELECT SUM(mf.size) * 8.0 / 1024 FROM sys.master_files mf WHERE mf.database_id = d.database_id), 0) AS size_mb
                    FROM sys.databases d
                    ORDER BY d.name
                """.trimIndent()
                conn.createStatement().use { st ->
                    st.executeQuery(sql).use { rs ->
                        val out = mutableListOf<DatabaseInfo>()
                        while (rs.next()) {
                            out.add(
                                DatabaseInfo(
                                    name = rs.getString("name"),
                                    stateDesc = rs.getString("state_desc") ?: "",
                                    recoveryModel = rs.getString("recovery_model") ?: "",
                                    sizeMb = rs.getDouble("size_mb"),
                                )
                            )
                        }
                        Result.success(out)
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** BACKUP/RESTORE 実行中に別コネクションで進捗(%)をポーリングする。権限不足等は無視する。 */
    private suspend fun pollProgress(profile: ConnectionProfile, onProgress: (Int?) -> Unit) {
        try {
            openConnection(profile, "master").use { conn ->
                val sql = """
                    SELECT TOP 1 r.percent_complete AS pct
                    FROM sys.dm_exec_requests r
                    WHERE r.command IN ('BACKUP DATABASE', 'RESTORE DATABASE')
                    ORDER BY r.percent_complete DESC
                """.trimIndent()
                while (true) {
                    try {
                        conn.createStatement().use { st ->
                            st.executeQuery(sql).use { rs ->
                                if (rs.next()) {
                                    onProgress(rs.getDouble("pct").toInt())
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 権限不足など。進捗は不明のまま続行。
                    }
                    delay(1500)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ポーリング用コネクションが張れない場合は進捗表示なしで続行。
        }
    }

    private fun quoteIdent(name: String) = "[" + name.replace("]", "]]") + "]"
    private fun quoteLiteral(value: String) = "N'" + value.replace("'", "''") + "'"

    suspend fun runBackup(
        profile: ConnectionProfile,
        databaseName: String,
        filePath: String,
        compression: Boolean,
        differential: Boolean,
        copyOnly: Boolean,
        onProgress: (Int?) -> Unit = {},
    ): Result<Unit> = coroutineScope {
        val pollJob = async(Dispatchers.IO) { pollProgress(profile, onProgress) }
        try {
            val result = withContext(Dispatchers.IO) {
                try {
                    openConnection(profile, "master").use { conn ->
                        val options = mutableListOf(
                            "NAME = ${quoteLiteral("$databaseName-backup")}",
                            "STATS = 5",
                        )
                        if (compression) options.add("COMPRESSION")
                        if (differential) options.add("DIFFERENTIAL")
                        if (copyOnly) options.add("COPY_ONLY")
                        val sql = "BACKUP DATABASE ${quoteIdent(databaseName)} " +
                            "TO DISK = ${quoteLiteral(filePath)} " +
                            "WITH ${options.joinToString(", ")}"
                        conn.createStatement().use { st -> st.execute(sql) }
                        Result.success(Unit)
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            result
        } finally {
            pollJob.cancel()
            onProgress(null)
        }
    }

    suspend fun listBackupFiles(profile: ConnectionProfile, filePath: String): Result<List<BackupFileEntry>> =
        withContext(Dispatchers.IO) {
            try {
                openConnection(profile, "master").use { conn ->
                    val sql = "RESTORE FILELISTONLY FROM DISK = ${quoteLiteral(filePath)}"
                    conn.createStatement().use { st ->
                        st.executeQuery(sql).use { rs ->
                            val out = mutableListOf<BackupFileEntry>()
                            while (rs.next()) {
                                out.add(
                                    BackupFileEntry(
                                        logicalName = rs.getString("LogicalName") ?: "",
                                        physicalName = rs.getString("PhysicalName") ?: "",
                                        type = rs.getString("Type") ?: "",
                                    )
                                )
                            }
                            Result.success(out)
                        }
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun getBackupHeader(profile: ConnectionProfile, filePath: String): Result<BackupHeaderInfo> =
        withContext(Dispatchers.IO) {
            try {
                openConnection(profile, "master").use { conn ->
                    val sql = "RESTORE HEADERONLY FROM DISK = ${quoteLiteral(filePath)}"
                    conn.createStatement().use { st ->
                        st.executeQuery(sql).use { rs ->
                            if (rs.next()) {
                                Result.success(
                                    BackupHeaderInfo(
                                        backupName = rs.getString("BackupName"),
                                        backupType = rs.getInt("BackupType"),
                                        databaseName = rs.getString("DatabaseName"),
                                        startDate = rs.getString("BackupStartDate"),
                                        finishDate = rs.getString("BackupFinishDate"),
                                    )
                                )
                            } else {
                                Result.failure(IllegalStateException("バックアップ情報が取得できませんでした"))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun runRestore(
        profile: ConnectionProfile,
        databaseName: String,
        filePath: String,
        withReplace: Boolean,
        moves: List<Pair<String, String>>, // logicalName to newPhysicalPath
        onProgress: (Int?) -> Unit = {},
    ): Result<Unit> = coroutineScope {
        val pollJob = async(Dispatchers.IO) { pollProgress(profile, onProgress) }
        try {
            val result = withContext(Dispatchers.IO) {
                try {
                    openConnection(profile, "master").use { conn ->
                        if (withReplace) {
                            setSingleUserBestEffort(conn, databaseName, singleUser = true)
                        }
                        val options = mutableListOf("STATS = 5")
                        moves.forEach { (logical, path) ->
                            options.add("MOVE ${quoteLiteral(logical)} TO ${quoteLiteral(path)}")
                        }
                        if (withReplace) options.add("REPLACE")
                        val sql = "RESTORE DATABASE ${quoteIdent(databaseName)} " +
                            "FROM DISK = ${quoteLiteral(filePath)} " +
                            "WITH ${options.joinToString(", ")}"
                        conn.createStatement().use { st -> st.execute(sql) }
                        if (withReplace) {
                            setSingleUserBestEffort(conn, databaseName, singleUser = false)
                        }
                        Result.success(Unit)
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            result
        } finally {
            pollJob.cancel()
            onProgress(null)
        }
    }

    private fun setSingleUserBestEffort(conn: Connection, databaseName: String, singleUser: Boolean) {
        try {
            val mode = if (singleUser) "SINGLE_USER WITH ROLLBACK IMMEDIATE" else "MULTI_USER"
            conn.createStatement().use { st: Statement ->
                st.execute("ALTER DATABASE ${quoteIdent(databaseName)} SET $mode")
            }
        } catch (e: SQLException) {
            // 対象データベースがまだ存在しない(初回リストア)場合などは無視する。
        }
    }
}
