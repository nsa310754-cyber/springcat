package com.springcat.screenlink

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.textfield.TextInputEditText
import com.springcat.screenlink.capture.ScreenCaptureService
import com.springcat.screenlink.net.PeerAdapter
import com.springcat.screenlink.net.RelayClient
import com.springcat.screenlink.net.RelayConnectionService

class MainActivity : AppCompatActivity(), RelayClient.Listener {

    private lateinit var enrollGroup: android.view.View
    private lateinit var mainGroup: android.view.View
    private lateinit var statusText: TextView
    private lateinit var btnShareToggle: Button
    private lateinit var peerAdapter: PeerAdapter

    private var isSharing = false
    private var pendingRequesterId: String? = null

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val requesterId = pendingRequesterId
            pendingRequesterId = null
            if (result.resultCode == RESULT_OK && result.data != null) {
                val intent = Intent(this, ScreenCaptureService::class.java)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                startForegroundService(intent)
                isSharing = true
                updateShareButton()
            } else {
                // User declined the system capture consent: undo the session we
                // tentatively accepted so the viewer isn't left waiting forever.
                if (requesterId != null) RelayClient.stopShare()
                Toast.makeText(this, "画面共有の許可が得られなかったため中止しました", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AppSession.init(this)

        enrollGroup = findViewById(R.id.enrollGroup)
        mainGroup = findViewById(R.id.mainGroup)
        statusText = findViewById(R.id.statusText)
        btnShareToggle = findViewById(R.id.btnShareToggle)

        val peerList = findViewById<RecyclerView>(R.id.peerList)
        peerAdapter = PeerAdapter { peer -> onPeerTapped(peer) }
        peerList.layoutManager = LinearLayoutManager(this)
        peerList.adapter = peerAdapter

        findViewById<Button>(R.id.btnSave).setOnClickListener { onSaveEnrollment() }
        findViewById<Button>(R.id.btnForget).setOnClickListener { onForget() }
        findViewById<Button>(R.id.btnServerAdmin).setOnClickListener {
            startActivity(Intent(this, ServerAdminActivity::class.java))
        }
        btnShareToggle.setOnClickListener { onShareToggle() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        refreshEnrollmentUi()
    }

    override fun onResume() {
        super.onResume()
        RelayClient.setListener(this)
        if (AppSession.isEnrolled) peerAdapter.submit(AppSession.roster)
    }

    override fun onPause() {
        super.onPause()
        RelayClient.setListener(null)
    }

    private fun refreshEnrollmentUi() {
        if (AppSession.isEnrolled) {
            enrollGroup.visibility = android.view.View.GONE
            mainGroup.visibility = android.view.View.VISIBLE
            startForegroundService(Intent(this, RelayConnectionService::class.java))
        } else {
            enrollGroup.visibility = android.view.View.VISIBLE
            mainGroup.visibility = android.view.View.GONE
        }
    }

    private fun onSaveEnrollment() {
        val url = findViewById<TextInputEditText>(R.id.inputServerUrl).text?.toString()?.trim().orEmpty()
        val id = findViewById<TextInputEditText>(R.id.inputDeviceId).text?.toString()?.trim().orEmpty()
        val secret = findViewById<TextInputEditText>(R.id.inputSecret).text?.toString()?.trim().orEmpty()
        val name = findViewById<TextInputEditText>(R.id.inputDeviceName).text?.toString()?.trim().orEmpty()

        if (url.isBlank() || id.isBlank() || secret.isBlank() || name.isBlank()) {
            Toast.makeText(this, "すべての項目を入力してください", Toast.LENGTH_SHORT).show()
            return
        }
        AppSession.serverUrl = url
        AppSession.deviceId = id
        AppSession.deviceSecret = secret
        AppSession.deviceName = name
        refreshEnrollmentUi()
    }

    private fun onForget() {
        stopService(Intent(this, RelayConnectionService::class.java))
        stopService(Intent(this, ScreenCaptureService::class.java))
        AppSession.serverUrl = ""
        AppSession.deviceSecret = ""
        AppSession.deviceName = ""
        refreshEnrollmentUi()
    }

    private fun onShareToggle() {
        if (isSharing) {
            stopService(Intent(this, ScreenCaptureService::class.java))
            isSharing = false
            updateShareButton()
        } else {
            Toast.makeText(this, "画面を見たい端末からの共有リクエストを待っています", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateShareButton() {
        btnShareToggle.text = if (isSharing) "共有を停止" else "共有リクエスト待機中"
    }

    private fun onPeerTapped(peer: AppSession.Peer) {
        RelayClient.requestView(peer.id)
        Toast.makeText(this, "${peer.name} にリクエストを送信しました", Toast.LENGTH_SHORT).show()
    }

    // --- RelayClient.Listener ---

    override fun onState(state: RelayClient.State) {
        statusText.text = when (state) {
            RelayClient.State.CONNECTED -> "接続済み: ${AppSession.deviceName}"
            RelayClient.State.CONNECTING -> "接続中…"
            RelayClient.State.DISCONNECTED -> "未接続"
        }
    }

    override fun onRoster(peers: List<AppSession.Peer>) {
        peerAdapter.submit(peers)
    }

    override fun onIncomingViewRequest(fromId: String, fromName: String) {
        AlertDialog.Builder(this)
            .setTitle("画面表示のリクエスト")
            .setMessage("$fromName があなたの画面を見ようとしています。許可しますか?")
            .setPositiveButton("許可") { _, _ ->
                pendingRequesterId = fromId
                RelayClient.respondView(fromId, true)
                val mpm = getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
            .setNegativeButton("拒否") { _, _ -> RelayClient.respondView(fromId, false) }
            .setCancelable(false)
            .show()
    }

    override fun onViewResponse(fromId: String, accept: Boolean) {
        if (accept) {
            val peerName = AppSession.roster.find { it.id == fromId }?.name ?: fromId
            startActivity(ViewerActivity.intentFor(this, fromId, peerName))
        } else {
            Toast.makeText(this, "リクエストが拒否されました", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSessionEnded(peerId: String) {
        if (isSharing) {
            stopService(Intent(this, ScreenCaptureService::class.java))
            isSharing = false
            updateShareButton()
        }
    }

    override fun onFrame(bytes: ByteArray) {
        // Frames are only consumed while ViewerActivity is on screen.
    }

    override fun onAuthRejected() {
        Toast.makeText(this, "サーバーに許可されていない端末です。管理者に確認してください。", Toast.LENGTH_LONG).show()
    }
}
