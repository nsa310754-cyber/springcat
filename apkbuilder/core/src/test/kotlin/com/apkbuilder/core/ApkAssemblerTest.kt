package com.apkbuilder.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ApkAssemblerTest {

    @Test
    fun assembleAndSignAgainstRealTemplate() {
        val templatePath = System.getenv("APKBUILDER_TEMPLATE_APK")
            ?: "/home/user/springcat/apkbuilder/template/app/build/outputs/apk/debug/app-debug.apk"
        val templateBytes = File(templatePath).readBytes()

        val config = BuildConfig(
            appLabel = "テスト忍者ゲーム",
            packageId = "com.example.testninjagame",
            versionName = "2.3.0",
            versionCode = 7,
            permissions = listOf(
                "android.permission.INTERNET",
                "android.permission.VIBRATE",
                "android.permission.CAMERA",
            ),
        )

        val gameHtml = """
            <!DOCTYPE html><html><head><meta charset="utf-8"></head>
            <body>忍者ゲーム</body></html>
        """.trimIndent().toByteArray()

        val unsigned = ApkAssembler.assemble(
            templateApkBytes = templateBytes,
            config = config,
            fileOverrides = mapOf(
                "assets/game.html" to gameHtml,
                "res/mipmap-mdpi-v4/ic_launcher.png" to fakePng(),
                "res/mipmap-mdpi-v4/ic_launcher_round.png" to fakePng(),
                "res/mipmap-hdpi-v4/ic_launcher.png" to fakePng(),
                "res/mipmap-hdpi-v4/ic_launcher_round.png" to fakePng(),
                "res/mipmap-xhdpi-v4/ic_launcher.png" to fakePng(),
                "res/mipmap-xhdpi-v4/ic_launcher_round.png" to fakePng(),
                "res/mipmap-xxhdpi-v4/ic_launcher.png" to fakePng(),
                "res/mipmap-xxhdpi-v4/ic_launcher_round.png" to fakePng(),
                "res/mipmap-xxxhdpi-v4/ic_launcher.png" to fakePng(),
                "res/mipmap-xxxhdpi-v4/ic_launcher_round.png" to fakePng(),
            ),
        )
        assertTrue(unsigned.isNotEmpty())

        val keystore = KeystoreGenerator.generate(commonName = "APK Builder Test")
        val signed = ApkSigner.sign(unsigned, keystore.signingKey())
        assertTrue(signed.isNotEmpty())

        val outDir = File("/tmp/claude-0/-home-user-springcat/24da006e-d705-5624-92b2-89912239451d/scratchpad/apkinspect")
        outDir.mkdirs()
        File(outDir, "generated-signed.apk").writeBytes(signed)
        File(outDir, "generated.keystore").writeBytes(keystore.pkcs12Bytes)
        File(outDir, "generated-keystore-info.txt").writeText(
            "alias=${keystore.alias}\nstorePassword=${keystore.storePassword}\nkeyPassword=${keystore.keyPassword}\n",
        )
    }

    private fun fakePng(): ByteArray {
        // Minimal valid 1x1 PNG (transparent). Real icon bytes come from
        // Android's Bitmap encoder in the actual app; this is enough to
        // exercise the zip-replacement path in this JVM-only test.
        return byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
            0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
            0x78.toByte(), 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,
            0x0D, 0x0A, 0x2D, 0xB4.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
    }
}
