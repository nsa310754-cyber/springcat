package com.apkbuilder.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtifactAnalyzerTest {

    private fun asset(name: String): File =
        listOf(
            File("../app/src/main/assets/$name"),
            File("app/src/main/assets/$name"),
            File("/home/user/springcat/apkbuilder/app/src/main/assets/$name"),
        ).first { it.exists() }

    @Test
    fun analyzesTemplateApk() {
        val info = ArtifactAnalyzer.analyze(asset("template.apk").readBytes())
        assertEquals("APK", info.kind)
        assertEquals("com.apkbuilder.template", info.packageId)
        assertEquals(24, info.minSdk)
        assertTrue(info.dexCount >= 1, "expected at least one dex")
        assertTrue(info.fileCount > 0)
        assertTrue(info.largestEntries.isNotEmpty())
        assertTrue(info.totalUncompressedSize > 0)
    }

    @Test
    fun analyzesTemplateAab() {
        val info = ArtifactAnalyzer.analyze(asset("template.aab").readBytes())
        assertEquals("AAB", info.kind)
        assertEquals("com.apkbuilder.template", info.packageId)
        assertEquals("1.0", info.versionName)
        assertTrue(info.fileCount > 0)
        assertTrue(info.largestEntries.isNotEmpty())
    }

    @Test
    fun analyzesServicesApkPermissions() {
        val info = ArtifactAnalyzer.analyze(asset("template-services.apk").readBytes())
        assertEquals("APK", info.kind)
        assertTrue(info.permissions.any { it == "android.permission.INTERNET" }, "perms: ${info.permissions}")
    }
}
