package com.springcat.screenlink.relay

import com.springcat.screenlink.AppSession
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/**
 * The allowlist for this device's *embedded* relay server: only devices an
 * admin adds here (on this phone/tablet) can ever authenticate against it.
 * Stored in the same encrypted prefs file as the rest of the app's secrets.
 */
object DeviceAllowlist {

    data class Entry(val id: String, val secret: String, val name: String, val group: String)

    private const val KEY = "server_allowlist_json"
    private val random = SecureRandom()

    fun list(): List<Entry> {
        val raw = AppSession.rawPref(KEY) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getString("id"), o.getString("secret"), o.getString("name"), o.getString("group"))
        }
    }

    fun add(name: String, group: String): Entry {
        val entry = Entry(UUID.randomUUID().toString(), randomHex(24), name, group)
        save(list() + entry)
        return entry
    }

    fun remove(id: String) {
        save(list().filterNot { it.id == id })
    }

    fun findByCredentials(id: String, secret: String): Entry? {
        val entry = list().find { it.id == id } ?: return null
        val match = java.security.MessageDigest.isEqual(
            entry.secret.toByteArray(Charsets.UTF_8), secret.toByteArray(Charsets.UTF_8)
        )
        return if (match) entry else null
    }

    private fun save(entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("secret", e.secret)
                    .put("name", e.name)
                    .put("group", e.group)
            )
        }
        AppSession.putRawPref(KEY, arr.toString())
    }

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
