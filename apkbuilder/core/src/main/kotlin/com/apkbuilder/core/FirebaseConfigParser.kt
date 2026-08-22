package com.apkbuilder.core

import com.apkbuilder.core.json.MiniJson
import com.apkbuilder.core.json.arr
import com.apkbuilder.core.json.get
import com.apkbuilder.core.json.str

data class FirebaseRuntimeConfig(
    val json: String,
    /** true if a client entry in the file already matches the app's own packageId. */
    val exactPackageMatch: Boolean,
)

/**
 * Reduces a full `google-services.json` (which can list several registered
 * Android apps, each with its own OAuth/API-key block) down to the handful
 * of fields the template's `FirebaseOptions.Builder` actually needs, as a
 * small `firebase-config.json` written into the generated app's assets.
 * No `com.google.gms.google-services` Gradle plugin involved — this runs
 * entirely on-device, at APK-generation time.
 */
object FirebaseConfigParser {

    class ParseException(message: String) : Exception(message)

    fun toRuntimeConfig(googleServicesJson: String, packageId: String): FirebaseRuntimeConfig {
        val root = MiniJson.parse(googleServicesJson)
        val projectInfo = root["project_info"]
            ?: throw ParseException("google-services.json に project_info がありません")
        val projectId = projectInfo["project_id"].str()
            ?: throw ParseException("google-services.json に project_info.project_id がありません")
        val storageBucket = projectInfo["storage_bucket"].str()

        val clients = root["client"].arr()
        if (clients.isEmpty()) throw ParseException("google-services.json に client がありません")

        val exactMatch = clients.firstOrNull {
            it["client_info"]?.get("android_client_info")?.get("package_name").str() == packageId
        }
        val client = exactMatch ?: clients.first()

        val appId = client["client_info"]?.get("mobilesdk_app_id").str()
            ?: throw ParseException("google-services.json に client_info.mobilesdk_app_id がありません")
        val apiKey = client["api_key"].arr().firstOrNull()?.get("current_key").str()
            ?: throw ParseException("google-services.json に api_key.current_key がありません")

        val gcmSenderId = projectInfo["project_number"].str()

        val json = buildString {
            append("{")
            append("\"mobilesdk_app_id\":\"").append(escape(appId)).append("\",")
            append("\"api_key\":\"").append(escape(apiKey)).append("\",")
            append("\"project_id\":\"").append(escape(projectId)).append("\"")
            if (storageBucket != null) append(",\"storage_bucket\":\"").append(escape(storageBucket)).append("\"")
            if (gcmSenderId != null) append(",\"gcm_sender_id\":\"").append(escape(gcmSenderId)).append("\"")
            append("}")
        }
        return FirebaseRuntimeConfig(json, exactMatch != null)
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
