package com.springcat.screenlink

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.springcat.screenlink.net.RelayClient

/** View-only display of a peer's screen frames. No input is ever sent back. */
class ViewerActivity : AppCompatActivity(), RelayClient.Listener {

    companion object {
        private const val EXTRA_PEER_ID = "peer_id"
        private const val EXTRA_PEER_NAME = "peer_name"

        fun intentFor(context: Context, peerId: String, peerName: String): Intent =
            Intent(context, ViewerActivity::class.java)
                .putExtra(EXTRA_PEER_ID, peerId)
                .putExtra(EXTRA_PEER_NAME, peerName)
    }

    private lateinit var screenImage: ImageView
    private lateinit var peerId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        AppSession.init(this)

        peerId = intent.getStringExtra(EXTRA_PEER_ID).orEmpty()
        val peerName = intent.getStringExtra(EXTRA_PEER_NAME).orEmpty()

        screenImage = findViewById(R.id.screenImage)
        findViewById<TextView>(R.id.viewerTitle).text = "$peerName の画面"
        findViewById<Button>(R.id.btnDisconnectView).setOnClickListener {
            RelayClient.stopShare()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        RelayClient.setListener(this)
    }

    override fun onPause() {
        super.onPause()
        RelayClient.setListener(null)
    }

    override fun onFrame(bytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        screenImage.setImageBitmap(bitmap)
    }

    override fun onSessionEnded(peerId: String) {
        if (peerId == this.peerId) {
            Toast.makeText(this, "共有が終了しました", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onState(state: RelayClient.State) {
        if (state == RelayClient.State.DISCONNECTED) finish()
    }

    override fun onRoster(peers: List<AppSession.Peer>) {}
    override fun onIncomingViewRequest(fromId: String, fromName: String) {}
    override fun onViewResponse(fromId: String, accept: Boolean) {}
    override fun onAuthRejected() {}
}
