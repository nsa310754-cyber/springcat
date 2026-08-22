package com.apkbuilder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FirebaseConfigParserTest {

    private val googleServicesJson = """
        {
          "project_info": {
            "project_number": "123456789012",
            "project_id": "my-firebase-proj",
            "storage_bucket": "my-firebase-proj.appspot.com"
          },
          "client": [
            {
              "client_info": {
                "mobilesdk_app_id": "1:123456789012:android:aaaa1111",
                "android_client_info": { "package_name": "com.other.app" }
              },
              "api_key": [ { "current_key": "AIzaOtherKey" } ]
            },
            {
              "client_info": {
                "mobilesdk_app_id": "1:123456789012:android:bbbb2222",
                "android_client_info": { "package_name": "com.example.mygame" }
              },
              "api_key": [ { "current_key": "AIzaMatchingKey" } ]
            }
          ],
          "configuration_version": "1"
        }
    """.trimIndent()

    @Test
    fun picksExactPackageMatchWhenPresent() {
        val result = FirebaseConfigParser.toRuntimeConfig(googleServicesJson, "com.example.mygame")
        assertTrue(result.exactPackageMatch)
        assertTrue(result.json.contains("\"mobilesdk_app_id\":\"1:123456789012:android:bbbb2222\""))
        assertTrue(result.json.contains("\"api_key\":\"AIzaMatchingKey\""))
        assertTrue(result.json.contains("\"project_id\":\"my-firebase-proj\""))
        assertTrue(result.json.contains("\"storage_bucket\":\"my-firebase-proj.appspot.com\""))
        assertTrue(result.json.contains("\"gcm_sender_id\":\"123456789012\""))
    }

    @Test
    fun fallsBackToFirstClientWhenNoMatch() {
        val result = FirebaseConfigParser.toRuntimeConfig(googleServicesJson, "com.unregistered.app")
        assertEquals(false, result.exactPackageMatch)
        assertTrue(result.json.contains("\"mobilesdk_app_id\":\"1:123456789012:android:aaaa1111\""))
    }
}
