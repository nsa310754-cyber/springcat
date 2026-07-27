package com.springcat.apkdoctor.apk

import com.android.apksig.ApkVerifier
import java.io.File

/** Attribute lookup by name, for attributes without a well-known resource ID. */
fun AxmlElement.androidAttr(name: String): AxmlAttribute? =
    attributes.firstOrNull { it.isAndroid && it.name == name }

/** The parts of `AndroidManifest.xml` that decide whether an APK can install. */
data class ManifestInfo(
    val packageName: String?,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val maxSdk: Int?,
    val testOnly: Boolean,
    val debuggable: Boolean,
    val sharedUserId: String?,
    val splitName: String?,
    val isFeatureSplit: Boolean,
    val isSplitRequired: Boolean,
    val requiredSplitTypes: String?,
) {
    val isSplit: Boolean get() = !splitName.isNullOrEmpty()

    /** `targetSdkVersion` defaults to `minSdkVersion` when it is not declared. */
    val effectiveTargetSdk: Int get() = targetSdk ?: minSdk ?: 1

    val effectiveMinSdk: Int get() = minSdk ?: 1

    companion object {
        fun from(document: AxmlDocument): ManifestInfo {
            val manifest = document.element("manifest")
            val usesSdk = document.element("uses-sdk")
            val application = document.element("application")

            val versionCode = manifest?.attr(Axml.Attr.VERSION_CODE)?.asInt()?.toLong() ?: 0L
            val versionCodeMajor = manifest?.attr(Axml.Attr.VERSION_CODE_MAJOR)?.asInt()?.toLong() ?: 0L

            return ManifestInfo(
                packageName = manifest?.attr("package")?.asString(),
                versionCode = (versionCodeMajor shl 32) or (versionCode and 0xFFFFFFFFL),
                versionName = manifest?.attr(Axml.Attr.VERSION_NAME)?.asString(),
                minSdk = usesSdk?.attr(Axml.Attr.MIN_SDK_VERSION)?.asInt(),
                targetSdk = usesSdk?.attr(Axml.Attr.TARGET_SDK_VERSION)?.asInt(),
                maxSdk = usesSdk?.attr(Axml.Attr.MAX_SDK_VERSION)?.asInt(),
                testOnly = application?.attr(Axml.Attr.TEST_ONLY)?.asBoolean() ?: false,
                debuggable = application?.attr(Axml.Attr.DEBUGGABLE)?.asBoolean() ?: false,
                sharedUserId = manifest?.attr(Axml.Attr.SHARED_USER_ID)?.asString(),
                splitName = manifest?.attr("split")?.asString(),
                isFeatureSplit = manifest?.androidAttr("isFeatureSplit")?.asBoolean() ?: false,
                isSplitRequired = manifest?.attr(Axml.Attr.IS_SPLIT_REQUIRED)?.asBoolean() ?: false,
                requiredSplitTypes = manifest?.attr(Axml.Attr.REQUIRED_SPLIT_TYPES)?.asString(),
            )
        }
    }
}

/** Which signature schemes an APK carries, and whether they actually verify. */
data class SignatureInfo(
    /** Verified *using* v1. False for a modern APK even when v1 files exist: the
     *  verifier stops at the highest scheme the platform range needs. */
    val v1: Boolean,
    val v2: Boolean,
    val v3: Boolean,
    /** JAR signature files are present, whether or not they were consulted. */
    val v1Present: Boolean,
    val verified: Boolean,
    val errors: List<String>,
    val certificateSha256: String?,
) {
    val hasAny: Boolean get() = v1 || v2 || v3 || v1Present

    val schemeNames: List<String>
        get() = buildList {
            if (v1 || v1Present) add("v1")
            if (v2) add("v2")
            if (v3) add("v3")
        }

    companion object {
        val NONE = SignatureInfo(false, false, false, false, false, emptyList(), null)
    }
}

/** A single APK on disk, parsed far enough to diagnose install failures. */
class ApkArchive private constructor(
    val file: File,
    val document: AxmlDocument?,
    val manifest: ManifestInfo?,
    val signature: SignatureInfo,
    val abis: Set<String>,
    val hasNativeLibraries: Boolean,
    /** Android 11+ refuses to install an APK whose `resources.arsc` is deflated. */
    val resourcesArscCompressed: Boolean,
    val zipError: String?,
) {
    val isReadable: Boolean get() = zipError == null && manifest != null

    companion object {
        private val ABI_PATTERN = Regex("^lib/([^/]+)/.+")

        fun read(file: File, verifySignature: Boolean = true): ApkArchive {
            val zipError = Zips.probe(file)
            if (zipError != null) {
                return ApkArchive(file, null, null, SignatureInfo.NONE, emptySet(), false, false, zipError)
            }

            val names = Zips.entryNames(file)
            val abis = names.mapNotNull { ABI_PATTERN.find(it)?.groupValues?.get(1) }.toSet()
            val hasNative = names.any { it.startsWith("lib/") && it.endsWith(".so") }
            val arscCompressed = Zips.isCompressed(file, "resources.arsc")
            val v1Present = names.any { it.startsWith("META-INF/") && it.uppercase().endsWith(".SF") }

            val manifestBytes = Zips.readEntry(file, "AndroidManifest.xml")
                ?: return ApkArchive(
                    file, null, null, SignatureInfo.NONE, abis, hasNative, arscCompressed,
                    "AndroidManifest.xml がありません",
                )

            val document = try {
                AxmlDocument.parse(manifestBytes)
            } catch (e: Exception) {
                return ApkArchive(
                    file, null, null, SignatureInfo.NONE, abis, hasNative, arscCompressed,
                    "AndroidManifest.xml を解析できません: ${e.message}",
                )
            }

            val signature = if (verifySignature) verify(file, v1Present) else SignatureInfo.NONE
            return ApkArchive(
                file, document, ManifestInfo.from(document), signature,
                abis, hasNative, arscCompressed, null,
            )
        }

        private fun verify(file: File, v1Present: Boolean): SignatureInfo = try {
            val result = ApkVerifier.Builder(file)
                // Checking from API 24 keeps v1-only APKs from being reported as
                // broken purely because the platform floor moved.
                .setMinCheckedPlatformVersion(24)
                .build()
                .verify()
            SignatureInfo(
                v1 = result.isVerifiedUsingV1Scheme,
                v2 = result.isVerifiedUsingV2Scheme,
                v3 = result.isVerifiedUsingV3Scheme,
                v1Present = v1Present,
                verified = result.isVerified,
                errors = result.allErrors.map { it.toString() },
                // Throws when nothing verified, which is exactly when we do not need it.
                certificateSha256 = runCatching {
                    result.signerCertificates.firstOrNull()?.let { Digests.sha256(it.encoded) }
                }.getOrNull(),
            )
        } catch (e: Exception) {
            SignatureInfo(false, false, false, v1Present, false, listOfNotNull(e.message), null)
        }
    }
}

object Digests {
    fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
