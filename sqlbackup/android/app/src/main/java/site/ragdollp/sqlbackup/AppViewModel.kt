package site.ragdollp.sqlbackup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class ConnectState {
    data object Idle : ConnectState()
    data object Connecting : ConnectState()
    data class Connected(val profile: ConnectionProfile, val serverInfo: String) : ConnectState()
    data class Error(val message: String) : ConnectState()
}

sealed class RunState {
    data object Idle : RunState()
    data object Running : RunState()
    data class Success(val message: String) : RunState()
    data class Failed(val message: String) : RunState()
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val store = Store(application)

    private val _connections = MutableStateFlow(store.loadConnections())
    val connections: StateFlow<List<ConnectionProfile>> = _connections.asStateFlow()

    private val _history = MutableStateFlow(store.loadHistory())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _connectState = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val connectState: StateFlow<ConnectState> = _connectState.asStateFlow()

    private val _databases = MutableStateFlow<List<DatabaseInfo>>(emptyList())
    val databases: StateFlow<List<DatabaseInfo>> = _databases.asStateFlow()

    private val _progress = MutableStateFlow(ProgressState())
    val progress: StateFlow<ProgressState> = _progress.asStateFlow()

    private val _backupState = MutableStateFlow<RunState>(RunState.Idle)
    val backupState: StateFlow<RunState> = _backupState.asStateFlow()

    private val _restoreState = MutableStateFlow<RunState>(RunState.Idle)
    val restoreState: StateFlow<RunState> = _restoreState.asStateFlow()

    private val _fileListState = MutableStateFlow<List<BackupFileEntry>>(emptyList())
    val fileListState: StateFlow<List<BackupFileEntry>> = _fileListState.asStateFlow()

    private val _headerState = MutableStateFlow<BackupHeaderInfo?>(null)
    val headerState: StateFlow<BackupHeaderInfo?> = _headerState.asStateFlow()

    fun saveConnection(profile: ConnectionProfile) {
        store.upsertConnection(profile)
        _connections.value = store.loadConnections()
    }

    fun deleteConnection(id: String) {
        store.deleteConnection(id)
        _connections.value = store.loadConnections()
    }

    fun resetConnectState() {
        _connectState.value = ConnectState.Idle
        _databases.value = emptyList()
    }

    fun connect(profile: ConnectionProfile) {
        _connectState.value = ConnectState.Connecting
        viewModelScope.launch {
            val test = SqlClient.testConnection(profile)
            test.fold(
                onSuccess = { info ->
                    _connectState.value = ConnectState.Connected(profile, info)
                    addHistory(OperationType.TEST_CONNECTION, profile.label, "-", "-", OperationStatus.SUCCESS, info)
                    refreshDatabases(profile)
                },
                onFailure = { e ->
                    _connectState.value = ConnectState.Error(e.message ?: "接続に失敗しました")
                    addHistory(OperationType.TEST_CONNECTION, profile.label, "-", "-", OperationStatus.FAILED, e.message ?: "")
                }
            )
        }
    }

    fun refreshDatabases(profile: ConnectionProfile) {
        viewModelScope.launch {
            SqlClient.listDatabases(profile).onSuccess { _databases.value = it }
        }
    }

    fun runBackup(
        profile: ConnectionProfile,
        databaseName: String,
        filePath: String,
        compression: Boolean,
        differential: Boolean,
        copyOnly: Boolean,
    ) {
        _backupState.value = RunState.Running
        _progress.value = ProgressState(running = true, percent = null, label = "バックアップ実行中…")
        val start = System.currentTimeMillis()
        viewModelScope.launch {
            val result = SqlClient.runBackup(
                profile, databaseName, filePath, compression, differential, copyOnly,
            ) { pct -> _progress.value = ProgressState(running = true, percent = pct, label = "バックアップ実行中…") }
            val duration = System.currentTimeMillis() - start
            _progress.value = ProgressState()
            result.fold(
                onSuccess = {
                    _backupState.value = RunState.Success("バックアップが完了しました")
                    addHistory(OperationType.BACKUP, profile.label, databaseName, filePath, OperationStatus.SUCCESS, "完了", duration)
                },
                onFailure = { e ->
                    val msg = e.message ?: "バックアップに失敗しました"
                    _backupState.value = RunState.Failed(msg)
                    addHistory(OperationType.BACKUP, profile.label, databaseName, filePath, OperationStatus.FAILED, msg, duration)
                }
            )
        }
    }

    fun resetBackupState() {
        _backupState.value = RunState.Idle
    }

    fun resetRestoreState() {
        _restoreState.value = RunState.Idle
        _fileListState.value = emptyList()
        _headerState.value = null
    }

    fun inspectBackupFile(profile: ConnectionProfile, filePath: String) {
        viewModelScope.launch {
            SqlClient.getBackupHeader(profile, filePath).onSuccess { _headerState.value = it }
            SqlClient.listBackupFiles(profile, filePath).onSuccess { _fileListState.value = it }
        }
    }

    fun runRestore(
        profile: ConnectionProfile,
        databaseName: String,
        filePath: String,
        withReplace: Boolean,
        moves: List<Pair<String, String>>,
    ) {
        _restoreState.value = RunState.Running
        _progress.value = ProgressState(running = true, percent = null, label = "リストア実行中…")
        val start = System.currentTimeMillis()
        viewModelScope.launch {
            val result = SqlClient.runRestore(
                profile, databaseName, filePath, withReplace, moves,
            ) { pct -> _progress.value = ProgressState(running = true, percent = pct, label = "リストア実行中…") }
            val duration = System.currentTimeMillis() - start
            _progress.value = ProgressState()
            result.fold(
                onSuccess = {
                    _restoreState.value = RunState.Success("リストアが完了しました")
                    addHistory(OperationType.RESTORE, profile.label, databaseName, filePath, OperationStatus.SUCCESS, "完了", duration)
                    refreshDatabases(profile)
                },
                onFailure = { e ->
                    val msg = e.message ?: "リストアに失敗しました"
                    _restoreState.value = RunState.Failed(msg)
                    addHistory(OperationType.RESTORE, profile.label, databaseName, filePath, OperationStatus.FAILED, msg, duration)
                }
            )
        }
    }

    fun clearHistory() {
        store.clearHistory()
        _history.value = emptyList()
    }

    private fun addHistory(
        type: OperationType,
        connLabel: String,
        dbName: String,
        filePath: String,
        status: OperationStatus,
        message: String,
        durationMs: Long = 0,
    ) {
        val entry = HistoryEntry(
            type = type,
            connectionLabel = connLabel,
            databaseName = dbName,
            filePath = filePath,
            status = status,
            message = message,
            durationMs = durationMs,
        )
        store.addHistory(entry)
        _history.value = store.loadHistory()
    }
}
