package site.ragdollp.permeditor

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.security.auth.x500.X500Principal

/**
 * Rebuilds an APK with permissions added/removed, then re-signs it. Ported
 * from apk-permission-manager/core.js + sign.js, but signing here delegates
 * to Google's own `apksig` library (v1+v2+v3) instead of a hand-rolled v1
 * signer, using an AndroidKeyStore-generated self-signed identity so the
 * private key never leaves the device's secure keystore.
 */
object ApkRebuilder {

    private const val KEY_ALIAS = "permeditor_signing_key"
    private val SIGNATURE_FILE_RE = Regex("^META-INF/[^/]+\\.(SF|RSA|DSA|EC)$", RegexOption.IGNORE_CASE)
    private val MANIFEST_MF_RE = Regex("^META-INF/MANIFEST\\.MF$", RegexOption.IGNORE_CASE)

    private fun isSignatureFile(name: String): Boolean =
        MANIFEST_MF_RE.matches(name) || SIGNATURE_FILE_RE.matches(name)

    data class Identity(val privateKey: PrivateKey, val certificate: X509Certificate)

    /** Reuses the same alias across runs so repeated edits of the same app share one signing identity. */
    fun getOrCreateIdentity(): Identity {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=apk-permission-editor"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(Date(0L))
                .setCertificateNotAfter(Date(4102444800000L)) // 2100-01-01
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
        }
        val privateKey = ks.getKey(KEY_ALIAS, null) as PrivateKey
        val cert = ks.getCertificateChain(KEY_ALIAS)[0] as X509Certificate
        return Identity(privateKey, cert)
    }

    data class Result(val outputApk: File, val certificate: X509Certificate)

    fun buildModifiedApk(
        inputApk: File,
        cacheDir: File,
        outputApk: File,
        remove: List<String>,
        add: List<Pair<String, String>>,
        progress: (String) -> Unit
    ): Result {
        val identity = getOrCreateIdentity()
        return buildModifiedApkWithIdentity(inputApk, cacheDir, outputApk, remove, add, identity, progress)
    }

    /**
     * Same as [buildModifiedApk] but takes an explicit signing identity
     * instead of generating one from AndroidKeyStore - lets the zip
     * rebuild + apksig integration be exercised from a plain JVM unit
     * test (AndroidKeyStore only exists on a real device).
     */
    fun buildModifiedApkWithIdentity(
        inputApk: File,
        cacheDir: File,
        outputApk: File,
        remove: List<String>,
        add: List<Pair<String, String>>,
        identity: Identity,
        progress: (String) -> Unit
    ): Result {
        progress("マニフェストを解析中…")
        val manifestBytes = ZipFile(inputApk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: error("AndroidManifest.xml が見つかりません。")
            zip.getInputStream(entry).readBytes()
        }
        val doc = Axml.parse(manifestBytes)
        remove.forEach { ManifestPerms.removePermission(doc, it) }
        add.forEach { (name, tag) -> ManifestPerms.addPermission(doc, name, tag) }
        val newManifestBytes = Axml.serialize(doc)

        progress("APKを再構築中…")
        val unsignedApk = File(cacheDir, "unsigned_${System.currentTimeMillis()}.apk")
        ZipFile(inputApk).use { zip ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(unsignedApk))).use { zos ->
                writeEntry(zos, "AndroidManifest.xml", newManifestBytes, ZipEntry.DEFLATED)
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    if (entry.name == "AndroidManifest.xml") continue
                    if (isSignatureFile(entry.name)) continue
                    val data = zip.getInputStream(entry).readBytes()
                    writeEntry(zos, entry.name, data, entry.method)
                }
            }
        }

        progress("署名を作成中（この端末内だけで行われます）…")
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "permeditor",
            identity.privateKey,
            listOf(identity.certificate)
        ).build()

        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(outputApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(24)
            .build()
            .sign()

        unsignedApk.delete()
        return Result(outputApk, identity.certificate)
    }

    private fun writeEntry(zos: ZipOutputStream, name: String, data: ByteArray, method: Int) {
        val ze = ZipEntry(name)
        ze.method = method
        if (method == ZipEntry.STORED) {
            ze.size = data.size.toLong()
            ze.compressedSize = data.size.toLong()
            val crc = CRC32()
            crc.update(data)
            ze.crc = crc.value
        }
        zos.putNextEntry(ze)
        zos.write(data)
        zos.closeEntry()
    }
}
