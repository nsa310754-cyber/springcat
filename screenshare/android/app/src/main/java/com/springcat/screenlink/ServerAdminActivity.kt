package com.springcat.screenlink

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.springcat.screenlink.relay.AdminDeviceAdapter
import com.springcat.screenlink.relay.DeviceAllowlist
import com.springcat.screenlink.relay.RelayServerService

/**
 * Turns this device into "the server" - the embedded relay other permitted
 * devices connect to directly, with no separate machine required. Also
 * manages the on-device allowlist of who is permitted to connect at all.
 */
class ServerAdminActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var serverSwitch: SwitchMaterial
    private lateinit var portInput: TextInputEditText
    private lateinit var adapter: AdminDeviceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_admin)
        AppSession.init(this)

        statusText = findViewById(R.id.serverStatus)
        serverSwitch = findViewById(R.id.serverSwitch)
        portInput = findViewById(R.id.inputPort)

        val list = findViewById<RecyclerView>(R.id.adminDeviceList)
        adapter = AdminDeviceAdapter(
            onShowCreds = { showCredsDialog(it) },
            onRemove = { entry ->
                DeviceAllowlist.remove(entry.id)
                refreshDeviceList()
            }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<android.widget.Button>(R.id.btnAddDevice).setOnClickListener { showAddDeviceDialog() }

        serverSwitch.isChecked = RelayServerService.isRunning
        serverSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) startServer() else stopServer()
        }

        refreshDeviceList()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun startServer() {
        val port = portInput.text?.toString()?.toIntOrNull() ?: RelayServerService.DEFAULT_PORT
        val intent = Intent(this, RelayServerService::class.java)
            .putExtra(RelayServerService.EXTRA_PORT, port)
        startForegroundService(intent)
        statusText.postDelayed({ refreshStatus() }, 300)
    }

    private fun stopServer() {
        startService(Intent(this, RelayServerService::class.java).setAction(RelayServerService.ACTION_STOP))
        statusText.postDelayed({ refreshStatus() }, 300)
    }

    private fun refreshStatus() {
        serverSwitch.isChecked = RelayServerService.isRunning
        statusText.text = if (RelayServerService.isRunning) {
            val addrs = RelayServerService.localAddresses()
            val port = portInput.text?.toString()?.toIntOrNull() ?: RelayServerService.DEFAULT_PORT
            if (addrs.isEmpty()) {
                "稼働中 (ポート $port) - IPアドレスを取得できませんでした。Wi-Fi接続を確認してください。"
            } else {
                "稼働中: " + addrs.joinToString(" / ") { "ws://$it:$port" }
            }
        } else {
            "停止中"
        }
    }

    private fun refreshDeviceList() {
        adapter.submit(DeviceAllowlist.list())
    }

    private fun showAddDeviceDialog() {
        val nameField = EditText(this).apply { hint = "端末の表示名" }
        val groupField = EditText(this).apply { hint = "グループ (例: office-fleet)"; setText("default") }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(nameField)
            addView(groupField)
        }
        AlertDialog.Builder(this)
            .setTitle("端末を追加")
            .setView(container)
            .setPositiveButton("追加") { _, _ ->
                val name = nameField.text?.toString()?.trim().orEmpty()
                val group = groupField.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank() && group.isNotBlank()) {
                    val entry = DeviceAllowlist.add(name, group)
                    refreshDeviceList()
                    showCredsDialog(entry)
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showCredsDialog(entry: DeviceAllowlist.Entry) {
        val port = portInput.text?.toString()?.toIntOrNull() ?: RelayServerService.DEFAULT_PORT
        val addr = RelayServerService.localAddresses().firstOrNull() ?: "<この端末のIPアドレス>"
        val message = """
            この端末アプリの「登録」画面に、以下を入力してもらってください:

            サーバーURL: ws://$addr:$port
            端末ID: ${entry.id}
            シークレット: ${entry.secret}
            表示名: 自由に設定可

            (グループ: ${entry.group})
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setMessage(message)
            .setPositiveButton("閉じる", null)
            .show()
    }
}
