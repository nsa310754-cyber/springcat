package com.springcat.screenlink.relay

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The relay server itself, running inside the app on whichever device an
 * admin turns into "the server" - no separate machine required. Protocol and
 * scope match screenshare/server/server.js exactly: pre-enrolled devices
 * only (DeviceAllowlist), 1:1 frame relay, no remote input/command channel.
 */
class EmbeddedRelayServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {

    private data class ConnInfo(val ws: WebSocket, val name: String, val group: String)

    private val deviceIdByConn = ConcurrentHashMap<WebSocket, String>()
    private val online = ConcurrentHashMap<String, ConnInfo>()
    private val sessions = ConcurrentHashMap<String, String>()
    private val helloTimers = ConcurrentHashMap<WebSocket, ScheduledFuture<*>>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    override fun onStart() {
        connectionLostTimeout = 30
        Log.i(TAG, "embedded relay listening on port $port")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val timer = scheduler.schedule({ conn.close(4008, "hello timeout") }, HELLO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        helloTimers[conn] = timer
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val deviceId = deviceIdByConn[conn]
        if (deviceId == null) {
            handleHello(conn, message)
            return
        }
        val msg = runCatching { JSONObject(message) }.getOrNull() ?: return
        val self = online[deviceId] ?: return
        when (msg.optString("type")) {
            "list" -> safeSend(conn, JSONObject().put("type", "roster").put("devices", rosterFor(deviceId)))
            "request-view" -> {
                val targetId = msg.optString("targetId")
                val target = online[targetId]
                if (target == null || target.group != self.group) {
                    safeSend(
                        conn,
                        JSONObject().put("type", "view-response").put("fromId", targetId)
                            .put("accept", false).put("reason", "offline")
                    )
                } else {
                    safeSend(
                        target.ws,
                        JSONObject().put("type", "incoming-view-request")
                            .put("fromId", deviceId).put("fromName", self.name)
                    )
                }
            }
            "respond-view" -> {
                val targetId = msg.optString("targetId")
                val viewer = online[targetId] ?: return
                val accept = msg.optBoolean("accept", false)
                if (accept) {
                    sessions[deviceId] = targetId
                    sessions[targetId] = deviceId
                }
                safeSend(
                    viewer.ws,
                    JSONObject().put("type", "view-response").put("fromId", deviceId).put("accept", accept)
                )
            }
            "stop-share" -> endSession(deviceId, notifyPeer = true)
        }
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        val deviceId = deviceIdByConn[conn] ?: return
        val peerId = sessions[deviceId] ?: return
        val peer = online[peerId] ?: return
        peer.ws.send(message.duplicate())
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        helloTimers.remove(conn)?.cancel(false)
        val deviceId = deviceIdByConn.remove(conn) ?: return
        val info = online.remove(deviceId)
        endSession(deviceId, notifyPeer = true)
        if (info != null) broadcastRoster(info.group)
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.w(TAG, "connection error", ex)
    }

    fun peerCount(): Int = online.size

    override fun stop(timeout: Int) {
        scheduler.shutdownNow()
        super.stop(timeout)
    }

    private fun handleHello(conn: WebSocket, text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull()
        if (msg == null || msg.optString("type") != "hello") {
            conn.close(4000, "bad hello"); return
        }
        val id = msg.optString("id")
        val secret = msg.optString("secret")
        if (id.isBlank() || secret.isBlank()) {
            conn.close(4000, "bad hello"); return
        }
        val entry = DeviceAllowlist.findByCredentials(id, secret)
        if (entry == null) {
            conn.close(4001, "not permitted"); return
        }
        if (online.containsKey(id)) {
            conn.close(4002, "already connected elsewhere"); return
        }
        helloTimers.remove(conn)?.cancel(false)
        deviceIdByConn[conn] = id
        online[id] = ConnInfo(conn, entry.name, entry.group)
        safeSend(
            conn,
            JSONObject().put("type", "hello-ok").put("id", id).put("name", entry.name).put("group", entry.group)
        )
        broadcastRoster(entry.group)
    }

    private fun rosterFor(deviceId: String): JSONArray {
        val self = online[deviceId] ?: return JSONArray()
        val arr = JSONArray()
        for ((id, info) in online) {
            if (id == deviceId || info.group != self.group) continue
            arr.put(JSONObject().put("id", id).put("name", info.name).put("sharing", !sessions.containsKey(id)))
        }
        return arr
    }

    private fun broadcastRoster(group: String) {
        for ((id, info) in online) {
            if (info.group != group) continue
            safeSend(info.ws, JSONObject().put("type", "roster").put("devices", rosterFor(id)))
        }
    }

    private fun endSession(deviceId: String, notifyPeer: Boolean) {
        val peerId = sessions.remove(deviceId) ?: return
        sessions.remove(peerId)
        if (notifyPeer) {
            online[peerId]?.let {
                safeSend(it.ws, JSONObject().put("type", "session-ended").put("peerId", deviceId))
            }
        }
    }

    private fun safeSend(ws: WebSocket, obj: JSONObject) {
        if (ws.isOpen) runCatching { ws.send(obj.toString()) }
    }

    companion object {
        private const val TAG = "EmbeddedRelayServer"
        private const val HELLO_TIMEOUT_MS = 5000L
    }
}
