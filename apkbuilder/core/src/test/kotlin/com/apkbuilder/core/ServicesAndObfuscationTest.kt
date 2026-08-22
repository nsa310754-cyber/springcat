package com.apkbuilder.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ServicesAndObfuscationTest {

    @Test
    fun assembleWithAdMobFirebaseAndObfuscation() {
        val templatePath = System.getenv("APKBUILDER_SERVICES_TEMPLATE_APK")
            ?: "/home/user/springcat/apkbuilder/template-services/app/build/outputs/apk/debug/app-debug.apk"
        val templateBytes = File(templatePath).readBytes()

        val packageId = "com.example.servicestest"
        val config = BuildConfig(
            appLabel = "サービステスト",
            packageId = packageId,
            versionName = "1.0",
            versionCode = 1,
            permissions = listOf("android.permission.INTERNET"),
            admobApplicationId = "ca-app-pub-3940256099942544~3347511713",
        )

        val plainHtml = "<!DOCTYPE html><html><body>秘密のゲーム本体</body></html>".toByteArray()
        val encryptedHtml = GameObfuscator.encryptGameHtml(plainHtml)

        val googleServicesJson = """
            {
              "project_info": { "project_number": "1", "project_id": "test-proj", "storage_bucket": "test-proj.appspot.com" },
              "client": [ {
                "client_info": { "mobilesdk_app_id": "1:1:android:x", "android_client_info": { "package_name": "$packageId" } },
                "api_key": [ { "current_key": "AIzaTest" } ]
              } ]
            }
        """.trimIndent()
        val firebaseConfig = FirebaseConfigParser.toRuntimeConfig(googleServicesJson, packageId)

        val unsigned = ApkAssembler.assemble(
            templateApkBytes = templateBytes,
            config = config,
            fileOverrides = mapOf(
                "assets/game.enc" to encryptedHtml,
                "assets/admob-config.json" to """{"bannerAdUnitId":"ca-app-pub-3940256099942544/6300978111"}""".toByteArray(),
                "assets/firebase-config.json" to firebaseConfig.json.toByteArray(),
            ),
            filesToOmit = setOf("assets/game.html"),
        )
        assertTrue(unsigned.isNotEmpty())

        val keystore = KeystoreGenerator.generate(commonName = "Services Test")
        val signed = ApkSigner.sign(unsigned, keystore)

        val outDir = File("/tmp/claude-0/-home-user-springcat/24da006e-d705-5624-92b2-89912239451d/scratchpad/apkinspect")
        outDir.mkdirs()
        File(outDir, "services-signed.apk").writeBytes(signed)
        println("wrote ${signed.size} bytes, exactPackageMatch=${firebaseConfig.exactPackageMatch}")
    }
}
