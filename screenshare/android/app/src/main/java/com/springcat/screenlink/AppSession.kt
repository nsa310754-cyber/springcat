package com.springcat.screenlink

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Holds this device's enrollment (server URL + admin-issued id/secret) and the
 * live peer roster. The id/secret are the credentials the relay server's
 * allowlist checks - only devices an admin enrolled server-side can connect,
 * so this is *not* self-signup.
 */
object AppSession {

    data class Peer(val id: String, val name: String, val sharing: Boolean)

    private lateinit var prefs: SharedPreferences
    private var initialized = false

    var roster: List<Peer> = emptyList()
        private set

    fun init(context: Context) {
        if (initialized) return
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "screenlink_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        initialized = true
    }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER, value).apply()

    var deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (existing != null) return existing
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var deviceSecret: String
        get() = prefs.getString(KEY_SECRET, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SECRET, value).apply()

    var deviceName: String
        get() = prefs.getString(KEY_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    val isEnrolled: Boolean
        get() = serverUrl.isNotBlank() && deviceSecret.isNotBlank()

    fun updateRoster(peers: List<Peer>) {
        roster = peers
    }

    private const val KEY_SERVER = "server_url"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_SECRET = "device_secret"
    private const val KEY_NAME = "device_name"
}
