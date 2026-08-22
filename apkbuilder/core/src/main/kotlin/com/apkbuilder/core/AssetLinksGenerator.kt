package com.apkbuilder.core

/**
 * Builds the `assetlinks.json` payload used by Android's Digital Asset
 * Links (Trusted Web Activity / App Links verification). The generated APK
 * cannot upload this to the target website itself — the site owner has to
 * publish it at `https://<domain>/.well-known/assetlinks.json`.
 */
object AssetLinksGenerator {
    fun generate(packageId: String, sha256CertFingerprint: String): String = """
        [{
          "relation": ["delegate_permission/common.handle_all_urls"],
          "target": {
            "namespace": "android_app",
            "package_name": "$packageId",
            "sha256_cert_fingerprints": ["$sha256CertFingerprint"]
          }
        }]
    """.trimIndent()
}
