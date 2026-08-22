package com.apkbuilder.core.pwa

import com.apkbuilder.core.AssetLinksGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PwaManifestTest {

    private val goodManifestJson = """
        {
          "name": "テストアプリ",
          "short_name": "Test",
          "start_url": "/app/?utm=pwa",
          "display": "standalone",
          "theme_color": "#112233",
          "background_color": "#ffffff",
          "icons": [
            { "src": "icons/192.png", "sizes": "192x192", "type": "image/png" },
            { "src": "/icons/512.png", "sizes": "512x512", "type": "image/png", "purpose": "any" }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesAndResolvesRelativeUrls() {
        val manifest = PwaManifestParser.parse(goodManifestJson, "https://example.com/site/manifest.json")

        assertEquals("テストアプリ", manifest.displayName)
        assertEquals("https://example.com/app/?utm=pwa", manifest.startUrl)
        assertEquals("https://example.com/site/icons/192.png", manifest.icons[0].src)
        assertEquals("https://example.com/icons/512.png", manifest.icons[1].src)
        assertEquals(512, manifest.bestIcon()?.maxDimension())
    }

    @Test
    fun validGoodManifestPassesValidation() {
        val manifest = PwaManifestParser.parse(goodManifestJson, "https://example.com/manifest.json")
        val result = PwaManifestValidator.validate(manifest)
        assertTrue(result.installable, "issues: ${result.issues}")
        assertTrue(result.warnings.isEmpty(), "warnings: ${result.warnings}")
    }

    @Test
    fun flagsMissingNameAndIcons() {
        val bareJson = """{ "start_url": "/", "display": "browser" }"""
        val manifest = PwaManifestParser.parse(bareJson, "https://example.com/manifest.json")
        val result = PwaManifestValidator.validate(manifest)
        assertFalse(result.installable)
        assertTrue(result.issues.any { it.contains("name") })
        assertTrue(result.issues.any { it.contains("icons") })
        assertTrue(result.warnings.any { it.contains("browser") })
    }

    @Test
    fun assetLinksJsonHasExpectedShape() {
        val json = AssetLinksGenerator.generate("com.example.app", "AA:BB:CC")
        assertTrue(json.contains("\"package_name\": \"com.example.app\""))
        assertTrue(json.contains("\"sha256_cert_fingerprints\": [\"AA:BB:CC\"]"))
        assertTrue(json.contains("delegate_permission/common.handle_all_urls"))
    }
}
