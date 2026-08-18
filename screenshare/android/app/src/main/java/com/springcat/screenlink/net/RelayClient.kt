package com.springcat.screenlink.net

import android.os.Handler
import android.os.Looper
import com.springcat.screenlink.AppSession
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around the relay server's WebSocket protocol. View-only screen
 * mirroring signaling: hello/auth, roster, view request/response, frame relay.
 * There is deliberately no "run command" or "inject input" message type here.
 */
object RelayClient {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    interface Listener {
        fun onState(state: State)
        fun onRoster(peers: List<AppSession.Peer>)
        fun onIncomingViewRequest(fromId: String, fromName: String)
        fun onViewResponse(fromId: String, accept: Boolean)
        fun onSessionEnded(peerId: String)
        fun onFrame(bytes: ByteArray)
        fun onAuthRejected()
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var state: State = State.DISCONNECTED
        private set

    fun setListener(l: Listener?) {
        listener = l
    }

    fun connect(serverUrl: String, deviceId: String, secret: String) {
        if (state != State.DISCONNECTED) return
        state = State.CONNECTING
        main.post { listener?.onState(state) }

        val request = Request.Builder().url(serverUrl).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = JSONObject()
                    .put("type", "hello")
                    .put("id", deviceId)
                    .put("secret", secret)
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                main.post { listener?.onFrame(bytes.toByteArray()) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                state = State.DISCONNECTED
                main.post {
                    if (code == 4001) listener?.onAuthRejected()
                    listener?.onState(state)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                state = State.DISCONNECTED
                main.post { listener?.onState(state) }
            }
        })
    }

    private fun handleText(text: String) {
        val msg = JSONObject(text)
        when (msg.optString("type")) {
            "hello-ok" -> {
                state = State.CONNECTED
                main.post { listener?.onState(state) }
                listDevices()
            }
            "roster" -> {
                val arr: JSONArray = msg.optJSONArray("devices") ?: JSONArray()
                val peers = ArrayList<AppSession.Peer>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    peers.add(AppSession.Peer(o.getString("id"), o.getString("name"), o.optBoolean("sharing", false)))
                }
                AppSession.updateRoster(peers)
                main.post { listener?.onRoster(peers) }
            }
            "incoming-view-request" -> {
                val fromId = msg.getString("fromId")
                val fromName = msg.getString("fromName")
                main.post { listener?.onIncomingViewRequest(fromId, fromName) }
            }
            "view-response" -> {
                val fromId = msg.getString("fromId")
                val accept = msg.optBoolean("accept", false)
                main.post { listener?.onViewResponse(fromId, accept) }
            }
            "session-ended" -> {
                val peerId = msg.getString("peerId")
                main.post { listener?.onSessionEnded(peerId) }
            }
        }
    }

    fun listDevices() {
        socket?.send(JSONObject().put("type", "list").toString())
    }

    fun requestView(targetId: String) {
        socket?.send(JSONObject().put("type", "request-view").put("targetId", targetId).toString())
    }

    fun respondView(targetId: String, accept: Boolean) {
        socket?.send(
            JSONObject().put("type", "respond-view").put("targetId", targetId).put("accept", accept).toString()
        )
    }

    fun stopShare() {
        socket?.send(JSONObject().put("type", "stop-share").toString())
    }

    fun sendFrame(bytes: ByteArray) {
        socket?.send(ByteString.of(*bytes))
    }

    fun disconnect() {
        socket?.close(1000, "bye")
        socket = null
        state = State.DISCONNECTED
    }
}
